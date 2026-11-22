package com.lxpure.music.core.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource

enum class PlaybackErrorKind {
    TRANSIENT_NETWORK,
    EXPIRED_OR_DENIED_URL,
    STREAM_OR_SOURCE_BROKEN
}

data class PlaybackRecoveryPlan(
    val kind: PlaybackErrorKind,
    val excludeCurrentSource: Boolean,
    val excludeResolvedTrack: Boolean,
    val evictStreamCache: Boolean,
    val preferSameSourceFirst: Boolean,
    val preservePosition: Boolean,
    val backoffMs: Long
)

object PlaybackRecoveryPolicy {
    const val MAX_RUNTIME_RETRIES = 4
    const val MAX_CONSECUTIVE_AUTO_SKIPS = 3

    private val transientNetworkCauseNames = setOf(
        "UnknownHostException",
        "SocketTimeoutException",
        "ConnectException",
        "SocketException",
        "EOFException",
        "InterruptedIOException",
        "NoRouteToHostException",
        "StreamResetException",
        "ConnectionShutdownException",
        "ProtocolException"
    )

    fun classifyPlaybackException(error: PlaybackException): PlaybackErrorKind {
        var cause: Throwable? = error.cause
        var httpStatusCode: Int? = null
        var matchedClassName: String? = null
        var matchedMessage: String? = error.message
        var depth = 0
        while (cause != null && depth < 6) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                httpStatusCode = cause.responseCode
            }
            val simpleName = cause.javaClass.simpleName
            if (simpleName in transientNetworkCauseNames) {
                matchedClassName = simpleName
                matchedMessage = cause.message
                break
            }
            if (matchedClassName == null) {
                matchedClassName = simpleName
                matchedMessage = cause.message
            }
            cause = cause.cause
            depth++
        }
        return classifyError(
            errorCode = error.errorCode,
            httpStatusCode = httpStatusCode,
            rootCauseClassName = matchedClassName,
            rootCauseMessage = matchedMessage
        )
    }

    fun classifyError(
        errorCode: Int,
        httpStatusCode: Int? = null,
        rootCauseClassName: String? = null,
        rootCauseMessage: String? = null
    ): PlaybackErrorKind {
        if (httpStatusCode != null) {
            return when (httpStatusCode) {
                401, 403, 410 -> PlaybackErrorKind.EXPIRED_OR_DENIED_URL
                408, 429 -> PlaybackErrorKind.TRANSIENT_NETWORK
                else -> PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN
            }
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
            return PlaybackErrorKind.EXPIRED_OR_DENIED_URL
        }
        if (errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT
        ) {
            return PlaybackErrorKind.TRANSIENT_NETWORK
        }
        if (!rootCauseClassName.isNullOrBlank() && rootCauseClassName in transientNetworkCauseNames) {
            return PlaybackErrorKind.TRANSIENT_NETWORK
        }
        val msg = rootCauseMessage.orEmpty()
        if (msg.contains("UnknownHostException", ignoreCase = true) ||
            msg.contains("no network", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("connection reset", ignoreCase = true) ||
            msg.contains("connection abort", ignoreCase = true)
        ) {
            return PlaybackErrorKind.TRANSIENT_NETWORK
        }
        return PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN
    }

    /**
     * Computes a deterministic recovery plan that:
     * - Preserves playback position across mid-song recoveries.
     * - Never bans a healthy source on transient network loss.
     * - Never bans the primary track ID (so backup sources can rescue the same song).
     * - Evicts potentially truncated/corrupted disk cache entries when a stream or codec breaks.
     */
    fun decideRecovery(
        kind: PlaybackErrorKind,
        retryAttemptIndex: Int,
        primaryTrackId: String,
        resolvedTrackId: String?
    ): PlaybackRecoveryPlan {
        val isCrossPlatformAlternate = !resolvedTrackId.isNullOrBlank() && resolvedTrackId != primaryTrackId
        return when (kind) {
            PlaybackErrorKind.TRANSIENT_NETWORK -> {
                if (retryAttemptIndex <= 1) {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = false,
                        excludeResolvedTrack = false,
                        evictStreamCache = false,
                        preferSameSourceFirst = true,
                        preservePosition = true,
                        backoffMs = if (retryAttemptIndex == 0) 1_200L else 2_500L
                    )
                } else {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = true,
                        excludeResolvedTrack = isCrossPlatformAlternate,
                        evictStreamCache = false,
                        preferSameSourceFirst = false,
                        preservePosition = true,
                        backoffMs = 1_500L
                    )
                }
            }
            PlaybackErrorKind.EXPIRED_OR_DENIED_URL -> {
                if (retryAttemptIndex == 0) {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = false,
                        excludeResolvedTrack = false,
                        evictStreamCache = false,
                        preferSameSourceFirst = true,
                        preservePosition = true,
                        backoffMs = 300L
                    )
                } else {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = true,
                        excludeResolvedTrack = isCrossPlatformAlternate,
                        evictStreamCache = true,
                        preferSameSourceFirst = false,
                        preservePosition = true,
                        backoffMs = 300L
                    )
                }
            }
            PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN -> {
                PlaybackRecoveryPlan(
                    kind = kind,
                    excludeCurrentSource = true,
                    excludeResolvedTrack = isCrossPlatformAlternate,
                    evictStreamCache = true,
                    preferSameSourceFirst = false,
                    preservePosition = true,
                    backoffMs = 250L
                )
            }
        }
    }

    /**
     * Prevents Media3 MediaSessionService from calling stopForeground() during brief async
     * track transitions or mid-stream error recovery while playback is active in background.
     * Dropping foreground status in background causes Android 12-16 to reject re-promotion
     * with ForegroundServiceStartNotAllowedException and revoke background network access.
     */
    fun shouldHoldForegroundDuringTransition(
        isPlaybackOngoing: Boolean,
        isTransitioningOrRecovering: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
        willAutoAdvanceOnEnd: Boolean,
        hasPlayerError: Boolean,
        canAutoRecoverFromError: Boolean
    ): Boolean {
        if (!isPlaybackOngoing) return false
        if (isTransitioningOrRecovering) return true
        if (playbackState == Player.STATE_ENDED && playWhenReady && willAutoAdvanceOnEnd) return true
        if (hasPlayerError && canAutoRecoverFromError) return true
        return false
    }
}
