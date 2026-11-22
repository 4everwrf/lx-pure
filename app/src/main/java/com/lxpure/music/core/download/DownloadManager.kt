package com.lxpure.music.core.download

import android.content.Context
import android.os.Environment
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.data.local.DownloadDao
import com.lxpure.music.data.local.DownloadEntity
import com.lxpure.music.data.local.roomJson
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val httpClient: OkHttpClient,
    private val userApiManager: UserApiManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val downloadDir: File
        get() {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "LXPure")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    fun enqueueDownload(track: Track, preferredQuality: AudioQuality, onFeedback: ((String) -> Unit)? = null) {
        if (track.isLocal) {
            onFeedback?.invoke("本地音乐无需下载")
            return
        }
        if (activeJobs.containsKey(track.id)) {
            onFeedback?.invoke("《${track.title}》正在下载中")
            return
        }

        val job = scope.launch {
            try {
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
                        markFailed(track, preferredQuality, err.message ?: "无法获取下载链接")
                        onFeedback?.invoke(err.message ?: "下载失败")
                        return@launch
                    }

                val ext = when {
                    resolved.quality == AudioQuality.FLAC || resolved.quality == AudioQuality.FLAC24 -> "flac"
                    resolved.url.contains(".flac", ignoreCase = true) -> "flac"
                    resolved.url.contains(".m4a", ignoreCase = true) -> "m4a"
                    else -> "mp3"
                }
                val safeName = "${track.artist} - ${track.title}"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .take(80)
                val outFile = File(downloadDir, "${safeName}_${track.id.takeLast(6)}.$ext")

                val request = Request.Builder()
                    .url(resolved.url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("服务器返回 HTTP ${resp.code}")
                    val body = resp.body ?: error("响应体为空")
                    val total = body.contentLength().coerceAtLeast(0L)
                    var downloaded = 0L
                    var lastEmit = 0L

                    downloadDao.upsert(
                        DownloadEntity(
                            trackId = track.id,
                            trackJson = roomJson.encodeToString(track),
                            quality = resolved.quality.lxType,
                            status = DownloadStatus.DOWNLOADING.name,
                            progress = 0.01f,
                            filePath = outFile.absolutePath,
                            totalBytes = total,
                            errorMessage = null
                        )
                    )

                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= 250L) {
                                    lastEmit = now
                                    val progress = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else 0.5f
                                    downloadDao.upsert(
                                        DownloadEntity(
                                            trackId = track.id,
                                            trackJson = roomJson.encodeToString(track),
                                            quality = resolved.quality.lxType,
                                            status = DownloadStatus.DOWNLOADING.name,
                                            progress = progress,
                                            filePath = outFile.absolutePath,
                                            totalBytes = total.coerceAtLeast(downloaded),
                                            errorMessage = null
                                        )
                                    )
                                }
                            }
                        }
                    }

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
            } catch (t: Throwable) {
                markFailed(track, preferredQuality, t.message ?: "下载中断")
                onFeedback?.invoke("下载失败：${t.message ?: "网络异常"}")
            } finally {
                activeJobs.remove(track.id)
            }
        }
        activeJobs[track.id] = job
    }

    suspend fun getLocalCachedPath(trackId: String): String? = withContext(Dispatchers.IO) {
        val record = downloadDao.getById(trackId) ?: return@withContext null
        if (record.status == DownloadStatus.COMPLETED.name && !record.filePath.isNullOrBlank()) {
            val file = File(record.filePath)
            if (file.exists() && file.length() > 1024L) {
                return@withContext file.absolutePath
            }
        }
        null
    }

    suspend fun deleteDownload(trackId: String) = withContext(Dispatchers.IO) {
        activeJobs.remove(trackId)?.cancel()
        val record = downloadDao.getById(trackId)
        record?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }
        downloadDao.delete(trackId)
    }

    suspend fun calculateDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        downloadDir.listFiles()?.sumOf { it.length() } ?: 0L
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
}
