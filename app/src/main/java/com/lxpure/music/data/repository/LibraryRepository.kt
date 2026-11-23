package com.lxpure.music.data.repository

import com.lxpure.music.data.local.AppDatabase
import com.lxpure.music.data.local.FavoriteEntity
import com.lxpure.music.data.local.HistoryEntity
import com.lxpure.music.data.local.PlaylistEntity
import com.lxpure.music.data.local.PlaylistTrackEntity
import com.lxpure.music.data.local.SearchHistoryEntity
import com.lxpure.music.data.local.roomJson
import com.lxpure.music.domain.model.DownloadRecord
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

sealed class CreatePlaylistResult {
    data class Created(val playlistId: Long) : CreatePlaylistResult()
    data class LimitReached(val maxAllowed: Int) : CreatePlaylistResult()
}

data class AddTracksResult(
    val addedCount: Int,
    val duplicateCount: Int
)

class LibraryRepository(private val db: AppDatabase) {

    companion object {
        const val MAX_CUSTOM_PLAYLISTS = 10

        fun isCustomPlaylist(sourceUrl: String?): Boolean = sourceUrl.isNullOrBlank()

        fun customPlaylistCount(playlists: List<UserPlaylist>): Int =
            playlists.count { isCustomPlaylist(it.sourceUrl) }

        fun canCreateCustomPlaylist(playlists: List<UserPlaylist>): Boolean =
            customPlaylistCount(playlists) < MAX_CUSTOM_PLAYLISTS

        /**
         * Computes the starting `position` value for inserting [insertCount] tracks.
         * Prepending assigns strictly smaller negative positions (`minPosition - insertCount`)
         * so `ORDER BY position ASC` places newly added songs at #1 in O(1) without rewriting rows.
         */
        fun computeInsertStartPosition(
            minPosition: Int,
            maxPosition: Int,
            insertCount: Int,
            prepend: Boolean
        ): Int = if (prepend) minPosition - insertCount else maxPosition + 1

        /**
         * Filters out tracks already present in a playlist (and duplicates within [tracksToAdd]),
         * preserving insertion order and returning both the unique additions and duplicate count.
         */
        fun filterNewTracksForPlaylist(
            existingTrackIds: Set<String>,
            tracksToAdd: List<Track>
        ): Pair<List<Track>, Int> {
            val seen = HashSet(existingTrackIds)
            val uniqueNew = mutableListOf<Track>()
            var duplicateCount = 0
            tracksToAdd.forEach { track ->
                if (seen.add(track.id)) {
                    uniqueNew.add(track)
                } else {
                    duplicateCount++
                }
            }
            return uniqueNew to duplicateCount
        }
    }

    val favorites: Flow<List<Track>> = db.favoriteDao().observeAll().map { list ->
        list.map { it.toTrack() }
    }

    val favoriteIds: Flow<Set<String>> = db.favoriteDao().observeIds().map { it.toSet() }

    val recentHistory: Flow<List<Track>> = db.historyDao().observeRecent().map { list ->
        list.map { it.toTrack() }
    }

    val searchHistory: Flow<List<String>> = db.searchHistoryDao().observeRecent().map { list ->
        list.map { it.query }
    }

    val downloads: Flow<List<DownloadRecord>> = db.downloadDao().observeAll().map { list ->
        list.map {
            DownloadRecord(
                trackId = it.trackId,
                track = it.toTrack(),
                quality = it.toQuality(),
                status = it.toStatus(),
                progress = it.progress,
                filePath = it.filePath,
                totalBytes = it.totalBytes,
                errorMessage = it.errorMessage,
                updatedAt = it.updatedAt
            )
        }
    }

    val userPlaylists: Flow<List<UserPlaylist>> = combine(
        db.playlistDao().observePlaylists(),
        db.playlistDao().observeAllTrackCounts()
    ) { playlists, counts ->
        val countMap = counts.associate { it.playlistId to it.count }
        playlists.map {
            UserPlaylist(
                id = it.id,
                name = it.name,
                coverUrl = it.coverUrl,
                description = it.description,
                sourceUrl = it.sourceUrl,
                trackCount = countMap[it.id] ?: 0,
                createdAt = it.createdAt
            )
        }
    }

    suspend fun toggleFavorite(track: Track): Boolean {
        val exists = db.favoriteDao().isFavorite(track.id)
        if (exists) {
            db.favoriteDao().delete(track.id)
            return false
        } else {
            db.favoriteDao().upsert(FavoriteEntity.fromTrack(track))
            return true
        }
    }

    suspend fun recordPlay(track: Track) {
        val existing = db.historyDao().getById(track.id)
        db.historyDao().upsert(
            HistoryEntity(
                trackId = track.id,
                trackJson = roomJson.encodeToString(track),
                playedAt = System.currentTimeMillis(),
                playCount = (existing?.playCount ?: 0) + 1
            )
        )
    }

    suspend fun clearHistory() = db.historyDao().clear()

    suspend fun createPlaylist(
        name: String,
        description: String = "",
        coverUrl: String? = null,
        sourceUrl: String? = null,
        tracks: List<Track> = emptyList()
    ): CreatePlaylistResult = withContext(Dispatchers.IO) {
        if (isCustomPlaylist(sourceUrl) && db.playlistDao().countCustomPlaylists() >= MAX_CUSTOM_PLAYLISTS) {
            return@withContext CreatePlaylistResult.LimitReached(MAX_CUSTOM_PLAYLISTS)
        }
        val cover = coverUrl ?: tracks.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
        val id = db.playlistDao().insert(
            PlaylistEntity(
                name = name.trim().ifBlank { "未命名歌单" },
                coverUrl = cover,
                description = description,
                sourceUrl = sourceUrl
            )
        )
        if (tracks.isNotEmpty()) {
            addTracksToPlaylist(id, tracks, prepend = false)
        }
        CreatePlaylistResult.Created(id)
    }

    /**
     * Adds unique tracks to a playlist.
     * When [prepend] is true (default for manually adding songs from the player or track menu),
     * newly added songs are placed at the very top of the playlist using O(1) negative positions
     * without rewriting existing rows. When [prepend] is false (importing an external playlist),
     * original track order is preserved.
     */
    suspend fun addTracksToPlaylist(
        playlistId: Long,
        tracks: List<Track>,
        prepend: Boolean = true
    ): AddTracksResult = withContext(Dispatchers.IO) {
        val existingIds = db.playlistDao().getTrackIdsOnce(playlistId).toHashSet()
        val (uniqueNew, duplicateCount) = filterNewTracksForPlaylist(existingIds, tracks)
        if (uniqueNew.isNotEmpty()) {
            val startPosition = computeInsertStartPosition(
                minPosition = if (prepend) db.playlistDao().getMinPosition(playlistId) else 0,
                maxPosition = if (prepend) 0 else db.playlistDao().getMaxPosition(playlistId),
                insertCount = uniqueNew.size,
                prepend = prepend
            )
            val entities = uniqueNew.mapIndexed { idx, track ->
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    trackJson = roomJson.encodeToString(track),
                    position = startPosition + idx
                )
            }
            // Batch SQLite inserts to support large 2,400–10,000+ track playlists cleanly
            entities.chunked(500).forEach { batch ->
                db.playlistDao().insertTracks(batch)
            }
            val playlist = db.playlistDao().getById(playlistId)
            if (playlist != null && playlist.coverUrl.isNullOrBlank()) {
                val cover = uniqueNew.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
                if (cover != null) {
                    db.playlistDao().updateMeta(playlistId, playlist.name, cover)
                }
            }
        }
        AddTracksResult(addedCount = uniqueNew.size, duplicateCount = duplicateCount)
    }

    fun observePlaylistIdsForTrack(trackId: String): Flow<Set<Long>> =
        db.playlistDao().observePlaylistIdsContainingTrack(trackId).map { it.toSet() }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        db.playlistDao().removeTrack(playlistId, trackId)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        db.playlistDao().clearPlaylistTracks(playlistId)
        db.playlistDao().deletePlaylist(playlistId)
    }

    suspend fun getPlaylistMeta(playlistId: Long): PlaylistEntity? = db.playlistDao().getById(playlistId)

    fun observePlaylistTracks(playlistId: Long): Flow<List<Track>> =
        db.playlistDao().observeTracks(playlistId).map { list -> list.map { it.toTrack() } }

    suspend fun saveSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            db.searchHistoryDao().upsert(SearchHistoryEntity(trimmed))
        }
    }

    suspend fun deleteSearchQuery(query: String) = db.searchHistoryDao().delete(query)

    suspend fun clearSearchHistory() = db.searchHistoryDao().clear()
}
