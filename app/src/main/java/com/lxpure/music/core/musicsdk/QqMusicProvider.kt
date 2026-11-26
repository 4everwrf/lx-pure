package com.lxpure.music.core.musicsdk

import com.lxpure.music.core.userapi.LxCrypto
import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.QualityInfo
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class QqMusicProvider(private val httpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val leaderboards: List<LeaderboardItem> = listOf(
        LeaderboardItem("tx_lxb", "4", "流行指数榜", "实时飙升 · QQ", MusicSource.TX, 0xFF10B981),
        LeaderboardItem("tx_rgb", "26", "热歌榜", "全网热门 · QQ", MusicSource.TX, 0xFF14B8A6),
        LeaderboardItem("tx_xgb", "27", "新歌榜", "最新发行 · QQ", MusicSource.TX, 0xFF38BDF8),
        LeaderboardItem("tx_bsb", "62", "飙升榜", "热度暴涨 · QQ", MusicSource.TX, 0xFF22C55E),
        LeaderboardItem("tx_dyb", "60", "抖快榜", "短视频热曲 · QQ", MusicSource.TX, 0xFFEC4899),
        LeaderboardItem("tx_ndb", "5", "内地榜", "华语精选 · QQ", MusicSource.TX, 0xFF6366F1),
        LeaderboardItem("tx_omb", "3", "欧美榜", "Billboard · QQ", MusicSource.TX, 0xFF8B5CF6),
        LeaderboardItem("tx_hgb", "16", "韩国榜", "K-Pop · QQ", MusicSource.TX, 0xFFF59E0B)
    )

    suspend fun search(query: String, page: Int = 1, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val searchId = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT) +
                Random.nextInt(10000, 99999).toString()

            val payload = buildJsonObject {
                put("comm", buildJsonObject {
                    put("_channelid", "0")
                    put("_os_version", "6.2.9200-2")
                    put("ct", "19")
                    put("cv", "2151")
                    put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                    put("patch", "118")
                    put("tmeAppID", "qqmusic")
                    put("tmeLoginType", 0)
                    put("uin", "0")
                    put("wid", "7223299733393904640")
                })
                put("music.search.SearchCgiService", buildJsonObject {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", buildJsonObject {
                        put("grp", 1)
                        put("num_per_page", limit)
                        put("page_num", page)
                        put("query", query)
                        put("remoteplace", "txt.newclient.top")
                        put("search_type", 0)
                        put("searchid", searchId)
                    })
                })
            }.toString()

            val sign = QqSign.zzcSign(payload)
            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign")
                .header("User-Agent", "QQMusic 14090508(android 12)")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@suspendRunCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching emptyList()
            val list = root["music.search.SearchCgiService"].asJsonObjectOrNull()
                ?.get("data").asJsonObjectOrNull()
                ?.get("body").asJsonObjectOrNull()
                ?.get("song").asJsonObjectOrNull()
                ?.get("list").asJsonArrayOrNull() ?: return@suspendRunCatching emptyList()

            list.mapNotNull { el ->
                runCatching { el.asJsonObjectOrNull()?.let { parseQqSong(it) } }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getHotPlaylists(page: Int = 1, limit: Int = 20): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val payload = buildJsonObject {
                put("comm", buildJsonObject {
                    put("cv", 1602)
                    put("ct", 20)
                })
                put("playlist", buildJsonObject {
                    put("method", "get_playlist_by_tag")
                    put("param", buildJsonObject {
                        put("id", 10000000)
                        put("sin", (page - 1) * limit)
                        put("size", limit)
                        put("order", 5)
                        put("cur_page", page)
                    })
                    put("module", "playlist.PlayListPlazaServer")
                })
            }.toString()

            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@suspendRunCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching emptyList()
            val items = root["playlist"].asJsonObjectOrNull()
                ?.get("data").asJsonObjectOrNull()
                ?.get("v_playlist").asJsonArrayOrNull()
                ?: return@suspendRunCatching emptyList()

            items.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["tid"].asStringOrNull() ?: return@mapNotNull null
                    OnlinePlaylist(
                        id = id,
                        name = FormatUtils.decodeHtml(obj["title"].asStringOrNull()),
                        coverUrl = obj["cover_url_medium"].asStringOrNull().orEmpty().replace("http://", "https://"),
                        author = obj["creator_info"].asJsonObjectOrNull()?.get("nick").asStringOrNull().orEmpty(),
                        playCount = FormatUtils.formatPlayCount(obj["access_num"].asLongOrNull() ?: 0L),
                        description = FormatUtils.decodeHtml(obj["desc"].asStringOrNull()),
                        trackCount = obj["song_ids"].asJsonArrayOrNull()?.size ?: 0,
                        source = MusicSource.TX
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getPlaylistDetail(rawIdOrUrl: String): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val id = extractPlaylistId(rawIdOrUrl) ?: error("无法识别 QQ 音乐歌单 ID")
            val numericId = id.toLongOrNull() ?: error("无法识别 QQ 音乐歌单 ID")
            val pageSize = 500
            val maxTracks = 10_000
            val allTracks = mutableListOf<Track>()
            val seenIds = HashSet<String>()
            var offset = 0
            var totalExpected = Int.MAX_VALUE
            var firstDir: JsonObject? = null

            while (offset < totalExpected && allTracks.size < maxTracks) {
                val payload = buildJsonObject {
                    put("comm", buildJsonObject {
                        put("cv", 4747474)
                        put("ct", 24)
                        put("format", "json")
                        put("inCharset", "utf-8")
                        put("outCharset", "utf-8")
                        put("platform", "yqq.json")
                        put("needNewCode", 1)
                        put("uin", 0)
                    })
                    put("req_1", buildJsonObject {
                        put("module", "music.srfDissInfo.aiDissInfo")
                        put("method", "uniform_get_Dissinfo")
                        put("param", buildJsonObject {
                            put("disstid", numericId)
                            put("userinfo", if (offset == 0) 1 else 0)
                            put("tag", if (offset == 0) 1 else 0)
                            put("orderlist", 1)
                            put("song_begin", offset)
                            put("song_num", pageSize)
                            put("onlysonglist", if (offset == 0) 0 else 1)
                            put("enc_host_uin", "")
                        })
                    })
                }.toString()

                val req = Request.Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .header("Origin", "https://y.qq.com")
                    .header("Referer", "https://y.qq.com/n/yqq/playsquare/$id.html")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .post(payload.toRequestBody(jsonMediaType))
                    .build()

                val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                    ?: if (offset == 0) error("获取 QQ 歌单失败，请检查网络连接") else break
                val root = json.parseToJsonElement(body).asJsonObjectOrNull()
                    ?: if (offset == 0) error("QQ 歌单数据异常") else break
                val req1 = root["req_1"].asJsonObjectOrNull()
                    ?: if (offset == 0) error("QQ 歌单数据为空") else break
                val reqCode = req1["code"].asIntOrNull()
                if (reqCode != null && reqCode != 0) {
                    if (offset == 0) error("歌单不存在或暂无访问权限") else break
                }
                val data = req1["data"].asJsonObjectOrNull()
                    ?: if (offset == 0) error("QQ 歌单数据为空") else break

                if (firstDir == null) {
                    firstDir = data["dirinfo"].asJsonObjectOrNull()
                    totalExpected = data["total_song_num"].asIntOrNull()
                        ?: firstDir?.get("songnum").asIntOrNull()
                        ?: pageSize
                }

                val rawList = data["songlist"].asJsonArrayOrNull()
                if (rawList == null || rawList.isEmpty()) break

                var added = 0
                rawList.forEach { el ->
                    val parsed = runCatching { el.asJsonObjectOrNull()?.let { parseQqSong(it) } }.getOrNull()
                    if (parsed != null && seenIds.add(parsed.id)) {
                        allTracks.add(parsed)
                        added++
                    }
                }
                offset += rawList.size
                if (added == 0 || (totalExpected > 0 && offset >= totalExpected)) break
            }

            val dirTitle = firstDir?.get("title").asNonBlankStringOrNull()
            if (allTracks.isEmpty() && dirTitle == null) {
                error("歌单不存在或暂无访问权限")
            }

            val meta = OnlinePlaylist(
                id = id,
                name = FormatUtils.decodeHtml(dirTitle ?: "QQ 音乐歌单"),
                coverUrl = firstDir?.get("picurl").asStringOrNull().orEmpty().replace("http://", "https://"),
                author = firstDir?.get("host_nick").asStringOrNull().orEmpty(),
                playCount = FormatUtils.formatPlayCount(firstDir?.get("listennum").asLongOrNull() ?: 0L),
                description = FormatUtils.decodeHtml(firstDir?.get("desc").asStringOrNull()),
                trackCount = allTracks.size,
                source = MusicSource.TX
            )
            PlaylistDetailResult(meta, allTracks)
        }
    }

    suspend fun getLeaderboardDetail(board: LeaderboardItem): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val payload = buildJsonObject {
                put("toplist", buildJsonObject {
                    put("module", "musicToplist.ToplistInfoServer")
                    put("method", "GetDetail")
                    put("param", buildJsonObject {
                        put("topid", board.bangId.toInt())
                        put("num", 100)
                        put("period", "")
                    })
                })
                put("comm", buildJsonObject {
                    put("uin", 0)
                    put("format", "json")
                    put("ct", 20)
                    put("cv", 1859)
                })
            }.toString()

            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .post(payload.toRequestBody(jsonMediaType))
                .build()

            val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                ?: error("获取 QQ 榜单失败，请检查网络连接")
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: error("QQ 榜单数据异常")
            val data = root["toplist"].asJsonObjectOrNull()?.get("data").asJsonObjectOrNull() ?: error("榜单数据为空")
            val info = data["data"].asJsonObjectOrNull()
            val tracks = data["songInfoList"].asJsonArrayOrNull()?.mapNotNull { el ->
                runCatching { el.asJsonObjectOrNull()?.let { parseQqSong(it) } }.getOrNull()
            }.orEmpty()

            val meta = OnlinePlaylist(
                id = board.bangId,
                name = info?.get("title").asNonBlankStringOrNull() ?: board.name,
                coverUrl = info?.get("frontPicUrl").asStringOrNull().orEmpty().replace("http://", "https://"),
                author = "QQ 音乐官方榜",
                playCount = info?.get("updateTime").asStringOrNull().orEmpty(),
                description = FormatUtils.decodeHtml(info?.get("intro").asStringOrNull()),
                trackCount = tracks.size,
                source = MusicSource.TX
            )
            PlaylistDetailResult(meta, tracks)
        }
    }

    suspend fun getLyric(songmid: String): ParsedLyrics = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val req = Request.Builder()
                .url("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json&nobase64=0")
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@suspendRunCatching ParsedLyrics()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching ParsedLyrics()
            val rawLrc = root["lyric"].asStringOrNull()?.let { decodeB64(it).replace("&#10;", "\n") }
            val rawTrans = root["trans"].asStringOrNull()?.let { decodeB64(it).replace("&#10;", "\n") }
            LrcParser.parse(rawLrc, rawTrans)
        }.getOrDefault(ParsedLyrics())
    }

    private fun decodeB64(str: String): String {
        if (str.isBlank()) return ""
        val bytes = LxCrypto.decodeBase64Flexible(str) ?: return ""
        return String(bytes, StandardCharsets.UTF_8)
    }

    internal suspend fun extractPlaylistId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return trimmed

        val idRegex = Regex("(?:/playlist/|/playsquare/|[?&](?:id|tid|disstid)=)(\\d+)", RegexOption.IGNORE_CASE)
        idRegex.find(trimmed)?.let { return it.groupValues[1] }

        val urlMatch = extractFirstHttpUrl(trimmed) ?: return null
        val req = Request.Builder().url(urlMatch).header("User-Agent", "Mozilla/5.0").build()
        return suspendRunCatching {
            httpClient.newCall(req).awaitResponse().use { resp ->
                val finalUrl = resp.request.url.toString()
                idRegex.find(finalUrl)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    internal fun parseQqSong(item: JsonObject): Track? {
        val mid = item["mid"].asNonBlankStringOrNull() ?: return null
        val file = item["file"].asJsonObjectOrNull()
        val mediaMid = file?.get("media_mid").asNonBlankStringOrNull() ?: mid
        val title = FormatUtils.decodeHtml(item["title"].asNonBlankStringOrNull() ?: item["name"].asNonBlankStringOrNull())
            .ifBlank { return null }
        val singers = item["singer"].asJsonArrayOrNull()
        val artistList = singers?.mapNotNull {
            it.asJsonObjectOrNull()?.get("name").asNonBlankStringOrNull()
                ?.let { rawName -> FormatUtils.decodeHtml(rawName).trim() }
                ?.takeIf { cleaned -> cleaned.isNotEmpty() }
        }.orEmpty()
        val artist = artistList
            .joinToString(" / ")
            .ifBlank { "未知歌手" }
        val albumObj = item["album"].asJsonObjectOrNull()
        val albumName = FormatUtils.decodeHtml(albumObj?.get("name").asStringOrNull())
        val albumMid = albumObj?.get("mid").asStringOrNull().orEmpty()
        val firstSingerMid = singers?.firstOrNull().asJsonObjectOrNull()?.get("mid").asStringOrNull().orEmpty()
        val coverUrl = when {
            albumMid.isNotBlank() && albumMid != "空" -> "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
            firstSingerMid.isNotBlank() -> "https://y.gtimg.cn/music/photo_new/T001R500x500M000$firstSingerMid.jpg"
            else -> null
        }

        val qualities = mutableListOf<QualityInfo>()
        val s128 = file?.get("size_128mp3").asLongOrNull() ?: 0L
        val s320 = file?.get("size_320mp3").asLongOrNull() ?: 0L
        val sFlac = file?.get("size_flac").asLongOrNull() ?: 0L
        val sHires = file?.get("size_hires").asLongOrNull() ?: 0L
        if (s128 > 0L) qualities.add(QualityInfo("128k", FormatUtils.formatSize(s128)))
        if (s320 > 0L) qualities.add(QualityInfo("320k", FormatUtils.formatSize(s320)))
        if (sFlac > 0L) qualities.add(QualityInfo("flac", FormatUtils.formatSize(sFlac)))
        if (sHires > 0L) qualities.add(QualityInfo("flac24bit", FormatUtils.formatSize(sHires)))
        if (qualities.isEmpty()) qualities.add(QualityInfo("128k"))

        return Track(
            id = "tx_$mid",
            title = title,
            artist = artist,
            album = albumName,
            source = MusicSource.TX,
            songmid = mid,
            durationMs = (item["interval"].asLongOrNull() ?: 0L) * 1000L,
            coverUrl = coverUrl,
            albumId = albumMid,
            albumMid = albumMid,
            strMediaMid = mediaMid,
            songId = item["id"].asStringOrNull(),
            qualities = qualities,
            artistNames = artistList
        )
    }
}
