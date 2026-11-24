import Combine
import Foundation
import UIKit

private struct HistoryEntry: Codable, Sendable {
    var track: Track
    var playedAt: Int64
    var playCount: Int
}

private struct TrackIdOnly: Decodable, Sendable {
    let id: String
}

enum CreatePlaylistResult: Equatable, Sendable {
    case created(Int64)
    case limitReached(Int)
}

struct AddTracksResult: Equatable, Sendable {
    let addedCount: Int
    let duplicateCount: Int
}

@MainActor
final class LibraryStore: ObservableObject {
    nonisolated static let maxCustomPlaylists = 10
    nonisolated static let maxCachedTrackLists = 3
    nonisolated static let maxCachedTracksTotal = 3_000

    nonisolated static func isCustomPlaylist(sourceUrl: String?) -> Bool {
        (sourceUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var customPlaylistCount: Int {
        userPlaylists.filter { Self.isCustomPlaylist(sourceUrl: $0.sourceUrl) }.count
    }

    var canCreateCustomPlaylist: Bool {
        customPlaylistCount < Self.maxCustomPlaylists
    }

    var cachedTrackListCount: Int {
        playlistTracksCache.count
    }

    var cachedTrackTotalCount: Int {
        playlistTracksCache.values.reduce(0) { $0 + $1.count }
    }

    var cachedTrackIdSetCount: Int {
        playlistTrackIdsCache.count
    }

    @Published private(set) var favorites: [Track] = []
    @Published private(set) var favoriteIds: Set<String> = []
    @Published private(set) var recentHistory: [Track] = []
    @Published private(set) var searchHistory: [String] = []
    @Published private(set) var userPlaylists: [UserPlaylist] = []
    @Published private(set) var downloads: [DownloadRecord] = []
    @Published private(set) var playlistTracksVersion: Int = 0

    private var historyEntries: [HistoryEntry] = []
    private var playlistTracksCache: [Int64: [Track]] = [:]
    private var playlistTracksCacheOrder: [Int64] = []
    private var playlistTrackIdsCache: [Int64: Set<String>] = [:]
    private var savedUserApis: [UserApiSource] = []
    private var memoryWarningCancellable: AnyCancellable?

    private let baseDir: URL
    private let ioQueue = DispatchQueue(label: "com.lxpure.library.io", qos: .utility)

    init(baseDirectory: URL? = nil) {
        if let baseDirectory {
            self.baseDir = baseDirectory
        } else {
            let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.baseDir = appSupport.appendingPathComponent("LXPure", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
        loadAllSync()

        memoryWarningCancellable = NotificationCenter.default
            .publisher(for: UIApplication.didReceiveMemoryWarningNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.evictTrackCaches()
            }
    }

    func evictTrackCaches() {
        playlistTracksCache.removeAll(keepingCapacity: false)
        playlistTracksCacheOrder.removeAll(keepingCapacity: false)
        playlistTrackIdsCache.removeAll(keepingCapacity: false)
    }

    func flushPendingWrites() {
        ioQueue.sync {}
    }

    nonisolated func flushPendingWritesAsync(_ completion: @escaping @Sendable () -> Void) {
        ioQueue.async {
            completion()
        }
    }

    private func fileURL(_ name: String) -> URL {
        baseDir.appendingPathComponent(name)
    }

    private func loadAllSync() {
        favorites = readJson("favorites.json") ?? []
        favoriteIds = Set(favorites.map(\.id))

        historyEntries = readJson("history.json") ?? []
        recentHistory = historyEntries.sorted { $0.playedAt > $1.playedAt }.map(\.track)

        searchHistory = readJson("search_history.json") ?? []
        userPlaylists = readJson("playlists.json") ?? []
        downloads = (readJson("downloads.json") ?? []).sorted { $0.updatedAt > $1.updatedAt }
        savedUserApis = (readJson("user_apis.json") ?? []).sorted { $0.priority < $1.priority }
    }

    private func readJson<T: Decodable>(_ filename: String) -> T? {
        ioQueue.sync {}
        let url = fileURL(filename)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func writeJson<T: Encodable & Sendable>(_ value: T, to filename: String) {
        let url = fileURL(filename)
        ioQueue.async {
            guard let data = try? JSONEncoder().encode(value) else { return }
            try? data.write(to: url, options: .atomic)
        }
    }

    // MARK: - Favorites

    @discardableResult
    func toggleFavorite(_ track: Track) -> Bool {
        if favoriteIds.contains(track.id) {
            favorites.removeAll { $0.id == track.id }
            favoriteIds.remove(track.id)
            writeJson(favorites, to: "favorites.json")
            return false
        } else {
            favorites.insert(track, at: 0)
            favoriteIds.insert(track.id)
            writeJson(favorites, to: "favorites.json")
            return true
        }
    }

    // MARK: - History

    func recordPlay(_ track: Track) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if let idx = historyEntries.firstIndex(where: { $0.track.id == track.id }) {
            let oldCount = historyEntries[idx].playCount
            historyEntries.remove(at: idx)
            historyEntries.insert(HistoryEntry(track: track, playedAt: now, playCount: oldCount + 1), at: 0)
        } else {
            historyEntries.insert(HistoryEntry(track: track, playedAt: now, playCount: 1), at: 0)
        }
        if historyEntries.count > 200 {
            historyEntries = Array(historyEntries.prefix(200))
        }
        recentHistory = historyEntries.map(\.track)
        writeJson(historyEntries, to: "history.json")
    }

    func clearHistory() {
        historyEntries = []
        recentHistory = []
        writeJson(historyEntries, to: "history.json")
    }

    // MARK: - Search History

    func saveSearchQuery(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        searchHistory.removeAll { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
        searchHistory.insert(trimmed, at: 0)
        if searchHistory.count > 20 {
            searchHistory = Array(searchHistory.prefix(20))
        }
        writeJson(searchHistory, to: "search_history.json")
    }

    func deleteSearchQuery(_ query: String) {
        searchHistory.removeAll { $0 == query }
        writeJson(searchHistory, to: "search_history.json")
    }

    func clearSearchHistory() {
        searchHistory = []
        writeJson(searchHistory, to: "search_history.json")
    }

    // MARK: - User Playlists

    @discardableResult
    func createPlaylist(
        name: String,
        description: String = "",
        coverUrl: String? = nil,
        sourceUrl: String? = nil,
        tracks: [Track] = []
    ) -> CreatePlaylistResult {
        if Self.isCustomPlaylist(sourceUrl: sourceUrl) && customPlaylistCount >= Self.maxCustomPlaylists {
            return .limitReached(Self.maxCustomPlaylists)
        }
        var seen = Set<String>()
        let uniqueTracks = tracks.filter { seen.insert($0.id).inserted }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let nextId = max((userPlaylists.map(\.id).max() ?? 0) + 1, now)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let explicitCover = coverUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedCover = (explicitCover?.isEmpty == false)
            ? explicitCover
            : uniqueTracks.first(where: { !($0.coverUrl?.isEmpty ?? true) })?.coverUrl
        let trimmedSource = sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines)
        let playlist = UserPlaylist(
            id: nextId,
            name: trimmedName.isEmpty ? "未命名歌单" : trimmedName,
            coverUrl: resolvedCover,
            description: description,
            sourceUrl: (trimmedSource?.isEmpty == false) ? trimmedSource : nil,
            trackCount: uniqueTracks.count,
            createdAt: now
        )
        userPlaylists.insert(playlist, at: 0)
        cachePlaylistTracks(uniqueTracks, for: nextId)
        playlistTracksVersion &+= 1
        // Write track payload BEFORE index so an abrupt suspension never leaves an empty playlist with stale trackCount
        writeJson(uniqueTracks, to: "playlist_\(nextId).json")
        writeJson(userPlaylists, to: "playlists.json")
        return .created(nextId)
    }

    func cachedTracksIfLoaded(forPlaylistId playlistId: Int64) -> [Track]? {
        guard userPlaylists.contains(where: { $0.id == playlistId }) else { return [] }
        return playlistTracksCache[playlistId]
    }

    func loadTracksAsync(forPlaylistId playlistId: Int64) async -> [Track] {
        guard userPlaylists.contains(where: { $0.id == playlistId }) else { return [] }
        if let cached = playlistTracksCache[playlistId] {
            touchTrackCacheOrder(playlistId)
            return cached
        }
        let url = fileURL("playlist_\(playlistId).json")
        let queue = ioQueue
        let loaded: [Track] = await withCheckedContinuation { cont in
            queue.async {
                let decoded: [Track]
                if let data = try? Data(contentsOf: url),
                   let parsed = try? JSONDecoder().decode([Track].self, from: data) {
                    decoded = parsed
                } else {
                    decoded = []
                }
                cont.resume(returning: decoded)
            }
        }
        guard let plIndex = userPlaylists.firstIndex(where: { $0.id == playlistId }) else {
            return []
        }
        // If a concurrent addTracks/removeTrack updated the cache while reading disk, prefer the newer cache
        if let fresh = playlistTracksCache[playlistId] {
            return fresh
        }
        cachePlaylistTracks(loaded, for: playlistId)
        if userPlaylists[plIndex].trackCount != loaded.count {
            userPlaylists[plIndex].trackCount = loaded.count
            writeJson(userPlaylists, to: "playlists.json")
        }
        return loaded
    }

    func tracks(forPlaylistId playlistId: Int64) -> [Track] {
        guard let plIndex = userPlaylists.firstIndex(where: { $0.id == playlistId }) else {
            return []
        }
        if let cached = playlistTracksCache[playlistId] {
            touchTrackCacheOrder(playlistId)
            return cached
        }
        let loaded: [Track] = readJson("playlist_\(playlistId).json") ?? []
        cachePlaylistTracks(loaded, for: playlistId)
        if userPlaylists[plIndex].trackCount != loaded.count {
            let expectedCount = loaded.count
            Task { @MainActor [weak self] in
                guard let self,
                      let liveIdx = self.userPlaylists.firstIndex(where: { $0.id == playlistId }),
                      self.userPlaylists[liveIdx].trackCount != expectedCount else {
                    return
                }
                self.userPlaylists[liveIdx].trackCount = expectedCount
                self.writeJson(self.userPlaylists, to: "playlists.json")
            }
        }
        return loaded
    }

    func cachedPlaylistIdsContainingIfLoaded(trackId: String) -> Set<Int64> {
        var result = Set<Int64>()
        for pl in userPlaylists {
            if let cachedIds = playlistTrackIdsCache[pl.id] {
                if cachedIds.contains(trackId) { result.insert(pl.id) }
            } else if let cachedTracks = playlistTracksCache[pl.id] {
                if cachedTracks.contains(where: { $0.id == trackId }) { result.insert(pl.id) }
            }
        }
        return result
    }

    func playlistIdsContainingAsync(trackId: String) async -> Set<Int64> {
        let playlistIds = userPlaylists.map(\.id)
        var missing: [(Int64, URL)] = []
        var result = Set<Int64>()
        for plId in playlistIds {
            if let cachedIds = playlistTrackIdsCache[plId] {
                if cachedIds.contains(trackId) { result.insert(plId) }
            } else if let cachedTracks = playlistTracksCache[plId] {
                let ids = Set(cachedTracks.map(\.id))
                playlistTrackIdsCache[plId] = ids
                if ids.contains(trackId) { result.insert(plId) }
            } else {
                missing.append((plId, fileURL("playlist_\(plId).json")))
            }
        }
        if missing.isEmpty { return result }

        let pendingReads = missing
        let queue = ioQueue
        let decodedPairs: [(Int64, Set<String>)] = await withCheckedContinuation { cont in
            queue.async {
                var out: [(Int64, Set<String>)] = []
                out.reserveCapacity(pendingReads.count)
                let decoder = JSONDecoder()
                for (plId, url) in pendingReads {
                    let ids: Set<String>
                    if let data = try? Data(contentsOf: url),
                       let minimal = try? decoder.decode([TrackIdOnly].self, from: data) {
                        ids = Set(minimal.map(\.id))
                    } else {
                        ids = []
                    }
                    out.append((plId, ids))
                }
                cont.resume(returning: out)
            }
        }
        for (plId, ids) in decodedPairs {
            guard userPlaylists.contains(where: { $0.id == plId }) else { continue }
            if playlistTrackIdsCache[plId] == nil {
                playlistTrackIdsCache[plId] = ids
            }
            if let liveIds = playlistTrackIdsCache[plId], liveIds.contains(trackId) {
                result.insert(plId)
            }
        }
        return result
    }

    func playlistIdsContaining(trackId: String) -> Set<Int64> {
        var result = Set<Int64>()
        for pl in userPlaylists {
            if let cachedIds = playlistTrackIdsCache[pl.id] {
                if cachedIds.contains(trackId) { result.insert(pl.id) }
                continue
            }
            if let cachedTracks = playlistTracksCache[pl.id] {
                let ids = Set(cachedTracks.map(\.id))
                playlistTrackIdsCache[pl.id] = ids
                if ids.contains(trackId) { result.insert(pl.id) }
                continue
            }
            let minimal: [TrackIdOnly] = readJson("playlist_\(pl.id).json") ?? []
            let ids = Set(minimal.map(\.id))
            playlistTrackIdsCache[pl.id] = ids
            if ids.contains(trackId) { result.insert(pl.id) }
        }
        return result
    }

    /// Adds unique tracks to a playlist.
    /// When `prepend` is true (default for manually adding songs from the player or track menu),
    /// newly added tracks appear at the very top of the playlist (#1).
    @discardableResult
    func addTracks(
        _ tracksToAdd: [Track],
        toPlaylistId playlistId: Int64,
        prepend: Bool = true
    ) -> AddTracksResult {
        guard let idx = userPlaylists.firstIndex(where: { $0.id == playlistId }) else {
            return AddTracksResult(addedCount: 0, duplicateCount: 0)
        }
        var existing = tracks(forPlaylistId: playlistId)
        var existingIds = Set(existing.map(\.id))
        var addedCount = 0
        var duplicateCount = 0
        var addedTracks: [Track] = []
        for t in tracksToAdd {
            if existingIds.insert(t.id).inserted {
                addedTracks.append(t)
                addedCount += 1
            } else {
                duplicateCount += 1
            }
        }
        if addedCount > 0 {
            if prepend {
                existing.insert(contentsOf: addedTracks, at: 0)
            } else {
                existing.append(contentsOf: addedTracks)
            }
            cachePlaylistTracks(existing, for: playlistId)
            playlistTracksVersion &+= 1
            writeJson(existing, to: "playlist_\(playlistId).json")

            userPlaylists[idx].trackCount = existing.count
            if (userPlaylists[idx].coverUrl ?? "").isEmpty,
               let cover = addedTracks.first(where: { !($0.coverUrl?.isEmpty ?? true) })?.coverUrl {
                userPlaylists[idx].coverUrl = cover
            }
            writeJson(userPlaylists, to: "playlists.json")
        }
        return AddTracksResult(addedCount: addedCount, duplicateCount: duplicateCount)
    }

    func removeTrack(trackId: String, fromPlaylistId playlistId: Int64) {
        guard let idx = userPlaylists.firstIndex(where: { $0.id == playlistId }) else { return }
        var existing = tracks(forPlaylistId: playlistId)
        let beforeCount = existing.count
        existing.removeAll { $0.id == trackId }
        guard existing.count != beforeCount else { return }
        cachePlaylistTracks(existing, for: playlistId)
        playlistTracksVersion &+= 1
        writeJson(existing, to: "playlist_\(playlistId).json")

        userPlaylists[idx].trackCount = existing.count
        writeJson(userPlaylists, to: "playlists.json")
    }

    func deletePlaylist(id playlistId: Int64) {
        userPlaylists.removeAll { $0.id == playlistId }
        playlistTracksCache.removeValue(forKey: playlistId)
        playlistTracksCacheOrder.removeAll { $0 == playlistId }
        playlistTrackIdsCache.removeValue(forKey: playlistId)
        playlistTracksVersion &+= 1
        writeJson(userPlaylists, to: "playlists.json")
        let url = fileURL("playlist_\(playlistId).json")
        ioQueue.async {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func cachePlaylistTracks(_ tracks: [Track], for playlistId: Int64) {
        playlistTracksCache[playlistId] = tracks
        playlistTrackIdsCache[playlistId] = Set(tracks.map(\.id))
        touchTrackCacheOrder(playlistId)
        enforceTrackCacheLimits()
    }

    private func touchTrackCacheOrder(_ playlistId: Int64) {
        if playlistTracksCacheOrder.last == playlistId { return }
        playlistTracksCacheOrder.removeAll { $0 == playlistId }
        playlistTracksCacheOrder.append(playlistId)
    }

    private func enforceTrackCacheLimits() {
        var totalTracks = playlistTracksCache.values.reduce(0) { $0 + $1.count }
        while playlistTracksCacheOrder.count > 1 &&
            (playlistTracksCache.count > Self.maxCachedTrackLists || totalTracks > Self.maxCachedTracksTotal) {
            let oldestId = playlistTracksCacheOrder.removeFirst()
            if let evicted = playlistTracksCache.removeValue(forKey: oldestId) {
                totalTracks -= evicted.count
            }
        }
    }

    // MARK: - Downloads

    func upsertDownload(_ record: DownloadRecord, persist: Bool = true, reorder: Bool = true) {
        if let idx = downloads.firstIndex(where: { $0.trackId == record.trackId }) {
            downloads[idx] = record
        } else {
            downloads.insert(record, at: 0)
        }
        if reorder {
            downloads.sort { $0.updatedAt > $1.updatedAt }
        }
        if persist {
            writeJson(downloads, to: "downloads.json")
        }
    }

    /// Marks persisted `.queued` / `.downloading` rows as `.failed` after an abrupt app kill
    /// and returns any orphaned partial paths for cleanup.
    func reconcileInterruptedDownloads() -> [String] {
        var orphanedPaths: [String] = []
        var changed = false
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for idx in downloads.indices {
            if downloads[idx].status == .queued || downloads[idx].status == .downloading {
                if let path = downloads[idx].filePath, !path.isEmpty {
                    orphanedPaths.append(path)
                }
                downloads[idx].status = .failed
                downloads[idx].progress = 0
                downloads[idx].filePath = nil
                downloads[idx].totalBytes = 0
                downloads[idx].errorMessage = "下载已中断，请重试"
                downloads[idx].updatedAt = now
                changed = true
            }
        }
        if changed {
            writeJson(downloads, to: "downloads.json")
        }
        return orphanedPaths
    }

    func downloadRecord(for trackId: String) -> DownloadRecord? {
        downloads.first { $0.trackId == trackId }
    }

    func deleteDownloadRecord(trackId: String) {
        downloads.removeAll { $0.trackId == trackId }
        writeJson(downloads, to: "downloads.json")
    }

    // MARK: - Custom User API Sources

    func loadSavedUserApis() -> [UserApiSource] {
        savedUserApis.sorted { $0.priority < $1.priority }
    }

    func saveUserApis(_ list: [UserApiSource]) {
        savedUserApis = list.sorted { $0.priority < $1.priority }
        writeJson(savedUserApis, to: "user_apis.json")
    }
}
