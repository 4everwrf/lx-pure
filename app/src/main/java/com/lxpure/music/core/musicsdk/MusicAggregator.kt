package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.ParsedLyrics
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import kotlin.math.abs

class MusicAggregator(httpClient: OkHttpClient) {
    companion object {
        /**
         * Platform priority tuned by real-world playback success rate across community LX sources:
         * WY (4) > TX (3) > KW (2) > KG (1) > MG (0)
         */
        fun platformPriority(source: MusicSource): Int = when (source) {
            MusicSource.LOCAL -> 5
            MusicSource.WY -> 4
            MusicSource.TX -> 3
            MusicSource.KW -> 2
            MusicSource.KG -> 1
            MusicSource.MG -> 0
        }

        /**
         * Picks the primary track and orders alternate platform tracks by real playback reliability first,
         * then cover availability, then quality richness.
         */
        fun selectPrimaryAndAlternates(group: List<Track>): Track {
            val sorted = group.sortedWith(
                compareByDescending<Track> { platformPriority(it.source) }
                    .thenByDescending { !it.coverUrl.isNullOrBlank() }
                    .thenByDescending { it.qualities.size }
            )
            val primary = sorted.first()
            return primary.copy(alternates = sorted.drop(1))
        }
    }

    val netEase = NetEaseProvider(httpClient)
    val qq = QqMusicProvider(httpClient)
    val kuwo = KuwoProvider(httpClient)
    val kugou = KugouProvider(httpClient)

    suspend fun searchSinglePlatform(source: MusicSource, query: String, page: Int = 1, limit: Int = 25): List<Track> =
        when (source) {
            MusicSource.WY -> netEase.search(query, page, limit)
            MusicSource.TX -> qq.search(query, page, limit)
            MusicSource.KW -> kuwo.search(query, page, limit)
            MusicSource.KG -> kugou.search(query, page, limit)
            else -> emptyList()
        }

    /**
     * Queries WY, TX, KW, KG concurrently and deduplicates identical tracks while preserving
     * alternate platform tracks on the primary item for instant silent fallback.
     */
    suspend fun searchAggregated(query: String, limitPerPlatform: Int = 15): List<Track> = coroutineScope {
        val platforms = listOf(MusicSource.WY, MusicSource.TX, MusicSource.KW, MusicSource.KG)
        val batches = platforms.map { platform ->
            async { runCatching { searchSinglePlatform(platform, query, 1, limitPerPlatform) }.getOrDefault(emptyList()) }
        }.awaitAll()

        // Interleave by rank so top matches from all platforms surface fairly
        val interleaved = mutableListOf<Track>()
        val maxLen = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            batches.forEach { batch ->
                if (i < batch.size) interleaved.add(batch[i])
            }
        }

        val grouped = LinkedHashMap<String, MutableList<Track>>()
        interleaved.forEach { track ->
            val key = normalizeKey(track.title, track.artist)
            grouped.getOrPut(key) { mutableListOf() }.add(track)
        }

        grouped.values.map { group -> selectPrimaryAndAlternates(group) }
    }

    /**
     * Searches other platforms for equivalent tracks to enable silent cross-platform fallback.
     */
    suspend fun findAlternativeTracks(track: Track, allowedPlatformCodes: Set<String>): List<Track> = coroutineScope {
        val targetPlatforms = listOf(MusicSource.WY, MusicSource.TX, MusicSource.KW, MusicSource.KG)
            .filter { it != track.source && it.code in allowedPlatformCodes }
        if (targetPlatforms.isEmpty()) return@coroutineScope emptyList()

        val query = "${track.title} ${track.artist.split("/").firstOrNull().orEmpty()}".trim()
        val fTitle = normalizeText(track.title)
        val fArtist = normalizeText(track.artist)

        val candidates = targetPlatforms.map { platform ->
            async { runCatching { searchSinglePlatform(platform, query, 1, 10) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        candidates.filter { candidate ->
            val cTitle = normalizeText(candidate.title)
            val cArtist = normalizeText(candidate.artist)
            val titleMatch = cTitle == fTitle || cTitle.contains(fTitle) || fTitle.contains(cTitle)
            val artistMatch = fArtist.isEmpty() || cArtist.contains(fArtist) || fArtist.contains(cArtist)
            val durationMatch = track.durationMs <= 0L || candidate.durationMs <= 0L ||
                abs(track.durationMs - candidate.durationMs) <= 7_000L
            titleMatch && artistMatch && durationMatch
        }.sortedWith(
            compareBy<Track> { if (normalizeText(it.title) == fTitle) 0 else 1 }
                .thenByDescending { platformPriority(it.source) }
                .thenBy { candidate ->
                    if (track.durationMs > 0L && candidate.durationMs > 0L) {
                        abs(track.durationMs - candidate.durationMs)
                    } else {
                        3000L
                    }
                }
        )
    }

    suspend fun getHotPlaylists(source: MusicSource, page: Int = 1): List<OnlinePlaylist> = when (source) {
        MusicSource.WY -> netEase.getHotPlaylists(page)
        MusicSource.TX -> qq.getHotPlaylists(page)
        MusicSource.KW -> kuwo.getHotPlaylists(page)
        else -> netEase.getHotPlaylists(page)
    }

    fun getLeaderboards(source: MusicSource): List<LeaderboardItem> = when (source) {
        MusicSource.WY -> netEase.leaderboards
        MusicSource.TX -> qq.leaderboards
        MusicSource.KW -> kuwo.leaderboards
        else -> netEase.leaderboards
    }

    suspend fun getPlaylistDetail(source: MusicSource, idOrUrl: String): Result<PlaylistDetailResult> = when (source) {
        MusicSource.WY -> netEase.getPlaylistDetail(idOrUrl)
        MusicSource.TX -> qq.getPlaylistDetail(idOrUrl)
        MusicSource.KW -> kuwo.getPlaylistDetail(idOrUrl)
        else -> Result.failure(IllegalArgumentException("暂不支持该平台歌单详情"))
    }

    suspend fun getLeaderboardDetail(board: LeaderboardItem): Result<PlaylistDetailResult> = when (board.source) {
        MusicSource.WY -> netEase.getPlaylistDetail(board.bangId)
        MusicSource.TX -> qq.getLeaderboardDetail(board)
        MusicSource.KW -> kuwo.getLeaderboardDetail(board)
        else -> Result.failure(IllegalArgumentException("暂不支持该平台榜单"))
    }

    /**
     * Auto-detects platform from any pasted share copy or playlist URL (NetEase / QQ / Kuwo).
     */
    suspend fun importExternalPlaylist(rawInput: String, preferredSource: MusicSource? = null): Result<PlaylistDetailResult> {
        val text = rawInput.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("请粘贴歌单链接或歌单 ID"))

        val detected = when {
            text.contains("163.com", ignoreCase = true) || text.contains("163cn.tv", ignoreCase = true) -> MusicSource.WY
            text.contains("qq.com", ignoreCase = true) -> MusicSource.TX
            text.contains("kuwo.cn", ignoreCase = true) -> MusicSource.KW
            preferredSource != null -> preferredSource
            else -> MusicSource.WY
        }
        return getPlaylistDetail(detected, text)
    }

    suspend fun fetchLyrics(track: Track): ParsedLyrics {
        val direct = when (track.source) {
            MusicSource.WY -> netEase.getLyric(track.songmid)
            MusicSource.TX -> qq.getLyric(track.songmid)
            MusicSource.KW -> kuwo.getLyric(track.songmid)
            MusicSource.KG -> kugou.getLyric(track)
            else -> ParsedLyrics()
        }
        if (!direct.isEmpty) return direct

        // Fallback lyric lookup via NetEase search (great for local files or tracks lacking lyrics)
        val matched = runCatching {
            netEase.search("${track.title} ${track.artist}".trim(), 1, 3).firstOrNull()
        }.getOrNull() ?: return ParsedLyrics()
        return netEase.getLyric(matched.songmid)
    }

    suspend fun resolveCoverIfNeeded(track: Track): String? {
        if (!track.coverUrl.isNullOrBlank()) return track.coverUrl
        if (track.source == MusicSource.KW) {
            return kuwo.resolveCover(track.songmid)
        }
        return null
    }

    private fun normalizeText(str: String): String =
        str.lowercase()
            .replace(Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\[\\]!！·]"), "")

    private fun normalizeKey(title: String, artist: String): String {
        val firstArtist = artist.split("/", "、", ",", "，", "&").firstOrNull().orEmpty()
        return "${normalizeText(title)}_${normalizeText(firstArtist)}"
    }
}
