package com.lxpure.music

import com.lxpure.music.core.musicsdk.LrcParser
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.musicsdk.QishuiProvider
import com.lxpure.music.core.musicsdk.asJsonObjectOrNull
import com.lxpure.music.core.player.AutoSkipDecision
import com.lxpure.music.core.player.PlaybackRecoveryPolicy
import com.lxpure.music.core.player.QueueNavigator
import com.lxpure.music.core.userapi.SourceRaceCoordinator
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.RepeatMode
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserApiSource
import com.lxpure.music.ui.discover.DiscoverSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class QishuiDiscoverAndFallbackTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun readBody(request: Request): String {
        val body = request.body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    // =========================================================================
    // 1. QishuiProvider: Cover URL construction, dirty JSON, mix, charts, detail
    // =========================================================================

    @Test
    fun qishuiCoverUrlBuilderAppendsResizeSuffixPercentEncodesAndRejectsBareImg() {
        val standardObj = json.parseToJsonElement(
            """
            {
              "uri": "tos-cn-i-b829550vbb/e586145eb19046f9b3941a4cb05fc596",
              "urls": ["https://p3-luna.douyinpic.com/img/"],
              "template_prefix": "tplv-custom-prefix"
            }
            """.trimIndent()
        ).asJsonObjectOrNull()
        assertEquals(
            "https://p3-luna.douyinpic.com/img/tos-cn-i-b829550vbb/e586145eb19046f9b3941a4cb05fc596~tplv-custom-prefix-resize:500:500.png",
            QishuiProvider.buildCoverUrl(standardObj)
        )

        // Missing template_prefix falls back to tplv-b829550vbb, non-ASCII uri percent-encoded
        val nonAsciiObj = json.parseToJsonElement(
            """
            {
              "uri": "tos-cn-v/中文 封面",
              "urls": ["http://p6-luna.douyinpic.com/img/"]
            }
            """.trimIndent()
        ).asJsonObjectOrNull()
        assertEquals(
            "https://p6-luna.douyinpic.com/img/tos-cn-v/%E4%B8%AD%E6%96%87%20%E5%B0%81%E9%9D%A2~tplv-b829550vbb-resize:500:500.png",
            QishuiProvider.buildCoverUrl(nonAsciiObj)
        )

        // Bare /img/{uri} in urls without uri field still gets resize suffix (never bare /img/{uri})
        val bareImgObj = json.parseToJsonElement(
            """
            {
              "urls": ["https://p3-luna.douyinpic.com/img/tos-cn-v/abc123"]
            }
            """.trimIndent()
        ).asJsonObjectOrNull()
        assertEquals(
            "https://p3-luna.douyinpic.com/img/tos-cn-v/abc123~tplv-b829550vbb-resize:500:500.png",
            QishuiProvider.buildCoverUrl(bareImgObj)
        )

        // Bare /img/ host without uri must return null rather than emitting an invalid HTTP 400 URL
        val hostOnlyObj = json.parseToJsonElement(
            """
            {
              "uri": null,
              "urls": ["https://p3-luna.douyinpic.com/img/"]
            }
            """.trimIndent()
        ).asJsonObjectOrNull()
        assertNull(QishuiProvider.buildCoverUrl(hostOnlyObj))
        assertNull(QishuiProvider.buildCoverUrl(null))
    }

    @Test
    fun qishuiHotPlaylistsQueriesChannelsConcurrentlyDedupsFiltersAndFallsBack() = runBlocking {
        val requestedEndpoints = CopyOnWriteArrayList<String>()
        val requestedBodies = CopyOnWriteArrayList<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val body = readBody(req)
                requestedEndpoints.add(url)
                requestedBodies.add(body)

                if (url.contains("/luna/pc/discover/mix") && body.contains("\"sub_channel_id\":\"14\"")) {
                    // Force channel 14 to fall back to beta-luna.douyin.com
                    return@Interceptor jsonResponse(req, 502, "Bad Gateway")
                }

                val mixJson = if (body.contains("\"sub_channel_id\":\"0\"")) {
                    """
                    {
                      "inner_block": [
                        {
                          "resources": [
                            {
                              "entity": {
                                "playlist": {
                                  "id": "p_100",
                                  "title": "汽水热歌精选",
                                  "count_tracks": 12,
                                  "owner": {"nickname": "编辑部"},
                                  "stats": {"count_collected": 25000},
                                  "url_cover": {
                                    "uri": "tos-cn/cover100",
                                    "urls": ["https://p3-luna.douyinpic.com/img/"],
                                    "template_prefix": "tplv-b829550vbb"
                                  }
                                }
                              }
                            },
                            {
                              "entity": {
                                "playlist": {
                                  "id": "p_empty",
                                  "title": "空歌单",
                                  "count_tracks": 0,
                                  "owner": null,
                                  "stats": null
                                }
                              }
                            },
                            null
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "inner_block": [
                        {
                          "resources": [
                            {
                              "entity": {
                                "playlist": {
                                  "id": "p_100",
                                  "title": "重复歌单应去重",
                                  "resource_cnt": {"track_cnt": 12}
                                }
                              }
                            },
                            {
                              "entity": {
                                "playlist": {
                                  "id": "p_200",
                                  "public_title": "汽水民谣夜",
                                  "desc": null,
                                  "resource_cnt": {"track_cnt": 18},
                                  "owner": {"public_name": "夜行人"},
                                  "stats": {"count_played": 120000}
                                }
                              }
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                }
                jsonResponse(req, 200, mixJson)
            })
            .build()

        val playlists = MusicAggregator(client).getHotPlaylists(MusicSource.SODA)
        assertEquals(listOf("p_100", "p_200"), playlists.map { it.id })
        assertEquals(MusicSource.SODA, playlists[0].source)
        assertEquals("汽水热歌精选", playlists[0].name)
        assertEquals("2.5万", playlists[0].playCount)
        assertTrue(playlists[0].coverUrl.endsWith("~tplv-b829550vbb-resize:500:500.png"))
        assertEquals("汽水民谣夜", playlists[1].name)
        assertEquals("夜行人", playlists[1].author)
        assertEquals("12.0万", playlists[1].playCount)
        assertTrue(requestedEndpoints.any { it.contains("https://beta-luna.douyin.com/luna/discover/mix") })
        assertTrue(requestedBodies.any { it.contains("\"sub_channel_id\":\"0\"") })
        assertTrue(requestedBodies.any { it.contains("\"sub_channel_id\":\"14\"") })
    }

    @Test
    fun qishuiPlaylistDetailPaginatesGuardsCursorCyclesAndSurvivesDirtyJson() = runBlocking {
        val cursorsSeen = CopyOnWriteArrayList<String>()
        val pageCalls = AtomicInteger(0)

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val cursor = req.url.queryParameter("cursor").orEmpty()
                cursorsSeen.add(cursor)
                val callNum = pageCalls.incrementAndGet()

                // Page 1 GET returns empty/502 to exercise POST fallback on page 1
                if (callNum == 1 && req.method == "GET") {
                    return@Interceptor jsonResponse(req, 502, "Bad Gateway")
                }

                val payload = if (req.method == "POST") {
                    """
                    {
                      "has_more": true,
                      "next_cursor": "100",
                      "playlist": {
                        "id": "7095679776978896903",
                        "title": "汽水测试歌单",
                        "owner": {"nickname": "汽水策划"},
                        "url_cover": {"uri": "tos-cn/pl", "urls": ["https://p3-luna.douyinpic.com/img/"]}
                      },
                      "media_resources": [
                        {
                          "entity": {
                            "track_wrapper": {
                              "track": {
                                "id": "7001",
                                "name": "第一首",
                                "duration": 185000,
                                "artists": [null, {"name": "歌手甲"}, {"simple_display_name": "歌手乙"}],
                                "album": {"id": "a1", "name": "专辑A", "url_cover": {"uri": "tos-cn/t1"}},
                                "bit_rates": [
                                  {"quality": "medium", "size": 1500000},
                                  {"quality": "highest", "size": 4500000},
                                  {"quality": "lossless", "size": 25000000}
                                ]
                              }
                            }
                          }
                        },
                        null
                      ]
                    }
                    """.trimIndent()
                } else if (cursor == "100") {
                    // Return same next_cursor="100" to verify cycle guard stops pagination cleanly
                    """
                    {
                      "has_more": true,
                      "next_cursor": "100",
                      "media_resources": [
                        {
                          "entity": {
                            "track": {
                              "id": "7002",
                              "name": "第二首",
                              "duration": 210000,
                              "artists": [],
                              "album": null,
                              "bit_rates": null
                            }
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    """{"has_more": false, "media_resources": []}"""
                }
                jsonResponse(req, 200, payload)
            })
            .build()

        val result = MusicAggregator(client).getPlaylistDetail(MusicSource.SODA, "7095679776978896903")
        assertTrue("Expected success, got ${result.exceptionOrNull()}", result.isSuccess)
        val detail = result.getOrThrow()
        assertEquals("汽水测试歌单", detail.playlist.name)
        assertEquals(MusicSource.SODA, detail.playlist.source)
        assertEquals(2, detail.tracks.size)
        assertEquals(listOf("soda_7001", "soda_7002"), detail.tracks.map { it.id })
        assertEquals("歌手甲 / 歌手乙", detail.tracks[0].artist)
        assertEquals(listOf("歌手甲", "歌手乙"), detail.tracks[0].artistNames)
        assertEquals(listOf("128k", "320k", "flac"), detail.tracks[0].qualities.map { it.type })
        assertEquals("https://p3-luna.douyinpic.com/img/tos-cn/t1~tplv-b829550vbb-resize:500:500.png", detail.tracks[0].coverUrl)
        assertEquals("未知歌手", detail.tracks[1].artist)
        assertEquals("", cursorsSeen.first())
    }

    @Test
    fun qishuiChartsExposeFourBoardsFallbackAndPropagateCancellation() = runBlocking {
        val aggregator = MusicAggregator(OkHttpClient())
        val boards = aggregator.getLeaderboards(MusicSource.SODA)
        assertEquals(4, boards.size)
        assertEquals(
            listOf("7036274230471712007", "7060812597884869927", "7061475546400005410", "7415959718721494311"),
            boards.map { it.bangId }
        )
        assertTrue(boards.all { it.source == MusicSource.SODA })

        // Verify primary -> fallback api5-lf.qishui.com chart parsing
        val requestedUrls = CopyOnWriteArrayList<String>()
        val fallbackClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                if (url.contains("api.qishui.com/luna/pc/charts/")) {
                    jsonResponse(req, 502, "Bad Gateway")
                } else {
                    jsonResponse(
                        req,
                        200,
                        """
                        {
                          "chart": {
                            "id": "7036274230471712007",
                            "title": "汽水热歌榜",
                            "period": "第47周",
                            "track_ranks": [
                              {
                                "track": {
                                  "id": "8801",
                                  "name": "热歌榜首",
                                  "duration": 190000,
                                  "artists": [{"name": "热门歌手"}],
                                  "album": {"id": "al1", "name": "热专", "url_cover": {"uri": "tos-cn/chart1"}}
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                }
            })
            .build()

        val chartRes = MusicAggregator(fallbackClient).getLeaderboardDetail(boards.first())
        assertTrue(chartRes.isSuccess)
        assertEquals("soda_8801", chartRes.getOrThrow().tracks.single().id)
        assertTrue(requestedUrls.any { it.contains("https://api5-lf.qishui.com/luna/charts/7036274230471712007?charge=0") })

        // Verify CancellationException propagates immediately without calling fallback
        val enteredPrimary = CompletableDeferred<Unit>()
        val cancelUrls = CopyOnWriteArrayList<String>()
        val sawCancel = CompletableDeferred<Boolean>()
        val cancelClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                cancelUrls.add(req.url.toString())
                enteredPrimary.complete(Unit)
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    throw java.io.IOException("Canceled")
                }
                jsonResponse(req, 502, "Should not complete")
            })
            .build()

        val provider = QishuiProvider(cancelClient)
        val job = launch(Dispatchers.Default) {
            try {
                provider.getLeaderboardDetail(boards.first())
                sawCancel.complete(false)
            } catch (_: CancellationException) {
                sawCancel.complete(true)
            }
        }
        withTimeout(5_000L) {
            enteredPrimary.await()
            job.cancelAndJoin()
            assertTrue("CancellationException must propagate", sawCancel.await())
        }
        assertEquals(1, cancelUrls.size)
        assertTrue(cancelUrls.single().contains("api.qishui.com/luna/pc/charts/"))
    }

    // =========================================================================
    // 2. DiscoverSessionState with SODA & Search isolation verification
    // =========================================================================

    @Test
    fun discoverStateCachesSodaAcrossBackNavAndGuardsStaleResponses() {
        val aggregator = MusicAggregator(OkHttpClient())
        val wyPlaylists = listOf(OnlinePlaylist("wy_1", "网易歌单", "", "a", "1万", "", 10, MusicSource.WY))
        val sodaPlaylists = listOf(OnlinePlaylist("soda_1", "汽水歌单", "", "b", "5万", "", 15, MusicSource.SODA))
        val sodaRefreshed = listOf(OnlinePlaylist("soda_2", "汽水新歌单", "", "c", "8万", "", 20, MusicSource.SODA))

        var state = DiscoverSessionState(selectedSource = MusicSource.WY)
        state = state.commitFetchedPlaylists(MusicSource.WY, state.generation, wyPlaylists)

        // Switch to SODA and start async fetch
        state = state.selectSource(MusicSource.SODA)
        val sodaReqGen = state.generation
        assertEquals(MusicSource.SODA, state.activeSource)
        assertTrue(state.shouldFetchForCurrentState())

        // Stale WY commit arriving after switching to SODA must be ignored
        state = state.commitFetchedPlaylists(MusicSource.WY, 0L, wyPlaylists)
        assertEquals(MusicSource.SODA, state.activeSource)
        assertTrue(state.currentPlaylists.isEmpty())

        // Commit valid SODA playlists, simulate back-nav retention
        state = state.commitFetchedPlaylists(MusicSource.SODA, sodaReqGen, sodaPlaylists)
        assertFalse(state.shouldFetchForCurrentState())
        assertEquals(sodaPlaylists, state.currentPlaylists)
        assertTrue(aggregator.getLeaderboards(state.activeSource).all { it.source == MusicSource.SODA })

        // Refresh on SODA bumps generation and ignores stale response
        state = state.requestRefresh()
        val refreshGen = state.generation
        assertTrue(state.shouldFetchForCurrentState())
        state = state.commitFetchedPlaylists(MusicSource.SODA, sodaReqGen, sodaPlaylists)
        assertTrue(state.shouldFetchForCurrentState())

        state = state.commitFetchedPlaylists(MusicSource.SODA, refreshGen, sodaRefreshed)
        assertFalse(state.shouldFetchForCurrentState())
        assertEquals(sodaRefreshed, state.currentPlaylists)
    }

    @Test
    fun searchScopeAndAggregatorStrictlyExcludeSoda() = runBlocking {
        // 1. SearchScreen SearchScope enum must NOT include SODA
        val scopeClass = Class.forName("com.lxpure.music.ui.search.SearchScope")
        val scopeNames = scopeClass.enumConstants?.map { (it as Enum<*>).name }.orEmpty()
        assertEquals(listOf("ALL", "WY", "TX", "KW", "KG"), scopeNames)

        // 2. MusicAggregator.searchSinglePlatform(SODA) returns empty and hits 0 network endpoints
        val hitUrls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                hitUrls.add(req.url.toString())
                jsonResponse(req, 200, "{}")
            })
            .build()
        val aggregator = MusicAggregator(client)
        assertTrue(aggregator.searchSinglePlatform(MusicSource.SODA, "晴天").isEmpty())
        assertTrue(hitUrls.isEmpty())

        // 3. Aggregated search & findAlternativeTracks never query qishui / douyin endpoints
        aggregator.searchAggregated("晴天")
        assertTrue(hitUrls.none { it.contains("qishui") || it.contains("douyin") })
        hitUrls.clear()

        val sodaTrack = Track(
            id = "soda_1",
            title = "晴天",
            artist = "周杰伦",
            source = MusicSource.SODA,
            songmid = "1",
            durationMs = 269_000L
        )
        aggregator.findAlternativeTracks(sodaTrack, setOf("wy", "tx", "kw", "kg", "soda"))
        assertTrue(hitUrls.none { it.contains("qishui") || it.contains("douyin") })
    }

    // =========================================================================
    // 3. LrcParser Qishui <start,dur,0> word-timing tag stripping
    // =========================================================================

    @Test
    fun lrcParserStripsQishuiWordTimingTagsWhilePreservingTextAndRegularTags() {
        val qishuiLrc = """
            [45410,5900]<0,510,0>Cold <510,340,0>rain <850,850,0>fell
            [01:02.50]<0,200,0>Rock &lt;Live>
        """.trimIndent()

        val parsed = LrcParser.parse(qishuiLrc)
        assertEquals(2, parsed.lines.size)
        assertEquals(45_410L, parsed.lines[0].timeMs)
        assertEquals("Cold rain fell", parsed.lines[0].text)
        assertEquals(62_500L, parsed.lines[1].timeMs)
        assertEquals("Rock <Live>", parsed.lines[1].text)
    }

    // =========================================================================
    // 4. Playback cross-platform fallback & unsupported-source error path + preload
    // =========================================================================

    @Test
    fun qishuiPlaybackFallbackAndUnsupportedSourcePolicy() = runBlocking {
        val sodaTrack = Track(
            id = "soda_999",
            title = "夜曲",
            artist = "周杰伦",
            source = MusicSource.SODA,
            songmid = "999",
            durationMs = 226_000L
        )
        val wyOnlySource = UserApiSource(
            id = "src_wy",
            name = "OnlyWY",
            description = "",
            version = "1.0.0",
            author = "dev",
            homepage = "",
            script = "/* wy */",
            sourceUrl = null,
            enabled = true,
            priority = 0,
            status = UserApiRuntimeStatus.READY,
            supportedPlatforms = mapOf("wy" to listOf("128k", "320k", "flac"))
        )

        // (a) No ready source supports SODA
        assertFalse(UserApiManager.hasReadySourceSupportingPlatform(listOf(wyOnlySource), MusicSource.SODA))

        // With allowCrossPlatformFallback = true: findAlternativeTracks matches WY track and resolves stream
        val wyCandidate = Track(
            id = "wy_222",
            title = "夜曲",
            artist = "周杰伦",
            source = MusicSource.WY,
            songmid = "222",
            durationMs = 226_000L
        )
        val mockWySearchClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("music.163.com")) {
                    jsonResponse(
                        req,
                        200,
                        """
                        {
                          "code": 200,
                          "data": {
                            "resources": [
                              {
                                "baseInfo": {
                                  "simpleSongData": {
                                    "id": 222,
                                    "name": "夜曲",
                                    "ar": [{"name": "周杰伦"}],
                                    "al": {"id": 10, "name": "十一月的萧邦", "picUrl": "https://p1.music.126.net/a.jpg"},
                                    "dt": 226000,
                                    "privilege": {"maxbr": 999000}
                                  }
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                } else {
                    jsonResponse(req, 404, "{}")
                }
            })
            .build()

        val alts = MusicAggregator(mockWySearchClient).findAlternativeTracks(
            sodaTrack,
            setOf("wy", "tx", "kw", "kg")
        )
        assertEquals(listOf("wy_222"), alts.map { it.id })

        val raceCoordinator = SourceRaceCoordinator(
            isMeteredNetwork = { false },
            isInCooldown = { false },
            markCooldown = { _, _ -> },
            clearCooldown = { },
            fetchStreamUrl = { cand, _ ->
                if (cand.track.source == MusicSource.WY) {
                    Result.success("https://cdn.example.com/wy_222.flac")
                } else {
                    Result.failure(IllegalStateException("unsupported"))
                }
            },
            verifyStreamUrl = { true }
        )
        val resolved = raceCoordinator.tryResolveTracks(
            tracks = alts,
            preferredQuality = AudioQuality.FLAC,
            sources = listOf(wyOnlySource),
            switchedPlatform = true,
            preferredSourceId = null,
            prunedSourcePlatforms = ConcurrentHashMap.newKeySet(),
            prunedSourceTracks = ConcurrentHashMap.newKeySet(),
            trackMissCounts = ConcurrentHashMap(),
            includeCooling = false,
            deadlineMs = System.currentTimeMillis() + 5_000L
        )
        assertNotNull(resolved)
        assertTrue(resolved!!.switchedPlatform)
        assertEquals(wyCandidate.id, resolved.resolvedTrack.id)

        // When fallback is ON but no match exists -> clear SODA unsupported message
        val noMatchErr = UserApiManager.buildUnsupportedPlatformFailure(sodaTrack, allowCrossPlatformFallback = true)
        assertTrue(noMatchErr.allowCrossPlatformFallback)
        assertEquals(
            "当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《夜曲》",
            noMatchErr.message
        )
        val nav = QueueNavigator(Random(1))
        val singleTrackStop = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = sodaTrack.title,
            isLocalTrack = false,
            hasReadySource = true,
            isNetworkConnected = true,
            preserveExclusions = false,
            allowAutoSkip = true,
            queueSize = 1,
            currentIndex = 0,
            mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(),
            queueNavigator = nav,
            fallbackErrorMessage = noMatchErr.message,
            preferFallbackErrorOnStop = true
        )
        assertEquals(
            AutoSkipDecision.Stop("当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《夜曲》", promptImportSource = false),
            singleTrackStop
        )

        // (b) When allowCrossPlatformFallback = false and no source supports SODA:
        // returns clear guidance and disables silent playlist auto-skip
        val fallbackOffErr = UserApiManager.buildUnsupportedPlatformFailure(sodaTrack, allowCrossPlatformFallback = false)
        assertFalse(fallbackOffErr.allowCrossPlatformFallback)
        assertEquals(
            "当前自定义音源不支持汽水音乐，请在设置开启「无感自动换源兜底」或导入支持汽水音乐的音源",
            fallbackOffErr.message
        )
        val playlistStopNoSkip = PlaybackRecoveryPolicy.decideAutoSkipOnFailure(
            trackTitle = sodaTrack.title,
            isLocalTrack = false,
            hasReadySource = true,
            isNetworkConnected = true,
            preserveExclusions = false,
            allowAutoSkip = false,
            queueSize = 20,
            currentIndex = 0,
            mode = RepeatMode.LIST_LOOP,
            autoSkippedIndices = emptySet(),
            queueNavigator = nav,
            fallbackErrorMessage = fallbackOffErr.message,
            preferFallbackErrorOnStop = true
        )
        assertEquals(
            AutoSkipDecision.Stop(
                "当前自定义音源不支持汽水音乐，请在设置开启「无感自动换源兜底」或导入支持汽水音乐的音源",
                promptImportSource = false
            ),
            playlistStopNoSkip
        )

        // Verify user-api-preload.js registers 'soda' cleanly alongside existing sources
        val preloadFile = listOf(
            File("app/src/main/assets/script/user-api-preload.js"),
            File("src/main/assets/script/user-api-preload.js")
        ).firstOrNull { it.exists() }
        assertNotNull("user-api-preload.js should exist in module or root path", preloadFile)
        val preloadText = preloadFile!!.readText()
        assertTrue(preloadText.contains("'soda'"))
        assertTrue(preloadText.contains("soda: ['128k', '320k', 'flac', 'flac24bit']"))
        assertTrue(preloadText.contains("soda: ['musicUrl']"))
    }

    @Test
    fun qishuiResolveStreamDistinguishesZeroMatchFromMatchedButFailedAlternative() = runBlocking {
        val sodaTrack = Track(
            id = "soda_999",
            title = "夜曲",
            artist = "周杰伦",
            source = MusicSource.SODA,
            songmid = "999",
            durationMs = 226_000L
        )
        val wyOnlySource = UserApiSource(
            id = "src_wy",
            name = "OnlyWY",
            description = "",
            version = "1.0.0",
            author = "dev",
            homepage = "",
            script = "/* wy */",
            sourceUrl = null,
            enabled = true,
            priority = 0,
            status = UserApiRuntimeStatus.READY,
            supportedPlatforms = mapOf("wy" to listOf("128k", "320k", "flac"))
        )

        // Case 1: allowCrossPlatformFallback = true and findAlternativeTracks matches WY track,
        // but custom-source resolution fails for that WY track -> must fail with "所有音源与平台均无法获取..."
        val mockWyMatchedClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (url.contains("music.163.com")) {
                    jsonResponse(
                        req,
                        200,
                        """
                        {
                          "code": 200,
                          "data": {
                            "resources": [
                              {
                                "baseInfo": {
                                  "simpleSongData": {
                                    "id": 222,
                                    "name": "夜曲",
                                    "ar": [{"name": "周杰伦"}],
                                    "al": {"id": 10, "name": "十一月的萧邦", "picUrl": "https://p1.music.126.net/a.jpg"},
                                    "dt": 226000,
                                    "privilege": {"maxbr": 999000}
                                  }
                                }
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                } else {
                    jsonResponse(req, 404, "{}")
                }
            })
            .build()

        val attemptedTrackIds = CopyOnWriteArrayList<String>()
        val managerWithMatch = UserApiManager.createForTesting(
            httpClient = mockWyMatchedClient,
            aggregator = MusicAggregator(mockWyMatchedClient),
            sources = listOf(wyOnlySource),
            fetchStreamUrl = { cand, _ ->
                attemptedTrackIds.add(cand.track.id)
                Result.failure(IllegalStateException("该歌曲暂无版权"))
            }
        )

        val matchedFailRes = managerWithMatch.resolveStream(
            track = sodaTrack,
            preferredQuality = AudioQuality.FLAC,
            allowCrossPlatformFallback = true
        )
        assertTrue(matchedFailRes.isFailure)
        assertTrue(attemptedTrackIds.contains("wy_222"))
        assertFalse(matchedFailRes.exceptionOrNull() is UserApiManager.UnsupportedSourcePlatformException)
        assertEquals(
            "所有音源与平台均无法获取《夜曲》的可用音频流",
            matchedFailRes.exceptionOrNull()?.message
        )

        // Case 2: allowCrossPlatformFallback = true and 0 cross-platform matches ->
        // must fail with UnsupportedSourcePlatformException ("当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到...")
        val mockEmptySearchClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                jsonResponse(req, 200, """{"code": 200, "data": {"resources": []}}""")
            })
            .build()
        val managerZeroMatch = UserApiManager.createForTesting(
            httpClient = mockEmptySearchClient,
            aggregator = MusicAggregator(mockEmptySearchClient),
            sources = listOf(wyOnlySource),
            fetchStreamUrl = { _, _ -> Result.failure(IllegalStateException("unexpected call")) }
        )

        val zeroMatchRes = managerZeroMatch.resolveStream(
            track = sodaTrack,
            preferredQuality = AudioQuality.FLAC,
            allowCrossPlatformFallback = true
        )
        assertTrue(zeroMatchRes.isFailure)
        val zeroMatchErr = zeroMatchRes.exceptionOrNull() as? UserApiManager.UnsupportedSourcePlatformException
        assertNotNull(zeroMatchErr)
        assertTrue(zeroMatchErr!!.allowCrossPlatformFallback)
        assertEquals(
            "当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《夜曲》",
            zeroMatchErr.message
        )

        // Case 3: allowCrossPlatformFallback = false -> fast-fails with guidance message
        val disabledFallbackRes = managerWithMatch.resolveStream(
            track = sodaTrack,
            preferredQuality = AudioQuality.FLAC,
            allowCrossPlatformFallback = false
        )
        assertTrue(disabledFallbackRes.isFailure)
        val disabledErr = disabledFallbackRes.exceptionOrNull() as? UserApiManager.UnsupportedSourcePlatformException
        assertNotNull(disabledErr)
        assertFalse(disabledErr!!.allowCrossPlatformFallback)
        assertEquals(
            "当前自定义音源不支持汽水音乐，请在设置开启「无感自动换源兜底」或导入支持汽水音乐的音源",
            disabledErr.message
        )
    }
}
