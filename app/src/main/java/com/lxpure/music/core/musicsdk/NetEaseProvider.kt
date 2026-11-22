package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.QualityInfo
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

data class PlaylistDetailResult(
    val playlist: OnlinePlaylist,
    val tracks: List<Track>
)

class NetEaseProvider(private val httpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    val leaderboards: List<LeaderboardItem> = listOf(
        LeaderboardItem("wy_bsb", "19723756", "飙升榜", "每日更新 · 网易云", MusicSource.WY, 0xFFF43F5E),
        LeaderboardItem("wy_rgb", "3778678", "热歌榜", "全站热门 · 网易云", MusicSource.WY, 0xFFFB7185),
        LeaderboardItem("wy_xgb", "3779629", "新歌榜", "近期新曲 · 网易云", MusicSource.WY, 0xFF38BDF8),
        LeaderboardItem("wy_ycb", "2884035", "原创榜", "独立原创 · 网易云", MusicSource.WY, 0xFFA855F7),
        LeaderboardItem("wy_dyb", "2250011882", "抖音热歌榜", "潮流爆款 · 网易云", MusicSource.WY, 0xFFEC4899),
        LeaderboardItem("wy_scb", "991319590", "说唱榜", "中文说唱 · 网易云", MusicSource.WY, 0xFFF59E0B),
        LeaderboardItem("wy_dy", "1978921795", "电音榜", "节拍律动 · 网易云", MusicSource.WY, 0xFF06B6D4),
        LeaderboardItem("wy_gd", "71384707", "古典榜", "经典器乐 · 网易云", MusicSource.WY, 0xFF6366F1)
    )

    suspend fun search(query: String, page: Int = 1, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val eapiResult = runCatching { searchViaEapi(query, page, limit) }.getOrDefault(emptyList())
        if (eapiResult.isNotEmpty()) return@withContext eapiResult
        runCatching { searchViaWeapi(query, page, limit) }.getOrDefault(emptyList())
    }

    private fun searchViaEapi(query: String, page: Int, limit: Int): List<Track> {
        val payload = buildJsonObject {
            put("keyword", query)
            put("needCorrect", "1")
            put("channel", "typing")
            put("offset", limit * (page - 1))
            put("scene", "normal")
            put("total", page == 1)
            put("limit", limit)
        }.toString()

        val formParams = NetEaseCrypto.eapi("/api/search/song/list/page", payload)
        val body = postForm("https://interface.music.163.com/eapi/batch", formParams) ?: return emptyList()
        val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return emptyList()
        if (root["code"].asIntOrNull() != 200) return emptyList()
        val resources = root["data"].asJsonObjectOrNull()?.get("resources").asJsonArrayOrNull() ?: return emptyList()

        return resources.mapNotNull { el ->
            runCatching {
                val song = el.asJsonObjectOrNull()
                    ?.get("baseInfo").asJsonObjectOrNull()
                    ?.get("simpleSongData").asJsonObjectOrNull() ?: return@mapNotNull null
                parseSongObj(song, song["privilege"].asJsonObjectOrNull())
            }.getOrNull()
        }
    }

    private fun searchViaWeapi(query: String, page: Int, limit: Int): List<Track> {
        val payload = buildJsonObject {
            put("s", query)
            put("type", 1)
            put("limit", limit)
            put("offset", limit * (page - 1))
            put("total", true)
        }.toString()
        val formParams = NetEaseCrypto.weapi(payload)
        val body = postForm("https://music.163.com/weapi/cloudsearch/get/web", formParams) ?: return emptyList()
        val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return emptyList()
        if (root["code"].asIntOrNull() != 200) return emptyList()
        val songs = root["result"].asJsonObjectOrNull()?.get("songs").asJsonArrayOrNull() ?: return emptyList()
        return songs.mapNotNull { el ->
            runCatching {
                val song = el.asJsonObjectOrNull() ?: return@mapNotNull null
                parseSongObj(song, song["privilege"].asJsonObjectOrNull())
            }.getOrNull()
        }
    }

    suspend fun getHotPlaylists(page: Int = 1, limit: Int = 20): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("cat", "全部")
                put("order", "hot")
                put("limit", limit)
                put("offset", limit * (page - 1))
                put("total", true)
            }.toString()
            val body = postForm("https://music.163.com/weapi/playlist/list", NetEaseCrypto.weapi(payload))
                ?: return@runCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@runCatching emptyList()
            if (root["code"].asIntOrNull() != 200) return@runCatching emptyList()
            val playlists = root["playlists"].asJsonArrayOrNull() ?: return@runCatching emptyList()
            playlists.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"].asStringOrNull() ?: return@mapNotNull null
                    OnlinePlaylist(
                        id = id,
                        name = obj["name"].asStringOrNull().orEmpty(),
                        coverUrl = obj["coverImgUrl"].asStringOrNull().orEmpty().replace("http://", "https://"),
                        author = obj["creator"].asJsonObjectOrNull()?.get("nickname").asStringOrNull().orEmpty(),
                        playCount = FormatUtils.formatPlayCount(obj["playCount"].asLongOrNull() ?: 0L),
                        description = obj["description"].asStringOrNull().orEmpty(),
                        trackCount = obj["trackCount"].asIntOrNull() ?: 0,
                        source = MusicSource.WY
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getPlaylistDetail(rawIdOrUrl: String): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        runCatching {
            val playlistId = extractPlaylistId(rawIdOrUrl) ?: error("无法识别网易云歌单 ID")
            val linuxPayload = buildJsonObject {
                put("method", "POST")
                put("url", "https://music.163.com/api/v3/playlist/detail")
                put("params", buildJsonObject {
                    put("id", playlistId)
                    put("n", 100000)
                    put("s", 8)
                })
            }.toString()

            val body = postForm("https://music.163.com/api/linux/forward", NetEaseCrypto.linuxApi(linuxPayload))
                ?: error("获取网易云歌单失败，请检查网络连接")
            val root = json.parseToJsonElement(body).asJsonObjectOrNull()
                ?: error("网易云歌单数据格式异常")
            if (root["code"].asIntOrNull() != 200) error("网易云歌单接口暂时不可用")

            val playlistObj = root["playlist"].asJsonObjectOrNull() ?: error("歌单不存在或暂无访问权限")
            val meta = OnlinePlaylist(
                id = playlistId,
                name = playlistObj["name"].asStringOrNull() ?: "网易云歌单",
                coverUrl = playlistObj["coverImgUrl"].asStringOrNull().orEmpty().replace("http://", "https://"),
                author = playlistObj["creator"].asJsonObjectOrNull()?.get("nickname").asStringOrNull().orEmpty(),
                playCount = FormatUtils.formatPlayCount(playlistObj["playCount"].asLongOrNull() ?: 0L),
                description = playlistObj["description"].asStringOrNull().orEmpty(),
                trackCount = playlistObj["trackCount"].asIntOrNull() ?: 0,
                source = MusicSource.WY
            )

            val privilegesMap = buildMap<String, JsonObject> {
                root["privileges"].asJsonArrayOrNull()?.forEach { el ->
                    val privObj = el.asJsonObjectOrNull() ?: return@forEach
                    val pid = privObj["id"].asStringOrNull() ?: return@forEach
                    put(pid, privObj)
                }
            }

            val trackIds = playlistObj["trackIds"].asJsonArrayOrNull()?.mapNotNull { el ->
                el.asJsonObjectOrNull()?.get("id").asStringOrNull()
            }.orEmpty()

            val directTracks = playlistObj["tracks"].asJsonArrayOrNull()?.mapNotNull { el ->
                runCatching {
                    val song = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val sid = song["id"].asStringOrNull().orEmpty()
                    parseSongObj(song, privilegesMap[sid])
                }.getOrNull()
            }.orEmpty()

            val finalTracks = if (directTracks.size >= trackIds.size || trackIds.isEmpty()) {
                directTracks
            } else {
                // Keep already-returned tracks and hydrate ALL remaining trackIds in parallel waves
                // (supports large playlists with 2,400–10,000+ songs while preserving original order)
                val hydratedMap = LinkedHashMap<String, Track>(trackIds.size)
                directTracks.forEach { hydratedMap[it.songmid] = it }

                val missingIds = trackIds.filter { it !in hydratedMap }
                val chunks = missingIds.chunked(400)
                coroutineScope {
                    // Run 3 chunks concurrently per wave to stay fast while avoiding NetEase rate limits
                    chunks.chunked(3).forEach { wave ->
                        val batchResults = wave.map { chunk ->
                            async { fetchSongsByIdsWithRetry(chunk) }
                        }.awaitAll()
                        batchResults.forEach { list ->
                            list.forEach { track -> hydratedMap[track.songmid] = track }
                        }
                    }
                }

                val ordered = trackIds.mapNotNull { hydratedMap[it] }
                ordered.ifEmpty { directTracks }
            }

            PlaylistDetailResult(meta.copy(trackCount = finalTracks.size), finalTracks)
        }
    }

    private fun fetchSongsByIdsWithRetry(ids: List<String>, maxAttempts: Int = 2): List<Track> {
        repeat(maxAttempts) {
            val res = runCatching { fetchSongsByIds(ids) }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun fetchSongsByIds(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val cParam = "[" + ids.joinToString(",") { """{"id":$it}""" } + "]"
        val idsParam = "[" + ids.joinToString(",") + "]"
        val payload = buildJsonObject {
            put("c", cParam)
            put("ids", idsParam)
        }.toString()

        val body = postForm("https://music.163.com/weapi/v3/song/detail", NetEaseCrypto.weapi(payload)) ?: return emptyList()
        val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return emptyList()
        if (root["code"].asIntOrNull() != 200) return emptyList()
        val songs = root["songs"].asJsonArrayOrNull() ?: return emptyList()
        val privMap = buildMap<String, JsonObject> {
            root["privileges"].asJsonArrayOrNull()?.forEach { el ->
                val privObj = el.asJsonObjectOrNull() ?: return@forEach
                val pid = privObj["id"].asStringOrNull() ?: return@forEach
                put(pid, privObj)
            }
        }

        return songs.mapNotNull { el ->
            runCatching {
                val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                val id = obj["id"].asStringOrNull().orEmpty()
                parseSongObj(obj, privMap[id])
            }.getOrNull()
        }
    }

    suspend fun getLyric(songmid: String): ParsedLyrics = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("id", songmid)
                put("cp", false)
                put("tv", 0)
                put("lv", 0)
                put("rv", 0)
                put("kv", 0)
                put("yv", 0)
                put("ytv", 0)
                put("yrv", 0)
            }.toString()
            val body = postForm("https://interface3.music.163.com/eapi/song/lyric/v1", NetEaseCrypto.eapi("/api/song/lyric/v1", payload))
                ?: return@runCatching ParsedLyrics()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@runCatching ParsedLyrics()
            val rawLrc = root["yrc"].asJsonObjectOrNull()?.get("lyric").asStringOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: root["lrc"].asJsonObjectOrNull()?.get("lyric").asStringOrNull()
            val rawTrans = root["ytlrc"].asJsonObjectOrNull()?.get("lyric").asStringOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: root["tlyric"].asJsonObjectOrNull()?.get("lyric").asStringOrNull()
            LrcParser.parse(rawLrc, rawTrans)
        }.getOrDefault(ParsedLyrics())
    }

    private fun extractPlaylistId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return trimmed

        val directMatch = Regex("(?:[?&]id=|/playlist/)(\\d+)").find(trimmed)
        if (directMatch != null) return directMatch.groupValues[1]

        // Follow short links such as 163cn.tv
        val urlMatch = Regex("https?://\\S+").find(trimmed)?.value ?: return null
        val req = Request.Builder().url(urlMatch).header("User-Agent", ua).build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                Regex("(?:[?&]id=|/playlist/)(\\d+)").find(finalUrl)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    internal fun parseSongObj(item: JsonObject, privilege: JsonObject?): Track? {
        val id = item["id"].asStringOrNull() ?: return null
        val name = item["name"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val artists = item["ar"].asJsonArrayOrNull()
            ?.mapNotNull { it.asJsonObjectOrNull()?.get("name").asStringOrNull() }
            ?.joinToString(" / ")
            .orEmpty()
            .ifBlank { "未知歌手" }
        val albumObj = item["al"].asJsonObjectOrNull()
        val albumName = albumObj?.get("name").asStringOrNull().orEmpty()
        val albumId = albumObj?.get("id").asStringOrNull()
        val coverUrl = albumObj?.get("picUrl").asStringOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.replace("http://", "https://")
        val durationMs = item["dt"].asLongOrNull() ?: 0L

        val qualities = mutableListOf<QualityInfo>()
        val maxBrLevel = privilege?.get("maxBrLevel").asStringOrNull()
        val maxBr = privilege?.get("maxbr").asIntOrNull() ?: 320000

        qualities.add(
            QualityInfo(
                "128k",
                FormatUtils.formatSize(item["l"].asJsonObjectOrNull()?.get("size").asLongOrNull() ?: 0L)
            )
        )
        if (maxBr >= 320000) {
            qualities.add(
                QualityInfo(
                    "320k",
                    FormatUtils.formatSize(item["h"].asJsonObjectOrNull()?.get("size").asLongOrNull() ?: 0L)
                )
            )
        }
        if (maxBr >= 999000) {
            qualities.add(
                QualityInfo(
                    "flac",
                    FormatUtils.formatSize(item["sq"].asJsonObjectOrNull()?.get("size").asLongOrNull() ?: 0L)
                )
            )
        }
        if (maxBrLevel == "hires") {
            qualities.add(
                QualityInfo(
                    "flac24bit",
                    FormatUtils.formatSize(item["hr"].asJsonObjectOrNull()?.get("size").asLongOrNull() ?: 0L)
                )
            )
        }

        return Track(
            id = "wy_$id",
            title = name,
            artist = artists,
            album = albumName,
            source = MusicSource.WY,
            songmid = id,
            durationMs = durationMs,
            coverUrl = coverUrl,
            albumId = albumId,
            qualities = qualities
        )
    }

    private fun postForm(url: String, params: Map<String, String>): String? {
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Origin", "https://music.163.com")
            .header("Referer", "https://music.163.com/")
            .post(form)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }
}
