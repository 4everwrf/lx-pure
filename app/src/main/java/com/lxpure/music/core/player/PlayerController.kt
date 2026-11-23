package com.lxpure.music.core.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.util.Log
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
import com.lxpure.music.core.network.EffectiveQualityDecision
import com.lxpure.music.core.network.NetworkQualityAdvisor
import com.lxpure.music.core.userapi.ResolvedStream
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.data.local.PlaybackSessionStore
import com.lxpure.music.data.local.SettingsDataStore
import com.lxpure.music.data.repository.LibraryRepository
import com.lxpure.music.domain.model.AppSettings
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.PlaybackQueueSource
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
    val queueSource: PlaybackQueueSource? = null,
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

private data class PreloadedNextItem(
    val forTrackId: String,
    val nextIndex: Int,
    val track: Track,
    val mediaItem: MediaItem,
    val qualityBadge: String,
    val providerName: String,
    val sourceId: String?,
    val resolvedTrackId: String?,
    val cacheKey: String?
)

class PlayerController(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val networkAdvisor: NetworkQualityAdvisor,
    private val mediaCacheManager: MediaCacheManager,
    private val settingsDataStore: SettingsDataStore,
    private val libraryRepository: LibraryRepository,
    private val playbackSessionStore: PlaybackSessionStore,
    private val userApiManager: UserApiManager,
    private val musicAggregator: MusicAggregator,
    private val downloadManager: DownloadManager
) {
    companion object {
        private const val TAG = "PlayerController"
        private const val PRELOAD_WINDOW_MS = 20_000L
        private const val MIN_TRACK_DURATION_FOR_PRELOAD_MS = 25_000L
        private const val STABLE_PLAYBACK_RESET_DELTA_MS = 8_000L
        private const val CURSOR_PERSIST_INTERVAL_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerUiEvent> = _events.asSharedFlow()

    private var mediaController: MediaController? = null
    private var resolveJob: Job? = null
    private var recoveryDelayJob: Job? = null
    private var preloadJob: Job? = null
    private var coverJob: Job? = null
    private var lyricJob: Job? = null
    private var paletteJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isConnecting = false

    // Guards against stale progress flicker, false auto-advance, and premature FGS demotion
    @Volatile
    private var isAwaitingMediaLoad = false
    @Volatile
    private var isRecoveringError = false
    private var hasEnteredReadyForActiveMedia = false
    private var activeExpectedMediaId: String? = null

    // Tracks failed sources & playback retries for the currently playing song
    private var activeStreamSourceId: String? = null
    private var activeResolvedTrackId: String? = null
    private var activeCacheKey: String? = null
    private var pendingResumePositionMs: Long = 0L
    private var lastRecoveryStartPositionMs: Long = 0L
    private val failedSourceIdsForCurrentTrack = mutableSetOf<String>()
    private val failedTrackIdsForCurrentTrack = mutableSetOf<String>()
    private var runtimeRetryCount = 0
    private var consecutiveAutoSkipCount = 0

    // Gapless next-track preload state (eliminates STATE_ENDED between songs in background)
    private var preloadAttemptedForTrackId: String? = null
    private var preloadedNext: PreloadedNextItem? = null

    // Session persistence & cold-start restoration guards
    private var hasUserInitiatedPlayback = false
    private var lyricsLoadedForTrackId: String? = null
    private var lastCursorPersistRealtimeMs = 0L
    private var queuePersistJob: Job? = null
    private var cursorPersistJob: Job? = null

    init {
        startProgressTicker()
        restoreLastSessionAsync()
    }

    private fun restoreLastSessionAsync() {
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                playbackSessionStore.loadSession()
            } ?: return@launch

            if (hasUserInitiatedPlayback || _state.value.queue.isNotEmpty()) return@launch
            val track = restored.queue.getOrNull(restored.currentIndex) ?: return@launch

            pendingResumePositionMs = restored.positionMs
            lastRecoveryStartPositionMs = restored.positionMs
            _state.value = PlayerState(
                queue = restored.queue,
                currentIndex = restored.currentIndex,
                queueSource = restored.queueSource,
                isPlaying = false,
                isBuffering = false,
                positionMs = restored.positionMs,
                durationMs = restored.durationMs,
                repeatMode = restored.repeatMode,
                activeQualityBadge = restored.activeQualityBadge,
                activeProviderName = restored.activeProviderName,
                lyrics = ParsedLyrics(),
                isLoadingLyrics = true
            )

            loadLyricsAsync(track)
            if (!track.coverUrl.isNullOrBlank()) {
                extractAmbientPaletteAsync(track.coverUrl)
            } else {
                resolveCoverAsync(restored.currentIndex, track)
            }
        }
    }

    private fun resolveLivePositionAndDuration(snapshot: PlayerState): Pair<Long, Long> {
        val ctrl = mediaController
        val livePos = if (ctrl != null &&
            ctrl.isConnected &&
            !isAwaitingMediaLoad &&
            ctrl.mediaItemCount > 0 &&
            ctrl.currentMediaItem?.mediaId == activeExpectedMediaId
        ) {
            ctrl.currentPosition.coerceAtLeast(0L)
        } else {
            snapshot.positionMs.coerceAtLeast(0L)
        }
        val liveDur = if (ctrl != null && ctrl.isConnected && ctrl.duration > 0L) {
            ctrl.duration
        } else {
            snapshot.durationMs
        }
        return livePos to liveDur
    }

    private fun persistQueueAndCursorAsync() {
        val snapshot = _state.value
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        lastCursorPersistRealtimeMs = SystemClock.elapsedRealtime()
        queuePersistJob?.cancel()
        queuePersistJob = scope.launch(Dispatchers.IO) {
            playbackSessionStore.saveQueueAndCursor(
                queue = snapshot.queue,
                queueSource = snapshot.queueSource,
                currentIndex = snapshot.currentIndex,
                positionMs = pos,
                durationMs = dur,
                repeatMode = snapshot.repeatMode,
                activeQualityBadge = snapshot.activeQualityBadge,
                activeProviderName = snapshot.activeProviderName
            )
        }
    }

    private fun persistCursorAsync(force: Boolean) {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCursorPersistRealtimeMs < CURSOR_PERSIST_INTERVAL_MS) return
        lastCursorPersistRealtimeMs = now
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        cursorPersistJob?.cancel()
        cursorPersistJob = scope.launch(Dispatchers.IO) {
            playbackSessionStore.saveCursorOnly(
                currentIndex = snapshot.currentIndex,
                currentTrackId = track.id,
                positionMs = pos,
                durationMs = dur,
                repeatMode = snapshot.repeatMode,
                activeQualityBadge = snapshot.activeQualityBadge,
                activeProviderName = snapshot.activeProviderName
            )
        }
    }

    /**
     * Synchronously flushes the tiny playback cursor (and clears if empty) when the app is
     * backgrounded, swiped away, or destroyed so the latest second is never lost.
     */
    fun persistSnapshotNow() {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        pendingResumePositionMs = pos
        lastCursorPersistRealtimeMs = SystemClock.elapsedRealtime()
        playbackSessionStore.saveCursorOnly(
            currentIndex = snapshot.currentIndex,
            currentTrackId = track.id,
            positionMs = pos,
            durationMs = dur,
            repeatMode = snapshot.repeatMode,
            activeQualityBadge = snapshot.activeQualityBadge,
            activeProviderName = snapshot.activeProviderName
        )
    }

    /**
     * Used by PlaybackService.onUpdateNotification to keep the mediaPlayback foreground service
     * alive during async track URL resolution and mid-stream error recovery.
     */
    fun isTransitioningOrRecovering(): Boolean = isAwaitingMediaLoad || isRecoveringError

    fun willAutoAdvanceOnEnd(): Boolean = _state.value.queue.isNotEmpty()

    fun canAutoRecoverFromError(): Boolean {
        val track = _state.value.currentTrack ?: return false
        return !track.isLocal && runtimeRetryCount < PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES
    }

    /**
     * Called when the user explicitly taps Pause from notification / lock screen / Bluetooth
     * while an async track transition or error recovery is in progress.
     */
    fun onExternalPauseRequested() {
        if (isAwaitingMediaLoad || isRecoveringError) {
            recoveryDelayJob?.cancel()
            resolveJob?.cancel()
            isAwaitingMediaLoad = false
            isRecoveringError = false
            _state.update { it.copy(isBuffering = false, isPlaying = false) }
            PlaybackService.refreshNotificationIfNeeded()
        }
    }

    /**
     * Called when Play is requested from lock screen / Bluetooth after playback stopped or errored.
     */
    fun resumeOrReloadCurrent() {
        val idx = _state.value.currentIndex
        if (idx >= 0) {
            loadAndPlayIndex(idx, resumePositionMs = pendingResumePositionMs)
        }
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
            controller.repeatMode = if (_state.value.repeatMode == RepeatMode.SINGLE_LOOP) {
                Player.REPEAT_MODE_ONE
            } else {
                Player.REPEAT_MODE_OFF
            }
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
                if (!isPlaying) {
                    val (pos, _) = resolveLivePositionAndDuration(_state.value)
                    pendingResumePositionMs = pos
                    persistCursorAsync(force = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isAwaitingMediaLoad) return
                val next = preloadedNext ?: return
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    mediaItem != null &&
                    mediaItem.mediaId == next.track.id
                ) {
                    Log.d(TAG, "Gapless auto-transition to ${next.track.title}")
                    promotePreloadedNextTrack(controller, next)
                }
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
                            isRecoveringError = false
                            consecutiveAutoSkipCount = 0
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
                        if (!isRecoveringError) {
                            _state.update { it.copy(isBuffering = false, isPlaying = false) }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isAwaitingMediaLoad) return
                hasEnteredReadyForActiveMedia = false
                val curIdx = _state.value.currentIndex
                val curTrack = _state.value.currentTrack
                val livePos = controller.currentPosition.coerceAtLeast(0L)
                val resumePos = maxOf(livePos, _state.value.positionMs)
                pendingResumePositionMs = resumePos

                val errorKind = PlaybackRecoveryPolicy.classifyPlaybackException(error)
                Log.w(
                    TAG,
                    "Playback error (${error.errorCodeName}, kind=$errorKind) on ${curTrack?.title} at ${resumePos}ms: ${error.message}"
                )

                if (curTrack != null && !curTrack.isLocal && runtimeRetryCount < PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES) {
                    val attempt = runtimeRetryCount
                    runtimeRetryCount++
                    val plan = PlaybackRecoveryPolicy.decideRecovery(
                        kind = errorKind,
                        retryAttemptIndex = attempt,
                        primaryTrackId = curTrack.id,
                        resolvedTrackId = activeResolvedTrackId
                    )
                    isRecoveringError = true
                    clearPreloadedNext(removeFromPlayer = true)

                    if (plan.evictStreamCache) {
                        mediaCacheManager.removeCacheKey(activeCacheKey)
                    }
                    if (plan.excludeCurrentSource) {
                        activeStreamSourceId?.let { failedSourceIdsForCurrentTrack.add(it) }
                    }
                    if (plan.excludeResolvedTrack) {
                        activeResolvedTrackId?.let { failedTrackIdsForCurrentTrack.add(it) }
                    }

                    val preferredSource = if (plan.preferSameSourceFirst) activeStreamSourceId else null
                    val targetPos = if (plan.preservePosition) resumePos else 0L
                    lastRecoveryStartPositionMs = targetPos

                    _state.update { it.copy(isBuffering = true, isPlaying = false, positionMs = targetPos) }
                    when (plan.kind) {
                        PlaybackErrorKind.TRANSIENT_NETWORK ->
                            emitMessage("网络波动，正在自动恢复播放…")
                        PlaybackErrorKind.EXPIRED_OR_DENIED_URL ->
                            emitMessage("音频链接已刷新，正在无缝续播…")
                        PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN ->
                            emitMessage("音频流异常，正在自动切换备用音源续播…")
                    }

                    recoveryDelayJob?.cancel()
                    resolveJob?.cancel()
                    recoveryDelayJob = scope.launch {
                        delay(plan.backoffMs)
                        if (plan.kind == PlaybackErrorKind.TRANSIENT_NETWORK && !networkAdvisor.isNetworkConnected()) {
                            // Wait up to 8s for Wi-Fi <-> 5G handoff to settle before resolving
                            repeat(16) {
                                if (networkAdvisor.isNetworkConnected()) return@repeat
                                delay(500L)
                            }
                        }
                        if (!isActive || _state.value.currentTrack?.id != curTrack.id) return@launch
                        loadAndPlayIndex(
                            index = curIdx,
                            preferredSourceId = preferredSource,
                            isRuntimeFallbackRetry = true,
                            resumePositionMs = targetPos
                        )
                    }
                } else {
                    isRecoveringError = false
                    isAwaitingMediaLoad = false
                    _state.update { it.copy(isBuffering = false, isPlaying = false, positionMs = resumePos) }
                    PlaybackService.refreshNotificationIfNeeded()
                    val msg = if (errorKind == PlaybackErrorKind.TRANSIENT_NETWORK) {
                        "网络波动导致播放中断，恢复网络后点击播放可从原位置继续"
                    } else {
                        "当前曲目播放失败，可尝试切换音质或手动下一首"
                    }
                    emitMessage(msg)
                }
            }
        })
    }

    private fun promotePreloadedNextTrack(controller: MediaController, next: PreloadedNextItem) {
        val currentQueue = _state.value.queue
        val validIdx = if (next.nextIndex in currentQueue.indices && currentQueue[next.nextIndex].id == next.track.id) {
            next.nextIndex
        } else {
            currentQueue.indexOfFirst { it.id == next.track.id }
        }
        preloadedNext = null
        preloadAttemptedForTrackId = null
        if (validIdx < 0) return

        // Trim the finished track from ExoPlayer so memory stays strictly bounded to 1-2 items
        if (controller.mediaItemCount > 1 && controller.currentMediaItemIndex > 0) {
            runCatching { controller.removeMediaItems(0, controller.currentMediaItemIndex) }
        }

        activeExpectedMediaId = next.track.id
        hasEnteredReadyForActiveMedia = true
        activeStreamSourceId = next.sourceId
        activeResolvedTrackId = next.resolvedTrackId
        activeCacheKey = next.cacheKey
        pendingResumePositionMs = 0L
        lastRecoveryStartPositionMs = 0L
        runtimeRetryCount = 0
        consecutiveAutoSkipCount = 0
        failedSourceIdsForCurrentTrack.clear()
        failedTrackIdsForCurrentTrack.clear()

        val dur = controller.duration.takeIf { it > 0L } ?: next.track.durationMs
        _state.update {
            it.copy(
                currentIndex = validIdx,
                isPlaying = controller.isPlaying || controller.playWhenReady,
                isBuffering = false,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = dur,
                activeQualityBadge = next.qualityBadge,
                activeProviderName = next.providerName,
                lyrics = ParsedLyrics(),
                isLoadingLyrics = true
            )
        }

        loadLyricsAsync(next.track)
        if (!next.track.coverUrl.isNullOrBlank()) {
            extractAmbientPaletteAsync(next.track.coverUrl)
        } else {
            resolveCoverAsync(validIdx, next.track)
        }
        persistCursorAsync(force = true)
        scope.launch(Dispatchers.IO) {
            libraryRepository.recordPlay(next.track)
        }
    }

    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
        queueSource: PlaybackQueueSource? = null
    ) {
        if (tracks.isEmpty()) return
        hasUserInitiatedPlayback = true
        val clamped = startIndex.coerceIn(0, tracks.lastIndex)
        clearPreloadedNext(removeFromPlayer = true)
        _state.update {
            it.copy(
                queue = tracks,
                currentIndex = clamped,
                queueSource = queueSource,
                positionMs = 0L
            )
        }
        persistQueueAndCursorAsync()
        loadAndPlayIndex(clamped)
    }

    fun playSingle(track: Track, queueSource: PlaybackQueueSource? = null) {
        hasUserInitiatedPlayback = true
        clearPreloadedNext(removeFromPlayer = true)
        val currentQueue = _state.value.queue.toMutableList()
        val existingIdx = currentQueue.indexOfFirst { it.id == track.id }
        if (existingIdx >= 0) {
            _state.update { it.copy(currentIndex = existingIdx, positionMs = 0L) }
            persistCursorAsync(force = true)
            loadAndPlayIndex(existingIdx)
        } else {
            val insertAt = (_state.value.currentIndex + 1).coerceIn(0, currentQueue.size)
            currentQueue.add(insertAt, track)
            _state.update {
                it.copy(
                    queue = currentQueue,
                    currentIndex = insertAt,
                    queueSource = it.queueSource ?: queueSource,
                    positionMs = 0L
                )
            }
            persistQueueAndCursorAsync()
            loadAndPlayIndex(insertAt)
        }
    }

    fun playNextInQueue(track: Track) {
        hasUserInitiatedPlayback = true
        clearPreloadedNext(removeFromPlayer = true)
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
        persistQueueAndCursorAsync()
        emitMessage("已加入下一首播放：《${track.title}》")
    }

    fun removeFromQueue(index: Int) {
        hasUserInitiatedPlayback = true
        clearPreloadedNext(removeFromPlayer = true)
        val queue = _state.value.queue.toMutableList()
        if (index !in queue.indices) return
        val cur = _state.value.currentIndex
        queue.removeAt(index)
        when {
            queue.isEmpty() -> {
                recoveryDelayJob?.cancel()
                resolveJob?.cancel()
                coverJob?.cancel()
                isAwaitingMediaLoad = false
                isRecoveringError = false
                hasEnteredReadyForActiveMedia = false
                activeExpectedMediaId = null
                lyricsLoadedForTrackId = null
                pendingResumePositionMs = 0L
                mediaController?.stop()
                mediaController?.clearMediaItems()
                _state.update {
                    it.copy(
                        queue = emptyList(),
                        currentIndex = -1,
                        queueSource = null,
                        isPlaying = false,
                        isBuffering = false,
                        positionMs = 0L,
                        durationMs = 0L,
                        lyrics = ParsedLyrics()
                    )
                }
                persistQueueAndCursorAsync()
                PlaybackService.refreshNotificationIfNeeded()
            }
            index < cur -> {
                _state.update { it.copy(queue = queue, currentIndex = cur - 1) }
                persistQueueAndCursorAsync()
            }
            index == cur -> {
                val nextIdx = cur.coerceAtMost(queue.lastIndex)
                _state.update { it.copy(queue = queue, currentIndex = nextIdx, positionMs = 0L) }
                persistQueueAndCursorAsync()
                loadAndPlayIndex(nextIdx)
            }
            else -> {
                _state.update { it.copy(queue = queue) }
                persistQueueAndCursorAsync()
            }
        }
    }

    fun clearQueue() {
        hasUserInitiatedPlayback = true
        clearPreloadedNext(removeFromPlayer = false)
        recoveryDelayJob?.cancel()
        resolveJob?.cancel()
        coverJob?.cancel()
        isAwaitingMediaLoad = false
        isRecoveringError = false
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = null
        lyricsLoadedForTrackId = null
        pendingResumePositionMs = 0L
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _state.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                queueSource = null,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                lyrics = ParsedLyrics()
            )
        }
        persistQueueAndCursorAsync()
        PlaybackService.refreshNotificationIfNeeded()
    }

    fun togglePlayPause() {
        val ctrl = mediaController
        if (ctrl == null) {
            val idx = _state.value.currentIndex
            if (idx >= 0) loadAndPlayIndex(idx, resumePositionMs = pendingResumePositionMs)
            return
        }
        if (isAwaitingMediaLoad || isRecoveringError) {
            onExternalPauseRequested()
            ctrl.pause()
            return
        }
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            val needsReload = ctrl.mediaItemCount == 0 ||
                ctrl.playerError != null ||
                ctrl.playbackState == Player.STATE_IDLE
            if (needsReload && _state.value.currentIndex >= 0) {
                loadAndPlayIndex(_state.value.currentIndex, resumePositionMs = pendingResumePositionMs)
            } else {
                ctrl.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (isAwaitingMediaLoad) return
        val clamped = positionMs.coerceAtLeast(0L)
        pendingResumePositionMs = clamped
        mediaController?.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
        persistCursorAsync(force = true)
    }

    fun hasNext(): Boolean = _state.value.queue.size > 1
    fun hasPrevious(): Boolean = _state.value.queue.size > 1

    fun next() {
        nextInternal(isAutoAdvance = false)
    }

    private fun nextInternal(isAutoAdvance: Boolean) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (!isAutoAdvance) {
            consecutiveAutoSkipCount = 0
        }

        // Fast-path: if the next track was already preloaded for the current song, play it directly with 0ms resolve
        val readyNext = preloadedNext
        val preloadedUri = readyNext?.mediaItem?.localConfiguration?.uri
        if (readyNext != null &&
            preloadedUri != null &&
            readyNext.forTrackId == st.currentTrack?.id &&
            readyNext.nextIndex in st.queue.indices &&
            st.queue[readyNext.nextIndex].id == readyNext.track.id
        ) {
            clearPreloadedNext(removeFromPlayer = false)
            recoveryDelayJob?.cancel()
            resolveJob?.cancel()
            coverJob?.cancel()
            isAwaitingMediaLoad = true
            hasEnteredReadyForActiveMedia = false
            activeExpectedMediaId = readyNext.track.id
            activeStreamSourceId = readyNext.sourceId
            activeResolvedTrackId = readyNext.resolvedTrackId
            failedSourceIdsForCurrentTrack.clear()
            failedTrackIdsForCurrentTrack.clear()
            runtimeRetryCount = 0
            lastRecoveryStartPositionMs = 0L
            pendingResumePositionMs = 0L
            PlaybackService.silenceForTransition()

            _state.update {
                it.copy(
                    currentIndex = readyNext.nextIndex,
                    isPlaying = false,
                    isBuffering = true,
                    positionMs = 0L,
                    durationMs = readyNext.track.durationMs,
                    activeQualityBadge = readyNext.qualityBadge,
                    activeProviderName = readyNext.providerName,
                    lyrics = ParsedLyrics(),
                    isLoadingLyrics = true
                )
            }
            loadLyricsAsync(readyNext.track)
            if (!readyNext.track.coverUrl.isNullOrBlank()) {
                extractAmbientPaletteAsync(readyNext.track.coverUrl)
            } else {
                resolveCoverAsync(readyNext.nextIndex, readyNext.track)
            }
            persistCursorAsync(force = true)
            resolveJob = scope.launch {
                startPlaybackOnMedia3(
                    track = readyNext.track,
                    mediaUri = preloadedUri,
                    qualityBadge = readyNext.qualityBadge,
                    providerName = readyNext.providerName,
                    cacheKey = readyNext.cacheKey,
                    startPositionMs = 0L
                )
            }
            return
        }

        val nextIdx = computeNextIndex(st)
        _state.update { it.copy(currentIndex = nextIdx) }
        loadAndPlayIndex(nextIdx, isAutoAdvance = isAutoAdvance)
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        // If more than 5s into track, restart current track first
        if (st.positionMs > 5_000L) {
            seekTo(0L)
            return
        }
        consecutiveAutoSkipCount = 0
        val prevIdx = if (st.currentIndex <= 0) st.queue.lastIndex else st.currentIndex - 1
        _state.update { it.copy(currentIndex = prevIdx) }
        loadAndPlayIndex(prevIdx)
    }

    fun cycleRepeatMode() {
        val nextMode = _state.value.repeatMode.next()
        _state.update { it.copy(repeatMode = nextMode) }
        mediaController?.let { ctrl ->
            if (ctrl.isConnected) {
                ctrl.repeatMode = if (nextMode == RepeatMode.SINGLE_LOOP) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
            }
        }
        clearPreloadedNext(removeFromPlayer = true)
        persistCursorAsync(force = true)
    }

    fun retryWithQuality(quality: AudioQuality, preferredSourceId: String? = null) {
        val idx = _state.value.currentIndex
        if (idx >= 0) {
            val curPos = mediaController?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
            loadAndPlayIndex(
                index = idx,
                qualityOverride = quality,
                preferredSourceId = preferredSourceId,
                resumePositionMs = curPos
            )
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
            onExternalPauseRequested()
            mediaController?.pause()
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            emitMessage("睡眠定时已到，已暂停播放")
        }
    }

    private fun computeNextIndex(st: PlayerState): Int = when (st.repeatMode) {
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

    private fun handleTrackEnded() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        if (st.repeatMode == RepeatMode.SINGLE_LOOP) {
            hasEnteredReadyForActiveMedia = true
            mediaController?.seekTo(0L)
            mediaController?.play()
        } else {
            nextInternal(isAutoAdvance = true)
        }
    }

    private fun clearPreloadedNext(removeFromPlayer: Boolean) {
        preloadJob?.cancel()
        preloadJob = null
        preloadedNext = null
        preloadAttemptedForTrackId = null
        if (removeFromPlayer) {
            mediaController?.let { ctrl ->
                if (ctrl.isConnected && ctrl.mediaItemCount > ctrl.currentMediaItemIndex + 1) {
                    runCatching {
                        ctrl.removeMediaItems(ctrl.currentMediaItemIndex + 1, ctrl.mediaItemCount)
                    }
                }
            }
        }
    }

    private fun schedulePreloadNextTrack(currentTrackId: String, snapshot: PlayerState) {
        if (snapshot.queue.size <= 1 || snapshot.repeatMode == RepeatMode.SINGLE_LOOP) return
        val nextIdx = computeNextIndex(snapshot)
        if (nextIdx == snapshot.currentIndex) return
        val nextTrack = snapshot.queue.getOrNull(nextIdx) ?: return

        preloadAttemptedForTrackId = currentTrackId
        preloadJob?.cancel()
        preloadJob = scope.launch {
            val built = resolveTrackToPreparedItem(
                track = nextTrack,
                qualityOverride = null,
                preferredSourceId = null,
                excludedSourceIds = emptySet(),
                excludedTrackIds = emptySet()
            ) ?: return@launch

            if (!isActive || isAwaitingMediaLoad || _state.value.currentTrack?.id != currentTrackId) return@launch
            if (_state.value.repeatMode == RepeatMode.SINGLE_LOOP) return@launch
            val ctrl = mediaController ?: return@launch
            if (!ctrl.isConnected || ctrl.currentMediaItem?.mediaId != currentTrackId) return@launch

            // Ensure no stale preloaded items linger after the active item
            if (ctrl.mediaItemCount > ctrl.currentMediaItemIndex + 1) {
                runCatching { ctrl.removeMediaItems(ctrl.currentMediaItemIndex + 1, ctrl.mediaItemCount) }
            }
            ctrl.addMediaItem(built.mediaItem)
            preloadedNext = built.copy(forTrackId = currentTrackId, nextIndex = nextIdx)
            Log.d(TAG, "Preloaded gapless next track: ${nextTrack.title} [${built.qualityBadge}]")
        }
    }

    private suspend fun resolveTrackToPreparedItem(
        track: Track,
        qualityOverride: AudioQuality?,
        preferredSourceId: String?,
        excludedSourceIds: Set<String>,
        excludedTrackIds: Set<String>
    ): PreloadedNextItem? {
        if (!track.localUri.isNullOrBlank()) {
            val item = buildMediaItem(track, Uri.parse(track.localUri), cacheKey = null)
            return PreloadedNextItem(
                forTrackId = "",
                nextIndex = -1,
                track = track,
                mediaItem = item,
                qualityBadge = "LOCAL",
                providerName = "本地音频",
                sourceId = null,
                resolvedTrackId = track.id,
                cacheKey = null
            )
        }
        val cachedPath = downloadManager.getLocalCachedPath(track.id)
            ?: track.cachedFilePath?.takeIf { File(it).exists() }
        if (!cachedPath.isNullOrBlank()) {
            val item = buildMediaItem(track, Uri.fromFile(File(cachedPath)), cacheKey = null)
            return PreloadedNextItem(
                forTrackId = "",
                nextIndex = -1,
                track = track,
                mediaItem = item,
                qualityBadge = "离线",
                providerName = "离线缓存",
                sourceId = null,
                resolvedTrackId = track.id,
                cacheKey = null
            )
        }
        val settings: AppSettings = settingsDataStore.settings.first()
        val qualityDecision = if (qualityOverride != null) {
            EffectiveQualityDecision(qualityOverride, downgradedForCellular = false)
        } else {
            networkAdvisor.decideQuality(settings.preferredQuality, settings.wifiOnlyHighQuality)
        }
        val resolved: ResolvedStream = userApiManager.resolveStream(
            track = track,
            preferredQuality = qualityDecision.quality,
            allowCrossPlatformFallback = settings.autoFallbackSource,
            preferredSourceId = preferredSourceId,
            excludedSourceIds = excludedSourceIds,
            excludedTrackIds = excludedTrackIds
        ).getOrNull() ?: return null

        val qualityBadge = if (qualityDecision.downgradedForCellular) {
            "${resolved.quality.badge} · 省流"
        } else {
            resolved.quality.badge
        }
        val cacheKey = "${track.id}_${resolved.quality.lxType}"
        val item = buildMediaItem(track, Uri.parse(resolved.url), cacheKey = cacheKey)
        return PreloadedNextItem(
            forTrackId = "",
            nextIndex = -1,
            track = track,
            mediaItem = item,
            qualityBadge = qualityBadge,
            providerName = resolved.providerName,
            sourceId = resolved.sourceId,
            resolvedTrackId = resolved.resolvedTrack.id,
            cacheKey = cacheKey
        )
    }

    private fun loadAndPlayIndex(
        index: Int,
        qualityOverride: AudioQuality? = null,
        preferredSourceId: String? = null,
        isRuntimeFallbackRetry: Boolean = false,
        resumePositionMs: Long = 0L,
        isAutoAdvance: Boolean = false
    ) {
        val track = _state.value.queue.getOrNull(index) ?: return
        recoveryDelayJob?.cancel()
        resolveJob?.cancel()
        coverJob?.cancel()
        clearPreloadedNext(removeFromPlayer = false)

        // Mark awaiting BEFORE silencing previous audio so PlaybackService NEVER drops
        // out of foreground service status while the next track URL is resolving.
        isAwaitingMediaLoad = true
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = track.id
        PlaybackService.silenceForTransition()

        if (!isRuntimeFallbackRetry) {
            failedSourceIdsForCurrentTrack.clear()
            failedTrackIdsForCurrentTrack.clear()
            activeStreamSourceId = null
            activeResolvedTrackId = null
            activeCacheKey = null
            runtimeRetryCount = 0
            lastRecoveryStartPositionMs = resumePositionMs
        }
        pendingResumePositionMs = resumePositionMs

        val reuseLyrics = isRuntimeFallbackRetry || lyricsLoadedForTrackId == track.id
        _state.update {
            it.copy(
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = resumePositionMs,
                durationMs = if ((isRuntimeFallbackRetry || it.currentTrack?.id == track.id) && it.durationMs > 0L) it.durationMs else track.durationMs,
                activeQualityBadge = if (isRuntimeFallbackRetry) it.activeQualityBadge else (it.activeQualityBadge?.takeIf { reuseLyrics } ?: track.bestQualityBadge),
                activeProviderName = if (isRuntimeFallbackRetry) it.activeProviderName else null,
                lyrics = if (reuseLyrics) it.lyrics else ParsedLyrics(),
                isLoadingLyrics = !reuseLyrics
            )
        }
        persistCursorAsync(force = true)

        // Launch parallel lyrics, cover enrichment & ambient color tasks without blocking playback resolution
        if (!isRuntimeFallbackRetry) {
            if (!reuseLyrics) {
                loadLyricsAsync(track)
            }
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
                    providerName = "本地音频",
                    startPositionMs = resumePositionMs
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
                    providerName = "离线缓存",
                    startPositionMs = resumePositionMs
                )
                return@launch
            }

            // 3. Resolve online stream via LX QuickJS custom sources (network-aware quality + concurrent racing)
            val settings: AppSettings = settingsDataStore.settings.first()
            val qualityDecision = if (qualityOverride != null) {
                EffectiveQualityDecision(qualityOverride, downgradedForCellular = false)
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
                    cacheKey = "${latestTrack.id}_${resolved.quality.lxType}",
                    startPositionMs = resumePositionMs
                )
            }.onFailure { err ->
                // During background playlist auto-advance, automatically skip up to 3 dead tracks while online
                if (isAutoAdvance &&
                    networkAdvisor.isNetworkConnected() &&
                    _state.value.queue.size > 1 &&
                    consecutiveAutoSkipCount < PlaybackRecoveryPolicy.MAX_CONSECUTIVE_AUTO_SKIPS
                ) {
                    consecutiveAutoSkipCount++
                    emitMessage("《${latestTrack.title}》暂无可用音源，已自动跳至下一首")
                    nextInternal(isAutoAdvance = true)
                    return@launch
                }

                isAwaitingMediaLoad = false
                isRecoveringError = false
                hasEnteredReadyForActiveMedia = false
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        positionMs = resumePositionMs
                    )
                }
                PlaybackService.refreshNotificationIfNeeded()
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
                persistQueueAndCursorAsync()
            }
        }
    }

    private fun buildMediaItem(track: Track, mediaUri: Uri, cacheKey: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                track.coverUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .apply {
                if (!cacheKey.isNullOrBlank()) setCustomCacheKey(cacheKey)
            }
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun startPlaybackOnMedia3(
        track: Track,
        mediaUri: Uri,
        qualityBadge: String,
        providerName: String,
        cacheKey: String? = null,
        startPositionMs: Long = 0L
    ) {
        val controller = ensureController() ?: run {
            isAwaitingMediaLoad = false
            isRecoveringError = false
            hasEnteredReadyForActiveMedia = false
            _state.update { it.copy(isBuffering = false, isPlaying = false) }
            PlaybackService.refreshNotificationIfNeeded()
            return
        }

        val mediaItem = buildMediaItem(track, mediaUri, cacheKey)

        activeExpectedMediaId = track.id
        activeCacheKey = cacheKey
        hasEnteredReadyForActiveMedia = false
        isAwaitingMediaLoad = false
        isRecoveringError = false
        pendingResumePositionMs = startPositionMs

        _state.update {
            it.copy(
                activeQualityBadge = qualityBadge,
                activeProviderName = providerName,
                positionMs = startPositionMs
            )
        }

        controller.repeatMode = if (_state.value.repeatMode == RepeatMode.SINGLE_LOOP) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        if (startPositionMs > 0L) {
            controller.setMediaItem(mediaItem, startPositionMs)
        } else {
            controller.setMediaItem(mediaItem)
        }
        controller.prepare()
        controller.play()
        persistCursorAsync(force = true)

        withContext(Dispatchers.IO) {
            libraryRepository.recordPlay(track)
        }
    }

    private fun loadLyricsAsync(track: Track) {
        lyricJob?.cancel()
        lyricsLoadedForTrackId = null
        lyricJob = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { musicAggregator.fetchLyrics(track) }.getOrDefault(ParsedLyrics())
            }
            if (_state.value.currentTrack?.id == track.id) {
                lyricsLoadedForTrackId = track.id
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
                        pendingResumePositionMs = pos
                        if (pos != _state.value.positionMs || dur != _state.value.durationMs) {
                            _state.update { it.copy(positionMs = pos, durationMs = dur) }
                        }
                        persistCursorAsync(force = false)

                        // Once playback progresses stably by 8s after a recovery, restore fresh retry budget
                        if (runtimeRetryCount > 0 && pos >= lastRecoveryStartPositionMs + STABLE_PLAYBACK_RESET_DELTA_MS) {
                            runtimeRetryCount = 0
                            failedSourceIdsForCurrentTrack.clear()
                            failedTrackIdsForCurrentTrack.clear()
                        }

                        // Pre-resolve the next track in the final 20s so transitions happen gaplessly
                        // inside ExoPlayer (0 wasted data if skipped early, never enters STATE_ENDED)
                        val st = _state.value
                        val curTrack = st.currentTrack
                        if (curTrack != null &&
                            st.repeatMode != RepeatMode.SINGLE_LOOP &&
                            st.queue.size > 1 &&
                            dur >= MIN_TRACK_DURATION_FOR_PRELOAD_MS &&
                            (dur - pos) in 1_000L..PRELOAD_WINDOW_MS &&
                            preloadAttemptedForTrackId != curTrack.id &&
                            preloadJob?.isActive != true
                        ) {
                            schedulePreloadNextTrack(curTrack.id, st)
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
