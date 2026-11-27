import Foundation
import SwiftUI

enum MusicSource: String, Codable, CaseIterable, Identifiable, Hashable, Sendable {
    case wy
    case tx
    case kw
    case kg
    case mg
    case soda
    case local

    var id: String { rawValue }
    var code: String { rawValue }

    var displayName: String {
        switch self {
        case .wy: return "网易云音乐"
        case .tx: return "QQ 音乐"
        case .kw: return "酷我音乐"
        case .kg: return "酷狗音乐"
        case .mg: return "咪咕音乐"
        case .soda: return "汽水音乐"
        case .local: return "本地音乐"
        }
    }

    var shortLabel: String {
        switch self {
        case .wy: return "网易"
        case .tx: return "QQ"
        case .kw: return "酷我"
        case .kg: return "酷狗"
        case .mg: return "咪咕"
        case .soda: return "汽水"
        case .local: return "本地"
        }
    }

    static func fromCode(_ code: String?) -> MusicSource {
        guard let normalized = code?.lowercased() else { return .wy }
        return MusicSource(rawValue: normalized) ?? .wy
    }
}

enum AudioQuality: String, Codable, CaseIterable, Identifiable, Hashable, Sendable {
    case q128k = "128k"
    case q320k = "320k"
    case flac = "flac"
    case flac24 = "flac24bit"

    var id: String { rawValue }
    var lxType: String { rawValue }

    var label: String {
        switch self {
        case .q128k: return "标准 128k"
        case .q320k: return "高品质 320k"
        case .flac: return "无损 FLAC"
        case .flac24: return "Hi-Res 无损"
        }
    }

    var badge: String {
        switch self {
        case .q128k: return "128K"
        case .q320k: return "HQ"
        case .flac: return "SQ"
        case .flac24: return "HR"
        }
    }

    var priority: Int {
        switch self {
        case .q128k: return 1
        case .q320k: return 2
        case .flac: return 3
        case .flac24: return 4
        }
    }

    static func fromLxType(_ type: String?) -> AudioQuality {
        guard let normalized = type?.lowercased() else { return .q320k }
        return AudioQuality(rawValue: normalized) ?? .q320k
    }

    static func fallbackChain(preferred: AudioQuality) -> [AudioQuality] {
        allCases.filter { $0.priority <= preferred.priority }.sorted { $0.priority > $1.priority }
    }
}

struct QualityInfo: Codable, Hashable, Sendable {
    let type: String
    let size: String
    let hash: String?

    init(type: String, size: String = "", hash: String? = nil) {
        self.type = type
        self.size = size
        self.hash = hash
    }
}

struct Track: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let artist: String
    var album: String
    let source: MusicSource
    let songmid: String
    let durationMs: Int64
    var coverUrl: String?
    let albumId: String?
    let albumMid: String?
    let hash: String?
    let strMediaMid: String?
    let songId: String?
    let qualities: [QualityInfo]
    var localUri: String?
    var cachedFilePath: String?
    var alternates: [Track]
    let artistNames: [String]

    enum CodingKeys: String, CodingKey {
        case id, title, artist, album, source, songmid, durationMs
        case coverUrl, albumId, albumMid, hash, strMediaMid, songId
        case qualities, localUri, cachedFilePath, artistNames
    }

    init(
        id: String,
        title: String,
        artist: String,
        album: String = "",
        source: MusicSource = .wy,
        songmid: String = "",
        durationMs: Int64 = 0,
        coverUrl: String? = nil,
        albumId: String? = nil,
        albumMid: String? = nil,
        hash: String? = nil,
        strMediaMid: String? = nil,
        songId: String? = nil,
        qualities: [QualityInfo] = [],
        localUri: String? = nil,
        cachedFilePath: String? = nil,
        alternates: [Track] = [],
        artistNames: [String] = []
    ) {
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.source = source
        self.songmid = songmid
        self.durationMs = durationMs
        self.coverUrl = coverUrl
        self.albumId = albumId
        self.albumMid = albumMid
        self.hash = hash
        self.strMediaMid = strMediaMid
        self.songId = songId
        self.qualities = qualities
        self.localUri = localUri
        self.cachedFilePath = cachedFilePath
        self.alternates = alternates
        self.artistNames = artistNames
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decode(String.self, forKey: .title)
        artist = try c.decode(String.self, forKey: .artist)
        album = try c.decodeIfPresent(String.self, forKey: .album) ?? ""
        source = try c.decodeIfPresent(MusicSource.self, forKey: .source) ?? .wy
        songmid = try c.decodeIfPresent(String.self, forKey: .songmid) ?? ""
        durationMs = try c.decodeIfPresent(Int64.self, forKey: .durationMs) ?? 0
        coverUrl = try c.decodeIfPresent(String.self, forKey: .coverUrl)
        albumId = try c.decodeIfPresent(String.self, forKey: .albumId)
        albumMid = try c.decodeIfPresent(String.self, forKey: .albumMid)
        hash = try c.decodeIfPresent(String.self, forKey: .hash)
        strMediaMid = try c.decodeIfPresent(String.self, forKey: .strMediaMid)
        songId = try c.decodeIfPresent(String.self, forKey: .songId)
        qualities = try c.decodeIfPresent([QualityInfo].self, forKey: .qualities) ?? []
        localUri = try c.decodeIfPresent(String.self, forKey: .localUri)
        cachedFilePath = try c.decodeIfPresent(String.self, forKey: .cachedFilePath)
        alternates = []
        artistNames = try c.decodeIfPresent([String].self, forKey: .artistNames) ?? []
    }

    static func == (lhs: Track, rhs: Track) -> Bool {
        lhs.id == rhs.id &&
            lhs.title == rhs.title &&
            lhs.artist == rhs.artist &&
            lhs.artistNames == rhs.artistNames &&
            lhs.album == rhs.album &&
            lhs.source == rhs.source &&
            lhs.songmid == rhs.songmid &&
            lhs.durationMs == rhs.durationMs &&
            lhs.coverUrl == rhs.coverUrl &&
            lhs.localUri == rhs.localUri &&
            lhs.cachedFilePath == rhs.cachedFilePath
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
        hasher.combine(source)
        hasher.combine(songmid)
    }

    var isLocal: Bool {
        source == .local || !(localUri?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }

    var formattedDuration: String {
        guard durationMs > 0 else { return "" }
        let totalSeconds = durationMs / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    var bestQualityBadge: String? {
        if isLocal { return "LOCAL" }
        if let cached = cachedFilePath, !cached.isEmpty { return "离线" }
        if qualities.contains(where: { $0.type == "flac24bit" }) { return "HR" }
        if qualities.contains(where: { $0.type == "flac" }) { return "SQ" }
        if qualities.contains(where: { $0.type == "320k" }) { return "HQ" }
        return nil
    }

    /// Produces the exact LX Music `musicInfo` JSON object expected by community `.js` custom source scripts.
    func toLxMusicInfoDictionary() -> [String: Any] {
        var dict: [String: Any] = [
            "id": id,
            "name": title,
            "singer": artist,
            "albumName": album,
            "source": source.code,
            "img": coverUrl ?? ""
        ]

        if !formattedDuration.isEmpty {
            dict["interval"] = formattedDuration
        } else {
            dict["interval"] = NSNull()
        }

        if source == .wy, let numericMid = Int64(songmid) {
            dict["songmid"] = numericMid
        } else {
            dict["songmid"] = songmid
        }

        if let albumId, !albumId.isEmpty { dict["albumId"] = albumId }
        if let albumMid, !albumMid.isEmpty { dict["albumMid"] = albumMid }
        if let hash, !hash.isEmpty { dict["hash"] = hash }
        if let strMediaMid, !strMediaMid.isEmpty { dict["strMediaMid"] = strMediaMid }
        if let songId, !songId.isEmpty {
            if let num = Int64(songId) {
                dict["songId"] = num
            } else {
                dict["songId"] = songId
            }
        }

        let effectiveQualities = qualities.isEmpty
            ? [QualityInfo(type: "128k"), QualityInfo(type: "320k"), QualityInfo(type: "flac")]
            : qualities

        let typesArray: [[String: Any]] = effectiveQualities.map { q in
            var obj: [String: Any] = ["type": q.type, "size": q.size]
            if let h = q.hash, !h.isEmpty { obj["hash"] = h }
            return obj
        }

        var typesDict: [String: Any] = [:]
        for q in effectiveQualities {
            var obj: [String: Any] = ["size": q.size]
            if let h = q.hash, !h.isEmpty { obj["hash"] = h }
            typesDict[q.type] = obj
        }

        dict["types"] = typesArray
        dict["_types"] = typesDict

        var meta: [String: Any] = [
            "songId": songmid,
            "albumName": album,
            "picUrl": coverUrl ?? "",
            "qualitys": typesArray,
            "_qualitys": typesDict
        ]
        if let hash, !hash.isEmpty { meta["hash"] = hash }
        if let strMediaMid, !strMediaMid.isEmpty { meta["strMediaMid"] = strMediaMid }
        if let albumMid, !albumMid.isEmpty { meta["albumMid"] = albumMid }
        dict["meta"] = meta

        return dict
    }
}

struct OnlinePlaylist: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let coverUrl: String
    let author: String
    let playCount: String
    let description: String
    let trackCount: Int
    let source: MusicSource

    init(
        id: String,
        name: String,
        coverUrl: String = "",
        author: String = "",
        playCount: String = "",
        description: String = "",
        trackCount: Int = 0,
        source: MusicSource = .wy
    ) {
        self.id = id
        self.name = name
        self.coverUrl = coverUrl
        self.author = author
        self.playCount = playCount
        self.description = description
        self.trackCount = trackCount
        self.source = source
    }
}

struct LeaderboardItem: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let bangId: String
    let name: String
    let subtitle: String
    let source: MusicSource
    let accentHex: UInt32
}

struct UserPlaylist: Codable, Identifiable, Hashable, Sendable {
    let id: Int64
    var name: String
    var coverUrl: String?
    var description: String
    var sourceUrl: String?
    var trackCount: Int
    let createdAt: Int64
}

struct LyricLine: Identifiable, Hashable, Sendable {
    let timeMs: Int64
    let text: String
    var translation: String?
    var lineIndex: Int = 0

    var id: String { "\(lineIndex)_\(timeMs)_\(text.hashValue)" }
}

struct ParsedLyrics: Hashable, Sendable {
    let lines: [LyricLine]
    let hasTranslation: Bool

    init(lines: [LyricLine] = [], hasTranslation: Bool = false) {
        self.lines = lines
        self.hasTranslation = hasTranslation
    }

    var isEmpty: Bool { lines.isEmpty }

    func activeIndex(at positionMs: Int64) -> Int {
        guard !lines.isEmpty else { return -1 }
        let targetMs = positionMs > Int64.max - 120 ? Int64.max : positionMs + 120
        var low = 0
        var high = lines.count - 1
        var result = -1
        while low <= high {
            let mid = (low + high) >> 1
            if lines[mid].timeMs <= targetMs {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        while result > 0 && lines[result - 1].timeMs == lines[result].timeMs {
            result -= 1
        }
        return result
    }
}

enum RepeatMode: String, Codable, CaseIterable, Sendable {
    case listLoop
    case sequential
    case shuffle
    case singleLoop

    var label: String {
        switch self {
        case .listLoop: return "列表循环"
        case .sequential: return "顺序播放"
        case .shuffle: return "随机播放"
        case .singleLoop: return "单曲循环"
        }
    }

    var sfSymbol: String {
        switch self {
        case .listLoop: return "repeat"
        case .sequential: return "arrow.right.to.line"
        case .shuffle: return "shuffle"
        case .singleLoop: return "repeat.1"
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = (try? container.decode(String.self)) ?? ""
        if let exact = RepeatMode(rawValue: raw) {
            self = exact
            return
        }
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "sequential":
            self = .sequential
        case "shuffle":
            self = .shuffle
        case "singleloop", "single_loop":
            self = .singleLoop
        case "listloop", "list_loop":
            self = .listLoop
        default:
            self = .listLoop
        }
    }

    func next() -> RepeatMode {
        switch self {
        case .listLoop: return .sequential
        case .sequential: return .shuffle
        case .shuffle: return .singleLoop
        case .singleLoop: return .listLoop
        }
    }
}

enum ThemeMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case system
    case dark
    case light

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "跟随系统"
        case .dark: return "深曜暗黑"
        case .light: return "纯净瓷白"
        }
    }

    var preferredColorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .dark: return .dark
        case .light: return .light
        }
    }
}

enum UserApiRuntimeStatus: String, Codable, Sendable {
    case idle
    case initializing
    case ready
    case error
}

struct UserApiSource: Codable, Identifiable, Hashable, Sendable {
    let id: String
    var name: String
    var description: String
    var version: String
    var author: String
    var homepage: String
    var script: String
    var sourceUrl: String?
    var enabled: Bool
    var priority: Int
    var status: UserApiRuntimeStatus = .idle
    var supportedPlatforms: [String: [String]] = [:]
    var latencyMs: Int64?
    var errorMessage: String?

    enum CodingKeys: String, CodingKey {
        case id, name, description, version, author, homepage, script, sourceUrl, enabled, priority
    }

    init(
        id: String,
        name: String,
        description: String,
        version: String,
        author: String,
        homepage: String,
        script: String,
        sourceUrl: String?,
        enabled: Bool,
        priority: Int,
        status: UserApiRuntimeStatus = .idle,
        supportedPlatforms: [String: [String]] = [:],
        latencyMs: Int64? = nil,
        errorMessage: String? = nil
    ) {
        self.id = id
        self.name = name
        self.description = description
        self.version = version
        self.author = author
        self.homepage = homepage
        self.script = script
        self.sourceUrl = sourceUrl
        self.enabled = enabled
        self.priority = priority
        self.status = status
        self.supportedPlatforms = supportedPlatforms
        self.latencyMs = latencyMs
        self.errorMessage = errorMessage
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        version = try c.decodeIfPresent(String.self, forKey: .version) ?? "1.0.0"
        author = try c.decodeIfPresent(String.self, forKey: .author) ?? "未知作者"
        homepage = try c.decodeIfPresent(String.self, forKey: .homepage) ?? ""
        script = try c.decode(String.self, forKey: .script)
        sourceUrl = try c.decodeIfPresent(String.self, forKey: .sourceUrl)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        priority = try c.decodeIfPresent(Int.self, forKey: .priority) ?? 0
        status = enabled ? .initializing : .idle
        supportedPlatforms = [:]
        latencyMs = nil
        errorMessage = nil
    }
}

enum DownloadStatus: String, Codable, Sendable {
    case queued
    case downloading
    case completed
    case failed
}

struct DownloadRecord: Codable, Identifiable, Hashable, Sendable {
    let trackId: String
    var track: Track
    var quality: AudioQuality
    var status: DownloadStatus
    var progress: Double
    var filePath: String?
    var totalBytes: Int64
    var errorMessage: String?
    var updatedAt: Int64

    var id: String { trackId }
}

struct AppSettings: Codable, Equatable, Sendable {
    var preferredQuality: AudioQuality = .flac
    var autoFallbackSource: Bool = true
    var themeMode: ThemeMode = .dark
    var fluidAmbientBackground: Bool = true
    var showTranslationLyrics: Bool = true
    var wifiOnlyHighQuality: Bool = true

    enum CodingKeys: String, CodingKey {
        case preferredQuality, autoFallbackSource, themeMode
        case fluidAmbientBackground, showTranslationLyrics, wifiOnlyHighQuality
    }

    init(
        preferredQuality: AudioQuality = .flac,
        autoFallbackSource: Bool = true,
        themeMode: ThemeMode = .dark,
        fluidAmbientBackground: Bool = true,
        showTranslationLyrics: Bool = true,
        wifiOnlyHighQuality: Bool = true
    ) {
        self.preferredQuality = preferredQuality
        self.autoFallbackSource = autoFallbackSource
        self.themeMode = themeMode
        self.fluidAmbientBackground = fluidAmbientBackground
        self.showTranslationLyrics = showTranslationLyrics
        self.wifiOnlyHighQuality = wifiOnlyHighQuality
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let rawQuality = try? c.decodeIfPresent(String.self, forKey: .preferredQuality) {
            self.preferredQuality = AudioQuality.fromLxType(rawQuality)
        } else {
            self.preferredQuality = .flac
        }
        self.autoFallbackSource = (try? c.decodeIfPresent(Bool.self, forKey: .autoFallbackSource)) ?? true
        if let rawTheme = try? c.decodeIfPresent(String.self, forKey: .themeMode),
           let parsedTheme = ThemeMode(rawValue: rawTheme) {
            self.themeMode = parsedTheme
        } else {
            self.themeMode = .dark
        }
        self.fluidAmbientBackground = (try? c.decodeIfPresent(Bool.self, forKey: .fluidAmbientBackground)) ?? true
        self.showTranslationLyrics = (try? c.decodeIfPresent(Bool.self, forKey: .showTranslationLyrics)) ?? true
        self.wifiOnlyHighQuality = (try? c.decodeIfPresent(Bool.self, forKey: .wifiOnlyHighQuality)) ?? true
    }
}

enum QueueSourceType: String, Codable, Equatable, Hashable, Sendable {
    case unknown
    case favorites
    case local
    case downloads
    case history
    case userPlaylist
    case onlinePlaylist
    case leaderboard
    case search
}

struct PlaybackQueueSource: Codable, Equatable, Hashable, Sendable {
    var type: QueueSourceType = .unknown
    var title: String = ""
    var userPlaylistId: Int64? = nil
    var onlinePlaylist: OnlinePlaylist? = nil
    var leaderboard: LeaderboardItem? = nil

    init(
        type: QueueSourceType = .unknown,
        title: String = "",
        userPlaylistId: Int64? = nil,
        onlinePlaylist: OnlinePlaylist? = nil,
        leaderboard: LeaderboardItem? = nil
    ) {
        self.type = type
        self.title = title
        self.userPlaylistId = userPlaylistId
        self.onlinePlaylist = onlinePlaylist
        self.leaderboard = leaderboard
    }
}

struct PlaybackQueueSnapshot: Codable, Sendable {
    var version: Int = 1
    var queue: [Track] = []
    var queueSource: PlaybackQueueSource? = nil
    var savedAt: Int64 = 0
}

struct PlaybackCursorSnapshot: Codable, Sendable {
    var version: Int = 1
    var currentIndex: Int = -1
    var currentTrackId: String? = nil
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var repeatMode: RepeatMode = .listLoop
    var activeQualityBadge: String? = nil
    var activeProviderName: String? = nil
    var updatedAt: Int64 = 0

    enum CodingKeys: String, CodingKey {
        case version
        case currentIndex
        case currentTrackId
        case positionMs
        case durationMs
        case repeatMode
        case activeQualityBadge
        case activeProviderName
        case updatedAt
    }

    init(
        version: Int = 1,
        currentIndex: Int = -1,
        currentTrackId: String? = nil,
        positionMs: Int64 = 0,
        durationMs: Int64 = 0,
        repeatMode: RepeatMode = .listLoop,
        activeQualityBadge: String? = nil,
        activeProviderName: String? = nil,
        updatedAt: Int64 = 0
    ) {
        self.version = version
        self.currentIndex = currentIndex
        self.currentTrackId = currentTrackId
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.repeatMode = repeatMode
        self.activeQualityBadge = activeQualityBadge
        self.activeProviderName = activeProviderName
        self.updatedAt = updatedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decodeIfPresent(Int.self, forKey: .version)) ?? 1
        currentIndex = (try? c.decodeIfPresent(Int.self, forKey: .currentIndex)) ?? -1
        currentTrackId = try? c.decodeIfPresent(String.self, forKey: .currentTrackId)
        positionMs = (try? c.decodeIfPresent(Int64.self, forKey: .positionMs)) ?? 0
        durationMs = (try? c.decodeIfPresent(Int64.self, forKey: .durationMs)) ?? 0
        repeatMode = (try? c.decodeIfPresent(RepeatMode.self, forKey: .repeatMode)) ?? .listLoop
        activeQualityBadge = try? c.decodeIfPresent(String.self, forKey: .activeQualityBadge)
        activeProviderName = try? c.decodeIfPresent(String.self, forKey: .activeProviderName)
        updatedAt = (try? c.decodeIfPresent(Int64.self, forKey: .updatedAt)) ?? 0
    }
}

struct PlaylistDetailResult: Sendable {
    let playlist: OnlinePlaylist
    let tracks: [Track]
}

struct AmbientPalette: Equatable, Sendable {
    var primary: Color
    var secondary: Color
    var accent: Color

    static let `default` = AmbientPalette(
        primary: Color(hex: 0x1E3A5F),
        secondary: Color(hex: 0x312E81),
        accent: Color(hex: 0x38BDF8)
    )
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }

    init(argb: UInt32) {
        let a = Double((argb >> 24) & 0xFF) / 255.0
        let r = Double((argb >> 16) & 0xFF) / 255.0
        let g = Double((argb >> 8) & 0xFF) / 255.0
        let b = Double(argb & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}
