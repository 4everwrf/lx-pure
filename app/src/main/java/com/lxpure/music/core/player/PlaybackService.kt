package com.lxpure.music.core.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lxpure.music.LxPureApp
import com.lxpure.music.MainActivity
import com.lxpure.music.core.userapi.LxQuickJsRuntime
import java.io.FileNotFoundException

class PlaybackService : MediaSessionService() {
    companion object {
        internal const val HINT_INTERNAL_CONTROLLER = "com.lxpure.music.INTERNAL_CONTROLLER"
        internal const val BUFFER_FOR_PLAYBACK_MS = 700
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeInstance: PlaybackService? = null

        /**
         * Silently pauses the underlying ExoPlayer during an async track transition
         * without clearing MediaItems, cancelling the notification, or cancelling PlayerController's
         * pending resolution. Keeps the mediaPlayback foreground service continuously intact.
         */
        fun silenceForTransition() {
            val runSilence = {
                val service = activeInstance
                val transitioning = (service?.application as? LxPureApp)
                    ?.container?.playerController?.isTransitioningOrRecovering() == true
                service?.exoPlayerRef?.let { player ->
                    if (transitioning && player.playWhenReady) {
                        player.pause()
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runSilence()
            } else {
                mainHandler.post { runSilence() }
            }
        }

        /**
         * Re-evaluates foreground service / notification state after an async transition finishes
         * with a terminal error or explicit queue clear so foreground is cleanly released.
         */
        @OptIn(UnstableApi::class)
        fun refreshNotificationIfNeeded() {
            val runRefresh = {
                activeInstance?.let { service ->
                    service.mediaSession?.let { session ->
                        service.onUpdateNotification(session, false)
                    }
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runRefresh()
            } else {
                mainHandler.post { runRefresh() }
            }
        }

        /**
         * Propagates updated queue navigation capabilities (canSkipPrevious / canSkipNext / hasQueue)
         * to MediaSession & system notifications after `_state` commits.
         */
        fun refreshAvailableCommands() {
            mainHandler.post {
                activeInstance?.notifyAvailableCommandsChanged?.invoke()
            }
        }
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayerRef: ExoPlayer? = null
    private var notifyAvailableCommandsChanged: (() -> Unit)? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val app = application as LxPureApp

        val okHttpFactory = OkHttpDataSource.Factory(app.container.streamHttpClient)
            .setUserAgent(LxQuickJsRuntime.CHROME_USER_AGENT)
        val upstreamFactory = DefaultDataSource.Factory(this, okHttpFactory)
        // Disk-backed 300 MB LRU cache: replays, loops, and seek-backs consume 0 mobile data
        val cachedDataSourceFactory = app.container.mediaCacheManager.wrapWithAudioCache(upstreamFactory)

        // Fail immediately on deterministic HTTP 401/403/404/410/416 and cap transient network retries
        // to 2 fast attempts (0.5s, 1.0s) so dead CDNs trigger source fallback in <2s instead of stalling 10-90s.
        val mediaSourceFactory = DefaultMediaSourceFactory(cachedDataSourceFactory)
            .setLoadErrorHandlingPolicy(FastFailLoadErrorHandlingPolicy())

        // Balanced load control: 700ms fast start, 15s-30s anti-jitter buffer, capped at 10 MB RAM
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .setTargetBufferBytes(10 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayerRef = exoPlayer

        // Bridge system/Bluetooth/Lock-Screen controls directly to our queue controller and intercept
        // ExoPlayer's raw onAvailableCommandsChanged so shrinking 2->1 preloaded items never strips Next.
        val wrappedListeners = java.util.IdentityHashMap<Player.Listener, Player.Listener>()
        fun computeForwardingCommands(base: Player.Commands): Player.Commands {
            val pc = app.container.playerController
            val builder = base.buildUpon()
            if (pc.hasQueue()) {
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                if (pc.hasPrevious()) {
                    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                } else {
                    builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                if (pc.hasNext()) {
                    builder.add(Player.COMMAND_SEEK_TO_NEXT)
                    builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                } else {
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT)
                    builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
            } else {
                builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                builder.remove(Player.COMMAND_SEEK_TO_NEXT)
                builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return builder.build()
        }

        val forwardingPlayer = PlaybackForwardingPlayer(
            basePlayer = exoPlayer,
            wrappedListeners = wrappedListeners,
            transformCommands = ::computeForwardingCommands,
            hasQueue = { app.container.playerController.hasQueue() },
            canSkipPrevious = { app.container.playerController.hasPrevious() },
            canSkipNext = { app.container.playerController.hasNext() },
            shouldDeferPrepareOrSeekToPlay = { app.container.playerController.shouldDeferPrepareOrSeekToPlay() },
            needsReloadForCurrentTrack = { app.container.playerController.needsReloadForCurrentTrack(exoPlayer) },
            onExternalPause = {
                val session = mediaSession
                val caller = session?.controllerForCurrentRequest
                val isInternalAppController = caller != null &&
                    caller.uid == android.os.Process.myUid() &&
                    caller.connectionHints.getBoolean(HINT_INTERNAL_CONTROLLER, false) &&
                    !session.isMediaNotificationController(caller)
                if (!isInternalAppController) {
                    app.container.playerController.onExternalPauseRequested()
                }
            },
            onExternalPlay = { app.container.playerController.onExternalPlayRequested() },
            onResumeOrReload = { app.container.playerController.resumeOrReloadCurrent() },
            onNext = { app.container.playerController.next() },
            onPrevious = { app.container.playerController.previous() }
        )
        notifyAvailableCommandsChanged = {
            val listenersSnapshot = synchronized(wrappedListeners) { wrappedListeners.keys.toList() }
            val updatedCommands = forwardingPlayer.availableCommands
            for (listener in listenersSnapshot) {
                val stillRegistered = synchronized(wrappedListeners) { wrappedListeners.containsKey(listener) }
                if (stillRegistered) {
                    listener.onAvailableCommandsChanged(updatedCommands)
                }
            }
            // MediaNotificationManager on API 26-32 does not listen to onAvailableCommandsChanged;
            // explicitly re-trigger notification refresh while playback is active/prepared.
            if (isPlaybackOngoing || exoPlayer.playbackState != Player.STATE_IDLE) {
                mediaSession?.let { onUpdateNotification(it, false) }
            }
        }

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityIntent)
            .setCallback(object : MediaSession.Callback {
                @OptIn(UnstableApi::class)
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    if (app.container.playerController.hasQueue()) {
                        app.container.playerController.resumeOrReloadCurrent()
                    }
                    return super.onPlaybackResumption(mediaSession, controller)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val app = application as LxPureApp
        val controller = app.container.playerController
        val player = session.player
        val keepForeground = PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
            isPlaybackOngoing = isPlaybackOngoing,
            isTransitioningOrRecovering = controller.isTransitioningOrRecovering(),
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            willAutoAdvanceOnEnd = controller.willAutoAdvanceOnEnd(),
            hasPlayerError = player.playerError != null,
            canAutoRecoverFromError = controller.canAutoRecoverFromError()
        )
        if (!startInForegroundRequired && keepForeground) {
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as LxPureApp
        app.container.playerController.persistSnapshotNow()
        val player = mediaSession?.player
        val keepAlive = isPlaybackOngoing ||
            app.container.playerController.isTransitioningOrRecovering() ||
            (player != null && player.playWhenReady && player.mediaItemCount > 0)
        if (!keepAlive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching {
            (application as? LxPureApp)?.container?.playerController?.persistSnapshotNow()
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        notifyAvailableCommandsChanged = null
        exoPlayerRef = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
internal class PlaybackForwardingPlayer(
    basePlayer: Player,
    private val wrappedListeners: java.util.IdentityHashMap<Player.Listener, Player.Listener> = java.util.IdentityHashMap(),
    private val transformCommands: (Player.Commands) -> Player.Commands = { it },
    private val hasQueue: () -> Boolean,
    private val canSkipPrevious: () -> Boolean,
    private val canSkipNext: () -> Boolean,
    private val shouldDeferPrepareOrSeekToPlay: () -> Boolean = { false },
    private val needsReloadForCurrentTrack: () -> Boolean = { false },
    private val onExternalPause: () -> Unit = {},
    private val onExternalPlay: () -> Unit = {},
    private val onResumeOrReload: () -> Unit = {},
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit
) : ForwardingPlayer(basePlayer) {

    override fun addListener(listener: Player.Listener) {
        val proxy = synchronized(wrappedListeners) {
            wrappedListeners.getOrPut(listener) {
                CommandOverridingPlayerListener(
                    delegate = listener,
                    transformCommands = transformCommands
                )
            }
        }
        super.addListener(proxy)
    }

    override fun removeListener(listener: Player.Listener) {
        val proxy = synchronized(wrappedListeners) {
            wrappedListeners.remove(listener)
        } ?: listener
        super.removeListener(proxy)
    }

    override fun getAvailableCommands(): Player.Commands =
        transformCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_PREVIOUS -> hasQueue()
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> canSkipPrevious()
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> canSkipNext()
        else -> super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean = canSkipNext()
    override fun hasPreviousMediaItem(): Boolean = canSkipPrevious()

    override fun prepare() {
        if (shouldDeferPrepareOrSeekToPlay()) return
        super.prepare()
    }

    override fun seekToDefaultPosition() {
        if (shouldDeferPrepareOrSeekToPlay()) return
        super.seekToDefaultPosition()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun pause() {
        onExternalPause()
        super.pause()
    }

    override fun play() {
        if (needsReloadForCurrentTrack()) {
            onResumeOrReload()
        } else {
            onExternalPlay()
            super.play()
        }
    }

    override fun seekToNext() {
        onNext()
    }

    override fun seekToNextMediaItem() {
        onNext()
    }

    override fun seekToPrevious() {
        onPrevious()
    }

    override fun seekToPreviousMediaItem() {
        onPrevious()
    }
}

/**
 * Explicit forwarding proxy for [Player.Listener].
 *
 * IMPORTANT: Do NOT replace with Kotlin `by delegate`! Every method on Java interface
 * [Player.Listener] is a Java 8 `default` no-op method, and Kotlin interface delegation does NOT
 * generate forwarding stubs for Java `default` methods — using `by` silently swallows every
 * ExoPlayer callback (`onIsPlayingChanged`, `onPlaybackStateChanged`, `onMediaItemTransition`,
 * `onPlayerError`, etc.) except overridden ones.
 */
internal class CommandOverridingPlayerListener(
    private val delegate: Player.Listener,
    private val transformCommands: (Player.Commands) -> Player.Commands
) : Player.Listener {

    override fun onEvents(player: Player, events: Player.Events) =
        delegate.onEvents(player, events)

    override fun onTimelineChanged(timeline: Timeline, reason: Int) =
        delegate.onTimelineChanged(timeline, reason)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
        delegate.onMediaItemTransition(mediaItem, reason)

    override fun onTracksChanged(tracks: Tracks) =
        delegate.onTracksChanged(tracks)

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
        delegate.onMediaMetadataChanged(mediaMetadata)

    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
        delegate.onPlaylistMetadataChanged(mediaMetadata)

    override fun onIsLoadingChanged(isLoading: Boolean) =
        delegate.onIsLoadingChanged(isLoading)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onLoadingChanged(isLoading: Boolean) =
        delegate.onLoadingChanged(isLoading)

    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
        delegate.onAvailableCommandsChanged(transformCommands(availableCommands))

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
        delegate.onTrackSelectionParametersChanged(parameters)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
        delegate.onPlayerStateChanged(playWhenReady, playbackState)

    override fun onPlaybackStateChanged(playbackState: Int) =
        delegate.onPlaybackStateChanged(playbackState)

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        delegate.onPlayWhenReadyChanged(playWhenReady, reason)

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        delegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

    override fun onIsPlayingChanged(isPlaying: Boolean) =
        delegate.onIsPlayingChanged(isPlaying)

    override fun onRepeatModeChanged(repeatMode: Int) =
        delegate.onRepeatModeChanged(repeatMode)

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
        delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)

    override fun onPlayerError(error: PlaybackException) =
        delegate.onPlayerError(error)

    override fun onPlayerErrorChanged(error: PlaybackException?) =
        delegate.onPlayerErrorChanged(error)

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onPositionDiscontinuity(reason: Int) =
        delegate.onPositionDiscontinuity(reason)

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) = delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        delegate.onPlaybackParametersChanged(playbackParameters)

    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
        delegate.onSeekBackIncrementChanged(seekBackIncrementMs)

    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
        delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)

    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
        delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

    @OptIn(UnstableApi::class)
    override fun onAudioSessionIdChanged(audioSessionId: Int) =
        delegate.onAudioSessionIdChanged(audioSessionId)

    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
        delegate.onAudioAttributesChanged(audioAttributes)

    override fun onVolumeChanged(volume: Float) =
        delegate.onVolumeChanged(volume)

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
        delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)

    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) =
        delegate.onDeviceInfoChanged(deviceInfo)

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) =
        delegate.onDeviceVolumeChanged(volume, muted)

    override fun onVideoSizeChanged(videoSize: VideoSize) =
        delegate.onVideoSizeChanged(videoSize)

    override fun onSurfaceSizeChanged(width: Int, height: Int) =
        delegate.onSurfaceSizeChanged(width, height)

    override fun onRenderedFirstFrame() =
        delegate.onRenderedFirstFrame()

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCues(cues: MutableList<Cue>) =
        delegate.onCues(cues)

    override fun onCues(cueGroup: CueGroup) =
        delegate.onCues(cueGroup)

    @OptIn(UnstableApi::class)
    override fun onMetadata(metadata: Metadata) =
        delegate.onMetadata(metadata)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommandOverridingPlayerListener) return false
        return delegate == other.delegate
    }

    override fun hashCode(): Int = delegate.hashCode()
}

@OptIn(UnstableApi::class)
internal class FastFailLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(MAX_TRANSIENT_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isNonRetryableLoadException(loadErrorInfo.exception)) {
            return C.TIME_UNSET
        }
        if (loadErrorInfo.errorCount > MAX_TRANSIENT_RETRIES) {
            return C.TIME_UNSET
        }
        return minOf(loadErrorInfo.errorCount * 500L, 1_000L)
    }

    companion object {
        internal const val MAX_TRANSIENT_RETRIES = 2
        private val deterministicHttpCodes = setOf(400, 401, 403, 404, 410, 416, 422, 451, 501)

        internal fun isNonRetryableLoadException(ex: Throwable?): Boolean {
            var cur = ex
            var depth = 0
            while (cur != null && depth < 6) {
                when (cur) {
                    is HttpDataSource.CleartextNotPermittedException,
                    is ParserException,
                    is FileNotFoundException,
                    is Loader.UnexpectedLoaderException -> return true
                    is HttpDataSource.InvalidResponseCodeException -> {
                        if (cur.responseCode in deterministicHttpCodes) return true
                    }
                    is DataSourceException -> {
                        if (cur.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) return true
                    }
                }
                cur = cur.cause
                depth++
            }
            return false
        }
    }
}
