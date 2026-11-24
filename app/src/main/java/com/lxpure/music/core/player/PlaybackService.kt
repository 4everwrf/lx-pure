package com.lxpure.music.core.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lxpure.music.LxPureApp
import com.lxpure.music.MainActivity

class PlaybackService : MediaSessionService() {
    companion object {
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
                activeInstance?.exoPlayerRef?.let { player ->
                    if (player.playWhenReady) {
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
    }

    private var mediaSession: MediaSession? = null
    private var exoPlayerRef: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        val app = application as LxPureApp

        val okHttpFactory = OkHttpDataSource.Factory(app.container.streamHttpClient)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        val upstreamFactory = DefaultDataSource.Factory(this, okHttpFactory)
        // Disk-backed 300 MB LRU cache: replays, loops, and seek-backs consume 0 mobile data
        val cachedDataSourceFactory = app.container.mediaCacheManager.wrapWithAudioCache(upstreamFactory)

        // Allow up to 5 internal ExoPlayer load retries (1s..5s backoff) to smoothly absorb
        // brief Wi-Fi <-> 5G handoffs or transient CDN resets without interrupting playback.
        val mediaSourceFactory = DefaultMediaSourceFactory(cachedDataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))

        // Balanced load control: 1.5s fast start, 15s-30s anti-jitter buffer, capped at 10 MB RAM
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_500,
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

        // Bridge system/Bluetooth/Lock-Screen controls directly to our queue controller
        val forwardingPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = app.container.playerController.hasNext()
            override fun hasPreviousMediaItem(): Boolean = app.container.playerController.hasPrevious()

            override fun pause() {
                app.container.playerController.onExternalPauseRequested()
                super.pause()
            }

            override fun play() {
                if (app.container.playerController.needsReloadForCurrentTrack(exoPlayer)) {
                    app.container.playerController.resumeOrReloadCurrent()
                } else {
                    super.play()
                }
            }

            override fun seekToNext() {
                app.container.playerController.next()
            }

            override fun seekToNextMediaItem() {
                app.container.playerController.next()
            }

            override fun seekToPrevious() {
                app.container.playerController.previous()
            }

            override fun seekToPreviousMediaItem() {
                app.container.playerController.previous()
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
        exoPlayerRef = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
