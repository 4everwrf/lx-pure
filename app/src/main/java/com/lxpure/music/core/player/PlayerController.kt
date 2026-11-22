package com.lxpure.music.core.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.lxpure.music.core.download.DownloadManager
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.network.NetworkQualityAdvisor
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.data.local.SettingsDataStore
import com.lxpure.music.data.repository.LibraryRepository
import com.lxpure.music.domain.model.AppSettings
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

data class AmbientPalette(
    val primary: Color = Color(0xFF1E3A5F),
    val secondary: Color = Color(0xFF312E81),
    val accent: Color = Color(0xFF38BDF8)
)

data class PlayerUiEvent(
    val message: String,
    val actionLabel: String? = null,
    val openSourceManager: Boolean = false
)

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.LIST_LOOP,
    val activeQualityBadge: String? = null,
    val activeProviderName: String? = null,
    val lyrics: ParsedLyrics = ParsedLyrics(),
    val isLoadingLyrics: Boolean = false,
    val ambientPalette: AmbientPalette = AmbientPalette(),
    val sleepTimerRemainingMs: Long? = null
) {
    val currentTrack: Track? get() = queue.getOrNull(currentIndex)
    val activeLyricIndex: Int get() = lyrics.activeIndexAt(positionMs)
}

class PlayerController(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val networkAdvisor: NetworkQualityAdvisor,
    private val settingsDataStore: SettingsDataStore,
    private val libraryRepository: LibraryRepository,
    private val userApiManager: UserApiManager,
    private val musicAggregator: MusicAggregator,
    private val downloadManager: DownloadManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerUiEvent> = _events.asSharedFlow()

    private var mediaController: MediaController? = null
    private var resolveJob: Job? = null
    private var coverJob: Job? = null
    private var lyricJob: Job? = null
    private var paletteJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isConnecting = false

    // Guards against stale progress flicker & false auto-advance when switching or failing tracks
    private var isAwaitingMediaLoad = false
    private var hasEnteredReadyForActiveMedia = false
    private var activeExpectedMediaId: String? = null

    // Tracks failed sources & playback retries for the currently playing song to guarantee seamless runtime failover
    private var activeStreamSourceId: String? = null
    private var activeResolvedTrackId: String? = null
    private val failedSourceIdsForCurrentTrack = mutableSetOf<String>()
    private val failedTrackIdsForCurrentTrack = mutableSetOf<String>()
    private var runtimeRetryCount = 0

    init {
        startProgressTicker()
    }

    private suspend fun ensureController(): MediaController? {
        mediaController?.let { if (it.isConnected) return it }
        if (isConnecting) {
            repeat(20) {
                delay(100)
                mediaController?.let { if (it.isConnected) return it }
            }
            return mediaController
        }
        isConnecting = true
        return try {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controller = MediaController.Builder(context, token).buildAsync().await()
            attachPlayerListener(controller)
            mediaController = controller
            controller
        } catch (t: Throwable) {
            emitMessage("音频服务启动失败：${t.message}")
            null
        } finally {
            isConnecting = false
        }
    }

    private fun attachPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isAwaitingMediaLoad) return
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isAwaitingMediaLoad) return
                val currentMediaId = controller.currentMediaItem?.mediaId
                val matchesActiveTrack = currentMediaId != null && currentMediaId == activeExpectedMediaId

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (matchesActiveTrack) {
                            _state.update { it.copy(isBuffering = true) }
                        }
                    }
                    Player.STATE_READY -> {
                        if (matchesActiveTrack) {
                            hasEnteredReadyForActiveMedia = true
                            val dur = controller.duration.takeIf { it > 0L } ?: _state.value.durationMs
                            _state.update { it.copy(isBuffering = false, durationMs = dur) }
                        }
                    }
                    Player.STATE_ENDED -> {
                        _state.update { it.copy(isBuffering = false, isPlaying = false) }
                        // Only auto-advance if this exact track truly played after reaching STATE_READY
                        if (matchesActiveTrack && hasEnteredReadyForActiveMedia) {
                            hasEnteredReadyForActiveMedia = false
                            handleTrackEnded()
                        }
                    }
                    Player.STATE_IDLE -> {
                        _state.update { it.copy(isBuffering = false, isPlaying = false) }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isAwaitingMediaLoad) return
                hasEnteredReadyForActiveMedia = false
                val curIdx = _state.value.currentIndex
                val curTrack = _state.value.currentTrack
                if (curTrack != null && !curTrack.isLocal && runtimeRetryCount < 3) {
                    activeStreamSourceId?.let { failedSourceIdsForCurrentTrack.add(it) }
                    activeResolvedTrackId?.let { failedTrackIdsForCurrentTrack.add(it) }
                    runtimeRetryCount++
                    emitMessage("音频流异常，正在自动切换备用音源重试…")
                    loadAndPlayIndex(curIdx, isRuntimeFallbackRetry = true)
                } else {
                    _state.update { it.copy(isBuffering = false, isPlaying = false) }
                    emitMessage("当前曲目播放失败，可尝试切换音质或手动下一首")
                }
            }
        })
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val clamped = startIndex.coerceIn(0, tracks.lastIndex)
        _state.update { it.copy(queue = tracks, currentIndex = clamped) }
        loadAndPlayIndex(clamped)
    }

    fun playSingle(track: Track) {
        val currentQueue = _state.value.queue.toMutableList()
        val existingIdx = currentQueue.indexOfFirst { it.id == track.id }
        if (existingIdx >= 0) {
            _state.update { it.copy(currentIndex = existingIdx) }
            loadAndPlayIndex(existingIdx)
        } else {
            val insertAt = (_state.value.currentIndex + 1).coerceIn(0, currentQueue.size)
            currentQueue.add(insertAt, track)
            _state.update { it.copy(queue = currentQueue, currentIndex = insertAt) }
            loadAndPlayIndex(insertAt)
        }
    }

    fun playNextInQueue(track: Track) {
        val currentQueue = _state.value.queue.toMutableList()
        if (currentQueue.isEmpty()) {
            playTracks(listOf(track), 0)
            return
        }
        val existing = currentQueue.indexOfFirst { it.id == track.id }
        var curIdx = _state.value.currentIndex
        if (existing >= 0 && existing != curIdx) {
            currentQueue.removeAt(existing)
            if (existing < curIdx) curIdx--
        }
        if (existing == curIdx) {
            emitMessage("《${track.title}》正在播放")
            return
        }
        val insertAt = (curIdx + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertAt, track)
        _state.update { it.copy(queue = currentQueue, currentIndex = curIdx) }
        emitMessage("已加入下一首播放：《${track.title}》")
    }

    fun removeFromQueue(index: Int) {
        val queue = _state.value.queue.toMutableList()
        if (index !in queue.indices) return
        val cur = _state.value.currentIndex
        queue.removeAt(index)
        when {
            queue.isEmpty() -> {
                resolveJob?.cancel()
                coverJob?.cancel()
                isAwaitingMediaLoad = false
                hasEnteredReadyForActiveMedia = false
                activeExpectedMediaId = null
                mediaController?.stop()
                mediaController?.clearMediaItems()
                _state.update {
                    it.copy(
                        queue = emptyList(),
                        currentIndex = -1,
                        isPlaying = false,
                        isBuffering = false,
                        positionMs = 0L,
                        durationMs = 0L,
                        lyrics = ParsedLyrics()
                    )
                }
            }
            index < cur -> _state.update { it.copy(queue = queue, currentIndex = cur - 1) }
            index == cur -> {
                val nextIdx = cur.coerceAtMost(queue.lastIndex)
                _state.update { it.copy(queue = queue, currentIndex = nextIdx) }
                loadAndPlayIndex(nextIdx)
            }
            else -> _state.update { it.copy(queue = queue) }
        }
    }

    fun clearQueue() {
        resolveJob?.cancel()
        coverJob?.cancel()
        isAwaitingMediaLoad = false
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                lyrics = ParsedLyrics()
            )
        }
    }

    fun togglePlayPause() {
        val ctrl = mediaController
        if (ctrl == null) {
            val idx = _state.value.currentIndex
            if (idx >= 0) loadAndPlayIndex(idx)
            return
        }
        if (isAwaitingMediaLoad) return
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            if (ctrl.mediaItemCount == 0 && _state.value.currentIndex >= 0) {
                loadAndPlayIndex(_state.value.currentIndex)
            } else {
                ctrl.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (isAwaitingMediaLoad) return
        val clamped = positionMs.coerceAtLeast(0L)
        mediaController?.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun hasNext(): Boolean = _state.value.queue.size > 1
    fun hasPrevious(): Boolean = _state.value.queue.size > 1

    fun next() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val nextIdx = when (st.repeatMode) {
            RepeatMode.SHUFFLE -> {
                if (st.queue.size == 1) 0
                else {
                    var candidate = Random.nextInt(st.queue.size)
                    if (candidate == st.currentIndex) candidate = (candidate + 1) % st.queue.size
                    candidate
                }
            }
            else -> (st.currentIndex + 1) % st.queue.size
        }
        _state.update { it.copy(currentIndex = nextIdx) }
        loadAndPlayIndex(nextIdx)
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        // If more than 5s into track, restart current track first
        if (st.positionMs > 5_000L) {
            seekTo(0L)
            return
        }
        val prevIdx = if (st.currentIndex <= 0) st.queue.lastIndex else st.currentIndex - 1
        _state.update { it.copy(currentIndex = prevIdx) }
        loadAndPlayIndex(prevIdx)
    }

    fun cycleRepeatMode() {
        _state.update { it.copy(repeatMode = it.repeatMode.next()) }
    }

    fun retryWithQuality(quality: AudioQuality, preferredSourceId: String? = null) {
        val idx = _state.value.currentIndex
        if (idx >= 0) {
            loadAndPlayIndex(idx, qualityOverride = quality, preferredSourceId = preferredSourceId)
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            emitMessage("已关闭睡眠定时")
            return
        }
        val totalMs = minutes * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = totalMs) }
        emitMessage("将在 $minutes 分钟后暂停播放")
        sleepTimerJob = scope.launch {
            var remaining = totalMs
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining -= 1_000L
                _state.update { it.copy(sleepTimerRemainingMs = remaining.coerceAtLeast(0L)) }
            }
            mediaController?.pause()
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            emitMessage("睡眠定时已到，已暂停播放")
        }
    }

    private fun handleTrackEnded() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (st.repeatMode == RepeatMode.SINGLE_LOOP) {
            hasEnteredReadyForActiveMedia = true
            seekTo(0L)
            mediaController?.play()
        } else {
            next()
        }
    }

    private fun loadAndPlayIndex(
        index: Int,
        qualityOverride: AudioQuality? = null,
        preferredSourceId: String? = null,
        isRuntimeFallbackRetry: Boolean = false
    ) {
        val track = _state.value.queue.getOrNull(index) ?: return
        resolveJob?.cancel()
        coverJob?.cancel()

        // Immediately stop any previous track so old STATE_ENDED / old position never leak into the new track
        isAwaitingMediaLoad = true
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = track.id
        mediaController?.let { ctrl ->
            if (ctrl.isConnected) {
                ctrl.stop()
                ctrl.clearMediaItems()
            }
        }

        if (!isRuntimeFallbackRetry) {
            failedSourceIdsForCurrentTrack.clear()
            failedTrackIdsForCurrentTrack.clear()
            activeStreamSourceId = null
            activeResolvedTrackId = null
            runtimeRetryCount = 0
        }

        _state.update {
            it.copy(
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = 0L,
                durationMs = track.durationMs,
                activeQualityBadge = track.bestQualityBadge,
                activeProviderName = null,
                lyrics = if (isRuntimeFallbackRetry) it.lyrics else ParsedLyrics(),
                isLoadingLyrics = !isRuntimeFallbackRetry
            )
        }

        // Launch parallel lyrics, cover enrichment & ambient color tasks without blocking playback resolution
        if (!isRuntimeFallbackRetry) {
            loadLyricsAsync(track)
            if (!track.coverUrl.isNullOrBlank()) {
                extractAmbientPaletteAsync(track.coverUrl)
            } else {
                resolveCoverAsync(index, track)
            }
        }

        resolveJob = scope.launch {
            val activeTrack = _state.value.queue.getOrNull(index)?.takeIf { it.id == track.id } ?: track

            // 1. Offline-first check: local audio URI
            if (!activeTrack.localUri.isNullOrBlank()) {
                startPlaybackOnMedia3(
                    track = activeTrack,
                    mediaUri = Uri.parse(activeTrack.localUri),
                    qualityBadge = "LOCAL",
                    providerName = "本地音频"
                )
                return@launch
            }

            // 2. Offline-first check: downloaded file
            val cachedPath = downloadManager.getLocalCachedPath(activeTrack.id)
                ?: activeTrack.cachedFilePath?.takeIf { File(it).exists() }
            if (!cachedPath.isNullOrBlank()) {
                startPlaybackOnMedia3(
                    track = activeTrack,
                    mediaUri = Uri.fromFile(File(cachedPath)),
                    qualityBadge = "离线",
                    providerName = "离线缓存"
                )
                return@launch
            }

            // 3. Resolve online stream via LX QuickJS custom sources (network-aware quality + concurrent racing)
            val settings: AppSettings = settingsDataStore.settings.first()
            val qualityDecision = if (qualityOverride != null) {
                com.lxpure.music.core.network.EffectiveQualityDecision(qualityOverride, downgradedForCellular = false)
            } else {
                networkAdvisor.decideQuality(settings.preferredQuality, settings.wifiOnlyHighQuality)
            }
            val targetQuality = qualityDecision.quality
            val streamResult = userApiManager.resolveStream(
                track = activeTrack,
                preferredQuality = targetQuality,
                allowCrossPlatformFallback = settings.autoFallbackSource,
                preferredSourceId = preferredSourceId,
                excludedSourceIds = failedSourceIdsForCurrentTrack.toSet(),
                excludedTrackIds = failedTrackIdsForCurrentTrack.toSet()
            )

            // Re-read latest queue item in case cover finished resolving in parallel
            val latestTrack = _state.value.queue.getOrNull(index)?.takeIf { it.id == track.id } ?: activeTrack

            streamResult.onSuccess { resolved ->
                activeStreamSourceId = resolved.sourceId
                activeResolvedTrackId = resolved.resolvedTrack.id
                when {
                    resolved.switchedPlatform -> emitMessage("已自动换源至 ${resolved.resolvedTrack.source.shortLabel} · ${resolved.providerName}")
                    resolved.switchedSource || isRuntimeFallbackRetry -> emitMessage("已自动切换至备用音源：${resolved.providerName}")
                }
                val qualityBadge = if (qualityDecision.downgradedForCellular) {
                    "${resolved.quality.badge} · 省流"
                } else {
                    resolved.quality.badge
                }
                startPlaybackOnMedia3(
                    track = latestTrack,
                    mediaUri = Uri.parse(resolved.url),
                    qualityBadge = qualityBadge,
                    providerName = resolved.providerName,
                    cacheKey = "${latestTrack.id}_${resolved.quality.lxType}"
                )
            }.onFailure { err ->
                isAwaitingMediaLoad = false
                hasEnteredReadyForActiveMedia = false
                _state.update { it.copy(isBuffering = false, isPlaying = false, positionMs = 0L) }
                val noSource = !userApiManager.hasReadySource()
                _events.tryEmit(
                    PlayerUiEvent(
                        message = err.message ?: "无法播放《${latestTrack.title}》",
                        actionLabel = if (noSource) "导入音源" else null,
                        openSourceManager = noSource
                    )
                )
            }
        }
    }

    private fun resolveCoverAsync(index: Int, track: Track) {
        coverJob?.cancel()
        coverJob = scope.launch {
            val resolvedCover = withContext(Dispatchers.IO) {
                runCatching { musicAggregator.resolveCoverIfNeeded(track) }.getOrNull()
            }
            if (!resolvedCover.isNullOrBlank() && resolvedCover != track.coverUrl) {
                val updated = track.copy(coverUrl = resolvedCover)
                _state.update { st ->
                    val mutable = st.queue.toMutableList()
                    if (index in mutable.indices && mutable[index].id == track.id) {
                        mutable[index] = updated
                    }
                    st.copy(queue = mutable)
                }
                if (_state.value.currentTrack?.id == track.id) {
                    extractAmbientPaletteAsync(resolvedCover)
                }
            }
        }
    }

    private suspend fun startPlaybackOnMedia3(
        track: Track,
        mediaUri: Uri,
        qualityBadge: String,
        providerName: String,
        cacheKey: String? = null
    ) {
        val controller = ensureController() ?: run {
            isAwaitingMediaLoad = false
            hasEnteredReadyForActiveMedia = false
            _state.update { it.copy(isBuffering = false, isPlaying = false) }
            return
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                track.coverUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .apply {
                if (!cacheKey.isNullOrBlank()) setCustomCacheKey(cacheKey)
            }
            .setMediaMetadata(metadata)
            .build()

        activeExpectedMediaId = track.id
        hasEnteredReadyForActiveMedia = false
        isAwaitingMediaLoad = false

        _state.update {
            it.copy(
                activeQualityBadge = qualityBadge,
                activeProviderName = providerName,
                positionMs = 0L
            )
        }

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()

        withContext(Dispatchers.IO) {
            libraryRepository.recordPlay(track)
        }
    }

    private fun loadLyricsAsync(track: Track) {
        lyricJob?.cancel()
        lyricJob = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { musicAggregator.fetchLyrics(track) }.getOrDefault(ParsedLyrics())
            }
            if (_state.value.currentTrack?.id == track.id) {
                _state.update { it.copy(lyrics = parsed, isLoadingLyrics = false) }
            }
        }
    }

    private fun extractAmbientPaletteAsync(coverUrl: String?) {
        paletteJob?.cancel()
        if (coverUrl.isNullOrBlank()) {
            _state.update { it.copy(ambientPalette = AmbientPalette()) }
            return
        }
        paletteJob = scope.launch(Dispatchers.IO) {
            val palette = runCatching {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false)
                    .size(96)
                    .build()
                val result = imageLoader.execute(request) as? SuccessResult
                val bitmap: Bitmap? = (result?.drawable as? BitmapDrawable)?.bitmap
                bitmap?.let { Palette.from(it).maximumColorCount(12).generate() }
            }.getOrNull()

            if (palette != null) {
                val primaryInt = palette.getDarkVibrantColor(
                    palette.getDarkMutedColor(
                        palette.getDominantColor(0xFF1E3A5F.toInt())
                    )
                )
                val secondaryInt = palette.getMutedColor(
                    palette.getDarkMutedColor(0xFF312E81.toInt())
                )
                val accentInt = palette.getVibrantColor(
                    palette.getLightVibrantColor(0xFF38BDF8.toInt())
                )
                _state.update {
                    it.copy(
                        ambientPalette = AmbientPalette(
                            primary = Color(primaryInt),
                            secondary = Color(secondaryInt),
                            accent = Color(accentInt)
                        )
                    )
                }
            }
        }
    }

    private fun startProgressTicker() {
        // Event-driven ticker: sleeps with 0% CPU wakeup while paused; polls every 300ms only when playing
        scope.launch {
            _state.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                if (!playing) return@collectLatest
                while (isActive) {
                    val ctrl = mediaController
                    if (ctrl != null &&
                        ctrl.isConnected &&
                        !isAwaitingMediaLoad &&
                        ctrl.mediaItemCount > 0 &&
                        ctrl.currentMediaItem?.mediaId == activeExpectedMediaId
                    ) {
                        val pos = ctrl.currentPosition.coerceAtLeast(0L)
                        val dur = ctrl.duration.takeIf { it > 0L } ?: _state.value.durationMs
                        if (pos != _state.value.positionMs || dur != _state.value.durationMs) {
                            _state.update { it.copy(positionMs = pos, durationMs = dur) }
                        }
                    }
                    delay(300L)
                }
            }
        }
    }

    fun emitMessage(message: String) {
        _events.tryEmit(PlayerUiEvent(message))
    }
}
