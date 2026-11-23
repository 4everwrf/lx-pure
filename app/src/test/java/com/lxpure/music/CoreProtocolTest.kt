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
}
