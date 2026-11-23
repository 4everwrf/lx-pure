package com.lxpure.music

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lxpure.music.core.download.DownloadManager
import com.lxpure.music.core.localmedia.LocalMusicScanner
import com.lxpure.music.core.musicsdk.MusicAggregator
import com.lxpure.music.core.network.NetworkQualityAdvisor
import com.lxpure.music.core.player.MediaCacheManager
import com.lxpure.music.core.player.PlayerController
import com.lxpure.music.core.update.AppUpdateManager
import com.lxpure.music.core.userapi.LxQuickJsRuntime
import com.lxpure.music.core.userapi.UserApiManager
import com.lxpure.music.data.local.AppDatabase
import com.lxpure.music.data.local.PlaybackSessionStore
import com.lxpure.music.data.local.SettingsDataStore
import com.lxpure.music.data.repository.LibraryRepository
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppContainer(application: Application) {
    val httpDiskCache: Cache = Cache(
        directory = File(application.cacheDir, "http_cache"),
        maxSize = 12L * 1024L * 1024L // 12 MB API metadata cache
    )

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cache(httpDiskCache)
        .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Dedicated audio streaming client: bypasses the 12 MB API metadata cache (audio uses 300 MB SimpleCache)
    // and allows longer CDN read windows + automatic TCP reconnects across network hiccups.
    val streamHttpClient: OkHttpClient = httpClient.newBuilder()
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(application)
        .okHttpClient(httpClient)
        .memoryCache {
            MemoryCache.Builder(application)
                // Hard 24 MB cap keeps foreground Bitmap PSS strictly bounded even after browsing multiple 200-track charts
                .maxSizeBytes(24 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(application.cacheDir, "image_cache"))
                .maxSizeBytes(MediaCacheManager.MAX_IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .allowRgb565(true) // Halves Bitmap memory for opaque album covers
        .respectCacheHeaders(false) // Reuse cached covers across sessions to save mobile data
        .build()

    val mediaCacheManager: MediaCacheManager = MediaCacheManager(application, httpDiskCache)
    val networkQualityAdvisor: NetworkQualityAdvisor = NetworkQualityAdvisor(application)
    val database: AppDatabase = AppDatabase.build(application)
    val settingsDataStore: SettingsDataStore = SettingsDataStore(application)
    val playbackSessionStore: PlaybackSessionStore = PlaybackSessionStore(application)
    val libraryRepository: LibraryRepository = LibraryRepository(database)
    val musicAggregator: MusicAggregator = MusicAggregator(httpClient)
    val appUpdateManager: AppUpdateManager = AppUpdateManager(application, httpClient)
    val userApiManager: UserApiManager = UserApiManager(
        context = application,
        dao = database.userApiDao(),
        httpClient = httpClient,
        aggregator = musicAggregator,
        networkAdvisor = networkQualityAdvisor
    )
    val localMusicScanner: LocalMusicScanner = LocalMusicScanner(application)
    val downloadManager: DownloadManager = DownloadManager(
        context = application,
        downloadDao = database.downloadDao(),
        httpClient = httpClient,
        userApiManager = userApiManager
    )
    val playerController: PlayerController = PlayerController(
        context = application,
        imageLoader = imageLoader,
        networkAdvisor = networkQualityAdvisor,
        mediaCacheManager = mediaCacheManager,
        settingsDataStore = settingsDataStore,
        libraryRepository = libraryRepository,
        playbackSessionStore = playbackSessionStore,
        userApiManager = userApiManager,
        musicAggregator = musicAggregator,
        downloadManager = downloadManager
    )
}

class LxPureApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LxQuickJsRuntime.ensureQuickJsLoaded()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Release decoded cover Bitmaps and unused backup QuickJS VMs when backgrounded
            container.imageLoader.memoryCache?.clear()
            container.userApiManager.trimIdleRuntimes(keepTopCount = 2)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!::container.isInitialized) return
        container.imageLoader.memoryCache?.clear()
        container.userApiManager.trimIdleRuntimes(keepTopCount = 1)
    }
}
