package com.lxpure.music.core.download

import android.content.Context
import android.os.Environment
import android.util.Log
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.data.local.DownloadDao
import com.lxpure.music.data.local.DownloadEntity
import com.lxpure.music.data.local.roomJson
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val httpClient: OkHttpClient,
    private val userApiManager: UserApiManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            recoverInterruptedDownloads()
        }
    }

    val downloadDir: File
        get() {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val dir = File(baseDir, "LXPure")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun recoverInterruptedDownloads() = withContext(Dispatchers.IO) {
        runCatching {
            val interrupted = downloadDao.getInProgressOnce()
            interrupted.forEach { entity ->
                if (!activeJobs.containsKey(entity.trackId)) {
                    entity.filePath?.takeIf { it.isNotBlank() }?.let { path ->
                        runCatching { File(path).delete() }
                    }
                    downloadDao.upsert(
                        entity.copy(
                            status = DownloadStatus.FAILED.name,
                            progress = 0f,
                            filePath = null,
                            totalBytes = 0L,
                            errorMessage = "下载已中断，请重试",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            // Clean stray .part files left by abrupt process kills when idle
            if (activeJobs.isEmpty()) {
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".part", ignoreCase = true)) {
                        runCatching { file.delete() }
                    }
                }
            }
        }.onFailure { err ->
            Log.w("DownloadManager", "Failed to recover interrupted downloads: ${err.message}")
        }
    }

    fun enqueueDownload(track: Track, preferredQuality: AudioQuality, onFeedback: ((String) -> Unit)? = null) {
        if (track.isLocal) {
            onFeedback?.invoke("本地音乐无需下载")
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var partFile: File? = null
            var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
            try {
                val previousPath = downloadDao.getById(track.id)?.filePath
                downloadDao.upsert(
                    DownloadEntity(
                        trackId = track.id,
                        trackJson = roomJson.encodeToString(track),
                        quality = preferredQuality.lxType,
                        status = DownloadStatus.QUEUED.name,
                        progress = 0f,
                        filePath = null,
                        totalBytes = 0L,
                        errorMessage = null
                    )
                )

                val resolved = userApiManager.resolveStream(track, preferredQuality, allowCrossPlatformFallback = true)
                    .getOrElse { err ->
                        if (err is CancellationException || !currentCoroutineContext().isActive) {
                            throw CancellationException("Download cancelled during resolve")
                        }
                        markFailed(track, preferredQuality, err.message ?: "无法获取下载链接")
                        onFeedback?.invoke(err.message ?: "下载失败")
                        return@launch
                    }

                ensureActive()

                val request = Request.Builder()
                    .url(resolved.url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val call = httpClient.newCall(request)
                cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

                call.execute().use { resp ->
                    ensureActive()
                    if (!resp.isSuccessful) error("服务器返回 HTTP ${resp.code}")
                    val contentType = resp.header("Content-Type")
                    if (isDisallowedAudioContentType(contentType)) {
                        error("音源返回了非音频响应 (${contentType.orEmpty().substringBefore(';').trim()})")
                    }
                    val body = resp.body ?: error("响应体为空")
                    val total = body.contentLength().coerceAtLeast(0L)
                    val ext = inferAudioExtension(
                        urlPath = resp.request.url.encodedPath,
                        contentType = contentType,
                        contentDisposition = resp.header("Content-Disposition"),
                        quality = resolved.quality
                    )
                    val safeName = sanitizeFileName("${track.artist} - ${track.title}")
                    val idSuffix = sanitizeFileName(track.id).takeLast(8).ifBlank { "id" }
                    val outFile = File(downloadDir, "${safeName}_${idSuffix}.$ext")
                    val tempPart = File(downloadDir, "${outFile.name}.${System.nanoTime()}.part")
                    partFile = tempPart

                    var downloaded = 0L
                    var lastEmit = 0L

                    downloadDao.upsert(
                        DownloadEntity(
                            trackId = track.id,
                            trackJson = roomJson.encodeToString(track),
                            quality = resolved.quality.lxType,
                            status = DownloadStatus.DOWNLOADING.name,
                            progress = 0.01f,
                            filePath = null,
                            totalBytes = total,
                            errorMessage = null
                        )
                    )

                    body.byteStream().use { input ->
                        FileOutputStream(tempPart).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 300L) {
                                    lastEmit = now
                                    val progress = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else 0.5f
                                    downloadDao.upsert(
                                        DownloadEntity(
                                            trackId = track.id,
                                            trackJson = roomJson.encodeToString(track),
                                            quality = resolved.quality.lxType,
                                            status = DownloadStatus.DOWNLOADING.name,
                                            progress = progress,
                                            filePath = null,
                                            totalBytes = total.coerceAtLeast(downloaded),
                                            errorMessage = null
                                        )
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    ensureActive()
                    if (downloaded <= 1024L) {
                        error("音频文件异常（仅 ${downloaded} 字节）")
                    }
                    if (total > 0L && downloaded < total) {
                        error("下载不完整（已接收 ${downloaded}/${total} 字节）")
                    }

                    if (outFile.exists()) {
                        outFile.delete()
                    }
                    val moved = tempPart.renameTo(outFile)
                    if (!moved) {
                        tempPart.copyTo(outFile, overwrite = true)
                        tempPart.delete()
                    }
                    partFile = null

                    if (!previousPath.isNullOrBlank() && previousPath != outFile.absolutePath) {
                        runCatching { File(previousPath).delete() }
                    }

                    ensureActive()
                    val completedTrack = track.copy(cachedFilePath = outFile.absolutePath)
                    downloadDao.upsert(
                        DownloadEntity(
                            trackId = track.id,
                            trackJson = roomJson.encodeToString(completedTrack),
                            quality = resolved.quality.lxType,
                            status = DownloadStatus.COMPLETED.name,
                            progress = 1f,
                            filePath = outFile.absolutePath,
                            totalBytes = outFile.length(),
                            errorMessage = null
                        )
                    )
                    onFeedback?.invoke("已离线保存《${track.title}》(${resolved.quality.badge})")
                }
            } catch (ce: CancellationException) {
                partFile?.let { runCatching { it.delete() } }
                throw ce
            } catch (t: Throwable) {
                partFile?.let { runCatching { it.delete() } }
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Download cancelled")
                }
                markFailed(track, preferredQuality, t.message ?: "下载中断")
                onFeedback?.invoke("下载失败：${t.message ?: "网络异常"}")
            } finally {
                cancelHandle?.dispose()
                activeJobs.remove(track.id, currentCoroutineContext()[Job])
            }
        }

        if (activeJobs.putIfAbsent(track.id, job) != null) {
            job.cancel()
            onFeedback?.invoke("《${track.title}》正在下载中")
            return
        }
        job.start()
    }

    suspend fun getLocalCachedPath(trackId: String): String? = withContext(Dispatchers.IO) {
        val record = downloadDao.getById(trackId) ?: return@withContext null
        if (record.status == DownloadStatus.COMPLETED.name) {
            val path = record.filePath
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.isFile && file.length() > 1024L) {
                    return@withContext file.absolutePath
                }
            }
            // Self-heal stale COMPLETED records whose underlying file was removed or truncated
            downloadDao.delete(trackId)
        }
        null
    }

    suspend fun deleteDownload(trackId: String) = withContext(Dispatchers.IO) {
        activeJobs.remove(trackId)?.cancelAndJoin()
        val record = downloadDao.getById(trackId)
        record?.filePath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
        }
        downloadDao.delete(trackId)
    }

    suspend fun calculateDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        downloadDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part", ignoreCase = true) }
            ?.sumOf { it.length() } ?: 0L
    }

    private suspend fun markFailed(track: Track, quality: AudioQuality, reason: String) {
        downloadDao.upsert(
            DownloadEntity(
                trackId = track.id,
                trackJson = roomJson.encodeToString(track),
                quality = quality.lxType,
                status = DownloadStatus.FAILED.name,
                progress = 0f,
                filePath = null,
                totalBytes = 0L,
                errorMessage = reason
            )
        )
    }

    companion object {
        private val INVALID_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|]")
        private val CONTROL_CHARS_REGEX = Regex("\\p{Cntrl}")
        private val CONTENT_DISPOSITION_EXT_REGEX = Regex("filename\\*?=['\"]?[^'\"\\s;]+\\.([A-Za-z0-9]{2,5})['\"]?", RegexOption.IGNORE_CASE)
        private val SUPPORTED_EXTENSIONS = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav")

        fun isDisallowedAudioContentType(contentType: String?): Boolean {
            val normalized = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (normalized.isEmpty()) return false
            return normalized.startsWith("text/") ||
                normalized.startsWith("image/") ||
                normalized == "application/json" ||
                normalized == "application/xml" ||
                normalized == "application/xhtml+xml" ||
                normalized == "application/javascript" ||
                normalized == "application/x-javascript"
        }

        fun inferAudioExtension(
            urlPath: String?,
            contentType: String?,
            contentDisposition: String?,
            quality: AudioQuality
        ): String {
            val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            when {
                mime.contains("flac") -> return "flac"
                mime.contains("mp4") || mime.contains("m4a") -> return "m4a"
                mime.contains("ogg") || mime.contains("opus") -> return "ogg"
                mime.contains("aac") -> return "aac"
                mime.contains("wav") || mime.contains("wave") -> return "wav"
                mime == "audio/mpeg" || mime == "audio/mp3" -> return "mp3"
            }
            contentDisposition?.let { disp ->
                val ext = CONTENT_DISPOSITION_EXT_REGEX.find(disp)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
                if (ext != null && ext in SUPPORTED_EXTENSIONS) return ext
            }
            val pathExt = urlPath?.substringAfterLast('/')
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
            if (pathExt != null && pathExt in SUPPORTED_EXTENSIONS) {
                return pathExt
            }
            return when (quality) {
                AudioQuality.FLAC, AudioQuality.FLAC24 -> "flac"
                else -> "mp3"
            }
        }

        fun sanitizeFileName(raw: String): String {
            val cleaned = raw
                .replace(CONTROL_CHARS_REGEX, "")
                .replace(INVALID_FILENAME_REGEX, "_")
                .trim()
                .trimStart('.')
                .trim()
                .take(80)
                .trimEnd('.', ' ')
            return cleaned.ifBlank { "track" }
        }
    }
}
