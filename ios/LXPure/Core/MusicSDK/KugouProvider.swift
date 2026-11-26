import Foundation

final class KugouProvider: Sendable {
    private let session: URLSession

    init(session: URLSession) {
        self.session = session
    }

    func search(query: String, page: Int = 1, limit: Int = 25) async -> [Track] {
        let encoded = urlEncodeFormComponent(query)
        let urlStr = "https://songsearch.kugou.com/song_search_v2?keyword=\(encoded)&page=\(page)&pagesize=\(limit)&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
        guard let url = URL(string: urlStr) else { return [] }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")

        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse, (200..<300).contains(httpResp.statusCode),
              let root = JsonSafe.parseObject(data),
              JsonSafe.asInt(root["error_code"]) == 0,
              let dataObj = JsonSafe.asObject(root["data"]),
              let list = JsonSafe.asArray(dataObj["lists"]) else {
            return []
        }

        return list.compactMap { el in
            guard let obj = JsonSafe.asObject(el),
                  let hash = JsonSafe.asNonBlankString(obj["FileHash"]) else { return nil }
            let audioId = JsonSafe.asNonBlankString(obj["Audioid"]) ?? hash
            let baseName = FormatUtils.decodeHtml(JsonSafe.asNonBlankString(obj["OriSongName"]) ?? JsonSafe.asNonBlankString(obj["SongName"]))
            guard !baseName.isEmpty else { return nil }
            let suffix = FormatUtils.decodeHtml(JsonSafe.asString(obj["Suffix"]))
            let title = suffix.isEmpty ? baseName : "\(baseName) \(suffix)"

            let singersList = (JsonSafe.asArray(obj["Singers"]) ?? []).compactMap { el -> String? in
                guard let rawName = JsonSafe.asNonBlankString(JsonSafe.asObject(el)?["name"]) else { return nil }
                let decoded = FormatUtils.decodeHtml(rawName).trimmingCharacters(in: .whitespacesAndNewlines)
                return decoded.isEmpty ? nil : decoded
            }
            var artist = singersList.joined(separator: " / ")
            if artist.isEmpty { artist = FormatUtils.decodeHtml(JsonSafe.asString(obj["SingerName"])) }
            if artist.isEmpty { artist = "未知歌手" }

            var qualities: [QualityInfo] = []
            let fSize = JsonSafe.asInt64(obj["FileSize"]) ?? 0
            let hqSize = JsonSafe.asInt64(obj["HQFileSize"]) ?? 0
            let sqSize = JsonSafe.asInt64(obj["SQFileSize"]) ?? 0
            let resSize = JsonSafe.asInt64(obj["ResFileSize"]) ?? 0
            let hqHash = JsonSafe.asNonBlankString(obj["HQFileHash"])
            let sqHash = JsonSafe.asNonBlankString(obj["SQFileHash"])
            let resHash = JsonSafe.asNonBlankString(obj["ResFileHash"])
            if fSize > 0 { qualities.append(QualityInfo(type: "128k", size: FormatUtils.formatSize(fSize), hash: hash)) }
            if hqSize > 0, let hqHash { qualities.append(QualityInfo(type: "320k", size: FormatUtils.formatSize(hqSize), hash: hqHash)) }
            if sqSize > 0, let sqHash { qualities.append(QualityInfo(type: "flac", size: FormatUtils.formatSize(sqSize), hash: sqHash)) }
            if resSize > 0, let resHash { qualities.append(QualityInfo(type: "flac24bit", size: FormatUtils.formatSize(resSize), hash: resHash)) }
            if qualities.isEmpty { qualities.append(QualityInfo(type: "128k", size: "", hash: hash)) }

            let img = JsonSafe.asNonBlankString(obj["Image"])?
                .replacingOccurrences(of: "{size}", with: "400")
                .replacingOccurrences(of: "http://", with: "https://")

            return Track(
                id: "kg_\(audioId)_\(hash)",
                title: title,
                artist: artist,
                album: FormatUtils.decodeHtml(JsonSafe.asString(obj["AlbumName"])),
                source: .kg,
                songmid: audioId,
                durationMs: (JsonSafe.asInt64(obj["Duration"]) ?? 0) * 1000,
                coverUrl: img,
                albumId: JsonSafe.asString(obj["AlbumID"]),
                hash: hash,
                qualities: qualities,
                artistNames: singersList
            )
        }
    }

    func getLyric(track: Track) async -> ParsedLyrics {
        guard let hash = track.hash, !hash.isEmpty else { return ParsedLyrics() }
        let durationMs = max(track.durationMs, 1000)
        let keyword = urlEncodeFormComponent("\(track.artist)-\(track.title)")
        let searchUrlStr = "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=\(keyword)&duration=\(durationMs)&hash=\(hash)"
        guard let searchUrl = URL(string: searchUrlStr),
              let (sData, sResp) = try? await session.data(from: searchUrl),
              let sHttp = sResp as? HTTPURLResponse, (200..<300).contains(sHttp.statusCode),
              let sRoot = JsonSafe.parseObject(sData),
              let candidates = JsonSafe.asArray(sRoot["candidates"]),
              let candidate = JsonSafe.asObject(candidates.first),
              let id = JsonSafe.asString(candidate["id"]),
              let accessKey = JsonSafe.asString(candidate["accesskey"]) else {
            return ParsedLyrics()
        }

        let dlUrlStr = "https://lyrics.kugou.com/download?ver=1&client=pc&id=\(id)&accesskey=\(accessKey)&fmt=lrc&charset=utf8"
        guard let dlUrl = URL(string: dlUrlStr),
              let (dData, dResp) = try? await session.data(from: dlUrl),
              let dHttp = dResp as? HTTPURLResponse, (200..<300).contains(dHttp.statusCode),
              let dRoot = JsonSafe.parseObject(dData),
              let b64 = JsonSafe.asString(dRoot["content"]),
              let decoded = LxCrypto.decodeBase64Flexible(b64),
              let rawLrc = String(data: decoded, encoding: .utf8) else {
            return ParsedLyrics()
        }
        return LrcParser.parse(rawLrc)
    }
}
