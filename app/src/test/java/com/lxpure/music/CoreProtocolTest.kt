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
import com.lxpure.music.core.update.AppUpdateManager
import com.lxpure.music.core.userapi.LxQuickJsRuntime
import com.lxpure.music.core.userapi.LxScriptParser
import com.lxpure.music.domain.model.AudioQuality
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

    @Test
    fun playbackSessionStorePersistsQueuePlaylistContextAndProgressRoundTrip() {
        val tempDir = java.nio.file.Files.createTempDirectory("lx_session_test").toFile()
        try {
            val store = com.lxpure.music.data.local.PlaybackSessionStore(tempDir)
            val t1 = Track(id = "wy_1", title = "晴天", artist = "周杰伦", album = "叶惠美", source = MusicSource.WY, songmid = "1", durationMs = 269_000L)
            val t2 = Track(id = "tx_2", title = "稻香", artist = "周杰伦", album = "魔杰座", source = MusicSource.TX, songmid = "2", durationMs = 223_000L)
            val t3 = Track(id = "wy_3", title = "夜曲", artist = "周杰伦", album = "十一月的萧邦", source = MusicSource.WY, songmid = "3", durationMs = 226_000L)
            val source = com.lxpure.music.domain.model.PlaybackQueueSource(
                type = com.lxpure.music.domain.model.QueueSourceType.USER_PLAYLIST,
                title = "通勤歌单",
                userPlaylistId = 42L
            )

            store.saveQueueAndCursor(
                queue = listOf(t1, t2, t3),
                queueSource = source,
                currentIndex = 1,
                positionMs = 95_000L,
                durationMs = 223_000L,
                repeatMode = com.lxpure.music.domain.model.RepeatMode.SHUFFLE,
                activeQualityBadge = "SQ",
                activeProviderName = "测试源"
            )

            // Simulate cursor update during playback without rewriting queue file
            store.saveCursorOnly(
                currentIndex = 2,
                currentTrackId = "wy_3",
                positionMs = 142_500L,
                durationMs = 226_000L,
                repeatMode = com.lxpure.music.domain.model.RepeatMode.SINGLE_LOOP,
                activeQualityBadge = "HR",
                activeProviderName = "测试源"
            )

            // Cold relaunch with a brand new store instance
            val reloaded = com.lxpure.music.data.local.PlaybackSessionStore(tempDir).loadSession()
            assertNotNull(reloaded)
            assertEquals(3, reloaded!!.queue.size)
            assertEquals(2, reloaded.currentIndex)
            assertEquals("wy_3", reloaded.queue[reloaded.currentIndex].id)
            assertEquals(142_500L, reloaded.positionMs)
            assertEquals(226_000L, reloaded.durationMs)
            assertEquals(com.lxpure.music.domain.model.RepeatMode.SINGLE_LOOP, reloaded.repeatMode)
            assertEquals("通勤歌单", reloaded.queueSource?.title)
            assertEquals(42L, reloaded.queueSource?.userPlaylistId)
            assertEquals("HR", reloaded.activeQualityBadge)

            // Index drift reconciliation: cursor index stale, trackId still matches index 1
            val reconciled = com.lxpure.music.data.local.PlaybackSessionStore.resolveRestoredSession(
                queueSnapshot = com.lxpure.music.domain.model.PlaybackQueueSnapshot(queue = listOf(t1, t2, t3), queueSource = source),
                cursorSnapshot = com.lxpure.music.domain.model.PlaybackCursorSnapshot(
                    currentIndex = 0,
                    currentTrackId = "tx_2",
                    positionMs = 222_500L,
                    durationMs = 223_000L
                )
            )
            assertNotNull(reconciled)
            assertEquals(1, reconciled!!.currentIndex)
            // Near-end timestamp clamped back 2s so pressing Play doesn't insta-skip
            assertEquals(221_000L, reconciled.positionMs)

            // Oversized queue slicing keeps active track centered
            val bigQueue = (0 until 6000).map { i -> Track(id = "wy_$i", title = "Song $i", artist = "Artist") }
            val (sliced, remappedIdx) = com.lxpure.music.data.local.PlaybackSessionStore.sliceQueueForPersistence(bigQueue, 3456)
            assertEquals(com.lxpure.music.data.local.PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE, sliced.size)
            assertEquals("wy_3456", sliced[remappedIdx].id)

            store.clear()
            assertNull(store.loadSession())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun appUpdateVersionComparisonAndGithubReleaseParsing() {
        assertTrue(com.lxpure.music.core.update.AppUpdateManager.isNewerVersion("1.1.0", "1.1.1"))
        assertTrue(com.lxpure.music.core.update.AppUpdateManager.isNewerVersion("1.1.0-debug", "v1.1.1"))
        assertTrue(com.lxpure.music.core.update.AppUpdateManager.isNewerVersion("1.2.0", "1.10.0"))
        assertFalse(com.lxpure.music.core.update.AppUpdateManager.isNewerVersion("1.1.1", "v1.1.1"))
        assertFalse(com.lxpure.music.core.update.AppUpdateManager.isNewerVersion("1.2.0", "1.1.9"))
        assertEquals(0, com.lxpure.music.core.update.AppUpdateManager.compareVersions("v1.2", "1.2.0"))

        val releaseJson = """
            {
              "tag_name": "v1.1.1",
              "name": "v1.1.1",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/4everwrf/lx-pure/releases/tag/v1.1.1",
              "body": "## 轻雪音乐 (LX Pure) v1.1.1\n\n### 本版变更记录\n- 修复播放页误触切歌\n- 新增退出后自动记录歌单与进度",
              "assets": [
                {
                  "name": "lx-pure-ios-v1.1.1-unsigned.ipa",
                  "size": 1350000,
                  "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "browser_download_url": "https://github.com/4everwrf/lx-pure/releases/download/v1.1.1/lx-pure-ios-v1.1.1-unsigned.ipa"
                },
                {
                  "name": "lx-pure-v1.1.1-release.apk",
                  "size": 8200000,
                  "digest": "sha256:5aa7222a07e8ce0775389a3f3611bc03c08a3f48dd02108de4a97b3acd76ed10",
                  "browser_download_url": "https://github.com/4everwrf/lx-pure/releases/download/v1.1.1/lx-pure-v1.1.1-release.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = com.lxpure.music.core.update.AppUpdateManager.parseGithubReleaseJson(releaseJson)
        assertNotNull(parsed)
        assertEquals("1.1.1", parsed!!.versionName)
        assertEquals("v1.1.1", parsed.tagName)
        assertEquals("lx-pure-v1.1.1-release.apk", parsed.assetName)
        assertEquals(8_200_000L, parsed.assetSizeBytes)
        assertEquals("5aa7222a07e8ce0775389a3f3611bc03c08a3f48dd02108de4a97b3acd76ed10", parsed.expectedSha256)
        assertTrue(parsed.releaseNotes.contains("修复播放页误触切歌"))

        // Draft releases must be ignored
        assertNull(com.lxpure.music.core.update.AppUpdateManager.parseGithubReleaseJson(releaseJson.replace("\"draft\": false", "\"draft\": true")))

        assertEquals(
            "v1.2.3",
            com.lxpure.music.core.update.AppUpdateManager.extractTagFromReleaseUrl("https://github.com/4everwrf/lx-pure/releases/tag/v1.2.3?ref=app")
        )
        assertTrue(com.lxpure.music.core.update.AppUpdateManager.isValidZipOrApkMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(com.lxpure.music.core.update.AppUpdateManager.isValidZipOrApkMagic("<html>".toByteArray()))
    }

    @Test
    fun normalizeScriptStripsBomAndUnifiesLineEndings() {
        val unixScript = "/**\n * @name Demo\n * @version 1\n */\nconst s = \"\\r\\n\";\n"
        val windowsBomScript = "\uFEFF/**\r\n * @name Demo\r\n * @version 1\r\n */\rconst s = \"\\r\\n\";\r\n"

        val normalized = LxScriptParser.normalizeScript(windowsBomScript)
        assertEquals(unixScript, normalized)
        assertEquals(
            com.lxpure.music.core.userapi.LxCrypto.md5Hex(unixScript.trim()),
            com.lxpure.music.core.userapi.LxCrypto.md5Hex(normalized.trim())
        )
        assertEquals("Demo", LxScriptParser.parse(windowsBomScript).name)
    }

    @Test
    fun batchUpdateSummaryFormatsAccurateMessagesForLocalAndOnlineSources() {
        assertEquals(
            "当前均为本地导入脚本，未绑定在线更新链接",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 0,
                failedCount = 0,
                skippedLocalCount = 7
            ).formatUserMessage()
        )
        assertEquals(
            "全部 2 个在线音源均已是最新版本（已跳过 3 个本地脚本）",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 2,
                failedCount = 0,
                skippedLocalCount = 3
            ).formatUserMessage()
        )
        assertEquals(
            "全部 2 个在线音源均已是最新版本",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 2,
                failedCount = 0,
                skippedLocalCount = 0
            ).formatUserMessage()
        )
        assertEquals(
            "已更新 1 个音源：源A v2.0（另有 1 个拉取失败）",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = listOf("源A v2.0"),
                latestCount = 0,
                failedCount = 1,
                skippedLocalCount = 2
            ).formatUserMessage()
        )
        assertEquals(
            "1 个已最新，2 个拉取失败",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 1,
                failedCount = 2,
                skippedLocalCount = 0
            ).formatUserMessage()
        )
        assertEquals(
            "更新检查失败，请检查网络连接后重试",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 0,
                failedCount = 2,
                skippedLocalCount = 3
            ).formatUserMessage()
        )
        assertEquals(
            "暂无可检查更新的音源",
            com.lxpure.music.core.userapi.BatchUpdateSummary(
                updatedNames = emptyList(),
                latestCount = 0,
                failedCount = 0,
                skippedLocalCount = 0
            ).formatUserMessage()
        )
    }

    @Test
    fun customPlaylistLimitAndTrackDeduplicationRules() {
        assertEquals(10, com.lxpure.music.data.repository.LibraryRepository.MAX_CUSTOM_PLAYLISTS)
        assertTrue(com.lxpure.music.data.repository.LibraryRepository.isCustomPlaylist(null))
        assertTrue(com.lxpure.music.data.repository.LibraryRepository.isCustomPlaylist("   "))
        assertFalse(com.lxpure.music.data.repository.LibraryRepository.isCustomPlaylist("https://music.163.com/playlist?id=1"))
        assertFalse(com.lxpure.music.data.repository.LibraryRepository.isCustomPlaylist("wy:123456"))

        val nineCustomAndTwoExternal = (1L..9L).map { id ->
            com.lxpure.music.domain.model.UserPlaylist(
                id = id,
                name = "自建$id",
                coverUrl = null,
                description = "",
                sourceUrl = null,
                trackCount = 0,
                createdAt = id
            )
        } + listOf(
            com.lxpure.music.domain.model.UserPlaylist(
                id = 100L,
                name = "网易导入",
                coverUrl = null,
                description = "",
                sourceUrl = "https://music.163.com/playlist?id=1",
                trackCount = 12,
                createdAt = 100L
            ),
            com.lxpure.music.domain.model.UserPlaylist(
                id = 101L,
                name = "飙升榜",
                coverUrl = null,
                description = "",
                sourceUrl = "leaderboard:wy:19723756",
                trackCount = 50,
                createdAt = 101L
            )
        )
        assertEquals(9, com.lxpure.music.data.repository.LibraryRepository.customPlaylistCount(nineCustomAndTwoExternal))
        assertTrue(com.lxpure.music.data.repository.LibraryRepository.canCreateCustomPlaylist(nineCustomAndTwoExternal))

        val tenCustomAndTwoExternal = nineCustomAndTwoExternal +
            com.lxpure.music.domain.model.UserPlaylist(
                id = 10L,
                name = "自建10",
                coverUrl = null,
                description = "",
                sourceUrl = null,
                trackCount = 0,
                createdAt = 10L
            )
        assertEquals(10, com.lxpure.music.data.repository.LibraryRepository.customPlaylistCount(tenCustomAndTwoExternal))
        assertFalse(com.lxpure.music.data.repository.LibraryRepository.canCreateCustomPlaylist(tenCustomAndTwoExternal))

        val t1 = Track(id = "wy_1", title = "Song 1", artist = "A")
        val t2 = Track(id = "tx_2", title = "Song 2", artist = "B")
        val t3 = Track(id = "kw_3", title = "Song 3", artist = "C")
        val t4 = Track(id = "wy_4", title = "Song 4", artist = "D")
        val (uniqueNew, dupCount) = com.lxpure.music.data.repository.LibraryRepository.filterNewTracksForPlaylist(
            existingTrackIds = setOf("wy_1", "tx_2"),
            tracksToAdd = listOf(t1, t3, t3, t2, t4)
        )
        assertEquals(listOf("kw_3", "wy_4"), uniqueNew.map { it.id })
        assertEquals(3, dupCount)
    }

    @Test
    fun largePlaylistStressAndMemoryLeakGuard() {
        val tempDir = java.nio.file.Files.createTempDirectory("lxpure_stress_test").toFile()
        try {
            val store = com.lxpure.music.data.local.PlaybackSessionStore(tempDir)
            val runtime = Runtime.getRuntime()
            System.gc()
            Thread.sleep(30)
            val baselineUsedBytes = runtime.totalMemory() - runtime.freeMemory()

            // Stress 20 playlists (10 custom + 10 large external) x 2,000 tracks = 40,000 tracks processed
            var weakLastLargeQueue: java.lang.ref.WeakReference<List<Track>>? = null
            for (round in 1..20) {
                val largeTracks = (0 until 2_000).map { idx ->
                    Track(
                        id = "wy_${round}_$idx",
                        title = "大歌单压测曲目 $round-$idx",
                        artist = "歌手 $idx",
                        album = "专辑 $round",
                        source = MusicSource.WY,
                        songmid = "${round * 10_000 + idx}",
                        durationMs = 210_000L,
                        coverUrl = "https://p2.music.126.net/test_${round}_$idx.jpg"
                    )
                }
                val existingIds = largeTracks.take(1_000).mapTo(HashSet()) { it.id }
                val (uniqueNew, dupCount) = com.lxpure.music.data.repository.LibraryRepository.filterNewTracksForPlaylist(
                    existingTrackIds = existingIds,
                    tracksToAdd = largeTracks
                )
                assertEquals(1_000, uniqueNew.size)
                assertEquals(1_000, dupCount)

                // Verify persistence strictly bounds huge 6,000-track queues without memory blowup
                if (round % 5 == 0) {
                    val giantQueue = (0 until 6_000).map { idx ->
                        Track(id = "tx_${round}_$idx", title = "Giant $idx", artist = "Artist")
                    }
                    val source = com.lxpure.music.domain.model.PlaybackQueueSource(
                        type = com.lxpure.music.domain.model.QueueSourceType.USER_PLAYLIST,
                        title = "超大外部歌单 $round",
                        userPlaylistId = round.toLong()
                    )
                    store.saveQueueAndCursor(
                        queue = giantQueue,
                        currentIndex = 4_321,
                        positionMs = 88_000L,
                        durationMs = 240_000L,
                        repeatMode = com.lxpure.music.domain.model.RepeatMode.LIST_LOOP,
                        queueSource = source,
                        activeQualityBadge = "SQ",
                        activeProviderName = null
                    )
                    val restored = store.loadSession()
                    assertNotNull(restored)
                    assertEquals(com.lxpure.music.data.local.PlaybackSessionStore.MAX_PERSISTED_QUEUE_SIZE, restored!!.queue.size)
                    assertEquals("tx_${round}_4321", restored.queue[restored.currentIndex].id)
                    weakLastLargeQueue = java.lang.ref.WeakReference(giantQueue)
                }
            }

            store.clear()
            assertNull(store.loadSession())

            // Verify temporary large playlist allocations are reclaimable (no static/cache retention leak)
            var reclaimed = false
            for (attempt in 0 until 6) {
                System.gc()
                Thread.sleep(30)
                if (weakLastLargeQueue?.get() == null) {
                    reclaimed = true
                    break
                }
            }
            assertTrue("Expected temporary 6,000-track list to be GC-reclaimable", reclaimed)

            val afterUsedBytes = runtime.totalMemory() - runtime.freeMemory()
            val retainedDeltaMb = (afterUsedBytes - baselineUsedBytes) / (1024L * 1024L)
            assertTrue("Retained heap delta after stress test should stay bounded, was ${retainedDeltaMb}MB", retainedDeltaMb < 48L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun updateRoutesErrorMessagesNotesParsingAndFastScrollBounds() {
        val rawUrl = "https://github.com/4everwrf/lx-pure/releases/download/v1.1.2/lx-pure-v1.1.2-release.apk"
        val routes = com.lxpure.music.core.update.AppUpdateManager.buildDownloadRoutes(rawUrl)
        assertEquals(3, routes.size)
        assertEquals("GitHub 直连", routes[0].label)
        assertEquals(rawUrl, routes[0].url)
        assertTrue(routes[1].url.endsWith(rawUrl))
        assertTrue(routes[2].url.endsWith(rawUrl))

        val timeoutMsg = com.lxpure.music.core.update.AppUpdateManager.formatDownloadError(
            java.net.SocketTimeoutException("connect timed out"),
            triedMirrors = true
        )
        assertTrue(timeoutMsg.contains("超时"))
        assertTrue(timeoutMsg.contains("代理"))

        val unreachableMsg = com.lxpure.music.core.update.AppUpdateManager.formatDownloadError(
            java.net.UnknownHostException("github.com"),
            triedMirrors = true
        )
        assertTrue(unreachableMsg.contains("无法连接"))

        val rawReleaseBody = """
            ## 轻雪音乐 (LX Pure) v1.1.2

            ### 本版变更记录
            - feat: 支持播放页添加到歌单 (6f4900e)
            - perf(android): 修复大歌单内存抖动 (fb036db)

            ### 重要提示
            - Android 与 iOS 安装包均为**开源纯净版**

            ### 校验信息
            - SHA-256: `abc`
        """.trimIndent()
        val parsedItems = com.lxpure.music.core.update.AppUpdateManager.parseReleaseNoteItems(rawReleaseBody)
        assertEquals(
            listOf("支持播放页添加到歌单", "修复大歌单内存抖动"),
            parsedItems
        )

        // Prepend-to-top O(1) position ordering math
        val firstPrependPos = com.lxpure.music.data.repository.LibraryRepository.computeInsertStartPosition(
            minPosition = 0,
            maxPosition = 99,
            insertCount = 1,
            prepend = true
        )
        assertEquals(-1, firstPrependPos)
        val secondPrependPos = com.lxpure.music.data.repository.LibraryRepository.computeInsertStartPosition(
            minPosition = firstPrependPos,
            maxPosition = 99,
            insertCount = 2,
            prepend = true
        )
        assertEquals(-3, secondPrependPos)
        assertEquals(
            100,
            com.lxpure.music.data.repository.LibraryRepository.computeInsertStartPosition(
                minPosition = -3,
                maxPosition = 99,
                insertCount = 1,
                prepend = false
            )
        )

        // Fast-scroll drag fraction boundary checks
        assertEquals(0, com.lxpure.music.ui.components.computeFastScrollTrackIndex(0f, 500))
        assertEquals(0, com.lxpure.music.ui.components.computeFastScrollTrackIndex(-0.5f, 500))
        assertEquals(499, com.lxpure.music.ui.components.computeFastScrollTrackIndex(1f, 500))
        assertEquals(499, com.lxpure.music.ui.components.computeFastScrollTrackIndex(1.5f, 500))
        assertEquals(250, com.lxpure.music.ui.components.computeFastScrollTrackIndex(0.5f, 501))
        assertEquals(0, com.lxpure.music.ui.components.computeFastScrollTrackIndex(0.8f, 1))
    }

    @Test
    fun round1PlaybackSessionMismatchResetsPositionAndCacheKeysIsolateSources() {
        val t1 = Track(id = "wy_1", title = "Song 1", artist = "A", durationMs = 180_000L)
        val t2 = Track(id = "wy_2", title = "Song 2", artist = "B", durationMs = 240_000L)
        val restored = com.lxpure.music.data.local.PlaybackSessionStore.resolveRestoredSession(
            queueSnapshot = com.lxpure.music.domain.model.PlaybackQueueSnapshot(queue = listOf(t1, t2)),
            cursorSnapshot = com.lxpure.music.domain.model.PlaybackCursorSnapshot(
                currentIndex = 1,
                currentTrackId = "deleted_track_id",
                positionMs = 135_000L,
                durationMs = 310_000L,
                activeQualityBadge = "HR",
                activeProviderName = "OldSource"
            )
        )
        assertNotNull(restored)
        assertEquals(1, restored!!.currentIndex)
        assertEquals(0L, restored.positionMs)
        assertEquals(240_000L, restored.durationMs)
        assertNull(restored.activeProviderName)

        // Distinct sources / cross-platform fallback tracks must produce distinct SimpleCache keys
        val k1 = com.lxpure.music.core.player.PlayerController.buildStreamCacheKey(
            trackId = "wy_1",
            resolvedTrackId = "wy_1",
            sourceId = "src_a",
            quality = com.lxpure.music.domain.model.AudioQuality.FLAC
        )
        val k2 = com.lxpure.music.core.player.PlayerController.buildStreamCacheKey(
            trackId = "wy_1",
            resolvedTrackId = "wy_1",
            sourceId = "src_b",
            quality = com.lxpure.music.domain.model.AudioQuality.FLAC
        )
        val k3 = com.lxpure.music.core.player.PlayerController.buildStreamCacheKey(
            trackId = "wy_1",
            resolvedTrackId = "tx_99",
            sourceId = "src_a",
            quality = com.lxpure.music.domain.model.AudioQuality.FLAC
        )
        assertFalse(k1 == k2)
        assertFalse(k1 == k3)
        assertTrue(k1.endsWith("_flac"))
    }

    @Test
    fun round2SdkCryptoLyricsAndUserApiRegressionSuite() {
        // 1. LxCrypto: URL-safe Base64, CBC empty IV -> zero IV (deterministic), zlib deflate/inflate round-trip
        val raw = "LX Pure Round 2 音频沙箱 🎵"
        val b64 = com.lxpure.music.core.userapi.LxCrypto.str2b64(raw)
        val compressed = com.lxpure.music.core.userapi.LxCrypto.zlibDeflateBase64(b64)
        assertTrue(compressed.isNotEmpty())
        val decompressed = com.lxpure.music.core.userapi.LxCrypto.zlibInflateBase64(compressed)
        assertEquals(b64, decompressed)

        val keyB64 = com.lxpure.music.core.userapi.LxCrypto.str2b64("0123456789abcdef")
        val cbcEmptyIv1 = com.lxpure.music.core.userapi.LxCrypto.aesEncryptBase64(b64, keyB64, "", "AES/CBC/PKCS7Padding")
        val cbcEmptyIv2 = com.lxpure.music.core.userapi.LxCrypto.aesEncryptBase64(b64, keyB64, "", "AES/CBC/PKCS7Padding")
        assertTrue(cbcEmptyIv1.isNotEmpty())
        assertEquals(cbcEmptyIv1, cbcEmptyIv2)

        // 2. LxScriptParser: license banner before metadata + distinct IDs for headerless scripts
        val bannerScript = """
            /*! MIT License Copyright 2026 */
            /**
             * @name Real Source After License
             * @version 3.0.1
             */
            globalThis.lx.send('inited', { sources: {} })
        """.trimIndent()
        val bannerMeta = LxScriptParser.parse(bannerScript)
        assertEquals("Real Source After License", bannerMeta.name)
        assertEquals("3.0.1", bannerMeta.version)

        val headlessA = LxScriptParser.parse("const a = 1; globalThis.lx.on('request', () => 'a')")
        val headlessB = LxScriptParser.parse("const b = 2; globalThis.lx.on('request', () => 'b')")
        assertFalse(headlessA.id == headlessB.id)

        // 3. BOM HTML rejection & transport vs per-track failure classification
        assertFalse(
            com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(
                "\uFEFF<!DOCTYPE html><html><body>lx 404</body></html>" + " ".repeat(200)
            )
        )
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isTransportOrRuntimeFailure("请求超时，请检查网络连接"))
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isTransportOrRuntimeFailure("音源暂不支持解析当前曲目"))

        // 4. LrcParser: BOM, [offset:], colon & 4-digit ms, QQ "//" filter, 1-to-1 trans matching, duplicate timeMs
        val lrcWithEdgeCases = "\uFEFF[offset:-500]\n[00:01.0000]Line One\n[00:01:20]Line Two\n[00:05.00]Dup A\n[00:05.00]Dup B"
        val transWithSlash = "[00:01.00]第一句\n[00:05.00]//"
        val parsedLrc = LrcParser.parse(lrcWithEdgeCases, transWithSlash)
        assertEquals(4, parsedLrc.lines.size)
        assertEquals(1_500L, parsedLrc.lines[0].timeMs)
        assertEquals("第一句", parsedLrc.lines[0].translation)
        assertNull(parsedLrc.lines[1].translation) // 1-to-1 matching prevents bleed
        assertNull(parsedLrc.lines[2].translation) // "//" filtered out
        assertEquals(2, parsedLrc.activeIndexAt(5_600L)) // lands on first of identical-timestamp group

        // 5. FormatUtils.decodeHtml: numeric entities, <em> stripping, no &amp; double decode
        assertEquals(
            "Rock & Roll '99 &lt;Live>",
            com.lxpure.music.core.musicsdk.FormatUtils.decodeHtml("<em>Rock</em> &#38; Roll &#x27;99 &amp;lt;Live&gt;")
        )

        // 6. CJK share copy URL extraction
        assertEquals(
            "http://163cn.tv/abc123",
            com.lxpure.music.core.musicsdk.extractFirstHttpUrl("分享歌单 http://163cn.tv/abc123（来自@网易云音乐）")
        )

        // 7. MusicAggregator.isTrackMatch: variant mismatch rejection + reversed multi-artist match
        val original = Track(id = "wy_1", title = "千里之外", artist = "周杰伦 / 费玉清", durationMs = 256_000L)
        val karaoke = Track(id = "tx_2", title = "千里之外 (伴奏)", artist = "周杰伦 / 费玉清", durationMs = 256_000L)
        val reversedArtist = Track(id = "kw_3", title = "千里之外", artist = "费玉清 / 周杰伦", durationMs = 257_000L)
        assertFalse(MusicAggregator.isTrackMatch(original, karaoke))
        assertTrue(MusicAggregator.isTrackMatch(original, reversedArtist))
    }

    @Test
    fun round3DataDownloadAndUpdaterSecurityRegressionSuite() {
        // 1. Corrupt Room entity JSON safely returns null instead of crashing cold-start; incomplete downloads never expose cachedFilePath
        val corruptFav = com.lxpure.music.data.local.FavoriteEntity(
            trackId = "bad_1",
            trackJson = "{broken json"
        )
        assertNull(corruptFav.toTrackOrNull())

        val validTrackJson = com.lxpure.music.data.local.roomJson.encodeToString(
            kotlinx.serialization.serializer<Track>(),
            Track(id = "wy_dl_1", title = "Song", artist = "Artist")
        )
        val downloadingEntity = com.lxpure.music.data.local.DownloadEntity(
            trackId = "wy_dl_1",
            trackJson = validTrackJson,
            quality = AudioQuality.FLAC.lxType,
            status = com.lxpure.music.domain.model.DownloadStatus.DOWNLOADING.name,
            progress = 0.5f,
            filePath = "/tmp/partial.flac",
            totalBytes = 1000L,
            errorMessage = null
        )
        assertNull(downloadingEntity.toTrackOrNull()?.cachedFilePath)

        val completedEntity = downloadingEntity.copy(
            status = com.lxpure.music.domain.model.DownloadStatus.COMPLETED.name,
            progress = 1f,
            filePath = "/tmp/done.flac"
        )
        assertEquals("/tmp/done.flac", completedEntity.toTrackOrNull()?.cachedFilePath)

        // 2. DownloadManager MIME rejection, extension inference, and filename sanitization
        assertTrue(com.lxpure.music.core.download.DownloadManager.isDisallowedAudioContentType("text/html; charset=utf-8"))
        assertTrue(com.lxpure.music.core.download.DownloadManager.isDisallowedAudioContentType("application/json"))
        assertFalse(com.lxpure.music.core.download.DownloadManager.isDisallowedAudioContentType("audio/flac"))
        assertFalse(com.lxpure.music.core.download.DownloadManager.isDisallowedAudioContentType("application/octet-stream"))
        assertEquals(
            "m4a",
            com.lxpure.music.core.download.DownloadManager.inferAudioExtension(
                urlPath = "/cdn/stream",
                contentType = "audio/mp4",
                contentDisposition = null,
                quality = AudioQuality.FLAC
            )
        )
        assertEquals(
            "ogg",
            com.lxpure.music.core.download.DownloadManager.inferAudioExtension(
                urlPath = "/cdn/stream",
                contentType = "application/octet-stream",
                contentDisposition = "attachment; filename=\"demo.ogg\"",
                quality = AudioQuality.Q320K
            )
        )
        assertEquals(
            "hidden_song",
            com.lxpure.music.core.download.DownloadManager.sanitizeFileName("...hidden/song\n")
        )

        // 3. AppUpdateManager SHA-256 extraction from release body & SHA256SUMS.txt + mirror gating
        val digestHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val body = """
            ### 校验信息
            - **Android APK**: `lx-pure-v1.1.4-release.apk`
              - SHA-256: `$digestHex`
            - **iOS IPA**: `lx-pure-ios-v1.1.4-unsigned.ipa`
              - SHA-256: `0000000000000000000000000000000000000000000000000000000000000001`
        """.trimIndent()
        assertEquals(digestHex, AppUpdateManager.extractSha256FromReleaseBody(body, "lx-pure-v1.1.4-release.apk"))

        val sums = "$digestHex  lx-pure-v1.1.4-release.apk\n1111111111111111111111111111111111111111111111111111111111111111  lx-pure-ios-v1.1.4-unsigned.ipa"
        assertEquals(digestHex, AppUpdateManager.extractSha256FromSumsText(sums, "lx-pure-v1.1.4-release.apk"))

        assertTrue(AppUpdateManager.isDisallowedUpdateContentType("text/html; charset=utf-8"))
        assertFalse(AppUpdateManager.isDisallowedUpdateContentType("application/vnd.android.package-archive"))

        val unverifiedRoutes = AppUpdateManager.buildDownloadRoutes(
            "https://github.com/4everwrf/lx-pure/releases/download/v1.1.4/lx-pure-v1.1.4-release.apk",
            allowMirrors = false
        )
        assertEquals(1, unverifiedRoutes.size)
        assertEquals("GitHub 直连", unverifiedRoutes.first().label)
    }

    @Test
    fun round5SessionWriteOrderAndProgressIsolationRegression() {
        val tempDir = createTempDir(prefix = "lxpure_r5_session_")
        try {
            val store = com.lxpure.music.data.local.PlaybackSessionStore(tempDir)
            val t1 = Track(id = "wy_r5_1", title = "A", artist = "Artist", durationMs = 200_000L)
            val t2 = Track(id = "wy_r5_2", title = "B", artist = "Artist", durationMs = 180_000L)
            store.saveQueueAndCursor(
                queue = listOf(t1, t2),
                queueSource = null,
                currentIndex = 0,
                positionMs = 10_000L,
                durationMs = 200_000L,
                repeatMode = com.lxpure.music.domain.model.RepeatMode.LIST_LOOP,
                activeQualityBadge = "HQ",
                activeProviderName = "Mock"
            )
            store.saveCursorOnly(
                currentIndex = 1,
                currentTrackId = t2.id,
                positionMs = 95_000L,
                durationMs = 180_000L,
                repeatMode = com.lxpure.music.domain.model.RepeatMode.SINGLE_LOOP,
                activeQualityBadge = "SQ",
                activeProviderName = "Mock"
            )
            val loaded = store.loadSession()
            assertNotNull(loaded)
            assertEquals(1, loaded!!.currentIndex)
            assertEquals(95_000L, loaded.positionMs)
            assertEquals(180_000L, loaded.durationMs)
            assertEquals(com.lxpure.music.domain.model.RepeatMode.SINGLE_LOOP, loaded.repeatMode)

            store.clear()
            assertNull(store.loadSession())

            // Isolated PlaybackProgress holds live progress independently of PlayerState
            val prog = com.lxpure.music.core.player.PlaybackProgress(
                positionMs = 45_000L,
                durationMs = 180_000L,
                sleepTimerRemainingMs = 600_000L
            )
            assertEquals(45_000L, prog.positionMs)
            assertEquals(180_000L, prog.durationMs)
            assertEquals(600_000L, prog.sleepTimerRemainingMs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun artistParserHandlesMultiArtistProtectedBandsFeatAndPlaceholders() {
        val parser = com.lxpure.music.domain.model.ArtistParser

        // Empty / unknown placeholders must produce empty list (non-clickable)
        assertTrue(parser.parseRawString("").isEmpty())
        assertTrue(parser.parseRawString("   \t\u200B  ").isEmpty())
        assertTrue(parser.parseRawString("未知歌手").isEmpty())
        assertTrue(parser.parseRawString("<unknown>").isEmpty())
        assertTrue(parser.parseRawString("Unknown Artist").isEmpty())
        assertTrue(parser.parseRawString(" / / & , ").isEmpty())

        // Standard multi-artist delimiters across WY/TX/KW/KG/Local
        assertEquals(listOf("周杰伦", "费玉清"), parser.parseRawString("周杰伦 / 费玉清"))
        assertEquals(listOf("周杰伦", "温岚", "弹头"), parser.parseRawString("周杰伦、温岚、弹头"))
        assertEquals(listOf("林俊杰", "蔡卓妍"), parser.parseRawString("林俊杰&蔡卓妍"))
        assertEquals(listOf("陈奕迅", "王菲"), parser.parseRawString("陈奕迅；王菲"))
        assertEquals(listOf("Drake", "Future", "Young Thug"), parser.parseRawString("Drake, Future, Young Thug"))
        assertEquals(listOf("周杰伦", "五月天"), parser.parseRawString("周杰伦 × 五月天"))
        assertEquals(listOf("SPY×FAMILY"), parser.parseRawString("SPY×FAMILY"))

        // Protected atomic bands & aliases with dots/hyphens/parentheses
        assertEquals(listOf("AC/DC"), parser.parseRawString("AC/DC"))
        assertEquals(listOf("AC/DC", "Metallica"), parser.parseRawString("AC/DC / Metallica"))
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), parser.parseRawString("Tyler, The Creator / Kali Uchis"))
        assertEquals(listOf("Simon & Garfunkel"), parser.parseRawString("Simon & Garfunkel"))
        assertEquals(listOf("Earth, Wind & Fire", "The Emotions"), parser.parseRawString("Earth, Wind & Fire & The Emotions"))
        assertEquals(listOf("G.E.M. 邓紫棋", "潘玮柏"), parser.parseRawString("G.E.M. 邓紫棋 / 潘玮柏"))
        assertEquals(listOf("S.H.E"), parser.parseRawString("S.H.E"))
        assertEquals(listOf("A-Lin"), parser.parseRawString("A-Lin"))
        assertEquals(listOf("陈奕迅 (Eason Chan)"), parser.parseRawString("陈奕迅 (Eason Chan)"))

        // Featuring annotations must split without breaking Daft Punk / Taylor Swift
        assertEquals(listOf("Daft Punk"), parser.parseRawString("Daft Punk"))
        assertEquals(listOf("Taylor Swift"), parser.parseRawString("Taylor Swift"))
        assertEquals(listOf("Jay Chou", "Lara"), parser.parseRawString("Jay Chou feat. Lara"))
        assertEquals(listOf("Jay Chou", "Lara", "Gary"), parser.parseRawString("Jay Chou (feat. Lara & Gary)"))
        assertEquals(listOf("周杰伦", "温岚"), parser.parseRawString("周杰伦（ft. 温岚）"))

        // Case-insensitive dedup + mixed placeholder filtering + NUL/newline tags
        assertEquals(listOf("周杰伦", "费玉清"), parser.parseRawString("周杰伦 / 未知歌手 / 周杰伦 / 费玉清"))
        assertEquals(listOf("Jay Chou", "Lara"), parser.parseRawString("Jay Chou / jay chou / Lara"))
        assertEquals(listOf("Artist A", "Artist B"), parser.parseRawString("Artist A\u0000Artist B"))

        // Structured artistNames takes precedence (preserves arbitrary slash/ampersand band names)
        val structuredTrack = Track(
            id = "wy_band",
            title = "Test",
            artist = "Custom/Band / Foo & Bar",
            artistNames = listOf("Custom/Band", "Foo & Bar")
        )
        assertEquals(listOf("Custom/Band", "Foo & Bar"), parser.extractArtists(structuredTrack))

        // Legacy persisted JSON without artistNames deserializes safely
        val legacyJson = """{"id":"wy_legacy","title":"千里之外","artist":"周杰伦 / 费玉清","album":"依然范特西"}"""
        val decoded = com.lxpure.music.data.local.roomJson.decodeFromString<Track>(legacyJson)
        assertTrue(decoded.artistNames.isEmpty())
        assertEquals(listOf("周杰伦", "费玉清"), parser.extractArtists(decoded))
    }

    @Test
    fun artistRowLayoutPlannerHandlesSingleMultiOverflowAndExtremeWidths() {
        val planner = com.lxpure.music.domain.model.ArtistRowLayoutPlanner

        // 1. Single short artist + short album: both fit without truncation
        val singleFit = planner.plan(
            artistCount = 1,
            maxWidth = 300f,
            artistWidthAt = { 60f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 70f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(1, singleFit.visibleCount)
        assertEquals(0, singleFit.hiddenCount)
        assertTrue(singleFit.showAlbum)
        assertFalse(singleFit.truncateSingleVisibleArtist)

        // 2. Single short artist + very long album: artist NOT truncated, album shown & ellipsized
        val singleLongAlbum = planner.plan(
            artistCount = 1,
            maxWidth = 300f,
            artistWidthAt = { 60f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 400f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(1, singleLongAlbum.visibleCount)
        assertTrue(singleLongAlbum.showAlbum)
        assertFalse(singleLongAlbum.truncateSingleVisibleArtist)

        // 3. Multi-artist all fitting + album
        val twoFit = planner.plan(
            artistCount = 2,
            maxWidth = 300f,
            artistWidthAt = { 70f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 48f },
            albumFullWidth = 100f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(2, twoFit.visibleCount)
        assertEquals(0, twoFit.hiddenCount)
        assertTrue(twoFit.showAlbum)

        // 4. Huge 70-artist chorus: stops measuring lazily & shows first 2 + "+68" badge
        var measureCalls = 0
        val chorusPlan = planner.plan(
            artistCount = 70,
            maxWidth = 240f,
            artistWidthAt = {
                measureCalls++
                80f
            },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 56f },
            albumFullWidth = 90f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(2, chorusPlan.visibleCount)
        assertEquals(68, chorusPlan.hiddenCount)
        assertFalse(chorusPlan.showAlbum)
        assertFalse(chorusPlan.truncateSingleVisibleArtist)
        assertTrue("Expected lazy measurement (< 10 calls), got $measureCalls", measureCalls <= 4)

        // 5. Multi-artist where artist #1 alone overflows with badge -> truncates #1 & keeps badge visible
        val longFirstPlan = planner.plan(
            artistCount = 3,
            maxWidth = 220f,
            artistWidthAt = { idx -> if (idx == 0) 210f else 60f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 50f },
            albumFullWidth = 0f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(1, longFirstPlan.visibleCount)
        assertEquals(2, longFirstPlan.hiddenCount)
        assertTrue(longFirstPlan.truncateSingleVisibleArtist)
        assertFalse(longFirstPlan.showCompactSummaryOnly)

        // 6. Absurdly narrow viewport -> compact summary pill only
        val tinyPlan = planner.plan(
            artistCount = 5,
            maxWidth = 90f,
            artistWidthAt = { 120f },
            separatorWidth = 6f,
            singleArtistChevronWidth = 20f,
            overflowBadgeWidthForHidden = { 52f },
            albumFullWidth = 0f,
            minAlbumDisplayWidth = 60f,
            minTruncatedArtistWidth = 56f
        )
        assertEquals(0, tinyPlan.visibleCount)
        assertEquals(5, tinyPlan.hiddenCount)
        assertTrue(tinyPlan.showCompactSummaryOnly)
    }

    @Test
    fun parseMeloraAndMultiScriptJsonBundlesWhileRejectingErrorAndMalformedJson() {
        val scriptA = "/**\r\n * @name Alpha Demo\r\n * @version 1.2.0\r\n * @author TesterA\r\n */\r\n" +
            "const { EVENT_NAMES, on, send } = globalThis.lx;\n" +
            "on(EVENT_NAMES.request, async () => 'https://cdn.example.com/a.mp3');\n" +
            "send(EVENT_NAMES.inited, { sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });"
        val scriptB = "globalThis.lx.on('request', async () => 'https://cdn.example.com/b.mp3');\n" +
            "globalThis.lx.send('inited', { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });"

        fun escapeJson(raw: String): String = kotlinx.serialization.json.JsonPrimitive(raw).toString()

        // 1. Melora lx-sources bundle with BOM, valid scripts (header + headerless fallback name), and invalid entries to skip
        val meloraJson = """
            {
              "app": "melora",
              "type": "lx-sources",
              "scripts": [
                { "name": "Alpha_v1.2.0.js", "code": ${escapeJson(scriptA)} },
                { "name": "FallbackSource.js", "code": ${escapeJson(scriptB)} },
                { "name": "BadNumberCode.js", "code": 404 },
                { "name": "HtmlStub.js", "code": "<html><body>404 Not Found</body></html>" },
                { "name": "NonLxText.js", "code": "just plain text without markers" }
              ]
            }
        """.trimIndent()

        val meloraResult = LxScriptParser.parseScriptBundle("\uFEFF$meloraJson")
        assertTrue(meloraResult is com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle)
        val validMelora = meloraResult as com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle
        assertEquals(2, validMelora.entries.size)
        assertEquals(3, validMelora.skippedCount)
        assertEquals("Alpha_v1.2.0", validMelora.entries[0].fallbackName)
        assertFalse(validMelora.entries[0].script.contains("\r"))
        assertEquals("Alpha Demo", LxScriptParser.parse(validMelora.entries[0].script, validMelora.entries[0].fallbackName).name)
        assertEquals("FallbackSource", validMelora.entries[1].fallbackName)
        assertEquals("FallbackSource", LxScriptParser.parse(validMelora.entries[1].script, validMelora.entries[1].fallbackName).name)

        // Payload validator accepts Melora bundle while single-script validator keeps rejecting JSON (guards updateSingleSource)
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(meloraJson))
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(meloraJson))

        // 2. Top-level array & single wrapped object formats
        val arrayBundle = """[{"name":"ArrSrc.js","script":${escapeJson(scriptA)}}]"""
        val arrayRes = LxScriptParser.parseScriptBundle(arrayBundle)
        assertTrue(arrayRes is com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle)
        assertEquals(1, (arrayRes as com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle).entries.size)
        // P0-1 regression: top-level JSON array bundle must NEVER pass single-script validator (guards updateSingleSource)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(arrayBundle))
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(arrayBundle))

        val singleWrapped = """{"name":"Wrapped.js","code":${escapeJson(scriptA)}}"""
        val wrappedRes = LxScriptParser.parseScriptBundle(singleWrapped)
        assertTrue(wrappedRes is com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle)
        assertEquals(1, (wrappedRes as com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle).entries.size)

        // 3. Regular standalone .js script returns NotBundle (existing single-script path preserved)
        assertEquals(com.lxpure.music.core.userapi.ScriptBundleParseResult.NotBundle, LxScriptParser.parseScriptBundle(scriptA))
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(scriptA))
        assertTrue(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(scriptA))

        // 4. Adversarial / dirty JSON & oversized inputs must be rejected cleanly (A4)
        val errorJsonNumeric = """{"code": 404, "message": "not found"}"""
        assertTrue(LxScriptParser.parseScriptBundle(errorJsonNumeric) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(errorJsonNumeric))

        val errorJsonString = """{"code": "ERROR_LX_404", "message": "short string not script"}"""
        assertTrue(LxScriptParser.parseScriptBundle(errorJsonString) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(errorJsonString))

        val shortTagArray = """{"sources":["lx-api"]}"""
        assertTrue(LxScriptParser.parseScriptBundle(shortTagArray) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(shortTagArray))

        val errorArrayLong = """[{"error":"lx rate limit","detail":"${"x".repeat(200)}"}]"""
        assertTrue(LxScriptParser.parseScriptBundle(errorArrayLong) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(errorArrayLong))

        val emptyScriptsJson = """{"app":"melora","scripts":[]}"""
        assertTrue(LxScriptParser.parseScriptBundle(emptyScriptsJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        val allInvalidJson = """{"app":"melora","scripts":[{"name":"bad.js","code":"<html>404</html>"}]}"""
        assertTrue(LxScriptParser.parseScriptBundle(allInvalidJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        val brokenJson = """{"app":"melora","scripts":[{"name":"a","code":"broken"""
        assertTrue(LxScriptParser.parseScriptBundle(brokenJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        val brokenContentJson = """{"app":"melora","content":"globalThis.lx broken"""
        assertTrue(LxScriptParser.parseScriptBundle(brokenContentJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        val nulJson = "{\"app\":\"melora\",\"scripts\":[{\"name\":\"a\",\"code\":\"globalThis.lx\u0000\"}]}"
        assertTrue(LxScriptParser.parseScriptBundle(nulJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        // JSON-escaped \u0000 inside script code is decoded by parser and rejected by entry validator
        val escapedNulJson = """{"app":"melora","scripts":[{"name":"a.js","code":"globalThis.lx.send('inited', {});\u0000padding"}]}"""
        assertTrue(LxScriptParser.parseScriptBundle(escapedNulJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        // Single-script download validator rejects NUL bytes and > 1 MB scripts (guards updateSingleSource)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(scriptA + "\u0000"))
        assertFalse(
            com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedScript(
                scriptA + "//" + "a".repeat(LxScriptParser.MAX_SINGLE_SCRIPT_BYTES)
            )
        )

        // > 1 MB entry inside bundle is skipped if valid siblings exist, and rejected if alone
        val oversizedEntry = scriptA + "//" + "a".repeat(LxScriptParser.MAX_SINGLE_SCRIPT_BYTES)
        val mixedOversizedBundle = """{"scripts":[{"name":"Huge.js","code":${escapeJson(oversizedEntry)}},{"name":"Ok.js","code":${escapeJson(scriptB)}}]}"""
        val mixedOversizedRes = LxScriptParser.parseScriptBundle(mixedOversizedBundle)
        assertTrue(mixedOversizedRes is com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle)
        assertEquals(1, (mixedOversizedRes as com.lxpure.music.core.userapi.ScriptBundleParseResult.ValidBundle).entries.size)
        assertEquals(1, mixedOversizedRes.skippedCount)

        val onlyOversizedBundle = """{"scripts":[{"name":"Huge.js","code":${escapeJson(oversizedEntry)}}]}"""
        assertTrue(LxScriptParser.parseScriptBundle(onlyOversizedBundle) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)

        // > 4 MB bundle payload rejected before JSON parsing
        val hugePayload = "{\"scripts\":[\"" + "a".repeat(LxScriptParser.MAX_BUNDLE_PAYLOAD_BYTES + 16) + "\"]}"
        assertTrue(LxScriptParser.parseScriptBundle(hugePayload) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(hugePayload))

        val tooManyItems = (1..51).joinToString(",") { idx ->
            """{"name":"S$idx.js","code":${escapeJson(scriptB)}}"""
        }
        val fiftyOneObjectJson = """{"scripts":[$tooManyItems]}"""
        val fiftyOneArrayJson = """[$tooManyItems]"""
        assertTrue(LxScriptParser.parseScriptBundle(fiftyOneObjectJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertTrue(LxScriptParser.parseScriptBundle(fiftyOneArrayJson) is com.lxpure.music.core.userapi.ScriptBundleParseResult.InvalidBundle)
        assertFalse(com.lxpure.music.core.userapi.UserApiManager.isValidDownloadedPayload(fiftyOneArrayJson))

        // 5. SourceImportBatchResult message formatting parity
        val dummySource = com.lxpure.music.domain.model.UserApiSource(
            id = "s1", name = "Alpha Demo", description = "", version = "1.0.0",
            author = "Tester", homepage = "", script = scriptA, sourceUrl = null, enabled = true, priority = 0
        )
        assertEquals(
            "未导入任何有效音源",
            com.lxpure.music.core.userapi.SourceImportBatchResult(emptyList(), 0).formatUserMessage()
        )
        assertEquals(
            "已导入音源：Alpha Demo",
            com.lxpure.music.core.userapi.SourceImportBatchResult(listOf(dummySource), 0).formatUserMessage()
        )
        assertEquals(
            "已导入 2 个音源",
            com.lxpure.music.core.userapi.SourceImportBatchResult(listOf(dummySource, dummySource.copy(id = "s2")), 0).formatUserMessage()
        )
        assertEquals(
            "已导入 1 个音源（跳过 2 个无效项）",
            com.lxpure.music.core.userapi.SourceImportBatchResult(listOf(dummySource), 2).formatUserMessage()
        )
    }
}
