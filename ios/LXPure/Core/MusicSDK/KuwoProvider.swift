import Foundation

final class KuwoProvider: Sendable {
    private let session: URLSession
    private static let minfoRegex = try! NSRegularExpression(
        pattern: "level:(\\w+),bitrate:(\\d+),format:(\\w+),size:([\\w.]+)",
        options: []
    )

    let leaderboards: [LeaderboardItem] = [
        LeaderboardItem(id: "kw_bsb", bangId: "93", name: "飙升榜", subtitle: "热门飙升 · 酷我", source: .kw, accentHex: 0xFFF59E0B),
        LeaderboardItem(id: "kw_rgb", bangId: "16", name: "热歌榜", subtitle: "全站热播 · 酷我", source: .kw, accentHex: 0xFFFB923C),
        LeaderboardItem(id: "kw_xgb", bangId: "17", name: "新歌榜", subtitle: "最新上架 · 酷我", source: .kw, accentHex: 0xFF38BDF8),
        LeaderboardItem(id: "kw_dyb", bangId: "158", name: "抖音热歌榜", subtitle: "爆款单曲 · 酷我", source: .kw, accentHex: 0xFFEC4899),
        LeaderboardItem(id: "kw_hyb", bangId: "104", name: "华语榜", subtitle: "经典华语 · 酷我", source: .kw, accentHex: 0xFFEF4444),
        LeaderboardItem(id: "kw_omb", bangId: "22", name: "欧美榜", subtitle: "潮流欧美 · 酷我", source: .kw, accentHex: 0xFF6366F1)
    ]

    init(session: URLSession) {
        self.session = session
    }

    /// Keeps Kuwo CDN URLs on `http://` to prevent Tencent Cloud wildcard SSL hostname mismatches.
    static func normalizeKuwoImageUrl(_ raw: String?) -> String? {
        let trimmed = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let lower = trimmed.lowercased()
        let upgraded: String
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            upgraded = trimmed
        } else if trimmed.hasPrefix("//") {
            upgraded = "http:\(trimmed)"
        } else if trimmed.hasPrefix("120/") {
            let replaced = "500/" + trimmed.dropFirst(4)
            upgraded = "http://img1.kuwo.cn/star/albumcover/\(replaced)"
        } else {
            upgraded = "http://img1.kuwo.cn/star/albumcover/\(trimmed)"
        }

        if upgraded.lowercased().contains("kuwo.cn"),
           upgraded.lowercased().hasPrefix("https://") {
            return "http://" + upgraded.dropFirst(8)
        }
        return upgraded
    }

    static func parseKuwoArtist(_ raw: String?) -> (String, [String]) {
        let decoded = FormatUtils.decodeHtml(raw)
        let parsed = ArtistParser.parseRawString(decoded)
        let display = parsed.isEmpty ? ArtistParser.fallbackDisplayName(decoded) : parsed.joined(separator: " / ")
        return (display, parsed)
    }

    func search(query: String, page: Int = 1, limit: Int = 25) async -> [Track] {
        let encoded = urlEncodeFormComponent(query)
        let url = "http://search.kuwo.cn/r.s?client=kt&all=\(encoded)&pn=\(page - 1)&rn=\(limit)&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        guard let body = await getString(url),
              let root = JsonSafe.parseObject(body),
              let absList = JsonSafe.asArray(root["abslist"]) else {
            return []
        }

        return absList.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let rawRid = JsonSafe.asNonBlankString(obj["MUSICRID"]) else { return nil }
            let rid = rawRid.hasPrefix("MUSIC_") ? String(rawRid.dropFirst(6)) : rawRid
            let name = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(obj["SONGNAME"]) ?? JsonSafe.asNonBlankString(obj["NAME"]))
            guard !name.isEmpty else { return nil }

            let (artist, artistNames) = Self.parseKuwoArtist(JsonSafe.asString(obj["ARTIST"]))
            let album = FormatUtils.decodeHtml(JsonSafe.asString(obj["ALBUM"]))
            let durationSec = JsonSafe.asInt64(obj["DURATION"]) ?? 0
            let rawPic = JsonSafe.asNonBlankString(obj["hts_MVPIC"])
                ?? JsonSafe.asNonBlankString(obj["web_albumpic_short"])
                ?? JsonSafe.asNonBlankString(obj["albumpic"])

            return Track(
                id: "kw_\(rid)",
                title: name,
                artist: artist,
                album: album,
                source: .kw,
                songmid: rid,
                durationMs: durationSec * 1000,
                coverUrl: Self.normalizeKuwoImageUrl(rawPic),
                albumId: JsonSafe.asString(obj["ALBUMID"]),
                qualities: parseMinfoQualities(JsonSafe.asNonBlankString(obj["N_MINFO"]) ?? JsonSafe.asNonBlankString(obj["n_minfo"])),
                artistNames: artistNames
            )
        }
    }

    func getHotPlaylists(page: Int = 1, limit: Int = 20) async -> [OnlinePlaylist] {
        let url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=\(page)&rn=\(limit)&order=hot"
        guard let body = await getString(url),
              let root = JsonSafe.parseObject(body),
              let outerData = JsonSafe.asObject(root["data"]),
              let list = JsonSafe.asArray(outerData["data"]) else {
            return []
        }

        return list.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let id = JsonSafe.asString(obj["id"]) else { return nil }
            return OnlinePlaylist(
                id: id,
                name: FormatUtils.decodeHtml(JsonSafe.asString(obj["name"])),
                coverUrl: Self.normalizeKuwoImageUrl(JsonSafe.asString(obj["img"])) ?? "",
                author: FormatUtils.decodeHtml(JsonSafe.asString(obj["uname"])),
                playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(obj["listencnt"]) ?? 0),
                description: FormatUtils.decodeHtml(JsonSafe.asString(obj["desc"])),
                trackCount: JsonSafe.asInt(obj["total"]) ?? 0,
                source: .kw
            )
        }
    }

    func getPlaylistDetail(rawIdOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        guard let id = await extractPlaylistId(rawIdOrUrl) else {
            return .failure(LxRuntimeError.message("无法识别酷我歌单 ID"))
        }

        let pageSize = 500
        let maxPages = 20
        var allRawTracks: [Track] = []
        var seenIds = Set<String>()
        var firstRoot: [String: Any]?
        var totalExpected = Int.max

        for page in 0..<maxPages {
            if Task.isCancelled { return .failure(CancellationError()) }
            if allRawTracks.count >= totalExpected { break }
            let url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=\(id)&pn=\(page)&rn=\(pageSize)&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
            guard let body = await getString(url) else {
                if Task.isCancelled { return .failure(CancellationError()) }
                if page == 0 { return .failure(LxRuntimeError.message("获取酷我歌单失败，请检查网络连接")) }
                break
            }
            guard let root = JsonSafe.parseObject(body) else {
                if page == 0 { return .failure(LxRuntimeError.message("酷我歌单数据异常")) }
                break
            }
            if JsonSafe.asString(root["result"])?.lowercased() == "fail" {
                if page == 0 { return .failure(LxRuntimeError.message("酷我歌单不存在或暂无访问权限")) }
                break
            }
            if firstRoot == nil {
                firstRoot = root
                totalExpected = JsonSafe.asInt(root["total"]) ?? pageSize
            }
            guard let musicList = JsonSafe.asArray(root["musiclist"]) else {
                if page == 0 { return .failure(LxRuntimeError.message("酷我歌单为空")) }
                break
            }
            if musicList.isEmpty { break }

            var added = 0
            for el in musicList {
                guard let obj = JsonSafe.asObject(el),
                      let rid = JsonSafe.asNonBlankString(obj["id"]) else { continue }
                let title = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(obj["name"]))
                guard !title.isEmpty else { continue }
                let (artist, artistNames) = Self.parseKuwoArtist(JsonSafe.asString(obj["artist"]))
                let track = Track(
                    id: "kw_\(rid)",
                    title: title,
                    artist: artist,
                    album: FormatUtils.decodeHtml(JsonSafe.asString(obj["album"])),
                    source: .kw,
                    songmid: rid,
                    durationMs: (JsonSafe.asInt64(obj["duration"]) ?? 0) * 1000,
                    coverUrl: extractInlineCover(obj),
                    albumId: JsonSafe.asString(obj["albumid"]),
                    qualities: parseMinfoQualities(JsonSafe.asNonBlankString(obj["N_MINFO"]) ?? JsonSafe.asNonBlankString(obj["n_minfo"])),
                    artistNames: artistNames
                )
                if seenIds.insert(track.id).inserted {
                    allRawTracks.append(track)
                    added += 1
                }
            }
            if added == 0 || musicList.count < pageSize { break }
        }

        let rootTitle = JsonSafe.asNonBlankString(firstRoot?["title"])
        if allRawTracks.isEmpty && rootTitle == nil {
            return .failure(LxRuntimeError.message("酷我歌单不存在或暂无访问权限"))
        }

        let tracks = await hydrateMissingCovers(allRawTracks)
        let meta = OnlinePlaylist(
            id: id,
            name: FormatUtils.decodeHtml(rootTitle ?? "酷我歌单"),
            coverUrl: Self.normalizeKuwoImageUrl(JsonSafe.asString(firstRoot?["pic"])) ?? "",
            author: FormatUtils.decodeHtml(JsonSafe.asString(firstRoot?["uname"])),
            playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(firstRoot?["playnum"]) ?? 0),
            description: FormatUtils.decodeHtml(JsonSafe.asString(firstRoot?["info"])),
            trackCount: tracks.count,
            source: .kw
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: tracks))
    }

    func getLeaderboardDetail(board: LeaderboardItem) async -> Result<PlaylistDetailResult, Error> {
        let url = "http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=0&rn=100&type=bang&data=content&id=\(board.bangId)&show_copyright_off=0&pcmp4=1&isbang=1"
        guard let body = await getString(url) else {
            return .failure(LxRuntimeError.message("获取酷我榜单失败，请检查网络连接"))
        }
        guard let root = JsonSafe.parseObject(body),
              let list = JsonSafe.asArray(root["musiclist"]) else {
            return .failure(LxRuntimeError.message("酷我榜单数据为空"))
        }

        let rawTracks: [Track] = list.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let rid = JsonSafe.asNonBlankString(obj["id"]) else { return nil }
            let title = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(obj["name"]))
            guard !title.isEmpty else { return nil }
            let (artist, artistNames) = Self.parseKuwoArtist(JsonSafe.asString(obj["artist"]))
            return Track(
                id: "kw_\(rid)",
                title: title,
                artist: artist,
                album: FormatUtils.decodeHtml(JsonSafe.asString(obj["album"])),
                source: .kw,
                songmid: rid,
                durationMs: (JsonSafe.asInt64(obj["duration"]) ?? 0) * 1000,
                coverUrl: extractInlineCover(obj),
                albumId: JsonSafe.asString(obj["albumid"]),
                qualities: parseMinfoQualities(JsonSafe.asNonBlankString(obj["n_minfo"]) ?? JsonSafe.asNonBlankString(obj["N_MINFO"])),
                artistNames: artistNames
            )
        }

        let tracks = await hydrateMissingCovers(rawTracks)
        let picCandidates = [
            JsonSafe.asString(root["pic5"]),
            JsonSafe.asString(root["pic2"]),
            JsonSafe.asString(root["pic"])
        ]
        let rawBoardPic = picCandidates.compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let meta = OnlinePlaylist(
            id: board.bangId,
            name: JsonSafe.asString(root["name"]) ?? board.name,
            coverUrl: Self.normalizeKuwoImageUrl(rawBoardPic) ?? "",
            author: "酷我音乐官方榜",
            playCount: JsonSafe.asString(root["pub"]) ?? "",
            trackCount: tracks.count,
            source: .kw
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: tracks))
    }

    func getLyric(songmid: String) async -> ParsedLyrics {
        let url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=\(songmid)"
        guard let body = await getString(url),
              let root = JsonSafe.parseObject(body),
              let dataObj = JsonSafe.asObject(root["data"]),
              let lrcList = JsonSafe.asArray(dataObj["lrclist"]) else {
            return ParsedLyrics()
        }

        var rawLines: [(Int64, String)] = []
        rawLines.reserveCapacity(lrcList.count)
        for el in lrcList {
            guard let obj = JsonSafe.asObject(el),
                  let sec = JsonSafe.asDouble(obj["time"]),
                  sec.isFinite else { continue }
            let text = FormatUtils.decodeHtml(JsonSafe.asString(obj["lineLyric"]))
            if !text.isEmpty {
                let timeMs = max(Int64((sec * 1000.0).rounded()), 0)
                rawLines.append((timeMs, text))
            }
        }
        guard !rawLines.isEmpty else { return ParsedLyrics() }
        let sorted = rawLines.sorted { $0.0 < $1.0 }
        let indexed = sorted.enumerated().map { idx, item in
            LyricLine(timeMs: item.0, text: item.1, translation: nil, lineIndex: idx)
        }
        return ParsedLyrics(lines: indexed, hasTranslation: false)
    }

    func resolveCover(songmid: String) async -> String? {
        let url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=\(songmid)"
        guard let pic = await getString(url)?.trimmingCharacters(in: .whitespacesAndNewlines),
              pic.lowercased().hasPrefix("http") else { return nil }
        return Self.normalizeKuwoImageUrl(pic)
    }

    private func extractInlineCover(_ obj: [String: Any]) -> String? {
        let candidates = [
            JsonSafe.asString(obj["albumpic"]),
            JsonSafe.asString(obj["pic"]),
            JsonSafe.asString(obj["web_albumpic_short"]),
            JsonSafe.asString(obj["pic120"])
        ]
        let raw = candidates.compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return Self.normalizeKuwoImageUrl(raw)
    }

    private func hydrateMissingCovers(_ tracks: [Track], maxHydrate: Int = 24) async -> [Track] {
        let targets = tracks.prefix(maxHydrate).filter { ($0.coverUrl ?? "").isEmpty }
        guard !targets.isEmpty else { return tracks }

        let coverMap: [String: String] = await withTaskGroup(of: (String, String?)?.self) { group in
            for t in targets {
                group.addTask { [weak self] in
                    guard let self else { return nil }
                    let cover = await self.resolveCover(songmid: t.songmid)
                    return (t.songmid, cover)
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                return nil
            }
            var map: [String: String] = [:]
            var completed = 0
            for await item in group {
                guard let pair = item else {
                    group.cancelAll()
                    break
                }
                if let url = pair.1 {
                    map[pair.0] = url
                }
                completed += 1
                if completed >= targets.count {
                    group.cancelAll()
                    break
                }
            }
            return map
        }

        guard !coverMap.isEmpty else { return tracks }
        return tracks.map { track in
            if (track.coverUrl ?? "").isEmpty, let hydrated = coverMap[track.songmid] {
                var copy = track
                copy.coverUrl = hydrated
                return copy
            }
            return track
        }
    }

    func extractPlaylistId(_ raw: String) async -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty && trimmed.allSatisfy(\.isNumber) { return trimmed }

        let pattern = "(?:/playlist(?:_detail)?/|[?&](?:pid|id)=)(\\d+)"
        if let direct = firstRegexCapture(pattern: pattern, in: trimmed) {
            return direct
        }

        guard let urlStr = extractFirstHttpUrl(from: trimmed),
              let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")
        guard let (_, resp) = try? await session.data(for: req),
              let finalUrl = resp.url?.absoluteString else { return nil }
        return firstRegexCapture(pattern: pattern, in: finalUrl)
    }

    private func parseMinfoQualities(_ minfo: String?) -> [QualityInfo] {
        guard let minfo, !minfo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return [QualityInfo(type: "128k"), QualityInfo(type: "320k")]
        }
        var seen = Set<String>()
        var result: [QualityInfo] = []
        for segment in minfo.components(separatedBy: ";") {
            let ns = segment as NSString
            let range = NSRange(location: 0, length: ns.length)
            guard let m = Self.minfoRegex.firstMatch(in: segment, options: [], range: range),
                  m.numberOfRanges > 4 else { continue }
            let bitrate = ns.substring(with: m.range(at: 2))
            let size = ns.substring(with: m.range(at: 4)).uppercased()
            let type: String?
            switch bitrate {
            case "4000": type = "flac24bit"
            case "2000": type = "flac"
            case "320": type = "320k"
            case "128": type = "128k"
            default: type = nil
            }
            if let type, seen.insert(type).inserted {
                result.append(QualityInfo(type: type, size: size))
            }
        }
        return result.isEmpty ? [QualityInfo(type: "128k")] : result
    }

    private func getString(_ urlStr: String) async -> String? {
        guard let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}
