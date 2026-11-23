import Foundation

final class NetEaseProvider: Sendable {
    private let session: URLSession
    private let ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    let leaderboards: [LeaderboardItem] = [
        LeaderboardItem(id: "wy_bsb", bangId: "19723756", name: "飙升榜", subtitle: "每日更新 · 网易云", source: .wy, accentHex: 0xFFF43F5E),
        LeaderboardItem(id: "wy_rgb", bangId: "3778678", name: "热歌榜", subtitle: "全站热门 · 网易云", source: .wy, accentHex: 0xFFFB7185),
        LeaderboardItem(id: "wy_xgb", bangId: "3779629", name: "新歌榜", subtitle: "近期新曲 · 网易云", source: .wy, accentHex: 0xFF38BDF8),
        LeaderboardItem(id: "wy_ycb", bangId: "2884035", name: "原创榜", subtitle: "独立原创 · 网易云", source: .wy, accentHex: 0xFFA855F7),
        LeaderboardItem(id: "wy_dyb", bangId: "2250011882", name: "抖音热歌榜", subtitle: "潮流爆款 · 网易云", source: .wy, accentHex: 0xFFEC4899),
        LeaderboardItem(id: "wy_scb", bangId: "991319590", name: "说唱榜", subtitle: "中文说唱 · 网易云", source: .wy, accentHex: 0xFFF59E0B),
        LeaderboardItem(id: "wy_dy", bangId: "1978921795", name: "电音榜", subtitle: "节拍律动 · 网易云", source: .wy, accentHex: 0xFF06B6D4),
        LeaderboardItem(id: "wy_gd", bangId: "71384707", name: "古典榜", subtitle: "经典器乐 · 网易云", source: .wy, accentHex: 0xFF6366F1)
    ]

    init(session: URLSession) {
        self.session = session
    }

    func search(query: String, page: Int = 1, limit: Int = 25) async -> [Track] {
        let eapiRes = await searchViaEapi(query: query, page: page, limit: limit)
        if !eapiRes.isEmpty { return eapiRes }
        return await searchViaWeapi(query: query, page: page, limit: limit)
    }

    private func searchViaEapi(query: String, page: Int, limit: Int) async -> [Track] {
        let payload = JsonSafe.stringify([
            "keyword": query,
            "needCorrect": "1",
            "channel": "typing",
            "offset": limit * (page - 1),
            "scene": "normal",
            "total": page == 1,
            "limit": limit
        ])
        let formParams = NetEaseCrypto.eapi(path: "/api/search/song/list/page", jsonBody: payload)
        guard let body = await postForm(url: "https://interface.music.163.com/eapi/batch", params: formParams),
              let root = JsonSafe.parseObject(body),
              JsonSafe.asInt(root["code"]) == 200,
              let dataObj = JsonSafe.asObject(root["data"]),
              let resources = JsonSafe.asArray(dataObj["resources"]) else {
            return []
        }

        return resources.compactMap { el in
            guard let item = JsonSafe.asObject(el),
                  let baseInfo = JsonSafe.asObject(item["baseInfo"]),
                  let song = JsonSafe.asObject(baseInfo["simpleSongData"]) else { return nil }
            return parseSongObj(song, privilege: JsonSafe.asObject(song["privilege"]))
        }
    }

    private func searchViaWeapi(query: String, page: Int, limit: Int) async -> [Track] {
        let payload = JsonSafe.stringify([
            "s": query,
            "type": 1,
            "limit": limit,
            "offset": limit * (page - 1),
            "total": true
        ])
        let formParams = NetEaseCrypto.weapi(jsonBody: payload)
        guard let body = await postForm(url: "https://music.163.com/weapi/cloudsearch/get/web", params: formParams),
              let root = JsonSafe.parseObject(body),
              JsonSafe.asInt(root["code"]) == 200,
              let resultObj = JsonSafe.asObject(root["result"]),
              let songs = JsonSafe.asArray(resultObj["songs"]) else {
            return []
        }

        return songs.compactMap { el in
            guard let song = JsonSafe.asObject(el) else { return nil }
            return parseSongObj(song, privilege: JsonSafe.asObject(song["privilege"]))
        }
    }

    func getHotPlaylists(page: Int = 1, limit: Int = 20) async -> [OnlinePlaylist] {
        let payload = JsonSafe.stringify([
            "cat": "全部",
            "order": "hot",
            "limit": limit,
            "offset": limit * (page - 1),
            "total": true
        ])
        guard let body = await postForm(url: "https://music.163.com/weapi/playlist/list", params: NetEaseCrypto.weapi(jsonBody: payload)),
              let root = JsonSafe.parseObject(body),
              JsonSafe.asInt(root["code"]) == 200,
              let playlists = JsonSafe.asArray(root["playlists"]) else {
            return []
        }

        return playlists.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let id = JsonSafe.asString(obj["id"]) else { return nil }
            let creator = JsonSafe.asObject(obj["creator"])
            let cover = (JsonSafe.asString(obj["coverImgUrl"]) ?? "").replacingOccurrences(of: "http://", with: "https://")
            return OnlinePlaylist(
                id: id,
                name: JsonSafe.asString(obj["name"]) ?? "",
                coverUrl: cover,
                author: JsonSafe.asString(creator?["nickname"]) ?? "",
                playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(obj["playCount"]) ?? 0),
                description: JsonSafe.asString(obj["description"]) ?? "",
                trackCount: JsonSafe.asInt(obj["trackCount"]) ?? 0,
                source: .wy
            )
        }
    }

    func getPlaylistDetail(rawIdOrUrl: String) async -> Result<PlaylistDetailResult, Error> {
        guard let playlistId = await extractPlaylistId(rawIdOrUrl) else {
            return .failure(LxRuntimeError.message("无法识别网易云歌单 ID"))
        }

        let linuxPayload = JsonSafe.stringify([
            "method": "POST",
            "url": "https://music.163.com/api/v3/playlist/detail",
            "params": [
                "id": playlistId,
                "n": 100000,
                "s": 8
            ] as [String: Any]
        ])

        guard let body = await postForm(url: "https://music.163.com/api/linux/forward", params: NetEaseCrypto.linuxApi(jsonBody: linuxPayload)) else {
            return .failure(LxRuntimeError.message("获取网易云歌单失败，请检查网络连接"))
        }
        guard let root = JsonSafe.parseObject(body) else {
            return .failure(LxRuntimeError.message("网易云歌单数据格式异常"))
        }
        guard JsonSafe.asInt(root["code"]) == 200 else {
            return .failure(LxRuntimeError.message("网易云歌单接口暂时不可用"))
        }
        guard let playlistObj = JsonSafe.asObject(root["playlist"]) else {
            return .failure(LxRuntimeError.message("歌单不存在或暂无访问权限"))
        }

        let creator = JsonSafe.asObject(playlistObj["creator"])
        let cover = (JsonSafe.asString(playlistObj["coverImgUrl"]) ?? "").replacingOccurrences(of: "http://", with: "https://")
        let meta = OnlinePlaylist(
            id: playlistId,
            name: JsonSafe.asString(playlistObj["name"]) ?? "网易云歌单",
            coverUrl: cover,
            author: JsonSafe.asString(creator?["nickname"]) ?? "",
            playCount: FormatUtils.formatPlayCount(JsonSafe.asInt64(playlistObj["playCount"]) ?? 0),
            description: JsonSafe.asString(playlistObj["description"]) ?? "",
            trackCount: JsonSafe.asInt(playlistObj["trackCount"]) ?? 0,
            source: .wy
        )

        var privilegesMap: [String: [String: Any]] = [:]
        if let privs = JsonSafe.asArray(root["privileges"]) {
            for el in privs {
                guard let privObj = JsonSafe.asObject(el),
                      let pid = JsonSafe.asString(privObj["id"]) else { continue }
                privilegesMap[pid] = privObj
            }
        }

        let trackIds: [String] = (JsonSafe.asArray(playlistObj["trackIds"]) ?? []).compactMap { el in
            JsonSafe.asString(JsonSafe.asObject(el)?["id"])
        }

        let directTracks: [Track] = (JsonSafe.asArray(playlistObj["tracks"]) ?? []).compactMap { el in
            guard let song = JsonSafe.asObject(el) else { return nil }
            let sid = JsonSafe.asString(song["id"]) ?? ""
            return parseSongObj(song, privilege: privilegesMap[sid])
        }

        let finalTracks: [Track]
        if directTracks.count >= trackIds.count || trackIds.isEmpty {
            finalTracks = directTracks
        } else {
            var hydratedMap: [String: Track] = Dictionary(uniqueKeysWithValues: directTracks.map { ($0.songmid, $0) })
            let missingIds = trackIds.filter { hydratedMap[$0] == nil }
            let chunks = stride(from: 0, to: missingIds.count, by: 400).map {
                Array(missingIds[$0..<min($0 + 400, missingIds.count)])
            }
            let waves = stride(from: 0, to: chunks.count, by: 3).map {
                Array(chunks[$0..<min($0 + 3, chunks.count)])
            }

            for wave in waves {
                let waveResults: [[Track]] = await withTaskGroup(of: [Track].self) { group in
                    for chunk in wave {
                        group.addTask { await self.fetchSongsByIdsWithRetry(chunk) }
                    }
                    var collected: [[Track]] = []
                    for await batch in group {
                        collected.append(batch)
                    }
                    return collected
                }
                for batch in waveResults {
                    for track in batch {
                        hydratedMap[track.songmid] = track
                    }
                }
            }

            let ordered = trackIds.compactMap { hydratedMap[$0] }
            finalTracks = ordered.isEmpty ? directTracks : ordered
        }

        let finalMeta = OnlinePlaylist(
            id: meta.id,
            name: meta.name,
            coverUrl: meta.coverUrl,
            author: meta.author,
            playCount: meta.playCount,
            description: meta.description,
            trackCount: finalTracks.count,
            source: .wy
        )
        return .success(PlaylistDetailResult(playlist: finalMeta, tracks: finalTracks))
    }

    private func fetchSongsByIdsWithRetry(_ ids: [String], maxAttempts: Int = 2) async -> [Track] {
        for _ in 0..<maxAttempts {
            let res = await fetchSongsByIds(ids)
            if !res.isEmpty { return res }
        }
        return []
    }

    private func fetchSongsByIds(_ ids: [String]) async -> [Track] {
        guard !ids.isEmpty else { return [] }
        let cParam = "[" + ids.map { "{\"id\":\($0)}" }.joined(separator: ",") + "]"
        let idsParam = "[" + ids.joined(separator: ",") + "]"
        let payload = JsonSafe.stringify(["c": cParam, "ids": idsParam])

        guard let body = await postForm(url: "https://music.163.com/weapi/v3/song/detail", params: NetEaseCrypto.weapi(jsonBody: payload)),
              let root = JsonSafe.parseObject(body),
              JsonSafe.asInt(root["code"]) == 200,
              let songs = JsonSafe.asArray(root["songs"]) else {
            return []
        }

        var privMap: [String: [String: Any]] = [:]
        if let privs = JsonSafe.asArray(root["privileges"]) {
            for el in privs {
                guard let privObj = JsonSafe.asObject(el),
                      let pid = JsonSafe.asString(privObj["id"]) else { continue }
                privMap[pid] = privObj
            }
        }

        return songs.compactMap { el in
            guard let obj = JsonSafe.asObject(el) else { return nil }
            let id = JsonSafe.asString(obj["id"]) ?? ""
            return parseSongObj(obj, privilege: privMap[id])
        }
    }

    func getLyric(songmid: String) async -> ParsedLyrics {
        let payload = JsonSafe.stringify([
            "id": songmid,
            "cp": false,
            "tv": 0,
            "lv": 0,
            "rv": 0,
            "kv": 0,
            "yv": 0,
            "ytv": 0,
            "yrv": 0
        ])
        guard let body = await postForm(
            url: "https://interface3.music.163.com/eapi/song/lyric/v1",
            params: NetEaseCrypto.eapi(path: "/api/song/lyric/v1", jsonBody: payload)
        ),
              let root = JsonSafe.parseObject(body) else {
            return ParsedLyrics()
        }

        let yrcText = JsonSafe.asString(JsonSafe.asObject(root["yrc"])?["lyric"])
        let lrcText = JsonSafe.asString(JsonSafe.asObject(root["lrc"])?["lyric"])
        let rawLrc = (yrcText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false) ? yrcText : lrcText

        let ytlrcText = JsonSafe.asString(JsonSafe.asObject(root["ytlrc"])?["lyric"])
        let tlyricText = JsonSafe.asString(JsonSafe.asObject(root["tlyric"])?["lyric"])
        let rawTrans = (ytlrcText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false) ? ytlrcText : tlyricText

        return LrcParser.parse(rawLrc, translation: rawTrans)
    }

    private func extractPlaylistId(_ raw: String) async -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty && trimmed.allSatisfy(\.isNumber) { return trimmed }

        if let direct = firstRegexCapture(pattern: "(?:[?&]id=|/playlist/)(\\d+)", in: trimmed) {
            return direct
        }

        guard let urlStr = firstRegexCapture(pattern: "(https?://\\S+)", in: trimmed),
              let url = URL(string: urlStr) else { return nil }
        var req = URLRequest(url: url)
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        guard let (_, resp) = try? await session.data(for: req),
              let finalUrl = resp.url?.absoluteString else { return nil }
        return firstRegexCapture(pattern: "(?:[?&]id=|/playlist/)(\\d+)", in: finalUrl)
    }

    func parseSongObj(_ item: [String: Any], privilege: [String: Any]?) -> Track? {
        guard let id = JsonSafe.asString(item["id"]),
              let name = JsonSafe.asString(item["name"]),
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        let artistsList = (JsonSafe.asArray(item["ar"]) ?? []).compactMap { el -> String? in
            JsonSafe.asString(JsonSafe.asObject(el)?["name"])
        }
        let artist = artistsList.isEmpty ? "未知歌手" : artistsList.joined(separator: " / ")

        let albumObj = JsonSafe.asObject(item["al"])
        let albumName = JsonSafe.asString(albumObj?["name"]) ?? ""
        let albumId = JsonSafe.asString(albumObj?["id"])
        let rawCover = JsonSafe.asString(albumObj?["picUrl"])?.trimmingCharacters(in: .whitespacesAndNewlines)
        let coverUrl = (rawCover?.isEmpty == false) ? rawCover?.replacingOccurrences(of: "http://", with: "https://") : nil
        let durationMs = JsonSafe.asInt64(item["dt"]) ?? 0

        var qualities: [QualityInfo] = []
        let maxBrLevel = JsonSafe.asString(privilege?["maxBrLevel"])
        let maxBr = JsonSafe.asInt(privilege?["maxbr"]) ?? 320_000

        qualities.append(
            QualityInfo(
                type: "128k",
                size: FormatUtils.formatSize(JsonSafe.asInt64(JsonSafe.asObject(item["l"])?["size"]) ?? 0)
            )
        )
        if maxBr >= 320_000 {
            qualities.append(
                QualityInfo(
                    type: "320k",
                    size: FormatUtils.formatSize(JsonSafe.asInt64(JsonSafe.asObject(item["h"])?["size"]) ?? 0)
                )
            )
        }
        if maxBr >= 999_000 {
            qualities.append(
                QualityInfo(
                    type: "flac",
                    size: FormatUtils.formatSize(JsonSafe.asInt64(JsonSafe.asObject(item["sq"])?["size"]) ?? 0)
                )
            )
        }
        if maxBrLevel == "hires" {
            qualities.append(
                QualityInfo(
                    type: "flac24bit",
                    size: FormatUtils.formatSize(JsonSafe.asInt64(JsonSafe.asObject(item["hr"])?["size"]) ?? 0)
                )
            )
        }

        return Track(
            id: "wy_\(id)",
            title: name,
            artist: artist,
            album: albumName,
            source: .wy,
            songmid: id,
            durationMs: durationMs,
            coverUrl: coverUrl,
            albumId: albumId,
            qualities: qualities
        )
    }

    private func postForm(url: String, params: [String: String]) async -> String? {
        guard let targetUrl = URL(string: url) else { return nil }
        var req = URLRequest(url: targetUrl)
        req.httpMethod = "POST"
        req.setValue(ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://music.163.com", forHTTPHeaderField: "Origin")
        req.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        req.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = buildFormUrlEncodedBody(params)

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }
}
