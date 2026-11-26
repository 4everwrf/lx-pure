package com.lxpure.music.core.localmedia

import android.content.ContentUris
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse
import com.lxpure.music.domain.model.ArtistParser
import com.lxpure.music.domain.model.MusicSource
import com.lxpure.music.domain.model.Track
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalMusicScanner(private val context: Context) {
    private val scanMutex = Mutex()

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            _isScanning.value = true
            val cancelSignal = CancellationSignal()
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cancelSignal.cancel() }
            try {
                val results = mutableListOf<Track>()
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION
                )
                val selection =
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 15000 AND ${MediaStore.Audio.Media.SIZE} > 0"
                val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

                val cursor = context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    null,
                    sortOrder,
                    cancelSignal
                ) ?: return@withLock _localTracks.value

                cursor.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol < 0) return@withLock _localTracks.value
                    val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val displayNameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

                    val albumArtBase = Uri.parse("content://media/external/audio/albumart")

                    while (c.moveToNext()) {
                        ensureActive()
                        val id = c.getLong(idCol)
                        val rawTitle = if (titleCol >= 0) c.getString(titleCol).orEmpty().trim() else ""
                        val displayFallback = if (displayNameCol >= 0) {
                            c.getString(displayNameCol).orEmpty().trim().substringBeforeLast('.').trim()
                        } else {
                            ""
                        }
                        val title = rawTitle.ifBlank { displayFallback.ifBlank { "未知曲目" } }
                        val rawArtist = if (artistCol >= 0) c.getString(artistCol).orEmpty().trim() else ""
                        val artist = if (ArtistParser.isUnknownPlaceholder(rawArtist)) "未知歌手" else rawArtist
                        val rawAlbum = if (albumCol >= 0) c.getString(albumCol).orEmpty().trim() else ""
                        val album = if (rawAlbum.equals("<unknown>", ignoreCase = true)) "" else rawAlbum
                        val albumId = if (albumIdCol >= 0) c.getLong(albumIdCol) else 0L
                        val duration = if (durationCol >= 0) c.getLong(durationCol).coerceAtLeast(0L) else 0L

                        val contentUri = ContentUris.withAppendedId(collection, id).toString()
                        // Android 10+ (API 29+) deprecates undocumented audio/albumart URI; load per-track embedded art via ContentResolver.loadThumbnail
                        val coverUri = when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> contentUri
                            albumId > 0L -> ContentUris.withAppendedId(albumArtBase, albumId).toString()
                            else -> null
                        }

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
                _localTracks.value = results
                results
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("LocalMusicScanner", "Local media scan failed: ${t.message}")
                _localTracks.value
            } finally {
                cancelHandle?.dispose()
                _isScanning.value = false
            }
        }
    }
}

/**
 * Coil [Fetcher] that loads embedded per-file album art for MediaStore audio content URIs
 * on Android 10+ (API 29+) via [android.content.ContentResolver.loadThumbnail].
 */
class LocalAudioArtFetcher(
    private val context: Context,
    private val uri: Uri,
    private val targetSize: Size
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("loadThumbnail requires API 29+")
        }
        val signal = CancellationSignal()
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            signal.cancel()
        }
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, targetSize, signal)
        } finally {
            cancelHandle?.dispose()
        }
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val isMediaAudio = data.scheme == "content" &&
                data.authority == MediaStore.AUTHORITY &&
                data.path?.contains("/audio/media/") == true
            if (!isMediaAudio) return null
            val w = options.size.width.pxOrElse { 512 }.coerceIn(96, 512)
            val h = options.size.height.pxOrElse { 512 }.coerceIn(96, 512)
            return LocalAudioArtFetcher(context.applicationContext, data, Size(w, h))
        }
    }
}
