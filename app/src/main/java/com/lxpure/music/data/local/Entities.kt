package com.lxpure.music.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.Track
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val roomJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackId: String,
    val trackJson: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toTrack(): Track = roomJson.decodeFromString(trackJson)

    companion object {
        fun fromTrack(track: Track) = FavoriteEntity(
            trackId = track.id,
            trackJson = roomJson.encodeToString(track)
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverUrl: String? = null,
    val description: String = "",
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: String,
    val trackJson: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toTrack(): Track = roomJson.decodeFromString(trackJson)
}

@Entity(tableName = "play_history")
data class HistoryEntity(
    @PrimaryKey val trackId: String,
    val trackJson: String,
    val playedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1
) {
    fun toTrack(): Track = roomJson.decodeFromString(trackJson)
}

@Entity(tableName = "user_apis")
data class UserApiEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
    val script: String,
    val sourceUrl: String?,
    val enabled: Boolean,
    val priority: Int,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val trackJson: String,
    val quality: String,
    val status: String,
    val progress: Float,
    val filePath: String?,
    val totalBytes: Long,
    val errorMessage: String?,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toTrack(): Track = roomJson.decodeFromString<Track>(trackJson).copy(cachedFilePath = filePath)
    fun toQuality(): AudioQuality = AudioQuality.fromLxType(quality)
    fun toStatus(): DownloadStatus = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED)
}

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val timestamp: Long = System.currentTimeMillis()
)
