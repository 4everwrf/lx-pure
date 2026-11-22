package com.lxpure.music

import com.lxpure.music.core.musicsdk.KuwoProvider
import com.lxpure.music.core.musicsdk.LrcParser
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.musicsdk.NetEaseProvider
import com.lxpure.music.core.musicsdk.asBooleanOrNull
import com.lxpure.music.core.musicsdk.asIntOrNull
import com.lxpure.music.core.musicsdk.asJsonArrayOrNull
import com.lxpure.music.core.musicsdk.asJsonObjectOrNull
import com.lxpure.music.core.musicsdk.asLongOrNull
import com.lxpure.music.core.musicsdk.asStringOrNull
import com.lxpure.music.core.userapi.LxQuickJsRuntime
import com.lxpure.music.core.userapi.LxScriptParser
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.QualityInfo
import com.lxpure.music.domain.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreProtocolTest {

    @Test
    fun parseLxScriptMetadataHeader() {
        val sampleScript = """
            /**
             * @name Demo LX Source
             * @description High quality community custom source
             * @version 2.3.1
             * @author OpenSourceDev
             * @homepage https://example.com
             */
            const { EVENT_NAMES, on, send } = globalThis.lx
        """.trimIndent()

        val meta = LxScriptParser.parse(sampleScript)
        assertEquals("Demo LX Source", meta.name)
        assertEquals("High quality community custom source", meta.description)
        assertEquals("2.3.1", meta.version)
        assertEquals("OpenSourceDev", meta.author)
        assertEquals("https://example.com", meta.homepage)
        assertTrue(meta.id.startsWith("user_api_"))

        val bangHeaderScript = """
            /*!
             * @name Demo Bang Header Source
             * @version v4.0.2
             * @author DemoTeam
             */
        """.trimIndent()
        val bangMeta = LxScriptParser.parse(bangHeaderScript)
        assertEquals("Demo Bang Header Source", bangMeta.name)
        assertEquals("v4.0.2", bangMeta.version)
        assertEquals("DemoTeam", bangMeta.author)
    }

    @Test
    fun formatVersionBadgeDeduplicatesVPrefix() {
        assertEquals("v3.2.18", LxScriptParser.formatVersionBadge("3.2.18"))
        assertEquals("v3.2.18", LxScriptParser.formatVersionBadge("v3.2.18"))
        assertEquals("v3.2.18", LxScriptParser.formatVersionBadge("V3.2.18"))
        assertEquals("v1.0", LxScriptParser.formatVersionBadge("vv1.0"))
        assertEquals("v1.0.0", LxScriptParser.formatVersionBadge(""))
        assertEquals("v1.0.0", LxScriptParser.formatVersionBadge(null))
    }

    @Test
    fun safeJsonExtensionsAndNetEaseParseSurviveJsonNull() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val dirtyJson = """
            {
              "id": 123456,
              "name": "热歌测试曲",
              "ar": [null, {"name": "歌手A"}, {"name": null}],
              "al": null,
              "dt": 215000,
              "l": null,
              "h": null,
              "sq": null,
              "hr": null,
              "flag": null
            }
        """.trimIndent()

        val root = json.parseToJsonElement(dirtyJson).asJsonObjectOrNull()
        assertNotNull(root)
        assertNull(root!!["al"].asJsonObjectOrNull())
        assertNull(root["l"].asJsonArrayOrNull())
        assertNull(root["flag"].asStringOrNull())
        assertNull(root["flag"].asLongOrNull())
        assertNull(root["flag"].asIntOrNull())
        assertNull(root["flag"].asBooleanOrNull())

        val privilege = json.parseToJsonElement(
            """{"maxbr": 999000, "maxBrLevel": "hires"}"""
        ).asJsonObjectOrNull()

        val provider = NetEaseProvider(OkHttpClient())
        val track = provider.parseSongObj(root, privilege)
        assertNotNull(track)
        assertEquals("wy_123456", track!!.id)
        assertEquals("热歌测试曲", track.title)
        assertEquals("歌手A", track.artist)
        assertEquals("", track.album)
        assertNull(track.coverUrl)
        assertEquals(215_000L, track.durationMs)
        assertEquals(4, track.qualities.size)
    }

    @Test
    fun aggregatedSearchPrioritizesReliablePlatformsOverKugou() {
        val kgTrack = Track(
            id = "kg_1",
            title = "Someone Like You",
            artist = "Adele",
            source = MusicSource.KG,
            coverUrl = "https://img.example.com/kg.jpg",
            qualities = listOf(
                QualityInfo("128k"),
                QualityInfo("320k"),
                QualityInfo("flac"),
                QualityInfo("flac24bit")
            )
        )
        val kwTrack = Track(
            id = "kw_1",
            title = "Someone Like You",
            artist = "Adele",
            source = MusicSource.KW,
            coverUrl = null,
            qualities = listOf(QualityInfo("128k"))
        )
        val txTrack = Track(
            id = "tx_1",
            title = "Someone Like You",
            artist = "Adele",
            source = MusicSource.TX,
            coverUrl = "https://img.example.com/tx.jpg",
            qualities = listOf(QualityInfo("128k"), QualityInfo("flac"))
        )
        val wyTrack = Track(
            id = "wy_1",
            title = "Someone Like You",
            artist = "Adele",
            source = MusicSource.WY,
            coverUrl = null,
            qualities = listOf(QualityInfo("128k"))
        )
        val mgTrack = Track(
            id = "mg_1",
            title = "Someone Like You",
            artist = "Adele",
            source = MusicSource.MG,
            coverUrl = "https://img.example.com/mg.jpg",
            qualities = listOf(QualityInfo("128k"), QualityInfo("320k"), QualityInfo("flac"))
        )

        val selected = MusicAggregator.selectPrimaryAndAlternates(
            listOf(kgTrack, mgTrack, kwTrack, txTrack, wyTrack)
        )
        assertEquals(MusicSource.WY, selected.source)
        assertEquals(
            listOf(MusicSource.TX, MusicSource.KW, MusicSource.KG, MusicSource.MG),
            selected.alternates.map { it.source }
        )

        // When WY is absent, TX beats KG even if KG advertises more qualities
        val withoutWy = MusicAggregator.selectPrimaryAndAlternates(listOf(kgTrack, txTrack, kwTrack))
        assertEquals(MusicSource.TX, withoutWy.source)
        assertEquals(listOf(MusicSource.KW, MusicSource.KG), withoutWy.alternates.map { it.source })
    }

    @Test
    fun kuwoImageUrlNormalizationPreventsSslMismatch() {
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/albumcover/500/12/34.jpg",
            KuwoProvider.normalizeKuwoImageUrl("https://img1.kwcdn.kuwo.cn/star/albumcover/500/12/34.jpg")
        )
        assertEquals(
            "http://img1.kuwo.cn/star/albumcover/500/abc.jpg",
            KuwoProvider.normalizeKuwoImageUrl("120/abc.jpg")
        )
        assertNull(KuwoProvider.normalizeKuwoImageUrl("  "))
    }

    @Test
    fun friendlyErrorMappingHidesRawJniStack() {
        val jniErr = "The so library must be initialized before createContext! QuickJSLoader.init should be called on the Android platform."
        val mapped = LxQuickJsRuntime.formatFriendlyError(jniErr)
        assertFalse(mapped.contains("QuickJSLoader"))
        assertTrue(mapped.contains("脚本引擎"))
    }

    @Test
    fun parseLrcAndTranslationMerge() {
        val lrc = """
            [00:01.50]First line
            [00:05.20]Second line
            [00:10.00]Third line
        """.trimIndent()
        val trans = """
            [00:01.55]第一句翻译
            [00:10.05]第三句翻译
        """.trimIndent()

        val parsed = LrcParser.parse(lrc, trans)
        assertEquals(3, parsed.lines.size)
        assertTrue(parsed.hasTranslation)
        assertEquals("第一句翻译", parsed.lines[0].translation)
        assertEquals(null, parsed.lines[1].translation)
        assertEquals("第三句翻译", parsed.lines[2].translation)
        assertEquals(1, parsed.activeIndexAt(6_000L))
    }

    @Test
    fun parseNetEaseYrcLines() {
        val yrc = """
            {"t":0,"c":[{"tx":"作词: Test"}]}
            [1230,2000](1230,500,0)Hello (1730,600,0)World
            [4500,1500](4500,800,0)Second
        """.trimIndent()

        val parsed = LrcParser.parse(yrc)
        assertEquals(2, parsed.lines.size)
        assertEquals(1230L, parsed.lines[0].timeMs)
        assertEquals("Hello World", parsed.lines[0].text)
        assertEquals(4500L, parsed.lines[1].timeMs)
    }

    @Test
    fun trackSerializesToLxMusicInfoContract() {
        val track = Track(
            id = "tx_001abc",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            source = MusicSource.TX,
            songmid = "001abc",
            durationMs = 269_000L,
            coverUrl = "https://example.com/cover.jpg",
            strMediaMid = "media_001",
            qualities = listOf(QualityInfo("128k", "4.1MB"), QualityInfo("flac", "28.4MB"))
        )

        val jsonObj = track.toLxMusicInfoJson()
        assertEquals("晴天", jsonObj["name"]?.jsonPrimitive?.content)
        assertEquals("周杰伦", jsonObj["singer"]?.jsonPrimitive?.content)
        assertEquals("tx", jsonObj["source"]?.jsonPrimitive?.content)
        assertEquals("001abc", jsonObj["songmid"]?.jsonPrimitive?.content)
        assertEquals("04:29", jsonObj["interval"]?.jsonPrimitive?.content)
        assertEquals("media_001", jsonObj["strMediaMid"]?.jsonPrimitive?.content)
        assertEquals(2, jsonObj["types"]?.jsonArray?.size)
        assertEquals("28.4MB", jsonObj["_types"]?.jsonObject?.get("flac")?.jsonObject?.get("size")?.jsonPrimitive?.content)
    }

    @Test
    fun networkQualityAdvisorBalancesCellularDataAndWifiLossless() {
        val cellularSaved = com.lxpure.music.core.network.NetworkQualityAdvisor.decideQualityForNetwork(
            preferred = com.lxpure.music.domain.model.AudioQuality.FLAC,
            cellularDataSaverEnabled = true,
            isMetered = true
        )
        assertEquals(com.lxpure.music.domain.model.AudioQuality.Q128K, cellularSaved.quality)
        assertTrue(cellularSaved.downgradedForCellular)

        val wifiLossless = com.lxpure.music.core.network.NetworkQualityAdvisor.decideQualityForNetwork(
            preferred = com.lxpure.music.domain.model.AudioQuality.FLAC,
            cellularDataSaverEnabled = true,
            isMetered = false
        )
        assertEquals(com.lxpure.music.domain.model.AudioQuality.FLAC, wifiLossless.quality)
        assertFalse(wifiLossless.downgradedForCellular)

        val saverDisabled = com.lxpure.music.core.network.NetworkQualityAdvisor.decideQualityForNetwork(
            preferred = com.lxpure.music.domain.model.AudioQuality.FLAC24,
            cellularDataSaverEnabled = false,
            isMetered = true
        )
        assertEquals(com.lxpure.music.domain.model.AudioQuality.FLAC24, saverDisabled.quality)
        assertFalse(saverDisabled.downgradedForCellular)
    }

    @Test
    fun coverUrlOptimizationReducesThumbnailTraffic() {
        assertEquals(
            "https://p2.music.126.net/abc/123.jpg?param=200y200",
            com.lxpure.music.ui.components.optimizeCoverUrlForSize(
                "https://p2.music.126.net/abc/123.jpg",
                isThumbnail = true
            )
        )
        assertEquals(
            "https://p2.music.126.net/abc/123.jpg?param=640y640",
            com.lxpure.music.ui.components.optimizeCoverUrlForSize(
                "https://p2.music.126.net/abc/123.jpg",
                isThumbnail = false
            )
        )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000001abc.jpg",
            com.lxpure.music.ui.components.optimizeCoverUrlForSize(
                "https://y.gtimg.cn/music/photo_new/T002R500x500M000001abc.jpg",
                isThumbnail = true
            )
        )
    }

    @Test
    fun sourceUrlValidationAndScriptSanityCheck() {
        val validUrls = com.lxpure.music.core.userapi.SourceUrlUtils.buildUpdateCandidateUrls(
            sourceUrl = "  https://example.com/custom-source.js  "
        )
        assertEquals(listOf("https://example.com/custom-source.js"), validUrls)
        assertTrue(com.lxpure.music.core.userapi.SourceUrlUtils.buildUpdateCandidateUrls("file:///local.js").isEmpty())
        assertTrue(com.lxpure.music.core.userapi.SourceUrlUtils.buildUpdateCandidateUrls(null).isEmpty())

        // Verify hex-escaped LX script passes download validation while HTML 404 pages are rejected
        val hexEscapedScript = "/**\n * @name Demo Hex Source\n * @version 1\n */\n" +
            "let x = globalThis['\\x6c\\x78']; " + "a".repeat(200)
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(hexEscapedScript))
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript("<html><body>404 Not Found</body></html>" + " ".repeat(200)))
    }

    @Test
    fun foregroundServiceHeldAcrossBackgroundTrackTransitionAndRecovery() {
        // Song ended naturally in background while queue will auto-advance -> MUST hold FGS
        assertTrue(
            com.lxpure.music.core.player.PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = false,
                playbackState = androidx.media3.common.Player.STATE_ENDED,
                playWhenReady = true,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = false,
                canAutoRecoverFromError = false
            )
        )
        // Resolving next track URL asynchronously while previous audio was silenced -> MUST hold FGS
        assertTrue(
            com.lxpure.music.core.player.PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = true,
                playbackState = androidx.media3.common.Player.STATE_READY,
                playWhenReady = false,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = false,
                canAutoRecoverFromError = false
            )
        )
        // Mid-stream error with auto-recovery budget remaining -> MUST hold FGS
        assertTrue(
            com.lxpure.music.core.player.PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = false,
                playbackState = androidx.media3.common.Player.STATE_IDLE,
                playWhenReady = true,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = true,
                canAutoRecoverFromError = true
            )
        )
        // User explicitly paused playback (not transitioning, not ended, no error) -> MUST release FGS
        assertFalse(
            com.lxpure.music.core.player.PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = true,
                isTransitioningOrRecovering = false,
                playbackState = androidx.media3.common.Player.STATE_READY,
                playWhenReady = false,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = false,
                canAutoRecoverFromError = false
            )
        )
        // Service not currently in foreground -> never suppress normal notification updates
        assertFalse(
            com.lxpure.music.core.player.PlaybackRecoveryPolicy.shouldHoldForegroundDuringTransition(
                isPlaybackOngoing = false,
                isTransitioningOrRecovering = true,
                playbackState = androidx.media3.common.Player.STATE_ENDED,
                playWhenReady = true,
                willAutoAdvanceOnEnd = true,
                hasPlayerError = false,
                canAutoRecoverFromError = true
            )
        )
    }

    @Test
    fun playbackRecoveryPreservesPositionAndNeverBansPrimaryTrack() {
        // 1. Transient UnknownHostException: keep same source, keep cache, preserve position
        val netKind = com.lxpure.music.core.player.PlaybackRecoveryPolicy.classifyError(
            errorCode = androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            rootCauseClassName = "UnknownHostException",
            rootCauseMessage = "UnknownHostException (no network)"
        )
        assertEquals(com.lxpure.music.core.player.PlaybackErrorKind.TRANSIENT_NETWORK, netKind)
        val netPlan = com.lxpure.music.core.player.PlaybackRecoveryPolicy.decideRecovery(
            kind = netKind,
            retryAttemptIndex = 0,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001"
        )
        assertFalse(netPlan.excludeCurrentSource)
        assertFalse(netPlan.excludeResolvedTrack)
        assertFalse(netPlan.evictStreamCache)
        assertTrue(netPlan.preferSameSourceFirst)
        assertTrue(netPlan.preservePosition)

        // 2. Expired CDN URL (HTTP 403): first attempt refreshes same source without banning it
        val expiredKind = com.lxpure.music.core.player.PlaybackRecoveryPolicy.classifyError(
            errorCode = androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            httpStatusCode = 403
        )
        assertEquals(com.lxpure.music.core.player.PlaybackErrorKind.EXPIRED_OR_DENIED_URL, expiredKind)
        val refreshPlan = com.lxpure.music.core.player.PlaybackRecoveryPolicy.decideRecovery(
            kind = expiredKind,
            retryAttemptIndex = 0,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001"
        )
        assertFalse(refreshPlan.excludeCurrentSource)
        assertTrue(refreshPlan.preferSameSourceFirst)
        assertTrue(refreshPlan.preservePosition)

        // 3. Broken stream on primary track: bans ONLY the source, NEVER primary track ID, and evicts cache
        val brokenPlanPrimary = com.lxpure.music.core.player.PlaybackRecoveryPolicy.decideRecovery(
            kind = com.lxpure.music.core.player.PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN,
            retryAttemptIndex = 0,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "wy_1001"
        )
        assertTrue(brokenPlanPrimary.excludeCurrentSource)
        assertFalse(brokenPlanPrimary.excludeResolvedTrack)
        assertTrue(brokenPlanPrimary.evictStreamCache)
        assertTrue(brokenPlanPrimary.preservePosition)

        // 4. Broken stream on cross-platform alternate track: excludes the bad alternate track too
        val brokenPlanAlt = com.lxpure.music.core.player.PlaybackRecoveryPolicy.decideRecovery(
            kind = com.lxpure.music.core.player.PlaybackErrorKind.STREAM_OR_SOURCE_BROKEN,
            retryAttemptIndex = 1,
            primaryTrackId = "wy_1001",
            resolvedTrackId = "tx_9999"
        )
        assertTrue(brokenPlanAlt.excludeCurrentSource)
        assertTrue(brokenPlanAlt.excludeResolvedTrack)
        assertTrue(brokenPlanAlt.evictStreamCache)
    }
}
