package com.lxpure.music

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.lxpure.music.core.player.FastFailLoadErrorHandlingPolicy
import com.lxpure.music.core.player.PlaybackErrorKind
import com.lxpure.music.core.player.PlaybackRecoveryPolicy
import com.lxpure.music.core.player.PlayerController
import com.lxpure.music.core.userapi.LxQuickJsRuntime
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.UserApiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Unit tests verifying fast-fail ExoPlayer retry behavior, downward-only quality fallback,
 * startup vs mid-song error recovery policy, scoped cache key revisions, and URL/source helpers.
 */
class PlaybackAndSourceSwitchingTest {

    @Test
    fun audioQualityFallbackChainIsStrictlyDownwardAndRespectsCellularDataSaver() {
        assertEquals(
            listOf(AudioQuality.FLAC24, AudioQuality.FLAC, AudioQuality.Q320K, AudioQuality.Q128K),
            AudioQuality.fallbackChain(AudioQuality.FLAC24)
        )
        assertEquals(
            listOf(AudioQuality.FLAC, AudioQuality.Q320K, AudioQuality.Q128K),
            AudioQuality.fallbackChain(AudioQuality.FLAC)
        )
        assertEquals(
            listOf(AudioQuality.Q320K, AudioQuality.Q128K),
            AudioQuality.fallbackChain(AudioQuality.Q320K)
        )
        assertEquals(
            listOf(AudioQuality.Q128K),
            AudioQuality.fallbackChain(AudioQuality.Q128K)
        )
    }

    private fun allocateDummyDataSpec(): DataSpec {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    @Test
    fun fastFailLoadErrorHandlingPolicyAbortsImmediatelyOnDeterministicHttpAndParserErrors() {
        val policy = FastFailLoadErrorHandlingPolicy()
        val dummySpec = allocateDummyDataSpec()

        fun loadErrorInfo(ex: IOException, errorCount: Int): LoadErrorHandlingPolicy.LoadErrorInfo {
            val loadEvent = LoadEventInfo(1L, dummySpec, 0L)
            val mediaData = MediaLoadData(C.DATA_TYPE_MEDIA)
            return LoadErrorHandlingPolicy.LoadErrorInfo(loadEvent, mediaData, ex, errorCount)
        }

        // Deterministic HTTP codes (401/403/404/410/416) must fail immediately so source fallback fires in <1s
        for (code in listOf(400, 401, 403, 404, 410, 416, 422, 451, 501)) {
            val httpErr = HttpDataSource.InvalidResponseCodeException(
                code,
                "HTTP $code",
                null,
                emptyMap(),
                dummySpec,
                ByteArray(0)
            )
            assertTrue("HTTP $code should be non-retryable", FastFailLoadErrorHandlingPolicy.isNonRetryableLoadException(httpErr))
            assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(httpErr, 1)))
        }

        // ParserException & FileNotFoundException must fail immediately
        val parserErr = ParserException.createForMalformedContainer("corrupt header", null)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(parserErr, 1)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(FileNotFoundException("missing"), 1)))

        // Transient network / 503 should allow at most 2 fast retries (500ms, 1000ms), then fail fast
        val timeoutErr = SocketTimeoutException("read timed out")
        assertFalse(FastFailLoadErrorHandlingPolicy.isNonRetryableLoadException(timeoutErr))
        assertEquals(500L, policy.getRetryDelayMsFor(loadErrorInfo(timeoutErr, 1)))
        assertEquals(1_000L, policy.getRetryDelayMsFor(loadErrorInfo(timeoutErr, 2)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(timeoutErr, 3)))

        val server503 = HttpDataSource.InvalidResponseCodeException(
            503,
            "Service Unavailable",
            null,
            emptyMap(),
            dummySpec,
            ByteArray(0)
        )
        assertEquals(500L, policy.getRetryDelayMsFor(loadErrorInfo(server503, 1)))
        assertEquals(1_000L, policy.getRetryDelayMsFor(loadErrorInfo(server503, 2)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(loadErrorInfo(server503, 3)))
    }

    @Test
    fun recoveryPolicySwitchesImmediatelyOnStartupHttp403WhileRefreshingMidSongExpiration() {
        // Mid-song 403 (wasReady = true): attempt 0 refreshes same source first
        val midSongPlan = PlaybackRecoveryPolicy.decideRecovery(
            kind = PlaybackErrorKind.EXPIRED_OR_DENIED_URL,
            retryAttemptIndex = 0,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001",
            wasReady = true
        )
        assertFalse(midSongPlan.excludeCurrentSource)
        assertTrue(midSongPlan.preferSameSourceFirst)

        // Immediate startup 403 (wasReady = false): freshly resolved URL was denied -> switch source immediately
        val startupDeniedPlan = PlaybackRecoveryPolicy.decideRecovery(
            kind = PlaybackErrorKind.EXPIRED_OR_DENIED_URL,
            retryAttemptIndex = 0,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001",
            wasReady = false
        )
        assertTrue(startupDeniedPlan.excludeCurrentSource)
        assertFalse(startupDeniedPlan.preferSameSourceFirst)
        assertTrue(startupDeniedPlan.evictStreamCache)
        assertFalse(startupDeniedPlan.excludeResolvedTrack)

        // Startup transient network switches source faster (after attempt 0 instead of waiting 2 retries)
        val startupNetAttempt1 = PlaybackRecoveryPolicy.decideRecovery(
            kind = PlaybackErrorKind.TRANSIENT_NETWORK,
            retryAttemptIndex = 1,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001",
            wasReady = false
        )
        assertTrue(startupNetAttempt1.excludeCurrentSource)
        assertFalse(startupNetAttempt1.preferSameSourceFirst)
    }

    @Test
    fun streamCacheKeysAndSourceHelpersIsolateScriptRevisionsAndPlatforms() {
        val k1 = PlayerController.buildStreamCacheKey(
            trackId = "wy_1",
            resolvedTrackId = "wy_1",
            sourceId = "src_a",
            quality = AudioQuality.FLAC,
            sourceRevision = "rev11111"
        )
        val k2 = PlayerController.buildStreamCacheKey(
            trackId = "wy_1",
            resolvedTrackId = "wy_1",
            sourceId = "src_a",
            quality = AudioQuality.FLAC,
            sourceRevision = "rev22222"
        )
        assertNotEquals(k1, k2)
        assertTrue(k1.endsWith("_flac"))
        assertTrue(k2.endsWith("_flac"))

        val srcV1 = UserApiSource(
            id = "s1",
            name = "Test",
            description = "desc",
            version = "1.0.0",
            author = "tester",
            homepage = "",
            script = "console.log('v1')",
            sourceUrl = null,
            enabled = true,
            priority = 0
        )
        val srcV2 = srcV1.copy(version = "1.0.1", script = "console.log('v2')")
        assertNotEquals(
            UserApiManager.computeSourceRevision(srcV1),
            UserApiManager.computeSourceRevision(srcV2)
        )

        assertEquals("s1_wy", UserApiManager.sourcePlatformKey("s1", "WY"))
        assertEquals("s1_wy_1001", UserApiManager.sourceTrackKey("s1", "wy_1001"))
        assertTrue(UserApiManager.isDeterministicTrackMiss("该歌曲暂无版权"))
        assertTrue(UserApiManager.isDeterministicTrackMiss("歌曲已下架 404"))
        // VIP / 403 on higher tiers must NOT trigger full-track pruning so 128k fallback still runs
        assertFalse(UserApiManager.isDeterministicTrackMiss("需要VIP会员才可试听无损"))
        assertFalse(UserApiManager.isDeterministicTrackMiss("HTTP 403 Forbidden"))
        assertFalse(UserApiManager.isDeterministicTrackMiss("当前音质不可用"))

        assertEquals(
            "https://cdn.example.com/a%20b%7Cc.mp3",
            LxQuickJsRuntime.sanitizeRequestUrl("  https://cdn.example.com/a b|c.mp3 \n")
        )
    }
}
