import Foundation

struct RestoredPlaybackSession: Equatable, Sendable {
    let queue: [Track]
    let currentIndex: Int
    let positionMs: Int64
    let durationMs: Int64
    let repeatMode: RepeatMode
    let queueSource: PlaybackQueueSource?
    let activeQualityBadge: String?
    let activeProviderName: String?
}

/// Atomic, split-file persistence for the active playback queue and cursor position.
///
/// Stored independently from `user_apis.json` so app updates and playback saves never
/// touch user-imported custom sources, playlists, or favorites.
final class PlaybackSessionStore: @unchecked Sendable {
    static let maxPersistedQueueSize = 5_000

    private static let queueFileName = "playback_queue_v1.json"
    private static let cursorFileName = "playback_cursor_v1.json"

    private let baseDir: URL
    private let queueIOQueue = DispatchQueue(label: "com.lxpure.playbacksession.queue", qos: .utility)
    private let cursorIOQueue = DispatchQueue(label: "com.lxpure.playbacksession.cursor", qos: .userInitiated)
    private let genLock = NSLock()
    private var queueWriteGen: UInt64 = 0
    private var cursorWriteGen: UInt64 = 0

    init(baseDirectory: URL? = nil) {
        if let baseDirectory {
            self.baseDir = baseDirectory
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.baseDir = appSupport.appendingPathComponent("LXPure", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
    }

    private var queueURL: URL { baseDir.appendingPathComponent(Self.queueFileName) }
    private var cursorURL: URL { baseDir.appendingPathComponent(Self.cursorFileName) }

    /// Trims oversized queues around the active track while preserving relative ordering.
    static func sliceQueueForPersistence(_ queue: [Track], currentIndex: Int) -> ([Track], Int) {
        guard !queue.isEmpty else { return ([], -1) }
        let safeIdx = min(max(currentIndex, 0), queue.count - 1)
        guard queue.count > maxPersistedQueueSize else { return (queue, safeIdx) }

        let half = maxPersistedQueueSize / 2
        var start = max(safeIdx - half, 0)
        var end = start + maxPersistedQueueSize
        if end > queue.count {
            end = queue.count
            start = max(end - maxPersistedQueueSize, 0)
        }
        return (Array(queue[start..<end]), safeIdx - start)
    }

    /// Reconciles saved queue and cursor snapshots safely against index drift or near-end timestamps.
    static func resolveRestoredSession(
        queueSnapshot: PlaybackQueueSnapshot?,
        cursorSnapshot: PlaybackCursorSnapshot?
    ) -> RestoredPlaybackSession? {
        guard let queue = queueSnapshot?.queue, !queue.isEmpty else { return nil }

        let resolvedIndex: Int
        if let cursor = cursorSnapshot {
            if cursor.currentIndex >= 0 && cursor.currentIndex < queue.count &&
                (cursor.currentTrackId == nil || cursor.currentTrackId?.isEmpty == true || queue[cursor.currentIndex].id == cursor.currentTrackId) {
                resolvedIndex = cursor.currentIndex
            } else if let targetId = cursor.currentTrackId, !targetId.isEmpty,
                      let found = queue.firstIndex(where: { $0.id == targetId }) {
                resolvedIndex = found
            } else {
                resolvedIndex = min(max(cursor.currentIndex, 0), queue.count - 1)
            }
        } else {
            resolvedIndex = 0
        }

        let currentTrack = queue[resolvedIndex]
        let trackMatched: Bool = {
            guard let targetId = cursorSnapshot?.currentTrackId, !targetId.isEmpty else {
                return true
            }
            return currentTrack.id == targetId
        }()

        let durationMs = trackMatched
            ? max(cursorSnapshot?.durationMs ?? 0, currentTrack.durationMs, 0)
            : max(currentTrack.durationMs, 0)
        var positionMs = trackMatched ? max(cursorSnapshot?.positionMs ?? 0, 0) : 0
        if durationMs > 5_000 && positionMs >= durationMs - 1_500 {
            positionMs = max(durationMs - 2_000, 0)
        } else if durationMs > 0 && positionMs > durationMs {
            positionMs = durationMs
        }

        return RestoredPlaybackSession(
            queue: queue,
            currentIndex: resolvedIndex,
            positionMs: positionMs,
            durationMs: durationMs,
            repeatMode: cursorSnapshot?.repeatMode ?? .listLoop,
            queueSource: queueSnapshot?.queueSource,
            activeQualityBadge: trackMatched
                ? (cursorSnapshot?.activeQualityBadge ?? currentTrack.bestQualityBadge)
                : currentTrack.bestQualityBadge,
            activeProviderName: trackMatched ? cursorSnapshot?.activeProviderName : nil
        )
    }

    func saveQueueAndCursor(
        queue: [Track],
        queueSource: PlaybackQueueSource?,
        currentIndex: Int,
        positionMs: Int64,
        durationMs: Int64,
        repeatMode: RepeatMode,
        activeQualityBadge: String?,
        activeProviderName: String?,
        synchronous: Bool = false
    ) {
        if queue.isEmpty {
            clear(synchronous: synchronous)
            return
        }

        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let qUrl = queueURL
        let cUrl = cursorURL
        let (qGen, cGen) = nextBothGenerations()

        // Compute sliced index cheaply (no array copy) so cursor write stays independent of heavy queue encoding
        let safeIdx = min(max(currentIndex, 0), queue.count - 1)
        let slicedIndex: Int
        if queue.count <= Self.maxPersistedQueueSize {
            slicedIndex = safeIdx
        } else {
            let half = Self.maxPersistedQueueSize / 2
            var start = max(safeIdx - half, 0)
            let end = min(start + Self.maxPersistedQueueSize, queue.count)
            start = max(end - Self.maxPersistedQueueSize, 0)
            slicedIndex = safeIdx - start
        }
        let activeTrackId = queue[safeIdx].id

        let queueWork = {
            if !self.isLatestQueueGen(qGen) { return }
            let (slicedQueue, _) = Self.sliceQueueForPersistence(queue, currentIndex: currentIndex)
            let queueSnap = PlaybackQueueSnapshot(
                version: 1,
                queue: slicedQueue,
                queueSource: queueSource,
                savedAt: now
            )
            guard let queueData = try? JSONEncoder().encode(queueSnap) else { return }
            if !self.isLatestQueueGen(qGen) { return }
            try? FileManager.default.createDirectory(at: self.baseDir, withIntermediateDirectories: true)
            try? queueData.write(to: qUrl, options: .atomic)
        }

        let cursorWork = {
            if !self.isLatestCursorGen(cGen) { return }
            let cursorSnap = PlaybackCursorSnapshot(
                version: 1,
                currentIndex: slicedIndex,
                currentTrackId: activeTrackId,
                positionMs: max(positionMs, 0),
                durationMs: max(durationMs, 0),
                repeatMode: repeatMode,
                activeQualityBadge: activeQualityBadge,
                activeProviderName: activeProviderName,
                updatedAt: now
            )
            guard let cursorData = try? JSONEncoder().encode(cursorSnap) else { return }
            if !self.isLatestCursorGen(cGen) { return }
            try? FileManager.default.createDirectory(at: self.baseDir, withIntermediateDirectories: true)
            try? cursorData.write(to: cUrl, options: .atomic)
        }

        if synchronous {
            queueIOQueue.sync(execute: queueWork)
            cursorIOQueue.sync(execute: cursorWork)
        } else {
            queueIOQueue.async(execute: queueWork)
            cursorIOQueue.async(execute: cursorWork)
        }
    }

    func saveCursorOnly(
        currentIndex: Int,
        currentTrackId: String?,
        positionMs: Int64,
        durationMs: Int64,
        repeatMode: RepeatMode,
        activeQualityBadge: String?,
        activeProviderName: String?,
        synchronous: Bool = false
    ) {
        guard currentIndex >= 0, let trackId = currentTrackId, !trackId.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let cUrl = cursorURL
        let cGen = nextCursorGeneration()

        let work = {
            if !self.isLatestCursorGen(cGen) { return }
            let cursorSnap = PlaybackCursorSnapshot(
                version: 1,
                currentIndex: currentIndex,
                currentTrackId: trackId,
                positionMs: max(positionMs, 0),
                durationMs: max(durationMs, 0),
                repeatMode: repeatMode,
                activeQualityBadge: activeQualityBadge,
                activeProviderName: activeProviderName,
                updatedAt: now
            )
            guard let cursorData = try? JSONEncoder().encode(cursorSnap) else { return }
            if !self.isLatestCursorGen(cGen) { return }
            try? FileManager.default.createDirectory(at: self.baseDir, withIntermediateDirectories: true)
            try? cursorData.write(to: cUrl, options: .atomic)
        }
        if synchronous {
            cursorIOQueue.sync(execute: work)
        } else {
            cursorIOQueue.async(execute: work)
        }
    }

    func loadSession() -> RestoredPlaybackSession? {
        let queueSnap: PlaybackQueueSnapshot? = queueIOQueue.sync {
            readJson(at: queueURL)
        }
        let cursorSnap: PlaybackCursorSnapshot? = cursorIOQueue.sync {
            readJson(at: cursorURL)
        }
        return Self.resolveRestoredSession(queueSnapshot: queueSnap, cursorSnapshot: cursorSnap)
    }

    func loadSessionAsync() async -> RestoredPlaybackSession? {
        await withCheckedContinuation { continuation in
            queueIOQueue.async {
                let queueSnap: PlaybackQueueSnapshot? = self.readJson(at: self.queueURL)
                self.cursorIOQueue.async {
                    let cursorSnap: PlaybackCursorSnapshot? = self.readJson(at: self.cursorURL)
                    continuation.resume(returning: Self.resolveRestoredSession(queueSnapshot: queueSnap, cursorSnapshot: cursorSnap))
                }
            }
        }
    }

    func clear(synchronous: Bool = false) {
        let qUrl = queueURL
        let cUrl = cursorURL
        let (qGen, cGen) = nextBothGenerations()
        let queueWork = {
            if !self.isLatestQueueGen(qGen) { return }
            try? FileManager.default.removeItem(at: qUrl)
        }
        let cursorWork = {
            if !self.isLatestCursorGen(cGen) { return }
            try? FileManager.default.removeItem(at: cUrl)
        }
        if synchronous {
            queueIOQueue.sync(execute: queueWork)
            cursorIOQueue.sync(execute: cursorWork)
        } else {
            queueIOQueue.async(execute: queueWork)
            cursorIOQueue.async(execute: cursorWork)
        }
    }

    func flush() {
        queueIOQueue.sync {}
        cursorIOQueue.sync {}
    }

    private func nextBothGenerations() -> (UInt64, UInt64) {
        genLock.lock()
        defer { genLock.unlock() }
        queueWriteGen &+= 1
        cursorWriteGen &+= 1
        return (queueWriteGen, cursorWriteGen)
    }

    private func nextCursorGeneration() -> UInt64 {
        genLock.lock()
        defer { genLock.unlock() }
        cursorWriteGen &+= 1
        return cursorWriteGen
    }

    private func isLatestQueueGen(_ gen: UInt64) -> Bool {
        genLock.lock()
        defer { genLock.unlock() }
        return queueWriteGen == gen
    }

    private func isLatestCursorGen(_ gen: UInt64) -> Bool {
        genLock.lock()
        defer { genLock.unlock() }
        return cursorWriteGen == gen
    }

    private func readJson<T: Decodable>(at url: URL) -> T? {
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }
}
