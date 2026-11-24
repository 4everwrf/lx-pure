package com.lxpure.music.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.lxpure.music.BuildConfig
import com.lxpure.music.core.musicsdk.asBooleanOrNull
import com.lxpure.music.core.musicsdk.asJsonArrayOrNull
import com.lxpure.music.core.musicsdk.asJsonObjectOrNull
import com.lxpure.music.core.musicsdk.asLongOrNull
import com.lxpure.music.core.musicsdk.asStringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.net.ssl.SSLException

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val assetSizeBytes: Long,
    val expectedSha256: String? = null
)

data class UpdateDownloadRoute(
    val label: String,
    val url: String
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Checking(val manual: Boolean) : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(
        val info: AppUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float,
        val routeLabel: String = "GitHub 直连"
    ) : AppUpdateState
    data class Verifying(val info: AppUpdateInfo) : AppUpdateState
    data class ReadyToInstall(val info: AppUpdateInfo, val apkFile: File) : AppUpdateState
    data class Error(val info: AppUpdateInfo?, val message: String) : AppUpdateState
}

class AppUpdateManager(
    private val context: Context,
    baseHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "AppUpdateManager"
        const val GITHUB_OWNER = "4everwrf"
        const val GITHUB_REPO = "lx-pure"
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        const val LATEST_RELEASE_WEB_URL =
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val MIN_VALID_APK_BYTES = 100L * 1024L
        private const val MAX_VALID_APK_BYTES = 200L * 1024L * 1024L
        private val COMMIT_HASH_SUFFIX_REGEX = Regex("\\s*\\([0-9a-fA-F]{6,10}\\)\\s*$")
        private val CONVENTIONAL_PREFIX_REGEX =
            Regex("^(feat|fix|perf|refactor|chore|style|docs|test|build)(\\([^)]+\\))?:\\s*", RegexOption.IGNORE_CASE)
        private val BODY_SHA256_REGEX = Regex("SHA-?256\\s*[:：]\\s*`?([0-9a-fA-F]{64})`?", RegexOption.IGNORE_CASE)
        private val SHA256SUMS_LINE_REGEX = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$")

        private val GITHUB_MIRROR_PREFIXES = listOf(
            "镜像加速 1" to "https://gh-proxy.org/",
            "镜像加速 2" to "https://ghfast.top/"
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun normalizeVersion(raw: String?): String {
            if (raw.isNullOrBlank()) return "0.0.0"
            val trimmed = raw.trim().trimStart('v', 'V')
            val withoutMeta = trimmed.substringBefore('+').substringBefore('-').trim()
            return withoutMeta.ifBlank { "0.0.0" }
        }

        /**
         * Compares two semantic version strings numerically.
         * Returns > 0 if [remote] is newer than [current], 0 if equal, < 0 if older.
         */
        fun compareVersions(remote: String?, current: String?): Int {
            val remoteParts = parseNumericParts(normalizeVersion(remote))
            val currentParts = parseNumericParts(normalizeVersion(current))
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r.compareTo(c)
            }
            return 0
        }

        fun isNewerVersion(current: String?, remote: String?): Boolean =
            compareVersions(remote, current) > 0

        private fun parseNumericParts(normalized: String): List<Int> {
            return normalized.split('.').mapNotNull { segment ->
                val digits = segment.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }.ifEmpty { listOf(0) }
        }

        fun extractTagFromReleaseUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val marker = "/releases/tag/"
            val idx = url.indexOf(marker, ignoreCase = true)
            if (idx < 0) return null
            val tag = url.substring(idx + marker.length)
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore('/')
                .trim()
            return tag.takeIf { it.isNotBlank() }
        }

        fun extractSha256Digest(rawDigest: String?): String? {
            if (rawDigest.isNullOrBlank()) return null
            val cleaned = rawDigest.trim()
                .removePrefix("sha256:")
                .removePrefix("SHA256:")
                .trim()
            return cleaned.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
                ?.lowercase(Locale.ROOT)
        }

        fun extractSha256FromReleaseBody(body: String?, assetName: String): String? {
            if (body.isNullOrBlank() || assetName.isBlank()) return null
            val lines = body.lines()
            for (i in lines.indices) {
                if (lines[i].contains(assetName, ignoreCase = true)) {
                    val end = minOf(lines.lastIndex, i + 3)
                    for (j in i..end) {
                        val match = BODY_SHA256_REGEX.find(lines[j])
                        if (match != null) {
                            return extractSha256Digest(match.groupValues[1])
                        }
                    }
                }
            }
            return null
        }

        fun extractSha256FromSumsText(sumsText: String?, assetName: String): String? {
            if (sumsText.isNullOrBlank() || assetName.isBlank()) return null
            for (rawLine in sumsText.lineSequence()) {
                val match = SHA256SUMS_LINE_REGEX.matchEntire(rawLine.trim()) ?: continue
                val fileName = match.groupValues[2].trim().substringAfterLast('/')
                if (fileName.equals(assetName, ignoreCase = true)) {
                    return extractSha256Digest(match.groupValues[1])
                }
            }
            return null
        }

        fun isDisallowedUpdateContentType(contentType: String?): Boolean {
            val normalized = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (normalized.isEmpty()) return false
            return normalized.startsWith("text/") ||
                normalized.startsWith("image/") ||
                normalized == "application/json" ||
                normalized == "application/xml" ||
                normalized == "application/xhtml+xml" ||
                normalized == "application/javascript"
        }

        fun isValidZipOrApkMagic(header: ByteArray): Boolean =
            header.size >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()

        /**
         * Builds ordered download candidates: direct GitHub first (fastest when proxy is active
         * or outside mainland China), followed by mirror accelerators ONLY when [allowMirrors]
         * is true (i.e. when a trusted GitHub SHA-256 digest is available to verify mirror output).
         */
        fun buildDownloadRoutes(originalUrl: String, allowMirrors: Boolean = true): List<UpdateDownloadRoute> {
            val clean = originalUrl.trim()
            if (clean.isBlank()) return emptyList()
            val routes = mutableListOf(UpdateDownloadRoute("GitHub 直连", clean))
            if (allowMirrors && clean.startsWith("https://github.com/", ignoreCase = true)) {
                GITHUB_MIRROR_PREFIXES.forEach { (label, prefix) ->
                    routes.add(UpdateDownloadRoute(label, "$prefix$clean"))
                }
            }
            return routes
        }

        fun formatDownloadError(error: Throwable?, triedMirrors: Boolean = true): String {
            val rawMsg = error?.message.orEmpty()
            val lower = rawMsg.lowercase(Locale.ROOT)
            val isTimeout = error is SocketTimeoutException ||
                lower.contains("timeout") ||
                lower.contains("timed out")
            val isUnreachable = error is UnknownHostException ||
                error is ConnectException ||
                error is SSLException ||
                lower.contains("unable to resolve host") ||
                lower.contains("failed to connect") ||
                lower.contains("connection reset") ||
                lower.contains("connection refused") ||
                lower.contains("handshake")
            val isMirrorPageError = lower.contains("非安装包响应")
            val isVerifyError = lower.contains("校验")

            return when {
                isMirrorPageError ->
                    "下载通道返回了异常页面而非安装包，请重试或点击「浏览器下载」。"
                isVerifyError ->
                    "安装包校验未通过，请点击「重试下载」或通过「浏览器下载」手动安装。"
                isTimeout && triedMirrors ->
                    "连接 GitHub 下载超时（直连与加速通道均未响应）。国内网络请检查代理/加速后重试，或点击「浏览器下载」。"
                isTimeout ->
                    "连接 GitHub 下载超时，请检查网络或开启代理后重试，也可点击「浏览器下载」。"
                isUnreachable ->
                    "无法连接 GitHub 下载服务器，请检查网络或开启代理后重试，也可点击「浏览器下载」。"
                rawMsg.contains("HTTP ") ->
                    "$rawMsg，请稍后重试或点击「浏览器下载」。"
                else ->
                    "下载更新失败，请检查网络（国内建议开启代理）后重试，或点击「浏览器下载」。"
            }
        }

        fun parseReleaseNoteItems(body: String?): List<String> {
            if (body.isNullOrBlank()) return listOf("常规体验优化与问题修复")
            val items = mutableListOf<String>()
            for (rawLine in body.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("### 重要提示") || trimmed.startsWith("### 校验信息")) {
                    break
                }
                if (trimmed.startsWith("#")) continue
                if (trimmed.startsWith("- **Android") ||
                    trimmed.startsWith("- **iOS") ||
                    trimmed.startsWith("- SHA-256:") ||
                    trimmed.startsWith("- 签名证书")
                ) {
                    continue
                }
                val cleaned = trimmed
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("• ")
                    .replace(CONVENTIONAL_PREFIX_REGEX, "")
                    .replace(COMMIT_HASH_SUFFIX_REGEX, "")
                    .replace("**", "")
                    .replace("`", "")
                    .trim()
                if (cleaned.isNotEmpty()) {
                    items.add(cleaned)
                }
            }
            return items.ifEmpty { listOf("常规体验优化与问题修复") }
        }

        fun sanitizeReleaseNotes(body: String?): String =
            parseReleaseNoteItems(body).joinToString("\n")

        /**
         * Parses GitHub Releases API JSON and selects the matching Android `.apk` asset.
         */
        fun parseGithubReleaseJson(jsonText: String): AppUpdateInfo? {
            val root = runCatching { json.parseToJsonElement(jsonText).asJsonObjectOrNull() }.getOrNull()
                ?: return null
            if (root["draft"].asBooleanOrNull() == true) return null
            if (root["prerelease"].asBooleanOrNull() == true) return null

            val tagName = root["tag_name"].asStringOrNull()?.trim().orEmpty()
            if (tagName.isBlank()) return null
            val versionName = normalizeVersion(tagName)
            val releaseTitle = root["name"].asStringOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: tagName
            val htmlUrl = root["html_url"].asStringOrNull()?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$tagName"
            val notes = sanitizeReleaseNotes(root["body"].asStringOrNull())

            val assets = root["assets"].asJsonArrayOrNull().orEmpty()
                .mapNotNull { it.asJsonObjectOrNull() }
                .filter { obj ->
                    val name = obj["name"].asStringOrNull().orEmpty()
                    val url = obj["browser_download_url"].asStringOrNull().orEmpty()
                    name.endsWith(".apk", ignoreCase = true) && url.startsWith("http", ignoreCase = true)
                }

            val selectedAsset = assets.firstOrNull { obj ->
                val name = obj["name"].asStringOrNull().orEmpty()
                name.contains("release", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
            } ?: assets.firstOrNull() ?: return null

            val assetName = selectedAsset["name"].asStringOrNull()?.trim().orEmpty()
            val downloadUrl = selectedAsset["browser_download_url"].asStringOrNull()?.trim().orEmpty()
            val sizeBytes = selectedAsset["size"].asLongOrNull() ?: 0L
            val rawBody = root["body"].asStringOrNull()
            val sha256 = extractSha256Digest(selectedAsset["digest"].asStringOrNull())
                ?: extractSha256FromReleaseBody(rawBody, assetName)

            if (assetName.isBlank() || downloadUrl.isBlank()) return null

            return AppUpdateInfo(
                versionName = versionName,
                tagName = tagName,
                releaseTitle = releaseTitle,
                releaseNotes = notes,
                htmlUrl = htmlUrl,
                assetName = assetName,
                downloadUrl = downloadUrl,
                assetSizeBytes = sizeBytes,
                expectedSha256 = sha256
            )
        }

        fun buildFallbackReleaseInfo(tagName: String): AppUpdateInfo {
            val cleanTag = tagName.trim()
            val versionName = normalizeVersion(cleanTag)
            val assetName = "lx-pure-$cleanTag-release.apk"
            return AppUpdateInfo(
                versionName = versionName,
                tagName = cleanTag,
                releaseTitle = cleanTag,
                releaseNotes = "发现新版本 $cleanTag，点击立即更新即可下载安装。",
                htmlUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$cleanTag",
                assetName = assetName,
                downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$cleanTag/$assetName",
                assetSizeBytes = 0L,
                expectedSha256 = null
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val apiClient = baseHttpClient.newBuilder()
        .cache(null)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Snappy per-route timeouts with bounded total call timeout so trickling mirrors fail over
    private val downloadClient = baseHttpClient.newBuilder()
        .cache(null)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val checkGeneration = AtomicLong(0L)
    private val downloadGeneration = AtomicLong(0L)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile
    private var activeCheckCall: Call? = null
    @Volatile
    private var activeDownloadCall: Call? = null
    private var hasAutoChecked = false

    private val updatesDir: File
        get() = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }

    fun checkOnStartupOnce() {
        if (hasAutoChecked) return
        hasAutoChecked = true
        checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean = false) {
        val currentState = _state.value
        if (currentState is AppUpdateState.Downloading ||
            currentState is AppUpdateState.Verifying ||
            currentState is AppUpdateState.ReadyToInstall
        ) {
            return
        }
        val gen = checkGeneration.incrementAndGet()
        checkJob?.cancel()
        activeCheckCall?.cancel()
        activeCheckCall = null
        _state.value = AppUpdateState.Checking(manual = manual)

        checkJob = scope.launch {
            try {
                cleanupStaleCachedApks(cleanPartFiles = downloadJob?.isActive != true)
                ensureActive()

                val info = fetchLatestRelease(gen)
                if (!isActive || checkGeneration.get() != gen) return@launch
                if (_state.value is AppUpdateState.Downloading ||
                    _state.value is AppUpdateState.Verifying ||
                    _state.value is AppUpdateState.ReadyToInstall
                ) {
                    return@launch
                }

                if (info == null) {
                    _state.value = AppUpdateState.Idle
                    if (manual) {
                        _messages.tryEmit("连接 GitHub 检查更新超时或失败，国内网络建议开启代理后重试")
                    }
                    return@launch
                }

                if (isNewerVersion(BuildConfig.VERSION_NAME, info.versionName)) {
                    _state.value = AppUpdateState.Available(info)
                } else {
                    _state.value = AppUpdateState.Idle
                    if (manual) {
                        _messages.tryEmit("当前已是最新版本 (v${normalizeVersion(BuildConfig.VERSION_NAME)})")
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Update check failed: ${t.message}")
                if (isActive && checkGeneration.get() == gen && _state.value is AppUpdateState.Checking) {
                    _state.value = AppUpdateState.Idle
                    if (manual) {
                        _messages.tryEmit("连接 GitHub 检查更新超时或失败，国内网络建议开启代理后重试")
                    }
                }
            }
        }
    }

    fun startDownload(info: AppUpdateInfo) {
        // Cancel any pending check so it cannot overwrite Downloading/Verifying/ReadyToInstall
        checkGeneration.incrementAndGet()
        checkJob?.cancel()
        activeCheckCall?.cancel()
        activeCheckCall = null

        val gen = downloadGeneration.incrementAndGet()
        downloadJob?.cancel()
        activeDownloadCall?.cancel()
        activeDownloadCall = null

        // Reflect Downloading immediately so rapid double-taps cannot re-enter
        val routes = buildDownloadRoutes(
            originalUrl = info.downloadUrl,
            allowMirrors = !info.expectedSha256.isNullOrBlank()
        )
        if (routes.isEmpty()) {
            _state.value = AppUpdateState.Error(info, "更新包下载地址无效，请通过浏览器下载")
            return
        }
        _state.value = AppUpdateState.Downloading(
            info = info,
            downloadedBytes = 0L,
            totalBytes = info.assetSizeBytes,
            progress = 0f,
            routeLabel = routes.first().label
        )

        downloadJob = scope.launch {
            val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val finalFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.$gen.part")

            // Fast-path: reuse already downloaded and verified APK for the exact same version
            if (finalFile.exists() && isValidApkFile(finalFile, info)) {
                if (isActive && downloadGeneration.get() == gen) {
                    _state.value = AppUpdateState.ReadyToInstall(info, finalFile)
                }
                return@launch
            }

            var lastError: Throwable? = null
            for (route in routes) {
                if (!isActive || downloadGeneration.get() != gen) {
                    runCatching { if (partFile.exists()) partFile.delete() }
                    return@launch
                }

                _state.value = AppUpdateState.Downloading(
                    info = info,
                    downloadedBytes = 0L,
                    totalBytes = info.assetSizeBytes,
                    progress = 0f,
                    routeLabel = route.label
                )

                try {
                    downloadFromRoute(route, info, partFile, gen)
                    if (!isActive || downloadGeneration.get() != gen) {
                        runCatching { if (partFile.exists()) partFile.delete() }
                        return@launch
                    }
                    _state.value = AppUpdateState.Verifying(info)
                    val valid = isValidApkFile(partFile, info)
                    if (!isActive || downloadGeneration.get() != gen) {
                        runCatching { if (partFile.exists()) partFile.delete() }
                        return@launch
                    }
                    if (!valid) {
                        runCatching { if (partFile.exists()) partFile.delete() }
                        lastError = IllegalStateException("安装包校验未通过")
                        Log.w(TAG, "APK verification failed via ${route.label}, trying next route")
                        continue
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    if (isActive && downloadGeneration.get() == gen) {
                        _state.value = AppUpdateState.ReadyToInstall(info, finalFile)
                    }
                    return@launch
                } catch (ce: CancellationException) {
                    runCatching { if (partFile.exists()) partFile.delete() }
                    throw ce
                } catch (t: Throwable) {
                    runCatching { if (partFile.exists()) partFile.delete() }
                    if (!isActive || downloadGeneration.get() != gen) {
                        return@launch
                    }
                    Log.w(TAG, "APK download failed via ${route.label}: ${t.message}")
                    lastError = t
                }
            }

            if (isActive && downloadGeneration.get() == gen) {
                _state.value = AppUpdateState.Error(
                    info = info,
                    message = formatDownloadError(lastError, triedMirrors = routes.size > 1)
                )
            }
        }
    }

    private suspend fun downloadFromRoute(
        route: UpdateDownloadRoute,
        info: AppUpdateInfo,
        partFile: File,
        gen: Long
    ) {
        val request = Request.Builder()
            .url(route.url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
            .build()

        val call = downloadClient.newCall(request)
        activeDownloadCall = call
        val cancelHandle = kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            if (downloadGeneration.get() != gen) {
                call.cancel()
                throw CancellationException("下载已取消")
            }
            call.execute().use { response ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载失败（HTTP ${response.code}）")
                }
                val contentType = response.header("Content-Type")
                if (isDisallowedUpdateContentType(contentType)) {
                    throw IllegalStateException("服务器返回了非安装包响应")
                }
                val body = response.body ?: throw IllegalStateException("服务器返回空安装包")
                val responseLength = body.contentLength()
                if (responseLength > MAX_VALID_APK_BYTES) {
                    throw IllegalStateException("安装包体积超出安全限制")
                }
                val contentLength = responseLength.takeIf { it > 0L } ?: info.assetSizeBytes

                if (partFile.exists()) partFile.delete()
                var downloaded = 0L
                var lastEmitTime = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (call.isCanceled() || downloadGeneration.get() != gen) {
                                throw CancellationException("下载已取消")
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloaded += read
                            if (downloaded > MAX_VALID_APK_BYTES) {
                                throw IllegalStateException("安装包体积超出安全限制")
                            }
                            output.write(buffer, 0, read)

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 100L && downloadGeneration.get() == gen) {
                                lastEmitTime = now
                                val progress = if (contentLength > 0L) {
                                    (downloaded.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                _state.value = AppUpdateState.Downloading(
                                    info = info,
                                    downloadedBytes = downloaded,
                                    totalBytes = contentLength,
                                    progress = progress,
                                    routeLabel = route.label
                                )
                            }
                        }
                        output.flush()
                    }
                }
                if (responseLength > 0L && downloaded != responseLength) {
                    throw IllegalStateException("安装包下载不完整（已接收 ${downloaded}/${responseLength} 字节）")
                }
            }
        } finally {
            cancelHandle?.dispose()
            if (activeDownloadCall === call) {
                activeDownloadCall = null
            }
        }
    }

    fun cancelDownload() {
        val cur = _state.value
        downloadGeneration.incrementAndGet()
        downloadJob?.cancel()
        downloadJob = null
        activeDownloadCall?.cancel()
        activeDownloadCall = null
        when (cur) {
            is AppUpdateState.Downloading -> _state.value = AppUpdateState.Available(cur.info)
            is AppUpdateState.Verifying -> _state.value = AppUpdateState.Available(cur.info)
            else -> Unit
        }
    }

    fun dismiss() {
        checkGeneration.incrementAndGet()
        checkJob?.cancel()
        checkJob = null
        activeCheckCall?.cancel()
        activeCheckCall = null

        downloadGeneration.incrementAndGet()
        downloadJob?.cancel()
        downloadJob = null
        activeDownloadCall?.cancel()
        activeDownloadCall = null

        _state.value = AppUpdateState.Idle
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    fun buildUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun buildInstallIntent(apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun executeCheckCall(client: OkHttpClient, request: Request, gen: Long): okhttp3.Response? {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (checkGeneration.get() != gen) throw CancellationException("Update check cancelled")
        val call = client.newCall(request)
        activeCheckCall = call
        val handle = kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (checkGeneration.get() != gen) throw CancellationException("Update check cancelled")
            null
        } finally {
            handle?.dispose()
            if (activeCheckCall === call) activeCheckCall = null
        }
    }

    private suspend fun fetchDirectSha256ForAsset(tagName: String, assetName: String, gen: Long): String? {
        val sumsUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/SHA256SUMS.txt"
        val req = Request.Builder()
            .url(sumsUrl)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
            .build()
        executeCheckCall(apiClient, req, gen)?.use { resp ->
            if (resp.isSuccessful) {
                val text = resp.body?.string()
                return extractSha256FromSumsText(text, assetName)
            }
        }
        return null
    }

    private suspend fun fetchLatestRelease(gen: Long): AppUpdateInfo? {
        val apiReq = Request.Builder()
            .url(LATEST_RELEASE_API_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
            .build()

        executeCheckCall(apiClient, apiReq, gen)?.use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (!body.isNullOrBlank()) {
                    parseGithubReleaseJson(body)?.let { parsed ->
                        val enriched = if (parsed.expectedSha256.isNullOrBlank()) {
                            val sha = fetchDirectSha256ForAsset(parsed.tagName, parsed.assetName, gen)
                            parsed.copy(expectedSha256 = sha)
                        } else {
                            parsed
                        }
                        return enriched
                    }
                }
            } else if (resp.code == 403 || resp.code == 429) {
                Log.w(TAG, "GitHub API rate-limited (${resp.code}); falling back to release redirect")
            }
        }

        // Fallback via direct or mirror release redirect when API is blocked or rate-limited
        val redirectCandidates = listOf(
            LATEST_RELEASE_WEB_URL,
            "https://ghfast.top/$LATEST_RELEASE_WEB_URL",
            "https://gh-proxy.org/$LATEST_RELEASE_WEB_URL"
        )
        val noRedirectClient = apiClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
        for (candidate in redirectCandidates) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val webReq = Request.Builder()
                .url(candidate)
                .head()
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
                .build()

            val found = executeCheckCall(noRedirectClient, webReq, gen)?.use { resp ->
                val targetUrl = resp.header("Location") ?: resp.request.url.toString()
                val tag = extractTagFromReleaseUrl(targetUrl) ?: return@use null
                buildFallbackReleaseInfo(tag)
            }
            if (found != null) {
                // Attempt to fetch trusted SHA-256 directly from github.com (never from untrusted mirrors)
                val directSha = fetchDirectSha256ForAsset(found.tagName, found.assetName, gen)
                return found.copy(expectedSha256 = directSha)
            }
        }
        return null
    }

    private fun isValidApkFile(file: File, info: AppUpdateInfo): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_APK_BYTES) return false
        if (info.assetSizeBytes > 0L && file.length() != info.assetSizeBytes) return false

        val header = ByteArray(4)
        val headerRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)
        if (headerRead < 4 || !isValidZipOrApkMagic(header)) return false

        // Validate complete ZIP central directory and AndroidManifest.xml (rejects truncated archives)
        val validZip = runCatching {
            ZipFile(file).use { zip -> zip.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)
        if (!validZip) return false

        val expectedHash = info.expectedSha256
        if (!expectedHash.isNullOrBlank()) {
            val actualHash = computeSha256Hex(file)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch: expected=$expectedHash actual=$actualHash")
                return false
            }
        }
        return true
    }

    private fun computeSha256Hex(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }.getOrNull()

    private fun cleanupStaleCachedApks(cleanPartFiles: Boolean = true) {
        runCatching {
            val dir = File(context.cacheDir, "updates")
            if (!dir.exists()) return
            val currentNorm = normalizeVersion(BuildConfig.VERSION_NAME)
            dir.listFiles()?.forEach { file ->
                if (cleanPartFiles && file.name.endsWith(".part")) {
                    file.delete()
                } else if (file.name.endsWith(".apk", ignoreCase = true)) {
                    val match = Regex("v(\\d+(?:\\.\\d+)+)", RegexOption.IGNORE_CASE).find(file.name)
                    val fileVer = match?.groupValues?.getOrNull(1)
                    if (fileVer == null || !isNewerVersion(currentNorm, fileVer)) {
                        file.delete()
                    }
                }
            }
        }
    }
}
