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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data class Checking(val manual: Boolean) : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
    data class Downloading(
        val info: AppUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Float
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
                ?.lowercase()
        }

        fun isValidZipOrApkMagic(header: ByteArray): Boolean =
            header.size >= 4 &&
                header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()

        fun sanitizeReleaseNotes(body: String?): String {
            if (body.isNullOrBlank()) return "常规体验优化与问题修复"
            val lines = body.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() &&
                        !line.startsWith("## ") &&
                        !line.startsWith("### 校验信息") &&
                        !line.startsWith("- **Android APK**") &&
                        !line.startsWith("- **iOS IPA**") &&
                        !line.startsWith("- SHA-256:") &&
                        !line.startsWith("- 签名证书")
                }
            val cleaned = lines.joinToString("\n").trim()
            return cleaned.ifBlank { "常规体验优化与问题修复" }
        }

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
            val sha256 = extractSha256Digest(selectedAsset["digest"].asStringOrNull())

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
                releaseNotes = "发现新版本 $cleanTag，点击更新下载安装。",
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadClient = baseHttpClient.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var hasAutoChecked = false

    private val updatesDir: File
        get() = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }

    fun checkOnStartupOnce() {
        if (hasAutoChecked) return
        hasAutoChecked = true
        checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.Verifying) {
            return
        }
        checkJob?.cancel()
        checkJob = scope.launch {
            cleanupStaleCachedApks()
            _state.value = AppUpdateState.Checking(manual = manual)

            val result = runCatching { fetchLatestRelease() }
            val info = result.getOrNull()
            if (info == null) {
                _state.value = AppUpdateState.Idle
                if (manual) {
                    _messages.tryEmit("检查更新失败，请检查网络后重试")
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
        }
    }

    fun startDownload(info: AppUpdateInfo) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val safeName = info.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val finalFile = File(updatesDir, safeName)
            val partFile = File(updatesDir, "$safeName.part")

            // Fast-path: reuse already downloaded and verified APK for the exact same version
            if (finalFile.exists() && isValidApkFile(finalFile, info)) {
                _state.value = AppUpdateState.ReadyToInstall(info, finalFile)
                return@launch
            }

            _state.value = AppUpdateState.Downloading(
                info = info,
                downloadedBytes = 0L,
                totalBytes = info.assetSizeBytes,
                progress = 0f
            )

            try {
                val request = Request.Builder()
                    .url(info.downloadUrl)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("下载失败（HTTP ${response.code}）")
                    }
                    val body = response.body ?: throw IllegalStateException("服务器返回空安装包")
                    val contentLength = body.contentLength().takeIf { it > 0L } ?: info.assetSizeBytes

                    if (partFile.exists()) partFile.delete()
                    var downloaded = 0L
                    var lastEmitTime = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                if (!isActive) throw CancellationException("下载已取消")
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 100L) {
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
                                        progress = progress
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                }

                _state.value = AppUpdateState.Verifying(info)
                if (!isValidApkFile(partFile, info)) {
                    partFile.delete()
                    _state.value = AppUpdateState.Error(info, "安装包校验未通过，请重新下载")
                    return@launch
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                _state.value = AppUpdateState.ReadyToInstall(info, finalFile)
            } catch (ce: CancellationException) {
                runCatching { if (partFile.exists()) partFile.delete() }
                _state.value = AppUpdateState.Available(info)
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "APK download failed: ${t.message}")
                runCatching { if (partFile.exists()) partFile.delete() }
                _state.value = AppUpdateState.Error(
                    info = info,
                    message = t.message?.takeIf { it.isNotBlank() } ?: "下载更新失败，请检查网络或通过浏览器下载"
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val cur = _state.value
        if (cur is AppUpdateState.Downloading) {
            _state.value = AppUpdateState.Available(cur.info)
        }
    }

    fun dismiss() {
        if (_state.value is AppUpdateState.Downloading) {
            downloadJob?.cancel()
            downloadJob = null
        }
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

    private fun fetchLatestRelease(): AppUpdateInfo? {
        val apiReq = Request.Builder()
            .url(LATEST_RELEASE_API_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
            .build()

        apiClient.newCall(apiReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (!body.isNullOrBlank()) {
                    parseGithubReleaseJson(body)?.let { return it }
                }
            } else if (resp.code == 403 || resp.code == 429) {
                Log.w(TAG, "GitHub API rate-limited (${resp.code}); falling back to release redirect")
            }
        }

        // Rate-limit / API fallback: follow github.com/releases/latest redirect to discover tag
        val webReq = Request.Builder()
            .url(LATEST_RELEASE_WEB_URL)
            .head()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "LXPure/${normalizeVersion(BuildConfig.VERSION_NAME)}")
            .build()

        apiClient.newCall(webReq).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val tag = extractTagFromReleaseUrl(finalUrl) ?: return null
            return buildFallbackReleaseInfo(tag)
        }
    }

    private fun isValidApkFile(file: File, info: AppUpdateInfo): Boolean {
        if (!file.exists() || file.length() < MIN_VALID_APK_BYTES) return false
        if (info.assetSizeBytes > 0L && file.length() != info.assetSizeBytes) return false

        val header = ByteArray(4)
        val headerRead = runCatching {
            FileInputStream(file).use { it.read(header) }
        }.getOrDefault(0)
        if (headerRead < 4 || !isValidZipOrApkMagic(header)) return false

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
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun cleanupStaleCachedApks() {
        runCatching {
            val dir = File(context.cacheDir, "updates")
            if (!dir.exists()) return
            val currentNorm = normalizeVersion(BuildConfig.VERSION_NAME)
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part")) {
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
