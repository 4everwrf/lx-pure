import Foundation

private struct TrackDownloadOutput: Sendable {
    let tempFileURL: URL
    let responseURL: String?
    let contentType: String?
    let expectedLength: Int64
}

private final class TrackDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destinationPartURL: URL
    private let onProgress: @Sendable (Int64, Int64) -> Void
    private let onComplete: @Sendable (Result<TrackDownloadOutput, Error>) -> Void

    private let lock = NSLock()
    private var output: TrackDownloadOutput?
    private var moveError: Error?
    private var didFinish = false
    private var lastProgressEmitUptime: TimeInterval = 0

    init(
        destinationPartURL: URL,
        onProgress: @escaping @Sendable (Int64, Int64) -> Void,
        onComplete: @escaping @Sendable (Result<TrackDownloadOutput, Error>) -> Void
    ) {
        self.destinationPartURL = destinationPartURL
        self.onProgress = onProgress
        self.onComplete = onComplete
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    private func abortWithError(_ error: Error, task: URLSessionDownloadTask) {
        withLock {
            if moveError == nil { moveError = error }
        }
        task.cancel()
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        if let http = downloadTask.response as? HTTPURLResponse {
            if !(200..<300).contains(http.statusCode) {
                abortWithError(LxRuntimeError.message("服务器返回 HTTP \(http.statusCode)"), task: downloadTask)
                return
            }
            let ct = http.value(forHTTPHeaderField: "Content-Type")
            if DownloadManager.isDisallowedAudioContentType(ct) {
                abortWithError(LxRuntimeError.message("音源返回了非音频响应"), task: downloadTask)
                return
            }
        }
        let now = ProcessInfo.processInfo.systemUptime
        let shouldEmit: Bool = withLock {
            if moveError != nil { return false }
            if now - lastProgressEmitUptime < 0.25 && totalBytesWritten != totalBytesExpectedToWrite {
                return false
            }
            lastProgressEmitUptime = now
            return true
        }
        if shouldEmit {
            onProgress(totalBytesWritten, totalBytesExpectedToWrite)
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        if let http = downloadTask.response as? HTTPURLResponse {
            if !(200..<300).contains(http.statusCode) {
                withLock {
                    if moveError == nil {
                        moveError = LxRuntimeError.message("服务器返回 HTTP \(http.statusCode)")
                    }
                }
                return
            }
            let ct = http.value(forHTTPHeaderField: "Content-Type")
            if DownloadManager.isDisallowedAudioContentType(ct) {
                withLock {
                    if moveError == nil {
                        moveError = LxRuntimeError.message("音源返回了非音频响应")
                    }
                }
                return
            }
        }
        do {
            let fm = FileManager.default
            try fm.createDirectory(at: destinationPartURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: destinationPartURL.path) {
                try fm.removeItem(at: destinationPartURL)
            }
            try fm.moveItem(at: location, to: destinationPartURL)
            let http = downloadTask.response as? HTTPURLResponse
            let result = TrackDownloadOutput(
                tempFileURL: destinationPartURL,
                responseURL: http?.url?.absoluteString,
                contentType: http?.value(forHTTPHeaderField: "Content-Type"),
                expectedLength: downloadTask.response?.expectedContentLength ?? -1
            )
            withLock { output = result }
        } catch {
            withLock { moveError = error }
        }
    }

    func completeIfNotFinished(with error: Error) {
        let shouldFire: Bool = withLock {
            if didFinish { return false }
            didFinish = true
            return true
        }
        guard shouldFire else { return }
        let mErr = withLock { moveError }
        onComplete(.failure(mErr ?? error))
    }

    func urlSession(_ session: URLSession, didBecomeInvalidWithError error: Error?) {
        completeIfNotFinished(with: error ?? CancellationError())
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let shouldFire: Bool = withLock {
            if didFinish { return false }
            didFinish = true
            return true
        }
        guard shouldFire else { return }

        let (out, mErr) = withLock { (output, moveError) }
        if let mErr {
            onComplete(.failure(mErr))
            return
        }
        if let error {
            onComplete(.failure(error))
            return
        }
        if let out {
            onComplete(.success(out))
        } else {
            onComplete(.failure(LxRuntimeError.message("下载未返回有效音频文件")))
        }
    }
}

final class DownloadManager: @unchecked Sendable {
    private let libraryStore: LibraryStore
    private let session: URLSession
    private let userApiManager: UserApiManager
    private let lock = NSLock()
    private let ioQueue = DispatchQueue(label: "com.lxpure.download.io", qos: .utility)
    private var activeTasks: [String: Task<Void, Never>] = [:]
    private var activeTaskTokens: [String: UUID] = [:]

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

        // Reconcile any .queued/.downloading rows stranded by a previous force-quit/crash
        Task { [weak self] in
            guard let self else { return }
            let orphanedPaths = await MainActor.run {
                self.libraryStore.reconcileInterruptedDownloads()
            }
            let dir = self.downloadDirectory
            self.ioQueue.async {
                let fm = FileManager.default
                for stored in orphanedPaths {
                    let resolved = self.resolveStoredDownloadURL(stored, in: dir)
                    try? fm.removeItem(at: resolved)
                }
                if let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
                    for file in files where file.lastPathComponent.hasSuffix(".part") {
                        try? fm.removeItem(at: file)
                    }
                }
            }
        }
    }

    static func isDisallowedAudioContentType(_ contentType: String?) -> Bool {
        guard let contentType else { return false }
        let normalized = contentType
            .split(separator: ";", maxSplits: 1)
            .first
            .map { String($0).trimmingCharacters(in: .whitespacesAndNewlines).lowercased() } ?? ""
        if normalized.isEmpty { return false }
        return normalized.hasPrefix("text/") ||
            normalized.hasPrefix("image/") ||
            normalized == "application/json" ||
            normalized == "application/xml" ||
            normalized == "application/xhtml+xml" ||
            normalized == "application/javascript" ||
            normalized == "application/x-javascript"
    }

    static func sanitizeFileName(_ raw: String) -> String {
        let withoutControl = raw.components(separatedBy: .controlCharacters).joined()
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
        let joined = withoutControl.components(separatedBy: invalid).joined(separator: "_")
        var cleaned = joined.trimmingCharacters(in: .whitespacesAndNewlines)
        while cleaned.hasPrefix(".") {
            cleaned.removeFirst()
        }
        cleaned = String(cleaned.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
            .trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        return cleaned.isEmpty ? "track" : cleaned
    }

    /// Resolves either a container-independent relative filename or a legacy absolute path
    /// (falling back to the current sandbox container's `Documents/Downloads/<filename>` after app updates).
    func resolveStoredDownloadURL(_ storedPath: String, in dir: URL? = nil) -> URL {
        let baseDir = dir ?? downloadDirectory
        if !storedPath.hasPrefix("/") && !storedPath.hasPrefix("file://") {
            return baseDir.appendingPathComponent(storedPath)
        }
        let direct = storedPath.hasPrefix("file://")
            ? (URL(string: storedPath) ?? URL(fileURLWithPath: storedPath))
            : URL(fileURLWithPath: storedPath)
        if FileManager.default.fileExists(atPath: direct.path) {
            return direct
        }
        let filename = direct.lastPathComponent
        if !filename.isEmpty {
            return baseDir.appendingPathComponent(filename)
        }
        return direct
    }

    private func isTokenActive(_ trackId: String, token: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeTaskTokens[trackId] == token
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

        let token = UUID()
        var started = false
        lock.lock()
        if activeTasks[track.id] == nil {
            activeTaskTokens[track.id] = token
            let task = Task { [weak self] in
                guard let self else { return }
                await self.runDownload(
                    track: track,
                    preferredQuality: preferredQuality,
                    token: token,
                    onFeedback: onFeedback
                )
            }
            activeTasks[track.id] = task
            started = true
        }
        lock.unlock()

        if !started {
            Task { @MainActor in onFeedback?("《\(track.title)》正在下载中") }
        }
    }

    private func runDownload(
        track: Track,
        preferredQuality: AudioQuality,
        token: UUID,
        onFeedback: (@MainActor @Sendable (String) -> Void)?
    ) async {
        let partFileName = "dl_\(token.uuidString).part"
        let partUrl = downloadDirectory.appendingPathComponent(partFileName)

        defer {
            try? FileManager.default.removeItem(at: partUrl)
            lock.lock()
            if activeTaskTokens[track.id] == token {
                activeTaskTokens.removeValue(forKey: track.id)
                activeTasks.removeValue(forKey: track.id)
            }
            lock.unlock()
        }

        let previousStoredPath: String? = await MainActor.run {
            let prev = self.libraryStore.downloadRecord(for: track.id)?.filePath
            guard !Task.isCancelled && self.isTokenActive(track.id, token: token) else { return prev }
            self.libraryStore.upsertDownload(DownloadRecord(
                trackId: track.id,
                track: track,
                quality: preferredQuality,
                status: .queued,
                progress: 0,
                filePath: nil,
                totalBytes: 0,
                errorMessage: nil,
                updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
            ))
            return prev
        }
        guard !Task.isCancelled && isTokenActive(track.id, token: token) else { return }

        let resolveResult = await userApiManager.resolveStream(
            track: track,
            preferredQuality: preferredQuality,
            allowCrossPlatformFallback: true
        )
        guard !Task.isCancelled && isTokenActive(track.id, token: token) else { return }

        let resolved: ResolvedStream
        switch resolveResult {
        case .success(let stream):
            resolved = stream
        case .failure(let err):
            if err is CancellationError || (err as NSError).code == NSURLErrorCancelled { return }
            await markFailedIfActive(track: track, quality: preferredQuality, reason: err.localizedDescription, token: token)
            if isTokenActive(track.id, token: token) && !Task.isCancelled {
                await MainActor.run { onFeedback?(err.localizedDescription) }
            }
            return
        }

        guard let remoteUrl = LxJsRuntime.parseLenientURL(resolved.url) else {
            await markFailedIfActive(track: track, quality: resolved.quality, reason: "下载链接无效", token: token)
            if isTokenActive(track.id, token: token) && !Task.isCancelled {
                await MainActor.run { onFeedback?("下载失败：链接无效") }
            }
            return
        }

        let startedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
        await MainActor.run {
            guard !Task.isCancelled && self.isTokenActive(track.id, token: token) else { return }
            self.libraryStore.upsertDownload(
                DownloadRecord(
                    trackId: track.id,
                    track: track,
                    quality: resolved.quality,
                    status: .downloading,
                    progress: 0.01,
                    filePath: nil,
                    totalBytes: 0,
                    errorMessage: nil,
                    updatedAt: startedAtMs
                ),
                persist: false,
                reorder: false
            )
        }

        do {
            let dlOutput = try await downloadAudioToPartFile(
                remoteUrl: remoteUrl,
                partUrl: partUrl,
                track: track,
                quality: resolved.quality,
                startedAtMs: startedAtMs,
                token: token
            )
            guard !Task.isCancelled && isTokenActive(track.id, token: token) else {
                try? FileManager.default.removeItem(at: dlOutput.tempFileURL)
                return
            }

            let actualSize: Int64 = (try? FileManager.default.attributesOfItem(atPath: dlOutput.tempFileURL.path)[.size] as? Int64) ?? 0
            if actualSize <= 4096 {
                throw LxRuntimeError.message("音频文件异常（仅 \(actualSize) 字节）")
            }
            if dlOutput.expectedLength > 0 && actualSize < dlOutput.expectedLength {
                throw LxRuntimeError.message("下载不完整（已接收 \(actualSize)/\(dlOutput.expectedLength) 字节）")
            }

            let ext = AudioCacheManager.inferExtension(
                urlString: dlOutput.responseURL ?? resolved.url,
                contentType: dlOutput.contentType,
                quality: resolved.quality
            )
            let safeName = Self.sanitizeFileName("\(track.artist) - \(track.title)")
            let idSuffix = String(Self.sanitizeFileName(track.id).suffix(8))
            let finalFileName = "\(safeName)_\(idSuffix).\(ext)"
            let outUrl = downloadDirectory.appendingPathComponent(finalFileName)

            let dir = downloadDirectory
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                self.ioQueue.async {
                    do {
                        let fm = FileManager.default
                        if fm.fileExists(atPath: outUrl.path) {
                            try fm.removeItem(at: outUrl)
                        }
                        try fm.moveItem(at: dlOutput.tempFileURL, to: outUrl)
                        if let prev = previousStoredPath, !prev.isEmpty {
                            let prevUrl = self.resolveStoredDownloadURL(prev, in: dir)
                            if prevUrl.standardizedFileURL != outUrl.standardizedFileURL {
                                try? fm.removeItem(at: prevUrl)
                            }
                        }
                        cont.resume()
                    } catch {
                        cont.resume(throwing: error)
                    }
                }
            }

            guard !Task.isCancelled && isTokenActive(track.id, token: token) else {
                try? FileManager.default.removeItem(at: outUrl)
                return
            }

            var completedTrack = track
            completedTrack.cachedFilePath = finalFileName
            await MainActor.run {
                guard !Task.isCancelled && self.isTokenActive(track.id, token: token) else { return }
                self.libraryStore.upsertDownload(DownloadRecord(
                    trackId: track.id,
                    track: completedTrack,
                    quality: resolved.quality,
                    status: .completed,
                    progress: 1.0,
                    filePath: finalFileName,
                    totalBytes: actualSize,
                    errorMessage: nil,
                    updatedAt: Int64(Date().timeIntervalSince1970 * 1000)
                ))
                // Clear token immediately while still on MainActor so any late queued progress Task cannot overwrite .completed
                self.lock.lock()
                if self.activeTaskTokens[track.id] == token {
                    self.activeTaskTokens.removeValue(forKey: track.id)
                    self.activeTasks.removeValue(forKey: track.id)
                }
                self.lock.unlock()
                onFeedback?("已离线保存《\(track.title)》(\(resolved.quality.badge))")
            }
        } catch {
            if error is CancellationError || (error as NSError).code == NSURLErrorCancelled || Task.isCancelled || !isTokenActive(track.id, token: token) {
                return
            }
            await markFailedIfActive(track: track, quality: resolved.quality, reason: error.localizedDescription, token: token)
            if isTokenActive(track.id, token: token) && !Task.isCancelled {
                await MainActor.run { onFeedback?("下载失败：\(error.localizedDescription)") }
            }
        }
    }

    private struct DownloadCancelState {
        var isCancelled = false
        var task: URLSessionDownloadTask?
        var session: URLSession?
    }

    private func downloadAudioToPartFile(
        remoteUrl: URL,
        partUrl: URL,
        track: Track,
        quality: AudioQuality,
        startedAtMs: Int64,
        token: UUID
    ) async throws -> TrackDownloadOutput {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 900
        config.waitsForConnectivity = false

        var req = URLRequest(url: remoteUrl, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", forHTTPHeaderField: "User-Agent")

        let cancelBox = LockBox<DownloadCancelState>(DownloadCancelState())

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let alreadyCancelled = cancelBox.withValue { state -> Bool in
                    if state.isCancelled || Task.isCancelled {
                        state.isCancelled = true
                        return true
                    }
                    return false
                }
                if alreadyCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }

                let delegate = TrackDownloadDelegate(
                    destinationPartURL: partUrl,
                    onProgress: { [weak self] written, expected in
                        let total = max(expected, written)
                        let progress = expected > 0
                            ? min(max(Double(written) / Double(expected), 0.01), 0.99)
                            : 0.5
                        Task { @MainActor [weak self] in
                            guard let self,
                                  self.isTokenActive(track.id, token: token),
                                  self.libraryStore.downloadRecord(for: track.id)?.status != .completed else {
                                return
                            }
                            self.libraryStore.upsertDownload(
                                DownloadRecord(
                                    trackId: track.id,
                                    track: track,
                                    quality: quality,
                                    status: .downloading,
                                    progress: progress,
                                    filePath: nil,
                                    totalBytes: total,
                                    errorMessage: nil,
                                    updatedAt: startedAtMs
                                ),
                                persist: false,
                                reorder: false
                            )
                        }
                    },
                    onComplete: { result in
                        let sess = cancelBox.withValue { state -> URLSession? in
                            let s = state.session
                            state.session = nil
                            state.task = nil
                            return s
                        }
                        sess?.finishTasksAndInvalidate()
                        continuation.resume(with: result)
                    }
                )
                let dlSession = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
                let dlTask = dlSession.downloadTask(with: req)
                let cancelledNow = cancelBox.withValue { state -> Bool in
                    if state.isCancelled || Task.isCancelled {
                        state.isCancelled = true
                        return true
                    }
                    state.session = dlSession
                    state.task = dlTask
                    return false
                }
                if cancelledNow {
                    dlSession.invalidateAndCancel()
                    delegate.completeIfNotFinished(with: CancellationError())
                } else {
                    dlTask.resume()
                }
            }
        } onCancel: {
            let (task, sess): (URLSessionDownloadTask?, URLSession?) = cancelBox.withValue { state in
                state.isCancelled = true
                let t = state.task
                let s = state.session
                state.task = nil
                state.session = nil
                return (t, s)
            }
            task?.cancel()
            sess?.invalidateAndCancel()
        }
    }

    func localCachedFileURL(for trackId: String) async -> URL? {
        let record = await MainActor.run { libraryStore.downloadRecord(for: trackId) }
        guard let record, record.status == .completed, let storedPath = record.filePath, !storedPath.isEmpty else {
            return nil
        }
        let dir = downloadDirectory
        let resolved: URL? = await withCheckedContinuation { cont in
            ioQueue.async {
                let candidate = self.resolveStoredDownloadURL(storedPath, in: dir)
                if FileManager.default.fileExists(atPath: candidate.path),
                   let attrs = try? FileManager.default.attributesOfItem(atPath: candidate.path),
                   let size = attrs[.size] as? Int64,
                   size > 1024 {
                    cont.resume(returning: candidate)
                } else {
                    cont.resume(returning: nil)
                }
            }
        }
        if resolved == nil {
            // Self-heal stale .completed record whose file was deleted in Files app
            await MainActor.run {
                if self.libraryStore.downloadRecord(for: trackId)?.status == .completed {
                    self.libraryStore.deleteDownloadRecord(trackId: trackId)
                }
            }
        }
        return resolved
    }

    func deleteDownload(trackId: String) async {
        let task: Task<Void, Never>? = {
            lock.lock()
            defer { lock.unlock() }
            activeTaskTokens.removeValue(forKey: trackId)
            return activeTasks.removeValue(forKey: trackId)
        }()
        task?.cancel()

        let storedPath = await MainActor.run { libraryStore.downloadRecord(for: trackId)?.filePath }
        let dir = downloadDirectory
        if let storedPath, !storedPath.isEmpty {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                ioQueue.async {
                    let fileUrl = self.resolveStoredDownloadURL(storedPath, in: dir)
                    try? FileManager.default.removeItem(at: fileUrl)
                    cont.resume()
                }
            }
        }
        await MainActor.run {
            libraryStore.deleteDownloadRecord(trackId: trackId)
        }
    }

    func calculateDownloadedBytes() -> Int64 {
        ioQueue.sync {
            calculateDownloadedBytesOnIOQueue()
        }
    }

    func calculateDownloadedBytesAsync() async -> Int64 {
        await withCheckedContinuation { cont in
            ioQueue.async {
                cont.resume(returning: self.calculateDownloadedBytesOnIOQueue())
            }
        }
    }

    private func calculateDownloadedBytesOnIOQueue() -> Int64 {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: downloadDirectory,
            includingPropertiesForKeys: [.fileSizeKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        return files.reduce(Int64(0)) { acc, url in
            if url.lastPathComponent.hasSuffix(".part") { return acc }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            return acc + Int64(size)
        }
    }

    private func markFailedIfActive(track: Track, quality: AudioQuality, reason: String, token: UUID) async {
        guard !Task.isCancelled && isTokenActive(track.id, token: token) else { return }
        await MainActor.run {
            guard !Task.isCancelled && self.isTokenActive(track.id, token: token) else { return }
            self.libraryStore.upsertDownload(DownloadRecord(
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

private final class LockBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T

    init(_ initial: T) {
        self.value = initial
    }

    func get() -> T {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    @discardableResult
    func set(_ newValue: T) -> T {
        lock.lock()
        defer { lock.unlock() }
        let old = value
        value = newValue
        return old
    }

    func withValue<R>(_ body: (inout T) -> R) -> R {
        lock.lock()
        defer { lock.unlock() }
        return body(&value)
    }
}

private extension LockBox where T: ExpressibleByNilLiteral {
    convenience init() {
        self.init(nil)
    }
}
