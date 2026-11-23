import Foundation

final class MusicAggregator: Sendable {
    let netEase: NetEaseProvider
    let qq: QqMusicProvider
    let kuwo: KuwoProvider
    let kugou: KugouProvider

    init(session: URLSession) {
        self.netEase = NetEaseProvider(session: session)
        self.qq = QqMusicProvider(session: session)
        self.kuwo = KuwoProvider(session: session)
        self.kugou = KugouProvider(session: session)
    }

    static func platformPriority(_ source: MusicSource) -> Int {
        switch source {
        case .local: return 5
        case .wy: return 4
        case .tx: return 3
        case .kw: return 2
        case .kg: return 1
        case .mg: return 0
        }
    }

    static func selectPrimaryAndAlternates(_ group: [Track]) -> Track {
        let sorted = group.sorted { lhs, rhs in
            let lp = platformPriority(lhs.source)
            let rp = platformPriority(rhs.source)
            if lp != rp { return lp > rp }
            let lHasCover = !(lhs.coverUrl?.isEmpty ?? true)
            let rHasCover = !(rhs.coverUrl?.isEmpty ?? true)
            if lHasCover != rHasCover { return lHasCover }
            return lhs.qualities.count > rhs.qualities.count
        }
        var primary = sorted[0]
        primary.alternates = Array(sorted.dropFirst())
        return primary
    }

    func searchSinglePlatform(source: MusicSource, query: String, page: Int = 1, limit: Int = 25) async -> [Track] {
        switch source {
        case .wy: return await netEase.search(query: query, page: page, limit: limit)
        case .tx: return await qq.search(query: query, page: page, limit: limit)
        case .kw: return await kuwo.search(query: query, page: page, limit: limit)
        case .kg: return await kugou.search(query: query, page: page, limit: limit)
        default: return []
        }
    }

    func searchAggregated(query: String, limitPerPlatform: Int = 15) async -> [Track] {
        let platforms: [MusicSource] = [.wy, .tx, .kw, .kg]
        let batches: [(Int, [Track])] = await withTaskGroup(of: (Int, [Track]).self) { group in
            for (idx, platform) in platforms.enumerated() {
                group.addTask {
                    let tracks = await self.searchSinglePlatform(source: platform, query: query, page: 1, limit: limitPerPlatform)
                    return (idx, tracks)
                }
            }
            var res: [(Int, [Track])] = []
            for await item in group { res.append(item) }
            return res.sorted { $0.0 < $1.0 }
        }

        let orderedBatches = batches.map(\.1)
        let maxLen = orderedBatches.map(\.count).max() ?? 0
        var interleaved: [Track] = []
        for i in 0..<maxLen {
            for batch in orderedBatches where i < batch.count {
                interleaved.append(batch[i])
            }
        }

        var keyOrder: [String] = []
        var grouped: [String: [Track]] = [:]
        for track in interleaved {
            let key = normalizeKey(title: track.title, artist: track.artist)
            if grouped[key] == nil {
                keyOrder.append(key)
                grouped[key] = [track]
            } else {
                grouped[key]?.append(track)
            }
        }

        return keyOrder.compactMap { key in
            guard let group = grouped[key], !group.isEmpty else { return nil }
            return Self.selectPrimaryAndAlternates(group)
        }
    }

    func findAlternativeTracks(for track: Track, allowedPlatformCodes: Set<String>) async -> [Track] {
        let targetPlatforms: [MusicSource] = [.wy, .tx, .kw, .kg].filter {
            $0 != track.source && allowedPlatformCodes.contains($0.code)
        }
        guard !targetPlatforms.isEmpty else { return [] }

        let firstArtist = track.artist.components(separatedBy: "/").first ?? ""
        let query = "\(track.title) \(firstArtist)".trimmingCharacters(in: .whitespacesAndNewlines)
        let fTitle = normalizeText(track.title)
        let fArtist = normalizeText(track.artist)

        let candidates: [Track] = await withTaskGroup(of: [Track].self) { group in
            for platform in targetPlatforms {
                group.addTask {
                    await self.searchSinglePlatform(source: platform, query: query, page: 1, limit: 10)
                }
            }
            var all: [Track] = []
            for await batch in group { all.append(contentsOf: batch) }
            return all
        }

        return candidates.filter { candidate in
            let cTitle = normalizeText(candidate.title)
            let cArtist = normalizeText(candidate.artist)
            let titleMatch = cTitle == fTitle || cTitle.contains(fTitle) || fTitle.contains(cTitle)
            let artistMatch = fArtist.isEmpty || cArtist.contains(fArtist) || fArtist.contains(cArtist)
            let durationMatch = track.durationMs <= 0 || candidate.durationMs <= 0 ||
                abs(track.durationMs - candidate.durationMs) <= 7_000
            return titleMatch && artistMatch && durationMatch
        }.sorted { lhs, rhs in
            let lExact = normalizeText(lhs.title) == fTitle ? 0 : 1
            let rExact = normalizeText(rhs.title) == fTitle ? 0 : 1
            if lExact != rExact { return lExact < rExact }
            let lp = Self.platformPriority(lhs.source)
            let rp = Self.platformPriority(rhs.source)
            if lp != rp { return lp > rp }
            let lDiff = (track.durationMs > 0 && lhs.durationMs > 0) ? abs(track.durationMs - lhs.durationMs) : 3000
            let rDiff = (track.durationMs > 0 && rhs.durationMs > 0) ? abs(track.durationMs - rhs.durationMs) : 3000
            return lDiff < rDiff
        }
    }

    func getHotPlaylists(source: MusicSource, page: Int = 1) async -> [OnlinePlaylist] {
        switch source {
        case .wy: return await netEase.getHotPlaylists(page: page)
        case .tx: return await qq.getHotPlaylists(page: page)
        case .kw: return await kuwo.getHotPlaylists(page: page)
        default: return await netEase.getHotPlaylists(page: page)
        }
    }

    func getLeaderboards(source: MusicSource) -> [LeaderboardItem] {
        switch source {
        case .wy: return netEase.leaderboards
        case .tx: return qq.leaderboards
        case .kw: return kuwo.leaderboards
        default: return netEase.leaderboards
        }
    }

    func getPlaylistDetail(source: MusicSource, idOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        switch source {
        case .wy: return await netEase.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        case .tx: return await qq.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        case .kw: return await kuwo.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        default: return .failure(LxRuntimeError.message("暂不支持该平台歌单详情"))
        }
    }

    func getLeaderboardDetail(board: LeaderboardItem) async -> Result<PlaylistDetailResult, Error> {
        switch board.source {
        case .wy: return await netEase.getPlaylistDetail(rawIdOrUrl: board.bangId)
        case .tx: return await qq.getLeaderboardDetail(board: board)
        case .kw: return await kuwo.getLeaderboardDetail(board: board)
        default: return .failure(LxRuntimeError.message("暂不支持该平台榜单"))
        }
    }

    func importExternalPlaylist(rawInput: String, preferredSource: MusicSource? = nil) async -> Result<PlaylistDetailResult, Error> {
        let text = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return .failure(LxRuntimeError.message("请粘贴歌单链接或歌单 ID"))
        }

        let lower = text.lowercased()
        let detected: MusicSource
        if lower.contains("163.com") || lower.contains("163cn.tv") {
            detected = .wy
        } else if lower.contains("qq.com") {
            detected = .tx
        } else if lower.contains("kuwo.cn") {
            detected = .kw
        } else if let preferredSource {
            detected = preferredSource
        } else {
            detected = .wy
        }
        return await getPlaylistDetail(source: detected, idOrUrl: text)
    }

    func fetchLyrics(for track: Track) async -> ParsedLyrics {
        let direct: ParsedLyrics
        switch track.source {
        case .wy: direct = await netEase.getLyric(songmid: track.songmid)
        case .tx: direct = await qq.getLyric(songmid: track.songmid)
        case .kw: direct = await kuwo.getLyric(songmid: track.songmid)
        case .kg: direct = await kugou.getLyric(track: track)
        default: direct = ParsedLyrics()
        }
        if !direct.isEmpty { return direct }

        let query = "\(track.title) \(track.artist)".trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty,
              let matched = await netEase.search(query: query, page: 1, limit: 3).first else {
            return ParsedLyrics()
        }
        return await netEase.getLyric(songmid: matched.songmid)
    }

    func resolveCoverIfNeeded(for track: Track) async -> String? {
        if let cover = track.coverUrl, !cover.isEmpty { return cover }
        if track.source == .kw {
            return await kuwo.resolveCover(songmid: track.songmid)
        }
        return nil
    }

    private func normalizeText(_ str: String) -> String {
        let lowered = str.lowercased()
        let invalid = CharacterSet(charactersIn: " \t\n\r'.,，&\"、()（）`~-<>|/[]!！·")
        return lowered.unicodeScalars.filter { !invalid.contains($0) }.map(String.init).joined()
    }

    private func normalizeKey(title: String, artist: String) -> String {
        let separators = CharacterSet(charactersIn: "/、,，&")
        let firstArtist = artist.components(separatedBy: separators).first ?? ""
        return "\(normalizeText(title))_\(normalizeText(firstArtist))"
    }
}
