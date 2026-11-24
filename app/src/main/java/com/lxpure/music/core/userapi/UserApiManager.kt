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
    val switchedSource: Boolean = false
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

class UserApiManager(
    private val context: Context,
    private val dao: UserApiDao,
    private val httpClient: OkHttpClient,
    private val aggregator: MusicAggregator,
    private val networkAdvisor: NetworkQualityAdvisor
) {
    companion object {
        private const val TAG = "UserApiManager"
        private const val SCRIPT_URL_TIMEOUT_MS = 4_500L
        private const val STREAM_PROBE_TIMEOUT_MS = 2_500L
        private const val COOLDOWN_MS = 45_000L
        private const val MAX_SCRIPT_DOWNLOAD_BYTES = 2 * 1024 * 1024 // 2 MB cap

        /**
         * Validates a downloaded custom source script while rejecting HTML/JSON error pages
         * (including those prefixed with a UTF-8 BOM).
         */
        fun isValidDownloadedScript(content: String?): Boolean {
            if (content.isNullOrBlank() || content.length < 150) return false
            val cleaned = content.removePrefix("\uFEFF").trimStart()
            if (cleaned.isEmpty() || cleaned.startsWith("<") || cleaned.startsWith("{")) return false
            return cleaned.contains("lx", ignoreCase = true) ||
                cleaned.contains("\\x6c\\x78", ignoreCase = true) ||
                cleaned.contains("@name") ||
                cleaned.contains("globalThis")
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
    }

    private data class ResolveCandidate(
        val track: Track,
        val source: UserApiSource,
        val runtime: LxQuickJsRuntime?,
        val quality: AudioQuality,
        val switchedPlatform: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimes = ConcurrentHashMap<String, LxQuickJsRuntime>()
    private val sourceMutexes = ConcurrentHashMap<String, Mutex>()
    private val sourceGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val platformCooldowns = ConcurrentHashMap<String, Long>()

    private val probeClient = httpClient.newBuilder()
        .cache(null)
        .connectTimeout(STREAM_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(STREAM_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(STREAM_PROBE_TIMEOUT_MS + 300L, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _sources = MutableStateFlow<List<UserApiSource>>(emptyList())
    val sources: StateFlow<List<UserApiSource>> = _sources.asStateFlow()
    private val initialDbLoadDone = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                reloadAllFromDatabase()
            } finally {
                initialDbLoadDone.complete(Unit)
            }
        }
    }

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
    suspend fun awaitInitialLoadIfNeeded(timeoutMs: Long = 4_000L) {
        withTimeoutOrNull(timeoutMs) {
            initialDbLoadDone.await()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val list = _sources.value
                val hasReady = list.any { it.enabled && it.status == UserApiRuntimeStatus.READY }
                val hasInitializing = list.any { it.enabled && it.status == UserApiRuntimeStatus.INITIALIZING }
                if (hasReady || !hasInitializing) break
                kotlinx.coroutines.delay(60L)
            }
        }
    }

    suspend fun reloadAllFromDatabase() {
        LxQuickJsRuntime.ensureQuickJsLoaded()
        val entities = dao.getAllOnce()
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

            val runtime = LxQuickJsRuntime(context, httpClient, liveSource)
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
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
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
            require(scriptBytes <= 1 * 1024 * 1024) { "脚本体积过大（上限 1 MB），请导入标准 JS 音源文件" }
            val meta = LxScriptParser.parse(normalizedScript, fallbackName)
            val existing = dao.getAllOnce()
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
            dao.upsert(entity)

            val model = UserApiSource(
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
            _sources.update { list ->
                val existsInMem = list.any { it.id == model.id }
                val nextList = if (existsInMem) {
                    list.map { if (it.id == model.id) model else it }
                } else {
                    list + model
                }
                nextList.sortedBy { it.priority }
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
        dao.setEnabled(id, enabled)
        if (!enabled) {
            runtimes.remove(id)?.destroy()
            clearCooldownsForSource(id)
            updateSourceState(id) {
                it.copy(enabled = false, status = UserApiRuntimeStatus.IDLE, errorMessage = null, latencyMs = null)
            }
        } else {
            val target = _sources.value.firstOrNull { it.id == id }?.copy(enabled = true) ?: return
            updateSourceState(id) { it.copy(enabled = true) }
            scope.launch { startRuntime(target) }
        }
    }

    suspend fun removeSource(id: String) {
        bumpGeneration(id)
        runtimes.remove(id)?.destroy()
        clearCooldownsForSource(id)
        dao.delete(id)
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
        val liveEntity = dao.getAllOnce().firstOrNull { it.id == id }
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
        dao.upsert(updatedEntity)
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

    suspend fun movePriorityUp(id: String) {
        val current = dao.getAllOnce().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return
        val item = current.removeAt(index)
        current.add(index - 1, item)
        val updated = current.mapIndexed { idx, entity -> entity.copy(priority = idx) }
        dao.updateAll(updated)

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
                    it.code.equals(code, ignoreCase = true) && it != MusicSource.LOCAL
                }
            }
            ?.distinct()
            ?.sortedByDescending { MusicAggregator.platformPriority(it) }
            .orEmpty()
        if (platformOrder.isEmpty()) {
            val msg = "音源未声明可用音乐平台"
            updateSourceState(id) {
                it.copy(status = UserApiRuntimeStatus.ERROR, latencyMs = null, errorMessage = msg)
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        var probeTrack: Track? = null
        for (platform in platformOrder) {
            val found = try {
                aggregator.searchSinglePlatform(platform, "晴天", limit = 3).firstOrNull()
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
        if (probeTrack == null) {
            val msg = "测试曲目检索失败，请检查网络"
            updateSourceState(id) {
                it.copy(status = UserApiRuntimeStatus.ERROR, latencyMs = null, errorMessage = msg)
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val start = System.currentTimeMillis()
        val probeRes = activeRuntime.requestMusicUrl(probeTrack, AudioQuality.Q128K, timeoutMs = 6_000L)
        val url = probeRes.getOrNull()
        val playable = url != null && verifyPlayableAudioUrl(url)
        val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)

        if (playable) {
            clearCooldownsForSource(id)
            updateSourceState(id) {
                it.copy(latencyMs = elapsed, status = UserApiRuntimeStatus.READY, errorMessage = null)
            }
            Result.success(elapsed)
        } else {
            val rawErr = probeRes.exceptionOrNull()?.message ?: "返回链接不可播放"
            val friendly = LxQuickJsRuntime.formatFriendlyError(rawErr)
            markCooldown(id, probeTrack.source.code)
            updateSourceState(id) {
                it.copy(
                    status = UserApiRuntimeStatus.ERROR,
                    latencyMs = null,
                    errorMessage = "测速失败：$friendly"
                )
            }
            Result.failure(IllegalStateException(friendly))
        }
    }

    /**
     * High-speed concurrent racing stream resolver:
     * 1. Quality-tier-first parallel racing across all ready sources (guarantees SQ/HR priority over single-source downgrade)
     * 2. Per-source/platform pruning & 45s cooldown ONLY on transport/runtime failures (preserves FLAC -> 320k -> 128k fallback)
     * 3. Live cancellable HTTP Range audio-header probe (2.5s timeout; first valid URL wins & cancels all siblings)
     * 4. Parallel cross-platform fallback across attached alternates & dynamic fuzzy search matches
     */
    suspend fun resolveStream(
        track: Track,
        preferredQuality: AudioQuality,
        allowCrossPlatformFallback: Boolean = true,
        preferredSourceId: String? = null,
        excludedSourceIds: Set<String> = emptySet(),
        excludedTrackIds: Set<String> = emptySet()
    ): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        awaitInitialLoadIfNeeded()
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

        val prunedSourcePlatforms = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        // 1. Primary track: race across ready sources by quality tier
        if (track.id !in excludedTrackIds) {
            tryResolveTracksByQualityTiers(
                tracks = listOf(track),
                preferredQuality = preferredQuality,
                sources = readySources,
                switchedPlatform = false,
                preferredSourceId = preferredSourceId,
                prunedSourcePlatforms = prunedSourcePlatforms
            )?.let { return@withContext Result.success(it) }
        }

        // 2. Cross-platform fallback: race across attached deduplicated alternates in parallel
        if (allowCrossPlatformFallback) {
            val attachedAlts = track.alternates
                .filter { it.id !in excludedTrackIds }
                .sortedByDescending { MusicAggregator.platformPriority(it.source) }
            if (attachedAlts.isNotEmpty()) {
                tryResolveTracksByQualityTiers(
                    tracks = attachedAlts,
                    preferredQuality = preferredQuality,
                    sources = readySources,
                    switchedPlatform = true,
                    preferredSourceId = preferredSourceId,
                    prunedSourcePlatforms = prunedSourcePlatforms
                )?.let { return@withContext Result.success(it) }
            }

            // 3. Dynamic cross-platform search & fuzzy match across remaining platforms
            val triedPlatformCodes = (listOf(track) + attachedAlts).map { it.source.code }.toSet()
            val remainingPlatformCodes = readySources
                .flatMap { it.supportedPlatforms.keys }
                .filter { it !in triedPlatformCodes }
                .toSet()
            val searchPlatformCodes = remainingPlatformCodes.ifEmpty {
                readySources.flatMap { it.supportedPlatforms.keys }.toSet()
            }
            val dynamicAlts = withTimeoutOrNull(3_500L) {
                aggregator.findAlternativeTracks(track, searchPlatformCodes)
            }.orEmpty().filter { it.id !in excludedTrackIds }

            if (dynamicAlts.isNotEmpty()) {
                val bestPerPlatform = dynamicAlts.distinctBy { it.source }.take(3)
                tryResolveTracksByQualityTiers(
                    tracks = bestPerPlatform,
                    preferredQuality = preferredQuality,
                    sources = readySources,
                    switchedPlatform = true,
                    preferredSourceId = preferredSourceId,
                    prunedSourcePlatforms = prunedSourcePlatforms
                )?.let { return@withContext Result.success(it) }
            }
        }

        Result.failure(IllegalStateException("所有音源与平台均无法获取《${track.title}》的可用音频流"))
    }

    private suspend fun tryResolveTracksByQualityTiers(
        tracks: List<Track>,
        preferredQuality: AudioQuality,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        preferredSourceId: String?,
        prunedSourcePlatforms: MutableSet<String>
    ): ResolvedStream? {
        val qualityChain = AudioQuality.fallbackChain(preferredQuality)

        // Pass 1: Healthy (non-cooling) sources first across all quality tiers
        for (quality in qualityChain) {
            val candidates = buildTierCandidates(
                tracks = tracks,
                quality = quality,
                sources = sources,
                switchedPlatform = switchedPlatform,
                prunedSourcePlatforms = prunedSourcePlatforms,
                includeCooling = false
            )
            if (candidates.isEmpty()) continue
            raceTier(candidates, preferredSourceId, prunedSourcePlatforms)?.let { return it }
        }

        // Pass 2: Fallback to cooling sources that haven't already failed in this request
        for (quality in qualityChain) {
            val candidates = buildTierCandidates(
                tracks = tracks,
                quality = quality,
                sources = sources,
                switchedPlatform = switchedPlatform,
                prunedSourcePlatforms = prunedSourcePlatforms,
                includeCooling = true
            )
            if (candidates.isEmpty()) continue
            raceTier(candidates, preferredSourceId, prunedSourcePlatforms)?.let { return it }
        }

        return null
    }

    private fun buildTierCandidates(
        tracks: List<Track>,
        quality: AudioQuality,
        sources: List<UserApiSource>,
        switchedPlatform: Boolean,
        prunedSourcePlatforms: Set<String>,
        includeCooling: Boolean
    ): List<ResolveCandidate> {
        val result = mutableListOf<ResolveCandidate>()
        for (track in tracks) {
            val platformCode = track.source.code
            for (source in sources) {
                val supportedQualities = source.supportedPlatforms[platformCode] ?: continue
                val supportsTier = quality.lxType in supportedQualities ||
                    (supportedQualities.isEmpty() && quality == AudioQuality.Q128K)
                if (!supportsTier) continue

                val key = cooldownKey(source.id, platformCode)
                if (key in prunedSourcePlatforms) continue
                val cooling = isInCooldown(key)
                if (includeCooling != cooling) continue

                val runtime = runtimes[source.id]
                result.add(ResolveCandidate(track, source, runtime, quality, switchedPlatform))
            }
        }
        return result
    }

    private suspend fun raceTier(
        candidates: List<ResolveCandidate>,
        preferredSourceId: String?,
        prunedSourcePlatforms: MutableSet<String>
    ): ResolvedStream? {
        if (candidates.isEmpty()) return null

        if (!preferredSourceId.isNullOrBlank()) {
            val preferred = candidates.filter { it.source.id == preferredSourceId }
            val others = candidates.filterNot { it.source.id == preferredSourceId }
            if (preferred.isNotEmpty()) {
                raceFirstValid(preferred, prunedSourcePlatforms, switchedSourceOnWin = false)?.let { return it }
                if (others.isNotEmpty()) {
                    return raceInBalancedWaves(others, prunedSourcePlatforms, initialSwitched = true)
                }
                return null
            }
        }

        return raceInBalancedWaves(candidates, prunedSourcePlatforms, initialSwitched = false)
    }

    private suspend fun raceInBalancedWaves(
        candidates: List<ResolveCandidate>,
        prunedSourcePlatforms: MutableSet<String>,
        initialSwitched: Boolean
    ): ResolvedStream? {
        val waveSize = if (networkAdvisor.isMeteredNetwork()) 2 else 3
        candidates.chunked(waveSize).forEachIndexed { waveIdx, wave ->
            raceFirstValid(
                candidates = wave,
                prunedSourcePlatforms = prunedSourcePlatforms,
                switchedSourceOnWin = initialSwitched || waveIdx > 0
            )?.let { return it }
        }
        return null
    }

    private suspend fun raceFirstValid(
        candidates: List<ResolveCandidate>,
        prunedSourcePlatforms: MutableSet<String>,
        switchedSourceOnWin: Boolean
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext null

        val winner = CompletableDeferred<ResolvedStream?>()
        val remaining = AtomicInteger(candidates.size)
        val raceJob = SupervisorJob(coroutineContext[Job])
        val raceScope = CoroutineScope(coroutineContext + raceJob)

        candidates.forEach { candidate ->
            raceScope.launch {
                val platformCode = candidate.track.source.code
                val key = cooldownKey(candidate.source.id, platformCode)
                val startMs = System.currentTimeMillis()
                try {
                    val activeRuntime = candidate.runtime ?: ensureRuntimeReady(candidate.source)
                    if (activeRuntime == null) {
                        prunedSourcePlatforms.add(key)
                        markCooldown(candidate.source.id, platformCode)
                        return@launch
                    }
                    val res = activeRuntime.requestMusicUrl(
                        track = candidate.track,
                        quality = candidate.quality,
                        timeoutMs = SCRIPT_URL_TIMEOUT_MS
                    )
                    if (res.isSuccess) {
                        val url = res.getOrThrow()
                        if (verifyPlayableAudioUrl(url)) {
                            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            clearCooldown(key)
                            updateSourceState(candidate.source.id) {
                                if (it.status == UserApiRuntimeStatus.READY) {
                                    it.copy(latencyMs = elapsed)
                                } else {
                                    it
                                }
                            }
                            Log.d(
                                TAG,
                                "Resolved ${candidate.track.title} [$platformCode/${candidate.quality.lxType}] via ${candidate.source.name} (${elapsed}ms)"
                            )
                            val resolved = ResolvedStream(
                                url = url,
                                quality = candidate.quality,
                                resolvedTrack = candidate.track,
                                sourceId = candidate.source.id,
                                providerName = candidate.source.name,
                                switchedPlatform = candidate.switchedPlatform,
                                switchedSource = switchedSourceOnWin
                            )
                            if (winner.complete(resolved)) {
                                raceJob.cancel()
                            }
                            return@launch
                        } else {
                            Log.w(
                                TAG,
                                "Stream probe rejected dead/invalid URL from ${candidate.source.name} ($platformCode/${candidate.quality.lxType})"
                            )
                        }
                    } else {
                        val errMsg = res.exceptionOrNull()?.message
                        Log.w(
                            TAG,
                            "Script resolve failed from ${candidate.source.name} ($platformCode/${candidate.quality.lxType}): $errMsg"
                        )
                        // Only prune & cooldown on transport/runtime failures so FLAC -> 320k -> 128k fallback stays intact
                        if (isTransportOrRuntimeFailure(errMsg)) {
                            prunedSourcePlatforms.add(key)
                            markCooldown(candidate.source.id, platformCode)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Race candidate error (${candidate.source.name}): ${t.message}")
                    if (isTransportOrRuntimeFailure(t.message)) {
                        prunedSourcePlatforms.add(key)
                        markCooldown(candidate.source.id, platformCode)
                    }
                } finally {
                    if (remaining.decrementAndGet() == 0 && !winner.isCompleted) {
                        winner.complete(null)
                    }
                }
            }
        }

        try {
            withTimeoutOrNull(SCRIPT_URL_TIMEOUT_MS + STREAM_PROBE_TIMEOUT_MS + 600L) {
                winner.await()
            }
        } finally {
            raceJob.cancel()
        }
    }

    private suspend fun verifyPlayableAudioUrl(url: String): Boolean = suspendCancellableCoroutine { cont ->
        val req = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-127")
                .get()
                .build()
        }.getOrElse {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val call = probeClient.newCall(req)
        cont.invokeOnCancellation {
            runCatching { call.cancel() }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.use { resp ->
                    if (resp.code !in listOf(200, 206)) return@use false
                    val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                    if (contentType.contains("text/html") ||
                        contentType.contains("application/json") ||
                        contentType.contains("text/plain")
                    ) {
                        return@use false
                    }
                    val contentLength = resp.header("Content-Length")?.toLongOrNull()
                    if (contentLength != null && contentLength == 0L) return@use false
                    true
                }
                if (cont.isActive) cont.resume(ok)
            }
        })
    }

    private fun cooldownKey(sourceId: String, platformCode: String): String = "${sourceId}_${platformCode}"

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

    private fun clearCooldownsForSource(sourceId: String) {
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
            val runtime = LxQuickJsRuntime(context, httpClient, liveSource)
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
