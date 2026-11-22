package com.lxpure.music.core.localmedia

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class LocalMusicScanner(private val context: Context) {
    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        _isScanning.value = true
        val results = mutableListOf<Track>()
        runCatching {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000"
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                val albumArtBase = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol).orEmpty().ifBlank { "未知曲目" }
                    val rawArtist = cursor.getString(artistCol).orEmpty()
                    val artist = if (rawArtist.isBlank() || rawArtist == "<unknown>") "未知歌手" else rawArtist
                    val rawAlbum = cursor.getString(albumCol).orEmpty()
                    val album = if (rawAlbum == "<unknown>") "" else rawAlbum
                    val albumId = cursor.getLong(albumIdCol)
                    val duration = cursor.getLong(durationCol)

                    val contentUri = ContentUris.withAppendedId(collection, id).toString()
                    val coverUri = if (albumId > 0L) ContentUris.withAppendedId(albumArtBase, albumId).toString() else null

                    results.add(
                        Track(
                            id = "local_$id",
                            title = title,
                            artist = artist,
                            album = album,
                            source = MusicSource.LOCAL,
                            songmid = id.toString(),
                            durationMs = duration,
                            coverUrl = coverUri,
                            albumId = albumId.toString(),
                            localUri = contentUri
                        )
                    )
                }
            }
        }
        _localTracks.value = results
        _isScanning.value = false
        results
    }
}
