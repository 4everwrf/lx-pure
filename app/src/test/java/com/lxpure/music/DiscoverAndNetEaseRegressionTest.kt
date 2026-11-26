package com.lxpure.music

import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.musicsdk.NetEaseProvider
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.ui.discover.DISCOVER_SUPPORTED_SOURCES
import com.lxpure.music.ui.discover.DiscoverSessionState
import com.lxpure.music.ui.discover.normalizeDiscoverSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class DiscoverAndNetEaseRegressionTest {

    private fun jsonResponse(request: okhttp3.Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun samplePlaylistJson(
        playlistId: String = "19723756",
        name: String = "飙升榜",
        tracksJson: String = """
            [
              {
                "id": 101,
                "name": "测试曲目一",
                "ar": [{"name": "歌手甲"}],
                "al": {"id": 501, "name": "专辑一", "picUrl": "http://p1.music.126.net/a.jpg"},
                "dt": 210000,
                "l": {"size": 3200000},
                "h": {"size": 8400000},
                "sq": {"size": 25000000},
                "hr": {"size": 45000000}
              }
            ]
        """.trimIndent(),
        trackIdsJson: String = """[{"id": 101}]""",
        privilegesJson: String = """[{"id": 101, "maxbr": 999000, "maxBrLevel": "hires"}]"""
    ): String = """
        {
          "code": 200,
          "playlist": {
            "id": $playlistId,
            "name": "$name",
            "coverImgUrl": "http://p2.music.126.net/cover.jpg",
            "creator": {"nickname": "网易云音乐"},
            "playCount": 12500000,
            "description": "官方榜单",
            "trackCount": 1,
            "tracks": $tracksJson,
            "trackIds": $trackIdsJson
          },
          "privileges": $privilegesJson
        }
    """.trimIndent()

    // =========================================================================
    // R1: NetEaseProvider eapi/v6 primary, eapi/v3 fallback, song/detail fallback
    // =========================================================================

    @Test
    fun netEaseLeaderboardDetailUsesEapiV6AndNeverLinuxForward() = runBlocking {
        val requestedUrls = CopyOnWriteArrayList<String>()
        val hasEapiParams = CopyOnWriteArrayList<Boolean>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                requestedUrls.add(req.url.toString())
                val form = req.body as? FormBody
                val hasParams = form != null && (0 until form.size).any { form.name(it) == "params" && form.value(it).isNotBlank() }
                hasEapiParams.add(hasParams)
                jsonResponse(req, 200, samplePlaylistJson())
            })
            .build()

        val aggregator = MusicAggregator(client)
        val board = aggregator.getLeaderboards(MusicSource.WY).first()
        val result = aggregator.getLeaderboardDetail(board)

        assertTrue("Expected leaderboard detail success, got: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(requestedUrls.none { it.contains("/api/linux/forward") })
        assertEquals(listOf("https://interface.music.163.com/eapi/v6/playlist/detail"), requestedUrls)
        assertEquals(listOf(true), hasEapiParams)

        val detail = result.getOrThrow()
        assertEquals("飙升榜", detail.playlist.name)
        assertEquals("https://p2.music.126.net/cover.jpg", detail.playlist.coverUrl)
        assertEquals("网易云音乐", detail.playlist.author)
        assertEquals(1, detail.tracks.size)
        assertEquals("wy_101", detail.tracks[0].id)
        assertEquals("https://p1.music.126.net/a.jpg", detail.tracks[0].coverUrl)
        assertTrue(detail.tracks[0].qualities.any { it.type == "flac24bit" })
    }

    @Test
    fun netEasePlaylistDetailFallsBackToEapiV3OnHttp502NonJsonAndNon200Code() = runBlocking {
        val failureModes = listOf(
            "http502" to { req: okhttp3.Request -> jsonResponse(req, 502, "Bad Gateway") },
            "non200" to { req: okhttp3.Request -> jsonResponse(req, 200, """{"code": 500, "msg": "server error"}""") },
            "nonJson" to { req: okhttp3.Request -> jsonResponse(req, 200, "<html>502 Bad Gateway</html>") }
        )

        for ((label, primaryResponder) in failureModes) {
            val requestedUrls = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    val url = req.url.toString()
                    requestedUrls.add(url)
                    when {
                        url.endsWith("/eapi/v6/playlist/detail") -> primaryResponder(req)
                        url.endsWith("/eapi/v3/playlist/detail") -> jsonResponse(req, 200, samplePlaylistJson(name = "Fallback-$label"))
                        else -> jsonResponse(req, 404, "unexpected url $url")
                    }
                })
                .build()

            val provider = NetEaseProvider(client)
            val result = provider.getPlaylistDetail("19723756")

            assertTrue("Mode $label should fall back to eapi/v3 and succeed", result.isSuccess)
            assertEquals("Fallback-$label", result.getOrThrow().playlist.name)
            assertEquals(
                listOf(
                    "https://interface.music.163.com/eapi/v6/playlist/detail",
                    "https://interface.music.163.com/eapi/v3/playlist/detail"
                ),
                requestedUrls
            )
        }
    }

    @Test
    fun netEasePlaylistDetailHydratesMissingTracksWithSongDetailEapiFallback() = runBlocking {
        val requestedUrls = CopyOnWriteArrayList<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                requestedUrls.add(url)
                when {
                    url.endsWith("/eapi/v6/playlist/detail") -> {
                        val body = samplePlaylistJson(
                            trackIdsJson = """[{"id": 101}, {"id": 102}, {"id": 103}]"""
                        )
                        jsonResponse(req, 200, body)
                    }
                    url.endsWith("/weapi/v3/song/detail") -> {
                        jsonResponse(req, 502, "Bad Gateway")
                    }
                    url.endsWith("/eapi/v3/song/detail") -> {
                        val songsBody = """
                            {
                              "code": 200,
                              "songs": [
                                {
                                  "id": 103,
                                  "name": "补全曲目三",
                                  "ar": [{"name": "歌手丙"}],
                                  "al": {"id": 503, "name": "专辑三", "picUrl": "http://p1.music.126.net/c.jpg"},
                                  "dt": 180000
                                },
                                {
                                  "id": 102,
                                  "name": "补全曲目二",
                                  "ar": [{"name": "歌手乙"}],
                                  "al": {"id": 502, "name": "专辑二", "picUrl": "http://p1.music.126.net/b.jpg"},
                                  "dt": 195000
                                }
                              ],
                              "privileges": [
                                {"id": 102, "maxbr": 320000},
                                {"id": 103, "maxbr": 999000}
                              ]
                            }
                        """.trimIndent()
                        jsonResponse(req, 200, songsBody)
                    }
                    else -> jsonResponse(req, 404, "unexpected")
                }
            })
            .build()

        val result = NetEaseProvider(client).getPlaylistDetail("19723756")
        assertTrue(result.isSuccess)
        val tracks = result.getOrThrow().tracks
        assertEquals(listOf("wy_101", "wy_102", "wy_103"), tracks.map { it.id })
        assertTrue(requestedUrls.contains("https://music.163.com/weapi/v3/song/detail"))
        assertTrue(requestedUrls.contains("https://interface.music.163.com/eapi/v3/song/detail"))
    }

    @Test
    fun netEasePlaylistDetailReturnsFriendlyFailureWhenAllEndpointsFail() = runBlocking {
        val networkClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> jsonResponse(chain.request(), 502, "Bad Gateway") })
            .build()
        val netFailure = NetEaseProvider(networkClient).getPlaylistDetail("19723756")
        assertTrue(netFailure.isFailure)
        assertEquals("获取网易云歌单失败，请检查网络连接", netFailure.exceptionOrNull()?.message)

        val apiErrorClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> jsonResponse(chain.request(), 200, """{"code": 403}""") })
            .build()
        val apiFailure = NetEaseProvider(apiErrorClient).getPlaylistDetail("19723756")
        assertTrue(apiFailure.isFailure)
        assertEquals("网易云歌单接口暂时不可用", apiFailure.exceptionOrNull()?.message)

        val badJsonClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> jsonResponse(chain.request(), 200, "not-json") })
            .build()
        val jsonFailure = NetEaseProvider(badJsonClient).getPlaylistDetail("19723756")
        assertTrue(jsonFailure.isFailure)
        assertEquals("网易云歌单数据格式异常", jsonFailure.exceptionOrNull()?.message)
    }

    @Test
    fun netEasePlaylistDetailPropagatesCancellationWithoutCallingFallback() = runBlocking {
        val primaryEntered = CompletableDeferred<Unit>()
        val requestedUrls = CopyOnWriteArrayList<String>()
        val sawCancellation = CompletableDeferred<Boolean>()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                requestedUrls.add(req.url.toString())
                primaryEntered.complete(Unit)
                try {
                    Thread.sleep(5_000L)
                } catch (_: InterruptedException) {
                    throw java.io.IOException("Canceled")
                }
                jsonResponse(req, 502, "Should not complete")
            })
            .build()

        val provider = NetEaseProvider(client)
        val job = launch(Dispatchers.Default) {
            try {
                provider.getPlaylistDetail("19723756")
                sawCancellation.complete(false)
            } catch (_: CancellationException) {
                sawCancellation.complete(true)
            }
        }

        withTimeout(5_000L) {
            primaryEntered.await()
            job.cancelAndJoin()
            assertTrue("CancellationException must propagate instead of returning Result.failure", sawCancellation.await())
        }
        assertEquals(listOf("https://interface.music.163.com/eapi/v6/playlist/detail"), requestedUrls)
    }

    // =========================================================================
    // R2: Discover platform state persistence, cache preservation & stale guard
    // =========================================================================

    @Test
    fun discoverSourceNormalizationDefaultsUnsupportedSourcesToWy() {
        assertEquals(listOf(MusicSource.WY, MusicSource.TX, MusicSource.KW), DISCOVER_SUPPORTED_SOURCES)
        assertEquals(MusicSource.WY, normalizeDiscoverSource(MusicSource.WY))
        assertEquals(MusicSource.TX, normalizeDiscoverSource(MusicSource.TX))
        assertEquals(MusicSource.KW, normalizeDiscoverSource(MusicSource.KW))
        assertEquals(MusicSource.WY, normalizeDiscoverSource(MusicSource.KG))
        assertEquals(MusicSource.WY, normalizeDiscoverSource(MusicSource.MG))
        assertEquals(MusicSource.WY, normalizeDiscoverSource(MusicSource.LOCAL))
    }

    @Test
    fun discoverStatePreservesTxAndKwPlatformAndCacheAcrossBackNavigation() {
        val aggregator = MusicAggregator(OkHttpClient())
        val wyPlaylists = listOf(
            OnlinePlaylist("wy_p1", "网易精选", "", "author", "10万", "", 20, MusicSource.WY)
        )
        val txPlaylists = listOf(
            OnlinePlaylist("tx_p1", "QQ热歌精选", "", "txAuthor", "50万", "", 30, MusicSource.TX)
        )
        val kwPlaylists = listOf(
            OnlinePlaylist("kw_p1", "酷我精选", "", "kwAuthor", "20万", "", 25, MusicSource.KW)
        )

        // Start on WY and load WY playlists
        var state = DiscoverSessionState(selectedSource = MusicSource.WY)
        assertTrue(state.shouldFetchForCurrentState())
        state = state.commitFetchedPlaylists(MusicSource.WY, state.generation, wyPlaylists)
        assertFalse(state.shouldFetchForCurrentState())

        // Switch to QQ (TX) and load TX playlists
        state = state.selectSource(MusicSource.TX)
        assertEquals(MusicSource.TX, state.activeSource)
        assertTrue(state.shouldFetchForCurrentState())
        state = state.commitFetchedPlaylists(MusicSource.TX, state.generation, txPlaylists)
        assertEquals(txPlaylists, state.currentPlaylists)
        assertFalse(state.shouldFetchForCurrentState())

        // Simulate opening a TX playlist and navigating back: state hoisted at root stays on TX with cache intact
        val txAfterBackNav = state.copy()
        assertEquals(MusicSource.TX, txAfterBackNav.activeSource)
        assertFalse(txAfterBackNav.shouldFetchForCurrentState())
        assertEquals(txPlaylists, txAfterBackNav.currentPlaylists)
        assertTrue(aggregator.getLeaderboards(txAfterBackNav.activeSource).all { it.source == MusicSource.TX })

        // Switch to Kuwo (KW), load KW playlists, simulate opening playlist & navigating back
        state = txAfterBackNav.selectSource(MusicSource.KW)
        assertEquals(MusicSource.KW, state.activeSource)
        assertTrue(state.shouldFetchForCurrentState())
        state = state.commitFetchedPlaylists(MusicSource.KW, state.generation, kwPlaylists)

        val kwAfterBackNav = state.copy()
        assertEquals(MusicSource.KW, kwAfterBackNav.activeSource)
        assertFalse(kwAfterBackNav.shouldFetchForCurrentState())
        assertEquals(kwPlaylists, kwAfterBackNav.currentPlaylists)
        assertTrue(aggregator.getLeaderboards(kwAfterBackNav.activeSource).all { it.source == MusicSource.KW })

        // Switching back to TX still hits cached TX playlists without resetting to WY
        val backToTx = kwAfterBackNav.selectSource(MusicSource.TX)
        assertEquals(MusicSource.TX, backToTx.activeSource)
        assertFalse(backToTx.shouldFetchForCurrentState())
        assertEquals(txPlaylists, backToTx.currentPlaylists)
    }

    @Test
    fun discoverStateGuardsAgainstStaleAsyncResponsesAndRetryRefresh() {
        val txPlaylists = listOf(
            OnlinePlaylist("tx_1", "QQ歌单", "", "", "1万", "", 10, MusicSource.TX)
        )
        val kwOldPlaylists = listOf(
            OnlinePlaylist("kw_old", "旧酷我", "", "", "1万", "", 10, MusicSource.KW)
        )
        val kwNewPlaylists = listOf(
            OnlinePlaylist("kw_new", "新酷我", "", "", "2万", "", 12, MusicSource.KW)
        )

        // Start TX request at gen1, user switches to KW before TX returns -> TX commit must be dropped
        var state = DiscoverSessionState().selectSource(MusicSource.TX)
        val txRequestGen = state.generation
        state = state.selectSource(MusicSource.KW)
        val kwRequestGen = state.generation

        state = state.commitFetchedPlaylists(MusicSource.TX, txRequestGen, txPlaylists)
        assertEquals(MusicSource.KW, state.activeSource)
        assertFalse(state.hasCachedPlaylists(MusicSource.TX))
        assertTrue(state.currentPlaylists.isEmpty())

        // Commit valid KW response
        state = state.commitFetchedPlaylists(MusicSource.KW, kwRequestGen, kwOldPlaylists)
        assertEquals(kwOldPlaylists, state.currentPlaylists)
        assertFalse(state.shouldFetchForCurrentState())

        // Tap retry on KW: keeps platform on KW, increments generation & forces fetch
        state = state.requestRefresh()
        val retryGen = state.generation
        assertEquals(MusicSource.KW, state.activeSource)
        assertTrue(state.shouldFetchForCurrentState())

        // Stale pre-retry response is ignored
        state = state.commitFetchedPlaylists(MusicSource.KW, kwRequestGen, kwOldPlaylists)
        assertTrue(state.shouldFetchForCurrentState())

        // Current retry response commits and restores cache hit on subsequent back-navigation
        state = state.commitFetchedPlaylists(MusicSource.KW, retryGen, kwNewPlaylists)
        assertEquals(kwNewPlaylists, state.currentPlaylists)
        assertFalse(state.shouldFetchForCurrentState())
    }
}
