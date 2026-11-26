import Foundation

final class QqMusicProvider: Sendable {
    private let session: URLSession

    let leaderboards: [LeaderboardItem] = [
        LeaderboardItem(id: "tx_lxb", bangId: "4", name: "流行指数榜", subtitle: "实时飙升 · QQ", source: .tx, accentHex: 0xFF10B981),
        LeaderboardItem(id: "tx_rgb", bangId: "26", name: "热歌榜", subtitle: "全网热门 · QQ", source: .tx, accentHex: 0xFF14B8A6),
        LeaderboardItem(id: "tx_xgb", bangId: "27", name: "新歌榜", subtitle: "最新发行 · QQ", source: .tx, accentHex: 0xFF38BDF8),
        LeaderboardItem(id: "tx_bsb", bangId: "62", name: "飙升榜", subtitle: "热度暴涨 · QQ", source: .tx, accentHex: 0xFF22C55E),
        LeaderboardItem(id: "tx_dyb", bangId: "60", name: "抖快榜", subtitle: "短视频热曲 · QQ", source: .tx, accentHex: 0xFFEC4899),
        LeaderboardItem(id: "tx_ndb", bangId: "5", name: "内地榜", subtitle: "华语精选 · QQ", source: .tx, accentHex: 0xFF6366F1),
        LeaderboardItem(id: "tx_omb", bangId: "3", name: "欧美榜", subtitle: "Billboard · QQ", source: .tx, accentHex: 0xFF8B5CF6),
        LeaderboardItem(id: "tx_hgb", bangId: "16", name: "韩国榜", subtitle: "K-Pop · QQ", source: .tx, accentHex: 0xFFF59E0B)
    ]

    init(session: URLSession) {
        self.session = session
    }

    func search(query: String, page: Int = 1, limit: Int = 25) async -> [Track] {
        let searchId = UUID().uuidString.replacingOccurrences(of: "-", with: "").uppercased() +
            String(Int.random(in: 10000...99999))

        let payload = JsonSafe.stringify([
            "comm": [
                "_channelid": "0",
                "_os_version": "6.2.9200-2",
                "ct": "19",
                "cv": "2151",
                "guid": "1F70E520B2EAA7D25E11760783C53CA9",
                "patch": "118",
                "tmeAppID": "qqmusic",
                "tmeLoginType": 0,
                "uin": "0",
                "wid": "7223299733393904640"
            ] as [String: Any],
            "music.search.SearchCgiService": [
                "module": "music.search.SearchCgiService",
                "method": "DoSearchForQQMusicDesktop",
                "param": [
                    "grp": 1,
                    "num_per_page": limit,
                    "page_num": page,
                    "query": query,
                    "remoteplace": "txt.newclient.top",
                    "search_type": 0,
                    "searchid": searchId
                ] as [String: Any]
            ] as [String: Any]
        ])

        let sign = QqSign.zzcSign(payload)
        guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musics.fcg?sign=\(sign)") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("QQMusic 14090508(android 12)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(payload.utf8)

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode),
              let root = JsonSafe.parseObject(data),
              let svc = JsonSafe.asObject(root["music.search.SearchCgiService"]),
              let dataObj = JsonSafe.asObject(svc["data"]),
              let bodyObj = JsonSafe.asObject(dataObj["body"]),
              let songObj = JsonSafe.asObject(bodyObj["song"]),
              let list = JsonSafe.asArray(songObj["list"]) else {
            return []
        }

        return list.compactMap { el in
            guard let item = JsonSafe.asObject(el) else { return nil }
            return parseQqSong(item)
        }
    }

    func getHotPlaylists(page: Int = 1, limit: Int = 20) async -> [OnlinePlaylist] {
        let payload = JsonSafe.stringify([
            "comm": ["cv": 1602, "ct": 20],
            "playlist": [
                "method": "get_playlist_by_tag",
                "param": [
                    "id": 10000000,
                    "sin": (page - 1) * limit,
                    "size": limit,
                    "order": 5,
                    "cur_page": page
                ],
                "module": "playlist.PlayListPlazaServer"
            ] as [String: Any]
        ])

        guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musicu.fcg") else { return [] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(payload.utf8)

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode),
              let root = JsonSafe.parseObject(data),
              let pl = JsonSafe.asObject(root["playlist"]),
              let dataObj = JsonSafe.asObject(pl["data"]),
              let items = JsonSafe.asArray(dataObj["v_playlist"]) else {
            return []
        }

        return items.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let id = JsonSafe.asString(obj["tid"]) else { return nil }
            let cover = (JsonSafe.asString(obj["cover_url_medium"]) ?? "").replacingOccurrences(of: "http://", with: "https://")
            let creator = JsonSafe.asObject(obj["creator_info"])
            return OnlinePlaylist(
                id: id,
                name: FormatUtils.decodeHtml(JsonSafe.asString(obj["title"])),
                coverUrl: cover,
                author: JsonSafe.asString(creator?["nick"]) ?? "",
                playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(obj["access_num"]) ?? 0),
                description: FormatUtils.decodeHtml(JsonSafe.asString(obj["desc"])),
                trackCount: JsonSafe.asArray(obj["song_ids"])?.count ?? 0,
                source: .tx
            )
        }
    }

    func getPlaylistDetail(rawIdOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        guard let id = await extractPlaylistId(rawIdOrUrl), let numericId = Int64(id) else {
            return .failure(LxRuntimeError.message("无法识别 QQ 音乐歌单 ID"))
        }

        let pageSize = 500
        let maxTracks = 10_000
        var allTracks: [Track] = []
        var seenIds = Set<String>()
        var offset = 0
        var totalExpected = Int.max
        var firstDir: [String: Any]?

        while offset < totalExpected && allTracks.count < maxTracks {
            if Task.isCancelled { return .failure(CancellationError()) }
            let payload = JsonSafe.stringify([
                "comm": [
                    "cv": 4747474,
                    "ct": 24,
                    "format": "json",
                    "inCharset": "utf-8",
                    "outCharset": "utf-8",
                    "platform": "yqq.json",
                    "needNewCode": 1,
                    "uin": 0
                ] as [String: Any],
                "req_1": [
                    "module": "music.srfDissInfo.aiDissInfo",
                    "method": "uniform_get_Dissinfo",
                    "param": [
                        "disstid": numericId,
                        "userinfo": offset == 0 ? 1 : 0,
                        "tag": offset == 0 ? 1 : 0,
                        "orderlist": 1,
                        "song_begin": offset,
                        "song_num": pageSize,
                        "onlysonglist": offset == 0 ? 0 : 1,
                        "enc_host_uin": ""
                    ] as [String: Any]
                ] as [String: Any]
            ])

            guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musicu.fcg") else { break }
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("https://y.qq.com", forHTTPHeaderField: "Origin")
            req.setValue("https://y.qq.com/n/yqq/playsquare/\(id).html", forHTTPHeaderField: "Referer")
            req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data(payload.utf8)

            guard let (respData, resp) = try? await session.data(for: req),
                  let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode) else {
                if Task.isCancelled { return .failure(CancellationError()) }
                if offset == 0 {
                    return .failure(LxRuntimeError.message("获取 QQ 歌单失败，请检查网络连接"))
                }
                break
            }
            guard let root = JsonSafe.parseObject(respData) else {
                if offset == 0 { return .failure(LxRuntimeError.message("QQ 歌单数据异常")) }
                break
            }
            guard let req1 = JsonSafe.asObject(root["req_1"]) else {
                if offset == 0 { return .failure(LxRuntimeError.message("QQ 歌单数据为空")) }
                break
            }
            if let reqCode = JsonSafe.asInt(req1["code"]), reqCode != 0 {
                if offset == 0 { return .failure(LxRuntimeError.message("歌单不存在或暂无访问权限")) }
                break
            }
            guard let data = JsonSafe.asObject(req1["data"]) else {
                if offset == 0 { return .failure(LxRuntimeError.message("QQ 歌单数据为空")) }
                break
            }

            if firstDir == nil {
                firstDir = JsonSafe.asObject(data["dirinfo"])
                totalExpected = JsonSafe.asInt(data["total_song_num"])
                    ?? JsonSafe.asInt(firstDir?["songnum"])
                    ?? pageSize
            }

            guard let rawList = JsonSafe.asArray(data["songlist"]), !rawList.isEmpty else {
                break
            }

            var added = 0
            for el in rawList {
                guard let obj = JsonSafe.asObject(el), let t = parseQqSong(obj) else { continue }
                if seenIds.insert(t.id).inserted {
                    allTracks.append(t)
                    added += 1
                }
            }
            offset += rawList.count
            if added == 0 || (totalExpected > 0 && offset >= totalExpected) { break }
        }

        let dirTitle = JsonSafe.asNonBlankString(firstDir?["title"])
        if allTracks.isEmpty && dirTitle == nil {
            return .failure(LxRuntimeError.message("歌单不存在或暂无访问权限"))
        }

        let meta = OnlinePlaylist(
            id: id,
            name: FormatUtils.decodeHtml(dirTitle ?? "QQ 音乐歌单"),
            coverUrl: (JsonSafe.asString(firstDir?["picurl"]) ?? "").replacingOccurrences(of: "http://", with: "https://"),
            author: JsonSafe.asString(firstDir?["host_nick"]) ?? "",
            playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(firstDir?["listennum"]) ?? 0),
            description: FormatUtils.decodeHtml(JsonSafe.asString(firstDir?["desc"])),
            trackCount: allTracks.count,
            source: .tx
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: allTracks))
    }

    func getLeaderboardDetail(board: LeaderboardItem) async -> Result<PlaylistDetailResult, Error> {
        let payload = JsonSafe.stringify([
            "toplist": [
                "module": "musicToplist.ToplistInfoServer",
                "method": "GetDetail",
                "param": [
                    "topid": Int(board.bangId) ?? 4,
                    "num": 100,
                    "period": ""
                ] as [String: Any]
            ] as [String: Any],
            "comm": [
                "uin": 0,
                "format": "json",
                "ct": 20,
                "cv": 1859
            ] as [String: Any]
        ])

        guard let url = URL(string: "https://u.y.qq.com/cgi-bin/musicu.fcg") else {
            return .failure(LxRuntimeError.message("请求地址无效"))
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(payload.utf8)

        guard let (respData, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode) else {
            return .failure(LxRuntimeError.message("获取 QQ 榜单失败，请检查网络连接"))
        }
        guard let root = JsonSafe.parseObject(respData),
              let toplist = JsonSafe.asObject(root["toplist"]),
              let data = JsonSafe.asObject(toplist["data"]) else {
            return .failure(LxRuntimeError.message("QQ 榜单数据为空"))
        }

        let info = JsonSafe.asObject(data["data"])
        let tracks: [Track] = (JsonSafe.asArray(data["songInfoList"]) ?? []).compactMap { el in
            guard let obj = JsonSafe.asObject(el) else { return nil }
            return parseQqSong(obj)
        }

        let meta = OnlinePlaylist(
            id: board.bangId,
            name: JsonSafe.asString(info?["title"]) ?? board.name,
            coverUrl: (JsonSafe.asString(info?["frontPicUrl"]) ?? "").replacingOccurrences(of: "http://", with: "https://"),
            author: "QQ 音乐官方榜",
            playCount: JsonSafe.asString(info?["updateTime"]) ?? "",
            description: FormatUtils.decodeHtml(JsonSafe.asString(info?["intro"])),
            trackCount: tracks.count,
            source: .tx
        )
        return .success(PlaylistDetailResult(playlist: meta, tracks: tracks))
    }

    func getLyric(songmid: String) async -> ParsedLyrics {
        guard let url = URL(string: "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=\(songmid)&format=json&nobase64=0") else {
            return ParsedLyrics()
        }
        var req = URLRequest(url: url)
        req.setValue("https://y.qq.com/", forHTTPHeaderField: "Referer")
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode),
              let root = JsonSafe.parseObject(data) else {
            return ParsedLyrics()
        }

        let rawLrc = JsonSafe.asString(root["lyric"]).flatMap { decodeB64($0)?.replacingOccurrences(of: "&#10;", with: "\n") }
        let rawTrans = JsonSafe.asString(root["trans"]).flatMap { decodeB64($0)?.replacingOccurrences(of: "&#10;", with: "\n") }
        return LrcParser.parse(rawLrc, translation: rawTrans)
    }

    private func decodeB64(_ str: String) -> String? {
        guard !str.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = LxCrypto.decodeBase64Flexible(str) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func extractPlaylistId(_ raw: String) async -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty && trimmed.allSatisfy(\.isNumber) { return trimmed }

        let pattern = "(?:/playlist/|/playsquare/|[?&](?:id|tid|disstid)=)(\\d+)"
        if let direct = firstRegexCapture(pattern: pattern, in: trimmed) {
            return direct
        }

        guard let urlStr = extractFirstHttpUrl(from: trimmed),
              let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        guard let (_, resp) = try? await session.data(for: req),
              let finalUrl = resp.url?.absoluteString else { return nil }
        return firstRegexCapture(pattern: pattern, in: finalUrl)
    }

    func parseQqSong(_ item: [String: Any]) -> Track? {
        guard let mid = JsonSafe.asNonBlankString(item["mid"]) else { return nil }
        let file = JsonSafe.asObject(item["file"])
        let mediaMid = JsonSafe.asNonBlankString(file?["media_mid"]) ?? mid
        let title = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(item["title"]) ?? JsonSafe.asNonBlankString(item["name"]))
        guard !title.isEmpty else { return nil }

        let singers = JsonSafe.asArray(item["singer"]) ?? []
        let singerNames = singers.compactMap { el -> String? in
            guard let rawName = JsonSafe.asNonBlankString(JsonSafe.asObject(el)?["name"]) else { return nil }
            let decoded = FormatUtils.decodeHtml(rawName).trimmingCharacters(in: .whitespacesAndNewlines)
            return decoded.isEmpty ? nil : decoded
        }
        let artist = singerNames.isEmpty ? "未知歌手" : singerNames.joined(separator: " / ")

        let albumObj = JsonSafe.asObject(item["album"])
        let albumName = FormatUtils.decodeHtml(JsonSafe.asString(albumObj?["name"]))
        let albumMid = JsonSafe.asString(albumObj?["mid"]) ?? ""
        let firstSingerMid = JsonSafe.asString(JsonSafe.asObject(singers.first)?["mid"]) ?? ""

        let coverUrl: String?
        if !albumMid.isEmpty && albumMid != "空" {
            coverUrl = "https://y.gtimg.cn/music/photo_new/T002R500x500M000\(albumMid).jpg"
        } else if !firstSingerMid.isEmpty {
            coverUrl = "https://y.gtimg.cn/music/photo_new/T001R500x500M000\(firstSingerMid).jpg"
        } else {
            coverUrl = nil
        }

        var qualities: [QualityInfo] = []
        let s128 = JsonSafe.asInt64(file?["size_128mp3"]) ?? 0
        let s320 = JsonSafe.asInt64(file?["size_320mp3"]) ?? 0
        let sFlac = JsonSafe.asInt64(file?["size_flac"]) ?? 0
        let sHires = JsonSafe.asInt64(file?["size_hires"]) ?? 0
        if s128 > 0 { qualities.append(QualityInfo(type: "128k", size: FormatUtils.formatSize(s128))) }
        if s320 > 0 { qualities.append(QualityInfo(type: "320k", size: FormatUtils.formatSize(s320))) }
        if sFlac > 0 { qualities.append(QualityInfo(type: "flac", size: FormatUtils.formatSize(sFlac))) }
        if sHires > 0 { qualities.append(QualityInfo(type: "flac24bit", size: FormatUtils.formatSize(sHires))) }
        if qualities.isEmpty { qualities.append(QualityInfo(type: "128k")) }

        return Track(
            id: "tx_\(mid)",
            title: title,
            artist: artist,
            album: albumName,
            source: .tx,
            songmid: mid,
            durationMs: (JsonSafe.asInt64(item["interval"]) ?? 0) * 1000,
            coverUrl: coverUrl,
            albumId: albumMid,
            albumMid: albumMid,
            strMediaMid: mediaMid,
            songId: JsonSafe.asString(item["id"]),
            qualities: qualities,
            artistNames: singerNames
        )
    }
}
