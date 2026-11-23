package com.lxpure.music

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lxpure.music.core.update.AppUpdateState
import com.lxpure.music.data.repository.CreatePlaylistResult
import com.lxpure.music.data.repository.LibraryRepository
import com.lxpure.music.domain.model.AppSettings
import com.lxpure.music.domain.model.DownloadStatus
import com.lxpure.music.domain.model.LeaderboardItem
import com.lxpure.music.domain.model.OnlinePlaylist
import com.lxpure.music.domain.model.PlaybackQueueSource
import com.lxpure.music.domain.model.QueueSourceType
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserApiRuntimeStatus
import com.lxpure.music.domain.model.UserPlaylist
import com.lxpure.music.ui.components.AddToPlaylistSheet
import com.lxpure.music.ui.components.AppUpdateDialog
import com.lxpure.music.ui.components.FloatingMiniPlayer
import com.lxpure.music.ui.components.TrackActionSheet
import com.lxpure.music.ui.discover.DiscoverScreen
import com.lxpure.music.ui.discover.OnlinePlaylistDetailScreen
import com.lxpure.music.ui.library.CollectionDetailScreen
import com.lxpure.music.ui.library.DownloadsDetailScreen
import com.lxpure.music.ui.library.LibraryCollectionType
import com.lxpure.music.ui.library.LibraryScreen
import com.lxpure.music.ui.player.FullPlayerScreen
import com.lxpure.music.ui.player.QueueBottomSheet
import com.lxpure.music.ui.search.SearchScreen
import com.lxpure.music.ui.settings.SettingsScreen
import com.lxpure.music.ui.settings.SourceManagerScreen
import com.lxpure.music.ui.theme.FluidAmbientBackground
import com.lxpure.music.ui.theme.LocalLxColors
import com.lxpure.music.ui.theme.LxPureTheme
import kotlinx.coroutines.launch
import java.io.File

private enum class BottomTab(val label: String, val icon: ImageVector) {
    LIBRARY("音乐库", Icons.Rounded.LibraryMusic),
    DISCOVER("发现", Icons.Rounded.Explore),
    SEARCH("搜索", Icons.Rounded.Search)
}

private sealed interface SubRoute {
    data class Collection(val type: LibraryCollectionType) : SubRoute
    data class UserPlaylistDetail(val playlist: UserPlaylist) : SubRoute
    data class OnlinePlaylistDetail(val playlist: OnlinePlaylist) : SubRoute
    data class LeaderboardDetail(val board: LeaderboardItem) : SubRoute
    data object Settings : SubRoute
    data object SourceManager : SubRoute
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        val container = (application as LxPureApp).container
        setContent {
            LxPureRoot(container)
        }
    }

    override fun onPause() {
        super.onPause()
        (application as? LxPureApp)?.container?.playerController?.persistSnapshotNow()
    }

    override fun onStop() {
        super.onStop()
        (application as? LxPureApp)?.container?.playerController?.persistSnapshotNow()
    }
}

@Composable
private fun LxPureRoot(container: AppContainer) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by container.settingsDataStore.settings.collectAsState(initial = AppSettings())
    val playerState by container.playerController.state.collectAsState()
    val sources by container.userApiManager.sources.collectAsState()
    val favorites by container.libraryRepository.favorites.collectAsState(initial = emptyList())
    val favoriteIds by container.libraryRepository.favoriteIds.collectAsState(initial = emptySet())
    val recentHistory by container.libraryRepository.recentHistory.collectAsState(initial = emptyList())
    val playlists by container.libraryRepository.userPlaylists.collectAsState(initial = emptyList())
    val downloads by container.libraryRepository.downloads.collectAsState(initial = emptyList())
    val searchHistory by container.libraryRepository.searchHistory.collectAsState(initial = emptyList())
    val localTracks by container.localMusicScanner.localTracks.collectAsState()
    val isScanningLocal by container.localMusicScanner.isScanning.collectAsState()
    val updateState by container.appUpdateManager.state.collectAsState()

    var activeTab by remember { mutableStateOf(BottomTab.LIBRARY) }
    val backStack = remember { mutableStateListOf<SubRoute>() }
    var isPlayerExpanded by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var actionSheetTrack by remember { mutableStateOf<Track?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
    var isImportingPlaylist by remember { mutableStateOf(false) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var pendingInstallApkFile by remember { mutableStateOf<File?>(null) }
    var autoLaunchedApkPath by remember { mutableStateOf<String?>(null) }

    val hasReadySource = remember(sources) {
        sources.any { it.enabled && it.status == UserApiRuntimeStatus.READY }
    }

    fun showToast(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    fun triggerTrackDownload(track: Track) {
        val existing = downloads.firstOrNull { it.trackId == track.id }
        when (existing?.status) {
            DownloadStatus.COMPLETED -> {
                showToast("《${track.title}》已离线保存")
                return
            }
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                showToast("《${track.title}》正在后台下载中")
                return
            }
            else -> Unit
        }
        if (!track.isLocal) {
            showToast("开始离线下载《${track.title}》…")
        }
        container.downloadManager.enqueueDownload(track, settings.preferredQuality) { msg ->
            showToast(msg)
        }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val found = container.localMusicScanner.scan()
                showToast("扫描完成，找到 ${found.size} 首本地音频")
            }
        } else {
            showToast("需要音频读取权限以扫描本地音乐")
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission optional */ }

    fun openExternalUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            showToast("无法打开浏览器链接")
        }
    }

    fun launchApkInstall(apkFile: File) {
        container.playerController.persistSnapshotNow()
        runCatching {
            context.startActivity(container.appUpdateManager.buildInstallIntent(apkFile))
        }.onFailure { err ->
            showToast("无法调起系统安装器：${err.message ?: "请通过浏览器下载安装"}")
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pendingFile = pendingInstallApkFile
        pendingInstallApkFile = null
        if (pendingFile != null && pendingFile.exists()) {
            if (container.appUpdateManager.canRequestPackageInstalls()) {
                launchApkInstall(pendingFile)
            } else {
                showToast("未授予安装权限，请允许「安装未知应用」后点击立即安装")
            }
        }
    }

    fun requestApkInstall(apkFile: File) {
        if (!apkFile.exists()) {
            showToast("安装包不存在，请重新下载")
            return
        }
        if (container.appUpdateManager.canRequestPackageInstalls()) {
            launchApkInstall(apkFile)
        } else {
            pendingInstallApkFile = apkFile
            showToast("请先开启「允许安装未知应用」，返回后自动继续安装")
            runCatching {
                unknownSourcesLauncher.launch(container.appUpdateManager.buildUnknownSourcesSettingsIntent())
            }.onFailure {
                pendingInstallApkFile = null
                showToast("无法打开安装权限设置，请在系统设置中开启允许安装未知应用")
            }
        }
    }

    fun navigateToQueueSource(source: PlaybackQueueSource) {
        isPlayerExpanded = false
        val target: SubRoute? = when (source.type) {
            QueueSourceType.FAVORITES -> SubRoute.Collection(LibraryCollectionType.FAVORITES)
            QueueSourceType.LOCAL -> SubRoute.Collection(LibraryCollectionType.LOCAL)
            QueueSourceType.DOWNLOADS -> SubRoute.Collection(LibraryCollectionType.DOWNLOADS)
            QueueSourceType.HISTORY -> SubRoute.Collection(LibraryCollectionType.HISTORY)
            QueueSourceType.USER_PLAYLIST -> {
                val matched = playlists.firstOrNull { it.id == source.userPlaylistId }
                matched?.let { SubRoute.UserPlaylistDetail(it) }
            }
            QueueSourceType.ONLINE_PLAYLIST -> source.onlinePlaylist?.let { SubRoute.OnlinePlaylistDetail(it) }
            QueueSourceType.LEADERBOARD -> source.leaderboard?.let { SubRoute.LeaderboardDetail(it) }
            else -> null
        }
        if (target != null) {
            if (backStack.lastOrNull() != target) {
                backStack.add(target)
            }
        } else {
            showToast("未找到原歌单，当前队列曲目仍可继续播放")
        }
    }

    fun triggerLocalScan() {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch {
                val found = container.localMusicScanner.scan()
                showToast("扫描完成，找到 ${found.size} 首本地音频")
            }
        } else {
            audioPermissionLauncher.launch(audioPermission)
        }
    }

    // Immediate GitHub version check + initial passive local scan + Android 13 notification permission
    LaunchedEffect(Unit) {
        container.appUpdateManager.checkOnStartupOnce()
        if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            container.localMusicScanner.scan()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        container.appUpdateManager.messages.collect { msg ->
            showToast(msg)
        }
    }

    LaunchedEffect(updateState) {
        val ready = updateState as? AppUpdateState.ReadyToInstall
        if (ready != null && autoLaunchedApkPath != ready.apkFile.absolutePath) {
            autoLaunchedApkPath = ready.apkFile.absolutePath
            requestApkInstall(ready.apkFile)
        } else if (updateState is AppUpdateState.Idle) {
            autoLaunchedApkPath = null
        }
    }

    // Refresh offline storage bytes whenever download statuses/sizes change or user opens Settings
    val downloadStatsKey = remember(downloads) {
        Triple(
            downloads.size,
            downloads.count { it.status == DownloadStatus.COMPLETED },
            downloads.sumOf { it.totalBytes }
        )
    }
    val isSettingsVisible = backStack.lastOrNull() == SubRoute.Settings
    LaunchedEffect(downloadStatsKey, isSettingsVisible) {
        val diskBytes = container.downloadManager.calculateDownloadedBytes()
        val dbBytes = downloads
            .filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { it.totalBytes }
        downloadedBytes = maxOf(diskBytes, dbBytes)
        cacheBytes = container.mediaCacheManager.calculateTotalCacheBytes()
    }

    // Collect player events (errors, source switches, cold-start source onboarding shortcut)
    LaunchedEffect(Unit) {
        container.playerController.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = if (event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && event.openSourceManager) {
                isPlayerExpanded = false
                backStack.add(SubRoute.SourceManager)
            }
        }
    }

    // Predictive Back navigation: Player Sheet -> SubRoute -> Library Tab
    BackHandler(enabled = isPlayerExpanded || backStack.isNotEmpty() || activeTab != BottomTab.LIBRARY) {
        when {
            isPlayerExpanded -> isPlayerExpanded = false
            backStack.isNotEmpty() -> backStack.removeAt(backStack.lastIndex)
            activeTab != BottomTab.LIBRARY -> activeTab = BottomTab.LIBRARY
        }
    }

    LxPureTheme(themeMode = settings.themeMode) {
        val lxColors = LocalLxColors.current
        val hasCurrentTrack = playerState.currentTrack != null
        val showBottomNav = backStack.isEmpty()

        LaunchedEffect(hasCurrentTrack) {
            if (!hasCurrentTrack) isPlayerExpanded = false
        }

        // Dynamically sync system status bar & navigation bar icon contrast with App theme and immersive player
        val useLightSystemBars = !lxColors.isDark && !(isPlayerExpanded && hasCurrentTrack)
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = useLightSystemBars
                isAppearanceLightNavigationBars = useLightSystemBars
            }
        }

        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val contentBottomPadding = navBarBottom + when {
            showBottomNav && hasCurrentTrack -> 172.dp
            showBottomNav -> 104.dp
            hasCurrentTrack -> 104.dp
            else -> 36.dp
        }

        Box(modifier = Modifier.fillMaxSize()) {
            FluidAmbientBackground(
                palette = playerState.ambientPalette,
                enabled = settings.fluidAmbientBackground,
                immersive = false
            )

            // Main Content Surface
            val topRoute = backStack.lastOrNull()
            if (topRoute == null) {
                when (activeTab) {
                    BottomTab.LIBRARY -> LibraryScreen(
                        hasReadySource = hasReadySource,
                        favoritesCount = favorites.size,
                        localCount = localTracks.size,
                        downloadsCount = downloads.count { it.status == DownloadStatus.COMPLETED },
                        recentHistory = recentHistory,
                        playlists = playlists,
                        isImportingPlaylist = isImportingPlaylist,
                        bottomPadding = contentBottomPadding,
                        onOpenSettings = { backStack.add(SubRoute.Settings) },
                        onOpenSourceManager = { backStack.add(SubRoute.SourceManager) },
                        onOpenCollection = { type -> backStack.add(SubRoute.Collection(type)) },
                        onOpenPlaylist = { playlist -> backStack.add(SubRoute.UserPlaylistDetail(playlist)) },
                        onPlayTrack = { track ->
                            val idx = recentHistory.indexOfFirst { it.id == track.id }
                            if (idx >= 0) {
                                container.playerController.playTracks(
                                    tracks = recentHistory,
                                    startIndex = idx,
                                    queueSource = PlaybackQueueSource(QueueSourceType.HISTORY, "最近播放")
                                )
                            } else {
                                container.playerController.playSingle(
                                    track = track,
                                    queueSource = PlaybackQueueSource(QueueSourceType.HISTORY, "最近播放")
                                )
                            }
                        },
                        onCreatePlaylist = { name ->
                            scope.launch {
                                when (val result = container.libraryRepository.createPlaylist(name)) {
                                    is CreatePlaylistResult.Created ->
                                        showToast("已创建歌单《$name》")
                                    is CreatePlaylistResult.LimitReached ->
                                        showToast("最多支持自建 ${result.maxAllowed} 个歌单，请先删除不需要的歌单")
                                }
                            }
                        },
                        onImportExternalPlaylist = { raw ->
                            scope.launch {
                                isImportingPlaylist = true
                                showToast("正在全量拉取歌单曲目，大歌单请稍候…")
                                container.musicAggregator.importExternalPlaylist(raw)
                                    .onSuccess { res ->
                                        container.libraryRepository.createPlaylist(
                                            name = res.playlist.name,
                                            description = res.playlist.description,
                                            coverUrl = res.playlist.coverUrl,
                                            sourceUrl = raw,
                                            tracks = res.tracks
                                        )
                                        showToast("已导入《${res.playlist.name}》(${res.tracks.size} 首)")
                                    }
                                    .onFailure { err ->
                                        showToast("导入歌单失败：${err.message}")
                                    }
                                isImportingPlaylist = false
                            }
                        },
                        onPlaylistLimitReached = {
                            showToast("最多支持自建 ${LibraryRepository.MAX_CUSTOM_PLAYLISTS} 个歌单，请先删除不需要的歌单")
                        }
                    )

                    BottomTab.DISCOVER -> DiscoverScreen(
                        aggregator = container.musicAggregator,
                        bottomPadding = contentBottomPadding,
                        onOpenPlaylist = { pl -> backStack.add(SubRoute.OnlinePlaylistDetail(pl)) },
                        onOpenLeaderboard = { board -> backStack.add(SubRoute.LeaderboardDetail(board)) }
                    )

                    BottomTab.SEARCH -> SearchScreen(
                        aggregator = container.musicAggregator,
                        searchHistory = searchHistory,
                        currentTrackId = playerState.currentTrack?.id,
                        isPlaying = playerState.isPlaying,
                        bottomPadding = contentBottomPadding,
                        onSaveSearchQuery = { q -> scope.launch { container.libraryRepository.saveSearchQuery(q) } },
                        onClearSearchHistory = { scope.launch { container.libraryRepository.clearSearchHistory() } },
                        onPlayTracks = { list, idx ->
                            container.playerController.playTracks(
                                tracks = list,
                                startIndex = idx,
                                queueSource = PlaybackQueueSource(QueueSourceType.SEARCH, "搜索结果")
                            )
                        },
                        onTrackMore = { track -> actionSheetTrack = track }
                    )
                }
            } else {
                when (topRoute) {
                    is SubRoute.Collection -> when (topRoute.type) {
                        LibraryCollectionType.FAVORITES -> CollectionDetailScreen(
                            title = "我喜欢的",
                            subtitle = "${favorites.size} 首收藏曲目",
                            tracks = favorites,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = contentBottomPadding,
                            emptyHint = "还没有喜欢的歌曲，听到心动的旋律点亮红心吧",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPlayAll = {
                                container.playerController.playTracks(
                                    favorites,
                                    0,
                                    PlaybackQueueSource(QueueSourceType.FAVORITES, "我喜欢的")
                                )
                            },
                            onPlayTrackAt = { idx ->
                                container.playerController.playTracks(
                                    favorites,
                                    idx,
                                    PlaybackQueueSource(QueueSourceType.FAVORITES, "我喜欢的")
                                )
                            },
                            onTrackMore = { actionSheetTrack = it }
                        )

                        LibraryCollectionType.LOCAL -> CollectionDetailScreen(
                            title = "本地音乐",
                            subtitle = "${localTracks.size} 首本地音频",
                            tracks = localTracks,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = contentBottomPadding,
                            isLoading = isScanningLocal,
                            showScanAction = true,
                            emptyHint = "点击下方按钮扫描手机中的无损或 MP3 音频",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPlayAll = {
                                container.playerController.playTracks(
                                    localTracks,
                                    0,
                                    PlaybackQueueSource(QueueSourceType.LOCAL, "本地音乐")
                                )
                            },
                            onPlayTrackAt = { idx ->
                                container.playerController.playTracks(
                                    localTracks,
                                    idx,
                                    PlaybackQueueSource(QueueSourceType.LOCAL, "本地音乐")
                                )
                            },
                            onTrackMore = { actionSheetTrack = it },
                            onScanLocal = { triggerLocalScan() }
                        )

                        LibraryCollectionType.DOWNLOADS -> DownloadsDetailScreen(
                            records = downloads,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = contentBottomPadding,
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPlayTrack = { track ->
                                val completedTracks = downloads
                                    .filter { it.status == DownloadStatus.COMPLETED }
                                    .map { it.track }
                                val idx = completedTracks.indexOfFirst { it.id == track.id }
                                if (idx >= 0) {
                                    container.playerController.playTracks(
                                        completedTracks,
                                        idx,
                                        PlaybackQueueSource(QueueSourceType.DOWNLOADS, "离线下载")
                                    )
                                } else {
                                    container.playerController.playSingle(
                                        track,
                                        PlaybackQueueSource(QueueSourceType.DOWNLOADS, "离线下载")
                                    )
                                }
                            },
                            onDeleteDownload = { id ->
                                scope.launch {
                                    container.downloadManager.deleteDownload(id)
                                    showToast("已移除离线文件")
                                }
                            }
                        )

                        LibraryCollectionType.HISTORY -> CollectionDetailScreen(
                            title = "最近播放",
                            subtitle = "${recentHistory.size} 条聆听足迹",
                            tracks = recentHistory,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = contentBottomPadding,
                            showClearAction = true,
                            emptyHint = "还没有播放记录",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPlayAll = {
                                container.playerController.playTracks(
                                    recentHistory,
                                    0,
                                    PlaybackQueueSource(QueueSourceType.HISTORY, "最近播放")
                                )
                            },
                            onPlayTrackAt = { idx ->
                                container.playerController.playTracks(
                                    recentHistory,
                                    idx,
                                    PlaybackQueueSource(QueueSourceType.HISTORY, "最近播放")
                                )
                            },
                            onTrackMore = { actionSheetTrack = it },
                            onClearAction = {
                                scope.launch {
                                    container.libraryRepository.clearHistory()
                                    showToast("已清空播放历史")
                                }
                            }
                        )
                    }

                    is SubRoute.UserPlaylistDetail -> {
                        val plTracksFlow = remember(topRoute.playlist.id) {
                            container.libraryRepository.observePlaylistTracks(topRoute.playlist.id)
                        }
                        val plTracks by plTracksFlow.collectAsState(initial = emptyList())
                        val plSource = remember(topRoute.playlist.id, topRoute.playlist.name) {
                            PlaybackQueueSource(
                                type = QueueSourceType.USER_PLAYLIST,
                                title = topRoute.playlist.name,
                                userPlaylistId = topRoute.playlist.id
                            )
                        }

                        CollectionDetailScreen(
                            title = topRoute.playlist.name,
                            subtitle = "${plTracks.size} 首歌曲",
                            tracks = plTracks,
                            currentTrackId = playerState.currentTrack?.id,
                            isPlaying = playerState.isPlaying,
                            bottomPadding = contentBottomPadding,
                            showDeletePlaylistAction = true,
                            emptyHint = "歌单里还没有歌曲，可在播放页或歌曲菜单中添加",
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPlayAll = { container.playerController.playTracks(plTracks, 0, plSource) },
                            onPlayTrackAt = { idx -> container.playerController.playTracks(plTracks, idx, plSource) },
                            onTrackMore = { actionSheetTrack = it },
                            onDeletePlaylist = {
                                scope.launch {
                                    container.libraryRepository.deletePlaylist(topRoute.playlist.id)
                                    backStack.removeAt(backStack.lastIndex)
                                    showToast("已删除歌单")
                                }
                            }
                        )
                    }

                    is SubRoute.OnlinePlaylistDetail -> OnlinePlaylistDetailScreen(
                        title = topRoute.playlist.name,
                        coverUrl = topRoute.playlist.coverUrl,
                        loadDetail = { container.musicAggregator.getPlaylistDetail(topRoute.playlist.source, topRoute.playlist.id) },
                        currentTrackId = playerState.currentTrack?.id,
                        isPlaying = playerState.isPlaying,
                        bottomPadding = contentBottomPadding,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onPlayTracks = { list, idx ->
                            container.playerController.playTracks(
                                list,
                                idx,
                                PlaybackQueueSource(
                                    type = QueueSourceType.ONLINE_PLAYLIST,
                                    title = topRoute.playlist.name,
                                    onlinePlaylist = topRoute.playlist
                                )
                            )
                        },
                        onSaveToLibrary = { res ->
                            scope.launch {
                                container.libraryRepository.createPlaylist(
                                    name = res.playlist.name,
                                    description = res.playlist.description,
                                    coverUrl = res.playlist.coverUrl,
                                    sourceUrl = "${res.playlist.source.code}:${res.playlist.id}",
                                    tracks = res.tracks
                                )
                                showToast("已收藏到我的歌单：《${res.playlist.name}》")
                            }
                        },
                        onTrackMore = { actionSheetTrack = it }
                    )

                    is SubRoute.LeaderboardDetail -> OnlinePlaylistDetailScreen(
                        title = topRoute.board.name,
                        coverUrl = null,
                        loadDetail = { container.musicAggregator.getLeaderboardDetail(topRoute.board) },
                        currentTrackId = playerState.currentTrack?.id,
                        isPlaying = playerState.isPlaying,
                        bottomPadding = contentBottomPadding,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onPlayTracks = { list, idx ->
                            container.playerController.playTracks(
                                list,
                                idx,
                                PlaybackQueueSource(
                                    type = QueueSourceType.LEADERBOARD,
                                    title = topRoute.board.name,
                                    leaderboard = topRoute.board
                                )
                            )
                        },
                        onSaveToLibrary = { res ->
                            scope.launch {
                                container.libraryRepository.createPlaylist(
                                    name = res.playlist.name,
                                    description = res.playlist.description,
                                    coverUrl = res.playlist.coverUrl,
                                    sourceUrl = "leaderboard:${topRoute.board.source.code}:${topRoute.board.id}",
                                    tracks = res.tracks
                                )
                                showToast("已收藏榜单：《${res.playlist.name}》")
                            }
                        },
                        onTrackMore = { actionSheetTrack = it }
                    )

                    SubRoute.Settings -> SettingsScreen(
                        settings = settings,
                        sources = sources,
                        downloadedBytes = downloadedBytes,
                        cacheBytes = cacheBytes,
                        isCheckingUpdate = updateState is AppUpdateState.Checking,
                        bottomPadding = contentBottomPadding,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onOpenSourceManager = { backStack.add(SubRoute.SourceManager) },
                        onSelectQuality = { q -> scope.launch { container.settingsDataStore.setPreferredQuality(q) } },
                        onToggleAutoFallback = { e -> scope.launch { container.settingsDataStore.setAutoFallback(e) } },
                        onToggleCellularDataSaver = { e -> scope.launch { container.settingsDataStore.setWifiOnlyHighQuality(e) } },
                        onSelectTheme = { t -> scope.launch { container.settingsDataStore.setThemeMode(t) } },
                        onToggleFluidAmbient = { e -> scope.launch { container.settingsDataStore.setFluidAmbient(e) } },
                        onToggleTranslation = { e -> scope.launch { container.settingsDataStore.setShowTranslation(e) } },
                        onScanLocalMusic = { triggerLocalScan() },
                        onClearCache = {
                            scope.launch {
                                container.mediaCacheManager.clearAllCaches(container.imageLoader)
                                cacheBytes = container.mediaCacheManager.calculateTotalCacheBytes()
                                showToast("已清理音频与封面缓存")
                            }
                        },
                        onCheckUpdate = { container.appUpdateManager.checkForUpdate(manual = true) }
                    )

                    SubRoute.SourceManager -> SourceManagerScreen(
                        sources = sources,
                        userApiManager = container.userApiManager,
                        bottomPadding = contentBottomPadding,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onMessage = { showToast(it) }
                    )
                }
            }

            // Bottom Floating Stack with soft vertical scrim to eliminate text bleed-through between capsules
            val hasBottomDock = hasCurrentTrack || showBottomNav
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(
                        if (hasBottomDock) {
                            Modifier.background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.28f to lxColors.background.copy(alpha = 0.88f),
                                        0.62f to lxColors.background.copy(alpha = 0.96f),
                                        1.0f to lxColors.background
                                    )
                                )
                            )
                        } else {
                            Modifier
                        }
                    )
                    .navigationBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (hasBottomDock) {
                        Spacer(Modifier.height(10.dp))
                    }
                    if (!isPlayerExpanded) {
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                        ) { data ->
                            MinimalGlassSnackbar(data = data)
                        }
                    }

                    if (hasCurrentTrack) {
                        FloatingMiniPlayer(
                            state = playerState,
                            onExpand = { isPlayerExpanded = true },
                            onTogglePlayPause = { container.playerController.togglePlayPause() },
                            onNext = { container.playerController.next() },
                            onPrevious = { container.playerController.previous() },
                            onOpenQueue = { showQueueSheet = true }
                        )
                    }

                    if (showBottomNav) {
                        MinimalBottomBar(
                            activeTab = activeTab,
                            onSelect = { activeTab = it }
                        )
                    }
                }
            }

            // Full-Screen Immersive Fluid Player Overlay
            AnimatedVisibility(
                visible = isPlayerExpanded && hasCurrentTrack,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                val cur = playerState.currentTrack
                val currentDownloadRecord = remember(cur?.id, downloads) {
                    cur?.let { track -> downloads.firstOrNull { it.trackId == track.id } }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {}
                ) {
                    FullPlayerScreen(
                        state = playerState,
                        isFavorite = cur != null && cur.id in favoriteIds,
                        downloadRecord = currentDownloadRecord,
                        preferredQuality = settings.preferredQuality,
                        fluidAmbientEnabled = settings.fluidAmbientBackground,
                        showTranslationDefault = settings.showTranslationLyrics,
                        onCollapse = { isPlayerExpanded = false },
                        onTogglePlayPause = { container.playerController.togglePlayPause() },
                        onNext = { container.playerController.next() },
                        onPrevious = { container.playerController.previous() },
                        onSeek = { container.playerController.seekTo(it) },
                        onCycleRepeat = { container.playerController.cycleRepeatMode() },
                        onToggleFavorite = { track ->
                            scope.launch {
                                val liked = container.libraryRepository.toggleFavorite(track)
                                showToast(if (liked) "已添加到我喜欢" else "已取消喜欢")
                            }
                        },
                        onAddToPlaylist = { track -> addToPlaylistTrack = track },
                        onDownload = { track -> triggerTrackDownload(track) },
                        onOpenQueue = { showQueueSheet = true },
                        onSelectQuality = { q ->
                            container.playerController.retryWithQuality(q)
                            showToast("正在切换至 ${q.label}")
                        },
                        onSetSleepTimer = { mins ->
                            container.playerController.setSleepTimerMinutes(mins)
                        }
                    )

                    // Float glass toast cleanly above the 4 bottom utility icons in FullPlayerScreen
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 18.dp, end = 18.dp, bottom = 82.dp)
                    ) { data ->
                        MinimalGlassSnackbar(data = data)
                    }
                }
            }
        }

        // Modals
        if (showQueueSheet) {
            QueueBottomSheet(
                state = playerState,
                forceDarkStatusBar = isPlayerExpanded,
                onDismiss = { showQueueSheet = false },
                onSelectIndex = { idx ->
                    container.playerController.playTracks(
                        tracks = playerState.queue,
                        startIndex = idx,
                        queueSource = playerState.queueSource
                    )
                    showQueueSheet = false
                },
                onRemoveIndex = { idx -> container.playerController.removeFromQueue(idx) },
                onClearQueue = { container.playerController.clearQueue() },
                onOpenQueueSource = { source -> navigateToQueueSource(source) }
            )
        }

        if (updateState !is AppUpdateState.Idle && updateState !is AppUpdateState.Checking) {
            AppUpdateDialog(
                state = updateState,
                onStartDownload = { info -> container.appUpdateManager.startDownload(info) },
                onCancelDownload = { container.appUpdateManager.cancelDownload() },
                onInstallApk = { apkFile -> requestApkInstall(apkFile) },
                onOpenInBrowser = { url -> openExternalUrl(url) },
                onDismiss = { container.appUpdateManager.dismiss() }
            )
        }

        actionSheetTrack?.let { track ->
            val activeUserPlaylist = (backStack.lastOrNull() as? SubRoute.UserPlaylistDetail)?.playlist
            TrackActionSheet(
                track = track,
                isFavorite = track.id in favoriteIds,
                onDismiss = { actionSheetTrack = null },
                onPlayNext = { container.playerController.playNextInQueue(track) },
                onToggleFavorite = {
                    scope.launch {
                        val liked = container.libraryRepository.toggleFavorite(track)
                        showToast(if (liked) "已添加到我喜欢" else "已取消喜欢")
                    }
                },
                onAddToPlaylist = { addToPlaylistTrack = track },
                onDownload = { triggerTrackDownload(track) },
                onRemoveFromPlaylist = activeUserPlaylist?.let { playlist ->
                    {
                        scope.launch {
                            container.libraryRepository.removeTrackFromPlaylist(playlist.id, track.id)
                            showToast("已从《${playlist.name}》移除")
                        }
                    }
                }
            )
        }

        addToPlaylistTrack?.let { track ->
            val containedIds by remember(track.id) {
                container.libraryRepository.observePlaylistIdsForTrack(track.id)
            }.collectAsState(initial = emptySet())
            val customPlaylistCount = remember(playlists) {
                LibraryRepository.customPlaylistCount(playlists)
            }
            AddToPlaylistSheet(
                track = track,
                playlists = playlists,
                containedPlaylistIds = containedIds,
                customPlaylistCount = customPlaylistCount,
                maxCustomPlaylists = LibraryRepository.MAX_CUSTOM_PLAYLISTS,
                forceDarkStatusBar = isPlayerExpanded,
                onDismiss = { addToPlaylistTrack = null },
                onSelectPlaylist = { playlist ->
                    scope.launch {
                        val res = container.libraryRepository.addTracksToPlaylist(playlist.id, listOf(track))
                        if (res.addedCount > 0) {
                            showToast("已添加到《${playlist.name}》")
                        } else {
                            showToast("《${track.title}》已在《${playlist.name}》中")
                        }
                    }
                },
                onCreateNewPlaylist = { name ->
                    scope.launch {
                        when (val res = container.libraryRepository.createPlaylist(name, tracks = listOf(track))) {
                            is CreatePlaylistResult.Created ->
                                showToast("已创建《$name》并加入歌曲")
                            is CreatePlaylistResult.LimitReached ->
                                showToast("最多支持自建 ${res.maxAllowed} 个歌单，请先删除不需要的歌单")
                        }
                    }
                },
                onLimitReached = {
                    showToast("最多支持自建 ${LibraryRepository.MAX_CUSTOM_PLAYLISTS} 个歌单，请先删除不需要的歌单")
                }
            )
        }
    }
}

@Composable
private fun MinimalGlassSnackbar(data: SnackbarData) {
    val lxColors = LocalLxColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (lxColors.isDark) Color(0xF01C202E) else Color(0xF21E293B),
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFFF8FAFC),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            data.visuals.actionLabel?.let { action ->
                TextButton(onClick = { data.performAction() }) {
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF38BDF8)
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalBottomBar(
    activeTab: BottomTab,
    onSelect: (BottomTab) -> Unit
) {
    val lxColors = LocalLxColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (lxColors.isDark) Color(0xF212151E) else Color(0xFAFFFFFF),
        border = BorderStroke(0.8.dp, lxColors.hairline),
        shadowElevation = if (lxColors.isDark) 8.dp else 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomTab.entries.forEach { tab ->
                val selected = tab == activeTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab) }
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) lxColors.accent else lxColors.textTertiary,
                        modifier = Modifier.size(23.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (selected) lxColors.accent else lxColors.textTertiary
                    )
                }
            }
        }
    }
}
