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
import java.util.Locale
import kotlin.math.abs

class MusicAggregator(httpClient: OkHttpClient) {
    companion object {
        private val variantMarkers = listOf(
            "伴奏", "纯音乐", "instrumental", "dj", "remix", "live", "现场", "cover", "翻唱", "ktv", "伴唱"
        )
        private val artistSplitRegex = Regex("[/、,，&]")
        private val stripCharsRegex = Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\[\\]!！·]")

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
         * preserving original search rank within each platform so rank #1 is never demoted by an obscure remaster.
         */
        fun selectPrimaryAndAlternates(group: List<Track>): Track {
            val bestPerPlatform = group.distinctBy { it.source }
            val sorted = bestPerPlatform.sortedWith(
                compareByDescending<Track> { platformPriority(it.source) }
                    .thenByDescending { !it.coverUrl.isNullOrBlank() }
                    .thenByDescending { it.qualities.size }
            )
            val primary = sorted.first()
            return primary.copy(alternates = sorted.drop(1))
        }

        internal fun normalizeText(str: String): String =
            str.lowercase(Locale.ROOT).replace(stripCharsRegex, "")

        internal fun artistTokens(artist: String): Set<String> =
            artist.split(artistSplitRegex)
                .map { normalizeText(it) }
                .filter { it.isNotEmpty() && it != "未知歌手" && it != "unknown" }
                .toSet()

        internal fun hasVariantMismatch(titleA: String, titleB: String): Boolean {
            val a = titleA.lowercase(Locale.ROOT)
            val b = titleB.lowercase(Locale.ROOT)
            return variantMarkers.any { marker -> a.contains(marker) != b.contains(marker) }
        }

        internal fun isTrackMatch(source: Track, candidate: Track, maxDurationDiffMs: Long = 7_000L): Boolean {
            val fTitle = normalizeText(source.title)
            val cTitle = normalizeText(candidate.title)
            if (fTitle.isEmpty() || cTitle.isEmpty()) return false
            if (hasVariantMismatch(source.title, candidate.title)) return false

            val titleMatch = if (fTitle.length <= 1 || cTitle.length <= 1) {
                fTitle == cTitle
            } else {
                cTitle == fTitle || cTitle.contains(fTitle) || fTitle.contains(cTitle)
            }
            if (!titleMatch) return false

            val fTokens = artistTokens(source.artist)
            val cTokens = artistTokens(candidate.artist)
            val artistMatch = if (fTokens.isEmpty() || cTokens.isEmpty()) {
                true
            } else {
                fTokens.any { ft -> cTokens.any { ct -> ct == ft || ct.contains(ft) || ft.contains(ct) } }
            }
            if (!artistMatch) return false

            return source.durationMs <= 0L || candidate.durationMs <= 0L ||
                abs(source.durationMs - candidate.durationMs) <= maxDurationDiffMs
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
            async { suspendRunCatching { searchSinglePlatform(platform, query, 1, limitPerPlatform) }.getOrDefault(emptyList()) }
        }.awaitAll()

        // Interleave by rank so top matches from all platforms surface fairly
        val interleaved = mutableListOf<Track>()
        val maxLen = batches.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) {
            batches.forEach { batch ->
                if (i < batch.size) interleaved.add(batch[i])
            }
        }

        // Group only truly equivalent cross-platform tracks (never collapsing distinct durations or same-platform tracks)
        val groups = mutableListOf<MutableList<Track>>()
        val keyBuckets = HashMap<String, MutableList<MutableList<Track>>>()

        interleaved.forEach { track ->
            val key = normalizeKey(track.title, track.artist)
            val buckets = keyBuckets.getOrPut(key) { mutableListOf() }
            val matchingGroup = buckets.firstOrNull { group ->
                val head = group.first()
                group.none { it.source == track.source } &&
                    !hasVariantMismatch(head.title, track.title) &&
                    (head.durationMs <= 0L || track.durationMs <= 0L || abs(head.durationMs - track.durationMs) <= 12_000L)
            }
            if (matchingGroup != null) {
                matchingGroup.add(track)
            } else {
                val newGroup = mutableListOf(track)
                buckets.add(newGroup)
                groups.add(newGroup)
            }
        }

        groups.map { group -> selectPrimaryAndAlternates(group) }
    }

    /**
     * Searches other platforms for equivalent tracks to enable silent cross-platform fallback.
     */
    suspend fun findAlternativeTracks(track: Track, allowedPlatformCodes: Set<String>): List<Track> = coroutineScope {
        val targetPlatforms = listOf(MusicSource.WY, MusicSource.TX, MusicSource.KW, MusicSource.KG)
            .filter { it != track.source && it.code in allowedPlatformCodes }
        if (targetPlatforms.isEmpty()) return@coroutineScope emptyList()

        val fTitle = normalizeText(track.title)
        if (fTitle.isEmpty()) return@coroutineScope emptyList()

        val primaryArtist = track.artist.split(artistSplitRegex)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "未知歌手" }
            .orEmpty()
        val query = "${track.title} $primaryArtist".trim()

        val candidates = targetPlatforms.map { platform ->
            async { suspendRunCatching { searchSinglePlatform(platform, query, 1, 10) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()

        candidates.filter { candidate ->
            isTrackMatch(track, candidate)
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

        // Fallback lyric lookup via NetEase search (guarded by title/artist/duration match)
        val primaryArtist = track.artist.split(artistSplitRegex)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "未知歌手" }
            .orEmpty()
        val query = "${track.title} $primaryArtist".trim()
        if (query.isEmpty()) return ParsedLyrics()

        val candidates = suspendRunCatching {
            netEase.search(query, 1, 5)
        }.getOrDefault(emptyList())
        val matched = candidates.firstOrNull { cand ->
            !(track.source == MusicSource.WY && cand.songmid == track.songmid) &&
                isTrackMatch(track, cand, maxDurationDiffMs = 10_000L)
        } ?: return ParsedLyrics()
        return netEase.getLyric(matched.songmid)
    }

    suspend fun resolveCoverIfNeeded(track: Track): String? {
        if (!track.coverUrl.isNullOrBlank()) return track.coverUrl
        if (track.source == MusicSource.KW) {
            return kuwo.resolveCover(track.songmid)
        }
        return null
    }

    private fun normalizeKey(title: String, artist: String): String {
        val sortedArtists = artistTokens(artist).sorted().joinToString("_")
        return "${normalizeText(title)}_$sortedArtists"
    }
}
