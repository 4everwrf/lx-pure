import Combine
import Foundation

private struct HistoryEntry: Codable {
    var track: Track
    var playedAt: Int64
    var playCount: Int
}

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var favorites: [Track] = []
    @Published private(set) var favoriteIds: Set<String> = []
    @Published private(set) var recentHistory: [Track] = []
    @Published private(set) var searchHistory: [String] = []
    @Published private(set) var userPlaylists: [UserPlaylist] = []
    @Published private(set) var downloads: [DownloadRecord] = []

    private var historyEntries: [HistoryEntry] = []
    private var playlistTracksCache: [Int64: [Track]] = [:]
    private var savedUserApis: [UserApiSource] = []

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
        let url = fileURL(filename)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func writeJson<T: Encodable>(_ value: T, to filename: String) {
        let url = fileURL(filename)
        guard let data = try? JSONEncoder().encode(value) else { return }
        ioQueue.async {
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
    ) -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let nextId = max((userPlaylists.map(\.id).max() ?? 0) + 1, now)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedCover = coverUrl ?? tracks.first(where: { !($0.coverUrl?.isEmpty ?? true) })?.coverUrl
        let playlist = UserPlaylist(
            id: nextId,
            name: trimmedName.isEmpty ? "未命名歌单" : trimmedName,
            coverUrl: resolvedCover,
            description: description,
            sourceUrl: sourceUrl,
            trackCount: tracks.count,
            createdAt: now
        )
        userPlaylists.insert(playlist, at: 0)
        playlistTracksCache[nextId] = tracks
        writeJson(userPlaylists, to: "playlists.json")
        writeJson(tracks, to: "playlist_\(nextId).json")
        return nextId
    }

    func tracks(forPlaylistId playlistId: Int64) -> [Track] {
        if let cached = playlistTracksCache[playlistId] {
            return cached
        }
        let loaded: [Track] = readJson("playlist_\(playlistId).json") ?? []
        playlistTracksCache[playlistId] = loaded
        return loaded
    }

    func addTracks(_ tracksToAdd: [Track], toPlaylistId playlistId: Int64) {
        var existing = tracks(forPlaylistId: playlistId)
        var existingIds = Set(existing.map(\.id))
        for t in tracksToAdd where existingIds.insert(t.id).inserted {
            existing.append(t)
        }
        playlistTracksCache[playlistId] = existing
        writeJson(existing, to: "playlist_\(playlistId).json")

        if let idx = userPlaylists.firstIndex(where: { $0.id == playlistId }) {
            userPlaylists[idx].trackCount = existing.count
            if (userPlaylists[idx].coverUrl ?? "").isEmpty,
               let cover = tracksToAdd.first(where: { !($0.coverUrl?.isEmpty ?? true) })?.coverUrl {
                userPlaylists[idx].coverUrl = cover
            }
            writeJson(userPlaylists, to: "playlists.json")
        }
    }

    func removeTrack(trackId: String, fromPlaylistId playlistId: Int64) {
        var existing = tracks(forPlaylistId: playlistId)
        existing.removeAll { $0.id == trackId }
        playlistTracksCache[playlistId] = existing
        writeJson(existing, to: "playlist_\(playlistId).json")

        if let idx = userPlaylists.firstIndex(where: { $0.id == playlistId }) {
            userPlaylists[idx].trackCount = existing.count
            writeJson(userPlaylists, to: "playlists.json")
        }
    }

    func deletePlaylist(id playlistId: Int64) {
        userPlaylists.removeAll { $0.id == playlistId }
        playlistTracksCache.removeValue(forKey: playlistId)
        writeJson(userPlaylists, to: "playlists.json")
        let url = fileURL("playlist_\(playlistId).json")
        ioQueue.async {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // MARK: - Downloads

    func upsertDownload(_ record: DownloadRecord) {
        if let idx = downloads.firstIndex(where: { $0.trackId == record.trackId }) {
            downloads[idx] = record
        } else {
            downloads.insert(record, at: 0)
        }
        downloads.sort { $0.updatedAt > $1.updatedAt }
        writeJson(downloads, to: "downloads.json")
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
