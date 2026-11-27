import Foundation

final class QishuiProvider: Sendable {
    private let session: URLSession
    private let ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private static let defaultCoverHost = "https://p3-luna.douyinpic.com/img/"
    private static let defaultTemplatePrefix = "tplv-b829550vbb"
    private static let uriSegmentAllowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    let leaderboards: [LeaderboardItem] = [
        LeaderboardItem(
            id: "soda_rgb",
            bangId: "7036274230471712007",
            name: "热歌榜",
            subtitle: "每周四更新 · 汽水",
            source: .soda,
            accentHex: 0xFF10B981
        ),
        LeaderboardItem(
            id: "soda_xgb",
            bangId: "7060812597884869927",
            name: "新歌榜",
            subtitle: "近期新歌 · 汽水",
            source: .soda,
            accentHex: 0xFF06B6D4
        ),
        LeaderboardItem(
            id: "soda_omb",
            bangId: "7061475546400005410",
            name: "欧美榜",
            subtitle: "热门外文 · 汽水",
            source: .soda,
            accentHex: 0xFF6366F1
        ),
        LeaderboardItem(
            id: "soda_yyr",
            bangId: "7415959718721494311",
            name: "音乐人榜",
            subtitle: "站内热度 · 汽水",
            source: .soda,
            accentHex: 0xFFEC4899
        )
    ]

    init(session: URLSession) {
        self.session = session
    }

    static func buildCoverUrl(from coverObj: [String: Any]?, size: Int = 500) -> String? {
        guard let coverObj else { return nil }
        let templatePrefix = JsonSafe.asNonBlankString(coverObj["template_prefix"]) ?? defaultTemplatePrefix
        let rawUrls: [String] = (JsonSafe.asArray(coverObj["urls"]) ?? []).compactMap {
            JsonSafe.asNonBlankString($0)
        }

        if let rawUri = JsonSafe.asNonBlankString(coverObj["uri"]) {
            var cleanUri = rawUri
            while cleanUri.hasPrefix("/") {
                cleanUri.removeFirst()
            }
            if !cleanUri.isEmpty {
                let encodedUri = encodeUriPath(cleanUri)
                let baseHost = resolveCoverBase(from: rawUrls.first)
                return "\(baseHost)\(encodedUri)~\(templatePrefix)-resize:\(size):\(size).png"
            }
        }

        if let firstUrl = rawUrls.first {
            let httpsUrl = upgradeToHttps(firstUrl)
            let trimmed = httpsUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasSuffix("/img") || trimmed.hasSuffix("/img/") {
                return nil
            }
            if trimmed.contains("~") {
                return encodeNonAsciiInUrl(trimmed)
            }
            if trimmed.range(of: "/img/", options: .caseInsensitive) != nil {
                let cleaned = trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
                return "\(encodeNonAsciiInUrl(cleaned))~\(templatePrefix)-resize:\(size):\(size).png"
            }
            if trimmed.lowercased().hasPrefix("https://") || trimmed.lowercased().hasPrefix("http://") {
                return encodeNonAsciiInUrl(trimmed)
            }
        }

        return nil
    }

    private static func resolveCoverBase(from rawFirstUrl: String?) -> String {
        guard let rawFirstUrl else { return defaultCoverHost }
        let upgraded = upgradeToHttps(rawFirstUrl)
        let lower = upgraded.lowercased()
        guard lower.hasPrefix("https://") else { return defaultCoverHost }
        if lower.hasSuffix("/img/") {
            return upgraded
        }
        if lower.hasSuffix("/img") {
            return upgraded + "/"
        }
        return defaultCoverHost
    }

    private static func upgradeToHttps(_ url: String) -> String {
        if url.hasPrefix("//") {
            return "https:" + url
        }
        if url.lowercased().hasPrefix("http://") {
            return "https://" + url.dropFirst(7)
        }
        return url
    }

    private static func encodeUriPath(_ uri: String) -> String {
        let segments = uri.split(separator: "/", omittingEmptySubsequences: true)
        let encodedSegments = segments.map { segment -> String in
            let raw = String(segment)
            let decoded = raw.removingPercentEncoding ?? raw
            return decoded.addingPercentEncoding(withAllowedCharacters: uriSegmentAllowed) ?? raw
        }
        return encodedSegments.joined(separator: "/")
    }

    private static func encodeNonAsciiInUrl(_ url: String) -> String {
        var result = ""
        result.reserveCapacity(url.utf8.count)
        for byte in url.utf8 {
            if byte >= 0x21 && byte <= 0x7E {
                result.append(Character(UnicodeScalar(byte)))
            } else {
                result.append(String(format: "%%%02X", byte))
            }
        }
        return result
    }

    func getHotPlaylists(page: Int = 1) async -> [OnlinePlaylist] {
        guard page == 1, !Task.isCancelled else { return [] }
        let channels = ["0", "14"]

        let batches: [(Int, [OnlinePlaylist])] = await withTaskGroup(of: (Int, [OnlinePlaylist]).self) { group in
            for (index, channelId) in channels.enumerated() {
                group.addTask {
                    let items = await self.fetchDiscoverMixChannel(subChannelId: channelId)
                    return (index, items)
                }
            }
            var collected: [(Int, [OnlinePlaylist])] = []
            for await item in group {
                collected.append(item)
            }
            return collected.sorted { $0.0 < $1.0 }
        }

        var seenIds = Set<String>()
        var result: [OnlinePlaylist] = []
        for (_, items) in batches {
            for playlist in items where seenIds.insert(playlist.id).inserted {
                result.append(playlist)
            }
        }
        return result
    }

    private func fetchDiscoverMixChannel(subChannelId: String) async -> [OnlinePlaylist] {
        let payloadDict: [String: Any] = [
            "block_type": "discover_playlist_mix",
            "sub_channel_id": subChannelId,
            "count": 20
        ]
        let payload = JsonSafe.stringify(payloadDict)
        let endpoints = [
            "https://api.qishui.com/luna/pc/discover/mix?aid=386088&app_name=luna_pc&device_platform=windows",
            "https://beta-luna.douyin.com/luna/discover/mix"
        ]

        for endpoint in endpoints {
            if Task.isCancelled { return [] }
            guard let body = await postJson(url: endpoint, jsonBody: payload),
                  let root = JsonSafe.parseObject(body) else {
                continue
            }
            let parsed = parseDiscoverMixPlaylists(root)
            if !parsed.isEmpty {
                return parsed
            }
        }
        return []
    }

    func parseDiscoverMixPlaylists(_ root: [String: Any]) -> [OnlinePlaylist] {
        guard let blocks = JsonSafe.asArray(root["inner_block"]) else { return [] }
        var seenIds = Set<String>()
        var result: [OnlinePlaylist] = []

        for blockEl in blocks {
            guard let block = JsonSafe.asObject(blockEl),
                  let resources = JsonSafe.asArray(block["resources"]) else {
                continue
            }
            for resEl in resources {
                guard let resObj = JsonSafe.asObject(resEl),
                      let entity = JsonSafe.asObject(resObj["entity"]),
                      let plObj = JsonSafe.asObject(entity["playlist"]),
                      let playlist = parsePlaylistSummary(plObj),
                      playlist.trackCount > 0,
                      seenIds.insert(playlist.id).inserted else {
                    continue
                }
                result.append(playlist)
            }
        }
        return result
    }

    private func parsePlaylistSummary(_ obj: [String: Any]) -> OnlinePlaylist? {
        guard let id = JsonSafe.asNonBlankString(obj["id"]) else { return nil }
        let rawTitle = JsonSafe.asNonBlankString(obj["title"]) ?? JsonSafe.asNonBlankString(obj["name"]) ?? ""
        let title = FormatUtils.decodeHtml(rawTitle)
        guard !title.isEmpty else { return nil }

        let resourceCnt = JsonSafe.asObject(obj["resource_cnt"])
        let trackCount = JsonSafe.asInt(resourceCnt?["track_cnt"])
            ?? JsonSafe.asInt(obj["count_tracks"])
            ?? JsonSafe.asInt(obj["track_count"])
            ?? 0

        let ownerObj = JsonSafe.asObject(obj["owner"])
        let author = FormatUtils.decodeHtml(
            JsonSafe.asNonBlankString(ownerObj?["public_name"])
                ?? JsonSafe.asNonBlankString(ownerObj?["nickname"])
                ?? ""
        )

        let statsObj = JsonSafe.asObject(obj["stats"])
        let collected = JsonSafe.asInt64(statsObj?["count_collected"])
            ?? JsonSafe.asInt64(statsObj?["count_played"])
            ?? 0
        let playCount = collected > 0 ? FormatUtils.formatPlayCount(collected) : ""
        let coverUrl = Self.buildCoverUrl(from: JsonSafe.asObject(obj["url_cover"])) ?? ""
        let desc = FormatUtils.decodeHtml(JsonSafe.asString(obj["desc"]))

        return OnlinePlaylist(
            id: id,
            name: title,
            coverUrl: coverUrl,
            author: author,
            playCount: playCount,
            description: desc,
            trackCount: trackCount,
            source: .soda
        )
    }

    func getPlaylistDetail(rawIdOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        guard let playlistId = await extractPlaylistId(rawIdOrUrl) else {
            if Task.isCancelled { return .failure(CancellationError()) }
            return .failure(LxRuntimeError.message("无法识别汽水音乐歌单 ID"))
        }

        let maxPages = 20
        var cursor = ""
        var seenCursors = Set<String>()
        var allTracks: [Track] = []
        var seenTrackIds = Set<String>()
        var firstPlaylistObj: [String: Any]?

        for page in 0..<maxPages {
            if Task.isCancelled { return .failure(CancellationError()) }

            let rootResult = await fetchPlaylistDetailPage(
                playlistId: playlistId,
                cursor: cursor,
                isFirstPage: page == 0
            )
            guard case .success(let root) = rootResult else {
                if page == 0, case .failure(let err) = rootResult {
                    return .failure(err)
                }
                break
            }

            if firstPlaylistObj == nil {
                firstPlaylistObj = JsonSafe.asObject(root["playlist"])
            }

            var addedThisPage = 0
            if let resources = JsonSafe.asArray(root["media_resources"]) {
                for resEl in resources {
                    guard let resObj = JsonSafe.asObject(resEl),
                          let entity = JsonSafe.asObject(resObj["entity"]) else {
                        continue
                    }
                    let wrapper = JsonSafe.asObject(entity["track_wrapper"])
                    let trackObj = JsonSafe.asObject(wrapper?["track"]) ?? JsonSafe.asObject(entity["track"])
                    guard let trackObj, let track = parseTrackObj(trackObj) else { continue }
                    if seenTrackIds.insert(track.id).inserted {
                        allTracks.append(track)
                        addedThisPage += 1
                    }
                }
            }

            let hasMore = JsonSafe.asBool(root["has_more"]) ?? false
            guard hasMore,
                  addedThisPage > 0,
                  let nextCursor = JsonSafe.asNonBlankString(root["next_cursor"]),
                  nextCursor != cursor,
                  seenCursors.insert(nextCursor).inserted else {
                break
            }
            cursor = nextCursor
        }

        if allTracks.isEmpty && firstPlaylistObj == nil {
            return .failure(LxRuntimeError.message("汽水音乐歌单不存在或暂无访问权限"))
        }

        let parsedMeta = firstPlaylistObj.flatMap { parsePlaylistSummary($0) }
        let metaCover = parsedMeta?.coverUrl ?? ""
        let meta = OnlinePlaylist(
            id: playlistId,
            name: parsedMeta?.name ?? "汽水音乐歌单",
            coverUrl: !metaCover.isEmpty ? metaCover : (allTracks.first?.coverUrl ?? ""),
            author: parsedMeta?.author ?? "",
            playCount: parsedMeta?.playCount ?? "",
            description: parsedMeta?.description ?? "",
            trackCount: allTracks.count,
            source: .soda
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: allTracks))
    }

    private func fetchPlaylistDetailPage(
        playlistId: String,
        cursor: String,
        isFirstPage: Bool
    ) async -> Result<[String: Any], Error> {
        let encodedId = urlEncodeFormComponent(playlistId)
        let encodedCursor = urlEncodeFormComponent(cursor)
        let pcUrl = "https://api.qishui.com/luna/pc/playlist/detail?playlist_id=\(encodedId)&cursor=\(encodedCursor)&count=100&aid=386088&app_name=luna_pc&device_platform=windows"

        var sawNetworkResponse = false
        var primaryRoot: [String: Any]?
        if let body = await getString(pcUrl) {
            sawNetworkResponse = true
            primaryRoot = JsonSafe.parseObject(body)
        }
        let primaryMedia = JsonSafe.asArray(primaryRoot?["media_resources"]) ?? []
        let hasPrimaryData = primaryRoot != nil && (
            !primaryMedia.isEmpty ||
                (!isFirstPage && JsonSafe.asObject(primaryRoot?["playlist"]) != nil)
        )
        if hasPrimaryData, let primaryRoot {
            return .success(primaryRoot)
        }

        if Task.isCancelled { return .failure(CancellationError()) }

        let fallbackUrl = "https://api.qishui.com/luna/playlist/detail?aid=386088&app_name=luna_pc&device_platform=windows"
        let fallbackPayloadDict: [String: Any] = [
            "playlist_id": playlistId,
            "cursor": cursor,
            "count": 100
        ]
        let fallbackPayload = JsonSafe.stringify(fallbackPayloadDict)
        var postRoot: [String: Any]?
        if let body = await postJson(url: fallbackUrl, jsonBody: fallbackPayload) {
            sawNetworkResponse = true
            postRoot = JsonSafe.parseObject(body)
        }
        if let postRoot {
            let postMedia = JsonSafe.asArray(postRoot["media_resources"]) ?? []
            if !postMedia.isEmpty || JsonSafe.asObject(postRoot["playlist"]) != nil {
                return .success(postRoot)
            }
        }

        if let fallbackRoot = primaryRoot ?? postRoot,
           JsonSafe.asObject(fallbackRoot["playlist"]) != nil || JsonSafe.asArray(fallbackRoot["media_resources"]) != nil {
            return .success(fallbackRoot)
        }

        if Task.isCancelled { return .failure(CancellationError()) }
        if sawNetworkResponse {
            return .failure(LxRuntimeError.message("汽水音乐歌单数据异常"))
        }
        return .failure(LxRuntimeError.message("获取汽水音乐歌单失败，请检查网络连接"))
    }

    func getLeaderboardDetail(board: LeaderboardItem) async -> Result<PlaylistDetailResult, Error> {
        let endpoints = [
            "https://api.qishui.com/luna/pc/charts/\(board.bangId)?aid=386088&app_name=luna_pc&device_platform=windows",
            "https://api5-lf.qishui.com/luna/charts/\(board.bangId)?charge=0"
        ]

        var sawNetworkResponse = false
        var chartObj: [String: Any]?

        for url in endpoints {
            if Task.isCancelled { return .failure(CancellationError()) }
            guard let body = await getString(url) else { continue }
            sawNetworkResponse = true
            if let root = JsonSafe.parseObject(body),
               let chart = JsonSafe.asObject(root["chart"]) {
                chartObj = chart
                break
            }
        }

        if Task.isCancelled { return .failure(CancellationError()) }
        guard let chart = chartObj else {
            let msg = sawNetworkResponse ? "汽水音乐榜单数据为空" : "获取汽水音乐榜单失败，请检查网络连接"
            return .failure(LxRuntimeError.message(msg))
        }

        let trackRanks = JsonSafe.asArray(chart["track_ranks"]) ?? []
        var seenIds = Set<String>()
        var tracks: [Track] = []
        tracks.reserveCapacity(trackRanks.count)

        for rankEl in trackRanks {
            guard let rankObj = JsonSafe.asObject(rankEl) else { continue }
            let trackObj = JsonSafe.asObject(rankObj["track"]) ?? rankObj
            guard let track = parseTrackObj(trackObj) else { continue }
            if seenIds.insert(track.id).inserted {
                tracks.append(track)
            }
        }

        if tracks.isEmpty {
            return .failure(LxRuntimeError.message("汽水音乐榜单暂无曲目"))
        }

        let chartTitle = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(chart["title"]) ?? board.name)
        let styleDesc = FormatUtils.decodeHtml(JsonSafe.asString(JsonSafe.asObject(chart["style"])?["desc"]))
        let coverUrl = Self.buildCoverUrl(from: JsonSafe.asObject(chart["url_cover"]))
            ?? tracks.first?.coverUrl
            ?? ""

        let meta = OnlinePlaylist(
            id: board.bangId,
            name: chartTitle.isEmpty ? board.name : chartTitle,
            coverUrl: coverUrl,
            author: "汽水音乐官方榜",
            playCount: styleDesc.isEmpty ? board.subtitle : styleDesc,
            description: styleDesc,
            trackCount: tracks.count,
            source: .soda
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: tracks))
    }

    func getLyric(songmid: String) async -> ParsedLyrics {
        let trimmed = songmid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !Task.isCancelled else { return ParsedLyrics() }
        let encoded = urlEncodeFormComponent(trimmed)
        let url = "https://beta-luna.douyin.com/luna/h5/seo_track?track_id=\(encoded)&device_platform=web"
        guard let body = await getString(url),
              let root = JsonSafe.parseObject(body) else {
            return ParsedLyrics()
        }

        let lyricObj = JsonSafe.asObject(root["lyric"])
            ?? JsonSafe.asObject(JsonSafe.asObject(root["seo_track"])?["lyric"])
        let content = JsonSafe.asNonBlankString(lyricObj?["content"])
        let trans = JsonSafe.asNonBlankString(lyricObj?["translation"])
            ?? JsonSafe.asNonBlankString(lyricObj?["tlyric"])
        return LrcParser.parse(content, translation: trans)
    }

    func parseTrackObj(_ obj: [String: Any]) -> Track? {
        guard let trackId = JsonSafe.asNonBlankString(obj["id"]) else { return nil }
        let rawName = JsonSafe.asNonBlankString(obj["name"]) ?? JsonSafe.asNonBlankString(obj["title"]) ?? ""
        let title = FormatUtils.decodeHtml(rawName)
        guard !title.isEmpty else { return nil }

        let artistsArray = JsonSafe.asArray(obj["artists"]) ?? []
        let artistNames: [String] = artistsArray.compactMap { el in
            guard let raw = JsonSafe.asNonBlankString(JsonSafe.asObject(el)?["name"]) else { return nil }
            let decoded = FormatUtils.decodeHtml(raw)
            return decoded.isEmpty ? nil : decoded
        }
        let artist = artistNames.isEmpty ? "未知歌手" : artistNames.joined(separator: " / ")

        let albumObj = JsonSafe.asObject(obj["album"])
        let albumName = FormatUtils.decodeHtml(JsonSafe.asString(albumObj?["name"]))
        let albumId = JsonSafe.asNonBlankString(albumObj?["id"])
        let coverUrl = Self.buildCoverUrl(
            from: JsonSafe.asObject(albumObj?["url_cover"]) ?? JsonSafe.asObject(obj["url_cover"])
        )
        let durationMs = max(JsonSafe.asInt64(obj["duration"]) ?? 0, 0)
        let qualities = parseBitRates(JsonSafe.asArray(obj["bit_rates"]))

        return Track(
            id: "soda_\(trackId)",
            title: title,
            artist: artist,
            album: albumName,
            source: .soda,
            songmid: trackId,
            durationMs: durationMs,
            coverUrl: coverUrl,
            albumId: albumId,
            qualities: qualities,
            artistNames: artistNames
        )
    }

    func parseBitRates(_ rawArray: [Any]?) -> [QualityInfo] {
        guard let rawArray, !rawArray.isEmpty else {
            return []
        }
        var mapped: [String: QualityInfo] = [:]
        for el in rawArray {
            guard let item = JsonSafe.asObject(el),
                  let rawQuality = JsonSafe.asNonBlankString(item["quality"])?.lowercased() else {
                continue
            }
            let lxType: String?
            switch rawQuality {
            case "medium", "higher": lxType = "128k"
            case "highest": lxType = "320k"
            case "lossless": lxType = "flac"
            case "hi_res", "hires": lxType = "flac24bit"
            default: lxType = nil
            }
            guard let lxType else { continue }
            let sizeBytes = JsonSafe.asInt64(item["size"]) ?? 0
            let sizeLabel = sizeBytes > 0 ? FormatUtils.formatSize(sizeBytes) : ""
            if let existing = mapped[lxType] {
                if existing.size.isEmpty && !sizeLabel.isEmpty {
                    mapped[lxType] = QualityInfo(type: lxType, size: sizeLabel)
                }
            } else {
                mapped[lxType] = QualityInfo(type: lxType, size: sizeLabel)
            }
        }
        let canonicalOrder = ["128k", "320k", "flac", "flac24bit"]
        return canonicalOrder.compactMap { mapped[$0] }
    }

    private func extractPlaylistId(_ raw: String) async -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if trimmed.allSatisfy(\.isNumber) { return trimmed }

        let pattern = "(?:playlist_id=|[?&]id=|/playlist/)(\\d+)"
        if let direct = firstRegexCapture(pattern: pattern, in: trimmed) {
            return direct
        }

        guard let urlStr = extractFirstHttpUrl(from: trimmed),
              let url = URL(string: urlStr) else {
            return nil
        }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (_, resp) = try? await session.data(for: req),
              let finalUrl = resp.url?.absoluteString else {
            return nil
        }
        return firstRegexCapture(pattern: pattern, in: finalUrl)
    }

    private func getString(_ urlStr: String) async -> String? {
        guard let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.douyin.com/", forHTTPHeaderField: "Referer")
        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func postJson(url urlStr: String, jsonBody: String) async -> String? {
        guard let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.douyin.com/", forHTTPHeaderField: "Referer")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(jsonBody.utf8)
        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}
