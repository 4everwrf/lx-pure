package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.ArtistParser
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class QishuiProvider(private val httpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val pcUserAgent = "LunaPC/3.3.0(359450208)"

    companion object {
        private const val DEFAULT_COVER_HOST = "https://p3-luna.douyinpic.com/img/"
        private const val DEFAULT_TEMPLATE_PREFIX = "tplv-b829550vbb"
        private const val PLAYLIST_PAGE_SIZE = 100
        private const val MAX_PLAYLIST_PAGES = 5

        /**
         * Percent-encodes non-ASCII and whitespace characters in a URI path while preserving `/`
         * and safe ASCII path characters.
         */
        internal fun encodeUriSegments(uriPath: String): String {
            val cleaned = uriPath.trim().trimStart('/')
            if (cleaned.isEmpty()) return ""
            return cleaned.split('/').joinToString("/") { segment ->
                val sb = StringBuilder(segment.length)
                val bytes = segment.toByteArray(StandardCharsets.UTF_8)
                for (b in bytes) {
                    val c = b.toInt() and 0xFF
                    val isSafeAscii = (c in 'A'.code..'Z'.code) ||
                        (c in 'a'.code..'z'.code) ||
                        (c in '0'.code..'9'.code) ||
                        c == '-'.code || c == '_'.code || c == '.'.code ||
                        c == '~'.code || c == '%'.code
                    if (isSafeAscii) {
                        sb.append(c.toChar())
                    } else {
                        sb.append('%')
                        sb.append("%02X".format(Locale.US, c))
                    }
                }
                sb.toString()
            }
        }

        /**
         * Constructs valid Qishui cover URLs with `~{template_prefix}-resize:500:500.png`
         * (fallback `tplv-b829550vbb`), never emitting a bare `/img/{uri}` URL (which returns HTTP 400).
         */
        internal fun buildCoverUrl(coverObj: JsonObject?): String? {
            if (coverObj == null) return null
            val templatePrefix = coverObj["template_prefix"].asNonBlankStringOrNull() ?: DEFAULT_TEMPLATE_PREFIX
            val suffix = "~${templatePrefix}-resize:500:500.png"
            val rawUri = coverObj["uri"].asNonBlankStringOrNull()
            val urls = coverObj["urls"].asJsonArrayOrNull()
                ?.mapNotNull { it.asNonBlankStringOrNull() }
                .orEmpty()

            val firstHttpUrl = urls.firstOrNull {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true) ||
                    it.startsWith("//")
            }?.let { raw ->
                when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http://", ignoreCase = true) -> "https://" + raw.substring(7)
                    else -> raw
                }
            }

            if (rawUri != null) {
                val encodedUri = encodeUriSegments(rawUri)
                if (firstHttpUrl != null) {
                    if (firstHttpUrl.contains('~')) {
                        return encodeNonAsciiInUrl(firstHttpUrl)
                    }
                    val trimmed = firstHttpUrl.trimEnd('/')
                    return if (trimmed.endsWith("/img", ignoreCase = true) || !trimmed.contains("/img/", ignoreCase = true)) {
                        "$trimmed/$encodedUri$suffix"
                    } else {
                        "${encodeNonAsciiInUrl(trimmed)}$suffix"
                    }
                }
                return "$DEFAULT_COVER_HOST$encodedUri$suffix"
            }

            if (firstHttpUrl != null) {
                val trimmed = firstHttpUrl.trimEnd('/')
                if (trimmed.endsWith("/img", ignoreCase = true)) return null
                if (firstHttpUrl.contains('~')) return encodeNonAsciiInUrl(firstHttpUrl)
                return if (trimmed.contains("/img/", ignoreCase = true)) {
                    "${encodeNonAsciiInUrl(trimmed)}$suffix"
                } else {
                    encodeNonAsciiInUrl(firstHttpUrl)
                }
            }
            return null
        }

        private fun encodeNonAsciiInUrl(url: String): String {
            val sb = StringBuilder(url.length)
            for (ch in url) {
                if (ch.code in 0x21..0x7E) {
                    sb.append(ch)
                } else {
                    val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%')
                        sb.append("%02X".format(Locale.US, b.toInt() and 0xFF))
                    }
                }
            }
            return sb.toString()
        }

        internal fun extractPlaylistId(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) return trimmed
            val idRegex = Regex("(?:playlist_id=|/playlist/)(\\d+)", RegexOption.IGNORE_CASE)
            return idRegex.find(trimmed)?.groupValues?.getOrNull(1)
        }

        internal fun parseQualities(item: JsonObject): List<QualityInfo> {
            val bitRates = item["bit_rates"].asJsonArrayOrNull() ?: return emptyList()
            val mapped = LinkedHashMap<String, QualityInfo>()
            bitRates.forEach { el ->
                val obj = el.asJsonObjectOrNull() ?: return@forEach
                val rawQuality = obj["quality"].asNonBlankStringOrNull()?.lowercase(Locale.ROOT) ?: return@forEach
                val lxType = when (rawQuality) {
                    "medium", "higher" -> "128k"
                    "highest" -> "320k"
                    "lossless" -> "flac"
                    "hi_res", "hires" -> "flac24bit"
                    else -> null
                } ?: return@forEach
                val sizeBytes = obj["size"].asLongOrNull() ?: 0L
                val existing = mapped[lxType]
                if (existing == null || (existing.size.isEmpty() && sizeBytes > 0L)) {
                    mapped[lxType] = QualityInfo(lxType, FormatUtils.formatSize(sizeBytes))
                }
            }
            val order = listOf("128k", "320k", "flac", "flac24bit")
            return order.mapNotNull { mapped[it] }
        }
    }

    val leaderboards: List<LeaderboardItem> = listOf(
        LeaderboardItem("soda_rgb", "7036274230471712007", "热歌榜", "每周四更新 · 汽水", MusicSource.SODA, 0xFF10B981),
        LeaderboardItem("soda_xgb", "7060812597884869927", "新歌榜", "近期新歌 · 汽水", MusicSource.SODA, 0xFF38BDF8),
        LeaderboardItem("soda_omb", "7061475546400005410", "欧美榜", "热门外文 · 汽水", MusicSource.SODA, 0xFF8B5CF6),
        LeaderboardItem("soda_yyr", "7415959718721494311", "音乐人榜", "站内热度 · 汽水", MusicSource.SODA, 0xFFF59E0B)
    )

    suspend fun getHotPlaylists(page: Int = 1): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        val channels = if (page <= 1) listOf("0", "14") else listOf("8", "9")
        val batches = coroutineScope {
            channels.map { channel ->
                async {
                    suspendRunCatching { fetchDiscoverMixChannel(channel) }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val merged = LinkedHashMap<String, OnlinePlaylist>()
        batches.flatten().forEach { playlist ->
            if (playlist.trackCount > 0 && !merged.containsKey(playlist.id)) {
                merged[playlist.id] = playlist
            }
        }
        merged.values.toList()
    }

    private suspend fun fetchDiscoverMixChannel(subChannelId: String): List<OnlinePlaylist> {
        val payload = buildJsonObject {
            put("block_type", "discover_playlist_mix")
            put("sub_channel_id", subChannelId)
            put("count", 20)
        }.toString()

        val endpoints = listOf(
            "https://api.qishui.com/luna/pc/discover/mix?aid=386088&app_name=luna_pc&device_platform=windows",
            "https://beta-luna.douyin.com/luna/discover/mix"
        )
        for (url in endpoints) {
            val parsed = suspendRunCatching {
                val body = postJson(url, payload) ?: return@suspendRunCatching null
                val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching null
                parseDiscoverMixRoot(root)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseDiscoverMixRoot(root: JsonObject): List<OnlinePlaylist> {
        val blocks = root["inner_block"].asJsonArrayOrNull() ?: return emptyList()
        val results = mutableListOf<OnlinePlaylist>()
        val seenIds = HashSet<String>()
        blocks.forEach { blockEl ->
            val block = blockEl.asJsonObjectOrNull() ?: return@forEach
            val resources = block["resources"].asJsonArrayOrNull() ?: return@forEach
            resources.forEach { resEl ->
                val playlistObj = resEl.asJsonObjectOrNull()
                    ?.get("entity").asJsonObjectOrNull()
                    ?.get("playlist").asJsonObjectOrNull()
                    ?: return@forEach
                val playlist = parsePlaylistMeta(playlistObj) ?: return@forEach
                if (playlist.trackCount > 0 && seenIds.add(playlist.id)) {
                    results.add(playlist)
                }
            }
        }
        return results
    }

    suspend fun getPlaylistDetail(rawIdOrUrl: String): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val playlistId = extractPlaylistId(rawIdOrUrl) ?: error("无法识别汽水音乐歌单 ID")
            var cursor = ""
            val seenCursors = HashSet<String>()
            val seenTrackIds = HashSet<String>()
            val allTracks = mutableListOf<Track>()
            var meta: OnlinePlaylist? = null

            for (page in 0 until MAX_PLAYLIST_PAGES) {
                val root = fetchPlaylistDetailPage(playlistId, cursor, isFirstPage = page == 0)
                    ?: if (page == 0) error("获取汽水音乐歌单失败，请检查网络连接") else break

                if (meta == null) {
                    meta = root["playlist"].asJsonObjectOrNull()?.let { parsePlaylistMeta(it) }
                }

                val mediaResources = root["media_resources"].asJsonArrayOrNull().orEmpty()
                var addedThisPage = 0
                mediaResources.forEach { el ->
                    val entity = el.asJsonObjectOrNull()?.get("entity").asJsonObjectOrNull() ?: return@forEach
                    val trackObj = entity["track_wrapper"].asJsonObjectOrNull()?.get("track").asJsonObjectOrNull()
                        ?: entity["track"].asJsonObjectOrNull()
                        ?: return@forEach
                    val track = parseTrackObj(trackObj) ?: return@forEach
                    if (seenTrackIds.add(track.id)) {
                        allTracks.add(track)
                        addedThisPage++
                    }
                }

                val hasMore = root["has_more"].asBooleanOrNull() == true
                val nextCursor = root["next_cursor"].asNonBlankStringOrNull()
                if (!hasMore || nextCursor == null || addedThisPage == 0 || !seenCursors.add(nextCursor)) {
                    break
                }
                cursor = nextCursor
            }

            if (meta == null && allTracks.isEmpty()) {
                error("汽水音乐歌单不存在或暂无数据")
            }

            val finalMeta = (meta ?: OnlinePlaylist(id = playlistId, name = "汽水音乐歌单", source = MusicSource.SODA))
                .let { base ->
                    val fallbackCover = base.coverUrl.ifBlank {
                        allTracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl.orEmpty()
                    }
                    base.copy(coverUrl = fallbackCover, trackCount = allTracks.size)
                }
            PlaylistDetailResult(finalMeta, allTracks)
        }
    }

    private suspend fun fetchPlaylistDetailPage(
        playlistId: String,
        cursor: String,
        isFirstPage: Boolean
    ): JsonObject? {
        val encodedId = URLEncoder.encode(playlistId, "UTF-8")
        val encodedCursor = URLEncoder.encode(cursor, "UTF-8")
        val getUrl = "https://api.qishui.com/luna/pc/playlist/detail?playlist_id=$encodedId&cursor=$encodedCursor&count=$PLAYLIST_PAGE_SIZE&aid=386088&app_name=luna_pc&device_platform=windows"

        var primaryRoot: JsonObject? = null
        val getBody = suspendRunCatching { getString(getUrl) }.getOrNull()
        if (getBody != null) {
            primaryRoot = runCatching { json.parseToJsonElement(getBody).asJsonObjectOrNull() }.getOrNull()
        }
        val hasPrimaryData = primaryRoot != null && (
            !primaryRoot["media_resources"].asJsonArrayOrNull().isNullOrEmpty() ||
                (!isFirstPage && primaryRoot["playlist"].asJsonObjectOrNull() != null)
            )
        if (hasPrimaryData) return primaryRoot

        val postPayload = buildJsonObject {
            put("playlist_id", playlistId)
            put("cursor", cursor)
            put("count", PLAYLIST_PAGE_SIZE)
        }.toString()
        val postBody = suspendRunCatching {
            postJson("https://api.qishui.com/luna/playlist/detail", postPayload)
        }.getOrNull()
        val postRoot = postBody?.let {
            runCatching { json.parseToJsonElement(it).asJsonObjectOrNull() }.getOrNull()
        }
        if (postRoot != null && (
                !postRoot["media_resources"].asJsonArrayOrNull().isNullOrEmpty() ||
                    postRoot["playlist"].asJsonObjectOrNull() != null
                )
        ) {
            return postRoot
        }
        return primaryRoot ?: postRoot
    }

    suspend fun getLeaderboardDetail(board: LeaderboardItem): Result<PlaylistDetailResult> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val encodedId = URLEncoder.encode(board.bangId, "UTF-8")
            val endpoints = listOf(
                "https://api.qishui.com/luna/pc/charts/$encodedId?aid=386088&app_name=luna_pc&device_platform=windows",
                "https://api5-lf.qishui.com/luna/charts/$encodedId?charge=0"
            )
            var chartObj: JsonObject? = null
            var sawInvalidPayload = false
            for (url in endpoints) {
                val body = suspendRunCatching { getString(url) }.getOrNull() ?: continue
                val root = runCatching { json.parseToJsonElement(body).asJsonObjectOrNull() }.getOrNull()
                val chart = root?.get("chart").asJsonObjectOrNull()
                if (chart != null) {
                    chartObj = chart
                    break
                } else {
                    sawInvalidPayload = true
                }
            }

            val chart = chartObj ?: if (sawInvalidPayload) {
                error("汽水音乐榜单数据异常")
            } else {
                error("获取汽水音乐榜单失败，请检查网络连接")
            }

            val tracks = parseChartTracks(chart)
            val coverUrl = buildCoverUrl(chart["url_cover"].asJsonObjectOrNull())
                ?: tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl.orEmpty()
            val meta = OnlinePlaylist(
                id = board.bangId,
                name = chart["title"].asNonBlankStringOrNull() ?: board.name,
                coverUrl = coverUrl,
                author = "汽水音乐",
                playCount = "",
                description = chart["period"].asNonBlankStringOrNull() ?: board.subtitle,
                trackCount = tracks.size,
                source = MusicSource.SODA
            )
            PlaylistDetailResult(meta, tracks)
        }
    }

    internal fun parseChartTracks(chart: JsonObject): List<Track> {
        val ranks = chart["track_ranks"].asJsonArrayOrNull() ?: return emptyList()
        val seenIds = HashSet<String>()
        return ranks.mapNotNull { el ->
            runCatching {
                val trackObj = el.asJsonObjectOrNull()?.get("track").asJsonObjectOrNull() ?: return@mapNotNull null
                val track = parseTrackObj(trackObj) ?: return@mapNotNull null
                if (seenIds.add(track.id)) track else null
            }.getOrNull()
        }
    }

    suspend fun getLyric(songmid: String): ParsedLyrics = withContext(Dispatchers.IO) {
        if (songmid.isBlank()) return@withContext ParsedLyrics()
        suspendRunCatching {
            val encodedId = URLEncoder.encode(songmid, "UTF-8")
            val url = "https://beta-luna.douyin.com/luna/h5/seo_track?track_id=$encodedId&device_platform=web"
            val body = getString(url) ?: return@suspendRunCatching ParsedLyrics()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching ParsedLyrics()
            val lyricText = root["lyric"].asJsonObjectOrNull()?.get("content").asNonBlankStringOrNull()
                ?: return@suspendRunCatching ParsedLyrics()
            LrcParser.parse(lyricText)
        }.getOrDefault(ParsedLyrics())
    }

    internal fun parsePlaylistMeta(obj: JsonObject): OnlinePlaylist? {
        val id = obj["id"].asNonBlankStringOrNull() ?: return null
        val title = FormatUtils.decodeHtml(
            obj["title"].asNonBlankStringOrNull() ?: obj["public_title"].asNonBlankStringOrNull()
        ).ifBlank { return null }
        val ownerObj = obj["owner"].asJsonObjectOrNull()
        val author = FormatUtils.decodeHtml(
            ownerObj?.get("nickname").asNonBlankStringOrNull()
                ?: ownerObj?.get("public_name").asNonBlankStringOrNull()
                ?: obj["user_artist_info"].asJsonObjectOrNull()
                    ?.get("user_brief").asJsonObjectOrNull()
                    ?.get("nickname").asNonBlankStringOrNull()
        )
        val statsObj = obj["stats"].asJsonObjectOrNull()
        val playCountNum = statsObj?.get("count_played").asLongOrNull()
            ?: statsObj?.get("count_collected").asLongOrNull()
            ?: 0L
        val trackCount = obj["resource_cnt"].asJsonObjectOrNull()?.get("track_cnt").asIntOrNull()
            ?: obj["count_tracks"].asIntOrNull()
            ?: statsObj?.get("count_visible").asIntOrNull()
            ?: 0

        return OnlinePlaylist(
            id = id,
            name = title,
            coverUrl = buildCoverUrl(obj["url_cover"].asJsonObjectOrNull()).orEmpty(),
            author = author,
            playCount = if (playCountNum > 0L) FormatUtils.formatPlayCount(playCountNum) else "",
            description = FormatUtils.decodeHtml(obj["desc"].asStringOrNull()),
            trackCount = trackCount,
            source = MusicSource.SODA
        )
    }

    internal fun parseTrackObj(item: JsonObject): Track? {
        val trackId = item["id"].asNonBlankStringOrNull() ?: return null
        val title = FormatUtils.decodeHtml(item["name"].asNonBlankStringOrNull()).ifBlank { return null }

        val rawArtists = item["artists"].asJsonArrayOrNull()
            ?.mapNotNull { el ->
                val artistObj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                FormatUtils.decodeHtml(
                    artistObj["name"].asNonBlankStringOrNull()
                        ?: artistObj["simple_display_name"].asNonBlankStringOrNull()
                ).takeIf { it.isNotBlank() }
            }
            .orEmpty()
        val artistDisplay = rawArtists.joinToString(" / ").ifBlank { "未知歌手" }
        val artistNames = if (rawArtists.isNotEmpty()) {
            rawArtists
        } else {
            ArtistParser.parseRawString(artistDisplay)
        }

        val albumObj = item["album"].asJsonObjectOrNull()
        val albumName = FormatUtils.decodeHtml(albumObj?.get("name").asStringOrNull())
        val albumId = albumObj?.get("id").asNonBlankStringOrNull()
        val coverUrl = buildCoverUrl(albumObj?.get("url_cover").asJsonObjectOrNull())
            ?: buildCoverUrl(item["url_cover"].asJsonObjectOrNull())
        val durationMs = item["duration"].asLongOrNull()?.coerceAtLeast(0L) ?: 0L

        return Track(
            id = "soda_$trackId",
            title = title,
            artist = artistDisplay,
            album = albumName,
            source = MusicSource.SODA,
            songmid = trackId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            albumId = albumId,
            qualities = parseQualities(item),
            artistNames = artistNames
        )
    }

    private suspend fun getString(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", pcUserAgent)
            .build()
        return httpClient.newCall(req).awaitResponse().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private suspend fun postJson(url: String, payload: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", pcUserAgent)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        return httpClient.newCall(req).awaitResponse().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }
}
