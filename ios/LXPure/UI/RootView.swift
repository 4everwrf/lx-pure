import SwiftUI
import UIKit

private enum BottomTab: Int, CaseIterable, Identifiable {
    case library
    case discover
    case search

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .library: return "音乐库"
        case .discover: return "发现"
        case .search: return "搜索"
        }
    }

    var icon: String {
        switch self {
        case .library: return "music.note.house.fill"
        case .discover: return "safari.fill"
        case .search: return "magnifyingglass"
        }
    }
}

private enum AppRoute: Hashable {
    case collection(LibraryCollectionType)
    case userPlaylist(Int64)
    case onlinePlaylist(OnlinePlaylist)
    case leaderboard(LeaderboardItem)
    case settings
    case sourceManager
}

private struct ShareFileItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct RootView: View {
    @EnvironmentObject private var container: AppContainer
    @EnvironmentObject private var settingsStore: SettingsStore
    @EnvironmentObject private var libraryStore: LibraryStore
    @EnvironmentObject private var userApiManager: UserApiManager
    @EnvironmentObject private var localMusicManager: LocalMusicManager
    @EnvironmentObject private var playerController: PlayerController
    @EnvironmentObject private var appUpdateManager: AppUpdateManager

    @Environment(\.colorScheme) private var systemColorScheme
    @Environment(\.openURL) private var openURL

    @State private var activeTab: BottomTab = .library
    @State private var visitedTabs: Set<BottomTab> = [.library]
    @State private var path: [AppRoute] = []
    @State private var isPlayerExpanded = false
    @State private var showQueueSheet = false
    @State private var actionSheetTrack: Track?
    @State private var addToPlaylistTrack: Track?
    @State private var pendingAddToPlaylistTrack: Track?
    @State private var playerAddToPlaylistTrack: Track?
    @State private var isImportingPlaylist = false
    @State private var downloadedBytes: Int64 = 0
    @State private var cacheBytes: Int64 = 0
    @State private var shareFileItem: ShareFileItem?
    @State private var autoTriggeredIpaPath: String?

    @State private var activeToast: PlayerUiEvent?
    @State private var toastDismissTask: Task<Void, Never>?

    private var hasBlockingModalForUpdateSheet: Bool {
        isPlayerExpanded || showQueueSheet || actionSheetTrack != nil || addToPlaylistTrack != nil || playerAddToPlaylistTrack != nil
    }

    private var isUpdateSheetPresented: Bool {
        if hasBlockingModalForUpdateSheet { return false }
        switch appUpdateManager.state {
        case .available, .downloading, .verifying, .readyToInstall, .error:
            return true
        case .idle, .checking:
            return false
        }
    }

    private var isCheckingUpdate: Bool {
        if case .checking = appUpdateManager.state { return true }
        return false
    }

    private var canDismissUpdateSheet: Bool {
        switch appUpdateManager.state {
        case .downloading, .verifying:
            return false
        default:
            return true
        }
    }

    private var effectivePalette: LxPurePalette {
        switch settingsStore.settings.themeMode {
        case .dark: return .dark
        case .light: return .light
        case .system: return systemColorScheme == .dark ? .dark : .light
        }
    }

    private var hasReadySource: Bool {
        userApiManager.sources.contains { $0.enabled && $0.status == .ready }
    }

    private var bottomContentInset: CGFloat {
        let hasMiniPlayer = playerController.state.currentTrack != nil
        return path.isEmpty
            ? (hasMiniPlayer ? 164 : 96)
            : (hasMiniPlayer ? 96 : 28)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            NavigationStack(path: $path) {
                tabRootContent
                    .navigationDestination(for: AppRoute.self) { route in
                        destinationView(for: route)
                    }
            }
            .tint(effectivePalette.accent)

            // Floating MiniPlayer + Bottom Pill Tab Bar
            VStack(spacing: 10) {
                if let activeToast {
                    ToastBannerView(
                        event: activeToast,
                        onAction: activeToast.openSourceManager ? {
                            self.activeToast = nil
                            if path.last != .sourceManager {
                                path.append(.sourceManager)
                            }
                        } : nil
                    )
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                if playerController.state.currentTrack != nil {
                    FloatingMiniPlayer(
                        state: playerController.state,
                        progress: playerController.progress,
                        onTap: { isPlayerExpanded = true },
                        onTogglePlayPause: { playerController.togglePlayPause() },
                        onNext: { playerController.next() },
                        onPrevious: { playerController.previous() },
                        onOpenQueue: { showQueueSheet = true }
                    )
                }

                if path.isEmpty {
                    floatingTabBar
                }
            }
            .padding(.bottom, 8)
            .animation(.easeInOut(duration: 0.22), value: activeToast?.id)
        }
        .environment(\.lxPalette, effectivePalette)
        .preferredColorScheme(settingsStore.settings.themeMode.preferredColorScheme)
        .onAppear {
            applyWindowInterfaceStyle(settingsStore.settings.themeMode)
        }
        .onChange(of: settingsStore.settings.themeMode) { newMode in
            applyWindowInterfaceStyle(newMode)
        }
        .onChange(of: activeTab) { newTab in
            if newTab != .search {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            }
        }
        .fullScreenCover(isPresented: $isPlayerExpanded, onDismiss: {
            showQueueSheet = false
            playerAddToPlaylistTrack = nil
        }) {
            ZStack(alignment: .bottom) {
                FullPlayerView(
                    state: playerController.state,
                    progress: playerController.progress,
                    isFavorite: playerController.state.currentTrack.map { libraryStore.favoriteIds.contains($0.id) } ?? false,
                    downloadRecord: playerController.state.currentTrack.flatMap { libraryStore.downloadRecord(for: $0.id) },
                    preferredQuality: settingsStore.settings.preferredQuality,
                    fluidAmbientEnabled: settingsStore.settings.fluidAmbientBackground,
                    showTranslationDefault: settingsStore.settings.showTranslationLyrics,
                    onCollapse: { isPlayerExpanded = false },
                    onTogglePlayPause: { playerController.togglePlayPause() },
                    onNext: { playerController.next() },
                    onPrevious: { playerController.previous() },
                    onSeek: { playerController.seek(to: $0) },
                    onCycleRepeat: { playerController.cycleRepeatMode() },
                    onToggleFavorite: { track in
                        let added = libraryStore.toggleFavorite(track)
                        showToast(added ? "已添加到我喜欢的" : "已取消喜欢")
                    },
                    onAddToPlaylist: { track in
                        playerAddToPlaylistTrack = track
                    },
                    onDownload: { triggerTrackDownload($0) },
                    onOpenQueue: { showQueueSheet = true },
                    onSelectQuality: { playerController.changePreferredQuality($0) },
                    onSetSleepTimer: { playerController.setSleepTimer(minutes: $0) }
                )
                .sheet(isPresented: $showQueueSheet) {
                    queueSheet
                }

                if let activeToast {
                    ToastBannerView(
                        event: activeToast,
                        onAction: activeToast.openSourceManager ? {
                            self.activeToast = nil
                            isPlayerExpanded = false
                            if path.last != .sourceManager {
                                path.append(.sourceManager)
                            }
                        } : nil
                    )
                    .padding(.bottom, 76)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.easeInOut(duration: 0.22), value: activeToast?.id)
            .environment(\.lxPalette, effectivePalette)
            .sheet(item: $playerAddToPlaylistTrack) { track in
                addToPlaylistSheet(for: track)
            }
        }
        .sheet(isPresented: Binding(
            get: { showQueueSheet && !isPlayerExpanded },
            set: { showQueueSheet = $0 }
        )) {
            queueSheet
        }
        .sheet(item: $actionSheetTrack, onDismiss: {
            if let pending = pendingAddToPlaylistTrack {
                pendingAddToPlaylistTrack = nil
                addToPlaylistTrack = pending
            }
        }) { track in
            TrackActionSheetView(
                track: track,
                isFavorite: libraryStore.favoriteIds.contains(track.id),
                isDownloaded: libraryStore.downloadRecord(for: track.id)?.status == .completed,
                onPlayNext: { playerController.playNext(track) },
                onAddToQueue: { playerController.addToQueue(track) },
                onToggleFavorite: {
                    let added = libraryStore.toggleFavorite(track)
                    showToast(added ? "已添加到我喜欢的" : "已取消喜欢")
                },
                onAddToPlaylist: { pendingAddToPlaylistTrack = track },
                onDownload: { triggerTrackDownload(track) },
                onRemoveFromPlaylist: removeFromCurrentPlaylistHandler(for: track)
            )
            .environment(\.lxPalette, effectivePalette)
            .preferredColorScheme(effectivePalette.isDark ? .dark : .light)
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $addToPlaylistTrack) { track in
            addToPlaylistSheet(for: track)
        }
        .onReceive(playerController.events) { event in
            showEvent(event)
        }
        .onReceive(appUpdateManager.messages) { msg in
            showToast(msg)
        }
        .onChange(of: path) { newPath in
            if newPath.last == .settings {
                refreshStorageStats()
            }
        }
        .onChange(of: playerController.state.currentTrack?.id) { newId in
            if newId == nil {
                isPlayerExpanded = false
                showQueueSheet = false
                playerAddToPlaylistTrack = nil
            }
        }
        .onChange(of: appUpdateManager.state) { newState in
            switch newState {
            case .readyToInstall(_, let localURL, let resolvedUrl):
                if autoTriggeredIpaPath != localURL.path {
                    autoTriggeredIpaPath = localURL.path
                    playerController.persistSnapshotNow(synchronous: true)
                    if appUpdateManager.isTrollStoreAvailable,
                       let tsURL = AppUpdateManager.trollStoreInstallURL(for: resolvedUrl) {
                        openURL(tsURL)
                    } else {
                        shareFileItem = ShareFileItem(url: localURL)
                    }
                }
            case .idle:
                autoTriggeredIpaPath = nil
            default:
                break
            }
        }
        .task {
            appUpdateManager.checkOnStartupOnce()
        }
        .sheet(isPresented: Binding(
            get: { isUpdateSheetPresented },
            set: { presented in
                if !presented && !hasBlockingModalForUpdateSheet {
                    appUpdateManager.dismiss()
                }
            }
        )) {
            AppUpdateSheetView(
                state: appUpdateManager.state,
                currentVersion: appUpdateManager.currentAppVersion,
                isTrollStoreAvailable: appUpdateManager.isTrollStoreAvailable,
                onStartDownload: { info in
                    appUpdateManager.startDownload(info: info)
                },
                onCancelDownload: {
                    appUpdateManager.cancelDownload()
                },
                onInstallViaTrollStore: { resolvedUrl, info in
                    playerController.persistSnapshotNow(synchronous: true)
                    if let tsURL = AppUpdateManager.trollStoreInstallURL(for: resolvedUrl) {
                        openURL(tsURL)
                    } else if let webURL = URL(string: info.htmlUrl) {
                        openURL(webURL)
                    }
                },
                onOpenShareInstaller: { localURL in
                    playerController.persistSnapshotNow(synchronous: true)
                    shareFileItem = ShareFileItem(url: localURL)
                },
                onOpenInBrowser: { rawUrl in
                    if let webURL = URL(string: rawUrl) {
                        openURL(webURL)
                    }
                },
                onDismiss: {
                    appUpdateManager.dismiss()
                }
            )
            .environment(\.lxPalette, effectivePalette)
            .preferredColorScheme(effectivePalette.isDark ? .dark : .light)
            .interactiveDismissDisabled(!canDismissUpdateSheet)
            .presentationDetents([.fraction(0.78), .large])
            .presentationDragIndicator(.visible)
            .sheet(item: $shareFileItem) { item in
                ActivityShareSheet(activityItems: [item.url])
            }
        }
    }

    @ViewBuilder
    private var tabRootContent: some View {
        ZStack {
            if visitedTabs.contains(.library) {
                LibraryView(
                    hasReadySource: hasReadySource,
                    favoritesCount: libraryStore.favorites.count,
                    localCount: localMusicManager.localTracks.count,
                    downloadsCount: libraryStore.downloads.filter { $0.status == .completed }.count,
                    recentHistory: libraryStore.recentHistory,
                    playlists: libraryStore.userPlaylists,
                    isImportingPlaylist: isImportingPlaylist,
                    bottomInset: bottomContentInset,
                    onOpenSettings: {
                        refreshStorageStats()
                        path.append(.settings)
                    },
                    onOpenSourceManager: { path.append(.sourceManager) },
                    onOpenCollection: { path.append(.collection($0)) },
                    onOpenPlaylist: { path.append(.userPlaylist($0.id)) },
                    onPlayTrack: {
                        playerController.playTrack(
                            $0,
                            inQueue: libraryStore.recentHistory,
                            queueSource: PlaybackQueueSource(type: .history, title: "最近播放")
                        )
                    },
                    onCreatePlaylist: { name in
                        switch libraryStore.createPlaylist(name: name) {
                        case .created:
                            showToast("已创建歌单《\(name)》")
                        case .limitReached(let limit):
                            showToast("最多支持自建 \(limit) 个歌单，请先删除不需要的歌单")
                        }
                    },
                    onImportExternalPlaylist: { input in
                        importExternalPlaylist(input)
                    },
                    onDeletePlaylist: { pl in
                        libraryStore.deletePlaylist(id: pl.id)
                        path.removeAll { $0 == .userPlaylist(pl.id) }
                        showToast("已删除歌单《\(pl.name)》")
                    },
                    onPlaylistLimitReached: {
                        showToast("最多支持自建 \(LibraryStore.maxCustomPlaylists) 个歌单，请先删除不需要的歌单")
                    }
                )
                .opacity(activeTab == .library ? 1 : 0)
                .allowsHitTesting(activeTab == .library)
                .accessibilityHidden(activeTab != .library)
                .zIndex(activeTab == .library ? 1 : 0)
            }

            if visitedTabs.contains(.discover) {
                DiscoverView(
                    aggregator: container.musicAggregator,
                    bottomInset: bottomContentInset,
                    onOpenPlaylist: { path.append(.onlinePlaylist($0)) },
                    onOpenLeaderboard: { path.append(.leaderboard($0)) }
                )
                .opacity(activeTab == .discover ? 1 : 0)
                .allowsHitTesting(activeTab == .discover)
                .accessibilityHidden(activeTab != .discover)
                .zIndex(activeTab == .discover ? 1 : 0)
            }

            if visitedTabs.contains(.search) {
                SearchView(
                    aggregator: container.musicAggregator,
                    searchHistory: libraryStore.searchHistory,
                    currentTrackId: playerController.state.currentTrack?.id,
                    isPlaying: playerController.state.isPlaying,
                    bottomInset: bottomContentInset,
                    onSaveSearchQuery: { libraryStore.saveSearchQuery($0) },
                    onClearSearchHistory: { libraryStore.clearSearchHistory() },
                    onPlayTracks: { tracks, idx in
                        playerController.playTracks(
                            tracks,
                            startIndex: idx,
                            queueSource: PlaybackQueueSource(type: .search, title: "搜索结果")
                        )
                    },
                    onTrackMore: { actionSheetTrack = $0 }
                )
                .opacity(activeTab == .search ? 1 : 0)
                .allowsHitTesting(activeTab == .search)
                .accessibilityHidden(activeTab != .search)
                .zIndex(activeTab == .search ? 1 : 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            FluidAmbientBackground(
                palette: playerController.state.ambientPalette,
                enabled: settingsStore.settings.fluidAmbientBackground,
                immersive: false
            )
        }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder
    private func destinationView(for route: AppRoute) -> some View {
        switch route {
        case .collection(let type):
            switch type {
            case .favorites:
                CollectionDetailView(
                    title: type.title,
                    subtitle: "在播放页或歌曲菜单中点击心形图标收藏喜欢的歌曲",
                    tracks: libraryStore.favorites,
                    currentTrackId: playerController.state.currentTrack?.id,
                    isPlaying: playerController.state.isPlaying,
                    isLocalCollection: false,
                    isScanningLocal: false,
                    canClearHistory: false,
                    canDeleteTracks: true,
                    bottomInset: bottomContentInset,
                    onPlayTracks: {
                        playerController.playTracks(
                            $0,
                            startIndex: $1,
                            queueSource: PlaybackQueueSource(type: .favorites, title: "我喜欢的")
                        )
                    },
                    onTrackMore: { actionSheetTrack = $0 },
                    onRemoveTrack: { track in
                        libraryStore.toggleFavorite(track)
                        showToast("已移出我喜欢的")
                    }
                )

            case .local:
                CollectionDetailView(
                    title: type.title,
                    subtitle: "支持导入 FLAC / MP3 / M4A / WAV 本地音频",
                    tracks: localMusicManager.localTracks,
                    currentTrackId: playerController.state.currentTrack?.id,
                    isPlaying: playerController.state.isPlaying,
                    isLocalCollection: true,
                    isScanningLocal: localMusicManager.isScanning,
                    canClearHistory: false,
                    canDeleteTracks: true,
                    bottomInset: bottomContentInset,
                    onPlayTracks: {
                        playerController.playTracks(
                            $0,
                            startIndex: $1,
                            queueSource: PlaybackQueueSource(type: .local, title: "本地音乐")
                        )
                    },
                    onTrackMore: { actionSheetTrack = $0 },
                    onScanLocal: {
                        Task {
                            let found = await localMusicManager.scan()
                            showToast("扫描完成，找到 \(found.count) 首本地音频")
                        }
                    },
                    onImportLocalFiles: { urls in
                        Task {
                            let count = await localMusicManager.importFiles(from: urls)
                            showToast(count > 0 ? "已导入 \(count) 首本地音频" : "未找到可导入的音频文件")
                        }
                    },
                    onRemoveTrack: { track in
                        Task {
                            await localMusicManager.deleteLocalTrack(track)
                            showToast("已删除本地文件")
                        }
                    }
                )

            case .downloads:
                DownloadsDetailView(
                    downloads: libraryStore.downloads,
                    currentTrackId: playerController.state.currentTrack?.id,
                    isPlaying: playerController.state.isPlaying,
                    bottomInset: bottomContentInset,
                    onPlayTracks: {
                        playerController.playTracks(
                            $0,
                            startIndex: $1,
                            queueSource: PlaybackQueueSource(type: .downloads, title: "离线下载")
                        )
                    },
                    onDeleteDownload: { record in
                        Task {
                            await container.downloadManager.deleteDownload(trackId: record.trackId)
                            refreshStorageStats()
                            showToast("已删除离线文件")
                        }
                    },
                    onRetryDownload: { triggerTrackDownload($0) }
                )

            case .history:
                CollectionDetailView(
                    title: type.title,
                    subtitle: "播放过的歌曲将自动记录在这里",
                    tracks: libraryStore.recentHistory,
                    currentTrackId: playerController.state.currentTrack?.id,
                    isPlaying: playerController.state.isPlaying,
                    isLocalCollection: false,
                    isScanningLocal: false,
                    canClearHistory: true,
                    canDeleteTracks: false,
                    bottomInset: bottomContentInset,
                    onPlayTracks: {
                        playerController.playTracks(
                            $0,
                            startIndex: $1,
                            queueSource: PlaybackQueueSource(type: .history, title: "最近播放")
                        )
                    },
                    onTrackMore: { actionSheetTrack = $0 },
                    onClearHistory: {
                        libraryStore.clearHistory()
                        showToast("已清空播放历史")
                    }
                )
            }

        case .userPlaylist(let playlistId):
            UserPlaylistDetailHost(
                playlistId: playlistId,
                libraryStore: libraryStore,
                currentTrackId: playerController.state.currentTrack?.id,
                isPlaying: playerController.state.isPlaying,
                bottomInset: bottomContentInset,
                onPlayTracks: { tracks, idx, playlistName in
                    playerController.playTracks(
                        tracks,
                        startIndex: idx,
                        queueSource: PlaybackQueueSource(
                            type: .userPlaylist,
                            title: playlistName,
                            userPlaylistId: playlistId
                        )
                    )
                },
                onTrackMore: { actionSheetTrack = $0 },
                onRemoveTrack: { track in
                    libraryStore.removeTrack(trackId: track.id, fromPlaylistId: playlistId)
                    showToast("已从歌单移除")
                },
                onDeletePlaylist: { playlistName in
                    libraryStore.deletePlaylist(id: playlistId)
                    path.removeAll { $0 == .userPlaylist(playlistId) }
                    showToast("已删除歌单《\(playlistName)》")
                }
            )

        case .onlinePlaylist(let playlist):
            OnlinePlaylistDetailView(
                title: playlist.name,
                coverUrl: playlist.coverUrl,
                loadKey: "pl_\(playlist.source.code)_\(playlist.id)",
                loadDetail: {
                    await container.musicAggregator.getPlaylistDetail(source: playlist.source, idOrUrl: playlist.id)
                },
                currentTrackId: playerController.state.currentTrack?.id,
                isPlaying: playerController.state.isPlaying,
                bottomInset: bottomContentInset,
                onPlayTracks: {
                    playerController.playTracks(
                        $0,
                        startIndex: $1,
                        queueSource: PlaybackQueueSource(
                            type: .onlinePlaylist,
                            title: playlist.name,
                            onlinePlaylist: playlist
                        )
                    )
                },
                onSaveToLibrary: { saveOnlinePlaylistToLibrary($0) },
                onTrackMore: { actionSheetTrack = $0 }
            )

        case .leaderboard(let board):
            OnlinePlaylistDetailView(
                title: board.name,
                coverUrl: nil,
                loadKey: "board_\(board.id)",
                loadDetail: {
                    await container.musicAggregator.getLeaderboardDetail(board: board)
                },
                currentTrackId: playerController.state.currentTrack?.id,
                isPlaying: playerController.state.isPlaying,
                bottomInset: bottomContentInset,
                onPlayTracks: {
                    playerController.playTracks(
                        $0,
                        startIndex: $1,
                        queueSource: PlaybackQueueSource(
                            type: .leaderboard,
                            title: board.name,
                            leaderboard: board
                        )
                    )
                },
                onSaveToLibrary: { saveOnlinePlaylistToLibrary($0) },
                onTrackMore: { actionSheetTrack = $0 }
            )

        case .settings:
            SettingsView(
                settings: settingsStore.settings,
                sources: userApiManager.sources,
                downloadedBytes: downloadedBytes,
                cacheBytes: cacheBytes,
                isCheckingUpdate: isCheckingUpdate,
                bottomInset: bottomContentInset,
                onOpenSourceManager: { path.append(.sourceManager) },
                onSelectQuality: { q in settingsStore.update { $0.preferredQuality = q } },
                onToggleAutoFallback: { val in settingsStore.update { $0.autoFallbackSource = val } },
                onToggleCellularDataSaver: { val in settingsStore.update { $0.wifiOnlyHighQuality = val } },
                onSelectTheme: { mode in settingsStore.update { $0.themeMode = mode } },
                onToggleFluidAmbient: { val in settingsStore.update { $0.fluidAmbientBackground = val } },
                onToggleTranslation: { val in settingsStore.update { $0.showTranslationLyrics = val } },
                onScanLocalMusic: {
                    Task {
                        let found = await localMusicManager.scan()
                        showToast("扫描完成，找到 \(found.count) 首本地音频")
                    }
                },
                onClearCache: {
                    Task {
                        await container.audioCacheManager.clearAllCachesAsync()
                        await refreshStorageStatsAsync()
                        showToast("已清理音频与封面缓存")
                    }
                },
                onCheckUpdate: {
                    appUpdateManager.checkForUpdate(manual: true)
                }
            )

        case .sourceManager:
            SourceManagerView(
                userApiManager: userApiManager,
                bottomInset: bottomContentInset,
                onMessage: { showToast($0) }
            )
        }
    }

    private var floatingTabBar: some View {
        HStack(spacing: 4) {
            ForEach(BottomTab.allCases) { tab in
                let selected = tab == activeTab
                Button {
                    visitedTabs.insert(tab)
                    activeTab = tab
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 18, weight: selected ? .semibold : .regular))
                        Text(tab.label)
                            .font(.system(size: 10, weight: selected ? .semibold : .medium))
                    }
                    .foregroundColor(selected ? effectivePalette.accent : effectivePalette.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(selected ? effectivePalette.accentSoft : .clear)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial)
        .background(effectivePalette.glassSurface)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(effectivePalette.hairline, lineWidth: 0.8))
        .shadow(color: .black.opacity(effectivePalette.isDark ? 0.35 : 0.10), radius: 14, y: 6)
        .padding(.horizontal, 26)
    }

    private var queueSheet: some View {
        QueueSheetView(
            queue: playerController.state.queue,
            currentIndex: playerController.state.currentIndex,
            isPlaying: playerController.state.isPlaying,
            repeatMode: playerController.state.repeatMode,
            queueSource: playerController.state.queueSource,
            onSelectIndex: { idx in
                playerController.playTracks(
                    playerController.state.queue,
                    startIndex: idx,
                    queueSource: playerController.state.queueSource
                )
            },
            onRemoveIndex: { idx in
                playerController.removeFromQueue(at: idx)
            },
            onCycleRepeat: { playerController.cycleRepeatMode() },
            onClearQueue: { playerController.clearQueue() },
            onOpenQueueSource: { source in
                navigateToQueueSource(source)
            }
        )
        .environment(\.lxPalette, effectivePalette)
        .preferredColorScheme(effectivePalette.isDark ? .dark : .light)
        .presentationDetents([.medium, .large])
    }

    private func navigateToQueueSource(_ source: PlaybackQueueSource) {
        showQueueSheet = false
        isPlayerExpanded = false
        let target: AppRoute?
        switch source.type {
        case .favorites:
            target = .collection(.favorites)
        case .local:
            target = .collection(.local)
        case .downloads:
            target = .collection(.downloads)
        case .history:
            target = .collection(.history)
        case .userPlaylist:
            if let plId = source.userPlaylistId,
               libraryStore.userPlaylists.contains(where: { $0.id == plId }) {
                target = .userPlaylist(plId)
            } else {
                target = nil
            }
        case .onlinePlaylist:
            target = source.onlinePlaylist.map { .onlinePlaylist($0) }
        case .leaderboard:
            target = source.leaderboard.map { .leaderboard($0) }
        case .unknown, .search:
            target = nil
        }

        if let target {
            if path.last != target {
                path.append(target)
            }
        } else {
            showToast("未找到原歌单，当前队列曲目仍可继续播放")
        }
    }

    private func triggerTrackDownload(_ track: Track) {
        if let existing = libraryStore.downloadRecord(for: track.id) {
            switch existing.status {
            case .completed:
                Task {
                    if await container.downloadManager.localCachedFileURL(for: track.id) != nil {
                        showToast("《\(track.title)》已离线保存")
                    } else {
                        showToast("离线文件已失效，重新下载《\(track.title)》...")
                        container.downloadManager.enqueueDownload(
                            track: track,
                            preferredQuality: settingsStore.settings.preferredQuality
                        ) { msg in
                            showToast(msg)
                            refreshStorageStats()
                        }
                    }
                }
                return
            case .queued, .downloading:
                showToast("《\(track.title)》正在后台下载中")
                return
            case .failed:
                break
            }
        }
        if !track.isLocal {
            showToast("开始离线下载《\(track.title)》...")
        }
        container.downloadManager.enqueueDownload(
            track: track,
            preferredQuality: settingsStore.settings.preferredQuality
        ) { msg in
            showToast(msg)
            refreshStorageStats()
        }
    }

    private func importExternalPlaylist(_ rawInput: String) {
        guard !isImportingPlaylist else { return }
        isImportingPlaylist = true
        Task {
            defer { isImportingPlaylist = false }
            let res = await container.musicAggregator.importExternalPlaylist(rawInput: rawInput)
            switch res {
            case .success(let detail):
                _ = libraryStore.createPlaylist(
                    name: detail.playlist.name,
                    description: detail.playlist.description,
                    coverUrl: detail.playlist.coverUrl,
                    sourceUrl: rawInput,
                    tracks: detail.tracks
                )
                showToast("已导入歌单《\(detail.playlist.name)》（\(detail.tracks.count) 首）")
            case .failure(let err):
                showToast("导入歌单失败：\(err.localizedDescription)")
            }
        }
    }

    private func removeFromCurrentPlaylistHandler(for track: Track) -> (() -> Void)? {
        guard case .userPlaylist(let playlistId) = path.last else { return nil }
        let playlistName = libraryStore.userPlaylists.first(where: { $0.id == playlistId })?.name ?? "歌单"
        return {
            libraryStore.removeTrack(trackId: track.id, fromPlaylistId: playlistId)
            showToast("已从《\(playlistName)》移除")
        }
    }

    private func addToPlaylistSheet(for track: Track) -> some View {
        AddToPlaylistSheetHost(
            track: track,
            libraryStore: libraryStore,
            onSelectPlaylist: { pl in
                let res = libraryStore.addTracks([track], toPlaylistId: pl.id)
                if res.addedCount > 0 {
                    showToast("已添加到《\(pl.name)》")
                } else {
                    showToast("《\(track.title)》已在《\(pl.name)》中")
                }
            },
            onCreateAndAdd: { name in
                switch libraryStore.createPlaylist(name: name, tracks: [track]) {
                case .created:
                    showToast("已创建歌单《\(name)》并加入")
                case .limitReached(let limit):
                    showToast("最多支持自建 \(limit) 个歌单，请先删除不需要的歌单")
                }
            },
            onLimitReached: {
                showToast("最多支持自建 \(LibraryStore.maxCustomPlaylists) 个歌单，请先删除不需要的歌单")
            }
        )
        .environment(\.lxPalette, effectivePalette)
        .preferredColorScheme(effectivePalette.isDark ? .dark : .light)
        .presentationDetents([.medium, .large])
    }

    private func saveOnlinePlaylistToLibrary(_ detail: PlaylistDetailResult) {
        let sourceKey = "\(detail.playlist.source.code):\(detail.playlist.id)"
        if libraryStore.userPlaylists.contains(where: { $0.sourceUrl == sourceKey }) {
            showToast("《\(detail.playlist.name)》已在我的歌单中")
            return
        }
        _ = libraryStore.createPlaylist(
            name: detail.playlist.name,
            description: detail.playlist.description,
            coverUrl: detail.playlist.coverUrl,
            sourceUrl: sourceKey,
            tracks: detail.tracks
        )
        showToast("已收藏《\(detail.playlist.name)》到我的歌单")
    }

    private func applyWindowInterfaceStyle(_ mode: ThemeMode) {
        let style: UIUserInterfaceStyle
        switch mode {
        case .dark: style = .dark
        case .light: style = .light
        case .system: style = .unspecified
        }
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                if window.overrideUserInterfaceStyle != style {
                    window.overrideUserInterfaceStyle = style
                }
            }
        }
    }

    private func refreshStorageStats() {
        Task {
            await refreshStorageStatsAsync()
        }
    }

    private func refreshStorageStatsAsync() async {
        let dlBytes = await container.downloadManager.calculateDownloadedBytesAsync()
        let dbBytes = libraryStore.downloads
            .filter { $0.status == .completed }
            .reduce(Int64(0)) { $0 + max($1.totalBytes, 0) }
        downloadedBytes = max(dlBytes, dbBytes)
        cacheBytes = await container.audioCacheManager.calculateTotalCacheBytesAsync()
    }

    private func showToast(_ msg: String) {
        showEvent(PlayerUiEvent(message: msg))
    }

    private func showEvent(_ event: PlayerUiEvent) {
        toastDismissTask?.cancel()
        activeToast = event
        toastDismissTask = Task {
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            if !Task.isCancelled {
                activeToast = nil
            }
        }
    }
}

private struct UserPlaylistDetailHost: View {
    let playlistId: Int64
    @ObservedObject var libraryStore: LibraryStore
    let currentTrackId: String?
    let isPlaying: Bool
    let bottomInset: CGFloat
    let onPlayTracks: ([Track], Int, String) -> Void
    let onTrackMore: (Track) -> Void
    let onRemoveTrack: (Track) -> Void
    let onDeletePlaylist: (String) -> Void

    @State private var loadedTracks: [Track]?

    var body: some View {
        let playlist = libraryStore.userPlaylists.first(where: { $0.id == playlistId })
        let playlistName = playlist?.name ?? "歌单"
        let displayTracks = libraryStore.cachedTracksIfLoaded(forPlaylistId: playlistId) ?? loadedTracks ?? []

        CollectionDetailView(
            title: playlistName,
            subtitle: playlist == nil ? "该歌单已被删除" : "歌单内还没有歌曲，可在播放页或歌曲菜单中添加",
            tracks: displayTracks,
            currentTrackId: currentTrackId,
            isPlaying: isPlaying,
            isLocalCollection: false,
            isScanningLocal: false,
            canClearHistory: false,
            canDeleteTracks: playlist != nil,
            bottomInset: bottomInset,
            onPlayTracks: { tracks, idx in
                onPlayTracks(tracks, idx, playlistName)
            },
            onTrackMore: onTrackMore,
            onRemoveTrack: onRemoveTrack,
            onDeletePlaylist: playlist != nil ? { onDeletePlaylist(playlistName) } : nil
        )
        .task(id: "\(playlistId)_\(libraryStore.playlistTracksVersion)") {
            let tracks = await libraryStore.loadTracksAsync(forPlaylistId: playlistId)
            if !Task.isCancelled {
                loadedTracks = tracks
            }
        }
    }
}

private struct AddToPlaylistSheetHost: View {
    let track: Track
    @ObservedObject var libraryStore: LibraryStore
    let onSelectPlaylist: (UserPlaylist) -> Void
    let onCreateAndAdd: (String) -> Void
    let onLimitReached: () -> Void

    @State private var containedIds: Set<Int64>?

    var body: some View {
        let displayedContained = containedIds ?? libraryStore.cachedPlaylistIdsContainingIfLoaded(trackId: track.id)

        AddToPlaylistSheetView(
            track: track,
            playlists: libraryStore.userPlaylists,
            containedPlaylistIds: displayedContained,
            customPlaylistCount: libraryStore.customPlaylistCount,
            maxCustomPlaylists: LibraryStore.maxCustomPlaylists,
            onSelectPlaylist: onSelectPlaylist,
            onCreateAndAdd: onCreateAndAdd,
            onLimitReached: onLimitReached
        )
        .task(id: "\(track.id)_\(libraryStore.playlistTracksVersion)") {
            let ids = await libraryStore.playlistIdsContainingAsync(trackId: track.id)
            if !Task.isCancelled {
                containedIds = ids
            }
        }
    }
}
