package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.ArtistParser
import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.LyricLine
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

class KuwoProvider(private val httpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val minfoRegex = Regex("level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)")

    companion object {
        /**
         * Kuwo image CDN hosts (`img1~img4.kwcdn.kuwo.cn`, `img1.kuwo.cn`, etc.) serve Tencent Cloud
         * wildcard certificates (`*.cdn.myqcloud.com`) which fail HTTPS hostname verification on Android.
         * Keeping Kuwo CDN URLs on `http://` ensures 100% reliable cover loading in Coil & Media3 notifications.
         */
        fun normalizeKuwoImageUrl(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val upgraded = when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.startsWith("//") -> "http:$trimmed"
                trimmed.startsWith("120/") -> "http://img1.kuwo.cn/star/albumcover/${trimmed.replaceFirst("120/", "500/")}"
                else -> "http://img1.kuwo.cn/star/albumcover/$trimmed"
            }
            return if (upgraded.contains("kuwo.cn", ignoreCase = true)) {
                upgraded.replaceFirst("https://", "http://", ignoreCase = true)
            } else {
                upgraded
            }
        }

        internal fun parseKuwoArtist(raw: String?): Pair<String, List<String>> {
            val decoded = FormatUtils.decodeHtml(raw)
            val parsed = ArtistParser.parseRawString(decoded)
            val display = if (parsed.isNotEmpty()) {
                parsed.joinToString(" / ")
            } else {
                ArtistParser.fallbackDisplayName(decoded)
            }
            return display to parsed
        }
    }

    val leaderboards: List<LeaderboardItem> = listOf(
        LeaderboardItem("kw_bsb", "93", "飙升榜", "热门飙升 · 酷我", MusicSource.KW, 0xFFF59E0B),
        LeaderboardItem("kw_rgb", "16", "热歌榜", "全站热播 · 酷我", MusicSource.KW, 0xFFFB923C),
        LeaderboardItem("kw_xgb", "17", "新歌榜", "最新上架 · 酷我", MusicSource.KW, 0xFF38BDF8),
        LeaderboardItem("kw_dyb", "158", "抖音热歌榜", "爆款单曲 · 酷我", MusicSource.KW, 0xFFEC4899),
        LeaderboardItem("kw_hyb", "104", "华语榜", "经典华语 · 酷我", MusicSource.KW, 0xFFEF4444),
        LeaderboardItem("kw_omb", "22", "欧美榜", "潮流欧美 · 酷我", MusicSource.KW, 0xFF6366F1)
    )

    suspend fun search(query: String, page: Int = 1, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?client=kt&all=$encoded&pn=${page - 1}&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
            val body = getString(url) ?: return@suspendRunCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching emptyList()
            val absList = root["abslist"].asJsonArrayOrNull() ?: return@suspendRunCatching emptyList()

            absList.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val rid = obj["MUSICRID"].asNonBlankStringOrNull()?.removePrefix("MUSIC_") ?: return@mapNotNull null
                    val name = FormatUtils.decodeHtml(obj["SONGNAME"].asNonBlankStringOrNull() ?: obj["NAME"].asNonBlankStringOrNull())
                        .ifBlank { return@mapNotNull null }
                    val (artist, artistList) = parseKuwoArtist(obj["ARTIST"].asStringOrNull())
                    val album = FormatUtils.decodeHtml(obj["ALBUM"].asStringOrNull())
                    val durationSec = obj["DURATION"].asLongOrNull() ?: 0L
                    val rawPic = obj["hts_MVPIC"].asNonBlankStringOrNull()
                        ?: obj["web_albumpic_short"].asNonBlankStringOrNull()
                        ?: obj["albumpic"].asNonBlankStringOrNull()
                    val cover = normalizeKuwoImageUrl(rawPic)

                    Track(
                        id = "kw_$rid",
                        title = name,
                        artist = artist,
                        album = album,
                        source = MusicSource.KW,
                        songmid = rid,
                        durationMs = durationSec * 1000L,
                        coverUrl = cover,
                        albumId = obj["ALBUMID"].asStringOrNull(),
                        qualities = parseMinfoQualities(obj["N_MINFO"].asNonBlankStringOrNull() ?: obj["n_minfo"].asNonBlankStringOrNull()),
                        artistNames = artistList
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getHotPlaylists(page: Int = 1, limit: Int = 20): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=$page&rn=$limit&order=hot"
            val body = getString(url) ?: return@suspendRunCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching emptyList()
            val list = root["data"].asJsonObjectOrNull()?.get("data").asJsonArrayOrNull() ?: return@suspendRunCatching emptyList()
            list.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val id = obj["id"].asStringOrNull() ?: return@mapNotNull null
                    OnlinePlaylist(
                        id = id,
                        name = FormatUtils.decodeHtml(obj["name"].asStringOrNull()),
                        coverUrl = normalizeKuwoImageUrl(obj["img"].asStringOrNull()).orEmpty(),
                        author = FormatUtils.decodeHtml(obj["uname"].asStringOrNull()),
                        playCount = FormatUtils.formatPlayCount(obj["listencnt"].asLongOrNull() ?: 0L),
                        description = FormatUtils.decodeHtml(obj["desc"].asStringOrNull()),
                        trackCount = obj["total"].asIntOrNull() ?: 0,
                        source = MusicSource.KW
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getPlaylistDetail(rawIdOrUrl: String): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val id = extractPlaylistId(rawIdOrUrl) ?: error("无法识别酷我歌单 ID")
            val pageSize = 500
            val maxPages = 20
            val allRawTracks = mutableListOf<Track>()
            val seenIds = HashSet<String>()
            var firstRoot: JsonObject? = null
            var totalExpected = Int.MAX_VALUE

            for (page in 0 until maxPages) {
                if (allRawTracks.size >= totalExpected) break
                val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=$page&rn=$pageSize&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
                val body = getString(url)
                    ?: if (page == 0) error("获取酷我歌单失败，请检查网络连接") else break
                val root = json.parseToJsonElement(body).asJsonObjectOrNull()
                    ?: if (page == 0) error("酷我歌单数据异常") else break
                if (root["result"].asStringOrNull().equals("fail", ignoreCase = true)) {
                    if (page == 0) error("酷我歌单不存在或暂无访问权限") else break
                }
                if (firstRoot == null) {
                    firstRoot = root
                    totalExpected = root["total"].asIntOrNull() ?: pageSize
                }
                val musicList = root["musiclist"].asJsonArrayOrNull()
                    ?: if (page == 0) error("酷我歌单为空") else break
                if (musicList.isEmpty()) break

                var added = 0
                musicList.forEach { el ->
                    val track = runCatching {
                        val obj = el.asJsonObjectOrNull() ?: return@runCatching null
                        val rid = obj["id"].asNonBlankStringOrNull() ?: return@runCatching null
                        val title = FormatUtils.decodeHtml(obj["name"].asNonBlankStringOrNull()).ifBlank { return@runCatching null }
                        val (artist, artistList) = parseKuwoArtist(obj["artist"].asStringOrNull())
                        Track(
                            id = "kw_$rid",
                            title = title,
                            artist = artist,
                            album = FormatUtils.decodeHtml(obj["album"].asStringOrNull()),
                            source = MusicSource.KW,
                            songmid = rid,
                            durationMs = (obj["duration"].asLongOrNull() ?: 0L) * 1000L,
                            coverUrl = extractInlineCover(obj),
                            albumId = obj["albumid"].asStringOrNull(),
                            qualities = parseMinfoQualities(obj["N_MINFO"].asNonBlankStringOrNull() ?: obj["n_minfo"].asNonBlankStringOrNull()),
                            artistNames = artistList
                        )
                    }.getOrNull()
                    if (track != null && seenIds.add(track.id)) {
                        allRawTracks.add(track)
                        added++
                    }
                }
                if (added == 0 || musicList.size < pageSize) break
            }

            val rootTitle = firstRoot?.get("title").asNonBlankStringOrNull()
            if (allRawTracks.isEmpty() && rootTitle == null) {
                error("酷我歌单不存在或暂无访问权限")
            }

            val tracks = hydrateMissingCovers(allRawTracks)
            val meta = OnlinePlaylist(
                id = id,
                name = FormatUtils.decodeHtml(rootTitle ?: "酷我歌单"),
                coverUrl = normalizeKuwoImageUrl(firstRoot?.get("pic").asStringOrNull()).orEmpty(),
                author = FormatUtils.decodeHtml(firstRoot?.get("uname").asStringOrNull()),
                playCount = FormatUtils.formatPlayCount(firstRoot?.get("playnum").asLongOrNull() ?: 0L),
                description = FormatUtils.decodeHtml(firstRoot?.get("info").asStringOrNull()),
                trackCount = tracks.size,
                source = MusicSource.KW
            )
            PlaylistDetailResult(meta, tracks)
        }
    }

    suspend fun getLeaderboardDetail(board: LeaderboardItem): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=100&type=bang&data=content&id=${board.bangId}&show_copyright_off=0&pcmp4=1&isbang=1"
            val body = getString(url) ?: error("获取酷我榜单失败，请检查网络连接")
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: error("酷我榜单数据异常")
            val list = root["musiclist"].asJsonArrayOrNull() ?: error("酷我榜单数据为空")
            val rawTracks = list.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val rid = obj["id"].asNonBlankStringOrNull() ?: return@mapNotNull null
                    val title = FormatUtils.decodeHtml(obj["name"].asNonBlankStringOrNull()).ifBlank { return@mapNotNull null }
                    val (artist, artistList) = parseKuwoArtist(obj["artist"].asStringOrNull())
                    Track(
                        id = "kw_$rid",
                        title = title,
                        artist = artist,
                        album = FormatUtils.decodeHtml(obj["album"].asStringOrNull()),
                        source = MusicSource.KW,
                        songmid = rid,
                        durationMs = (obj["duration"].asLongOrNull() ?: 0L) * 1000L,
                        coverUrl = extractInlineCover(obj),
                        albumId = obj["albumid"].asStringOrNull(),
                        qualities = parseMinfoQualities(obj["n_minfo"].asNonBlankStringOrNull() ?: obj["N_MINFO"].asNonBlankStringOrNull()),
                        artistNames = artistList
                    )
                }.getOrNull()
            }
            val tracks = hydrateMissingCovers(rawTracks)
            val boardPic = normalizeKuwoImageUrl(
                root["pic5"].asNonBlankStringOrNull()
                    ?: root["pic2"].asNonBlankStringOrNull()
                    ?: root["pic"].asNonBlankStringOrNull()
            ).orEmpty()
            val meta = OnlinePlaylist(
                id = board.bangId,
                name = root["name"].asNonBlankStringOrNull() ?: board.name,
                coverUrl = boardPic,
                author = "酷我音乐官方榜",
                playCount = root["pub"].asStringOrNull().orEmpty(),
                trackCount = tracks.size,
                source = MusicSource.KW
            )
            PlaylistDetailResult(meta, tracks)
        }
    }

    suspend fun getLyric(songmid: String): ParsedLyrics = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songmid"
            val body = getString(url) ?: return@suspendRunCatching ParsedLyrics()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching ParsedLyrics()
            val lrcList = root["data"].asJsonObjectOrNull()?.get("lrclist").asJsonArrayOrNull()
                ?: return@suspendRunCatching ParsedLyrics()
            val lines = mutableListOf<LyricLine>()
            lrcList.forEach { el ->
                val obj = el.asJsonObjectOrNull() ?: return@forEach
                val sec = obj["time"].asDoubleOrNull() ?: return@forEach
                if (!sec.isFinite()) return@forEach
                val text = FormatUtils.decodeHtml(obj["lineLyric"].asStringOrNull())
                if (text.isNotEmpty()) {
                    lines.add(
                        LyricLine(
                            timeMs = (sec * 1000.0).roundToLong().coerceAtLeast(0L),
                            text = text
                        )
                    )
                }
            }
            if (lines.isEmpty()) {
                ParsedLyrics()
            } else {
                ParsedLyrics(lines = lines.sortedBy { it.timeMs }, hasTranslation = false)
            }
        }.getOrDefault(ParsedLyrics())
    }

    suspend fun resolveCover(songmid: String): String? = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$songmid"
            val pic = getString(url)?.trim()
            if (pic != null && pic.startsWith("http", ignoreCase = true)) {
                normalizeKuwoImageUrl(pic)
            } else {
                null
            }
        }.getOrNull()
    }

    private fun extractInlineCover(obj: JsonObject): String? {
        val raw = obj["albumpic"].asNonBlankStringOrNull()
            ?: obj["pic"].asNonBlankStringOrNull()
            ?: obj["web_albumpic_short"].asNonBlankStringOrNull()
            ?: obj["pic120"].asNonBlankStringOrNull()
        return normalizeKuwoImageUrl(raw)
    }

    /**
     * Kuwo chart/playlist payloads sometimes omit per-track cover URLs.
     * Concurrently hydrates covers with cancellable HTTP calls and preserves partial results on timeout.
     */
    private suspend fun hydrateMissingCovers(tracks: List<Track>, maxHydrate: Int = 24): List<Track> {
        val targets = tracks.take(maxHydrate).filter { it.coverUrl.isNullOrBlank() }
        if (targets.isEmpty()) return tracks
        val coverMap = ConcurrentHashMap<String, String>()
        withTimeoutOrNull(1_500L) {
            coroutineScope {
                targets.map { t ->
                    async {
                        val url = suspendRunCatching { resolveCover(t.songmid) }.getOrNull()
                        if (!url.isNullOrBlank()) {
                            coverMap[t.songmid] = url
                        }
                    }
                }.awaitAll()
            }
        }
        if (coverMap.isEmpty()) return tracks
        return tracks.map { track ->
            val hydrated = coverMap[track.songmid]
            if (track.coverUrl.isNullOrBlank() && hydrated != null) track.copy(coverUrl = hydrated) else track
        }
    }

    internal suspend fun extractPlaylistId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return trimmed

        val idRegex = Regex("(?:/playlist(?:_detail)?/|[?&](?:pid|id)=)(\\d+)", RegexOption.IGNORE_CASE)
        idRegex.find(trimmed)?.let { return it.groupValues[1] }

        val urlMatch = extractFirstHttpUrl(trimmed) ?: return null
        val req = Request.Builder()
            .url(urlMatch)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        return suspendRunCatching {
            httpClient.newCall(req).awaitResponse().use { resp ->
                val finalUrl = resp.request.url.toString()
                idRegex.find(finalUrl)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    private fun parseMinfoQualities(minfo: String?): List<QualityInfo> {
        if (minfo.isNullOrBlank()) return listOf(QualityInfo("128k"), QualityInfo("320k"))
        val seen = mutableSetOf<String>()
        val result = mutableListOf<QualityInfo>()
        minfo.split(";").forEach { segment ->
            val m = minfoRegex.find(segment) ?: return@forEach
            val bitrate = m.groupValues[2]
            val size = m.groupValues[4].uppercase(Locale.ROOT)
            val type = when (bitrate) {
                "4000" -> "flac24bit"
                "2000" -> "flac"
                "320" -> "320k"
                "128" -> "128k"
                else -> null
            }
            if (type != null && seen.add(type)) {
                result.add(QualityInfo(type, size))
            }
        }
        return result.ifEmpty { listOf(QualityInfo("128k")) }
    }

    private suspend fun getString(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        return httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
    }
}
