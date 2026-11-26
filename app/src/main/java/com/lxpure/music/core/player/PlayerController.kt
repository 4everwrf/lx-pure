package com.lxpure.music.core.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
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
import coil.request.CachePolicy
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
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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

/**
 * High-frequency playback progress isolated from [PlayerState] so 300ms progress ticks
 * and 1s sleep timer ticks only recompose mini/full player progress bars & lyric highlights
 * instead of triggering full root-tree recompositions.
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sleepTimerRemainingMs: Long? = null
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
    val activeStreamSourceId: String? = null,
    val lyrics: ParsedLyrics = ParsedLyrics(),
    val isLoadingLyrics: Boolean = false,
    val ambientPalette: AmbientPalette = AmbientPalette(),
    val sleepTimerRemainingMs: Long? = null,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false
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
    val resolvedPlatformCode: String? = null,
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
        private const val CURSOR_PERSIST_INTERVAL_MS = 10_000L

        internal fun buildStreamCacheKey(
            trackId: String,
            resolvedTrackId: String?,
            sourceId: String?,
            quality: AudioQuality,
            sourceRevision: String? = null
        ): String {
            val resolvedPart = resolvedTrackId?.takeIf { it.isNotBlank() } ?: trackId
            val sourcePart = sourceId?.takeIf { it.isNotBlank() } ?: "default"
            val revPart = sourceRevision?.takeIf { it.isNotBlank() }?.let { "_$it" }.orEmpty()
            return "${trackId}_${resolvedPart}_${sourcePart}${revPart}_${quality.lxType}"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionPersistDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayerUiEvent> = _events.asSharedFlow()

    private val queueNavigator = QueueNavigator()
    private var lastPublishedCommandSignature: Int = -1

    private var mediaController: MediaController? = null
    private val controllerMutex = Mutex()
    private var resolveJob: Job? = null
    private var recoveryDelayJob: Job? = null
    private var preloadJob: Job? = null
    private var coverJob: Job? = null
    private var lyricJob: Job? = null
    private var paletteJob: Job? = null
    private var sleepTimerJob: Job? = null

    // Guards against stale progress flicker, false auto-advance, and premature FGS demotion
    @Volatile
    private var isAwaitingMediaLoad = false
    @Volatile
    private var isRecoveringError = false
    @Volatile
    private var isErrorRecoverySuppressed = false
    @Volatile
    private var requiresFreshReloadOnPlay = false
    private var hasEnteredReadyForActiveMedia = false
    private var activeExpectedMediaId: String? = null
    private var playbackRequestId = 0L

    // Tracks failed sources & playback retries for the currently playing song
    private var activeStreamSourceId: String? = null
    private var activeResolvedTrackId: String? = null
    private var activeResolvedPlatformCode: String? = null
    private var activeCacheKey: String? = null
    private var pendingResumePositionMs: Long = 0L
    private var pendingQualityOverride: AudioQuality? = null
    private var lastRecoveryStartPositionMs: Long = 0L
    private val failedSourcePlatformsForCurrentTrack = mutableSetOf<String>()
    private val failedSourceTracksForCurrentTrack = mutableSetOf<String>()
    private val failedTrackIdsForCurrentTrack = mutableSetOf<String>()
    private val autoSkippedTrackIndices = mutableSetOf<Int>()
    private var runtimeRetryCount = 0
    private var allowAutoSkipForCurrentLoad = true

    // Gapless next-track preload state (eliminates STATE_ENDED between songs in background)
    private var preloadAttemptedForTrackId: String? = null
    private var preloadedNext: PreloadedNextItem? = null

    // Session persistence & cold-start restoration guards
    private var hasUserInitiatedPlayback = false
    private var lyricsLoadedForTrackId: String? = null
    private var lastCursorPersistRealtimeMs = 0L
    private val queuePersistGeneration = AtomicLong(0L)
    private val cursorPersistGeneration = AtomicLong(0L)
    private var lastPersistedQueueSize = 0
    private var lastPersistedSliceStart = -1
    private var lastPersistedSliceCount = 0
    private var queuePersistJob: Job? = null
    private var cursorPersistJob: Job? = null

    init {
        startProgressTicker()
        observeSourceConfigChanges()
        restoreLastSessionAsync()
    }

    private fun observeSourceConfigChanges() {
        scope.launch {
            userApiManager.sources
                .map { list ->
                    list.map { src ->
                        "${src.id}:${src.enabled}:${src.priority}:${src.status == UserApiRuntimeStatus.READY}:${UserApiManager.computeSourceRevision(src)}"
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .flowOn(Dispatchers.Default)
                .collect {
                    // Always invalidate preloaded next track so disabled/reordered/updated sources take effect immediately
                    clearPreloadedNext(removeFromPlayer = true)
                    val st = _state.value
                    val cur = st.currentTrack
                    if (cur != null && !cur.isLocal && !st.isPlaying && !st.isBuffering && !isAwaitingMediaLoad) {
                        requiresFreshReloadOnPlay = true
                    }
                }
        }
    }

    private fun restoreLastSessionAsync() {
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                playbackSessionStore.loadSession()
            }
            if (restored != null && !hasUserInitiatedPlayback && _state.value.queue.isEmpty()) {
                val track = restored.queue.getOrNull(restored.currentIndex)
                if (track != null) {
                    pendingResumePositionMs = restored.positionMs
                    lastRecoveryStartPositionMs = restored.positionMs
                    lastPersistedQueueSize = restored.queue.size
                    lastPersistedSliceStart = 0
                    lastPersistedSliceCount = restored.queue.size
                    queueNavigator.onQueueReplaced(restored.queue.size, restored.currentIndex, restored.repeatMode)
                    _progress.value = PlaybackProgress(
                        positionMs = restored.positionMs,
                        durationMs = restored.durationMs
                    )
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
                    ).withNavCapabilities()

                    loadLyricsAsync(track)
                    if (!track.coverUrl.isNullOrBlank()) {
                        extractAmbientPaletteAsync(track.coverUrl)
                    } else {
                        resolveCoverAsync(restored.currentIndex, track)
                    }
                }
            }
            // Pre-warm MediaController asynchronously so first Play avoids Service binding delay
            if (mediaController?.isConnected != true) {
                ensureController(emitError = false)
            }
        }
    }

    private fun resolveLivePositionAndDuration(snapshot: PlayerState): Pair<Long, Long> {
        val ctrl = mediaController
        val prog = _progress.value
        val isActiveMediaBound = ctrl != null &&
            ctrl.isConnected &&
            !isAwaitingMediaLoad &&
            ctrl.mediaItemCount > 0 &&
            ctrl.playbackState != Player.STATE_ENDED &&
            ctrl.playbackState != Player.STATE_IDLE &&
            ctrl.currentMediaItem?.mediaId == activeExpectedMediaId
        val livePos = if (isActiveMediaBound) {
            ctrl!!.currentPosition.coerceAtLeast(0L)
        } else {
            prog.positionMs.coerceAtLeast(0L)
        }
        val liveDur = if (isActiveMediaBound && ctrl!!.duration > 0L) {
            ctrl.duration
        } else {
            prog.durationMs.takeIf { it > 0L } ?: snapshot.durationMs
        }
        return livePos to liveDur
    }

    private fun computeRelativePersistedIndex(queueSize: Int, currentIndex: Int): Int {
        return if (queueSize > PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE && lastPersistedSliceStart >= 0) {
            (currentIndex - lastPersistedSliceStart).coerceIn(0, (lastPersistedSliceCount - 1).coerceAtLeast(0))
        } else {
            currentIndex
        }
    }

    private fun persistQueueAndCursorAsync() {
        val snapshot = _state.value
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        lastCursorPersistRealtimeMs = SystemClock.elapsedRealtime()
        if (snapshot.queue.size > PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE) {
            val maxSize = PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE
            val clampedIdx = snapshot.currentIndex.coerceIn(0, snapshot.queue.lastIndex)
            val start = (clampedIdx - maxSize / 2).coerceIn(0, (snapshot.queue.size - maxSize).coerceAtLeast(0))
            lastPersistedQueueSize = snapshot.queue.size
            lastPersistedSliceStart = start
            lastPersistedSliceCount = maxSize
        } else {
            lastPersistedQueueSize = snapshot.queue.size
            lastPersistedSliceStart = 0
            lastPersistedSliceCount = snapshot.queue.size
        }
        val generation = queuePersistGeneration.incrementAndGet()
        cursorPersistGeneration.incrementAndGet()
        cursorPersistJob?.cancel()
        queuePersistJob?.cancel()
        queuePersistJob = scope.launch(sessionPersistDispatcher) {
            if (generation != queuePersistGeneration.get()) return@launch
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

    private fun persistCursorAsync(
        force: Boolean,
        livePosOverride: Long? = null,
        liveDurOverride: Long? = null
    ) {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCursorPersistRealtimeMs < CURSOR_PERSIST_INTERVAL_MS) return
        if (snapshot.queue.size > PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE) {
            val outsideSlice = lastPersistedQueueSize != snapshot.queue.size ||
                lastPersistedSliceStart < 0 ||
                snapshot.currentIndex !in lastPersistedSliceStart until (lastPersistedSliceStart + lastPersistedSliceCount)
            if (outsideSlice) {
                persistQueueAndCursorAsync()
                return
            }
        }
        lastCursorPersistRealtimeMs = now
        val (pos, dur) = if (livePosOverride != null && liveDurOverride != null) {
            livePosOverride to liveDurOverride
        } else {
            resolveLivePositionAndDuration(snapshot)
        }
        val persistedIdx = computeRelativePersistedIndex(snapshot.queue.size, snapshot.currentIndex)
        val generation = cursorPersistGeneration.incrementAndGet()
        cursorPersistJob?.cancel()
        cursorPersistJob = scope.launch(sessionPersistDispatcher) {
            if (generation != cursorPersistGeneration.get()) return@launch
            playbackSessionStore.saveCursorOnly(
                currentIndex = persistedIdx,
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
     * Non-blocking cursor flush for lightweight lifecycle transitions (e.g. Activity.onPause).
     */
    fun persistSnapshotAsync() {
        val snapshot = _state.value
        if (snapshot.currentTrack == null) return
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        pendingResumePositionMs = pos
        persistCursorAsync(force = true, livePosOverride = pos, liveDurOverride = dur)
    }

    /**
     * Synchronously flushes the tiny playback cursor when the app is stopped, swiped away,
     * or destroyed so the latest second is never lost.
     */
    fun persistSnapshotNow() {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val (pos, dur) = resolveLivePositionAndDuration(snapshot)
        pendingResumePositionMs = pos
        lastCursorPersistRealtimeMs = SystemClock.elapsedRealtime()
        cursorPersistGeneration.incrementAndGet()
        cursorPersistJob?.cancel()
        val persistedIdx = computeRelativePersistedIndex(snapshot.queue.size, snapshot.currentIndex)
        playbackSessionStore.saveCursorOnly(
            currentIndex = persistedIdx,
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

    fun willAutoAdvanceOnEnd(): Boolean {
        val st = _state.value
        return queueNavigator.willAutoAdvanceOnEnd(st.queue.size, st.currentIndex, st.repeatMode)
    }

    fun hasQueue(): Boolean = _state.value.queue.isNotEmpty()

    fun onExternalPlayRequested() {
        isErrorRecoverySuppressed = false
    }

    private fun PlayerState.withNavCapabilities(): PlayerState {
        val canPrev = queueNavigator.canSkipPrevious(queue.size, currentIndex, repeatMode)
        val canNext = queueNavigator.canSkipNext(queue.size, currentIndex, repeatMode)
        val sig = (if (queue.isNotEmpty()) 1 else 0) or
            (if (canPrev) 2 else 0) or
            (if (canNext) 4 else 0)
        if (sig != lastPublishedCommandSignature) {
            lastPublishedCommandSignature = sig
            PlaybackService.refreshAvailableCommands()
        }
        return copy(
            canSkipPrevious = canPrev,
            canSkipNext = canNext
        )
    }

    fun canAutoRecoverFromError(): Boolean {
        if (isErrorRecoverySuppressed) return false
        val track = _state.value.currentTrack ?: return false
        return !track.isLocal && runtimeRetryCount < PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES
    }

    fun shouldDeferPrepareOrSeekToPlay(): Boolean = requiresFreshReloadOnPlay

    fun needsReloadForCurrentTrack(player: Player): Boolean {
        val expectedId = activeExpectedMediaId ?: _state.value.currentTrack?.id
        return requiresFreshReloadOnPlay ||
            player.mediaItemCount == 0 ||
            player.playerError != null ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED ||
            (expectedId != null && player.currentMediaItem?.mediaId != expectedId)
    }

    /**
     * Called when the user explicitly taps Pause from notification / lock screen / Bluetooth
     * while an async track transition or error recovery is in progress.
     */
    fun onExternalPauseRequested() {
        isErrorRecoverySuppressed = true
        if (isAwaitingMediaLoad || isRecoveringError) {
            playbackRequestId++
            recoveryDelayJob?.cancel()
            resolveJob?.cancel()
            isAwaitingMediaLoad = false
            isRecoveringError = false
            _state.update { it.copy(isBuffering = false, isPlaying = false) }
            PlaybackService.refreshNotificationIfNeeded()
        } else if (mediaController?.playerError != null) {
            PlaybackService.refreshNotificationIfNeeded()
        }
    }

    /**
     * Called when Play is requested from lock screen / Bluetooth after playback stopped or errored.
     */
    fun resumeOrReloadCurrent() {
        if (isAwaitingMediaLoad) return
        val ctrl = mediaController
        if (ctrl != null &&
            ctrl.isConnected &&
            ctrl.playWhenReady &&
            ctrl.currentMediaItem?.mediaId == activeExpectedMediaId &&
            !requiresFreshReloadOnPlay
        ) {
            return
        }
        val idx = _state.value.currentIndex
        if (idx >= 0) {
            loadAndPlayIndex(idx, resumePositionMs = pendingResumePositionMs)
        }
    }

    private suspend fun ensureController(emitError: Boolean = true): MediaController? {
        mediaController?.let { if (it.isConnected) return it }
        return controllerMutex.withLock {
            mediaController?.let { existing ->
                if (existing.isConnected) return@withLock existing
                mediaController = null
                runCatching { existing.release() }
            }
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val internalHints = Bundle().apply {
                putBoolean(PlaybackService.HINT_INTERNAL_CONTROLLER, true)
            }
            val future = MediaController.Builder(context, token)
                .setConnectionHints(internalHints)
                .setListener(object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        if (mediaController === controller) {
                            mediaController = null
                            val hasInFlightLoad = resolveJob?.isActive == true || recoveryDelayJob?.isActive == true
                            if (!hasInFlightLoad) {
                                isAwaitingMediaLoad = false
                                isRecoveringError = false
                                _state.update { it.copy(isPlaying = false, isBuffering = false) }
                            } else {
                                _state.update { it.copy(isPlaying = false) }
                            }
                        }
                    }
                })
                .buildAsync()
            try {
                val controller = future.await()
                attachPlayerListener(controller)
                controller.repeatMode = if (_state.value.repeatMode == RepeatMode.SINGLE_LOOP) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                mediaController = controller
                controller
            } catch (ce: CancellationException) {
                MediaController.releaseFuture(future)
                throw ce
            } catch (t: Throwable) {
                MediaController.releaseFuture(future)
                if (emitError) {
                    emitMessage("音频服务启动失败：${t.message}")
                }
                null
            }
        }
    }

    private fun attachPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isAwaitingMediaLoad || (isRecoveringError && !isPlaying)) return
                val currentMediaId = controller.currentMediaItem?.mediaId
                val matchesActiveTrack = currentMediaId != null && currentMediaId == activeExpectedMediaId
                if (!matchesActiveTrack && (isPlaying || currentMediaId != null)) return
                if (isPlaying) {
                    isErrorRecoverySuppressed = false
                    requiresFreshReloadOnPlay = false
                    if (matchesActiveTrack && controller.playbackState == Player.STATE_READY) {
                        hasEnteredReadyForActiveMedia = true
                        isRecoveringError = false
                        autoSkippedTrackIndices.clear()
                    }
                }
                _state.update {
                    it.copy(
                        isPlaying = isPlaying,
                        isBuffering = if (isPlaying) false else (controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady)
                    )
                }
                if (!isPlaying) {
                    val (pos, _) = resolveLivePositionAndDuration(_state.value)
                    pendingResumePositionMs = pos
                    persistCursorAsync(force = true)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (isAwaitingMediaLoad) return
                if (controller.currentMediaItem?.mediaId != activeExpectedMediaId) return
                val livePlaying = controller.isPlaying
                if (isRecoveringError && !livePlaying) return
                val liveBuffering = controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady && !livePlaying
                if (livePlaying) {
                    isErrorRecoverySuppressed = false
                    requiresFreshReloadOnPlay = false
                    hasEnteredReadyForActiveMedia = true
                    isRecoveringError = false
                    autoSkippedTrackIndices.clear()
                }
                _state.update {
                    if (it.isPlaying == livePlaying && it.isBuffering == liveBuffering) {
                        it
                    } else {
                        it.copy(isPlaying = livePlaying, isBuffering = liveBuffering)
                    }
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (isAwaitingMediaLoad) return
                if (!events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED
                    )
                ) return
                if (controller.currentMediaItem?.mediaId != activeExpectedMediaId) return
                val livePlaying = controller.isPlaying
                if (isRecoveringError && !livePlaying) return
                val liveBuffering = controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady && !livePlaying
                if (livePlaying) {
                    isErrorRecoverySuppressed = false
                    requiresFreshReloadOnPlay = false
                    if (controller.playbackState == Player.STATE_READY) {
                        hasEnteredReadyForActiveMedia = true
                        isRecoveringError = false
                        autoSkippedTrackIndices.clear()
                    }
                }
                _state.update {
                    if (it.isPlaying == livePlaying && it.isBuffering == liveBuffering) {
                        it
                    } else {
                        it.copy(isPlaying = livePlaying, isBuffering = liveBuffering)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (isAwaitingMediaLoad) return
                val next = preloadedNext
                if (next != null &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    mediaItem != null &&
                    mediaItem.mediaId == next.track.id
                ) {
                    Log.d(TAG, "Gapless auto-transition to ${next.track.title}")
                    promotePreloadedNextTrack(controller, next)
                    return
                }
                // Guard against IPC race: ExoPlayer auto-transitioned just before clearPreloadedNext() cleared preloadedNext
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    mediaItem != null &&
                    mediaItem.mediaId != activeExpectedMediaId
                ) {
                    val st = _state.value
                    preloadJob?.cancel()
                    preloadJob = null
                    preloadedNext = null
                    preloadAttemptedForTrackId = null
                    if (st.repeatMode == RepeatMode.SEQUENTIAL && st.currentIndex == st.queue.lastIndex) {
                        stopAtQueueEnd("顺序播放已结束")
                        return
                    }
                    val matchedIdx = st.queue.indexOfFirst { it.id == mediaItem.mediaId }
                    if (matchedIdx >= 0 && st.repeatMode != RepeatMode.SINGLE_LOOP) {
                        val matchedTrack = st.queue[matchedIdx]
                        if (controller.mediaItemCount > 1 && controller.currentMediaItemIndex > 0) {
                            runCatching { controller.removeMediaItems(0, controller.currentMediaItemIndex) }
                        }
                        activeExpectedMediaId = matchedTrack.id
                        hasEnteredReadyForActiveMedia = true
                        isErrorRecoverySuppressed = false
                        requiresFreshReloadOnPlay = false
                        activeStreamSourceId = null
                        activeResolvedTrackId = null
                        activeResolvedPlatformCode = null
                        activeCacheKey = null
                        pendingResumePositionMs = 0L
                        pendingQualityOverride = null
                        lastRecoveryStartPositionMs = 0L
                        runtimeRetryCount = 0
                        allowAutoSkipForCurrentLoad = true
                        autoSkippedTrackIndices.clear()
                        failedSourcePlatformsForCurrentTrack.clear()
                        failedSourceTracksForCurrentTrack.clear()
                        failedTrackIdsForCurrentTrack.clear()
                        queueNavigator.onPromotedPreloadedNext(st.queue.size, matchedIdx, st.repeatMode)
                        val dur = controller.duration.takeIf { it > 0L } ?: matchedTrack.durationMs
                        val curPos = controller.currentPosition.coerceAtLeast(0L)
                        val livePlaying = controller.isPlaying
                        val liveBuffering = controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady && !livePlaying
                        _progress.update { it.copy(positionMs = curPos, durationMs = dur) }
                        _state.update {
                            it.copy(
                                currentIndex = matchedIdx,
                                isPlaying = livePlaying,
                                isBuffering = liveBuffering,
                                positionMs = curPos,
                                durationMs = dur,
                                activeQualityBadge = matchedTrack.bestQualityBadge,
                                activeProviderName = null,
                                activeStreamSourceId = null,
                                lyrics = ParsedLyrics(),
                                isLoadingLyrics = true
                            ).withNavCapabilities()
                        }
                        loadLyricsAsync(matchedTrack)
                        if (!matchedTrack.coverUrl.isNullOrBlank()) {
                            extractAmbientPaletteAsync(matchedTrack.coverUrl)
                        } else {
                            resolveCoverAsync(matchedIdx, matchedTrack)
                        }
                        persistCursorAsync(force = true)
                        scope.launch(Dispatchers.IO) {
                            libraryRepository.recordPlay(matchedTrack)
                        }
                    } else if (st.currentIndex in st.queue.indices) {
                        loadAndPlayIndex(st.currentIndex)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isAwaitingMediaLoad) return
                val currentMediaId = controller.currentMediaItem?.mediaId
                val matchesActiveTrack = currentMediaId != null && currentMediaId == activeExpectedMediaId

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (matchesActiveTrack) {
                            val livePlaying = controller.isPlaying
                            _state.update {
                                it.copy(
                                    isBuffering = controller.playWhenReady && !livePlaying,
                                    isPlaying = livePlaying
                                )
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        if (matchesActiveTrack) {
                            hasEnteredReadyForActiveMedia = true
                            isRecoveringError = false
                            val livePlaying = controller.isPlaying
                            if (livePlaying) {
                                isErrorRecoverySuppressed = false
                                autoSkippedTrackIndices.clear()
                            }
                            val dur = controller.duration.takeIf { it > 0L }
                                ?: _progress.value.durationMs.takeIf { it > 0L }
                                ?: _state.value.durationMs
                            _progress.update { it.copy(durationMs = dur) }
                            _state.update {
                                it.copy(
                                    isPlaying = livePlaying,
                                    isBuffering = false,
                                    durationMs = dur
                                )
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        _state.update { it.copy(isBuffering = false, isPlaying = false) }
                        // Only auto-advance if this exact track truly played after reaching STATE_READY
                        if (matchesActiveTrack && hasEnteredReadyForActiveMedia) {
                            hasEnteredReadyForActiveMedia = false
                            handleTrackEnded()
                        } else if (matchesActiveTrack && controller.playWhenReady) {
                            requiresFreshReloadOnPlay = true
                            controller.pause()
                            PlaybackService.refreshNotificationIfNeeded()
                        }
                    }
                    Player.STATE_IDLE -> {
                        if (!isRecoveringError) {
                            _state.update { it.copy(isBuffering = false, isPlaying = false) }
                        }
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (isAwaitingMediaLoad) return
                when (reason) {
                    Player.DISCONTINUITY_REASON_SEEK,
                    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                        if (controller.currentMediaItem?.mediaId == activeExpectedMediaId) {
                            val clamped = newPosition.positionMs.coerceAtLeast(0L)
                            pendingResumePositionMs = clamped
                            val dur = _progress.value.durationMs.takeIf { it > 0L } ?: _state.value.durationMs
                            _progress.update { it.copy(positionMs = clamped) }
                            _state.update { it.copy(positionMs = clamped) }
                            persistCursorAsync(force = true, livePosOverride = clamped, liveDurOverride = dur)
                        }
                    }
                    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                        lastRecoveryStartPositionMs = 0L
                        pendingResumePositionMs = 0L
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isAwaitingMediaLoad) return
                if (isErrorRecoverySuppressed || (!controller.playWhenReady && !isRecoveringError)) {
                    hasEnteredReadyForActiveMedia = false
                    isRecoveringError = false
                    requiresFreshReloadOnPlay = true
                    _state.update { it.copy(isBuffering = false, isPlaying = false) }
                    PlaybackService.refreshNotificationIfNeeded()
                    return
                }
                val wasReady = hasEnteredReadyForActiveMedia
                val hadPreloadedNext = preloadedNext != null
                hasEnteredReadyForActiveMedia = false
                val curTrack = _state.value.currentTrack
                val livePos = controller.currentPosition.coerceAtLeast(0L)
                val resumePos = maxOf(livePos, _progress.value.positionMs, pendingResumePositionMs)
                pendingResumePositionMs = resumePos

                val errorKind = PlaybackRecoveryPolicy.classifyPlaybackException(error)
                Log.w(
                    TAG,
                    "Playback error (${error.errorCodeName}, kind=$errorKind) on ${curTrack?.title} at ${resumePos}ms: ${error.message}"
                )

                // If Track A was already playing cleanly and a preloaded Track B failed in background,
                // strip Track B (without resetting preloadAttemptedForTrackId) and resume Track A cleanly.
                if (curTrack != null &&
                    !curTrack.isLocal &&
                    hadPreloadedNext &&
                    wasReady &&
                    controller.currentMediaItem?.mediaId == activeExpectedMediaId &&
                    runtimeRetryCount < PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES
                ) {
                    runtimeRetryCount++
                    isRecoveringError = true
                    isErrorRecoverySuppressed = false
                    clearPreloadedNext(removeFromPlayer = true, resetAttemptFlag = false)
                    val preferredSource = activeStreamSourceId
                    lastRecoveryStartPositionMs = resumePos
                    _progress.update { it.copy(positionMs = resumePos) }
                    _state.update { it.copy(isBuffering = true, isPlaying = false, positionMs = resumePos) }

                    recoveryDelayJob?.cancel()
                    resolveJob?.cancel()
                    recoveryDelayJob = scope.launch {
                        delay(200L)
                        val liveIdx = _state.value.queue.indexOfFirst { it.id == curTrack.id }
                        if (!isActive || liveIdx < 0 || _state.value.currentTrack?.id != curTrack.id) return@launch
                        loadAndPlayIndex(
                            index = liveIdx,
                            preferredSourceId = preferredSource,
                            isRuntimeFallbackRetry = true,
                            resumePositionMs = pendingResumePositionMs
                        )
                    }
                    return
                }

                if (curTrack != null && !curTrack.isLocal && runtimeRetryCount < PlaybackRecoveryPolicy.MAX_RUNTIME_RETRIES) {
                    val attempt = runtimeRetryCount
                    runtimeRetryCount++
                    val plan = PlaybackRecoveryPolicy.decideRecovery(
                        kind = errorKind,
                        retryAttemptIndex = attempt,
                        primaryTrackId = curTrack.id,
                        resolvedTrackId = activeResolvedTrackId,
                        wasReady = wasReady
                    )
                    isRecoveringError = true
                    isErrorRecoverySuppressed = false
                    clearPreloadedNext(removeFromPlayer = true, resetAttemptFlag = false)

                    val keyToEvict = if (plan.evictStreamCache) activeCacheKey else null
                    if (!keyToEvict.isNullOrBlank()) {
                        // Release ExoPlayer's SimpleCache span lock before evicting the corrupted cache entry
                        runCatching { controller.stop() }
                    }
                    if (plan.excludeCurrentSource) {
                        activeStreamSourceId?.let { sid ->
                            val plat = activeResolvedPlatformCode ?: curTrack.source.code
                            failedSourcePlatformsForCurrentTrack.add(UserApiManager.sourcePlatformKey(sid, plat))
                            val rtid = activeResolvedTrackId ?: curTrack.id
                            failedSourceTracksForCurrentTrack.add(UserApiManager.sourceTrackKey(sid, rtid))
                        }
                    }
                    if (plan.excludeResolvedTrack) {
                        activeResolvedTrackId?.let { failedTrackIdsForCurrentTrack.add(it) }
                    }

                    val preferredSource = if (plan.preferSameSourceFirst) activeStreamSourceId else null
                    val targetPos = if (plan.preservePosition) resumePos else 0L
                    lastRecoveryStartPositionMs = targetPos

                    _progress.update { it.copy(positionMs = targetPos) }
                    _state.update { it.copy(isBuffering = true, isPlaying = false, positionMs = targetPos) }
                    when (plan.kind) {
                        PlaybackErrorKind.TRANSIENT_NETWORK ->
                            emitMessage("网络波动，正在自动恢复播放…")
                        PlaybackErrorKind.EXPIRED_OR_DENIED_URL ->
                            emitMessage(if (plan.excludeCurrentSource) "当前音源不可用，正在自动换源播放…" else "音频链接已刷新，正在无缝续播…")
                        PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN ->
                            emitMessage("音频流异常，正在自动切换备用音源续播…")
                    }

                    recoveryDelayJob?.cancel()
                    resolveJob?.cancel()
                    recoveryDelayJob = scope.launch {
                        if (!keyToEvict.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                mediaCacheManager.removeCacheKey(keyToEvict)
                            }
                        }
                        delay(plan.backoffMs)
                        if (plan.kind == PlaybackErrorKind.TRANSIENT_NETWORK && !networkAdvisor.isNetworkConnected()) {
                            // Wait up to 8s for Wi-Fi <-> 5G handoff to settle before resolving
                            repeat(16) {
                                if (networkAdvisor.isNetworkConnected()) return@repeat
                                delay(500L)
                            }
                        }
                        val liveIdx = _state.value.queue.indexOfFirst { it.id == curTrack.id }
                        if (!isActive || liveIdx < 0 || _state.value.currentTrack?.id != curTrack.id) return@launch
                        val effectiveResumePos = if (plan.preservePosition) pendingResumePositionMs else 0L
                        loadAndPlayIndex(
                            index = liveIdx,
                            preferredSourceId = preferredSource,
                            isRuntimeFallbackRetry = true,
                            resumePositionMs = effectiveResumePos
                        )
                    }
                } else {
                    val liveIdx = _state.value.queue.indexOfFirst { it.id == curTrack?.id }
                    val terminalMsg = if (errorKind == PlaybackErrorKind.TRANSIENT_NETWORK && !networkAdvisor.isNetworkConnected()) {
                        "网络波动导致播放中断，恢复网络后点击播放可从原位置继续"
                    } else {
                        "当前曲目播放失败，可尝试切换音质或手动下一首"
                    }
                    if (curTrack != null && liveIdx >= 0) {
                        handleAllSourcesFailed(
                            failedIndex = liveIdx,
                            failedTrack = curTrack,
                            preserveExclusions = false,
                            allowAutoSkip = allowAutoSkipForCurrentLoad,
                            resumePositionMs = resumePos,
                            fallbackErrorMessage = terminalMsg
                        )
                    } else {
                        autoSkippedTrackIndices.clear()
                        isRecoveringError = false
                        isAwaitingMediaLoad = false
                        isErrorRecoverySuppressed = true
                        requiresFreshReloadOnPlay = true
                        _progress.update { it.copy(positionMs = resumePos) }
                        _state.update { it.copy(isBuffering = false, isPlaying = false, positionMs = resumePos) }
                        PlaybackService.refreshNotificationIfNeeded()
                        emitMessage(terminalMsg)
                    }
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

        activeExpectedMediaId = next.track.id
        hasEnteredReadyForActiveMedia = true
        isErrorRecoverySuppressed = false
        requiresFreshReloadOnPlay = false
        // Trim the finished track from ExoPlayer so memory stays strictly bounded to 1-2 items
        if (controller.mediaItemCount > 1 && controller.currentMediaItemIndex > 0) {
            runCatching { controller.removeMediaItems(0, controller.currentMediaItemIndex) }
        }
        activeStreamSourceId = next.sourceId
        activeResolvedTrackId = next.resolvedTrackId
        activeResolvedPlatformCode = next.resolvedPlatformCode ?: next.track.source.code
        activeCacheKey = next.cacheKey
        pendingResumePositionMs = 0L
        pendingQualityOverride = null
        lastRecoveryStartPositionMs = 0L
        runtimeRetryCount = 0
        allowAutoSkipForCurrentLoad = true
        autoSkippedTrackIndices.clear()
        failedSourcePlatformsForCurrentTrack.clear()
        failedSourceTracksForCurrentTrack.clear()
        failedTrackIdsForCurrentTrack.clear()

        queueNavigator.onPromotedPreloadedNext(currentQueue.size, validIdx, _state.value.repeatMode)
        val dur = controller.duration.takeIf { it > 0L } ?: next.track.durationMs
        val curPos = controller.currentPosition.coerceAtLeast(0L)
        val livePlaying = controller.isPlaying
        val liveBuffering = controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady && !livePlaying
        _progress.update { it.copy(positionMs = curPos, durationMs = dur) }
        _state.update {
            it.copy(
                currentIndex = validIdx,
                isPlaying = livePlaying,
                isBuffering = liveBuffering,
                positionMs = curPos,
                durationMs = dur,
                activeQualityBadge = next.qualityBadge,
                activeProviderName = next.providerName,
                activeStreamSourceId = next.sourceId,
                lyrics = ParsedLyrics(),
                isLoadingLyrics = true
            ).withNavCapabilities()
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
        autoSkippedTrackIndices.clear()
        val clamped = startIndex.coerceIn(0, tracks.lastIndex)
        val targetTrack = tracks[clamped]
        val currentSnapshot = _state.value
        if (isAwaitingMediaLoad &&
            currentSnapshot.currentTrack?.id == targetTrack.id &&
            currentSnapshot.queue.size == tracks.size &&
            currentSnapshot.currentIndex == clamped
        ) {
            // Avoid cancelling and restarting an identical in-flight stream resolution on rapid double-taps
            return
        }
        pendingQualityOverride = null
        clearPreloadedNext(removeFromPlayer = true)
        queueNavigator.onQueueReplaced(tracks.size, clamped, _state.value.repeatMode)
        val targetDur = tracks[clamped].durationMs
        _progress.update { it.copy(positionMs = 0L, durationMs = targetDur) }
        _state.update {
            it.copy(
                queue = tracks,
                currentIndex = clamped,
                queueSource = queueSource,
                positionMs = 0L,
                durationMs = targetDur
            ).withNavCapabilities()
        }
        persistQueueAndCursorAsync()
        loadAndPlayIndex(clamped)
    }

    fun selectQueueIndex(index: Int) {
        val st = _state.value
        if (index !in st.queue.indices) return
        hasUserInitiatedPlayback = true
        autoSkippedTrackIndices.clear()
        pendingQualityOverride = null
        clearPreloadedNext(removeFromPlayer = true)
        queueNavigator.onManualSelectIndex(st.queue.size, index, st.repeatMode)
        val targetDur = st.queue[index].durationMs
        _progress.update { it.copy(positionMs = 0L, durationMs = targetDur) }
        _state.update {
            it.copy(
                currentIndex = index,
                positionMs = 0L,
                durationMs = targetDur
            ).withNavCapabilities()
        }
        persistCursorAsync(force = true, livePosOverride = 0L, liveDurOverride = targetDur)
        loadAndPlayIndex(index)
    }

    fun playSingle(track: Track, queueSource: PlaybackQueueSource? = null) {
        hasUserInitiatedPlayback = true
        autoSkippedTrackIndices.clear()
        pendingQualityOverride = null
        clearPreloadedNext(removeFromPlayer = true)
        val snapshot = _state.value
        val existingQueue = snapshot.queue
        val existingIdx = existingQueue.indexOfFirst { it.id == track.id }
        if (existingIdx >= 0) {
            queueNavigator.onManualSelectIndex(existingQueue.size, existingIdx, snapshot.repeatMode)
            val targetDur = existingQueue[existingIdx].durationMs
            _progress.update { it.copy(positionMs = 0L, durationMs = targetDur) }
            _state.update {
                it.copy(
                    currentIndex = existingIdx,
                    positionMs = 0L,
                    durationMs = targetDur
                ).withNavCapabilities()
            }
            persistCursorAsync(force = true)
            loadAndPlayIndex(existingIdx)
        } else {
            val currentQueue = existingQueue.toMutableList()
            val oldCurIdx = snapshot.currentIndex
            val insertAt = (oldCurIdx + 1).coerceIn(0, currentQueue.size)
            currentQueue.add(insertAt, track)
            if (existingQueue.isEmpty()) {
                queueNavigator.onQueueReplaced(currentQueue.size, insertAt, snapshot.repeatMode)
            } else {
                queueNavigator.onTrackInsertedAsPlayNext(
                    oldRemovedIndex = null,
                    insertedIndex = insertAt,
                    newCurrentIndex = oldCurIdx.coerceIn(0, currentQueue.lastIndex),
                    newQueueSize = currentQueue.size,
                    mode = snapshot.repeatMode
                )
                queueNavigator.onManualSelectIndex(currentQueue.size, insertAt, snapshot.repeatMode)
            }
            _progress.update { it.copy(positionMs = 0L, durationMs = track.durationMs) }
            _state.update {
                it.copy(
                    queue = currentQueue,
                    currentIndex = insertAt,
                    queueSource = it.queueSource ?: queueSource,
                    positionMs = 0L,
                    durationMs = track.durationMs
                ).withNavCapabilities()
            }
            persistQueueAndCursorAsync()
            loadAndPlayIndex(insertAt)
        }
    }

    fun playNextInQueue(track: Track) {
        hasUserInitiatedPlayback = true
        val snapshot = _state.value
        if (snapshot.queue.isEmpty()) {
            playTracks(listOf(track), 0)
            return
        }
        var curIdx = snapshot.currentIndex
        val existing = snapshot.queue.indexOfFirst { it.id == track.id }
        if (existing == curIdx) {
            emitMessage("《${track.title}》正在播放")
            return
        }
        clearPreloadedNext(removeFromPlayer = true)
        val currentQueue = snapshot.queue.toMutableList()
        if (existing >= 0) {
            currentQueue.removeAt(existing)
            if (existing < curIdx) curIdx--
        }
        val insertAt = (curIdx + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertAt, track)
        queueNavigator.onTrackInsertedAsPlayNext(
            oldRemovedIndex = existing.takeIf { it >= 0 },
            insertedIndex = insertAt,
            newCurrentIndex = curIdx,
            newQueueSize = currentQueue.size,
            mode = snapshot.repeatMode
        )
        _state.update { it.copy(queue = currentQueue, currentIndex = curIdx).withNavCapabilities() }
        persistQueueAndCursorAsync()
        emitMessage("已加入下一首播放：《${track.title}》")
    }

    fun removeFromQueue(index: Int) {
        hasUserInitiatedPlayback = true
        autoSkippedTrackIndices.clear()
        clearPreloadedNext(removeFromPlayer = true)
        val snapshot = _state.value
        val queue = snapshot.queue.toMutableList()
        if (index !in queue.indices) return
        val cur = snapshot.currentIndex
        queue.removeAt(index)
        when {
            queue.isEmpty() -> {
                queueNavigator.reset()
                recoveryDelayJob?.cancel()
                resolveJob?.cancel()
                coverJob?.cancel()
                lyricJob?.cancel()
                paletteJob?.cancel()
                playbackRequestId++
                isAwaitingMediaLoad = false
                isRecoveringError = false
                isErrorRecoverySuppressed = false
                requiresFreshReloadOnPlay = false
                hasEnteredReadyForActiveMedia = false
                activeExpectedMediaId = null
                activeStreamSourceId = null
                activeResolvedTrackId = null
                activeResolvedPlatformCode = null
                activeCacheKey = null
                failedSourcePlatformsForCurrentTrack.clear()
                failedSourceTracksForCurrentTrack.clear()
                failedTrackIdsForCurrentTrack.clear()
                lyricsLoadedForTrackId = null
                pendingResumePositionMs = 0L
                pendingQualityOverride = null
                mediaController?.stop()
                mediaController?.clearMediaItems()
                _progress.update { it.copy(positionMs = 0L, durationMs = 0L) }
                _state.update {
                    it.copy(
                        queue = emptyList(),
                        currentIndex = -1,
                        queueSource = null,
                        isPlaying = false,
                        isBuffering = false,
                        positionMs = 0L,
                        durationMs = 0L,
                        activeQualityBadge = null,
                        activeProviderName = null,
                        activeStreamSourceId = null,
                        lyrics = ParsedLyrics(),
                        isLoadingLyrics = false,
                        ambientPalette = AmbientPalette()
                    ).withNavCapabilities()
                }
                persistQueueAndCursorAsync()
                PlaybackService.refreshNotificationIfNeeded()
            }
            index < cur -> {
                val newCur = cur - 1
                queueNavigator.onTrackRemovedFromQueue(index, newCur, queue.size, snapshot.repeatMode)
                _state.update { it.copy(queue = queue, currentIndex = newCur).withNavCapabilities() }
                persistQueueAndCursorAsync()
            }
            index == cur -> {
                val nextIdx = cur.coerceAtMost(queue.lastIndex)
                val nextDur = queue[nextIdx].durationMs
                pendingQualityOverride = null
                queueNavigator.onTrackRemovedFromQueue(index, nextIdx, queue.size, snapshot.repeatMode)
                _progress.update { it.copy(positionMs = 0L, durationMs = nextDur) }
                _state.update {
                    it.copy(
                        queue = queue,
                        currentIndex = nextIdx,
                        positionMs = 0L,
                        durationMs = nextDur
                    ).withNavCapabilities()
                }
                persistQueueAndCursorAsync()
                loadAndPlayIndex(nextIdx)
            }
            else -> {
                queueNavigator.onTrackRemovedFromQueue(index, cur, queue.size, snapshot.repeatMode)
                _state.update { it.copy(queue = queue).withNavCapabilities() }
                persistQueueAndCursorAsync()
            }
        }
    }

    fun addToQueue(track: Track) {
        val snapshot = _state.value
        val current = snapshot.queue.toMutableList()
        if (current.isEmpty()) {
            playTracks(listOf(track), 0)
            return
        }
        val existing = current.indexOfFirst { it.id == track.id }
        if (existing >= 0) {
            emitMessage("《${track.title}》已在播放队列中")
            return
        }
        val wasAtQueueTail = snapshot.currentIndex == current.lastIndex
        current.add(track)
        queueNavigator.onTrackAppendedToQueue(
            appendedIndex = current.lastIndex,
            currentIndex = snapshot.currentIndex,
            newQueueSize = current.size,
            mode = snapshot.repeatMode
        )
        if (wasAtQueueTail || snapshot.repeatMode == RepeatMode.SHUFFLE) {
            clearPreloadedNext(removeFromPlayer = true)
        }
        _state.update { it.copy(queue = current).withNavCapabilities() }
        persistQueueAndCursorAsync()
        emitMessage("已加入播放队列：《${track.title}》")
    }

    fun clearQueue() {
        hasUserInitiatedPlayback = true
        allowAutoSkipForCurrentLoad = true
        autoSkippedTrackIndices.clear()
        queueNavigator.reset()
        clearPreloadedNext(removeFromPlayer = false)
        recoveryDelayJob?.cancel()
        resolveJob?.cancel()
        coverJob?.cancel()
        lyricJob?.cancel()
        paletteJob?.cancel()
        playbackRequestId++
        isAwaitingMediaLoad = false
        isRecoveringError = false
        isErrorRecoverySuppressed = false
        requiresFreshReloadOnPlay = false
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = null
        activeStreamSourceId = null
        activeResolvedTrackId = null
        activeResolvedPlatformCode = null
        activeCacheKey = null
        failedSourcePlatformsForCurrentTrack.clear()
        failedSourceTracksForCurrentTrack.clear()
        failedTrackIdsForCurrentTrack.clear()
        lyricsLoadedForTrackId = null
        pendingResumePositionMs = 0L
        pendingQualityOverride = null
        mediaController?.stop()
        mediaController?.clearMediaItems()
        _progress.update { it.copy(positionMs = 0L, durationMs = 0L) }
        _state.update {
            it.copy(
                queue = emptyList(),
                currentIndex = -1,
                queueSource = null,
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L,
                durationMs = 0L,
                activeQualityBadge = null,
                activeProviderName = null,
                activeStreamSourceId = null,
                lyrics = ParsedLyrics(),
                isLoadingLyrics = false,
                ambientPalette = AmbientPalette()
            ).withNavCapabilities()
        }
        persistQueueAndCursorAsync()
        PlaybackService.refreshNotificationIfNeeded()
    }

    fun togglePlayPause() {
        if (isAwaitingMediaLoad || isRecoveringError) {
            onExternalPauseRequested()
            mediaController?.takeIf { it.isConnected }?.pause()
            return
        }
        val ctrl = mediaController
        if (ctrl == null || !ctrl.isConnected) {
            val idx = _state.value.currentIndex
            if (idx >= 0) loadAndPlayIndex(idx, resumePositionMs = pendingResumePositionMs)
            return
        }
        if (ctrl.isPlaying || (ctrl.playWhenReady && ctrl.playbackState == Player.STATE_BUFFERING)) {
            isErrorRecoverySuppressed = true
            // If user pauses a track that was stuck buffering before ever reaching STATE_READY,
            // force a fresh re-resolution on the next Play tap instead of resuming the dead URL.
            if (!ctrl.isPlaying && !hasEnteredReadyForActiveMedia) {
                requiresFreshReloadOnPlay = true
            }
            ctrl.pause()
        } else {
            if (needsReloadForCurrentTrack(ctrl) && _state.value.currentIndex >= 0) {
                loadAndPlayIndex(_state.value.currentIndex, resumePositionMs = pendingResumePositionMs)
            } else {
                isErrorRecoverySuppressed = false
                ctrl.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        if (isAwaitingMediaLoad) return
        val clamped = positionMs.coerceAtLeast(0L)
        pendingResumePositionMs = clamped
        val ctrl = mediaController
        val canSeekActiveItem = ctrl != null &&
            ctrl.isConnected &&
            ctrl.mediaItemCount > 0 &&
            ctrl.playerError == null &&
            ctrl.playbackState != Player.STATE_IDLE &&
            ctrl.playbackState != Player.STATE_ENDED &&
            ctrl.currentMediaItem?.mediaId == activeExpectedMediaId
        if (canSeekActiveItem) {
            ctrl!!.seekTo(clamped)
        }
        _progress.update { it.copy(positionMs = clamped) }
        _state.update { it.copy(positionMs = clamped) }
        persistCursorAsync(force = true)
    }

    fun hasNext(): Boolean {
        val st = _state.value
        return queueNavigator.canSkipNext(st.queue.size, st.currentIndex, st.repeatMode)
    }

    fun hasPrevious(): Boolean {
        val st = _state.value
        return queueNavigator.canSkipPrevious(st.queue.size, st.currentIndex, st.repeatMode)
    }

    fun next() {
        nextInternal(isAutoAdvance = false)
    }

    private fun nextInternal(isAutoAdvance: Boolean) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        hasUserInitiatedPlayback = true
        if (!isAutoAdvance) {
            autoSkippedTrackIndices.clear()
        }

        // Fast-path: if the exact next track chosen by QueueNavigator was already preloaded, play it with 0ms resolve
        val readyNext = preloadedNext
        val preloadedUri = readyNext?.mediaItem?.localConfiguration?.uri
        val expectedPeek = if (readyNext != null && st.repeatMode != RepeatMode.SINGLE_LOOP) {
            queueNavigator.peekNextIndexForAutoAdvance(st.queue.size, st.currentIndex, st.repeatMode)
        } else {
            null
        }
        if (readyNext != null &&
            preloadedUri != null &&
            readyNext.forTrackId == st.currentTrack?.id &&
            readyNext.nextIndex == expectedPeek &&
            readyNext.nextIndex in st.queue.indices &&
            st.queue[readyNext.nextIndex].id == readyNext.track.id
        ) {
            queueNavigator.onPromotedPreloadedNext(st.queue.size, readyNext.nextIndex, st.repeatMode)
            clearPreloadedNext(removeFromPlayer = false)
            recoveryDelayJob?.cancel()
            resolveJob?.cancel()
            coverJob?.cancel()
            val requestId = ++playbackRequestId
            isAwaitingMediaLoad = true
            isRecoveringError = false
            isErrorRecoverySuppressed = false
            hasEnteredReadyForActiveMedia = false
            activeExpectedMediaId = readyNext.track.id
            activeStreamSourceId = readyNext.sourceId
            activeResolvedTrackId = readyNext.resolvedTrackId
            activeResolvedPlatformCode = readyNext.resolvedPlatformCode ?: readyNext.track.source.code
            failedSourcePlatformsForCurrentTrack.clear()
            failedSourceTracksForCurrentTrack.clear()
            failedTrackIdsForCurrentTrack.clear()
            runtimeRetryCount = 0
            allowAutoSkipForCurrentLoad = true
            lastRecoveryStartPositionMs = 0L
            pendingResumePositionMs = 0L
            pendingQualityOverride = null
            PlaybackService.silenceForTransition()

            _progress.update { it.copy(positionMs = 0L, durationMs = readyNext.track.durationMs) }
            _state.update {
                it.copy(
                    currentIndex = readyNext.nextIndex,
                    isPlaying = false,
                    isBuffering = true,
                    positionMs = 0L,
                    durationMs = readyNext.track.durationMs,
                    activeQualityBadge = readyNext.qualityBadge,
                    activeProviderName = readyNext.providerName,
                    activeStreamSourceId = readyNext.sourceId,
                    lyrics = ParsedLyrics(),
                    isLoadingLyrics = true
                ).withNavCapabilities()
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
                    requestId = requestId,
                    track = readyNext.track,
                    mediaUri = preloadedUri,
                    qualityBadge = readyNext.qualityBadge,
                    providerName = readyNext.providerName,
                    sourceId = readyNext.sourceId,
                    cacheKey = readyNext.cacheKey,
                    startPositionMs = 0L
                )
            }
            return
        }

        when (
            val outcome = queueNavigator.navigateNext(
                queueSize = st.queue.size,
                currentIndex = st.currentIndex,
                mode = st.repeatMode,
                isAutoAdvance = isAutoAdvance
            )
        ) {
            is QueueNavigationOutcome.SwitchTo -> {
                pendingQualityOverride = null
                _state.update { it.copy(currentIndex = outcome.targetIndex).withNavCapabilities() }
                loadAndPlayIndex(outcome.targetIndex, isAutoAdvance = isAutoAdvance)
            }
            is QueueNavigationOutcome.RestartCurrent -> {
                restartCurrentTrack(forcePlay = isAutoAdvance || outcome.message != null, message = outcome.message)
            }
            is QueueNavigationOutcome.StayAtBoundary -> {
                emitMessage(outcome.message)
            }
            is QueueNavigationOutcome.StopAtEnd -> {
                stopAtQueueEnd(message = outcome.message)
            }
            QueueNavigationOutcome.NoOp -> Unit
        }
    }

    fun previous() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        hasUserInitiatedPlayback = true
        autoSkippedTrackIndices.clear()
        val (curPos, _) = resolveLivePositionAndDuration(st)
        when (
            val outcome = queueNavigator.navigatePrevious(
                queueSize = st.queue.size,
                currentIndex = st.currentIndex,
                mode = st.repeatMode,
                positionMs = curPos
            )
        ) {
            is QueueNavigationOutcome.SwitchTo -> {
                pendingQualityOverride = null
                _state.update { it.copy(currentIndex = outcome.targetIndex).withNavCapabilities() }
                loadAndPlayIndex(outcome.targetIndex)
            }
            is QueueNavigationOutcome.RestartCurrent -> {
                restartCurrentTrack(forcePlay = outcome.message != null, message = outcome.message)
            }
            is QueueNavigationOutcome.StayAtBoundary -> {
                emitMessage(outcome.message)
            }
            is QueueNavigationOutcome.StopAtEnd -> {
                stopAtQueueEnd(message = outcome.message)
            }
            QueueNavigationOutcome.NoOp -> Unit
        }
    }

    private fun restartCurrentTrack(forcePlay: Boolean, message: String?) {
        val st = _state.value
        val curIdx = st.currentIndex
        if (curIdx !in st.queue.indices) return
        val (_, curDur) = resolveLivePositionAndDuration(st)
        val alreadyLoadingFromStart = isAwaitingMediaLoad && !isRecoveringError && pendingResumePositionMs == 0L
        pendingResumePositionMs = 0L
        lastRecoveryStartPositionMs = 0L

        if (isAwaitingMediaLoad || isRecoveringError) {
            _progress.update { it.copy(positionMs = 0L) }
            _state.update { it.copy(positionMs = 0L).withNavCapabilities() }
            if (forcePlay && !alreadyLoadingFromStart) {
                loadAndPlayIndex(curIdx, resumePositionMs = 0L)
            }
            if (!message.isNullOrBlank()) emitMessage(message)
            return
        }

        clearPreloadedNext(removeFromPlayer = true)
        val ctrl = mediaController
        val expectedId = activeExpectedMediaId ?: st.currentTrack?.id
        val canSeekInPlace = !requiresFreshReloadOnPlay &&
            ctrl != null &&
            ctrl.isConnected &&
            ctrl.mediaItemCount > 0 &&
            ctrl.playerError == null &&
            ctrl.playbackState != Player.STATE_IDLE &&
            ctrl.playbackState != Player.STATE_ENDED &&
            (expectedId == null || ctrl.currentMediaItem?.mediaId == expectedId)

        if (!canSeekInPlace) {
            if (forcePlay || st.isPlaying) {
                loadAndPlayIndex(curIdx, resumePositionMs = 0L)
            } else {
                _progress.update { it.copy(positionMs = 0L) }
                _state.update { it.copy(positionMs = 0L).withNavCapabilities() }
                persistCursorAsync(force = true, livePosOverride = 0L, liveDurOverride = curDur)
            }
        } else {
            val shouldPlay = forcePlay || st.isPlaying || (ctrl!!.playWhenReady && ctrl.playbackState == Player.STATE_BUFFERING)
            if (shouldPlay) {
                isErrorRecoverySuppressed = false
            }
            hasEnteredReadyForActiveMedia = true
            ctrl!!.seekTo(0L)
            if (shouldPlay) {
                ctrl.play()
            }
            val livePlaying = ctrl.isPlaying
            _progress.update { it.copy(positionMs = 0L) }
            _state.update {
                it.copy(
                    positionMs = 0L,
                    isPlaying = if (shouldPlay) livePlaying else false,
                    isBuffering = shouldPlay && ctrl.playbackState == Player.STATE_BUFFERING && !livePlaying
                ).withNavCapabilities()
            }
            persistCursorAsync(force = true, livePosOverride = 0L, liveDurOverride = curDur)
        }
        if (!message.isNullOrBlank()) emitMessage(message)
    }

    private fun stopAtQueueEnd(message: String?) {
        clearPreloadedNext(removeFromPlayer = true)
        recoveryDelayJob?.cancel()
        resolveJob?.cancel()
        playbackRequestId++
        isAwaitingMediaLoad = false
        isRecoveringError = false
        isErrorRecoverySuppressed = true
        requiresFreshReloadOnPlay = true
        hasEnteredReadyForActiveMedia = false
        pendingResumePositionMs = 0L
        lastRecoveryStartPositionMs = 0L
        autoSkippedTrackIndices.clear()
        val (_, curDur) = resolveLivePositionAndDuration(_state.value)
        mediaController?.let { ctrl ->
            if (ctrl.isConnected) {
                // Leave ExoPlayer paused in STATE_ENDED without seekTo(0L) so no spurious buffering
                // flash or expired-URL network fetch/auto-recovery can trigger after sequential end.
                ctrl.pause()
            }
        }
        _progress.update { it.copy(positionMs = 0L) }
        _state.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                positionMs = 0L
            ).withNavCapabilities()
        }
        persistCursorAsync(force = true, livePosOverride = 0L, liveDurOverride = curDur)
        PlaybackService.refreshNotificationIfNeeded()
        if (!message.isNullOrBlank()) emitMessage(message)
    }

    fun cycleRepeatMode() {
        val st = _state.value
        val nextMode = st.repeatMode.next()
        autoSkippedTrackIndices.clear()
        queueNavigator.onModeChanged(st.queue.size, st.currentIndex, nextMode)
        _state.update { it.copy(repeatMode = nextMode).withNavCapabilities() }
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
        emitMessage("已切换为${nextMode.label}")
    }

    fun retryWithQuality(quality: AudioQuality, preferredSourceId: String? = null) {
        val snapshot = _state.value
        val idx = snapshot.currentIndex
        val track = snapshot.currentTrack ?: return
        if (idx < 0 || track.isLocal) return
        if (snapshot.activeQualityBadge == "离线" && preferredSourceId == null) {
            emitMessage("已更新偏好音质为 ${quality.label}（当前播放离线文件）")
            return
        }
        val ctrl = mediaController
        val isPausedHealthy = !snapshot.isPlaying &&
            !snapshot.isBuffering &&
            preferredSourceId == null &&
            !requiresFreshReloadOnPlay &&
            ctrl != null &&
            ctrl.isConnected &&
            !needsReloadForCurrentTrack(ctrl)
        if (isPausedHealthy) {
            // Preserve paused state when user switches quality while paused on a healthy loaded item;
            // detach stale MediaItem so next resume reloads at the newly chosen quality.
            val (curPos, curDur) = resolveLivePositionAndDuration(snapshot)
            pendingResumePositionMs = curPos
            pendingQualityOverride = quality
            clearPreloadedNext(removeFromPlayer = true)
            activeExpectedMediaId = null
            isAwaitingMediaLoad = false
            requiresFreshReloadOnPlay = true
            mediaController?.let { c ->
                if (c.isConnected) {
                    c.stop()
                    c.clearMediaItems()
                }
            }
            _state.update { it.copy(activeQualityBadge = quality.badge) }
            persistCursorAsync(force = true, livePosOverride = curPos, liveDurOverride = curDur)
            emitMessage("已更新偏好音质为 ${quality.label}，继续播放时生效")
            return
        }
        val (curPos, _) = resolveLivePositionAndDuration(snapshot)
        autoSkippedTrackIndices.clear()
        loadAndPlayIndex(
            index = idx,
            qualityOverride = quality,
            preferredSourceId = preferredSourceId,
            resumePositionMs = curPos,
            bypassOfflineCache = preferredSourceId != null
        )
    }

    /**
     * Explicitly switches playback of the current track to the user-selected custom source.
     */
    fun switchSource(sourceId: String) {
        val snapshot = _state.value
        val idx = snapshot.currentIndex
        val track = snapshot.currentTrack ?: return
        if (idx < 0) return
        if (track.isLocal) {
            emitMessage("当前播放本地音频，无需切换在线音源")
            return
        }
        val targetSource = userApiManager.sources.value.firstOrNull { it.id == sourceId }
        val sourceLabel = targetSource?.name ?: "指定音源"
        userApiManager.clearCooldownsForSource(sourceId)
        val (curPos, _) = resolveLivePositionAndDuration(snapshot)
        autoSkippedTrackIndices.clear()
        emitMessage("正在切换至音源：$sourceLabel")
        loadAndPlayIndex(
            index = idx,
            preferredSourceId = sourceId,
            resumePositionMs = curPos,
            bypassOfflineCache = true,
            allowAutoSkipOnFailure = false
        )
    }

    /**
     * Forces switching away from the currently active (source, track/platform) pair to try
     * another custom source or cross-platform alternate for the current song.
     */
    fun switchToAlternateSource() {
        val snapshot = _state.value
        val idx = snapshot.currentIndex
        val track = snapshot.currentTrack ?: return
        if (idx < 0) return
        if (track.isLocal) {
            emitMessage("当前播放本地音频，无需换源")
            return
        }
        val curSid = activeStreamSourceId ?: snapshot.activeStreamSourceId
        val curPlat = activeResolvedPlatformCode ?: track.source.code
        val curResolvedId = activeResolvedTrackId ?: track.id
        if (!curSid.isNullOrBlank()) {
            failedSourceTracksForCurrentTrack.add(UserApiManager.sourceTrackKey(curSid, curResolvedId))
            failedSourcePlatformsForCurrentTrack.add(UserApiManager.sourcePlatformKey(curSid, curPlat))
        }
        if (curResolvedId != track.id) {
            failedTrackIdsForCurrentTrack.add(curResolvedId)
        }
        val (curPos, _) = resolveLivePositionAndDuration(snapshot)
        autoSkippedTrackIndices.clear()
        emitMessage("正在尝试切换其他音源或平台…")
        loadAndPlayIndex(
            index = idx,
            preferredSourceId = null,
            isRuntimeFallbackRetry = false,
            resumePositionMs = curPos,
            preserveExclusions = true,
            bypassOfflineCache = true
        )
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _progress.update { it.copy(sleepTimerRemainingMs = null) }
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            emitMessage("已关闭睡眠定时")
            return
        }
        val totalMs = minutes * 60_000L
        val deadlineRealtimeMs = SystemClock.elapsedRealtime() + totalMs
        _progress.update { it.copy(sleepTimerRemainingMs = totalMs) }
        _state.update { it.copy(sleepTimerRemainingMs = totalMs) }
        emitMessage("将在 $minutes 分钟后暂停播放")
        sleepTimerJob = scope.launch {
            while (isActive) {
                val remaining = (deadlineRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _progress.update { it.copy(sleepTimerRemainingMs = remaining) }
                if (remaining <= 0L) break
                delay(minOf(1_000L, remaining))
            }
            if (!isActive) return@launch
            onExternalPauseRequested()
            mediaController?.takeIf { it.isConnected }?.pause()
            _progress.update { it.copy(sleepTimerRemainingMs = null) }
            _state.update { it.copy(sleepTimerRemainingMs = null) }
            emitMessage("睡眠定时已到，已暂停播放")
        }
    }

    private fun handleTrackEnded() {
        val st = _state.value
        if (st.queue.isEmpty()) return
        nextInternal(isAutoAdvance = true)
    }

    private fun clearPreloadedNext(removeFromPlayer: Boolean, resetAttemptFlag: Boolean = true) {
        preloadJob?.cancel()
        preloadJob = null
        preloadedNext = null
        if (resetAttemptFlag) {
            preloadAttemptedForTrackId = null
        }
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
        val nextIdx = queueNavigator.peekNextIndexForAutoAdvance(
            queueSize = snapshot.queue.size,
            currentIndex = snapshot.currentIndex,
            mode = snapshot.repeatMode
        ) ?: return
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

            val live = _state.value
            if (!isActive || isAwaitingMediaLoad || live.currentTrack?.id != currentTrackId) return@launch
            if (live.repeatMode == RepeatMode.SINGLE_LOOP) return@launch
            val liveExpectedNext = queueNavigator.peekNextIndexForAutoAdvance(
                queueSize = live.queue.size,
                currentIndex = live.currentIndex,
                mode = live.repeatMode
            )
            if (liveExpectedNext != nextIdx || live.queue.getOrNull(nextIdx)?.id != nextTrack.id) return@launch
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
            ?: track.cachedFilePath?.let { path ->
                withContext(Dispatchers.IO) { path.takeIf { File(it).exists() } }
            }
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
        val cacheKey = buildStreamCacheKey(
            trackId = track.id,
            resolvedTrackId = resolved.resolvedTrack.id,
            sourceId = resolved.sourceId,
            quality = resolved.quality,
            sourceRevision = resolved.sourceRevision
        )
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
            resolvedPlatformCode = resolved.resolvedTrack.source.code,
            cacheKey = cacheKey
        )
    }

    private fun loadAndPlayIndex(
        index: Int,
        qualityOverride: AudioQuality? = null,
        preferredSourceId: String? = null,
        isRuntimeFallbackRetry: Boolean = false,
        resumePositionMs: Long = 0L,
        isAutoAdvance: Boolean = false,
        preserveExclusions: Boolean = false,
        bypassOfflineCache: Boolean = false,
        allowAutoSkipOnFailure: Boolean = true
    ) {
        val track = _state.value.queue.getOrNull(index) ?: return
        recoveryDelayJob?.cancel()
        resolveJob?.cancel()
        coverJob?.cancel()
        clearPreloadedNext(removeFromPlayer = true, resetAttemptFlag = !isRuntimeFallbackRetry)

        // Mark awaiting BEFORE silencing previous audio so PlaybackService NEVER drops
        // out of foreground service status while the next track URL is resolving.
        val previousMediaId = activeExpectedMediaId
        val previousActiveSourceId = activeStreamSourceId
        val isSameTrack = isRuntimeFallbackRetry ||
            previousMediaId == track.id ||
            (previousMediaId == null && lyricsLoadedForTrackId == track.id)
        val effectiveQualityOverride = qualityOverride ?: pendingQualityOverride
        pendingQualityOverride = null

        val requestId = ++playbackRequestId
        isAwaitingMediaLoad = true
        isErrorRecoverySuppressed = false
        requiresFreshReloadOnPlay = true
        hasEnteredReadyForActiveMedia = false
        activeExpectedMediaId = track.id
        PlaybackService.silenceForTransition()

        allowAutoSkipForCurrentLoad = PlaybackRecoveryPolicy.resolveAllowAutoSkipForLoad(
            currentAllowAutoSkip = allowAutoSkipForCurrentLoad,
            isRuntimeFallbackRetry = isRuntimeFallbackRetry,
            preserveExclusions = preserveExclusions,
            allowAutoSkipOnFailure = allowAutoSkipOnFailure
        )
        if (!isRuntimeFallbackRetry) {
            isRecoveringError = false
            if (!preserveExclusions) {
                failedSourcePlatformsForCurrentTrack.clear()
                failedSourceTracksForCurrentTrack.clear()
                failedTrackIdsForCurrentTrack.clear()
            }
            activeStreamSourceId = null
            activeResolvedTrackId = null
            activeResolvedPlatformCode = null
            activeCacheKey = null
            runtimeRetryCount = 0
            lastRecoveryStartPositionMs = resumePositionMs
        }
        pendingResumePositionMs = resumePositionMs

        val reuseLyrics = isRuntimeFallbackRetry || lyricsLoadedForTrackId == track.id
        val preservedDuration = _progress.value.durationMs.takeIf { it > 0L } ?: _state.value.durationMs
        val targetDuration = if (isSameTrack && preservedDuration > 0L) preservedDuration else track.durationMs
        _progress.update { it.copy(positionMs = resumePositionMs, durationMs = targetDuration) }
        _state.update {
            it.copy(
                currentIndex = index,
                isPlaying = false,
                isBuffering = true,
                positionMs = resumePositionMs,
                durationMs = targetDuration,
                activeQualityBadge = if (isRuntimeFallbackRetry) it.activeQualityBadge else (effectiveQualityOverride?.badge ?: it.activeQualityBadge?.takeIf { reuseLyrics } ?: track.bestQualityBadge),
                activeProviderName = if (isRuntimeFallbackRetry) it.activeProviderName else null,
                activeStreamSourceId = if (isRuntimeFallbackRetry) it.activeStreamSourceId else null,
                lyrics = if (reuseLyrics) it.lyrics else ParsedLyrics(),
                isLoadingLyrics = !reuseLyrics
            )
        }
        persistCursorAsync(force = true, livePosOverride = resumePositionMs, liveDurOverride = targetDuration)

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
                    requestId = requestId,
                    track = activeTrack,
                    mediaUri = Uri.parse(activeTrack.localUri),
                    qualityBadge = "LOCAL",
                    providerName = "本地音频",
                    startPositionMs = pendingResumePositionMs
                )
                return@launch
            }

            // 2. Offline-first check: downloaded file (bypassed on explicit online source switch or runtime error fallback)
            if (!isRuntimeFallbackRetry && !bypassOfflineCache) {
                val cachedPath = downloadManager.getLocalCachedPath(activeTrack.id)
                    ?: activeTrack.cachedFilePath?.let { path ->
                        withContext(Dispatchers.IO) { path.takeIf { File(it).exists() } }
                    }
                if (!cachedPath.isNullOrBlank()) {
                    startPlaybackOnMedia3(
                        requestId = requestId,
                        track = activeTrack,
                        mediaUri = Uri.fromFile(File(cachedPath)),
                        qualityBadge = "离线",
                        providerName = "离线缓存",
                        startPositionMs = pendingResumePositionMs
                    )
                    return@launch
                }
            }

            // 3. Resolve online stream via LX QuickJS custom sources (priority-aware + time-bounded)
            val settings: AppSettings = settingsDataStore.settings.first()
            val qualityDecision = if (effectiveQualityOverride != null) {
                EffectiveQualityDecision(effectiveQualityOverride, downgradedForCellular = false)
            } else {
                networkAdvisor.decideQuality(settings.preferredQuality, settings.wifiOnlyHighQuality)
            }
            val targetQuality = qualityDecision.quality
            val streamResult = userApiManager.resolveStream(
                track = activeTrack,
                preferredQuality = targetQuality,
                allowCrossPlatformFallback = settings.autoFallbackSource,
                preferredSourceId = preferredSourceId,
                excludedTrackIds = failedTrackIdsForCurrentTrack.toSet(),
                excludedSourcePlatforms = failedSourcePlatformsForCurrentTrack.toSet(),
                excludedSourceTracks = failedSourceTracksForCurrentTrack.toSet()
            )
            if (requestId != playbackRequestId || !isActive) return@launch

            // Re-read latest queue item in case cover finished resolving in parallel
            val latestTrack = _state.value.queue.firstOrNull { it.id == track.id } ?: activeTrack

            streamResult.onSuccess { resolved ->
                activeStreamSourceId = resolved.sourceId
                activeResolvedTrackId = resolved.resolvedTrack.id
                activeResolvedPlatformCode = resolved.resolvedTrack.source.code
                val actuallySwitchedSource = resolved.switchedSource ||
                    ((isRuntimeFallbackRetry || preserveExclusions) &&
                        previousActiveSourceId != null &&
                        resolved.sourceId != previousActiveSourceId)
                when {
                    resolved.switchedPlatform -> emitMessage("已自动换源至 ${resolved.resolvedTrack.source.shortLabel} · ${resolved.providerName}")
                    actuallySwitchedSource -> emitMessage("已自动切换至备用音源：${resolved.providerName}")
                }
                val qualityBadge = if (qualityDecision.downgradedForCellular) {
                    "${resolved.quality.badge} · 省流"
                } else {
                    resolved.quality.badge
                }
                startPlaybackOnMedia3(
                    requestId = requestId,
                    track = latestTrack,
                    mediaUri = Uri.parse(resolved.url),
                    qualityBadge = qualityBadge,
                    providerName = resolved.providerName,
                    sourceId = resolved.sourceId,
                    cacheKey = buildStreamCacheKey(
                        trackId = latestTrack.id,
                        resolvedTrackId = resolved.resolvedTrack.id,
                        sourceId = resolved.sourceId,
                        quality = resolved.quality,
                        sourceRevision = resolved.sourceRevision
                    ),
                    startPositionMs = pendingResumePositionMs
                )
            }.onFailure { err ->
                if (preserveExclusions) {
                    // Exhausted all alternate sources/platforms during manual switch; clear exclusions so next tap starts fresh
                    failedSourcePlatformsForCurrentTrack.clear()
                    failedSourceTracksForCurrentTrack.clear()
                    failedTrackIdsForCurrentTrack.clear()
                }
                val liveIdx = _state.value.queue.indexOfFirst { it.id == latestTrack.id }
                    .takeIf { it >= 0 } ?: _state.value.currentIndex
                handleAllSourcesFailed(
                    failedIndex = liveIdx,
                    failedTrack = latestTrack,
                    preserveExclusions = preserveExclusions,
                    allowAutoSkip = allowAutoSkipForCurrentLoad,
                    resumePositionMs = resumePositionMs,
                    fallbackErrorMessage = err.message ?: "无法播放《${latestTrack.title}》"
                )
            }
        }
    }

    private fun handleAllSourcesFailed(
        failedIndex: Int,
        failedTrack: Track,
        preserveExclusions: Boolean,
        allowAutoSkip: Boolean = allowAutoSkipForCurrentLoad,
        resumePositionMs: Long,
        fallbackErrorMessage: String?
    ) {
        val liveState = _state.value
        val decision = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = failedTrack.title,
            isLocalTrack = failedTrack.isLocal,
            hasReadySource = userApiManager.hasReadySource(),
            isNetworkConnected = networkAdvisor.isNetworkConnected(),
            preserveExclusions = preserveExclusions,
            allowAutoSkip = allowAutoSkip,
            queueSize = liveState.queue.size,
            currentIndex = failedIndex,
            mode = liveState.repeatMode,
            autoSkippedIndices = autoSkippedTrackIndices,
            queueNavigator = queueNavigator,
            fallbackErrorMessage = fallbackErrorMessage
        )
        when (decision) {
            is AutoSkipDecision.SkipTo -> {
                autoSkippedTrackIndices.add(failedIndex)
                if (liveState.repeatMode == RepeatMode.SHUFFLE) {
                    val navOut = queueNavigator.navigateNext(
                        queueSize = liveState.queue.size,
                        currentIndex = failedIndex,
                        mode = RepeatMode.SHUFFLE,
                        isAutoAdvance = true
                    )
                    if (navOut !is QueueNavigationOutcome.SwitchTo || navOut.targetIndex != decision.targetIndex) {
                        queueNavigator.onManualSelectIndex(
                            liveState.queue.size,
                            decision.targetIndex,
                            RepeatMode.SHUFFLE
                        )
                    }
                }
                emitMessage(decision.noticeMessage)
                _state.update { it.copy(currentIndex = decision.targetIndex).withNavCapabilities() }
                loadAndPlayIndex(decision.targetIndex, isAutoAdvance = true)
            }
            is AutoSkipDecision.Stop -> {
                autoSkippedTrackIndices.clear()
                isAwaitingMediaLoad = false
                isRecoveringError = false
                isErrorRecoverySuppressed = true
                requiresFreshReloadOnPlay = true
                hasEnteredReadyForActiveMedia = false
                _progress.update { it.copy(positionMs = resumePositionMs) }
                _state.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        positionMs = resumePositionMs
                    )
                }
                PlaybackService.refreshNotificationIfNeeded()
                _events.tryEmit(
                    PlayerUiEvent(
                        message = decision.noticeMessage,
                        actionLabel = if (decision.promptImportSource) "导入音源" else null,
                        openSourceManager = decision.promptImportSource
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
            if (!isActive) return@launch
            if (!resolvedCover.isNullOrBlank() && resolvedCover != track.coverUrl) {
                val updated = track.copy(coverUrl = resolvedCover)
                _state.update { st ->
                    val targetIdx = if (index in st.queue.indices && st.queue[index].id == track.id) {
                        index
                    } else {
                        st.queue.indexOfFirst { it.id == track.id }
                    }
                    if (targetIdx < 0) {
                        st
                    } else {
                        val mutable = st.queue.toMutableList()
                        mutable[targetIdx] = updated
                        st.copy(queue = mutable)
                    }
                }
                if (_state.value.currentTrack?.id == track.id) {
                    extractAmbientPaletteAsync(resolvedCover)
                }
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
        requestId: Long,
        track: Track,
        mediaUri: Uri,
        qualityBadge: String,
        providerName: String,
        sourceId: String? = null,
        cacheKey: String? = null,
        startPositionMs: Long = 0L
    ) {
        val controller = ensureController() ?: run {
            if (requestId == playbackRequestId) {
                isAwaitingMediaLoad = false
                isRecoveringError = false
                isErrorRecoverySuppressed = true
                requiresFreshReloadOnPlay = true
                hasEnteredReadyForActiveMedia = false
                _state.update { it.copy(isBuffering = false, isPlaying = false) }
                PlaybackService.refreshNotificationIfNeeded()
            }
            return
        }
        if (requestId != playbackRequestId || activeExpectedMediaId != track.id) {
            return
        }

        val mediaItem = buildMediaItem(track, mediaUri, cacheKey)

        activeExpectedMediaId = track.id
        activeStreamSourceId = sourceId
        activeCacheKey = cacheKey
        requiresFreshReloadOnPlay = false
        hasEnteredReadyForActiveMedia = false
        pendingResumePositionMs = startPositionMs

        _state.update {
            it.copy(
                activeQualityBadge = qualityBadge,
                activeProviderName = providerName,
                activeStreamSourceId = sourceId,
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

        // Release transition FGS guard only AFTER play() is issued so setMediaItem() cannot drop FGS on API 31+
        isAwaitingMediaLoad = false
        isRecoveringError = false
        val matchesNewTrack = controller.currentMediaItem?.mediaId == track.id
        if (matchesNewTrack) {
            when {
                controller.playerError != null -> {
                    requiresFreshReloadOnPlay = true
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                }
                controller.playbackState == Player.STATE_READY -> {
                    val dur = controller.duration.takeIf { it > 0L } ?: _state.value.durationMs
                    val livePlaying = controller.isPlaying
                    if (livePlaying) {
                        hasEnteredReadyForActiveMedia = true
                    }
                    _state.update {
                        it.copy(
                            isPlaying = livePlaying,
                            isBuffering = false,
                            durationMs = dur
                        )
                    }
                }
                controller.isPlaying -> {
                    hasEnteredReadyForActiveMedia = true
                    _state.update { it.copy(isPlaying = true, isBuffering = false) }
                }
                controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED || !controller.playWhenReady -> {
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                }
            }
        }
        persistCursorAsync(force = true)

        withContext(Dispatchers.IO) {
            libraryRepository.recordPlay(track)
        }
    }

    private fun loadLyricsAsync(track: Track) {
        lyricJob?.cancel()
        lyricsLoadedForTrackId = null
        lyricJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { musicAggregator.fetchLyrics(track) }
            }
            if (!isActive || _state.value.currentTrack?.id != track.id) return@launch
            result.onSuccess { parsed ->
                lyricsLoadedForTrackId = track.id
                _state.update { it.copy(lyrics = parsed, isLoadingLyrics = false) }
            }.onFailure {
                _state.update { it.copy(lyrics = ParsedLyrics(), isLoadingLyrics = false) }
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
            val bitmap: Bitmap? = try {
                val request = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .size(96)
                    .build()
                val result = imageLoader.execute(request) as? SuccessResult
                (result?.drawable as? BitmapDrawable)?.bitmap
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            if (!isActive || bitmap == null) return@launch

            val palette = withContext(Dispatchers.Default) {
                runCatching { Palette.from(bitmap).maximumColorCount(12).generate() }.getOrNull()
            }
            if (!isActive || palette == null) return@launch
            if (_state.value.currentTrack?.coverUrl != coverUrl) return@launch

            val primaryInt = clampAmbientLightness(
                palette.getDarkVibrantColor(
                    palette.getDarkMutedColor(
                        palette.getDominantColor(0xFF1E3A5F.toInt())
                    )
                ),
                minL = 0.12f,
                maxL = 0.34f
            )
            val secondaryInt = clampAmbientLightness(
                palette.getMutedColor(
                    palette.getDarkMutedColor(0xFF312E81.toInt())
                ),
                minL = 0.12f,
                maxL = 0.34f
            )
            val accentInt = clampAmbientLightness(
                palette.getVibrantColor(
                    palette.getLightVibrantColor(0xFF38BDF8.toInt())
                ),
                minL = 0.62f,
                maxL = 0.78f
            )
            _state.update {
                if (it.currentTrack?.coverUrl != coverUrl) {
                    it
                } else {
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

    private fun clampAmbientLightness(colorInt: Int, minL: Float, maxL: Float): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(colorInt, hsl)
        hsl[2] = hsl[2].coerceIn(minL, maxL)
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
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
                        val dur = ctrl.duration.takeIf { it > 0L }
                            ?: _progress.value.durationMs.takeIf { it > 0L }
                            ?: _state.value.durationMs
                        pendingResumePositionMs = pos
                        val curProg = _progress.value
                        if (pos != curProg.positionMs || dur != curProg.durationMs) {
                            _progress.value = curProg.copy(positionMs = pos, durationMs = dur)
                        }
                        persistCursorAsync(force = false, livePosOverride = pos, liveDurOverride = dur)

                        // Once playback progresses stably by 8s after a recovery, restore fresh retry budget
                        if (runtimeRetryCount > 0 && pos >= lastRecoveryStartPositionMs + STABLE_PLAYBACK_RESET_DELTA_MS) {
                            runtimeRetryCount = 0
                            failedSourcePlatformsForCurrentTrack.clear()
                            failedSourceTracksForCurrentTrack.clear()
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
