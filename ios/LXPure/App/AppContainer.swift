import Combine
import Foundation
import UIKit

@MainActor
final class AppContainer: ObservableObject {
    let urlSession: URLSession
    let networkAdvisor: NetworkQualityAdvisor
    let settingsStore: SettingsStore
    let libraryStore: LibraryStore
    let playbackSessionStore: PlaybackSessionStore
    let musicAggregator: MusicAggregator
    let appUpdateManager: AppUpdateManager
    let userApiManager: UserApiManager
    let audioCacheManager: AudioCacheManager
    let downloadManager: DownloadManager
    let localMusicManager: LocalMusicManager
    let playerController: PlayerController

    private var memoryWarningObserver: NSObjectProtocol?
    private var willResignActiveObserver: NSObjectProtocol?
    private var willTerminateObserver: NSObjectProtocol?

    init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 60
        config.waitsForConnectivity = false
        self.urlSession = URLSession(configuration: config)

        // Configure shared URLCache cap (24MB RAM / 64MB disk)
        URLCache.shared = URLCache(
            memoryCapacity: 24 * 1024 * 1024,
            diskCapacity: 64 * 1024 * 1024,
            diskPath: "LXPureSharedCache"
        )

        self.networkAdvisor = NetworkQualityAdvisor(startMonitoring: true)
        self.settingsStore = SettingsStore()
        self.libraryStore = LibraryStore()
        self.playbackSessionStore = PlaybackSessionStore()
        self.musicAggregator = MusicAggregator(session: urlSession)
        self.appUpdateManager = AppUpdateManager(session: urlSession)
        self.userApiManager = UserApiManager(
            libraryStore: libraryStore,
            session: urlSession,
            aggregator: musicAggregator,
            networkAdvisor: networkAdvisor
        )
        self.audioCacheManager = AudioCacheManager(session: urlSession)
        self.downloadManager = DownloadManager(
            libraryStore: libraryStore,
            session: urlSession,
            userApiManager: userApiManager
        )
        self.localMusicManager = LocalMusicManager()
        self.playerController = PlayerController(
            networkAdvisor: networkAdvisor,
            audioCacheManager: audioCacheManager,
            settingsStore: settingsStore,
            libraryStore: libraryStore,
            playbackSessionStore: playbackSessionStore,
            userApiManager: userApiManager,
            musicAggregator: musicAggregator,
            downloadManager: downloadManager
        )

        memoryWarningObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.handleMemoryPressure()
            }
        }

        willResignActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.playerController.persistSnapshotNow(synchronous: true)
            }
        }

        willTerminateObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.playerController.persistSnapshotNow(synchronous: true)
            }
        }
    }

    deinit {
        [memoryWarningObserver, willResignActiveObserver, willTerminateObserver].forEach {
            if let obs = $0 {
                NotificationCenter.default.removeObserver(obs)
            }
        }
    }

    func handleBackgroundTransition() {
        playerController.persistSnapshotNow(synchronous: true)
        userApiManager.trimIdleRuntimes(keepTopCount: 2)
    }

    func handleMemoryPressure() {
        ImageMemoryCache.shared.clear()
        userApiManager.trimIdleRuntimes(keepTopCount: 1)
    }
}
