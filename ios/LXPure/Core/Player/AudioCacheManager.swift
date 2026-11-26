import Foundation

final class AudioCacheManager: @unchecked Sendable {
    static let maxAudioCacheBytes: Int64 = 300 * 1024 * 1024 // 300 MB
    private static let supportedExtensions = ["flac", "m4a", "mp3", "aac", "wav"]

    private let cacheDir: URL
    private let session: URLSession
    private let ioQueue = DispatchQueue(label: "com.lxpure.audiocache.io", qos: .utility)
    private let lock = NSLock()
    private var activeCacheTasks: [String: URLSessionDownloadTask] = [:]
    private var activeTaskTokens: [String: UUID] = [:]
    private var keyGenerations: [String: UInt64] = [:]
    private var cacheGeneration: UInt64 = 0

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
        cacheKey(
            trackId: trackId,
            resolvedTrackId: nil,
            sourceId: nil,
            sourceRevision: nil,
            quality: quality
        )
    }

    static func cacheKey(
        trackId: String,
        resolvedTrackId: String?,
        sourceId: String?,
        sourceRevision: String? = nil,
        quality: AudioQuality
    ) -> String {
        let resolvedPart = (resolvedTrackId?.isEmpty == false) ? resolvedTrackId! : trackId
        let sourcePart = (sourceId?.isEmpty == false) ? sourceId! : "default"
        let revPart = (sourceRevision?.isEmpty == false) ? "_\(sourceRevision!)" : ""
        let raw = "\(trackId)_\(resolvedPart)_\(sourcePart)\(revPart)_\(quality.lxType)"
        return raw
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
    }

    func cachedFileURL(for cacheKey: String) -> URL? {
        ioQueue.sync {
            findCachedFileOnIOQueue(for: cacheKey)
        }
    }

    func cachedFileURLAsync(for cacheKey: String) async -> URL? {
        await withCheckedContinuation { continuation in
            ioQueue.async {
                continuation.resume(returning: self.findCachedFileOnIOQueue(for: cacheKey))
            }
        }
    }

    private func findCachedFileOnIOQueue(for cacheKey: String) -> URL? {
        for ext in Self.supportedExtensions {
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
        guard let remoteUrl = LxJsRuntime.parseLenientURL(remoteUrlString) else { return }

        lock.lock()
        let scheduledGen = cacheGeneration
        let scheduledKeyGen = keyGenerations[cacheKey] ?? 0
        lock.unlock()

        ioQueue.async { [weak self] in
            guard let self else { return }
            if self.findCachedFileOnIOQueue(for: cacheKey) != nil { return }

            var req = URLRequest(url: remoteUrl)
            req.setValue(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                forHTTPHeaderField: "User-Agent"
            )

            var tasksToCancel: [URLSessionDownloadTask] = []
            let taskToken = UUID()
            var createdTask: URLSessionDownloadTask?

            self.lock.lock()
            if self.cacheGeneration != scheduledGen || (self.keyGenerations[cacheKey] ?? 0) != scheduledKeyGen {
                self.lock.unlock()
                return
            }
            if self.activeCacheTasks[cacheKey] != nil {
                self.lock.unlock()
                return
            }
            // Limit background caching to 1 concurrent download so rapid skipping never queues dozens of streams
            if !self.activeCacheTasks.isEmpty {
                tasksToCancel = Array(self.activeCacheTasks.values)
                self.activeCacheTasks.removeAll()
                self.activeTaskTokens.removeAll()
            }

            let task = self.session.downloadTask(with: req) { [weak self] tempUrl, response, error in
                guard let self else { return }

                var stillValid = false
                self.lock.lock()
                if self.cacheGeneration == scheduledGen &&
                    (self.keyGenerations[cacheKey] ?? 0) == scheduledKeyGen &&
                    self.activeTaskTokens[cacheKey] == taskToken {
                    self.activeCacheTasks.removeValue(forKey: cacheKey)
                    self.activeTaskTokens.removeValue(forKey: cacheKey)
                    stillValid = true
                }
                self.lock.unlock()

                guard stillValid,
                      error == nil,
                      let tempUrl,
                      let httpResp = response as? HTTPURLResponse,
                      (200..<300).contains(httpResp.statusCode) else {
                    return
                }

                let contentType = (httpResp.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
                if contentType.hasPrefix("text/") ||
                    contentType.contains("application/json") ||
                    contentType.contains("application/xml") ||
                    contentType.contains("application/javascript") {
                    return
                }

                guard let attrs = try? FileManager.default.attributesOfItem(atPath: tempUrl.path),
                      let fileSize = attrs[.size] as? Int64,
                      fileSize > 4096 else {
                    return
                }
                if httpResp.expectedContentLength > 0 && fileSize < httpResp.expectedContentLength {
                    return
                }

                let ext = Self.inferExtension(
                    urlString: remoteUrlString,
                    contentType: contentType,
                    quality: quality
                )
                let destUrl = self.cacheDir.appendingPathComponent("\(cacheKey).\(ext)")

                // Move synchronously inside URLSession's background callback before iOS deletes tempUrl
                self.ioQueue.sync {
                    self.lock.lock()
                    let genMatches = (self.cacheGeneration == scheduledGen &&
                        (self.keyGenerations[cacheKey] ?? 0) == scheduledKeyGen)
                    self.lock.unlock()
                    guard genMatches else { return }

                    try? FileManager.default.createDirectory(at: self.cacheDir, withIntermediateDirectories: true)
                    for oldExt in Self.supportedExtensions {
                        let candidate = self.cacheDir.appendingPathComponent("\(cacheKey).\(oldExt)")
                        try? FileManager.default.removeItem(at: candidate)
                    }
                    try? FileManager.default.moveItem(at: tempUrl, to: destUrl)
                    self.pruneLRUIfNeededOnIOQueue(excluding: destUrl)
                }
            }

            self.activeCacheTasks[cacheKey] = task
            self.activeTaskTokens[cacheKey] = taskToken
            createdTask = task
            self.lock.unlock()

            tasksToCancel.forEach { $0.cancel() }
            createdTask?.resume()
        }
    }

    func evict(cacheKey: String) {
        lock.lock()
        keyGenerations[cacheKey] = (keyGenerations[cacheKey] ?? 0) &+ 1
        let task = activeCacheTasks.removeValue(forKey: cacheKey)
        activeTaskTokens.removeValue(forKey: cacheKey)
        lock.unlock()
        task?.cancel()

        ioQueue.async { [weak self] in
            guard let self else { return }
            for ext in Self.supportedExtensions {
                let url = self.cacheDir.appendingPathComponent("\(cacheKey).\(ext)")
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    func calculateTotalCacheBytes() -> Int64 {
        ioQueue.sync {
            calculateTotalCacheBytesOnIOQueue()
        }
    }

    func calculateTotalCacheBytesAsync() async -> Int64 {
        await withCheckedContinuation { continuation in
            ioQueue.async {
                continuation.resume(returning: self.calculateTotalCacheBytesOnIOQueue())
            }
        }
    }

    private func calculateTotalCacheBytesOnIOQueue() -> Int64 {
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
        total += ImageMemoryCache.shared.diskCacheBytes()
        return total
    }

    func clearAllCaches() {
        lock.lock()
        cacheGeneration &+= 1
        let tasks = Array(activeCacheTasks.values)
        activeCacheTasks.removeAll()
        activeTaskTokens.removeAll()
        lock.unlock()
        tasks.forEach { $0.cancel() }

        ioQueue.sync {
            removeAllCacheFilesOnIOQueue()
        }
        URLCache.shared.removeAllCachedResponses()
        ImageMemoryCache.shared.clearAll()
    }

    func clearAllCachesAsync() async {
        lock.lock()
        cacheGeneration &+= 1
        let tasks = Array(activeCacheTasks.values)
        activeCacheTasks.removeAll()
        activeTaskTokens.removeAll()
        lock.unlock()
        tasks.forEach { $0.cancel() }

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            ioQueue.async {
                self.removeAllCacheFilesOnIOQueue()
                URLCache.shared.removeAllCachedResponses()
                ImageMemoryCache.shared.clearAll()
                continuation.resume()
            }
        }
    }

    private func removeAllCacheFilesOnIOQueue() {
        if let files = try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil) {
            for url in files {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    private func pruneLRUIfNeededOnIOQueue(excluding protectedUrl: URL? = nil) {
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
        let protectedPath = protectedUrl?.standardizedFileURL.path
        let sorted = entries.sorted { $0.modified < $1.modified }
        for entry in sorted {
            if let protectedPath, entry.url.standardizedFileURL.path == protectedPath {
                continue
            }
            try? FileManager.default.removeItem(at: entry.url)
            total -= entry.size
            if total <= Int64(Double(Self.maxAudioCacheBytes) * 0.85) {
                break
            }
        }
    }

    static func inferExtension(urlString: String, quality: AudioQuality) -> String {
        inferExtension(urlString: urlString, contentType: nil, quality: quality)
    }

    static func inferExtension(urlString: String, contentType: String?, quality: AudioQuality) -> String {
        // 1. Trust explicit URL path extension first (CDNs frequently downgrade lossless requests to MP3/M4A)
        if let pathExt = LxJsRuntime.parseLenientURL(urlString)?.pathExtension.lowercased(), !pathExt.isEmpty {
            switch pathExt {
            case "flac": return "flac"
            case "m4a", "mp4": return "m4a"
            case "mp3": return "mp3"
            case "aac": return "aac"
            case "wav": return "wav"
            default: break
            }
        }

        // 2. Inspect HTTP Content-Type header if available
        let mime = (contentType ?? "").lowercased()
        if !mime.isEmpty {
            if mime.contains("flac") { return "flac" }
            if mime.contains("mp4") || mime.contains("m4a") { return "m4a" }
            if mime.contains("aac") { return "aac" }
            if mime.contains("wav") { return "wav" }
            if mime.contains("mpeg") || mime.contains("mp3") { return "mp3" }
        }

        // 3. Fallback to requested quality only when URL and Content-Type give no container hint
        if quality == .flac || quality == .flac24 { return "flac" }
        return "mp3"
    }
}
