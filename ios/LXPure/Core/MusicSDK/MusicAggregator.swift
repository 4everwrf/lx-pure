import Foundation

final class MusicAggregator: Sendable {
    private static let variantMarkers: [String] = [
        "伴奏", "纯音乐", "instrumental", "dj", "remix", "live", "现场", "cover", "翻唱", "ktv", "伴唱"
    ]
    private static let artistSeparators = CharacterSet(charactersIn: "/、,，&")
    private static let stripPunctuation = CharacterSet(charactersIn: "'.,，&\"、()（）`~-<>|/[]!！·")

    let netEase: NetEaseProvider
    let qq: QqMusicProvider
    let kuwo: KuwoProvider
    let kugou: KugouProvider
    let qishui: QishuiProvider

    init(session: URLSession) {
        self.netEase = NetEaseProvider(session: session)
        self.qq = QqMusicProvider(session: session)
        self.kuwo = KuwoProvider(session: session)
        self.kugou = KugouProvider(session: session)
        self.qishui = QishuiProvider(session: session)
    }

    static func platformPriority(_ source: MusicSource) -> Int {
        switch source {
        case .local: return 5
        case .wy: return 4
        case .tx: return 3
        case .kw: return 2
        case .kg: return 1
        case .mg: return 0
        case .soda: return -1
        }
    }

    static func selectPrimaryAndAlternates(_ group: [Track]) -> Track {
        var seenPlatforms = Set<MusicSource>()
        let bestPerPlatform = group.filter { seenPlatforms.insert($0.source).inserted }
        let sorted = bestPerPlatform.sorted { lhs, rhs in
            let lp = platformPriority(lhs.source)
            let rp = platformPriority(rhs.source)
            if lp != rp { return lp > rp }
            let lHasCover = !(lhs.coverUrl?.isEmpty ?? true)
            let rHasCover = !(rhs.coverUrl?.isEmpty ?? true)
            if lHasCover != rHasCover { return lHasCover }
            return lhs.qualities.count > rhs.qualities.count
        }
        var primary = sorted[0]
        let alternates = Array(sorted.dropFirst())
        primary.alternates = alternates
        if primary.artistNames.isEmpty,
           let resolvedNames = alternates.first(where: { !$0.artistNames.isEmpty })?.artistNames {
            primary = Track(
                id: primary.id,
                title: primary.title,
                artist: primary.artist,
                album: primary.album,
                source: primary.source,
                songmid: primary.songmid,
                durationMs: primary.durationMs,
                coverUrl: primary.coverUrl,
                albumId: primary.albumId,
                albumMid: primary.albumMid,
                hash: primary.hash,
                strMediaMid: primary.strMediaMid,
                songId: primary.songId,
                qualities: primary.qualities,
                localUri: primary.localUri,
                cachedFilePath: primary.cachedFilePath,
                alternates: alternates,
                artistNames: resolvedNames
            )
        }
        return primary
    }

    static func normalizeText(_ str: String) -> String {
        let lowered = str.lowercased()
        return lowered.unicodeScalars.filter { scalar in
            !CharacterSet.whitespacesAndNewlines.contains(scalar) && !stripPunctuation.contains(scalar)
        }.map(String.init).joined()
    }

    static func artistTokens(_ artist: String) -> Set<String> {
        Set(
            artist.components(separatedBy: artistSeparators)
                .map { normalizeText($0) }
                .filter { !$0.isEmpty && $0 != "未知歌手" && $0 != "unknown" }
        )
    }

    static func hasVariantMismatch(_ titleA: String, _ titleB: String) -> Bool {
        let a = titleA.lowercased()
        let b = titleB.lowercased()
        return variantMarkers.contains { marker in
            a.contains(marker) != b.contains(marker)
        }
    }

    static func isTrackMatch(_ source: Track, _ candidate: Track, maxDurationDiffMs: Int64 = 7_000) -> Bool {
        let fTitle = normalizeText(source.title)
        let cTitle = normalizeText(candidate.title)
        guard !fTitle.isEmpty, !cTitle.isEmpty else { return false }
        if hasVariantMismatch(source.title, candidate.title) { return false }

        let titleMatch: Bool
        if fTitle.count <= 1 || cTitle.count <= 1 {
            titleMatch = (fTitle == cTitle)
        } else {
            titleMatch = (cTitle == fTitle) || cTitle.contains(fTitle) || fTitle.contains(cTitle)
        }
        guard titleMatch else { return false }

        let fTokens = artistTokens(source.artist)
        let cTokens = artistTokens(candidate.artist)
        if !fTokens.isEmpty && !cTokens.isEmpty {
            let hasOverlap = fTokens.contains { ft in
                cTokens.contains { ct in ct == ft || ct.contains(ft) || ft.contains(ct) }
            }
            if !hasOverlap { return false }
        }

        if source.durationMs > 0 && candidate.durationMs > 0 {
            if abs(source.durationMs - candidate.durationMs) > maxDurationDiffMs {
                return false
            }
        }
        return true
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

        var groups: [[Track]] = []
        var keyToGroupIndices: [String: [Int]] = [:]

        for track in interleaved {
            let key = normalizeKey(title: track.title, artist: track.artist)
            let bucketIndices = keyToGroupIndices[key] ?? []
            var matchedGroupIdx: Int?
            for gIdx in bucketIndices {
                let group = groups[gIdx]
                guard let head = group.first else { continue }
                let samePlatformExists = group.contains { $0.source == track.source }
                let variantClash = Self.hasVariantMismatch(head.title, track.title)
                let durationOk = head.durationMs <= 0 || track.durationMs <= 0 || abs(head.durationMs - track.durationMs) <= 12_000
                if !samePlatformExists && !variantClash && durationOk {
                    matchedGroupIdx = gIdx
                    break
                }
            }
            if let gIdx = matchedGroupIdx {
                groups[gIdx].append(track)
            } else {
                let newIdx = groups.count
                groups.append([track])
                keyToGroupIndices[key, default: []].append(newIdx)
            }
        }

        return groups.map { Self.selectPrimaryAndAlternates($0) }
    }

    func findAlternativeTracks(for track: Track, allowedPlatformCodes: Set<String>) async -> [Track] {
        let targetPlatforms: [MusicSource] = [.wy, .tx, .kw, .kg].filter {
            $0 != track.source && allowedPlatformCodes.contains($0.code)
        }
        guard !targetPlatforms.isEmpty else { return [] }

        let fTitle = Self.normalizeText(track.title)
        guard !fTitle.isEmpty else { return [] }

        let primaryArtist = ArtistParser.extractArtists(from: track).first ?? ""
        let query = "\(track.title) \(primaryArtist)".trimmingCharacters(in: .whitespacesAndNewlines)

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
            Self.isTrackMatch(track, candidate)
        }.sorted { lhs, rhs in
            let lExact = Self.normalizeText(lhs.title) == fTitle ? 0 : 1
            let rExact = Self.normalizeText(rhs.title) == fTitle ? 0 : 1
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
        case .soda: return await qishui.getHotPlaylists(page: page)
        default: return await netEase.getHotPlaylists(page: page)
        }
    }

    func getLeaderboards(source: MusicSource) -> [LeaderboardItem] {
        switch source {
        case .wy: return netEase.leaderboards
        case .tx: return qq.leaderboards
        case .kw: return kuwo.leaderboards
        case .soda: return qishui.leaderboards
        default: return netEase.leaderboards
        }
    }

    func getPlaylistDetail(source: MusicSource, idOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        switch source {
        case .wy: return await netEase.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        case .tx: return await qq.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        case .kw: return await kuwo.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        case .soda: return await qishui.getPlaylistDetail(rawIdOrUrl: idOrUrl)
        default: return .failure(LxRuntimeError.message("暂不支持该平台歌单详情"))
        }
    }

    func getLeaderboardDetail(board: LeaderboardItem) async -> Result<PlaylistDetailResult, Error> {
        switch board.source {
        case .wy: return await netEase.getPlaylistDetail(rawIdOrUrl: board.bangId)
        case .tx: return await qq.getLeaderboardDetail(board: board)
        case .kw: return await kuwo.getLeaderboardDetail(board: board)
        case .soda: return await qishui.getLeaderboardDetail(board: board)
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
        case .soda: direct = await qishui.getLyric(songmid: track.songmid)
        default: direct = ParsedLyrics()
        }
        if !direct.isEmpty { return direct }

        let primaryArtist = ArtistParser.extractArtists(from: track).first ?? ""
        let query = "\(track.title) \(primaryArtist)".trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return ParsedLyrics() }

        let candidates = await netEase.search(query: query, page: 1, limit: 5)
        guard let matched = candidates.first(where: { cand in
            !(track.source == .wy && cand.songmid == track.songmid) &&
                Self.isTrackMatch(track, cand, maxDurationDiffMs: 10_000)
        }) else {
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

    private func normalizeKey(title: String, artist: String) -> String {
        let sortedArtists = Self.artistTokens(artist).sorted().joined(separator: "_")
        return "\(Self.normalizeText(title))_\(sortedArtists)"
    }
}
