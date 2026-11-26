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
    var queueSource: PlaybackQueueSource? = nil
    var isPlaying: Bool = false
    var isBuffering: Bool = false
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var repeatMode: RepeatMode = .listLoop
    var activeQualityBadge: String? = nil
    var activeProviderName: String? = nil
    var activeStreamSourceId: String? = nil
    var lyrics: ParsedLyrics = ParsedLyrics()
    var isLoadingLyrics: Bool = false
    var ambientPalette: AmbientPalette = .default
    var sleepTimerRemainingMs: Int64? = nil
    var canSkipPrevious: Bool = false
    var canSkipNext: Bool = false

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
    let resolvedPlatformCode: String?
    let cacheKey: String?
    let createdAt: Date
}

private struct PendingBackgroundCache {
    let remoteUrlString: String
    let cacheKey: String
    let quality: AudioQuality
}

@MainActor
final class PlaybackProgressModel: ObservableObject {
    @Published var positionMs: Int64 = 0
    @Published var durationMs: Int64 = 0
    @Published var sleepTimerRemainingMs: Int64? = nil
}

@MainActor
final class PlayerController: ObservableObject {
    @Published private(set) var state = PlayerState()
    /// Isolated high-frequency progress observable so 0.4s playback ticks and 1s sleep timer ticks
    /// only redraw FloatingMiniPlayer / FullPlayerView instead of invalidating RootView's entire tree.
    let progress = PlaybackProgressModel()
    let events = PassthroughSubject<PlayerUiEvent, Never>()

    private static let preloadWindowMs: Int64 = 20_000
    private static let minTrackDurationForPreloadMs: Int64 = 25_000
    private static let preloadMaxAgeSec: TimeInterval = 300.0
    private static let bufferingWatchdogTimeoutNs: UInt64 = 12_000_000_000
    private static let stablePlaybackResetDeltaMs: Int64 = 8_000
    private static let cursorPersistIntervalSec: TimeInterval = 10.0
    private static let chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private let player = AVPlayer()
    private let queueNavigator = QueueNavigator()
    private let networkAdvisor: NetworkQualityAdvisor
    private let audioCacheManager: AudioCacheManager
    private let settingsStore: SettingsStore
    private let libraryStore: LibraryStore
    private let playbackSessionStore: PlaybackSessionStore
    private let userApiManager: UserApiManager
    private let musicAggregator: MusicAggregator
    private let downloadManager: DownloadManager

    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var itemEndObserver: NSObjectProtocol?
    private var itemFailedObserver: NSObjectProtocol?
    private var itemStalledObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?
    private var sourcesCancellable: AnyCancellable?

    private var resolveTask: Task<Void, Never>?
    private var recoveryTask: Task<Void, Never>?
    private var bufferingWatchdogTask: Task<Void, Never>?
    private var preloadTask: Task<Void, Never>?
    private var coverTask: Task<Void, Never>?
    private var lyricTask: Task<Void, Never>?
    private var paletteTask: Task<Void, Never>?
    private var sleepTimerTask: Task<Void, Never>?

    private var activeLoadToken = UUID()
    private var activeSeekToken = UUID()
    private var isSeeking = false
    private var isRestartingForPlay = false
    private var wantsPlayback = false
    private var hasEnteredReadyForActiveMedia = false
    private var activeStreamSourceId: String?
    private var activeResolvedTrackId: String?
    private var activeResolvedPlatformCode: String?
    private var activeCacheKey: String?
    private var pendingBackgroundCache: PendingBackgroundCache?
    private var pendingResumePositionMs: Int64 = 0
    private var pendingQualityOverride: AudioQuality?
    private var lastRecoveryStartPositionMs: Int64 = 0
    private var failedSourcePlatformsForCurrentTrack = Set<String>()
    private var failedSourceTracksForCurrentTrack = Set<String>()
    private var failedTrackIdsForCurrentTrack = Set<String>()
    private var runtimeRetryCount = 0
    private var consecutiveAutoSkipCount = 0
    private var isRecoveringError = false
    private var resumeAfterInterruption = false
    private var hasUserInitiatedPlayback = false

    private var preloadAttemptedForTrackId: String?
    private var preloadedNext: PreloadedNextItem?
    private var cachedArtworkImage: UIImage?
    private var cachedArtwork: MPMediaItemArtwork?
    private var visualsTrackId: String?
    private var pendingInitialSeekMs: Int64?
    private var lyricsLoadedForTrackId: String?
    private var lastCursorPersistUptime: TimeInterval = 0
    private var lastPersistedQueueCount: Int = 0
    private var lastPersistedSliceStart: Int = -1
    private var lastPersistedSliceCount: Int = 0
    private var remoteCommandTargets: [(MPRemoteCommand, Any)] = []

    init(
        networkAdvisor: NetworkQualityAdvisor,
        audioCacheManager: AudioCacheManager,
        settingsStore: SettingsStore,
        libraryStore: LibraryStore,
        playbackSessionStore: PlaybackSessionStore,
        userApiManager: UserApiManager,
        musicAggregator: MusicAggregator,
        downloadManager: DownloadManager
    ) {
        self.networkAdvisor = networkAdvisor
        self.audioCacheManager = audioCacheManager
        self.settingsStore = settingsStore
        self.libraryStore = libraryStore
        self.playbackSessionStore = playbackSessionStore
        self.userApiManager = userApiManager
        self.musicAggregator = musicAggregator
        self.downloadManager = downloadManager

        player.automaticallyWaitsToMinimizeStalling = true
        configureAudioSession()
        configureRemoteCommands()
        observePlayerState()
        observeAudioSessionNotifications()
        observeSourceConfigChanges()
        restoreLastSessionAsync()
    }

    private func observeSourceConfigChanges() {
        sourcesCancellable = userApiManager.$sources
            .map { list in
                list.map { src in
                    "\(src.id):\(src.enabled):\(src.priority):\(src.status == .ready):\(UserApiManager.computeSourceRevision(for: src))"
                }
            }
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] _ in
                guard let self else { return }
                self.clearPreloadedNext()
                if let cur = self.state.currentTrack,
                   !cur.isLocal,
                   !self.state.isPlaying,
                   !self.state.isBuffering,
                   self.resolveTask == nil {
                    self.detachCurrentPlayerItem()
                }
            }
    }

    func invalidatePreload() {
        clearPreloadedNext()
    }

    private func restoreLastSessionAsync() {
        Task { [weak self] in
            guard let self else { return }
            guard let restored = await self.playbackSessionStore.loadSessionAsync(),
                  !self.hasUserInitiatedPlayback,
                  self.state.queue.isEmpty,
                  restored.queue.indices.contains(restored.currentIndex) else {
                return
            }
            let track = restored.queue[restored.currentIndex]
            self.pendingResumePositionMs = restored.positionMs
            self.lastRecoveryStartPositionMs = restored.positionMs
            self.lastPersistedQueueCount = restored.queue.count
            self.lastPersistedSliceStart = 0
            self.lastPersistedSliceCount = restored.queue.count
            self.queueNavigator.onQueueReplaced(
                queueSize: restored.queue.count,
                startIndex: restored.currentIndex,
                mode: restored.repeatMode
            )
            self.progress.positionMs = restored.positionMs
            self.progress.durationMs = restored.durationMs
            self.progress.sleepTimerRemainingMs = nil
            self.state = PlayerState(
                queue: restored.queue,
                currentIndex: restored.currentIndex,
                queueSource: restored.queueSource,
                isPlaying: false,
                isBuffering: false,
                positionMs: restored.positionMs,
                durationMs: restored.durationMs,
                repeatMode: restored.repeatMode,
                activeQualityBadge: restored.activeQualityBadge,
                activeProviderName: restored.activeProviderName,
                lyrics: ParsedLyrics(),
                isLoadingLyrics: true,
                ambientPalette: .default,
                sleepTimerRemainingMs: nil,
                canSkipPrevious: self.queueNavigator.canSkipPrevious(
                    queueSize: restored.queue.count,
                    currentIndex: restored.currentIndex,
                    mode: restored.repeatMode
                ),
                canSkipNext: self.queueNavigator.canSkipNext(
                    queueSize: restored.queue.count,
                    currentIndex: restored.currentIndex,
                    mode: restored.repeatMode
                )
            )
            self.syncNavigationCapabilities()
            self.loadMetadataAndVisuals(for: track)
            self.updateNowPlayingInfo()
        }
    }

    func clearCachedArtwork() {
        cachedArtworkImage = nil
        cachedArtwork = nil
    }

    private func computeRelativePersistedIndex(queueCount: Int, currentIndex: Int) -> Int {
        if queueCount > PlaybackSessionStore.maxPersistedQueueSize && lastPersistedSliceStart >= 0 {
            return min(max(currentIndex - lastPersistedSliceStart, 0), max(lastPersistedSliceCount - 1, 0))
        }
        return currentIndex
    }

    private func persistQueueAndCursor(synchronous: Bool = false) {
        lastCursorPersistUptime = ProcessInfo.processInfo.systemUptime
        let qCount = state.queue.count
        if qCount > PlaybackSessionStore.maxPersistedQueueSize {
            let maxSize = PlaybackSessionStore.maxPersistedQueueSize
            let clamped = min(max(state.currentIndex, 0), max(qCount - 1, 0))
            let start = min(max(clamped - maxSize / 2, 0), max(qCount - maxSize, 0))
            lastPersistedQueueCount = qCount
            lastPersistedSliceStart = start
            lastPersistedSliceCount = maxSize
        } else {
            lastPersistedQueueCount = qCount
            lastPersistedSliceStart = 0
            lastPersistedSliceCount = qCount
        }
        playbackSessionStore.saveQueueAndCursor(
            queue: state.queue,
            queueSource: state.queueSource,
            currentIndex: state.currentIndex,
            positionMs: progress.positionMs,
            durationMs: max(progress.durationMs, state.durationMs),
            repeatMode: state.repeatMode,
            activeQualityBadge: state.activeQualityBadge,
            activeProviderName: state.activeProviderName,
            synchronous: synchronous
        )
    }

    private func persistCursor(force: Bool, synchronous: Bool = false) {
        guard let track = state.currentTrack, state.currentIndex >= 0 else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if !force && (now - lastCursorPersistUptime) < Self.cursorPersistIntervalSec {
            return
        }
        lastCursorPersistUptime = now
        if state.queue.count > PlaybackSessionStore.maxPersistedQueueSize {
            let outsideSlice = lastPersistedQueueCount != state.queue.count ||
                lastPersistedSliceStart < 0 ||
                state.currentIndex < lastPersistedSliceStart ||
                state.currentIndex >= (lastPersistedSliceStart + lastPersistedSliceCount)
            if outsideSlice {
                persistQueueAndCursor(synchronous: synchronous)
                return
            }
        }
        let persistedIdx = computeRelativePersistedIndex(queueCount: state.queue.count, currentIndex: state.currentIndex)
        playbackSessionStore.saveCursorOnly(
            currentIndex: persistedIdx,
            currentTrackId: track.id,
            positionMs: progress.positionMs,
            durationMs: max(progress.durationMs, state.durationMs),
            repeatMode: state.repeatMode,
            activeQualityBadge: state.activeQualityBadge,
            activeProviderName: state.activeProviderName,
            synchronous: synchronous
        )
    }

    func persistSnapshotNow(synchronous: Bool = true) {
        guard state.currentTrack != nil else {
            if synchronous {
                playbackSessionStore.flush()
            }
            return
        }
        if player.currentItem != nil, pendingInitialSeekMs == nil, !isSeeking {
            let secs = player.currentTime().seconds
            if secs.isFinite && secs >= 0 {
                let liveMs = Int64(secs * 1000.0)
                if liveMs > 0 || progress.positionMs == 0 {
                    progress.positionMs = liveMs
                    pendingResumePositionMs = liveMs
                }
            }
        }
        persistCursor(force: true, synchronous: synchronous)
    }

    private func detachCurrentPlayerItem() {
        isRestartingForPlay = false
        hasEnteredReadyForActiveMedia = false
        pendingBackgroundCache = nil
        bufferingWatchdogTask?.cancel()
        bufferingWatchdogTask = nil
        statusObservation?.invalidate()
        statusObservation = nil
        if let obs = itemEndObserver {
            NotificationCenter.default.removeObserver(obs)
            itemEndObserver = nil
        }
        if let obs = itemFailedObserver {
            NotificationCenter.default.removeObserver(obs)
            itemFailedObserver = nil
        }
        if let obs = itemStalledObserver {
            NotificationCenter.default.removeObserver(obs)
            itemStalledObserver = nil
        }
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    deinit {
        if let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        for (command, token) in remoteCommandTargets {
            command.removeTarget(token)
        }
        [itemEndObserver, itemFailedObserver, itemStalledObserver, interruptionObserver, routeChangeObserver].forEach {
            if let obs = $0 { NotificationCenter.default.removeObserver(obs) }
        }
    }

    // MARK: - Public Controls

    private func syncNavigationCapabilities() {
        let prev = queueNavigator.canSkipPrevious(
            queueSize: state.queue.count,
            currentIndex: state.currentIndex,
            mode: state.repeatMode
        )
        let next = queueNavigator.canSkipNext(
            queueSize: state.queue.count,
            currentIndex: state.currentIndex,
            mode: state.repeatMode
        )
        if state.canSkipPrevious != prev {
            state.canSkipPrevious = prev
        }
        if state.canSkipNext != next {
            state.canSkipNext = next
        }
        let center = MPRemoteCommandCenter.shared()
        center.previousTrackCommand.isEnabled = !state.queue.isEmpty
        center.nextTrackCommand.isEnabled = next
    }

    func playTrack(_ track: Track, inQueue queue: [Track]? = nil, queueSource: PlaybackQueueSource? = nil) {
        if let queue, !queue.isEmpty {
            let idx = queue.firstIndex(where: { $0.id == track.id }) ?? 0
            playTracks(queue, startIndex: idx, queueSource: queueSource)
        } else {
            playTracks([track], startIndex: 0, queueSource: queueSource)
        }
    }

    func playTracks(_ tracks: [Track], startIndex: Int = 0, queueSource: PlaybackQueueSource? = nil) {
        guard !tracks.isEmpty else { return }
        hasUserInitiatedPlayback = true
        let safeIndex = min(max(startIndex, 0), tracks.count - 1)
        if resolveTask != nil &&
            state.currentTrack?.id == tracks[safeIndex].id &&
            state.queue.count == tracks.count &&
            state.currentIndex == safeIndex {
            // Avoid cancelling and restarting an identical in-flight stream resolution on rapid double-taps
            return
        }
        consecutiveAutoSkipCount = 0
        pendingQualityOverride = nil
        clearPreloadedNext()
        queueNavigator.onQueueReplaced(queueSize: tracks.count, startIndex: safeIndex, mode: state.repeatMode)
        state.queue = tracks
        state.currentIndex = safeIndex
        state.queueSource = queueSource
        state.positionMs = 0
        state.durationMs = tracks[safeIndex].durationMs
        syncNavigationCapabilities()
        progress.positionMs = 0
        progress.durationMs = tracks[safeIndex].durationMs
        persistQueueAndCursor()
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
    }

    func selectQueueIndex(_ index: Int) {
        guard index >= 0 && index < state.queue.count else { return }
        hasUserInitiatedPlayback = true
        consecutiveAutoSkipCount = 0
        pendingQualityOverride = nil
        clearPreloadedNext()
        queueNavigator.onManualSelectIndex(
            queueSize: state.queue.count,
            targetIndex: index,
            mode: state.repeatMode
        )
        state.currentIndex = index
        state.positionMs = 0
        state.durationMs = state.queue[index].durationMs
        syncNavigationCapabilities()
        progress.positionMs = 0
        progress.durationMs = state.queue[index].durationMs
        persistCursor(force: true)
        loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
    }

    func playNext(_ track: Track) {
        hasUserInitiatedPlayback = true
        if state.queue.isEmpty || state.currentIndex < 0 {
            playTracks([track], startIndex: 0)
            return
        }
        var updated = state.queue
        let existingIdx = updated.firstIndex(where: { $0.id == track.id })
        if existingIdx == state.currentIndex {
            events.send(PlayerUiEvent(message: "《\(track.title)》正在播放"))
            return
        }
        if let existingIdx {
            updated.remove(at: existingIdx)
            if existingIdx < state.currentIndex {
                state.currentIndex -= 1
            }
        }
        let insertIdx = min(state.currentIndex + 1, updated.count)
        updated.insert(track, at: insertIdx)
        state.queue = updated
        queueNavigator.onTrackInsertedAsPlayNext(
            oldRemovedIndex: existingIdx,
            insertedIndex: insertIdx,
            newCurrentIndex: state.currentIndex,
            newQueueSize: updated.count,
            mode: state.repeatMode
        )
        syncNavigationCapabilities()
        clearPreloadedNext()
        persistQueueAndCursor()
        events.send(PlayerUiEvent(message: "已将《\(track.title)》设为下一首播放"))
    }

    func addToQueue(_ track: Track) {
        hasUserInitiatedPlayback = true
        if state.queue.isEmpty {
            playTracks([track], startIndex: 0)
            return
        }
        if !state.queue.contains(where: { $0.id == track.id }) {
            let wasAtQueueTail = (state.currentIndex == state.queue.count - 1)
            state.queue.append(track)
            queueNavigator.onTrackAppendedToQueue(
                appendedIndex: state.queue.count - 1,
                currentIndex: state.currentIndex,
                newQueueSize: state.queue.count,
                mode: state.repeatMode
            )
            if wasAtQueueTail || state.repeatMode == .shuffle {
                clearPreloadedNext()
            }
            syncNavigationCapabilities()
            persistQueueAndCursor()
            events.send(PlayerUiEvent(message: "已加入播放队列"))
        } else {
            events.send(PlayerUiEvent(message: "《\(track.title)》已在播放队列中"))
        }
    }

    func removeFromQueue(at index: Int) {
        hasUserInitiatedPlayback = true
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
            queueNavigator.onTrackRemovedFromQueue(
                removedIndex: index,
                newCurrentIndex: state.currentIndex,
                newQueueSize: state.queue.count,
                mode: state.repeatMode
            )
            syncNavigationCapabilities()
            persistQueueAndCursor()
        } else if removingCurrent {
            let nextIdx = min(index, state.queue.count - 1)
            pendingQualityOverride = nil
            state.currentIndex = nextIdx
            state.positionMs = 0
            state.durationMs = state.queue[nextIdx].durationMs
            queueNavigator.onTrackRemovedFromQueue(
                removedIndex: index,
                newCurrentIndex: nextIdx,
                newQueueSize: state.queue.count,
                mode: state.repeatMode
            )
            syncNavigationCapabilities()
            progress.positionMs = 0
            progress.durationMs = state.queue[nextIdx].durationMs
            persistQueueAndCursor()
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
        } else {
            queueNavigator.onTrackRemovedFromQueue(
                removedIndex: index,
                newCurrentIndex: state.currentIndex,
                newQueueSize: state.queue.count,
                mode: state.repeatMode
            )
            syncNavigationCapabilities()
            persistQueueAndCursor()
        }
    }

    func clearQueue() {
        hasUserInitiatedPlayback = true
        stopAndReset()
    }

    func togglePlayPause() {
        if state.currentTrack == nil { return }
        // If user taps Play while an auto-skip countdown is pending after a failure (button visibly shows ▶),
        // cancel the countdown and immediately retry playing the current track instead of swallowing the tap.
        if recoveryTask != nil && resolveTask == nil && !isRecoveringError && !state.isPlaying && !state.isBuffering {
            recoveryTask?.cancel()
            recoveryTask = nil
            let resumeMs = max(progress.positionMs, pendingResumePositionMs)
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: resumeMs)
            return
        }
        if resolveTask != nil || recoveryTask != nil || isRecoveringError {
            wantsPlayback = false
            resolveTask?.cancel()
            resolveTask = nil
            recoveryTask?.cancel()
            recoveryTask = nil
            bufferingWatchdogTask?.cancel()
            bufferingWatchdogTask = nil
            activeLoadToken = UUID()
            activeSeekToken = UUID()
            isSeeking = false
            isRestartingForPlay = false
            isRecoveringError = false
            resumeAfterInterruption = false
            player.pause()
            state.isBuffering = false
            state.isPlaying = false
            pendingResumePositionMs = max(progress.positionMs, pendingResumePositionMs)
            persistCursor(force: true)
            updateNowPlayingInfo()
            return
        }
        if player.currentItem == nil || player.currentItem?.status == .failed {
            let resumeMs = max(progress.positionMs, pendingResumePositionMs)
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: resumeMs)
            return
        }
        if state.isPlaying || state.isBuffering || isRestartingForPlay || player.timeControlStatus != .paused {
            let wasStuckBeforeReady = !hasEnteredReadyForActiveMedia
            wantsPlayback = false
            resumeAfterInterruption = false
            activeSeekToken = UUID()
            isSeeking = false
            isRestartingForPlay = false
            bufferingWatchdogTask?.cancel()
            bufferingWatchdogTask = nil
            if wasStuckBeforeReady {
                // Detach stalled item so the next Play tap re-resolves cleanly instead of hanging on a dead CDN URL
                detachCurrentPlayerItem()
            } else {
                player.pause()
            }
            state.isPlaying = false
            state.isBuffering = false
            pendingResumePositionMs = progress.positionMs
            persistCursor(force: true)
            updateNowPlayingInfo()
        } else {
            wantsPlayback = true
            activateAudioSession()
            if pendingInitialSeekMs != nil && player.currentItem?.status == .unknown {
                state.isPlaying = true
                state.isBuffering = true
            } else {
                player.play()
                state.isPlaying = true
                if player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
                    state.isBuffering = true
                } else if player.timeControlStatus == .playing {
                    state.isBuffering = false
                }
            }
            scheduleBufferingWatchdogIfNeeded()
            updateNowPlayingInfo()
        }
    }

    func seek(to positionMs: Int64) {
        let clamped = max(positionMs, 0)
        progress.positionMs = clamped
        state.positionMs = clamped
        pendingResumePositionMs = clamped
        let resumePlayAfterSeek = isRestartingForPlay || (pendingInitialSeekMs != nil && state.isPlaying)
        isRestartingForPlay = false
        if let item = player.currentItem {
            if item.status == .readyToPlay {
                pendingInitialSeekMs = nil
                let token = UUID()
                activeSeekToken = token
                isSeeking = true
                isRestartingForPlay = resumePlayAfterSeek
                let target = CMTime(value: clamped, timescale: 1000)
                player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] _ in
                    Task { @MainActor in
                        guard let self, self.activeSeekToken == token else { return }
                        self.isSeeking = false
                        let shouldStartPlay = self.isRestartingForPlay
                        self.isRestartingForPlay = false
                        guard let item, self.player.currentItem == item else { return }
                        self.progress.positionMs = clamped
                        self.state.positionMs = clamped
                        self.pendingResumePositionMs = clamped
                        if shouldStartPlay && self.state.isPlaying {
                            self.activateAudioSession()
                            self.player.play()
                            self.state.isBuffering = (self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
                        }
                        self.updateNowPlayingInfo()
                    }
                }
            } else if item.status == .unknown {
                pendingInitialSeekMs = clamped
                updateNowPlayingInfo()
            } else {
                updateNowPlayingInfo()
            }
        } else {
            updateNowPlayingInfo()
        }
        persistCursor(force: true)
    }

    func next(isAutoAdvance: Bool = false) {
        guard !state.queue.isEmpty else { return }
        hasUserInitiatedPlayback = true
        if !isAutoAdvance { consecutiveAutoSkipCount = 0 }

        // Check preloaded next first only if it matches QueueNavigator's exact peeked next index
        let expectedPeek: Int? = (preloadedNext != nil && state.repeatMode != .singleLoop)
            ? queueNavigator.peekNextIndexForAutoAdvance(
                queueSize: state.queue.count,
                currentIndex: state.currentIndex,
                mode: state.repeatMode
            )
            : nil
        if let preloaded = preloadedNext,
           preloaded.forTrackId == state.currentTrack?.id,
           preloaded.nextIndex == expectedPeek,
           preloaded.nextIndex >= 0,
           preloaded.nextIndex < state.queue.count,
           preloaded.track.id == state.queue[preloaded.nextIndex].id,
           (!preloaded.isRemote || Date().timeIntervalSince(preloaded.createdAt) <= Self.preloadMaxAgeSec) {
            queueNavigator.onPromotedPreloadedNext(
                queueSize: state.queue.count,
                promotedIndex: preloaded.nextIndex,
                mode: state.repeatMode
            )
            resolveTask?.cancel()
            resolveTask = nil
            recoveryTask?.cancel()
            recoveryTask = nil
            clearPreloadedNext()
            activeLoadToken = UUID()
            failedSourcePlatformsForCurrentTrack.removeAll()
            failedSourceTracksForCurrentTrack.removeAll()
            failedTrackIdsForCurrentTrack.removeAll()
            runtimeRetryCount = 0
            isRecoveringError = false
            lastRecoveryStartPositionMs = 0
            pendingResumePositionMs = 0
            pendingQualityOverride = nil
            pendingInitialSeekMs = nil
            isSeeking = false

            state.currentIndex = preloaded.nextIndex
            state.positionMs = 0
            syncNavigationCapabilities()
            progress.positionMs = 0
            progress.durationMs = preloaded.track.durationMs
            persistCursor(force: true)
            playPreparedTrack(
                track: preloaded.track,
                url: preloaded.playbackURL,
                isRemote: preloaded.isRemote,
                quality: preloaded.quality,
                qualityBadge: preloaded.qualityBadge,
                providerName: preloaded.providerName,
                sourceId: preloaded.sourceId,
                resolvedTrackId: preloaded.resolvedTrackId,
                resolvedPlatformCode: preloaded.resolvedPlatformCode,
                cacheKey: preloaded.cacheKey,
                seekToMs: 0
            )
            return
        }

        let outcome = queueNavigator.navigateNext(
            queueSize: state.queue.count,
            currentIndex: state.currentIndex,
            mode: state.repeatMode,
            isAutoAdvance: isAutoAdvance
        )
        switch outcome {
        case .switchTo(let nextIdx):
            pendingQualityOverride = nil
            state.currentIndex = nextIdx
            state.positionMs = 0
            syncNavigationCapabilities()
            progress.positionMs = 0
            progress.durationMs = state.queue[nextIdx].durationMs
            persistCursor(force: true)
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
        case .restartCurrent(let msg):
            restartCurrentTrack(forcePlay: isAutoAdvance || msg != nil, message: msg)
        case .stayAtBoundary(let msg):
            events.send(PlayerUiEvent(message: msg))
        case .stopAtEnd(let msg):
            stopAtQueueEnd(message: msg)
        case .noOp:
            break
        }
    }

    func previous() {
        guard !state.queue.isEmpty else { return }
        hasUserInitiatedPlayback = true
        let outcome = queueNavigator.navigatePrevious(
            queueSize: state.queue.count,
            currentIndex: state.currentIndex,
            mode: state.repeatMode,
            positionMs: progress.positionMs
        )
        switch outcome {
        case .switchTo(let prevIdx):
            consecutiveAutoSkipCount = 0
            pendingQualityOverride = nil
            state.currentIndex = prevIdx
            state.positionMs = 0
            syncNavigationCapabilities()
            progress.positionMs = 0
            progress.durationMs = state.queue[prevIdx].durationMs
            persistCursor(force: true)
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
        case .restartCurrent(let msg):
            consecutiveAutoSkipCount = 0
            restartCurrentTrack(forcePlay: msg != nil, message: msg)
        case .stayAtBoundary(let msg):
            events.send(PlayerUiEvent(message: msg))
        case .stopAtEnd(let msg):
            stopAtQueueEnd(message: msg)
        case .noOp:
            break
        }
    }

    private func restartCurrentTrack(forcePlay: Bool, message: String?) {
        guard state.currentTrack != nil else { return }
        let alreadyLoadingFromStart = resolveTask != nil &&
            !isRecoveringError &&
            pendingResumePositionMs == 0 &&
            pendingInitialSeekMs == nil
        lastRecoveryStartPositionMs = 0
        pendingResumePositionMs = 0

        if resolveTask != nil || recoveryTask != nil || isRecoveringError {
            progress.positionMs = 0
            state.positionMs = 0
            pendingInitialSeekMs = nil
            if forcePlay && !alreadyLoadingFromStart {
                loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
            }
            if let message, !message.isEmpty {
                events.send(PlayerUiEvent(message: message))
            }
            return
        }

        clearPreloadedNext()
        if let item = player.currentItem, item.status == .readyToPlay {
            let shouldPlay = forcePlay || state.isPlaying
            progress.positionMs = 0
            state.positionMs = 0
            pendingInitialSeekMs = nil
            let token = UUID()
            activeSeekToken = token
            isSeeking = true
            isRestartingForPlay = shouldPlay
            if shouldPlay {
                wantsPlayback = true
                state.isPlaying = true
                state.isBuffering = true
                updateNowPlayingInfo()
            }
            player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak item] _ in
                Task { @MainActor in
                    guard let self, self.activeSeekToken == token else { return }
                    self.isSeeking = false
                    let resumePlay = self.isRestartingForPlay || shouldPlay || self.state.isPlaying
                    self.isRestartingForPlay = false
                    guard let item, self.player.currentItem == item else { return }
                    if item.status == .failed {
                        if resumePlay {
                            self.loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
                        }
                        return
                    }
                    self.progress.positionMs = 0
                    self.state.positionMs = 0
                    self.pendingResumePositionMs = 0
                    if resumePlay {
                        self.activateAudioSession()
                        self.player.play()
                        self.state.isPlaying = true
                        self.state.isBuffering = (self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
                    } else {
                        self.state.isBuffering = false
                    }
                    self.updateNowPlayingInfo()
                }
            }
            persistCursor(force: true)
        } else if let item = player.currentItem, item.status == .unknown {
            let shouldPlay = forcePlay || state.isPlaying
            progress.positionMs = 0
            state.positionMs = 0
            pendingInitialSeekMs = nil
            isRestartingForPlay = false
            if shouldPlay {
                wantsPlayback = true
                activateAudioSession()
                player.play()
                state.isPlaying = true
                state.isBuffering = (player.timeControlStatus != .playing)
            } else {
                wantsPlayback = false
                player.pause()
                state.isPlaying = false
                state.isBuffering = false
            }
            persistCursor(force: true)
            updateNowPlayingInfo()
        } else if forcePlay || state.isPlaying {
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: 0)
        } else {
            progress.positionMs = 0
            state.positionMs = 0
            pendingInitialSeekMs = nil
            persistCursor(force: true)
            updateNowPlayingInfo()
        }

        if let message, !message.isEmpty {
            events.send(PlayerUiEvent(message: message))
        }
    }

    private func stopAtQueueEnd(message: String?) {
        resolveTask?.cancel()
        resolveTask = nil
        recoveryTask?.cancel()
        recoveryTask = nil
        clearPreloadedNext()
        activeLoadToken = UUID()
        activeSeekToken = UUID()
        isSeeking = false
        isRecoveringError = false
        resumeAfterInterruption = false
        pendingInitialSeekMs = nil
        pendingResumePositionMs = 0
        lastRecoveryStartPositionMs = 0
        consecutiveAutoSkipCount = 0

        // Detach the ended item cleanly instead of issuing an unguarded async seek(to: .zero),
        // eliminating timeObserver/timeControl races and expired-URL auto-recovery.
        wantsPlayback = false
        detachCurrentPlayerItem()
        progress.positionMs = 0
        state.positionMs = 0
        state.isPlaying = false
        state.isBuffering = false
        syncNavigationCapabilities()
        persistCursor(force: true)
        updateNowPlayingInfo()
        if let message, !message.isEmpty {
            events.send(PlayerUiEvent(message: message))
        }
    }

    func cycleRepeatMode() {
        let nextMode = state.repeatMode.next()
        state.repeatMode = nextMode
        queueNavigator.onModeChanged(
            queueSize: state.queue.count,
            currentIndex: state.currentIndex,
            newMode: nextMode
        )
        syncNavigationCapabilities()
        clearPreloadedNext()
        persistCursor(force: true)
        events.send(PlayerUiEvent(message: "已切换为\(nextMode.label)"))
    }

    func changePreferredQuality(_ quality: AudioQuality) {
        settingsStore.update { $0.preferredQuality = quality }
        clearPreloadedNext()
        guard let track = state.currentTrack, !track.isLocal else { return }
        if state.activeQualityBadge == "离线" {
            events.send(PlayerUiEvent(message: "已更新偏好音质为 \(quality.label)（当前播放离线文件）"))
            return
        }
        let resumePos = max(progress.positionMs, pendingResumePositionMs)
        pendingResumePositionMs = resumePos
        let hasHealthyLoadedItem = player.currentItem != nil && player.currentItem?.status != .failed
        if state.isPlaying || state.isBuffering || resolveTask != nil || !hasHealthyLoadedItem {
            events.send(PlayerUiEvent(message: "正在切换至 \(quality.label)"))
            loadAndPlayCurrent(resetRecoveryState: true, seekToMs: resumePos, qualityOverride: quality)
        } else {
            // Stay paused; detach current item so the next Play resolves in the newly chosen quality
            recoveryTask?.cancel()
            recoveryTask = nil
            isRecoveringError = false
            detachCurrentPlayerItem()
            pendingQualityOverride = quality
            state.activeQualityBadge = quality.badge
            persistCursor(force: true)
            events.send(PlayerUiEvent(message: "已更新偏好音质为 \(quality.label)，继续播放时生效"))
        }
    }

    func switchSource(sourceId: String) {
        guard let track = state.currentTrack else { return }
        if track.isLocal {
            events.send(PlayerUiEvent(message: "当前播放本地音频，无需切换在线音源"))
            return
        }
        let sourceName = userApiManager.sources.first(where: { $0.id == sourceId })?.name ?? "指定音源"
        userApiManager.clearCooldowns(forSourceId: sourceId)
        let resumePos = max(progress.positionMs, pendingResumePositionMs)
        events.send(PlayerUiEvent(message: "正在切换至音源：\(sourceName)"))
        loadAndPlayCurrent(
            resetRecoveryState: true,
            seekToMs: resumePos,
            preferredSourceId: sourceId,
            bypassOfflineCache: true
        )
    }

    func switchToAlternateSource() {
        guard let track = state.currentTrack else { return }
        if track.isLocal {
            events.send(PlayerUiEvent(message: "当前播放本地音频，无需换源"))
            return
        }
        let curSid = activeStreamSourceId ?? state.activeStreamSourceId
        let curPlat = activeResolvedPlatformCode ?? track.source.code
        let curResolvedId = activeResolvedTrackId ?? track.id
        if let curSid, !curSid.isEmpty {
            failedSourceTracksForCurrentTrack.insert(UserApiManager.sourceTrackKey(sourceId: curSid, trackId: curResolvedId))
            failedSourcePlatformsForCurrentTrack.insert(UserApiManager.sourcePlatformKey(sourceId: curSid, platformCode: curPlat))
        }
        if curResolvedId != track.id {
            failedTrackIdsForCurrentTrack.insert(curResolvedId)
        }
        let resumePos = max(progress.positionMs, pendingResumePositionMs)
        events.send(PlayerUiEvent(message: "正在尝试切换其他音源或平台…"))
        loadAndPlayCurrent(
            resetRecoveryState: true,
            seekToMs: resumePos,
            preferredSourceId: nil,
            preserveExclusions: true,
            bypassOfflineCache: true
        )
    }

    func setSleepTimer(minutes: Int?) {
        sleepTimerTask?.cancel()
        sleepTimerTask = nil

        guard let minutes, minutes > 0 else {
            progress.sleepTimerRemainingMs = nil
            state.sleepTimerRemainingMs = nil
            events.send(PlayerUiEvent(message: "已关闭睡眠定时"))
            return
        }

        let endDate = Date().addingTimeInterval(TimeInterval(minutes * 60))
        let totalMs = Int64(minutes) * 60_000
        progress.sleepTimerRemainingMs = totalMs
        state.sleepTimerRemainingMs = totalMs
        events.send(PlayerUiEvent(message: "已设定 \(minutes) 分钟后自动暂停"))

        sleepTimerTask = Task { [weak self] in
            while !Task.isCancelled {
                let remainingSec = endDate.timeIntervalSinceNow
                let remainingMs = Int64(max(remainingSec, 0) * 1000.0)
                guard let self else { return }
                if remainingMs <= 0 { break }
                self.progress.sleepTimerRemainingMs = remainingMs
                let sleepNs = UInt64(min(remainingMs, 1_000)) * 1_000_000
                try? await Task.sleep(nanoseconds: sleepNs)
            }
            guard !Task.isCancelled, let self else { return }
            self.wantsPlayback = false
            self.resumeAfterInterruption = false
            self.resolveTask?.cancel()
            self.resolveTask = nil
            self.recoveryTask?.cancel()
            self.recoveryTask = nil
            self.bufferingWatchdogTask?.cancel()
            self.bufferingWatchdogTask = nil
            self.activeLoadToken = UUID()
            self.activeSeekToken = UUID()
            self.isSeeking = false
            self.isRestartingForPlay = false
            self.isRecoveringError = false
            self.player.pause()
            self.state.isPlaying = false
            self.state.isBuffering = false
            self.progress.sleepTimerRemainingMs = nil
            self.state.sleepTimerRemainingMs = nil
            self.persistCursor(force: true)
            self.updateNowPlayingInfo()
            self.events.send(PlayerUiEvent(message: "睡眠定时已到，已暂停播放"))
        }
    }

    // MARK: - Core Playback Pipeline

    private func loadAndPlayCurrent(
        resetRecoveryState: Bool,
        seekToMs: Int64,
        preferredSourceId: String? = nil,
        qualityOverride: AudioQuality? = nil,
        preserveExclusions: Bool = false,
        bypassOfflineCache: Bool = false
    ) {
        guard let track = state.currentTrack else { return }

        let effectiveQualityOverride = qualityOverride ?? pendingQualityOverride
        pendingQualityOverride = nil
        let previousActiveSourceId = activeStreamSourceId

        resolveTask?.cancel()
        resolveTask = nil
        recoveryTask?.cancel()
        recoveryTask = nil
        clearPreloadedNext()
        detachCurrentPlayerItem()

        if resetRecoveryState {
            if !preserveExclusions {
                failedSourcePlatformsForCurrentTrack.removeAll()
                failedSourceTracksForCurrentTrack.removeAll()
                failedTrackIdsForCurrentTrack.removeAll()
            }
            activeStreamSourceId = nil
            activeResolvedTrackId = nil
            activeResolvedPlatformCode = nil
            activeCacheKey = nil
            runtimeRetryCount = 0
            isRecoveringError = false
            lastRecoveryStartPositionMs = 0
        }

        let loadToken = UUID()
        activeLoadToken = loadToken
        activeSeekToken = UUID()
        isSeeking = false
        wantsPlayback = true
        pendingResumePositionMs = max(seekToMs, 0)
        pendingInitialSeekMs = seekToMs > 0 ? seekToMs : nil

        let reuseVisuals = visualsTrackId == track.id && (lyricsLoadedForTrackId == track.id || !resetRecoveryState)
        state.isPlaying = false
        state.isBuffering = true
        let initialPos = max(seekToMs, 0)
        let initialDur = max(track.durationMs, reuseVisuals ? max(progress.durationMs, state.durationMs) : 0)
        state.positionMs = initialPos
        state.durationMs = initialDur
        progress.positionMs = initialPos
        progress.durationMs = initialDur
        if resetRecoveryState {
            state.activeQualityBadge = effectiveQualityOverride?.badge ?? (reuseVisuals ? state.activeQualityBadge : nil)
            state.activeProviderName = nil
            state.activeStreamSourceId = nil
            if !reuseVisuals {
                loadMetadataAndVisuals(for: track)
            }
        }
        persistCursor(force: true)
        updateNowPlayingInfo()

        let settings = settingsStore.settings

        resolveTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.activeLoadToken == loadToken {
                    self.resolveTask = nil
                }
            }

            // 1. Local file track
            if track.isLocal, let localUri = track.localUri {
                let localUrl = Self.resolveLocalFileURL(from: localUri)
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
                        resolvedPlatformCode: track.source.code,
                        cacheKey: nil,
                        seekToMs: self.pendingResumePositionMs
                    )
                    return
                }
            }

            // 2. Offline downloaded file (bypassed on explicit online source switch or runtime error fallback)
            if resetRecoveryState && !bypassOfflineCache, let offlineUrl = await self.downloadManager.localCachedFileURL(for: track.id) {
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
                    resolvedPlatformCode: track.source.code,
                    cacheKey: nil,
                    seekToMs: self.pendingResumePositionMs
                )
                return
            }

            // 3. Determine effective quality (honors explicit manual quality override even on cellular)
            let qualityDecision: EffectiveQualityDecision
            if let overrideQuality = effectiveQualityOverride {
                qualityDecision = EffectiveQualityDecision(quality: overrideQuality, downgradedForCellular: false)
            } else {
                qualityDecision = self.networkAdvisor.decideQuality(
                    preferred: settings.preferredQuality,
                    cellularDataSaverEnabled: settings.wifiOnlyHighQuality
                )
            }
            let targetQuality = qualityDecision.quality

            // 4. Resolve online stream via UserApiManager (priority-aware + scoped cache lookup after resolution)
            let result = await self.userApiManager.resolveStream(
                track: track,
                preferredQuality: targetQuality,
                allowCrossPlatformFallback: settings.autoFallbackSource,
                preferredSourceId: preferredSourceId,
                excludedTrackIds: self.failedTrackIdsForCurrentTrack,
                excludedSourcePlatforms: self.failedSourcePlatformsForCurrentTrack,
                excludedSourceTracks: self.failedSourceTracksForCurrentTrack
            )
            guard self.activeLoadToken == loadToken else { return }

            switch result {
            case .success(let resolved):
                guard let remoteUrl = LxJsRuntime.parseLenientURL(resolved.url) else {
                    self.handleResolutionFailure(track: track, message: "返回的音频地址无效")
                    return
                }
                let actuallySwitchedSource = resolved.switchedSource ||
                    ((!resetRecoveryState || preserveExclusions) &&
                        previousActiveSourceId != nil &&
                        resolved.sourceId != previousActiveSourceId)
                if qualityDecision.downgradedForCellular {
                    self.events.send(PlayerUiEvent(message: "当前为移动网络，已智能省流切换 \(resolved.quality.badge)"))
                } else if resolved.switchedPlatform {
                    self.events.send(PlayerUiEvent(message: "已自动换源至 \(resolved.resolvedTrack.source.shortLabel) · \(resolved.providerName)"))
                } else if actuallySwitchedSource {
                    self.events.send(PlayerUiEvent(message: "已自动切换至备用音源：\(resolved.providerName)"))
                }
                let finalCacheKey = AudioCacheManager.cacheKey(
                    trackId: track.id,
                    resolvedTrackId: resolved.resolvedTrack.id,
                    sourceId: resolved.sourceId,
                    sourceRevision: resolved.sourceRevision,
                    quality: resolved.quality
                )
                // Check scoped disk cache (keyed by track + resolvedTrack + source + script revision + quality)
                if resetRecoveryState && !preserveExclusions,
                   let cachedUrl = await self.audioCacheManager.cachedFileURLAsync(for: finalCacheKey) {
                    guard self.activeLoadToken == loadToken else { return }
                    self.playPreparedTrack(
                        track: track,
                        url: cachedUrl,
                        isRemote: false,
                        quality: resolved.quality,
                        qualityBadge: "\(resolved.quality.badge) · 缓存",
                        providerName: resolved.providerName,
                        sourceId: resolved.sourceId,
                        resolvedTrackId: resolved.resolvedTrack.id,
                        resolvedPlatformCode: resolved.resolvedTrack.source.code,
                        cacheKey: finalCacheKey,
                        seekToMs: self.pendingResumePositionMs
                    )
                    return
                }
                self.playPreparedTrack(
                    track: track,
                    url: remoteUrl,
                    isRemote: true,
                    quality: resolved.quality,
                    qualityBadge: resolved.quality.badge,
                    providerName: resolved.providerName,
                    sourceId: resolved.sourceId,
                    resolvedTrackId: resolved.resolvedTrack.id,
                    resolvedPlatformCode: resolved.resolvedTrack.source.code,
                    cacheKey: finalCacheKey,
                    seekToMs: self.pendingResumePositionMs
                )

            case .failure(let error):
                if preserveExclusions {
                    self.failedSourcePlatformsForCurrentTrack.removeAll()
                    self.failedSourceTracksForCurrentTrack.removeAll()
                    self.failedTrackIdsForCurrentTrack.removeAll()
                }
                let noSource = !self.userApiManager.hasReadySource()
                let msg = (preserveExclusions && !noSource)
                    ? "暂无其他可用音源或平台，点击播放可重新尝试默认音源"
                    : error.localizedDescription
                self.handleResolutionFailure(
                    track: track,
                    message: msg,
                    openSourceManager: noSource,
                    allowAutoSkip: !preserveExclusions
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
        resolvedPlatformCode: String?,
        cacheKey: String?,
        seekToMs: Int64
    ) {
        // Clear resolveTask before arming bufferingWatchdogTask (loadAndPlayCurrent's defer runs after return)
        resolveTask = nil
        activateAudioSession()
        wantsPlayback = true
        hasEnteredReadyForActiveMedia = false
        activeStreamSourceId = sourceId
        activeResolvedTrackId = resolvedTrackId
        activeResolvedPlatformCode = resolvedPlatformCode ?? track.source.code
        activeCacheKey = cacheKey
        let effectiveSeekMs = max(seekToMs, 0)
        pendingResumePositionMs = effectiveSeekMs
        isRecoveringError = false
        isSeeking = false
        isRestartingForPlay = false
        activeSeekToken = UUID()

        state.activeQualityBadge = qualityBadge
        state.activeProviderName = providerName
        state.activeStreamSourceId = sourceId
        state.isBuffering = true
        let prepDur = max(track.durationMs, visualsTrackId == track.id ? max(progress.durationMs, state.durationMs) : 0)
        state.durationMs = prepDur
        progress.durationMs = prepDur

        // Refresh visuals & lyrics only when switching to a different track
        if visualsTrackId != track.id {
            loadMetadataAndVisuals(for: track)
        }

        let asset: AVURLAsset
        if isRemote {
            asset = AVURLAsset(url: url, options: [
                "AVURLAssetHTTPHeaderFieldsKey": [
                    "User-Agent": Self.chromeUserAgent
                ]
            ])
        } else {
            asset = AVURLAsset(url: url)
        }

        let item = AVPlayerItem(asset: asset)
        pendingInitialSeekMs = effectiveSeekMs > 0 ? effectiveSeekMs : nil
        if effectiveSeekMs > 0 {
            state.positionMs = effectiveSeekMs
            progress.positionMs = effectiveSeekMs
        }
        // Defer background disk caching until AVPlayer actually begins .playing so it never
        // competes with initial frame buffering or triggers anti-concurrency CDN rejections.
        if isRemote, let quality, let cacheKey {
            pendingBackgroundCache = PendingBackgroundCache(
                remoteUrlString: url.absoluteString,
                cacheKey: cacheKey,
                quality: quality
            )
        } else {
            pendingBackgroundCache = nil
        }

        observeCurrentItem(item)
        player.replaceCurrentItem(with: item)

        // When resuming at a non-zero offset, wait for .readyToPlay to perform a single seek
        // before starting playback so there is no audible 0:00 blip or double-seek stall.
        if effectiveSeekMs == 0 {
            player.play()
            if player.timeControlStatus == .playing {
                hasEnteredReadyForActiveMedia = true
                state.isBuffering = false
                startPendingBackgroundCacheIfNeeded()
            }
        }
        state.isPlaying = true
        scheduleBufferingWatchdogIfNeeded()
        libraryStore.recordPlay(track)
        persistCursor(force: true)
        updateNowPlayingInfo()
    }

    private func startPendingBackgroundCacheIfNeeded() {
        guard let pending = pendingBackgroundCache else { return }
        pendingBackgroundCache = nil
        audioCacheManager.cacheInBackground(
            remoteUrlString: pending.remoteUrlString,
            cacheKey: pending.cacheKey,
            quality: pending.quality
        )
    }

    private func scheduleBufferingWatchdogIfNeeded() {
        bufferingWatchdogTask?.cancel()
        bufferingWatchdogTask = nil
        guard wantsPlayback,
              !isRecoveringError,
              resolveTask == nil,
              let track = state.currentTrack,
              !track.isLocal,
              let item = player.currentItem,
              player.timeControlStatus != .playing else {
            return
        }
        bufferingWatchdogTask = Task { [weak self, weak item] in
            try? await Task.sleep(nanoseconds: Self.bufferingWatchdogTimeoutNs)
            guard !Task.isCancelled, let self, let item else { return }
            self.bufferingWatchdogTask = nil
            guard self.wantsPlayback,
                  !self.isRecoveringError,
                  self.resolveTask == nil,
                  self.player.currentItem == item,
                  self.player.timeControlStatus != .playing else {
                return
            }
            let watchdogError: Error = self.networkAdvisor.isNetworkConnected()
                ? LxRuntimeError.message("音频缓冲超时")
                : NSError(
                    domain: NSURLErrorDomain,
                    code: NSURLErrorNotConnectedToInternet,
                    userInfo: [NSLocalizedDescriptionKey: "网络连接已断开，音频缓冲超时"]
                )
            self.handleRuntimePlaybackError(item: item, error: watchdogError)
        }
    }

    private func handleResolutionFailure(
        track: Track,
        message: String,
        openSourceManager: Bool = false,
        allowAutoSkip: Bool = true
    ) {
        wantsPlayback = false
        detachCurrentPlayerItem()
        isRecoveringError = false
        state.isBuffering = false
        state.isPlaying = false
        updateNowPlayingInfo()

        if openSourceManager {
            events.send(PlayerUiEvent(message: message, actionLabel: "导入音源", openSourceManager: true))
            return
        }

        events.send(PlayerUiEvent(message: message))

        // Auto-skip next if in a multi-track queue and another track exists (never skip past SEQUENTIAL end,
        // never loop singleLoop, never auto-skip while offline or after manual alternate-source exhaustion)
        if allowAutoSkip &&
            networkAdvisor.isNetworkConnected() &&
            state.repeatMode != .singleLoop &&
            queueNavigator.canSkipNext(
                queueSize: state.queue.count,
                currentIndex: state.currentIndex,
                mode: state.repeatMode
            ) &&
            consecutiveAutoSkipCount < PlaybackRecoveryPolicy.maxConsecutiveAutoSkips {
            consecutiveAutoSkipCount += 1
            recoveryTask?.cancel()
            recoveryTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_200_000_000)
                guard !Task.isCancelled, let self else { return }
                self.recoveryTask = nil
                self.next(isAutoAdvance: true)
            }
        }
    }

    private func handleRuntimePlaybackError(item: AVPlayerItem, error: Error?) {
        guard let track = state.currentTrack, !isRecoveringError else { return }
        if track.isLocal {
            handleResolutionFailure(track: track, message: "本地音频《\(track.title)》无法播放")
            return
        }
        // Use wantsPlayback so a .paused KVO task firing ahead of .failed never silently kills auto-recovery
        let isPlaybackActive = wantsPlayback || state.isPlaying || state.isBuffering || player.timeControlStatus != .paused
        guard isPlaybackActive else {
            detachCurrentPlayerItem()
            state.isBuffering = false
            state.isPlaying = false
            updateNowPlayingInfo()
            return
        }

        let wasReady = hasEnteredReadyForActiveMedia
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
            resolvedTrackId: activeResolvedTrackId,
            wasReady: wasReady
        )
        runtimeRetryCount += 1
        isRecoveringError = true
        lastRecoveryStartPositionMs = progress.positionMs
        detachCurrentPlayerItem()

        if plan.evictStreamCache, let key = activeCacheKey {
            audioCacheManager.evict(cacheKey: key)
        }
        if plan.excludeCurrentSource, let sid = activeStreamSourceId {
            let plat = activeResolvedPlatformCode ?? track.source.code
            failedSourcePlatformsForCurrentTrack.insert(UserApiManager.sourcePlatformKey(sourceId: sid, platformCode: plat))
            let rtid = activeResolvedTrackId ?? track.id
            failedSourceTracksForCurrentTrack.insert(UserApiManager.sourceTrackKey(sourceId: sid, trackId: rtid))
        }
        if plan.excludeResolvedTrack, let rtid = activeResolvedTrackId {
            failedTrackIdsForCurrentTrack.insert(rtid)
        }

        pendingResumePositionMs = plan.preservePosition ? max(progress.positionMs, pendingResumePositionMs) : 0
        let preferSource = plan.preferSameSourceFirst ? activeStreamSourceId : nil
        let backoffNs = UInt64(max(plan.backoffMs, 100)) * 1_000_000

        state.isPlaying = false
        state.isBuffering = true
        updateNowPlayingInfo()
        switch plan.kind {
        case .transientNetwork:
            events.send(PlayerUiEvent(message: "网络波动，正在自动恢复播放…"))
        case .expiredOrDeniedUrl:
            events.send(PlayerUiEvent(message: plan.excludeCurrentSource ? "当前音源不可用，正在自动换源播放…" : "音频链接已刷新，正在无缝续播…"))
        case .streamOrSourceBroken:
            events.send(PlayerUiEvent(message: "音频流异常，正在自动切换备用音源续播…"))
        }

        recoveryTask?.cancel()
        recoveryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: backoffNs)
            guard !Task.isCancelled, let self else { return }
            if plan.kind == .transientNetwork && !self.networkAdvisor.isNetworkConnected() {
                for _ in 0..<16 {
                    if Task.isCancelled || self.networkAdvisor.isNetworkConnected() { break }
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
            }
            guard !Task.isCancelled else { return }
            self.recoveryTask = nil
            let targetResume = plan.preservePosition ? self.pendingResumePositionMs : 0
            self.loadAndPlayCurrent(
                resetRecoveryState: false,
                seekToMs: targetResume,
                preferredSourceId: preferSource
            )
        }
    }

    // MARK: - Gapless Preloading

    private func maybePreloadNextTrack() {
        let curDur = max(progress.durationMs, state.durationMs)
        let curPos = progress.positionMs
        let remainingMs = curDur - curPos
        guard let currentTrack = state.currentTrack,
              state.repeatMode != .singleLoop,
              curDur >= Self.minTrackDurationForPreloadMs,
              remainingMs >= 1_000 && remainingMs <= Self.preloadWindowMs,
              preloadAttemptedForTrackId != currentTrack.id,
              let nextIdx = queueNavigator.peekNextIndexForAutoAdvance(
                  queueSize: state.queue.count,
                  currentIndex: state.currentIndex,
                  mode: state.repeatMode
              ),
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

            @MainActor
            func commitIfStillValid(_ item: PreloadedNextItem) {
                guard !Task.isCancelled,
                      self.state.currentTrack?.id == forTrackId,
                      self.state.repeatMode != .singleLoop,
                      self.queueNavigator.peekNextIndexForAutoAdvance(
                          queueSize: self.state.queue.count,
                          currentIndex: self.state.currentIndex,
                          mode: self.state.repeatMode
                      ) == nextIdx,
                      nextIdx < self.state.queue.count,
                      self.state.queue[nextIdx].id == nextTrack.id else {
                    return
                }
                self.preloadedNext = item
            }

            if nextTrack.isLocal, let localUri = nextTrack.localUri {
                let localUrl = Self.resolveLocalFileURL(from: localUri)
                if FileManager.default.fileExists(atPath: localUrl.path) {
                    commitIfStillValid(PreloadedNextItem(
                        forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                        playbackURL: localUrl, isRemote: false, quality: nil,
                        qualityBadge: "LOCAL", providerName: "本地音乐",
                        sourceId: nil, resolvedTrackId: nextTrack.id, resolvedPlatformCode: nextTrack.source.code,
                        cacheKey: nil, createdAt: Date()
                    ))
                    return
                }
            }

            if let offlineUrl = await self.downloadManager.localCachedFileURL(for: nextTrack.id) {
                commitIfStillValid(PreloadedNextItem(
                    forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                    playbackURL: offlineUrl, isRemote: false, quality: nil,
                    qualityBadge: "离线", providerName: "已离线保存",
                    sourceId: nil, resolvedTrackId: nextTrack.id, resolvedPlatformCode: nextTrack.source.code,
                    cacheKey: nil, createdAt: Date()
                ))
                return
            }

            let decision = self.networkAdvisor.decideQuality(
                preferred: settings.preferredQuality,
                cellularDataSaverEnabled: settings.wifiOnlyHighQuality
            )
            let res = await self.userApiManager.resolveStream(
                track: nextTrack,
                preferredQuality: decision.quality,
                allowCrossPlatformFallback: settings.autoFallbackSource
            )
            guard !Task.isCancelled else { return }
            if case .success(let resolved) = res, let url = LxJsRuntime.parseLenientURL(resolved.url) {
                let key = AudioCacheManager.cacheKey(
                    trackId: nextTrack.id,
                    resolvedTrackId: resolved.resolvedTrack.id,
                    sourceId: resolved.sourceId,
                    sourceRevision: resolved.sourceRevision,
                    quality: resolved.quality
                )
                if let cachedUrl = await self.audioCacheManager.cachedFileURLAsync(for: key) {
                    guard !Task.isCancelled else { return }
                    commitIfStillValid(PreloadedNextItem(
                        forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                        playbackURL: cachedUrl, isRemote: false, quality: resolved.quality,
                        qualityBadge: "\(resolved.quality.badge) · 缓存", providerName: resolved.providerName,
                        sourceId: resolved.sourceId, resolvedTrackId: resolved.resolvedTrack.id,
                        resolvedPlatformCode: resolved.resolvedTrack.source.code,
                        cacheKey: key, createdAt: Date()
                    ))
                    return
                }
                commitIfStillValid(PreloadedNextItem(
                    forTrackId: forTrackId, nextIndex: nextIdx, track: nextTrack,
                    playbackURL: url, isRemote: true, quality: resolved.quality,
                    qualityBadge: resolved.quality.badge, providerName: resolved.providerName,
                    sourceId: resolved.sourceId, resolvedTrackId: resolved.resolvedTrack.id,
                    resolvedPlatformCode: resolved.resolvedTrack.source.code,
                    cacheKey: key, createdAt: Date()
                ))
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

        visualsTrackId = track.id
        state.lyrics = ParsedLyrics()
        state.isLoadingLyrics = true
        lyricsLoadedForTrackId = nil
        cachedArtworkImage = nil
        cachedArtwork = nil

        let trackId = track.id

        coverTask = Task { [weak self] in
            guard let self else { return }
            var cover = track.coverUrl
            if (cover ?? "").isEmpty {
                if let resolved = await self.musicAggregator.resolveCoverIfNeeded(for: track),
                   !Task.isCancelled {
                    cover = resolved
                    if let idx = self.state.queue.firstIndex(where: { $0.id == trackId }) {
                        self.state.queue[idx].coverUrl = resolved
                    }
                }
            }
            guard !Task.isCancelled, self.state.currentTrack?.id == trackId else { return }

            if let img = await ImageMemoryCache.shared.loadImage(from: cover, isThumbnail: false),
               !Task.isCancelled,
               self.state.currentTrack?.id == trackId {
                self.paletteTask?.cancel()
                self.paletteTask = Task { [weak self] in
                    let processed: (UIImage, AmbientPalette) = await Task.detached(priority: .utility) {
                        let small = PlayerController.downsampleArtwork(img, maxDimension: 512)
                        let pal = AmbientColorExtractor.extractPalette(from: small)
                        return (small, pal)
                    }.value
                    guard let self, !Task.isCancelled, self.state.currentTrack?.id == trackId else { return }
                    self.cachedArtworkImage = processed.0
                    self.cachedArtwork = MPMediaItemArtwork(boundsSize: processed.0.size) { _ in processed.0 }
                    self.state.ambientPalette = processed.1
                    self.updateNowPlayingInfo()
                }
            } else if !Task.isCancelled && self.state.currentTrack?.id == trackId {
                self.state.ambientPalette = .default
            }
        }

        lyricTask = Task { [weak self] in
            guard let self else { return }
            let fetched = await self.musicAggregator.fetchLyrics(for: track)
            guard !Task.isCancelled, self.state.currentTrack?.id == trackId else { return }
            self.lyricsLoadedForTrackId = fetched.isEmpty ? nil : trackId
            self.state.lyrics = fetched
            self.state.isLoadingLyrics = false
        }
    }

    private nonisolated static func resolveLocalFileURL(from uri: String) -> URL {
        LocalMusicManager.resolveStoredLocalFileURL(uri)
    }

    private nonisolated static func downsampleArtwork(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let targetSize = CGSize(width: floor(size.width * scale), height: floor(size.height * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
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
        let playToken = center.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let isActive = self.state.isPlaying || self.state.isBuffering ||
                    self.isRestartingForPlay || self.resolveTask != nil ||
                    self.recoveryTask != nil || self.isRecoveringError ||
                    self.player.timeControlStatus != .paused
                if !isActive { self.togglePlayPause() }
            }
            return .success
        }
        remoteCommandTargets.append((center.playCommand, playToken))

        center.pauseCommand.isEnabled = true
        let pauseToken = center.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let isActive = self.state.isPlaying || self.state.isBuffering ||
                    self.isRestartingForPlay || self.resolveTask != nil ||
                    self.recoveryTask != nil || self.isRecoveringError ||
                    self.player.timeControlStatus != .paused
                if isActive { self.togglePlayPause() }
            }
            return .success
        }
        remoteCommandTargets.append((center.pauseCommand, pauseToken))

        center.togglePlayPauseCommand.isEnabled = true
        let toggleToken = center.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.togglePlayPause() }
            return .success
        }
        remoteCommandTargets.append((center.togglePlayPauseCommand, toggleToken))

        center.nextTrackCommand.isEnabled = true
        let nextToken = center.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.next(isAutoAdvance: false) }
            return .success
        }
        remoteCommandTargets.append((center.nextTrackCommand, nextToken))

        center.previousTrackCommand.isEnabled = true
        let prevToken = center.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.previous() }
            return .success
        }
        remoteCommandTargets.append((center.previousTrackCommand, prevToken))

        center.changePlaybackPositionCommand.isEnabled = true
        let seekToken = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let posEvent = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(posEvent.positionTime * 1000.0)
            Task { @MainActor in self?.seek(to: ms) }
            return .success
        }
        remoteCommandTargets.append((center.changePlaybackPositionCommand, seekToken))
    }

    private func observePlayerState() {
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in
                guard let self else { return }
                switch self.player.timeControlStatus {
                case .playing:
                    self.hasEnteredReadyForActiveMedia = true
                    self.bufferingWatchdogTask?.cancel()
                    self.bufferingWatchdogTask = nil
                    self.state.isPlaying = true
                    self.state.isBuffering = false
                    self.consecutiveAutoSkipCount = 0
                    self.startPendingBackgroundCacheIfNeeded()
                case .waitingToPlayAtSpecifiedRate:
                    if self.player.currentItem != nil {
                        self.state.isBuffering = true
                        if self.player.rate > 0 {
                            self.state.isPlaying = true
                        }
                        self.scheduleBufferingWatchdogIfNeeded()
                    } else if self.resolveTask == nil && !self.isRecoveringError {
                        self.player.pause()
                        self.state.isBuffering = false
                        self.state.isPlaying = false
                    }
                case .paused:
                    // Guard against stale .paused KVO Tasks during active play start, restart seek, or initial seek
                    let isTransitioningToPlay = self.player.rate > 0 ||
                        self.isRestartingForPlay ||
                        (self.pendingInitialSeekMs != nil && self.state.isPlaying)
                    if !isTransitioningToPlay {
                        self.state.isPlaying = false
                        self.pendingResumePositionMs = self.progress.positionMs
                    }
                    if self.resolveTask == nil && !self.isRecoveringError && !isTransitioningToPlay {
                        self.state.isBuffering = false
                    }
                    if !isTransitioningToPlay && self.resolveTask == nil {
                        self.persistCursor(force: true)
                    }
                @unknown default:
                    break
                }
                self.updateNowPlayingInfo()
            }
        }

        let interval = CMTime(seconds: 0.4, preferredTimescale: 600)
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, self.player.currentItem != nil, self.resolveTask == nil, !self.isRecoveringError else { return }
                if self.player.timeControlStatus == .playing && (!self.state.isPlaying || self.state.isBuffering) {
                    self.state.isPlaying = true
                    self.state.isBuffering = false
                    self.updateNowPlayingInfo()
                }
                var nextPos = self.progress.positionMs
                var nextDur = max(self.progress.durationMs, self.state.durationMs)
                let hadZeroDuration = (nextDur <= 0)

                let posSec = time.seconds
                if self.pendingInitialSeekMs == nil, !self.isSeeking, posSec.isFinite && posSec >= 0 {
                    let posMs = Int64(posSec * 1000.0)
                    nextPos = posMs
                    self.pendingResumePositionMs = posMs
                    if self.runtimeRetryCount > 0 &&
                        posMs >= self.lastRecoveryStartPositionMs + Self.stablePlaybackResetDeltaMs {
                        self.runtimeRetryCount = 0
                        self.failedSourcePlatformsForCurrentTrack.removeAll()
                        self.failedSourceTracksForCurrentTrack.removeAll()
                        self.failedTrackIdsForCurrentTrack.removeAll()
                    }
                }
                if let dur = self.player.currentItem?.duration.seconds, dur.isFinite && dur > 0 {
                    nextDur = Int64(dur * 1000.0)
                }

                // Publish only on isolated `progress` model so RootView doesn't re-render 2.5 times/sec
                if nextPos != self.progress.positionMs {
                    self.progress.positionMs = nextPos
                }
                if nextDur != self.progress.durationMs {
                    self.progress.durationMs = nextDur
                }
                if hadZeroDuration && nextDur > 0 {
                    self.state.durationMs = nextDur
                    self.updateNowPlayingInfo()
                }
                if self.pendingInitialSeekMs == nil && !self.isSeeking {
                    self.persistCursor(force: false)
                }
                self.maybePreloadNextTrack()
            }
        }
    }

    private func observeCurrentItem(_ item: AVPlayerItem) {
        statusObservation?.invalidate()
        if let obs = itemEndObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = itemFailedObserver { NotificationCenter.default.removeObserver(obs) }
        if let obs = itemStalledObserver { NotificationCenter.default.removeObserver(obs) }

        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] observedItem, change in
            let observedStatus = change.newValue ?? observedItem.status
            guard observedStatus != .unknown else { return }
            Task { @MainActor in
                guard let self,
                      observedItem == self.player.currentItem,
                      observedItem.status == observedStatus else { return }
                switch observedStatus {
                case .readyToPlay:
                    self.hasEnteredReadyForActiveMedia = true
                    if observedItem.duration.seconds.isFinite && observedItem.duration.seconds > 0 {
                        let resolvedDur = Int64(observedItem.duration.seconds * 1000.0)
                        self.state.durationMs = resolvedDur
                        self.progress.durationMs = resolvedDur
                    }
                    if let targetMs = self.pendingInitialSeekMs {
                        self.pendingInitialSeekMs = nil
                        let curDur = max(self.progress.durationMs, self.state.durationMs)
                        let clampedTarget = curDur > 0
                            ? min(targetMs, max(curDur - 1_000, 0))
                            : targetMs
                        let targetTime = CMTime(value: clampedTarget, timescale: 1000)
                        let seekTolerance = CMTime(seconds: 0.5, preferredTimescale: 600)
                        let shouldAutoPlay = self.state.isPlaying
                        let token = UUID()
                        self.activeSeekToken = token
                        self.isSeeking = true
                        self.isRestartingForPlay = shouldAutoPlay
                        self.player.seek(to: targetTime, toleranceBefore: seekTolerance, toleranceAfter: seekTolerance) { [weak self, weak observedItem] _ in
                            Task { @MainActor in
                                guard let self, self.activeSeekToken == token else { return }
                                self.isSeeking = false
                                let resumePlay = self.isRestartingForPlay || self.state.isPlaying
                                self.isRestartingForPlay = false
                                guard let observedItem, self.player.currentItem == observedItem else { return }
                                self.progress.positionMs = clampedTarget
                                self.state.positionMs = clampedTarget
                                self.pendingResumePositionMs = clampedTarget
                                if resumePlay {
                                    self.wantsPlayback = true
                                    self.activateAudioSession()
                                    self.player.play()
                                    self.state.isPlaying = true
                                    self.state.isBuffering = (self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
                                    self.scheduleBufferingWatchdogIfNeeded()
                                } else {
                                    self.state.isBuffering = false
                                }
                                self.updateNowPlayingInfo()
                            }
                        }
                    } else {
                        let status = self.player.timeControlStatus
                        self.state.isBuffering = (status == .waitingToPlayAtSpecifiedRate)
                        if status == .playing {
                            self.state.isPlaying = true
                            self.bufferingWatchdogTask?.cancel()
                            self.bufferingWatchdogTask = nil
                            self.startPendingBackgroundCacheIfNeeded()
                        } else if status == .paused && self.player.rate == 0 && !self.isRestartingForPlay {
                            self.state.isPlaying = false
                        }
                        self.updateNowPlayingInfo()
                    }
                case .failed:
                    self.pendingInitialSeekMs = nil
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
        ) { [weak self, weak item] _ in
            MainActor.assumeIsolated {
                guard let self, let item, self.player.currentItem == item else { return }
                self.next(isAutoAdvance: true)
            }
        }

        itemFailedObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self, weak item] note in
            let err = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            MainActor.assumeIsolated {
                guard let self, let item, let curItem = self.player.currentItem, curItem == item else { return }
                self.handleRuntimePlaybackError(item: curItem, error: err)
            }
        }

        itemStalledObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemPlaybackStalled,
            object: item,
            queue: .main
        ) { [weak self, weak item] _ in
            MainActor.assumeIsolated {
                guard let self, let item, self.player.currentItem == item, self.wantsPlayback else { return }
                self.state.isBuffering = true
                self.scheduleBufferingWatchdogIfNeeded()
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
                    self.resumeAfterInterruption = self.wantsPlayback || self.state.isPlaying || self.state.isBuffering ||
                        self.isRestartingForPlay || self.resolveTask != nil || self.player.rate > 0
                    self.wantsPlayback = false
                    self.resolveTask?.cancel()
                    self.resolveTask = nil
                    self.recoveryTask?.cancel()
                    self.recoveryTask = nil
                    self.bufferingWatchdogTask?.cancel()
                    self.bufferingWatchdogTask = nil
                    self.activeLoadToken = UUID()
                    self.activeSeekToken = UUID()
                    self.isSeeking = false
                    self.isRestartingForPlay = false
                    self.isRecoveringError = false
                    self.player.pause()
                    self.state.isPlaying = false
                    self.state.isBuffering = false
                    self.updateNowPlayingInfo()
                } else if type == .ended {
                    let shouldResume = self.resumeAfterInterruption
                    self.resumeAfterInterruption = false
                    let opts = AVAudioSession.InterruptionOptions(rawValue: optsVal)
                    if opts.contains(.shouldResume) && shouldResume && self.state.currentTrack != nil {
                        if self.player.currentItem == nil || self.player.currentItem?.status == .failed {
                            let resumeMs = max(self.progress.positionMs, self.pendingResumePositionMs)
                            self.loadAndPlayCurrent(resetRecoveryState: true, seekToMs: resumeMs)
                        } else {
                            self.wantsPlayback = true
                            self.activateAudioSession()
                            if self.pendingInitialSeekMs != nil && self.player.currentItem?.status == .unknown {
                                self.state.isPlaying = true
                                self.state.isBuffering = true
                            } else {
                                self.player.play()
                                self.state.isPlaying = true
                                self.state.isBuffering = (self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate)
                            }
                            self.scheduleBufferingWatchdogIfNeeded()
                            self.updateNowPlayingInfo()
                        }
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
                self.wantsPlayback = false
                self.resumeAfterInterruption = false
                self.resolveTask?.cancel()
                self.resolveTask = nil
                self.recoveryTask?.cancel()
                self.recoveryTask = nil
                self.bufferingWatchdogTask?.cancel()
                self.bufferingWatchdogTask = nil
                self.activeLoadToken = UUID()
                self.activeSeekToken = UUID()
                self.isSeeking = false
                self.isRestartingForPlay = false
                self.isRecoveringError = false
                self.player.pause()
                self.state.isPlaying = false
                self.state.isBuffering = false
                self.updateNowPlayingInfo()
            }
        }
    }

    private func updateNowPlayingInfo() {
        guard let track = state.currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var elapsedSec = Double(progress.positionMs) / 1000.0
        if player.currentItem != nil, pendingInitialSeekMs == nil, !isSeeking {
            let liveSec = player.currentTime().seconds
            if liveSec.isFinite && liveSec >= 0 {
                elapsedSec = liveSec
            }
        }
        let playbackRate = (state.isPlaying && !state.isBuffering) ? 1.0 : 0.0
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsedSec,
            MPMediaItemPropertyPlaybackDuration: Double(max(progress.durationMs, state.durationMs, track.durationMs)) / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate: playbackRate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0
        ]
        if let cachedArtwork {
            info[MPMediaItemPropertyArtwork] = cachedArtwork
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func stopAndReset() {
        queueNavigator.reset()
        resolveTask?.cancel()
        resolveTask = nil
        recoveryTask?.cancel()
        recoveryTask = nil
        coverTask?.cancel()
        coverTask = nil
        lyricTask?.cancel()
        lyricTask = nil
        paletteTask?.cancel()
        paletteTask = nil
        sleepTimerTask?.cancel()
        sleepTimerTask = nil
        clearPreloadedNext()
        detachCurrentPlayerItem()

        activeLoadToken = UUID()
        activeSeekToken = UUID()
        isSeeking = false
        wantsPlayback = false
        isRecoveringError = false
        resumeAfterInterruption = false
        pendingInitialSeekMs = nil
        pendingResumePositionMs = 0
        lastRecoveryStartPositionMs = 0
        activeStreamSourceId = nil
        activeResolvedTrackId = nil
        activeResolvedPlatformCode = nil
        activeCacheKey = nil
        failedSourcePlatformsForCurrentTrack.removeAll()
        failedSourceTracksForCurrentTrack.removeAll()
        failedTrackIdsForCurrentTrack.removeAll()
        runtimeRetryCount = 0
        consecutiveAutoSkipCount = 0
        visualsTrackId = nil
        lyricsLoadedForTrackId = nil
        cachedArtworkImage = nil
        cachedArtwork = nil

        progress.positionMs = 0
        progress.durationMs = 0
        progress.sleepTimerRemainingMs = nil
        state = PlayerState(repeatMode: state.repeatMode)
        syncNavigationCapabilities()
        playbackSessionStore.clear()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
