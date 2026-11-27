package com.lxpure.music.core.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import com.lxpure.music.domain.model.RepeatMode

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

sealed class AutoSkipDecision {
    data class SkipTo(
        val targetIndex: Int,
        val noticeMessage: String
    ) : AutoSkipDecision()

    data class Stop(
        val noticeMessage: String,
        val promptImportSource: Boolean = false
    ) : AutoSkipDecision()
}

object PlaybackRecoveryPolicy {
    const val MAX_RUNTIME_RETRIES = 4
    const val MAX_AUTO_SKIP_CHAIN = 10

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
        resolvedTrackId: String?,
        wasReady: Boolean = true
    ): PlaybackRecoveryPlan {
        val isCrossPlatformAlternate = !resolvedTrackId.isNullOrBlank() && resolvedTrackId != primaryTrackId
        return when (kind) {
            PlaybackErrorKind.TRANSIENT_NETWORK -> {
                val keepSameSource = if (wasReady) retryAttemptIndex <= 1 else retryAttemptIndex == 0
                if (keepSameSource) {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = false,
                        excludeResolvedTrack = false,
                        evictStreamCache = false,
                        preferSameSourceFirst = true,
                        preservePosition = true,
                        backoffMs = if (retryAttemptIndex == 0) (if (wasReady) 1_200L else 800L) else 2_500L
                    )
                } else {
                    PlaybackRecoveryPlan(
                        kind = kind,
                        excludeCurrentSource = true,
                        excludeResolvedTrack = isCrossPlatformAlternate,
                        evictStreamCache = false,
                        preferSameSourceFirst = false,
                        preservePosition = true,
                        backoffMs = if (wasReady) 1_500L else 1_000L
                    )
                }
            }
            PlaybackErrorKind.EXPIRED_OR_DENIED_URL -> {
                // Only refresh the exact same source first when a URL expired mid-song (wasReady == true).
                // If a freshly resolved URL immediately returned 401/403/410 at startup, switch source right away.
                if (retryAttemptIndex == 0 && wasReady) {
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
                        backoffMs = 200L
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

    /**
     * Computes the next candidate queue index when auto-skipping after all sources fail.
     * Critically, for [RepeatMode.SINGLE_LOOP] with `queueSize > 1`, advances to `(currentIndex + 1) % queueSize`
     * instead of restarting the unplayable track.
     */
    fun computeAutoSkipNextIndex(
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        queueNavigator: QueueNavigator
    ): Int? {
        if (queueSize <= 1 || currentIndex !in 0 until queueSize) return null
        return when (mode) {
            RepeatMode.SEQUENTIAL -> if (currentIndex + 1 < queueSize) currentIndex + 1 else null
            RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP -> (currentIndex + 1) % queueSize
            RepeatMode.SHUFFLE -> queueNavigator.peekNextIndexForAutoAdvance(queueSize, currentIndex, mode)
        }
    }

    /**
     * Resolves whether auto-skip on failure is permitted for the current playback load.
     * Explicit manual source switches (`preserveExclusions = true` or `allowAutoSkipOnFailure = false`)
     * disable auto-skip for the entire load, including across subsequent runtime fallback retries.
     */
    fun resolveAllowAutoSkipForLoad(
        currentAllowAutoSkip: Boolean,
        isRuntimeFallbackRetry: Boolean,
        preserveExclusions: Boolean,
        allowAutoSkipOnFailure: Boolean
    ): Boolean = if (isRuntimeFallbackRetry) {
        currentAllowAutoSkip
    } else {
        !preserveExclusions && allowAutoSkipOnFailure
    }

    /**
     * Unified R3 decision for both stream-resolution failure and exhausted runtime playback retries.
     */
    fun decideAutoSkipOnFailure(
        trackTitle: String,
        isLocalTrack: Boolean,
        hasReadySource: Boolean,
        isNetworkConnected: Boolean,
        preserveExclusions: Boolean,
        allowAutoSkip: Boolean = !preserveExclusions,
        queueSize: Int,
        currentIndex: Int,
        mode: RepeatMode,
        autoSkippedIndices: Set<Int>,
        queueNavigator: QueueNavigator,
        fallbackErrorMessage: String? = null,
        preferFallbackErrorOnStop: Boolean = false
    ): AutoSkipDecision {
        if (!hasReadySource && !isLocalTrack) {
            return AutoSkipDecision.Stop(
                noticeMessage = fallbackErrorMessage ?: "当前无可用自定义音源，请检查音源状态或导入音源",
                promptImportSource = true
            )
        }
        if (preserveExclusions) {
            return AutoSkipDecision.Stop(
                noticeMessage = "暂无其他可用音源或平台，点击播放可重新尝试默认音源",
                promptImportSource = false
            )
        }
        if (!allowAutoSkip) {
            return AutoSkipDecision.Stop(
                noticeMessage = fallbackErrorMessage ?: "《$trackTitle》暂无可用音源，已停止播放",
                promptImportSource = false
            )
        }
        if (isLocalTrack) {
            return AutoSkipDecision.Stop(
                noticeMessage = fallbackErrorMessage ?: "无法播放本地音频《$trackTitle》",
                promptImportSource = false
            )
        }
        if (!isNetworkConnected) {
            return AutoSkipDecision.Stop(
                noticeMessage = fallbackErrorMessage ?: "网络连接不可用，请恢复网络后重试",
                promptImportSource = false
            )
        }

        val stopNotice = if (preferFallbackErrorOnStop && !fallbackErrorMessage.isNullOrBlank()) {
            fallbackErrorMessage
        } else {
            "《$trackTitle》暂无可用音源，已停止播放"
        }

        val updatedSkipped = autoSkippedIndices + currentIndex
        val maxChain = minOf(queueSize, MAX_AUTO_SKIP_CHAIN)
        if (queueSize <= 1 || updatedSkipped.size >= maxChain) {
            return AutoSkipDecision.Stop(
                noticeMessage = stopNotice,
                promptImportSource = false
            )
        }

        val targetIndex = computeAutoSkipNextIndex(
            queueSize = queueSize,
            currentIndex = currentIndex,
            mode = mode,
            queueNavigator = queueNavigator
        )
        if (targetIndex == null || targetIndex in updatedSkipped || targetIndex == currentIndex) {
            return AutoSkipDecision.Stop(
                noticeMessage = stopNotice,
                promptImportSource = false
            )
        }

        return AutoSkipDecision.SkipTo(
            targetIndex = targetIndex,
            noticeMessage = "《$trackTitle》暂无可用音源，已自动跳至下一首"
        )
    }
}
