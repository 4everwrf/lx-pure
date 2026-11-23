import AVFoundation
import Combine
import Foundation
import MediaPlayer
import SwiftUI
import UIKit

struct PlayerUiEvent: Identifiable, Sendable {
    let id = UUID()
    let message: String
    let actionLabel: String?
    let openSourceManager: Bool

    init(message: String, actionLabel: String? = nil, openSourceManager: Bool = false) {
        self.message = message
        self.actionLabel = actionLabel
        self.openSourceManager = openSourceManager
    }
}

struct PlayerState: Equatable {
    var queue: [Track] = []
    var currentIndex: Int = -1
    var isPlaying: Bool = false
    var isBuffering: Bool = false
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var repeatMode: RepeatMode = .listLoop
    var activeQualityBadge: String? = nil
    var activeProviderName: String? = nil
    var lyrics: ParsedLyrics = ParsedLyrics()
    var isLoadingLyrics: Bool = false
    var ambientPalette: AmbientPalette = .default
    var sleepTimerRemainingMs: Int64? = nil

    var currentTrack: Track? {
        guard currentIndex >= 0 && currentIndex < queue.count else { return nil }
        return queue[currentIndex]
    }

    var activeLyricIndex: Int {
        lyrics.activeIndex(at: positionMs)
    }
}

private struct PreloadedNextItem {
    let forTrackId: String
    let nextIndex: Int
    let track: Track
    let playbackURL: URL
    let isRemote: Bool
    let quality: AudioQuality?
    let qualityBadge: String
    let providerName: String
    let sourceId: String?
    let resolvedTrackId: String?
    let cacheKey: String?
}

@MainActor
final class PlayerController: ObservableObject {
    @Published private(set) var state = PlayerState()
    let events = PassthroughSubject<PlayerUiEvent, Never>()

    private static let preloadWindowMs: Int64 = 20_000
    private static let minTrackDurationForPreloadMs: Int64 = 25_000
    private static let stablePlaybackResetDeltaMs: Int64 = 8_000

    private let player = AVPlayer()
    private let networkAdvisor: NetworkQualityAdvisor
    private let audioCacheManager: AudioCacheManager
    private let settingsStore: SettingsStore
    private let libraryStore: LibraryStore
    private let userApiManager: UserApiManager
    private let musicAggregator: MusicAggregator
    private let downloadManager: DownloadManager

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var itemEndObserver: NSObjectProtocol?
    private var itemFailedObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?

    private var resolveTask: Task<Void, Never>?
    private var recoveryTask: Task<Void, Never>?
    private var preloadTask: Task<Void, Never>?
    private var coverTask: Task<Void, Never>?
    private var lyricTask: Task<Void, Never>?
    private var paletteTask: Task<Void, Never>?
    private var sleepTimerTask: Task<Void, Never>?

    private var activeLoadToken = UUID()
    private var activeStreamSourceId: String?
    private var activeResolvedTrackId: String?
    private var activeCacheKey: String?
    private var pendingResumePositionMs: Int64 = 0
    private var lastRecoveryStartPositionMs: Int64 = 0
    private var failedSourceIdsForCurrentTrack = Set<String>()
    private var failedTrackIdsForCurrentTrack = Set<String>()
    private var runtimeRetryCount = 0
    private var consecutiveAutoSkipCount = 0
    private var isRecoveringError = false

    private var preloadAttemptedForTrackId: String?
    private var preloadedNext: PreloadedNextItem?
    private var cachedArtworkImage: UIImage?

    init(
        networkAdvisor: NetworkQualityAdvisor,
        audioCacheManager: AudioCacheManager,
        settingsStore: SettingsStore,
        libraryStore: LibraryStore,
        userApiManager: UserApiManager,
        musicAggregator: MusicAggregator,
        downloadManager: DownloadManager
    ) {
        self.networkAdvisor = networkAdvisor
        self.audioCacheManager = audioCacheManager
        self.settingsStore = settingsStore
        self.libraryStore = libraryStore
        self.userApiManager = userApiManager
        self.musicAggregator = musicAggregator
        self.downloadManager = downloadManager

        player.automaticallyWaitsToMinimizeStalling = true
        configureAudioSession()
        configureRemoteCommands()
        observePlayerState()
        observeAudioSessionNotifications()
    }

    deinit {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        [itemEndObserver, itemFailedObserver, interruptionObserver, routeChangeObserver].forEach {
            if let obs = $0 { NotificationCenter.default.removeObserver(obs) }
        }
    }

    // MARK: - Public Controls

    func playTrack(_ track: Track, inQueue queue: [Track]? = nil) {
        if let queue, !queue.isEmpty {
            let idx = queue.firstIndex(where: { $0.id == track.id }) ?? 0
            playTracks(queue, startIndex: idx)
        } else {
            playTracks([track], startIndex: 0)
        }
    }

    func playTracks(_ tracks: [Track], startIndex: Int = 0) {
        guard !tracks.isEmpty else { return }
        let safeIndex = min(max(startIndex, 0), tracks.count - 1)
        consecutiveAutoSkipCount = 0
        state.queue = tracks
        state.currentIndex = safeIndex
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
    }

    func playNext(_ track: Track) {
        if state.queue.isEmpty || state.currentIndex < 0 {
            playTracks([track], startIndex: 0)
            return
        }
        var updated = state.queue
        if let existingIdx = updated.firstIndex(where: { $0.id == track.id }) {
            if existingIdx == state.currentIndex { return }
            updated.remove(at: existingIdx)
            if existingIdx < state.currentIndex {
                state.currentIndex -= 1
            }
        }
        let insertIdx = min(state.currentIndex + 1, updated.count)
        updated.insert(track, at: insertIdx)
        state.queue = updated
        clearPreloadedNext()
        events.send(PlayerUiEvent(message: "已将《\(track.title)》设为下一首播放"))
    }

    func addToQueue(_ track: Track) {
        if state.queue.isEmpty {
            playTracks([track], startIndex: 0)
            return
        }
        if !state.queue.contains(where: { $0.id == track.id }) {
            state.queue.append(track)
        }
        events.send(PlayerUiEvent(message: "已加入播放队列"))
    }

    func removeFromQueue(at index: Int) {
        guard index >= 0 && index < state.queue.count else { return }
        let removingCurrent = (index == state.currentIndex)
        state.queue.remove(at: index)
        clearPreloadedNext()

        if state.queue.isEmpty {
            stopAndReset()
            return
        }
        if index < state.currentIndex {
            state.currentIndex -= 1
        } else if removingCurrent {
            state.currentIndex = min(index, state.queue.count - 1)
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
        }
    }

    func clearQueue() {
        stopAndReset()
    }

    func togglePlayPause() {
        if state.currentTrack == nil { return }
        if player.currentItem == nil {
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: state.positionMs)
            return
        }
        if state.isPlaying {
            player.pause()
            state.isPlaying = false
            updateNowPlayingInfo()
        } else {
            activateAudioSession()
            player.play()
            state.isPlaying = true
            updateNowPlayingInfo()
        }
    }

    func seek(to positionMs: Int64) {
        let clamped = max(positionMs, 0)
        state.positionMs = clamped
        let target = CMTime(value: clamped, timescale: 1000)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                self?.updateNowPlayingInfo()
            }
        }
    }

    func next(isAutoAdvance: Bool = false) {
        guard !state.queue.isEmpty else { return }
        if !isAutoAdvance { consecutiveAutoSkipCount = 0 }

        guard let nextIdx = computeNextIndex(forceAdvance: !isAutoAdvance) else { return }

        if let preloaded = preloadedNext,
           preloaded.nextIndex == nextIdx,
           nextIdx < state.queue.count,
           preloaded.track.id == state.queue[nextIdx].id {
            preloadedNext = nil
            preloadAttemptedForTrackId = nil
            state.currentIndex = nextIdx
            playPreparedTrack(
                track: preloaded.track,
                url: preloaded.playbackURL,
                isRemote: preloaded.isRemote,
                quality: preloaded.quality,
                qualityBadge: preloaded.qualityBadge,
                providerName: preloaded.providerName,
                sourceId: preloaded.sourceId,
                resolvedTrackId: preloaded.resolvedTrackId,
                cacheKey: preloaded.cacheKey,
                seekToMs: 0
            )
            return
        }

        state.currentIndex = nextIdx
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
    }

    func previous() {
        guard !state.queue.isEmpty else { return }
        consecutiveAutoSkipCount = 0
        if state.positionMs > 4_000 {
            seek(to: 0)
            return
        }
        let prevIdx: Int
        if state.repeatMode == .shuffle && state.queue.count > 1 {
            prevIdx = randomOtherIndex()
        } else {
            prevIdx = (state.currentIndex - 1 + state.queue.count) % state.queue.count
        }
        state.currentIndex = prevIdx
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
    }

    func cycleRepeatMode() {
        state.repeatMode = state.repeatMode.next()
        clearPreloadedNext()
        events.send(PlayerUiEvent(message: "播放模式：\(state.repeatMode.label)"))
    }

    func changePreferredQuality(_ quality: AudioQuality) {
        settingsStore.update { $0.preferredQuality = quality }
        guard let track = state.currentTrack, !track.isLocal else { return }
        let resumePos = state.positionMs
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: resumePos)
    }

    func setSleepTimer(minutes: Int?) {
        sleepTimerTask?.cancel()
        sleepTimerTask = nil

        guard let minutes, minutes > 0 else {
            state.sleepTimerRemainingMs = nil
            events.send(PlayerUiEvent(message: "已关闭睡眠定时"))
            return
        }

        var remainingMs = Int64(minutes) * 60_000
        state.sleepTimerRemainingMs = remainingMs
        events.send(PlayerUiEvent(message: "已设定 \(minutes) 分钟后自动暂停"))

        sleepTimerTask = Task { [weak self] in
            while remainingMs > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                remainingMs = max(remainingMs - 1_000, 0)
                await MainActor.run {
                    self?.state.sleepTimerRemainingMs = remainingMs > 0 ? remainingMs : nil
                }
            }
            await MainActor.run {
                guard let self else { return }
                self.player.pause()
                self.state.isPlaying = false
                self.state.sleepTimerRemainingMs = nil
                self.updateNowPlayingInfo()
                self.events.send(PlayerUiEvent(message: "睡眠定时已到，已暂停播放"))
            }
        }
    }

    // MARK: - Core Playback Pipeline

    private func loadAndPlayCurrent(
        resetRecoveryState: Bool,
        seekToMs: Int64,
        preferredSourceId: String? = nil
    ) {
        guard let track = state.currentTrack else { return }

        resolveTask?.cancel()
        recoveryTask?.cancel()
        clearPreloadedNext()

        if resetRecoveryState {
            failedSourceIdsForCurrentTrack.removeAll()
            failedTrackIdsForCurrentTrack.removeAll()
            runtimeRetryCount = 0
            isRecoveringError = false
            lastRecoveryStartPositionMs = 0
        }

        let loadToken = UUID()
        activeLoadToken = loadToken
        pendingResumePositionMs = max(seekToMs, 0)

        state.isBuffering = true
        state.positionMs = max(seekToMs, 0)
        state.durationMs = track.durationMs
        if resetRecoveryState {
            state.activeQualityBadge = nil
            state.activeProviderName = nil
            loadMetadataAndVisuals(for: track)
        }
        updateNowPlayingInfo()

        let settings = settingsStore.settings

        resolveTask = Task { [weak self] in
            guard let self else { return }

            // 1. Local file track
            if track.isLocal, let localUri = track.localUri {
                let localUrl = localUri.hasPrefix("file://")
                    ? (URL(string: localUri) ?? URL(fileURLWithPath: localUri))
                    : URL(fileURLWithPath: localUri)
                guard self.activeLoadToken == loadToken else { return }
                if FileManager.default.fileExists(atPath: localUrl.path) {
                    self.playPreparedTrack(
                        track: track,
                        url: localUrl,
                        isRemote: false,
                        quality: nil,
                        qualityBadge: "LOCAL",
                        providerName: "本地音乐",
                        sourceId: nil,
                        resolvedTrackId: track.id,
                        cacheKey: nil,
                        seekToMs: seekToMs
                    )
                    return
                }
            }

            // 2. Offline downloaded file
            if let offlineUrl = await self.downloadManager.localCachedFileURL(for: track.id) {
                guard self.activeLoadToken == loadToken else { return }
                self.playPreparedTrack(
                    track: track,
                    url: offlineUrl,
                    isRemote: false,
                    quality: nil,
                    qualityBadge: "离线",
                    providerName: "已离线保存",
                    sourceId: nil,
                    resolvedTrackId: track.id,
                    cacheKey: nil,
                    seekToMs: seekToMs
                )
                return
            }

            // 3. Determine effective quality (Wi-Fi vs Cellular data saver)
            let qualityDecision = self.networkAdvisor.decideQuality(
                preferred: settings.preferredQuality,
                cellularDataSaverEnabled: settings.wifiOnlyHighQuality
            )
            let targetQuality = qualityDecision.quality
            let cacheKey = AudioCacheManager.cacheKey(trackId: track.id, quality: targetQuality)

            // 4. Hit 300MB disk LRU audio cache if available
            if let cachedUrl = self.audioCacheManager.cachedFileURL(for: cacheKey) {
                guard self.activeLoadToken == loadToken else { return }
                self.playPreparedTrack(
                    track: track,
                    url: cachedUrl,
                    isRemote: false,
                    quality: targetQuality,
                    qualityBadge: "\(targetQuality.badge) · 缓存",
                    providerName: track.source.displayName,
                    sourceId: nil,
                    resolvedTrackId: track.id,
                    cacheKey: cacheKey,
                    seekToMs: seekToMs
                )
                return
            }

            // 5. Resolve online stream via UserApiManager
            let result = await self.userApiManager.resolveStream(
                track: track,
                preferredQuality: targetQuality,
                allowCrossPlatformFallback: settings.autoFallbackSource,
                preferredSourceId: preferredSourceId,
                excludedSourceIds: self.failedSourceIdsForCurrentTrack,
                excludedTrackIds: self.failedTrackIdsForCurrentTrack
            )
            guard self.activeLoadToken == loadToken else { return }

            switch result {
            case .success(let resolved):
                guard let remoteUrl = URL(string: resolved.url) else {
                    self.handleResolutionFailure(track: track, message: "返回的音频地址无效")
                    return
                }
                if qualityDecision.downgradedForCellular {
                    self.events.send(PlayerUiEvent(message: "当前为移动网络，已智能省流切换 \(resolved.quality.badge)"))
                } else if resolved.switchedPlatform {
                    self.events.send(PlayerUiEvent(message: "已自动换源至 \(resolved.resolvedTrack.source.displayName)"))
                }
                let finalCacheKey = AudioCacheManager.cacheKey(trackId: track.id, quality: resolved.quality)
                self.playPreparedTrack(
                    track: track,
                    url: remoteUrl,
                    isRemote: true,
                    quality: resolved.quality,
                    qualityBadge: resolved.quality.badge,
                    providerName: resolved.providerName,
                    sourceId: resolved.sourceId,
                    resolvedTrackId: resolved.resolvedTrack.id,
                    cacheKey: finalCacheKey,
                    seekToMs: seekToMs
                )

            case .failure(let error):
                let noSource = !self.userApiManager.hasReadySource()
                self.handleResolutionFailure(
                    track: track,
                    message: error.localizedDescription,
                    openSourceManager: noSource
                )
            }
        }
    }

    private func playPreparedTrack(
        track: Track,
        url: URL,
        isRemote: Bool,
        quality: AudioQuality?,
        qualityBadge: String,
        providerName: String,
        sourceId: String?,
        resolvedTrackId: String?,
        cacheKey: String?,
        seekToMs: Int64
    ) {
        activateAudioSession()
        activeStreamSourceId = sourceId
        activeResolvedTrackId = resolvedTrackId
        activeCacheKey = cacheKey
        pendingResumePositionMs = max(seekToMs, 0)
        isRecoveringError = false

        state.activeQualityBadge = qualityBadge
        state.activeProviderName = providerName
        state.isBuffering = true
        state.durationMs = track.durationMs

        // Refresh visuals & lyrics if coming from preloaded transition
        if state.lyrics.isEmpty && !state.isLoadingLyrics {
            loadMetadataAndVisuals(for: track)
        } else if cachedArtworkImage == nil {
            loadMetadataAndVisuals(for: track)
        }

        let asset: AVURLAsset
        if isRemote {
            asset = AVURLAsset(url: url, options: [
                "AVURLAssetHTTPHeaderFieldsKey": [
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ]
            ])
        } else {
            asset = AVURLAsset(url: url)
        }

        let item = AVPlayerItem(asset: asset)
        observeCurrentItem(item)
        player.replaceCurrentItem(with: item)
        if seekToMs > 0 {
            let target = CMTime(value: seekToMs, timescale: 1000)
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        player.play()
        state.isPlaying = true
        libraryStore.recordPlay(track)
        updateNowPlayingInfo()

        // Opportunistically cache remote stream on disk for instant zero-data replays
        if isRemote, let quality, let cacheKey {
            audioCacheManager.cacheInBackground(
                remoteUrlString: url.absoluteString,
                cacheKey: cacheKey,
                quality: quality
            )
        }
    }

    private func handleResolutionFailure(track: Track, message: String, openSourceManager: Bool = false) {
        state.isBuffering = false
        state.isPlaying = false
        updateNowPlayingInfo()

        if openSourceManager {
            events.send(PlayerUiEvent(message: message, actionLabel: "导入音源", openSourceManager: true))
            return
        }

        events.send(PlayerUiEvent(message: message))

        // Auto-skip next if in a multi-track queue and within consecutive limit
        if state.queue.count > 1 && consecutiveAutoSkipCount < PlaybackRecoveryPolicy.maxConsecutiveAutoSkips {
            consecutiveAutoSkipCount += 1
            recoveryTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                if Task.isCancelled { return }
                await MainActor.run {
                    self?.next(isAutoAdvance: true)
                }
            }
        }
    }

    private func handleRuntimePlaybackError(item: AVPlayerItem, error: Error?) {
        guard let track = state.currentTrack, !isRecoveringError else { return }

        let httpCode = item.errorLog()?.events.last?.errorStatusCode
        let normalizedCode = (httpCode != nil && httpCode! >= 400) ? httpCode : nil
        let kind = PlaybackRecoveryPolicy.classify(error: error, httpStatusCode: normalizedCode)

        guard runtimeRetryCount < PlaybackRecoveryPolicy.maxRuntimeRetries else {
            handleResolutionFailure(track: track, message: "《\(track.title)》播放中断，已尝试切换其他音源")
            return
        }

        let plan = PlaybackRecoveryPolicy.decideRecovery(
            kind: kind,
            retryAttemptIndex: runtimeRetryCount,
            primaryTrackId: track.id,
            resolvedTrackId: activeResolvedTrackId
        )
        runtimeRetryCount += 1
        isRecoveringError = true
        lastRecoveryStartPositionMs = state.positionMs

        if plan.evictStreamCache, let key = activeCacheKey {
            audioCacheManager.evict(cacheKey: key)
        }
        if plan.excludeCurrentSource, let sid = activeStreamSourceId {
            failedSourceIdsForCurrentTrack.insert(sid)
        }
        if plan.excludeResolvedTrack, let rtid = activeResolvedTrackId {
            failedTrackIdsForCurrentTrack.insert(rtid)
        }

        let resumePos = plan.preservePosition ? max(state.positionMs, pendingResumePositionMs) : 0
        let preferSource = plan.preferSameSourceFirst ? activeStreamSourceId : nil
        let backoffNs = UInt64(max(plan.backoffMs, 100)) * 1_000_000

        recoveryTask?.cancel()
        recoveryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: backoffNs)
            if Task.isCancelled { return }
            await MainActor.run {
                self?.loadAndPlayCurrent(
                    resetRecoveryState: false,
                    seekToMs: resumePos,
                    preferredSourceId: preferSource
                )
            }
        }
    }

    // MARK: - Gapless Preloading

    private func maybePreloadNextTrack() {
        guard let currentTrack = state.currentTrack,
              state.repeatMode != .singleLoop,
              state.durationMs >= Self.minTrackDurationForPreloadMs,
              (state.durationMs - state.positionMs) <= Self.preloadWindowMs,
              preloadAttemptedForTrackId != currentTrack.id,
              let nextIdx = computeNextIndex(forceAdvance: false),
              nextIdx != state.currentIndex,
              nextIdx < state.queue.count else {
            return
        }

        let nextTrack = state.queue[nextIdx]
        preloadAttemptedForTrackId = currentTrack.id
        let settings = settingsStore.settings
        let forTrackId = currentTrack.id

        preloadTask?.cancel()
        preloadTask = Task { [weak self] in
            guard let self else { return }

            if nextTrack.isLocal, let localUri = nextTrack.localUri {
                let localUrl = URL(fileURLWithPath: localUri)
                if FileManager.default.fileExists(atPath: localUrl.path) {
                    self.preloadedNext = PreloadedNextItem(
                        forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                        playbackURL: localUrl, isRemote: false, quality: nil,
                        qualityBadge: "LOCAL", providerName: "本地音乐",
                        sourceId: nil, resolvedTrackId: nextTrack.id, cacheKey: nil
                    )
                    return
                }
            }

            if let offlineUrl = await self.downloadManager.localCachedFileURL(for: nextTrack.id) {
                self.preloadedNext = PreloadedNextItem(
                    forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                    playbackURL: offlineUrl, isRemote: false, quality: nil,
                    qualityBadge: "离线", providerName: "已离线保存",
                    sourceId: nil, resolvedTrackId: nextTrack.id, cacheKey: nil
                )
                return
            }

            let decision = self.networkAdvisor.decideQuality(
                preferred: settings.preferredQuality,
                cellularDataSaverEnabled: settings.wifiOnlyHighQuality
            )
            let cacheKey = AudioCacheManager.cacheKey(trackId: nextTrack.id, quality: decision.quality)
            if let cachedUrl = self.audioCacheManager.cachedFileURL(for: cacheKey) {
                self.preloadedNext = PreloadedNextItem(
                    forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                    playbackURL: cachedUrl, isRemote: false, quality: decision.quality,
                    qualityBadge: "\(decision.quality.badge) · 缓存", providerName: nextTrack.source.displayName,
                    sourceId: nil, resolvedTrackId: nextTrack.id, cacheKey: cacheKey
                )
                return
            }

            let res = await self.userApiManager.resolveStream(
                track: nextTrack,
                preferredQuality: decision.quality,
                allowCrossPlatformFallback: settings.autoFallbackSource
            )
            guard !Task.isCancelled else { return }
            if case .success(let resolved) = res, let url = URL(string: resolved.url) {
                let key = AudioCacheManager.cacheKey(trackId: nextTrack.id, quality: resolved.quality)
                self.preloadedNext = PreloadedNextItem(
                    forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                    playbackURL: url, isRemote: true, quality: resolved.quality,
                    qualityBadge: resolved.quality.badge, providerName: resolved.providerName,
                    sourceId: resolved.sourceId, resolvedTrackId: resolved.resolvedTrack.id, cacheKey: key
                )
            }
        }
    }

    private func clearPreloadedNext() {
        preloadTask?.cancel()
        preloadTask = nil
        preloadedNext = nil
        preloadAttemptedForTrackId = nil
    }

    // MARK: - Metadata, Cover, Lyrics & Ambient Palette

    private func loadMetadataAndVisuals(for track: Track) {
        coverTask?.cancel()
        lyricTask?.cancel()
        paletteTask?.cancel()

        state.lyrics = ParsedLyrics()
        state.isLoadingLyrics = true
        cachedArtworkImage = nil

        let trackId = track.id

        coverTask = Task { [weak self] in
            guard let self else { return }
            var cover = track.coverUrl
            if (cover ?? "").isEmpty {
                if let resolved = await self.musicAggregator.resolveCoverIfNeeded(for: track),
                   !Task.isCancelled {
                    cover = resolved
                    if self.state.currentIndex >= 0 &&
                        self.state.currentIndex < self.state.queue.count &&
                        self.state.queue[self.state.currentIndex].id == trackId {
                        self.state.queue[self.state.currentIndex].coverUrl = resolved
                    }
                }
            }
            guard !Task.isCancelled, self.state.currentTrack?.id == trackId else { return }

            if let img = await ImageMemoryCache.shared.loadImage(from: cover, isThumbnail: false),
               !Task.isCancelled,
               self.state.currentTrack?.id == trackId {
                self.cachedArtworkImage = img
                self.updateNowPlayingInfo()

                let palette = await Task.detached(priority: .utility) {
                    AmbientColorExtractor.extractPalette(from: img)
                }.value
                if !Task.isCancelled && self.state.currentTrack?.id == trackId {
                    self.state.ambientPalette = palette
                }
            } else if self.state.currentTrack?.id == trackId {
                self.state.ambientPalette = .default
            }
        }

        lyricTask = Task { [weak self] in
            guard let self else { return }
            let fetched = await self.musicAggregator.fetchLyrics(for: track)
            guard !Task.isCancelled, self.state.currentTrack?.id == trackId else { return }
            self.state.lyrics = fetched
            self.state.isLoadingLyrics = false
        }
    }

    // MARK: - AVPlayer & AudioSession Observers

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
    }

    private func activateAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
    }

    private func configureRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if !self.state.isPlaying { self.togglePlayPause() }
            }
            return .success
        }

        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.state.isPlaying { self.togglePlayPause() }
            }
            return .success
        }

        center.togglePlayPauseCommand.isEnabled = true
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }

        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next(isAutoAdvance: false) }
            return .success
        }

        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }

        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(posEvent.positionTime * 1000.0)
            Task { @MainActor in self?.seek(to: ms) }
            return .success
        }
    }

    private func observePlayerState() {
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            Task { @MainActor in
                guard let self else { return }
                switch player.timeControlStatus {
                case .playing:
                    self.state.isPlaying = true
                    self.state.isBuffering = false
                    self.consecutiveAutoSkipCount = 0
                case .waitingToPlayAtSpecifiedRate:
                    self.state.isBuffering = true
                case .paused:
                    self.state.isPlaying = false
                    self.state.isBuffering = false
                @unknown default:
                    break
                }
                self.updateNowPlayingInfo()
            }
        }

        let interval = CMTime(seconds: 0.25, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self else { return }
                let posSec = time.seconds
                if posSec.isFinite && posSec >= 0 {
                    let posMs = Int64(posSec * 1000.0)
                    self.state.positionMs = posMs
                    if self.runtimeRetryCount > 0 &&
                        posMs >= self.lastRecoveryStartPositionMs + Self.stablePlaybackResetDeltaMs {
                        self.runtimeRetryCount = 0
                    }
                }
                if let dur = self.player.currentItem?.duration.seconds, dur.isFinite && dur > 0 {
                    self.state.durationMs = Int64(dur * 1000.0)
                }
                self.maybePreloadNextTrack()
            }
        }
    }

    private func observeCurrentItem(_ item: AVPlayerItem) {
        statusObservation?.invalidate()
        if let obs = itemEndObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = itemFailedObserver { NotificationCenter.default.removeObserver(obs) }

        statusObservation = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            Task { @MainActor in
                guard let self, observedItem == self.player.currentItem else { return }
                switch observedItem.status {
                case .readyToPlay:
                    self.state.isBuffering = false
                    if observedItem.duration.seconds.isFinite && observedItem.duration.seconds > 0 {
                        self.state.durationMs = Int64(observedItem.duration.seconds * 1000.0)
                    }
                    self.updateNowPlayingInfo()
                case .failed:
                    self.handleRuntimePlaybackError(item: observedItem, error: observedItem.error)
                default:
                    break
                }
            }
        }

        itemEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.state.repeatMode == .singleLoop {
                    self.seek(to: 0)
                    self.player.play()
                } else {
                    self.next(isAutoAdvance: true)
                }
            }
        }

        itemFailedObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor in
                guard let self, let curItem = self.player.currentItem, curItem == item else { return }
                self.handleRuntimePlaybackError(item: curItem, error: err)
            }
        }
    }

    private func observeAudioSessionNotifications() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let info = note.userInfo,
                  let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeVal) else { return }
            let optsVal = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            Task { @MainActor in
                guard let self else { return }
                if type == .began {
                    self.player.pause()
                    self.state.isPlaying = false
                    self.updateNowPlayingInfo()
                } else if type == .ended {
                    let opts = AVAudioSession.InterruptionOptions(rawValue: optsVal)
                    if opts.contains(.shouldResume) {
                        self.activateAudioSession()
                        self.player.play()
                        self.state.isPlaying = true
                        self.updateNowPlayingInfo()
                    }
                }
            }
        }

        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let info = note.userInfo,
                  let reasonVal = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonVal),
                  reason == .oldDeviceUnavailable else { return }
            Task { @MainActor in
                guard let self else { return }
                self.player.pause()
                self.state.isPlaying = false
                self.updateNowPlayingInfo()
            }
        }
    }

    private func updateNowPlayingInfo() {
        guard let track = state.currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(state.positionMs) / 1000.0,
            MPMediaItemPropertyPlaybackDuration: Double(max(state.durationMs, track.durationMs)) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: state.isPlaying ? 1.0 : 0.0
        ]
        if let artworkImg = cachedArtworkImage {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: artworkImg.size) { _ in artworkImg }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func computeNextIndex(forceAdvance: Bool) -> Int? {
        guard !state.queue.isEmpty else { return nil }
        if state.queue.count == 1 { return 0 }
        switch state.repeatMode {
        case .singleLoop:
            return forceAdvance ? (state.currentIndex + 1) % state.queue.count : state.currentIndex
        case .shuffle:
            return randomOtherIndex()
        case .listLoop:
            return (state.currentIndex + 1) % state.queue.count
        }
    }

    private func randomOtherIndex() -> Int {
        guard state.queue.count > 1 else { return 0 }
        var idx = Int.random(in: 0..<state.queue.count)
        if idx == state.currentIndex {
            idx = (idx + 1) % state.queue.count
        }
        return idx
    }

    private func stopAndReset() {
        resolveTask?.cancel()
        recoveryTask?.cancel()
        clearPreloadedNext()
        player.pause()
        player.replaceCurrentItem(with: nil)
        state = PlayerState(repeatMode: state.repeatMode)
        cachedArtworkImage = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
