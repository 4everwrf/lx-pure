import Foundation

final class DownloadManager: @unchecked Sendable {
    private let libraryStore: LibraryStore
    private let session: URLSession
    private let userApiManager: UserApiManager
    private let lock = NSLock()
    private var activeTasks: [String: Task<Void, Never>] = [:]

    var downloadDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = docs.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    init(libraryStore: LibraryStore, session: URLSession, userApiManager: UserApiManager) {
        self.libraryStore = libraryStore
        self.session = session
        self.userApiManager = userApiManager
    }

    func enqueueDownload(
        track: Track,
        preferredQuality: AudioQuality,
        onFeedback: (@MainActor @Sendable (String) -> Void)? = nil
    ) {
        if track.isLocal {
            Task { @MainActor in onFeedback?("本地音乐无需下载") }
            return
        }

        let alreadyRunning: Bool = {
            lock.lock()
            defer { lock.unlock() }
            return activeTasks[track.id] != nil
        }()
        if alreadyRunning {
            Task { @MainActor in onFeedback?("《\(track.title)》正在下载中") }
            return
        }

        let task = Task { [weak self] in
            guard let self else { return }
            defer {
                self.lock.lock()
                self.activeTasks.removeValue(forKey: track.id)
                self.lock.unlock()
            }

            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            await MainActor.run {
                self.libraryStore.upsertDownload(DownloadRecord(
                    trackId: track.id,
                    track: track,
                    quality: preferredQuality,
                    status: .queued,
                    progress: 0,
                    filePath: nil,
                    totalBytes: 0,
                    errorMessage: nil,
                    updatedAt: nowMs
                ))
            }

            let resolveResult = await self.userApiManager.resolveStream(
                track: track,
                preferredQuality: preferredQuality,
                allowCrossPlatformFallback: true
            )

            let resolved: ResolvedStream
            switch resolveResult {
            case .success(let stream):
                resolved = stream
            case .failure(let err):
                await self.markFailed(track: track, quality: preferredQuality, reason: err.localizedDescription)
                await MainActor.run { onFeedback?(err.localizedDescription) }
                return
            }

            guard let remoteUrl = URL(string: resolved.url) else {
                await self.markFailed(track: track, quality: resolved.quality, reason: "下载链接无效")
                await MainActor.run { onFeedback?("下载失败：链接无效") }
                return
            }

            let ext = AudioCacheManager.inferExtension(urlString: resolved.url, quality: resolved.quality)
            let rawName = "\(track.artist) - \(track.title)"
            let sanitized = rawName
                .components(separatedBy: CharacterSet(charactersIn: "\\/:*?\"<>|"))
                .joined(separator: "_")
            let safeName = String(sanitized.prefix(80))
            let suffix = String(track.id.suffix(6))
            let outUrl = self.downloadDirectory.appendingPathComponent("\(safeName)_\(suffix).\(ext)")

            do {
                var req = URLRequest(url: remoteUrl)
                req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

                let (bytes, response) = try await self.session.bytes(for: req)
                guard let httpResp = response as? HTTPURLResponse,
                      (200..<300).contains(httpResp.statusCode) else {
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    throw LxRuntimeError.message("服务器返回 HTTP \(code)")
                }

                let expectedTotal = max(httpResp.expectedContentLength, 0)
                try? FileManager.default.removeItem(at: outUrl)
                FileManager.default.createFile(atPath: outUrl.path, contents: nil)
                let handle = try FileHandle(forWritingTo: outUrl)
                defer { try? handle.close() }

                await MainActor.run {
                    self.libraryStore.upsertDownload(DownloadRecord(
                        trackId: track.id,
                        track: track,
                        quality: resolved.quality,
                        status: .downloading,
                        progress: 0.01,
                        filePath: outUrl.path,
                        totalBytes: expectedTotal,
                        errorMessage: nil,
                        updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
                    ))
                }

                var buffer = Data()
                buffer.reserveCapacity(65536)
                var downloaded: Int64 = 0
                var lastEmit = Date()

                for try await byte in bytes {
                    try Task.checkCancellation()
                    buffer.append(byte)
                    if buffer.count >= 32768 {
                        try handle.write(contentsOf: buffer)
                        downloaded += Int64(buffer.count)
                        buffer.removeAll(keepingCapacity: true)

                        let now = Date()
                        if now.timeIntervalSince(lastEmit) >= 0.25 {
                            lastEmit = now
                            let progress = expectedTotal > 0
                                ? min(max(Double(downloaded) / Double(expectedTotal), 0.01), 0.99)
                                : 0.5
                            let currentBytes = max(expectedTotal, downloaded)
                            await MainActor.run {
                                self.libraryStore.upsertDownload(DownloadRecord(
                                    trackId: track.id,
                                    track: track,
                                    quality: resolved.quality,
                                    status: .downloading,
                                    progress: progress,
                                    filePath: outUrl.path,
                                    totalBytes: currentBytes,
                                    errorMessage: nil,
                                    updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
                                ))
                            }
                        }
                    }
                }

                if !buffer.isEmpty {
                    try handle.write(contentsOf: buffer)
                    downloaded += Int64(buffer.count)
                }

                var completedTrack = track
                completedTrack.cachedFilePath = outUrl.path
                await MainActor.run {
                    self.libraryStore.upsertDownload(DownloadRecord(
                        trackId: track.id,
                        track: completedTrack,
                        quality: resolved.quality,
                        status: .completed,
                        progress: 1.0,
                        filePath: outUrl.path,
                        totalBytes: downloaded,
                        errorMessage: nil,
                        updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
                    ))
                    onFeedback?("已离线保存《\(track.title)》(\(resolved.quality.badge))")
                }
            } catch {
                try? FileManager.default.removeItem(at: outUrl)
                await self.markFailed(track: track, quality: resolved.quality, reason: error.localizedDescription)
                await MainActor.run { onFeedback?("下载失败：\(error.localizedDescription)") }
            }
        }

        lock.lock()
        activeTasks[track.id] = task
        lock.unlock()
    }

    func localCachedFileURL(for trackId: String) async -> URL? {
        let record = await MainActor.run { libraryStore.downloadRecord(for: trackId) }
        guard let record, record.status == .completed, let path = record.filePath, !path.isEmpty else {
            return nil
        }
        let url = URL(fileURLWithPath: path)
        guard FileManager.default.fileExists(atPath: url.path),
              let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = attrs[.size] as? Int64,
              size > 1024 else {
            return nil
        }
        return url
    }

    func deleteDownload(trackId: String) async {
        let task: Task<Void, Never>? = {
            lock.lock()
            defer { lock.unlock() }
            return activeTasks.removeValue(forKey: trackId)
        }()
        task?.cancel()

        let path = await MainActor.run { libraryStore.downloadRecord(for: trackId)?.filePath }
        if let path {
            try? FileManager.default.removeItem(atPath: path)
        }
        await MainActor.run {
            libraryStore.deleteDownloadRecord(trackId: trackId)
        }
    }

    func calculateDownloadedBytes() -> Int64 {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: downloadDirectory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        return files.reduce(Int64(0)) { acc, url in
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return acc + Int64(size)
        }
    }

    private func markFailed(track: Track, quality: AudioQuality, reason: String) async {
        await MainActor.run {
            libraryStore.upsertDownload(DownloadRecord(
                trackId: track.id,
                track: track,
                quality: quality,
                status: .failed,
                progress: 0,
                filePath: nil,
                totalBytes: 0,
                errorMessage: reason,
                updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
            ))
        }
    }
}
