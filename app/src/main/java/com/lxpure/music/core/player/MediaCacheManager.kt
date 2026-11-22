package com.lxpure.music.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import java.io.File

/**
 * Disk-backed LRU audio stream cache + image/HTTP cache coordinator.
 * Eliminates repeated cellular downloads on replay, single-loop, and seek-back
 * while keeping RAM usage strictly bounded.
 */
@OptIn(UnstableApi::class)
class MediaCacheManager(
    context: Context,
    private val httpDiskCache: Cache?
) {
    companion object {
        const val MAX_AUDIO_CACHE_BYTES: Long = 300L * 1024L * 1024L // 300 MB disk LRU
        const val MAX_IMAGE_DISK_CACHE_BYTES: Long = 64L * 1024L * 1024L // 64 MB image LRU
    }

    private val appContext = context.applicationContext
    private val audioCacheDir = File(appContext.cacheDir, "audio_stream_cache")
    private val imageCacheDir = File(appContext.cacheDir, "image_cache")
    private val cacheLock = Any()

    @Volatile
    private var simpleCache: SimpleCache? = null

    fun getAudioCache(): SimpleCache {
        simpleCache?.let { return it }
        return synchronized(cacheLock) {
            simpleCache ?: run {
                if (!audioCacheDir.exists()) audioCacheDir.mkdirs()
                SimpleCache(
                    audioCacheDir,
                    LeastRecentlyUsedCacheEvictor(MAX_AUDIO_CACHE_BYTES),
                    StandaloneDatabaseProvider(appContext)
                ).also { simpleCache = it }
            }
        }
    }

    fun wrapWithAudioCache(upstreamFactory: DataSource.Factory): DataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(getAudioCache())
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Evicts a single stream cache key (e.g. if a truncated/corrupted partial stream caused a playback error).
     */
    fun removeCacheKey(cacheKey: String?) {
        if (cacheKey.isNullOrBlank()) return
        runCatching {
            getAudioCache().removeResource(cacheKey)
        }
    }

    suspend fun calculateTotalCacheBytes(): Long = withContext(Dispatchers.IO) {
        val audioBytes = runCatching { simpleCache?.cacheSpace ?: directorySize(audioCacheDir) }.getOrDefault(0L)
        val imageBytes = directorySize(imageCacheDir)
        val httpBytes = runCatching { httpDiskCache?.size() ?: 0L }.getOrDefault(0L)
        audioBytes + imageBytes + httpBytes
    }

    suspend fun clearAllCaches(imageLoader: ImageLoader): Unit = withContext(Dispatchers.IO) {
        // Clear audio stream LRU entries safely without releasing the live SimpleCache instance
        runCatching {
            val cache = getAudioCache()
            val keys = cache.keys.toList()
            keys.forEach { key ->
                runCatching { cache.removeResource(key) }
            }
        }
        // Clear Coil memory + disk cache
        runCatching { imageLoader.memoryCache?.clear() }
        runCatching { imageLoader.diskCache?.clear() }
        // Clear OkHttp HTTP cache
        runCatching { httpDiskCache?.evictAll() }
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
