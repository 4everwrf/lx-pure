package com.lxpure.music.core.userapi

import android.content.Context
import android.util.Log
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.network.NetworkQualityAdvisor
import com.lxpure.music.data.local.UserApiDao
import com.lxpure.music.data.local.UserApiEntity
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserApiSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class ResolvedStream(
    val url: String,
    val quality: AudioQuality,
    val resolvedTrack: Track,
    val sourceId: String,
    val providerName: String,
    val switchedPlatform: Boolean = false,
    val switchedSource: Boolean = false,
    val sourceRevision: String = ""
)

data class SourceUpdateResult(
    val sourceName: String,
    val oldVersion: String,
    val newVersion: String,
    val updated: Boolean,
    val alreadyLatest: Boolean,
    val errorMessage: String? = null
)

data class BatchUpdateSummary(
    val updatedNames: List<String>,
    val latestCount: Int,
    val failedCount: Int,
    val skippedLocalCount: Int
) {
    fun formatUserMessage(): String = when {
        updatedNames.isNotEmpty() -> {
            val base = "已更新 ${updatedNames.size} 个音源：${updatedNames.joinToString("、")}"
            if (failedCount > 0) "$base（另有 ${failedCount} 个拉取失败）" else base
        }
        failedCount == 0 && latestCount > 0 -> {
            if (skippedLocalCount > 0) {
                "全部 ${latestCount} 个在线音源均已是最新版本（已跳过 ${skippedLocalCount} 个本地脚本）"
            } else {
                "全部 ${latestCount} 个在线音源均已是最新版本"
            }
        }
        latestCount > 0 ->
            "${latestCount} 个已最新，${failedCount} 个拉取失败"
        failedCount == 0 && skippedLocalCount > 0 ->
            "当前均为本地导入脚本，未绑定在线更新链接"
        failedCount == 0 ->
            "暂无可检查更新的音源"
        else ->
            "更新检查失败，请检查网络连接后重试"
    }
}

data class SourceImportBatchResult(
    val imported: List<UserApiSource>,
    val skippedCount: Int = 0
) {
    fun formatUserMessage(): String {
        if (imported.isEmpty()) return "未导入任何有效音源"
        if (imported.size == 1 && skippedCount == 0) {
            return "已导入音源：${imported.first().name}"
        }
        val base = "已导入 ${imported.size} 个音源"
        return if (skippedCount > 0) "$base（跳过 $skippedCount 个无效项）" else base
    }
}

class UserApiManager private constructor(
    private val context: Context?,
    private val dao: UserApiDao?,
    private val httpClient: OkHttpClient,
    private val aggregator: MusicAggregator,
    private val networkAdvisor: NetworkQualityAdvisor?,
    streamHttpClient: OkHttpClient = httpClient,
    private val initialSourcesOverride: List<UserApiSource>? = null,
    private val fetchStreamUrlOverride: (suspend (ResolveCandidate, Long) -> Result<String>)? = null,
    private val verifyStreamUrlOverride: (suspend (String) -> Boolean)? = null,
    private val loggerDebug: (String) -> Unit = { msg -> Log.d(TAG, msg) },
    private val loggerWarn: (String) -> Unit = { msg -> Log.w(TAG, msg) }
) {
    constructor(
        context: Context,
        dao: UserApiDao,
        httpClient: OkHttpClient,
        aggregator: MusicAggregator,
        networkAdvisor: NetworkQualityAdvisor,
        streamHttpClient: OkHttpClient = httpClient
    ) : this(
        context = context,
        dao = dao,
        httpClient = httpClient,
        aggregator = aggregator,
        networkAdvisor = networkAdvisor,
        streamHttpClient = streamHttpClient,
        initialSourcesOverride = null,
        fetchStreamUrlOverride = null,
        verifyStreamUrlOverride = null
    )
    class UnsupportedSourcePlatformException(
        val source: MusicSource,
        val allowCrossPlatformFallback: Boolean,
        message: String
    ) : IllegalStateException(message)

    companion object {
        private const val TAG = "UserApiManager"
        internal const val SCRIPT_URL_TIMEOUT_MS = 4_000L
        internal const val STREAM_PROBE_TIMEOUT_MS = 2_200L
        internal const val RESOLVE_TOTAL_BUDGET_MS = 16_000L
        internal const val INITIAL_PRIMARY_GRACE_MS = 600L
        private const val COOLDOWN_MS = 45_000L
        private const val MAX_SCRIPT_DOWNLOAD_BYTES = LxScriptParser.MAX_BUNDLE_PAYLOAD_BYTES // 4 MB cap
        private val SEARCHABLE_PLATFORM_CODES = setOf(
            MusicSource.WY.code,
            MusicSource.TX.code,
            MusicSource.KW.code,
            MusicSource.KG.code
        )
        private val HEALTH_PROBE_QUERIES = listOf("海阔天空", "起风了", "晴天")

        internal fun sourcePlatformKey(sourceId: String, platformCode: String): String =
            "${sourceId}_${platformCode.lowercase(Locale.ROOT)}"

        internal fun sourceTrackKey(sourceId: String, trackId: String): String =
            "${sourceId}_${trackId}"

        internal fun createForTesting(
            httpClient: OkHttpClient,
            aggregator: MusicAggregator,
            sources: List<UserApiSource>,
            fetchStreamUrl: suspend (ResolveCandidate, Long) -> Result<String>,
            verifyStreamUrl: suspend (String) -> Boolean = { true }
        ): UserApiManager = UserApiManager(
            context = null,
            dao = null,
            httpClient = httpClient,
            aggregator = aggregator,
            networkAdvisor = null,
            streamHttpClient = httpClient,
            initialSourcesOverride = sources,
            fetchStreamUrlOverride = fetchStreamUrl,
            verifyStreamUrlOverride = verifyStreamUrl,
            loggerDebug = {},
            loggerWarn = {}
        )

        internal fun hasReadySourceSupportingPlatform(
            readySources: List<UserApiSource>,
            source: MusicSource
        ): Boolean {
            val code = source.code.lowercase(Locale.ROOT)
            return readySources.any { it.supportedPlatforms.containsKey(code) }
        }

        internal fun buildUnsupportedPlatformFailure(
            track: Track,
            allowCrossPlatformFallback: Boolean
        ): UnsupportedSourcePlatformException {
            val platformName = track.source.displayName
            val msg = if (allowCrossPlatformFallback) {
                "当前自定义音源不支持${platformName}，且暂未在其他平台匹配到《${track.title}》"
            } else {
                "当前自定义音源不支持${platformName}，请在设置开启「无感自动换源兜底」或导入支持${platformName}的音源"
            }
            return UnsupportedSourcePlatformException(
                source = track.source,
                allowCrossPlatformFallback = allowCrossPlatformFallback,
                message = msg
            )
        }

        internal fun computeSourceRevision(source: UserApiSource): String =
            LxCrypto.md5Hex("${source.version}:${source.script.trim()}").take(8)

        /**
         * Validates a downloaded custom source script while rejecting HTML/JSON error pages,
         * JSON source bundles (including top-level JSON arrays), NUL bytes, and oversized payloads.
         */
        fun isValidDownloadedScript(content: String?): Boolean {
            if (content.isNullOrBlank() || content.length < 150) return false
            if (content.contains('\u0000')) return false
            if (content.toByteArray(StandardCharsets.UTF_8).size > LxScriptParser.MAX_SINGLE_SCRIPT_BYTES) return false
            val cleaned = content.removePrefix("\uFEFF").trimStart()
            if (cleaned.isEmpty() || cleaned.startsWith("<") || cleaned.startsWith("{")) return false
            if (cleaned.startsWith("[") &&
                LxScriptParser.parseScriptBundle(content) !is ScriptBundleParseResult.NotBundle
            ) {
                return false
            }
            return cleaned.contains("lx", ignoreCase = true) ||
                cleaned.contains("\\x6c\\x78", ignoreCase = true) ||
                cleaned.contains("@name") ||
                cleaned.contains("globalThis")
        }

        /**
         * Validates either a Melora / multi-script JSON source bundle or a standalone LX `.js` script.
         */
        fun isValidDownloadedPayload(content: String?): Boolean {
            if (content.isNullOrBlank()) return false
            return when (LxScriptParser.parseScriptBundle(content)) {
                is ScriptBundleParseResult.ValidBundle -> true
                is ScriptBundleParseResult.InvalidBundle -> false
                is ScriptBundleParseResult.NotBundle -> isValidDownloadedScript(content)
            }
        }

        /**
         * Distinguishes infrastructure/runtime failures (worthy of source pruning & 45s cooldown)
         * from per-track/per-tier misses (e.g., track has no FLAC, allowing 320k/128k fallback).
         */
        internal fun isTransportOrRuntimeFailure(rawMessage: String?): Boolean {
            val lower = rawMessage.orEmpty().lowercase(Locale.ROOT)
            if (lower.isEmpty()) return false
            return lower.contains("超时") ||
                lower.contains("timeout") ||
                lower.contains("timed out") ||
                lower.contains("无法连接") ||
                lower.contains("连接失败") ||
                lower.contains("校验失败") ||
                lower.contains("运行异常") ||
                lower.contains("引擎") ||
                lower.contains("未正确注册") ||
                lower.contains("已卸载") ||
                lower.contains("已停用") ||
                lower.contains("已切换")
        }

        internal fun isDeterministicTrackMiss(rawMessage: String?): Boolean {
            val lower = rawMessage.orEmpty().lowercase(Locale.ROOT)
            if (lower.isEmpty()) return false
            // Never include VIP / 403 here: many sources block FLAC/320k as VIP while 128k remains playable.
            return lower.contains("无版权") ||
                lower.contains("版权") ||
                lower.contains("copyright") ||
                lower.contains("下架") ||
                lower.contains("不存在") ||
                lower.contains("not found") ||
                lower.contains("404")
        }

        internal suspend fun awaitInitialSourcesReady(
            sourcesSnapshot: () -> List<UserApiSource>,
            timeoutMs: Long = 4_500L,
            primaryGraceMs: Long = INITIAL_PRIMARY_GRACE_MS,
            preferredSourceId: String? = null,
            nowMs: () -> Long = System::currentTimeMillis
        ) {
            withTimeoutOrNull(timeoutMs) {
                val deadline = nowMs() + timeoutMs
                var readySeenAtMs: Long? = null
                while (nowMs() < deadline) {
                    val enabled = sourcesSnapshot().filter { it.enabled }.sortedBy { it.priority }
                    if (enabled.isEmpty()) break
                    val primary = preferredSourceId?.let { id -> enabled.firstOrNull { it.id == id } } ?: enabled.first()
                    val primarySettled = primary.status != UserApiRuntimeStatus.INITIALIZING
                    val hasReady = enabled.any { it.status == UserApiRuntimeStatus.READY }
                    val hasInitializing = enabled.any { it.status == UserApiRuntimeStatus.INITIALIZING }
                    if ((primarySettled && hasReady) || !hasInitializing) break
                    if (hasReady && preferredSourceId == null) {
                        val seenAt = readySeenAtMs ?: nowMs().also { readySeenAtMs = it }
                        if (nowMs() - seenAt >= primaryGraceMs) break
                    }
                    kotlinx.coroutines.delay(60L)
                }
            }
        }

        internal fun createProbeClient(streamHttpClient: OkHttpClient): OkHttpClient =
            streamHttpClient.newBuilder()
                .cache(null)
                .connectTimeout(STREAM_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(STREAM_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(STREAM_PROBE_TIMEOUT_MS + 300L, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        private enum class ProbeOutcome { PLAYABLE, RETRY_WITHOUT_RANGE, REJECTED }

        internal suspend fun verifyPlayableAudioUrlWithClient(client: OkHttpClient, url: String): Boolean {
            val safeUrl = LxQuickJsRuntime.sanitizeRequestUrl(url)
            if (safeUrl.isEmpty()) return false
            val rangeStatus = executeProbeRequestWithClient(client, safeUrl, useRange = true)
            if (rangeStatus == ProbeOutcome.PLAYABLE) return true
            if (rangeStatus == ProbeOutcome.RETRY_WITHOUT_RANGE) {
                return executeProbeRequestWithClient(client, safeUrl, useRange = false) == ProbeOutcome.PLAYABLE
            }
            return false
        }

        private suspend fun executeProbeRequestWithClient(
            client: OkHttpClient,
            safeUrl: String,
            useRange: Boolean
        ): ProbeOutcome = suspendCancellableCoroutine { cont ->
            val req = runCatching {
                Request.Builder()
                    .url(safeUrl)
                    .header("User-Agent", LxQuickJsRuntime.CHROME_USER_AGENT)
                    .apply {
                        if (useRange) {
                            header("Range", "bytes=0-127")
                            get()
                        } else {
                            head()
                        }
                    }
                    .build()
            }.getOrElse {
                cont.resume(ProbeOutcome.REJECTED)
                return@suspendCancellableCoroutine
            }

            val call = client.newCall(req)
            cont.invokeOnCancellation {
                runCatching { call.cancel() }
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(ProbeOutcome.REJECTED)
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome = response.use { resp ->
                        if (useRange && (resp.code == 416 || resp.code == 501)) {
                            return@use ProbeOutcome.RETRY_WITHOUT_RANGE
                        }
                        if (resp.code !in listOf(200, 206)) return@use ProbeOutcome.REJECTED
                        val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                        if (contentType.contains("text/html") ||
                            contentType.contains("application/json") ||
                            contentType.contains("application/xml") ||
                            contentType.contains("text/xml") ||
                            contentType.contains("application/javascript") ||
                            contentType.startsWith("image/")
                        ) {
                            return@use ProbeOutcome.REJECTED
                        }
                        val contentLength = resp.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null) {
                            if (contentLength == 0L) return@use ProbeOutcome.REJECTED
                            // A tiny 200 OK payload (not a 206 byte range) is almost always an error stub
                            if (resp.code == 200 && contentLength < 1024L) return@use ProbeOutcome.REJECTED
                        }
                        ProbeOutcome.PLAYABLE
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
            })
        }
    }

    internal data class ResolveCandidate(
        val track: Track,
        val source: UserApiSource,
        val runtime: LxQuickJsRuntime? = null,
        val quality: AudioQuality,
        val switchedPlatform: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = ConcurrentHashMap<String, LxQuickJsRuntime>()
    private val sourceMutexes = ConcurrentHashMap<String, Mutex>()
    private val priorityMutex = Mutex()
    private val sourceGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val platformCooldowns = ConcurrentHashMap<String, Long>()

    // Share streamHttpClient's ConnectionPool & Dispatcher so stream probing warms the exact
    // TCP/TLS socket pool immediately reused by ExoPlayer.
    internal val probeClient = createProbeClient(streamHttpClient)

    private val _sources = MutableStateFlow<List<UserApiSource>>(emptyList())
    val sources: StateFlow<List<UserApiSource>> = _sources.asStateFlow()
    private val initialDbLoadDone = CompletableDeferred<Unit>()

    private val raceCoordinator = SourceRaceCoordinator(
        isMeteredNetwork = { networkAdvisor?.isMeteredNetwork() ?: false },
        isInCooldown = { key -> isInCooldown(key) },
        markCooldown = { sourceId, platformCode -> markCooldown(sourceId, platformCode) },
        clearCooldown = { key -> clearCooldown(key) },
        runtimeLookup = { sourceId -> runtimes[sourceId] },
        onSourceLatencyMeasured = { sourceId, elapsed ->
            updateSourceState(sourceId) {
                if (it.status == UserApiRuntimeStatus.READY) {
                    it.copy(latencyMs = elapsed, errorMessage = null)
                } else {
                    it
                }
            }
        },
        fetchStreamUrl = { candidate, reqBudget ->
            fetchStreamUrlOverride?.invoke(candidate, reqBudget) ?: run {
                val activeRuntime = candidate.runtime ?: ensureRuntimeReady(candidate.source)
                if (activeRuntime == null) {
                    Result.failure(IllegalStateException("引擎初始化失败"))
                } else {
                    activeRuntime.requestMusicUrl(
                        track = candidate.track,
                        quality = candidate.quality,
                        timeoutMs = reqBudget
                    )
                }
            }
        },
        verifyStreamUrl = { url -> verifyStreamUrlOverride?.invoke(url) ?: verifyPlayableAudioUrl(url) },
        logDebug = loggerDebug,
        logWarn = loggerWarn
    )

    init {
        if (initialSourcesOverride != null) {
            _sources.value = initialSourcesOverride
            initialDbLoadDone.complete(Unit)
        } else {
            scope.launch {
                try {
                    reloadAllFromDatabase()
                } finally {
                    initialDbLoadDone.complete(Unit)
                }
            }
        }
    }

    private fun requireContext(): Context = checkNotNull(context) { "UserApiManager Context is required" }

    private fun requireDao(): UserApiDao = checkNotNull(dao) { "UserApiManager Dao is required" }

    private fun mutexFor(id: String): Mutex = sourceMutexes.computeIfAbsent(id) { Mutex() }

    private fun bumpGeneration(id: String): Long =
        sourceGenerations.computeIfAbsent(id) { AtomicLong(0L) }.incrementAndGet()

    private fun currentGeneration(id: String): Long =
        sourceGenerations[id]?.get() ?: 0L

    /**
     * Waits briefly during cold start so hitting Play right after launching the app
     * waits for saved custom source runtimes to finish loading/initializing instead
     * of falsely reporting "no available source".
     */
    suspend fun awaitInitialLoadIfNeeded(timeoutMs: Long = 4_500L, preferredSourceId: String? = null) {
        val startMs = System.currentTimeMillis()
        withTimeoutOrNull(timeoutMs) {
            initialDbLoadDone.await()
            val remainingMs = (timeoutMs - (System.currentTimeMillis() - startMs)).coerceAtLeast(1L)
            awaitInitialSourcesReady(
                sourcesSnapshot = { _sources.value },
                timeoutMs = remainingMs,
                primaryGraceMs = INITIAL_PRIMARY_GRACE_MS,
                preferredSourceId = preferredSourceId
            )
        }
    }

    suspend fun reloadAllFromDatabase() {
        LxQuickJsRuntime.ensureQuickJsLoaded()
        val entities = requireDao().getAllOnce()
        val initial = entities.map { entity ->
            UserApiSource(
                id = entity.id,
                name = entity.name,
                description = entity.description,
                version = entity.version,
                author = entity.author,
                homepage = entity.homepage,
                script = entity.script,
                sourceUrl = entity.sourceUrl,
                enabled = entity.enabled,
                priority = entity.priority,
                status = if (entity.enabled) UserApiRuntimeStatus.INITIALIZING else UserApiRuntimeStatus.IDLE
            )
        }
        _sources.value = initial

        val validIds = entities.filter { it.enabled }.map { it.id }.toSet()
        runtimes.keys.filter { it !in validIds }.forEach { id ->
            bumpGeneration(id)
            runtimes.remove(id)?.destroy()
        }

        initial.filter { it.enabled }.forEach { source ->
            scope.launch { startRuntime(source) }
        }
    }

    private suspend fun startRuntime(source: UserApiSource) {
        val gen = bumpGeneration(source.id)
        mutexFor(source.id).withLock {
            if (currentGeneration(source.id) != gen) return
            val liveSource = _sources.value.firstOrNull { it.id == source.id }
            if (liveSource == null || !liveSource.enabled) {
                runtimes.remove(source.id)?.destroy()
                return
            }

            runtimes.remove(source.id)?.destroy()
            updateSourceState(source.id) {
                it.copy(status = UserApiRuntimeStatus.INITIALIZING, errorMessage = null, latencyMs = null)
            }

            val runtime = LxQuickJsRuntime(requireContext(), httpClient, liveSource)
            val result = try {
                runtime.initialize()
            } catch (ce: CancellationException) {
                runtime.destroy()
                throw ce
            }

            val postLive = _sources.value.firstOrNull { it.id == source.id }
            if (currentGeneration(source.id) != gen || postLive == null || !postLive.enabled) {
                runtime.destroy()
                return
            }

            if (result.success) {
                runtimes.put(source.id, runtime)?.takeIf { it !== runtime }?.destroy()
                val verifiedLive = _sources.value.firstOrNull { it.id == source.id }
                if (currentGeneration(source.id) != gen || verifiedLive == null || !verifiedLive.enabled) {
                    if (runtimes.remove(source.id, runtime)) {
                        runtime.destroy()
                    }
                    return
                }
                clearCooldownsForSource(source.id)
                updateSourceState(source.id) {
                    if (currentGeneration(source.id) != gen || !it.enabled) {
                        it
                    } else {
                        it.copy(
                            status = UserApiRuntimeStatus.READY,
                            supportedPlatforms = result.supportedPlatforms,
                            latencyMs = null,
                            errorMessage = null
                        )
                    }
                }
            } else {
                runtime.destroy()
                updateSourceState(source.id) {
                    it.copy(
                        status = UserApiRuntimeStatus.ERROR,
                        errorMessage = LxQuickJsRuntime.formatFriendlyError(result.errorMessage ?: "初始化失败"),
                        latencyMs = null
                    )
                }
            }
        }
    }

    suspend fun importFromUrl(url: String): Result<UserApiSource> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            require(
                cleanUrl.startsWith("http://", ignoreCase = true) ||
                    cleanUrl.startsWith("https://", ignoreCase = true)
            ) {
                "请输入有效的 http:// 或 https:// 自定义源链接"
            }
            val script = downloadText(cleanUrl) ?: error("无法下载该音源链接")
            require(isValidDownloadedScript(script)) {
                "下载内容不是有效的自定义源 JavaScript 脚本"
            }
            importRawScript(script, sourceUrl = cleanUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importFromUrlPayload(url: String): Result<SourceImportBatchResult> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = url.trim()
            require(
                cleanUrl.startsWith("http://", ignoreCase = true) ||
                    cleanUrl.startsWith("https://", ignoreCase = true)
            ) {
                "请输入有效的 http:// 或 https:// 自定义源链接"
            }
            val content = downloadText(cleanUrl) ?: error("无法下载该音源链接")
            when (val parsed = LxScriptParser.parseScriptBundle(content)) {
                is ScriptBundleParseResult.ValidBundle -> importParsedBundle(parsed)
                is ScriptBundleParseResult.InvalidBundle -> Result.failure(IllegalArgumentException(parsed.errorMessage))
                is ScriptBundleParseResult.NotBundle -> {
                    require(isValidDownloadedScript(content)) {
                        "下载内容不是有效的自定义源 JavaScript 脚本或音源包"
                    }
                    importRawScript(content, sourceUrl = cleanUrl)
                        .map { SourceImportBatchResult(imported = listOf(it), skippedCount = 0) }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun downloadText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "LXPure/1.1")
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_SCRIPT_DOWNLOAD_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            String(bytes, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        }
    }

    private suspend fun importParsedBundle(parsed: ScriptBundleParseResult.ValidBundle): Result<SourceImportBatchResult> {
        val deduped = LinkedHashMap<String, Pair<ParsedScriptMeta, String>>()
        for (entry in parsed.entries) {
            val meta = LxScriptParser.parse(entry.script, entry.fallbackName)
            deduped[meta.id] = meta to entry.script
        }

        val importedModels = priorityMutex.withLock {
            val existingById = requireDao().getAllOnce().associateBy { it.id }
            var nextPriority = (existingById.values.maxOfOrNull { it.priority } ?: -1) + 1
            val models = mutableListOf<UserApiSource>()
            for ((meta, normalizedScript) in deduped.values) {
                val existingMatch = existingById[meta.id]
                val priority = existingMatch?.priority ?: nextPriority++
                val entity = UserApiEntity(
                    id = meta.id,
                    name = meta.name,
                    description = meta.description,
                    version = meta.version,
                    author = meta.author,
                    homepage = meta.homepage,
                    script = normalizedScript,
                    // Never bind a multi-script JSON bundle URL as a single-script update URL
                    sourceUrl = existingMatch?.sourceUrl,
                    enabled = true,
                    priority = priority,
                    addedAt = existingMatch?.addedAt ?: System.currentTimeMillis()
                )
                requireDao().upsert(entity)
                models.add(
                    UserApiSource(
                        id = entity.id,
                        name = entity.name,
                        description = entity.description,
                        version = entity.version,
                        author = entity.author,
                        homepage = entity.homepage,
                        script = entity.script,
                        sourceUrl = entity.sourceUrl,
                        enabled = true,
                        priority = entity.priority,
                        status = UserApiRuntimeStatus.INITIALIZING
                    )
                )
            }
            // Bump generations & publish in-memory state under priorityMutex so concurrent movePriorityUp cannot clobber order
            models.forEach { bumpGeneration(it.id) }
            val importedById = models.associateBy { it.id }
            _sources.update { list ->
                val updatedExisting = list.map { importedById[it.id] ?: it }
                val existingIds = list.mapTo(HashSet()) { it.id }
                val newlyAdded = models.filter { it.id !in existingIds }
                (updatedExisting + newlyAdded).sortedBy { it.priority }
            }
            models
        }

        importedModels.forEach { model ->
            scope.launch { startRuntime(model) }
        }
        return Result.success(
            SourceImportBatchResult(
                imported = importedModels,
                skippedCount = parsed.skippedCount
            )
        )
    }

    suspend fun importRawPayload(
        rawContent: String,
        sourceUrl: String? = null,
        fallbackName: String = "自定义音源"
    ): Result<SourceImportBatchResult> = withContext(Dispatchers.IO) {
        try {
            require(rawContent.isNotBlank()) { "脚本内容为空" }
            require(!rawContent.contains('\u0000')) { "文件包含二进制内容，不是有效的 JS 音源脚本或音源包" }
            when (val parsed = LxScriptParser.parseScriptBundle(rawContent)) {
                is ScriptBundleParseResult.NotBundle -> {
                    importRawScript(rawContent, sourceUrl = sourceUrl, fallbackName = fallbackName)
                        .map { SourceImportBatchResult(imported = listOf(it), skippedCount = 0) }
                }
                is ScriptBundleParseResult.InvalidBundle -> {
                    Result.failure(IllegalArgumentException(parsed.errorMessage))
                }
                is ScriptBundleParseResult.ValidBundle -> importParsedBundle(parsed)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importRawScript(
        script: String,
        sourceUrl: String? = null,
        fallbackName: String = "自定义音源"
    ): Result<UserApiSource> = withContext(Dispatchers.IO) {
        try {
            val normalizedScript = LxScriptParser.normalizeScript(script)
            require(normalizedScript.isNotBlank()) { "脚本内容为空" }
            require(!normalizedScript.contains('\u0000')) { "文件包含二进制内容，不是有效的 JS 音源脚本" }
            val scriptBytes = normalizedScript.toByteArray(StandardCharsets.UTF_8).size
            require(scriptBytes <= LxScriptParser.MAX_SINGLE_SCRIPT_BYTES) {
                "脚本体积过大（上限 1 MB），请导入标准 JS 音源文件"
            }
            val meta = LxScriptParser.parse(normalizedScript, fallbackName)
            val model = priorityMutex.withLock {
                val existing = requireDao().getAllOnce()
                val existingMatch = existing.firstOrNull { it.id == meta.id }
                val priority = existingMatch?.priority ?: ((existing.maxOfOrNull { it.priority } ?: -1) + 1)

                val entity = UserApiEntity(
                    id = meta.id,
                    name = meta.name,
                    description = meta.description,
                    version = meta.version,
                    author = meta.author,
                    homepage = meta.homepage,
                    script = normalizedScript,
                    sourceUrl = sourceUrl ?: existingMatch?.sourceUrl,
                    enabled = true,
                    priority = priority,
                    addedAt = existingMatch?.addedAt ?: System.currentTimeMillis()
                )
                requireDao().upsert(entity)

                val built = UserApiSource(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    version = entity.version,
                    author = entity.author,
                    homepage = entity.homepage,
                    script = entity.script,
                    sourceUrl = entity.sourceUrl,
                    enabled = true,
                    priority = entity.priority,
                    status = UserApiRuntimeStatus.INITIALIZING
                )
                bumpGeneration(built.id)
                _sources.update { list ->
                    val existsInMem = list.any { it.id == built.id }
                    val nextList = if (existsInMem) {
                        list.map { if (it.id == built.id) built else it }
                    } else {
                        list + built
                    }
                    nextList.sortedBy { it.priority }
                }
                built
            }
            scope.launch { startRuntime(model) }
            Result.success(model)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun toggleEnabled(id: String, enabled: Boolean) {
        bumpGeneration(id)
        requireDao().setEnabled(id, enabled)
        if (!enabled) {
            runtimes.remove(id)?.destroy()
            clearCooldownsForSource(id)
            updateSourceState(id) {
                it.copy(enabled = false, status = UserApiRuntimeStatus.IDLE, errorMessage = null, latencyMs = null)
            }
        } else {
            val target = _sources.value.firstOrNull { it.id == id }?.copy(
                enabled = true,
                status = UserApiRuntimeStatus.INITIALIZING,
                errorMessage = null,
                latencyMs = null
            ) ?: return
            clearCooldownsForSource(id)
            updateSourceState(id) {
                it.copy(
                    enabled = true,
                    status = UserApiRuntimeStatus.INITIALIZING,
                    errorMessage = null,
                    latencyMs = null
                )
            }
            scope.launch { startRuntime(target) }
        }
    }

    suspend fun removeSource(id: String) {
        bumpGeneration(id)
        runtimes.remove(id)?.destroy()
        clearCooldownsForSource(id)
        requireDao().delete(id)
        _sources.update { list -> list.filterNot { it.id == id } }
    }

    suspend fun refreshFromUrl(id: String): Result<UserApiSource> {
        val updateRes = updateSingleSource(id)
        return if (updateRes.errorMessage != null) {
            Result.failure(IllegalStateException(updateRes.errorMessage))
        } else {
            val latest = _sources.value.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalStateException("未找到该音源"))
            Result.success(latest)
        }
    }

    /**
     * Checks & updates a single custom source using its user-configured source URL.
     * Only restarts that source's QuickJS runtime if the script content actually changed.
     */
    suspend fun updateSingleSource(id: String): SourceUpdateResult = withContext(Dispatchers.IO) {
        val current = _sources.value.firstOrNull { it.id == id }
            ?: return@withContext SourceUpdateResult("未知音源", "", "", updated = false, alreadyLatest = false, errorMessage = "未找到该音源")

        val candidateUrls = SourceUrlUtils.buildUpdateCandidateUrls(current.sourceUrl)
        if (candidateUrls.isEmpty()) {
            return@withContext SourceUpdateResult(
                sourceName = current.name,
                oldVersion = current.version,
                newVersion = current.version,
                updated = false,
                alreadyLatest = false,
                errorMessage = "该本地脚本未绑定在线更新链接"
            )
        }

        var fetchedScript: String? = null
        var usedUrl: String? = null
        for (url in candidateUrls) {
            val content = try {
                downloadText(url)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            if (isValidDownloadedScript(content)) {
                fetchedScript = LxScriptParser.normalizeScript(content!!)
                usedUrl = url
                break
            }
        }

        if (fetchedScript == null) {
            return@withContext SourceUpdateResult(
                sourceName = current.name,
                oldVersion = current.version,
                newVersion = current.version,
                updated = false,
                alreadyLatest = false,
                errorMessage = "拉取最新脚本失败，请检查网络后重试"
            )
        }

        // Re-read live DB record after network I/O so we never resurrect a deleted source or revert user edits
        val liveEntity = requireDao().getAllOnce().firstOrNull { it.id == id }
            ?: return@withContext SourceUpdateResult(
                sourceName = current.name,
                oldVersion = current.version,
                newVersion = current.version,
                updated = false,
                alreadyLatest = false,
                errorMessage = "该音源已被删除"
            )
        val liveState = _sources.value.firstOrNull { it.id == id }

        val oldHash = LxCrypto.md5Hex(LxScriptParser.normalizeScript(liveEntity.script).trim())
        val newHash = LxCrypto.md5Hex(fetchedScript.trim())
        val parsed = LxScriptParser.parse(fetchedScript, fallbackName = liveEntity.name)

        if (oldHash == newHash) {
            if (liveEntity.enabled && liveState?.status == UserApiRuntimeStatus.ERROR) {
                startRuntime(liveState)
            }
            return@withContext SourceUpdateResult(
                sourceName = liveEntity.name,
                oldVersion = liveEntity.version,
                newVersion = parsed.version,
                updated = false,
                alreadyLatest = true
            )
        }

        // Preserve live source id, priority, enabled flag, and addedAt while hot-swapping script & metadata
        val updatedEntity = liveEntity.copy(
            name = parsed.name,
            description = parsed.description.ifBlank { liveEntity.description },
            version = parsed.version,
            author = parsed.author,
            homepage = parsed.homepage.ifBlank { liveEntity.homepage },
            script = fetchedScript,
            sourceUrl = usedUrl ?: liveEntity.sourceUrl
        )
        requireDao().upsert(updatedEntity)
        updateSourceState(id) { live ->
            live.copy(
                name = updatedEntity.name,
                description = updatedEntity.description,
                version = updatedEntity.version,
                author = updatedEntity.author,
                homepage = updatedEntity.homepage,
                script = updatedEntity.script,
                sourceUrl = updatedEntity.sourceUrl
            )
        }
        val refreshedLive = _sources.value.firstOrNull { it.id == id }
        if (updatedEntity.enabled && refreshedLive != null) {
            startRuntime(refreshedLive)
        }

        SourceUpdateResult(
            sourceName = updatedEntity.name,
            oldVersion = liveEntity.version,
            newVersion = updatedEntity.version,
            updated = true,
            alreadyLatest = false
        )
    }

    /**
     * Concurrently checks & updates all configured sources that have a user-bound remote URL.
     */
    suspend fun updateAllSources(): BatchUpdateSummary = withContext(Dispatchers.IO) {
        val currentList = _sources.value
        val results = coroutineScope {
            currentList.map { source ->
                async {
                    val urls = SourceUrlUtils.buildUpdateCandidateUrls(source.sourceUrl)
                    if (urls.isEmpty()) {
                        null // pure local script without remote URL
                    } else {
                        updateSingleSource(source.id)
                    }
                }
            }.awaitAll()
        }

        val updatedNames = mutableListOf<String>()
        var latestCount = 0
        var failedCount = 0
        var skippedLocal = 0

        results.forEach { res ->
            when {
                res == null -> skippedLocal++
                res.updated -> updatedNames.add("${res.sourceName} ${LxScriptParser.formatVersionBadge(res.newVersion)}")
                res.alreadyLatest -> latestCount++
                else -> failedCount++
            }
        }
        BatchUpdateSummary(
            updatedNames = updatedNames,
            latestCount = latestCount,
            failedCount = failedCount,
            skippedLocalCount = skippedLocal
        )
    }

    suspend fun movePriorityUp(id: String) = priorityMutex.withLock {
        val current = requireDao().getAllOnce().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return@withLock
        val item = current.removeAt(index)
        current.add(index - 1, item)
        val updated = current.mapIndexed { idx, entity -> entity.copy(priority = idx) }
        requireDao().updateAll(updated)
        clearCooldownsForSource(id)

        val priorityMap = updated.associate { it.id to it.priority }
        _sources.update { list ->
            list.map { src ->
                val newPriority = priorityMap[src.id]
                if (newPriority != null) src.copy(priority = newPriority) else src
            }.sortedBy { it.priority }
        }
    }

    suspend fun testSourceHealth(id: String): Result<Long> = withContext(Dispatchers.IO) {
        val source = _sources.value.firstOrNull { it.id == id }
            ?: return@withContext Result.failure(IllegalStateException("音源不存在"))
        if (!source.enabled) {
            return@withContext Result.failure(IllegalStateException("请先启用该音源后再测速"))
        }
        var runtime = runtimes[id]
        if (runtime == null) {
            startRuntime(source)
            runtime = runtimes[id]
        }
        val activeRuntime = runtime ?: return@withContext Result.failure(
            IllegalStateException(_sources.value.firstOrNull { it.id == id }?.errorMessage ?: "音源初始化失败")
        )

        val currentMeta = _sources.value.firstOrNull { it.id == id }
        val platformOrder = currentMeta?.supportedPlatforms?.keys
            ?.mapNotNull { code ->
                MusicSource.entries.firstOrNull {
                    it.code.equals(code, ignoreCase = true) && it.code in SEARCHABLE_PLATFORM_CODES
                }
            }
            ?.distinct()
            ?.sortedByDescending { platform ->
                // Prefer platforms with highest open-catalog availability for health checks first
                when (platform) {
                    MusicSource.KW -> 4
                    MusicSource.KG -> 3
                    MusicSource.TX -> 2
                    MusicSource.WY -> 1
                    else -> 0
                }
            }
            .orEmpty()
        if (platformOrder.isEmpty()) {
            val msg = "音源未声明可用音乐平台"
            updateSourceState(id) {
                if (!it.enabled) it else it.copy(status = UserApiRuntimeStatus.ERROR, latencyMs = null, errorMessage = msg)
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val runtimeGen = currentGeneration(id)
        var lastFriendlyError: String? = null
        var testedAnyTrack = false
        for (platform in platformOrder) {
            var probeTrack: Track? = null
            for (query in HEALTH_PROBE_QUERIES) {
                val found = try {
                    aggregator.searchSinglePlatform(platform, query, limit = 3).firstOrNull()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                if (found != null) {
                    probeTrack = found
                    break
                }
            }
            if (probeTrack == null) continue
            testedAnyTrack = true

            val start = System.currentTimeMillis()
            val probeRes = activeRuntime.requestMusicUrl(probeTrack, AudioQuality.Q128K, timeoutMs = 6_000L)
            val url = probeRes.getOrNull()
            val playable = url != null && verifyPlayableAudioUrl(url)
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)

            if (playable) {
                if (currentGeneration(id) == runtimeGen) {
                    clearCooldownsForSource(id)
                    updateSourceState(id) {
                        if (!it.enabled) it else it.copy(latencyMs = elapsed, status = UserApiRuntimeStatus.READY, errorMessage = null)
                    }
                }
                return@withContext Result.success(elapsed)
            } else {
                val rawErr = probeRes.exceptionOrNull()?.message ?: "返回链接不可播放"
                lastFriendlyError = LxQuickJsRuntime.formatFriendlyError(rawErr)
                if (currentGeneration(id) == runtimeGen) {
                    markCooldown(id, probeTrack.source.code)
                }
            }
        }

        val finalMsg = if (!testedAnyTrack) {
            "测试曲目检索失败，请检查网络"
        } else {
            lastFriendlyError ?: "当前各平台测试曲目均未返回可播链接"
        }
        // Keep an initialized QuickJS runtime READY so a temporary probe miss never permanently disables
        // playback across other tracks; surface a soft warning message on the card instead.
        if (currentGeneration(id) == runtimeGen) {
            updateSourceState(id) {
                if (!it.enabled) {
                    it
                } else {
                    it.copy(
                        status = UserApiRuntimeStatus.READY,
                        latencyMs = null,
                        errorMessage = "测速未通过：$finalMsg"
                    )
                }
            }
        }
        Result.failure(IllegalStateException(finalMsg))
    }

    /**
     * Priority-aware, time-bounded stream resolver:
     * 1. Explicit `preferredSourceId` tries its full downward quality chain before falling back to other sources.
     * 2. Normal playback honors user source priority (Priority #0 tried first; backup sources race in parallel on miss).
     * 3. Per-(source, platform) & per-(source, track) exclusions keep single-source cross-platform fallback alive.
     * 4. Strict total time budget (18s) + per-request dead-tier pruning prevents 30s-60s spinner stalls.
     */
    suspend fun resolveStream(
        track: Track,
        preferredQuality: AudioQuality,
        allowCrossPlatformFallback: Boolean = true,
        preferredSourceId: String? = null,
        excludedSourceIds: Set<String> = emptySet(),
        excludedTrackIds: Set<String> = emptySet(),
        excludedSourcePlatforms: Set<String> = emptySet(),
        excludedSourceTracks: Set<String> = emptySet()
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        awaitInitialLoadIfNeeded(preferredSourceId = preferredSourceId)
        val deadlineMs = System.currentTimeMillis() + RESOLVE_TOTAL_BUDGET_MS
        val readySources = _sources.value
            .filter {
                it.enabled &&
                    it.status == UserApiRuntimeStatus.READY &&
                    it.id !in excludedSourceIds
            }
            .sortedWith(compareByDescending<UserApiSource> { it.id == preferredSourceId }.thenBy { it.priority })

        if (readySources.isEmpty()) {
            val hasAny = _sources.value.isNotEmpty()
            val msg = when {
                excludedSourceIds.isNotEmpty() -> "所有已启用音源均无法播放《${track.title}》"
                hasAny -> "当前无可用自定义音源，请检查音源状态或重新测速启用"
                else -> "尚未导入自定义音源，点击前往导入 .js 自定义源"
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val hasPlatformSupport = hasReadySourceSupportingPlatform(readySources, track.source)
        if (!hasPlatformSupport && !allowCrossPlatformFallback) {
            return@withContext Result.failure(
                buildUnsupportedPlatformFailure(track, allowCrossPlatformFallback = false)
            )
        }

        val prunedSourcePlatforms = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        prunedSourcePlatforms.addAll(excludedSourcePlatforms)
        val prunedSourceTracks = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        prunedSourceTracks.addAll(excludedSourceTracks)
        val trackMissCounts = ConcurrentHashMap<String, AtomicInteger>()

        val attemptedTrackIds = excludedTrackIds.toMutableSet()
        val attachedAlts = if (allowCrossPlatformFallback) {
            track.alternates
                .filter { it.id !in excludedTrackIds }
                .sortedByDescending { MusicAggregator.platformPriority(it.source) }
        } else {
            emptyList()
        }
        var matchedCrossPlatformCount = attachedAlts.size

        // Phase A: Healthy (non-cooling) sources — Primary -> Attached Alts -> Dynamic Alts
        if (track.id !in excludedTrackIds) {
            attemptedTrackIds.add(track.id)
            tryResolveTracks(
                tracks = listOf(track),
                preferredQuality = preferredQuality,
                sources = readySources,
                switchedPlatform = false,
                preferredSourceId = preferredSourceId,
                prunedSourcePlatforms = prunedSourcePlatforms,
                prunedSourceTracks = prunedSourceTracks,
                trackMissCounts = trackMissCounts,
                includeCooling = false,
                deadlineMs = deadlineMs
            )?.let { return@withContext Result.success(it) }
        }

        var dynamicCandidates: List<Track> = emptyList()
        if (allowCrossPlatformFallback && System.currentTimeMillis() < deadlineMs) {
            if (attachedAlts.isNotEmpty()) {
                attachedAlts.forEach { attemptedTrackIds.add(it.id) }
                tryResolveTracks(
                    tracks = attachedAlts,
                    preferredQuality = preferredQuality,
                    sources = readySources,
                    switchedPlatform = true,
                    preferredSourceId = preferredSourceId,
                    prunedSourcePlatforms = prunedSourcePlatforms,
                    prunedSourceTracks = prunedSourceTracks,
                    trackMissCounts = trackMissCounts,
                    includeCooling = false,
                    deadlineMs = deadlineMs
                )?.let { return@withContext Result.success(it) }
            }

            if (System.currentTimeMillis() < deadlineMs) {
                val triedPlatformCodes = (listOf(track) + attachedAlts).map { it.source.code }.toSet()
                val searchableSupported = readySources
                    .flatMap { it.supportedPlatforms.keys }
                    .map { it.lowercase(Locale.ROOT) }
                    .filter { it in SEARCHABLE_PLATFORM_CODES }
                    .toSet()
                val remainingPlatformCodes = searchableSupported.filter { it !in triedPlatformCodes }.toSet()
                val searchPlatformCodes = remainingPlatformCodes.ifEmpty { searchableSupported }

                val searchBudgetMs = minOf(3_500L, (deadlineMs - System.currentTimeMillis()).coerceAtLeast(500L))
                val dynamicAlts = if (searchPlatformCodes.isNotEmpty()) {
                    withTimeoutOrNull(searchBudgetMs) {
                        aggregator.findAlternativeTracks(track, searchPlatformCodes)
                    }.orEmpty().filter { it.id !in attemptedTrackIds }
                } else {
                    emptyList()
                }
                matchedCrossPlatformCount += dynamicAlts.size

                if (dynamicAlts.isNotEmpty()) {
                    dynamicCandidates = dynamicAlts.distinctBy { it.source }.take(3)
                    dynamicCandidates.forEach { attemptedTrackIds.add(it.id) }
                    tryResolveTracks(
                        tracks = dynamicCandidates,
                        preferredQuality = preferredQuality,
                        sources = readySources,
                        switchedPlatform = true,
                        preferredSourceId = preferredSourceId,
                        prunedSourcePlatforms = prunedSourcePlatforms,
                        prunedSourceTracks = prunedSourceTracks,
                        trackMissCounts = trackMissCounts,
                        includeCooling = false,
                        deadlineMs = deadlineMs
                    )?.let { return@withContext Result.success(it) }
                }
            }
        }

        // Phase B: Cooling sources fallback ONLY after healthy primary & cross-platform alternates are exhausted
        if (System.currentTimeMillis() < deadlineMs) {
            if (track.id !in excludedTrackIds) {
                tryResolveTracks(
                    tracks = listOf(track),
                    preferredQuality = preferredQuality,
                    sources = readySources,
                    switchedPlatform = false,
                    preferredSourceId = preferredSourceId,
                    prunedSourcePlatforms = prunedSourcePlatforms,
                    prunedSourceTracks = prunedSourceTracks,
                    trackMissCounts = trackMissCounts,
                    includeCooling = true,
                    deadlineMs = deadlineMs
                )?.let { return@withContext Result.success(it) }
            }
            val allAlts = (attachedAlts + dynamicCandidates).distinctBy { it.id }
            if (allowCrossPlatformFallback && allAlts.isNotEmpty() && System.currentTimeMillis() < deadlineMs) {
                tryResolveTracks(
                    tracks = allAlts,
                    preferredQuality = preferredQuality,
                    sources = readySources,
                    switchedPlatform = true,
                    preferredSourceId = preferredSourceId,
                    prunedSourcePlatforms = prunedSourcePlatforms,
                    prunedSourceTracks = prunedSourceTracks,
                    trackMissCounts = trackMissCounts,
                    includeCooling = true,
                    deadlineMs = deadlineMs
                )?.let { return@withContext Result.success(it) }
            }
        }

        if (!hasPlatformSupport && matchedCrossPlatformCount == 0) {
            return@withContext Result.failure(
                buildUnsupportedPlatformFailure(track, allowCrossPlatformFallback = allowCrossPlatformFallback)
            )
        }
        Result.failure(IllegalStateException("所有音源与平台均无法获取《${track.title}》的可用音频流"))
    }

    private suspend fun tryResolveTracks(
        tracks: List<Track>,
        preferredQuality: AudioQuality,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        preferredSourceId: String?,
        prunedSourcePlatforms: MutableSet<String>,
        prunedSourceTracks: MutableSet<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        includeCooling: Boolean,
        deadlineMs: Long
    ): ResolvedStream? = raceCoordinator.tryResolveTracks(
        tracks = tracks,
        preferredQuality = preferredQuality,
        sources = sources,
        switchedPlatform = switchedPlatform,
        preferredSourceId = preferredSourceId,
        prunedSourcePlatforms = prunedSourcePlatforms,
        prunedSourceTracks = prunedSourceTracks,
        trackMissCounts = trackMissCounts,
        includeCooling = includeCooling,
        deadlineMs = deadlineMs
    )

    private suspend fun verifyPlayableAudioUrl(url: String): Boolean =
        verifyPlayableAudioUrlWithClient(probeClient, url)

    private fun cooldownKey(sourceId: String, platformCode: String): String =
        sourcePlatformKey(sourceId, platformCode)

    private fun isInCooldown(key: String): Boolean {
        val until = platformCooldowns[key] ?: return false
        if (System.currentTimeMillis() >= until) {
            platformCooldowns.remove(key)
            return false
        }
        return true
    }

    private fun markCooldown(sourceId: String, platformCode: String) {
        platformCooldowns[cooldownKey(sourceId, platformCode)] = System.currentTimeMillis() + COOLDOWN_MS
    }

    private fun clearCooldown(key: String) {
        platformCooldowns.remove(key)
    }

    fun clearCooldownsForSource(sourceId: String) {
        val prefix = "${sourceId}_"
        platformCooldowns.keys.filter { it.startsWith(prefix) }.forEach { platformCooldowns.remove(it) }
    }

    private fun updateSourceState(id: String, transform: (UserApiSource) -> UserApiSource) {
        _sources.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /**
     * Releases native QuickJS VMs for idle lower-priority backup sources when app moves to background
     * or memory is constrained. Never interrupts runtimes with active requests or initializing status.
     */
    fun trimIdleRuntimes(keepTopCount: Int = 2) {
        val snapshot = _sources.value
        val initializingIds = snapshot
            .filter { it.status == UserApiRuntimeStatus.INITIALIZING }
            .map { it.id }
            .toSet()
        val keepIds = snapshot
            .filter { it.enabled && it.status == UserApiRuntimeStatus.READY }
            .sortedBy { it.priority }
            .take(keepTopCount.coerceAtLeast(1))
            .map { it.id }
            .toSet() + initializingIds

        runtimes.entries.toList().forEach { (id, rt) ->
            if (id !in keepIds && !rt.hasActiveRequests()) {
                if (runtimes.remove(id, rt)) {
                    rt.destroy()
                    Log.d(TAG, "Trimmed idle backup QuickJS runtime $id to conserve RAM")
                }
            }
        }
    }

    private suspend fun ensureRuntimeReady(source: UserApiSource): LxQuickJsRuntime? {
        runtimes[source.id]?.let { return it }
        return mutexFor(source.id).withLock {
            runtimes[source.id]?.let { return@withLock it }
            val liveSource = _sources.value.firstOrNull { it.id == source.id }
            if (liveSource == null || !liveSource.enabled) return@withLock null
            val gen = currentGeneration(source.id)

            LxQuickJsRuntime.ensureQuickJsLoaded()
            val runtime = LxQuickJsRuntime(requireContext(), httpClient, liveSource)
            val init = try {
                runtime.initialize(timeoutMs = 5_000L)
            } catch (ce: CancellationException) {
                runtime.destroy()
                throw ce
            }

            val postLive = _sources.value.firstOrNull { it.id == source.id }
            if (init.success && currentGeneration(source.id) == gen && postLive != null && postLive.enabled) {
                runtimes.put(source.id, runtime)?.takeIf { it !== runtime }?.destroy()
                val verifiedLive = _sources.value.firstOrNull { it.id == source.id }
                if (currentGeneration(source.id) != gen || verifiedLive == null || !verifiedLive.enabled) {
                    if (runtimes.remove(source.id, runtime)) {
                        runtime.destroy()
                    }
                    null
                } else {
                    runtime
                }
            } else {
                runtime.destroy()
                null
            }
        }
    }

    fun hasReadySource(): Boolean =
        _sources.value.any { it.enabled && it.status == UserApiRuntimeStatus.READY }
}

internal class SourceRaceCoordinator(
    private val isMeteredNetwork: () -> Boolean,
    private val isInCooldown: (String) -> Boolean,
    private val markCooldown: (sourceId: String, platformCode: String) -> Unit,
    private val clearCooldown: (String) -> Unit,
    private val runtimeLookup: (String) -> LxQuickJsRuntime? = { null },
    private val onSourceLatencyMeasured: (sourceId: String, elapsedMs: Long) -> Unit = { _, _ -> },
    private val fetchStreamUrl: suspend (UserApiManager.ResolveCandidate, Long) -> Result<String>,
    private val verifyStreamUrl: suspend (String) -> Boolean,
    private val logDebug: (String) -> Unit = {},
    private val logWarn: (String) -> Unit = {},
    private val scriptUrlTimeoutMs: Long = UserApiManager.SCRIPT_URL_TIMEOUT_MS,
    private val streamProbeTimeoutMs: Long = UserApiManager.STREAM_PROBE_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    suspend fun tryResolveTracks(
        tracks: List<Track>,
        preferredQuality: AudioQuality,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        preferredSourceId: String?,
        prunedSourcePlatforms: MutableSet<String>,
        prunedSourceTracks: MutableSet<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        includeCooling: Boolean,
        deadlineMs: Long
    ): ResolvedStream? {
        val qualityChain = AudioQuality.fallbackChain(preferredQuality)

        // When user explicitly selected a source, honor it across its full downward quality chain first
        // (bypassing cooldown in Phase A so explicit manual source picks always try the chosen source).
        if (!preferredSourceId.isNullOrBlank()) {
            val preferredSources = sources.filter { it.id == preferredSourceId }
            val otherSources = sources.filterNot { it.id == preferredSourceId }
            if (preferredSources.isNotEmpty()) {
                if (!includeCooling) {
                    for (quality in qualityChain) {
                        if (nowMs() >= deadlineMs) return null
                        val candidates = buildTierCandidates(
                            tracks = tracks,
                            quality = quality,
                            sources = preferredSources,
                            switchedPlatform = switchedPlatform,
                            prunedSourcePlatforms = prunedSourcePlatforms,
                            prunedSourceTracks = prunedSourceTracks,
                            trackMissCounts = trackMissCounts,
                            includeCooling = false,
                            ignoreCooldown = true
                        )
                        if (candidates.isEmpty()) continue
                        raceFirstValid(
                            candidates = candidates,
                            prunedSourcePlatforms = prunedSourcePlatforms,
                            prunedSourceTracks = prunedSourceTracks,
                            trackMissCounts = trackMissCounts,
                            switchedSourceOnWin = false,
                            deadlineMs = deadlineMs
                        )?.let { return it }
                    }
                }
                if (otherSources.isEmpty()) return null
                return tryResolveQualityTiersOrdered(
                    tracks = tracks,
                    qualityChain = qualityChain,
                    sources = otherSources,
                    switchedPlatform = switchedPlatform,
                    prunedSourcePlatforms = prunedSourcePlatforms,
                    prunedSourceTracks = prunedSourceTracks,
                    trackMissCounts = trackMissCounts,
                    includeCooling = includeCooling,
                    initialSwitched = true,
                    deadlineMs = deadlineMs
                )
            }
        }

        return tryResolveQualityTiersOrdered(
            tracks = tracks,
            qualityChain = qualityChain,
            sources = sources,
            switchedPlatform = switchedPlatform,
            prunedSourcePlatforms = prunedSourcePlatforms,
            prunedSourceTracks = prunedSourceTracks,
            trackMissCounts = trackMissCounts,
            includeCooling = includeCooling,
            initialSwitched = false,
            deadlineMs = deadlineMs
        )
    }

    suspend fun tryResolveQualityTiersOrdered(
        tracks: List<Track>,
        qualityChain: List<AudioQuality>,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        prunedSourcePlatforms: MutableSet<String>,
        prunedSourceTracks: MutableSet<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        includeCooling: Boolean,
        initialSwitched: Boolean,
        deadlineMs: Long
    ): ResolvedStream? {
        for (quality in qualityChain) {
            if (nowMs() >= deadlineMs) return null
            val candidates = buildTierCandidates(
                tracks = tracks,
                quality = quality,
                sources = sources,
                switchedPlatform = switchedPlatform,
                prunedSourcePlatforms = prunedSourcePlatforms,
                prunedSourceTracks = prunedSourceTracks,
                trackMissCounts = trackMissCounts,
                includeCooling = includeCooling
            )
            if (candidates.isEmpty()) continue

            // Race all ready non-cooling sources concurrently in balanced waves (including Priority #0)
            // so the fastest valid playable stream wins immediately and cancels slower losing tasks.
            raceInBalancedWaves(
                candidates = candidates,
                prunedSourcePlatforms = prunedSourcePlatforms,
                prunedSourceTracks = prunedSourceTracks,
                trackMissCounts = trackMissCounts,
                initialSwitched = initialSwitched,
                deadlineMs = deadlineMs
            )?.let { return it }
        }
        return null
    }

    private fun buildTierCandidates(
        tracks: List<Track>,
        quality: AudioQuality,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        prunedSourcePlatforms: Set<String>,
        prunedSourceTracks: Set<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        includeCooling: Boolean,
        ignoreCooldown: Boolean = false
    ): List<UserApiManager.ResolveCandidate> {
        val result = mutableListOf<UserApiManager.ResolveCandidate>()
        for (track in tracks) {
            val platformCode = track.source.code.lowercase(Locale.ROOT)
            for (source in sources) {
                val supportedQualities = source.supportedPlatforms[platformCode] ?: continue
                val supportsTier = quality.lxType in supportedQualities ||
                    (supportedQualities.isEmpty() && quality == AudioQuality.Q128K)
                if (!supportsTier) continue

                val platKey = UserApiManager.sourcePlatformKey(source.id, platformCode)
                if (platKey in prunedSourcePlatforms) continue
                val trackKey = UserApiManager.sourceTrackKey(source.id, track.id)
                if (trackKey in prunedSourceTracks) continue
                // Skip redundant intermediate tiers (e.g. 320k after FLAC24 + FLAC both missed),
                // but NEVER skip Q128K fallback which is frequently the only free/playable tier.
                if (quality != AudioQuality.Q128K && (trackMissCounts[trackKey]?.get() ?: 0) >= 2) continue
                if (!ignoreCooldown) {
                    val cooling = isInCooldown(platKey)
                    if (includeCooling != cooling) continue
                }

                result.add(
                    UserApiManager.ResolveCandidate(
                        track = track,
                        source = source,
                        runtime = runtimeLookup(source.id),
                        quality = quality,
                        switchedPlatform = switchedPlatform
                    )
                )
            }
        }
        return result
    }

    private suspend fun raceInBalancedWaves(
        candidates: List<UserApiManager.ResolveCandidate>,
        prunedSourcePlatforms: MutableSet<String>,
        prunedSourceTracks: MutableSet<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        initialSwitched: Boolean,
        deadlineMs: Long
    ): ResolvedStream? {
        val waveSize = if (isMeteredNetwork()) 2 else 3
        candidates.chunked(waveSize).forEachIndexed { waveIdx, wave ->
            if (nowMs() >= deadlineMs) return null
            raceFirstValid(
                candidates = wave,
                prunedSourcePlatforms = prunedSourcePlatforms,
                prunedSourceTracks = prunedSourceTracks,
                trackMissCounts = trackMissCounts,
                switchedSourceOnWin = initialSwitched || waveIdx > 0,
                deadlineMs = deadlineMs
            )?.let { return it }
        }
        return null
    }

    suspend fun raceFirstValid(
        candidates: List<UserApiManager.ResolveCandidate>,
        prunedSourcePlatforms: MutableSet<String>,
        prunedSourceTracks: MutableSet<String>,
        trackMissCounts: ConcurrentHashMap<String, AtomicInteger>,
        switchedSourceOnWin: Boolean,
        deadlineMs: Long
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null
        val remainingBudgetMs = deadlineMs - nowMs()
        if (remainingBudgetMs <= 250L) return@withContext null

        val winner = CompletableDeferred<ResolvedStream?>()
        val remaining = AtomicInteger(candidates.size)
        val raceJob = SupervisorJob(coroutineContext[Job])
        val raceScope = CoroutineScope(coroutineContext + raceJob)

        candidates.forEach { candidate ->
            raceScope.launch {
                val platformCode = candidate.track.source.code.lowercase(Locale.ROOT)
                val platKey = UserApiManager.sourcePlatformKey(candidate.source.id, platformCode)
                val trackKey = UserApiManager.sourceTrackKey(candidate.source.id, candidate.track.id)
                val startMs = nowMs()
                try {
                    val reqBudget = minOf(
                        scriptUrlTimeoutMs,
                        (deadlineMs - nowMs()).coerceAtLeast(500L)
                    )
                    val res = fetchStreamUrl(candidate, reqBudget)
                    if (res.isSuccess) {
                        val url = res.getOrThrow()
                        if (verifyStreamUrl(url)) {
                            val elapsed = (nowMs() - startMs).coerceAtLeast(1L)
                            clearCooldown(platKey)
                            onSourceLatencyMeasured(candidate.source.id, elapsed)
                            logDebug(
                                "Resolved ${candidate.track.title} [$platformCode/${candidate.quality.lxType}] via ${candidate.source.name} (${elapsed}ms)"
                            )
                            val resolved = ResolvedStream(
                                url = url,
                                quality = candidate.quality,
                                resolvedTrack = candidate.track,
                                sourceId = candidate.source.id,
                                providerName = candidate.source.name,
                                switchedPlatform = candidate.switchedPlatform,
                                switchedSource = switchedSourceOnWin,
                                sourceRevision = UserApiManager.computeSourceRevision(candidate.source)
                            )
                            if (winner.complete(resolved)) {
                                raceJob.cancel()
                            }
                            return@launch
                        } else {
                            logWarn(
                                "Stream probe rejected dead/invalid URL from ${candidate.source.name} ($platformCode/${candidate.quality.lxType})"
                            )
                            trackMissCounts.computeIfAbsent(trackKey) { AtomicInteger(0) }.incrementAndGet()
                            if (candidate.quality == AudioQuality.Q128K) {
                                prunedSourceTracks.add(trackKey)
                            }
                        }
                    } else {
                        val errMsg = res.exceptionOrNull()?.message
                        logWarn(
                            "Script resolve failed from ${candidate.source.name} ($platformCode/${candidate.quality.lxType}): $errMsg"
                        )
                        when {
                            UserApiManager.isTransportOrRuntimeFailure(errMsg) -> {
                                prunedSourcePlatforms.add(platKey)
                                markCooldown(candidate.source.id, platformCode)
                            }
                            UserApiManager.isDeterministicTrackMiss(errMsg) -> {
                                prunedSourceTracks.add(trackKey)
                            }
                            else -> {
                                trackMissCounts.computeIfAbsent(trackKey) { AtomicInteger(0) }.incrementAndGet()
                                if (candidate.quality == AudioQuality.Q128K) {
                                    prunedSourceTracks.add(trackKey)
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logWarn("Race candidate error (${candidate.source.name}): ${t.message}")
                    if (UserApiManager.isTransportOrRuntimeFailure(t.message)) {
                        prunedSourcePlatforms.add(platKey)
                        markCooldown(candidate.source.id, platformCode)
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                        winner.complete(null)
                    }
                }
            }
        }

        val waveTimeoutMs = minOf(
            scriptUrlTimeoutMs + streamProbeTimeoutMs + 600L,
            remainingBudgetMs
        )
        try {
            withTimeoutOrNull(waveTimeoutMs) {
                winner.await()
            }
        } finally {
            raceJob.cancel()
        }
    }
}
