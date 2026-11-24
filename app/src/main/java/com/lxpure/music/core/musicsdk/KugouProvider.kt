package com.lxpure.music.core.musicsdk

import com.lxpure.music.core.userapi.LxCrypto
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.QualityInfo
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class KugouProvider(private val httpClient: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(query: String, page: Int = 1, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=$encoded&page=$page&pagesize=$limit&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val body = httpClient.newCall(req).awaitResponse().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@suspendRunCatching emptyList()
            val root = json.parseToJsonElement(body).asJsonObjectOrNull() ?: return@suspendRunCatching emptyList()
            if (root["error_code"].asIntOrNull() != 0) return@suspendRunCatching emptyList()
            val list = root["data"].asJsonObjectOrNull()?.get("lists").asJsonArrayOrNull()
                ?: return@suspendRunCatching emptyList()

            list.mapNotNull { el ->
                runCatching {
                    val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                    val hash = obj["FileHash"].asNonBlankStringOrNull() ?: return@mapNotNull null
                    val audioId = obj["Audioid"].asNonBlankStringOrNull() ?: hash
                    val baseName = FormatUtils.decodeHtml(obj["OriSongName"].asNonBlankStringOrNull() ?: obj["SongName"].asNonBlankStringOrNull())
                        .ifBlank { return@mapNotNull null }
                    val suffix = FormatUtils.decodeHtml(obj["Suffix"].asStringOrNull())
                    val title = if (suffix.isNotBlank()) "$baseName $suffix" else baseName
                    val singers = obj["Singers"].asJsonArrayOrNull()?.mapNotNull {
                        it.asJsonObjectOrNull()?.get("name").asNonBlankStringOrNull()?.let { n -> FormatUtils.decodeHtml(n) }
                    }?.joinToString(" / ").orEmpty()
                        .ifBlank { FormatUtils.decodeHtml(obj["SingerName"].asStringOrNull()) }
                        .ifBlank { "未知歌手" }

                    val qualities = mutableListOf<QualityInfo>()
                    val fSize = obj["FileSize"].asLongOrNull() ?: 0L
                    val hqSize = obj["HQFileSize"].asLongOrNull() ?: 0L
                    val sqSize = obj["SQFileSize"].asLongOrNull() ?: 0L
                    val resSize = obj["ResFileSize"].asLongOrNull() ?: 0L
                    val hqHash = obj["HQFileHash"].asNonBlankStringOrNull()
                    val sqHash = obj["SQFileHash"].asNonBlankStringOrNull()
                    val resHash = obj["ResFileHash"].asNonBlankStringOrNull()
                    if (fSize > 0L) qualities.add(QualityInfo("128k", FormatUtils.formatSize(fSize), hash))
                    if (hqSize > 0L && hqHash != null) qualities.add(QualityInfo("320k", FormatUtils.formatSize(hqSize), hqHash))
                    if (sqSize > 0L && sqHash != null) qualities.add(QualityInfo("flac", FormatUtils.formatSize(sqSize), sqHash))
                    if (resSize > 0L && resHash != null) qualities.add(QualityInfo("flac24bit", FormatUtils.formatSize(resSize), resHash))
                    if (qualities.isEmpty()) qualities.add(QualityInfo("128k", "", hash))

                    val img = obj["Image"].asNonBlankStringOrNull()?.replace("{size}", "400")?.replace("http://", "https://")

                    Track(
                        id = "kg_${audioId}_$hash",
                        title = title,
                        artist = singers,
                        album = FormatUtils.decodeHtml(obj["AlbumName"].asStringOrNull()),
                        source = MusicSource.KG,
                        songmid = audioId,
                        durationMs = (obj["Duration"].asLongOrNull() ?: 0L) * 1000L,
                        coverUrl = img,
                        albumId = obj["AlbumID"].asStringOrNull(),
                        hash = hash,
                        qualities = qualities
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getLyric(track: Track): ParsedLyrics = withContext(Dispatchers.IO) {
        suspendRunCatching {
            val hash = track.hash?.takeIf { it.isNotBlank() } ?: return@suspendRunCatching ParsedLyrics()
            val durationMs = track.durationMs.coerceAtLeast(1000L)
            val keyword = URLEncoder.encode("${track.artist}-${track.title}", "UTF-8")
            val searchUrl = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=$keyword&duration=$durationMs&hash=$hash"
            val searchBody = httpClient.newCall(Request.Builder().url(searchUrl).build()).awaitResponse().use {
                if (it.isSuccessful) it.body?.string() else null
            } ?: return@suspendRunCatching ParsedLyrics()
            val candidate = json.parseToJsonElement(searchBody).asJsonObjectOrNull()
                ?.get("candidates").asJsonArrayOrNull()
                ?.firstOrNull().asJsonObjectOrNull() ?: return@suspendRunCatching ParsedLyrics()
            val id = candidate["id"].asNonBlankStringOrNull() ?: return@suspendRunCatching ParsedLyrics()
            val accessKey = candidate["accesskey"].asNonBlankStringOrNull() ?: return@suspendRunCatching ParsedLyrics()

            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val dlBody = httpClient.newCall(Request.Builder().url(dlUrl).build()).awaitResponse().use {
                if (it.isSuccessful) it.body?.string() else null
            } ?: return@suspendRunCatching ParsedLyrics()
            val b64 = json.parseToJsonElement(dlBody).asJsonObjectOrNull()?.get("content").asNonBlankStringOrNull()
                ?: return@suspendRunCatching ParsedLyrics()
            val decodedBytes = LxCrypto.decodeBase64Flexible(b64) ?: return@suspendRunCatching ParsedLyrics()
            val rawLrc = String(decodedBytes, StandardCharsets.UTF_8)
            LrcParser.parse(rawLrc)
        }.getOrDefault(ParsedLyrics())
    }
}
