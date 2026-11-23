import Foundation

final class AudioCacheManager: @unchecked Sendable {
    static let maxAudioCacheBytes: Int64 = 300 * 1024 * 1024 // 300 MB

    private let cacheDir: URL
    private let session: URLSession
    private let lock = NSLock()
    private var activeCacheTasks: [String: URLSessionDownloadTask] = [:]

    init(session: URLSession, cacheDirectory: URL? = nil) {
        self.session = session
        if let cacheDirectory {
            self.cacheDir = cacheDirectory
        } else {
            let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory
            self.cacheDir = caches.appendingPathComponent("LXPureAudioCache", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    static func cacheKey(trackId: String, quality: AudioQuality) -> String {
        let safe = "\(trackId)_\(quality.lxType)"
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return safe
    }

    func cachedFileURL(for cacheKey: String) -> URL? {
        for ext in ["flac", "m4a", "mp3", "aac", "wav"] {
            let url = cacheDir.appendingPathComponent("\(cacheKey).\(ext)")
            if FileManager.default.fileExists(atPath: url.path),
               let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int64,
               size > 4096 {
                // Touch modification date for LRU freshness
                try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
                return url
            }
        }
        return nil
    }

    func cacheInBackground(remoteUrlString: String, cacheKey: String, quality: AudioQuality) {
        if cachedFileURL(for: cacheKey) != nil { return }
        guard let remoteUrl = URL(string: remoteUrlString) else { return }

        lock.lock()
        if activeCacheTasks[cacheKey] != nil {
            lock.unlock()
            return
        }
        lock.unlock()

        let ext = Self.inferExtension(urlString: remoteUrlString, quality: quality)
        let destUrl = cacheDir.appendingPathComponent("\(cacheKey).\(ext)")

        var req = URLRequest(url: remoteUrl)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

        let task = session.downloadTask(with: req) { [weak self] tempUrl, response, error in
            guard let self else { return }
            self.lock.lock()
            self.activeCacheTasks.removeValue(forKey: cacheKey)
            self.lock.unlock()

            guard error == nil,
                  let tempUrl,
                  let httpResp = response as? HTTPURLResponse,
                  (200..<300).contains(httpResp.statusCode) else {
                return
            }

            let contentType = (httpResp.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
            if contentType.contains("text/html") || contentType.contains("application/json") {
                return
            }

            try? FileManager.default.removeItem(at: destUrl)
            try? FileManager.default.moveItem(at: tempUrl, to: destUrl)
            self.pruneLRUIfNeeded()
        }

        lock.lock()
        activeCacheTasks[cacheKey] = task
        lock.unlock()
        task.resume()
    }

    func evict(cacheKey: String) {
        lock.lock()
        let task = activeCacheTasks.removeValue(forKey: cacheKey)
        lock.unlock()
        task?.cancel()

        for ext in ["flac", "m4a", "mp3", "aac", "wav"] {
            let url = cacheDir.appendingPathComponent("\(cacheKey).\(ext)")
            try? FileManager.default.removeItem(at: url)
        }
    }

    func calculateTotalCacheBytes() -> Int64 {
        var total: Int64 = 0
        if let files = try? FileManager.default.contentsOfDirectory(
            at: cacheDir,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) {
            for url in files {
                if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    total += Int64(size)
                }
            }
        }
        total += Int64(URLCache.shared.currentDiskUsage)
        return total
    }

    func clearAllCaches() {
        lock.lock()
        let tasks = Array(activeCacheTasks.values)
        activeCacheTasks.removeAll()
        lock.unlock()
        tasks.forEach { $0.cancel() }

        if let files = try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil) {
            for url in files {
                try? FileManager.default.removeItem(at: url)
            }
        }
        URLCache.shared.removeAllCachedResponses()
        ImageMemoryCache.shared.clear()
    }

    private func pruneLRUIfNeeded() {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: cacheDir,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        struct Entry {
            let url: URL
            let size: Int64
            let modified: Date
        }

        var entries: [Entry] = []
        var total: Int64 = 0
        for url in files {
            let vals = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            let size = Int64(vals?.fileSize ?? 0)
            let mod = vals?.contentModificationDate ?? .distantPast
            entries.append(Entry(url: url, size: size, modified: mod))
            total += size
        }

        guard total > Self.maxAudioCacheBytes else { return }
        let sorted = entries.sorted { $0.modified < $1.modified }
        for entry in sorted {
            try? FileManager.default.removeItem(at: entry.url)
            total -= entry.size
            if total <= Int64(Double(Self.maxAudioCacheBytes) * 0.85) {
                break
            }
        }
    }

    static func inferExtension(urlString: String, quality: AudioQuality) -> String {
        let lower = urlString.lowercased()
        if quality == .flac || quality == .flac24 || lower.contains(".flac") { return "flac" }
        if lower.contains(".m4a") { return "m4a" }
        if lower.contains(".wav") { return "wav" }
        return "mp3"
    }
}
