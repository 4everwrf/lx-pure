import Combine
import CryptoKit
import Foundation
import UIKit

struct AppUpdateInfo: Equatable, Sendable {
    let versionName: String
    let tagName: String
    let releaseTitle: String
    let releaseNotes: String
    let htmlUrl: String
    let assetName: String
    let downloadUrl: String
    let assetSizeBytes: Int64
    let expectedSha256: String?
}

enum AppUpdateState: Equatable {
    case idle
    case checking(manual: Bool)
    case available(AppUpdateInfo)
    case downloading(info: AppUpdateInfo, downloadedBytes: Int64, totalBytes: Int64, progress: Double)
    case verifying(AppUpdateInfo)
    case readyToInstall(info: AppUpdateInfo, localFileURL: URL)
    case error(info: AppUpdateInfo?, message: String)
}

private final class IpaDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destinationPartURL: URL
    private let onProgress: @Sendable (Int64, Int64) -> Void
    private let onComplete: @Sendable (Result<URL, Error>) -> Void

    private let lock = NSLock()
    private var movedFileURL: URL?
    private var moveError: Error?
    private var didFinish = false
    private var lastProgressEmitUptime: TimeInterval = 0

    init(
        destinationPartURL: URL,
        onProgress: @escaping @Sendable (Int64, Int64) -> Void,
        onComplete: @escaping @Sendable (Result<URL, Error>) -> Void
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

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let now = ProcessInfo.processInfo.systemUptime
        let shouldEmit: Bool = withLock {
            if now - lastProgressEmitUptime < 0.10 && totalBytesWritten != totalBytesExpectedToWrite {
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
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            withLock {
                moveError = NSError(
                    domain: "LXPureUpdate",
                    code: http.statusCode,
                    userInfo: [NSLocalizedDescriptionKey: "下载失败（HTTP \(http.statusCode)）"]
                )
            }
            return
        }
        do {
            let fm = FileManager.default
            try fm.createDirectory(at: destinationPartURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: destinationPartURL.path) {
                try fm.removeItem(at: destinationPartURL)
            }
            try fm.moveItem(at: location, to: destinationPartURL)
            withLock { movedFileURL = destinationPartURL }
        } catch {
            withLock { moveError = error }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let shouldFire: Bool = withLock {
            if didFinish { return false }
            didFinish = true
            return true
        }
        guard shouldFire else { return }

        if let error {
            onComplete(.failure(error))
            return
        }
        let (fileURL, mErr) = withLock { (movedFileURL, moveError) }
        if let mErr {
            onComplete(.failure(mErr))
        } else if let fileURL {
            onComplete(.success(fileURL))
        } else {
            onComplete(.failure(NSError(
                domain: "LXPureUpdate",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "下载未返回有效安装包"]
            )))
        }
    }
}

@MainActor
final class AppUpdateManager: ObservableObject {
    nonisolated static let githubOwner = "4everwrf"
    nonisolated static let githubRepo = "lx-pure"
    nonisolated static let latestReleaseApiUrl = "https://api.github.com/repos/\(githubOwner)/\(githubRepo)/releases/latest"
    nonisolated static let latestReleaseWebUrl = "https://github.com/\(githubOwner)/\(githubRepo)/releases/latest"

    private nonisolated static let minValidIpaBytes: Int64 = 100 * 1024

    @Published private(set) var state: AppUpdateState = .idle
    let messages = PassthroughSubject<String, Never>()

    private let session: URLSession
    private var checkTask: Task<Void, Never>?
    private var downloadSession: URLSession?
    private var downloadTask: URLSessionDownloadTask?
    private var activeDownloadToken = UUID()
    private var hasAutoChecked = false

    init(session: URLSession = .shared) {
        self.session = session
    }

    var currentAppVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.1.1"
    }

    var updatesDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = docs.appendingPathComponent("Updates", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    var isTrollStoreAvailable: Bool {
        guard let url = URL(string: "apple-magnifier://") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    nonisolated static func trollStoreInstallURL(for downloadUrl: String) -> URL? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=?+#")
        guard let encoded = downloadUrl.addingPercentEncoding(withAllowedCharacters: allowed) else {
            return nil
        }
        return URL(string: "apple-magnifier://install?url=\(encoded)")
    }

    // MARK: - Pure Version & Release Parsing Helpers

    nonisolated static func normalizeVersion(_ raw: String?) -> String {
        guard let raw else { return "0.0.0" }
        var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasPrefix("v") || trimmed.hasPrefix("V") {
            trimmed.removeFirst()
        }
        if let plusIdx = trimmed.firstIndex(of: "+") {
            trimmed = String(trimmed[..<plusIdx])
        }
        if let dashIdx = trimmed.firstIndex(of: "-") {
            trimmed = String(trimmed[..<dashIdx])
        }
        let cleaned = trimmed.trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? "0.0.0" : cleaned
    }

    /// Returns > 0 if `remote` is newer than `current`, 0 if equal, < 0 if older.
    nonisolated static func compareVersions(remote: String?, current: String?) -> Int {
        let rParts = parseNumericParts(normalizeVersion(remote))
        let cParts = parseNumericParts(normalizeVersion(current))
        let maxCount = max(rParts.count, cParts.count)
        for i in 0..<maxCount {
            let r = i < rParts.count ? rParts[i] : 0
            let c = i < cParts.count ? cParts[i] : 0
            if r != c { return r < c ? -1 : 1 }
        }
        return 0
    }

    nonisolated static func isNewerVersion(current: String?, remote: String?) -> Bool {
        compareVersions(remote: remote, current: current) > 0
    }

    private nonisolated static func parseNumericParts(_ normalized: String) -> [Int] {
        let parts = normalized.split(separator: ".").compactMap { segment -> Int? in
            let digits = segment.prefix { $0.isNumber }
            return Int(digits)
        }
        return parts.isEmpty ? [0] : parts
    }

    nonisolated static func extractTagFromReleaseUrl(_ url: String?) -> String? {
        guard let url, !url.isEmpty else { return nil }
        guard let range = url.range(of: "/releases/tag/", options: .caseInsensitive) else { return nil }
        let suffix = String(url[range.upperBound...])
        let tag = suffix
            .split(separator: "?", maxSplits: 1).first.map(String.init)?
            .split(separator: "#", maxSplits: 1).first.map(String.init)?
            .split(separator: "/", maxSplits: 1).first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return (tag?.isEmpty == false) ? tag : nil
    }

    nonisolated static func extractSha256Digest(_ rawDigest: String?) -> String? {
        guard let rawDigest else { return nil }
        var cleaned = rawDigest.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.lowercased().hasPrefix("sha256:") {
            cleaned = String(cleaned.dropFirst(7)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard cleaned.count == 64 else { return nil }
        let validHex = CharacterSet(charactersIn: "0123456789abcdefABCDEF")
        guard cleaned.unicodeScalars.allSatisfy({ validHex.contains($0) }) else { return nil }
        return cleaned.lowercased()
    }

    nonisolated static func isValidZipOrIpaMagic(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        return data[data.startIndex] == 0x50 &&
            data[data.startIndex + 1] == 0x4B &&
            data[data.startIndex + 2] == 0x03 &&
            data[data.startIndex + 3] == 0x04
    }

    nonisolated static func sanitizeReleaseNotes(_ body: String?) -> String {
        guard let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "常规体验优化与问题修复"
        }
        let filtered = body
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { line in
                !line.isEmpty &&
                    !line.hasPrefix("## ") &&
                    !line.hasPrefix("### 校验信息") &&
                    !line.hasPrefix("- **Android APK**") &&
                    !line.hasPrefix("- **iOS IPA**") &&
                    !line.hasPrefix("- SHA-256:") &&
                    !line.hasPrefix("- 签名证书")
            }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return filtered.isEmpty ? "常规体验优化与问题修复" : filtered
    }

    /// Parses GitHub Releases API JSON and selects the matching iOS `.ipa` asset.
    nonisolated static func parseGithubReleaseJson(_ jsonText: String) -> AppUpdateInfo? {
        guard let root = JsonSafe.parseObject(jsonText) else { return nil }
        if JsonSafe.asBool(root["draft"]) == true { return nil }
        if JsonSafe.asBool(root["prerelease"]) == true { return nil }

        guard let tagName = JsonSafe.asString(root["tag_name"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              !tagName.isEmpty else {
            return nil
        }

        let versionName = normalizeVersion(tagName)
        let titleRaw = JsonSafe.asString(root["name"])?.trimmingCharacters(in: .whitespacesAndNewlines)
        let releaseTitle = (titleRaw?.isEmpty == false) ? titleRaw! : tagName
        let htmlRaw = JsonSafe.asString(root["html_url"])?.trimmingCharacters(in: .whitespacesAndNewlines)
        let htmlUrl = (htmlRaw?.isEmpty == false)
            ? htmlRaw!
            : "https://github.com/\(githubOwner)/\(githubRepo)/releases/tag/\(tagName)"
        let notes = sanitizeReleaseNotes(JsonSafe.asString(root["body"]))

        let assets = (JsonSafe.asArray(root["assets"]) ?? [])
            .compactMap { JsonSafe.asObject($0) }
            .filter { obj in
                let name = JsonSafe.asString(obj["name"]) ?? ""
                let url = JsonSafe.asString(obj["browser_download_url"]) ?? ""
                return name.lowercased().hasSuffix(".ipa") && url.lowercased().hasPrefix("http")
            }

        let selected = assets.first { obj in
            let lower = (JsonSafe.asString(obj["name"]) ?? "").lowercased()
            return lower.contains("signed") && !lower.contains("unsigned")
        } ?? assets.first

        guard let selected,
              let assetName = JsonSafe.asString(selected["name"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              let downloadUrl = JsonSafe.asString(selected["browser_download_url"])?.trimmingCharacters(in: .whitespacesAndNewlines),
              !assetName.isEmpty,
              !downloadUrl.isEmpty else {
            return nil
        }

        let sizeBytes = JsonSafe.asInt64(selected["size"]) ?? 0
        let expectedSha256 = extractSha256Digest(JsonSafe.asString(selected["digest"]))

        return AppUpdateInfo(
            versionName: versionName,
            tagName: tagName,
            releaseTitle: releaseTitle,
            releaseNotes: notes,
            htmlUrl: htmlUrl,
            assetName: assetName,
            downloadUrl: downloadUrl,
            assetSizeBytes: sizeBytes,
            expectedSha256: expectedSha256
        )
    }

    nonisolated static func buildFallbackReleaseInfo(tagName: String) -> AppUpdateInfo {
        let cleanTag = tagName.trimmingCharacters(in: .whitespacesAndNewlines)
        let versionName = normalizeVersion(cleanTag)
        let assetName = "lx-pure-ios-\(cleanTag)-unsigned.ipa"
        return AppUpdateInfo(
            versionName: versionName,
            tagName: cleanTag,
            releaseTitle: cleanTag,
            releaseNotes: "发现新版本 \(cleanTag)，点击更新下载安装。",
            htmlUrl: "https://github.com/\(githubOwner)/\(githubRepo)/releases/tag/\(cleanTag)",
            assetName: assetName,
            downloadUrl: "https://github.com/\(githubOwner)/\(githubRepo)/releases/download/\(cleanTag)/\(assetName)",
            assetSizeBytes: 0,
            expectedSha256: nil
        )
    }

    // MARK: - Update Check & Download Lifecycle

    func checkOnStartupOnce() {
        guard !hasAutoChecked else { return }
        hasAutoChecked = true
        checkForUpdate(manual: false)
    }

    func checkForUpdate(manual: Bool = false) {
        switch state {
        case .downloading, .verifying:
            return
        default:
            break
        }

        checkTask?.cancel()
        let currentVer = currentAppVersion
        let updatesDir = updatesDirectory
        let sess = session

        checkTask = Task { [weak self] in
            guard let self else { return }
            self.state = .checking(manual: manual)
            await Task.detached(priority: .utility) {
                Self.cleanupStaleCachedIpas(in: updatesDir, currentVersion: currentVer)
            }.value

            let fetched = await Self.fetchLatestRelease(session: sess, currentVersion: currentVer)
            guard !Task.isCancelled else { return }

            guard let info = fetched else {
                self.state = .idle
                if manual {
                    self.messages.send("检查更新失败，请检查网络后重试")
                }
                return
            }

            if Self.isNewerVersion(current: currentVer, remote: info.versionName) {
                self.state = .available(info)
            } else {
                self.state = .idle
                if manual {
                    self.messages.send("当前已是最新版本 (v\(Self.normalizeVersion(currentVer)))")
                }
            }
        }
    }

    func startDownload(info: AppUpdateInfo) {
        cancelActiveDownloadSession()

        let token = UUID()
        activeDownloadToken = token

        let safeName = info.assetName.replacingOccurrences(
            of: "[^A-Za-z0-9._-]",
            with: "_",
            options: .regularExpression
        )
        let finalURL = updatesDirectory.appendingPathComponent(safeName)
        let partURL = updatesDirectory.appendingPathComponent("\(safeName).part")

        // Reuse already downloaded and verified IPA if present
        if FileManager.default.fileExists(atPath: finalURL.path),
           Self.isValidIpaFile(at: finalURL, info: info) {
            state = .readyToInstall(info: info, localFileURL: finalURL)
            return
        }

        guard let remoteURL = URL(string: info.downloadUrl) else {
            state = .error(info: info, message: "更新包下载地址无效")
            return
        }

        state = .downloading(info: info, downloadedBytes: 0, totalBytes: info.assetSizeBytes, progress: 0)

        var req = URLRequest(url: remoteURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 60)
        req.setValue("LXPure/\(Self.normalizeVersion(currentAppVersion))", forHTTPHeaderField: "User-Agent")

        let delegate = IpaDownloadDelegate(
            destinationPartURL: partURL,
            onProgress: { [weak self] written, expected in
                let total = expected > 0 ? expected : info.assetSizeBytes
                let progress = total > 0 ? min(max(Double(written) / Double(total), 0), 1) : 0
                Task { @MainActor [weak self] in
                    guard let self, self.activeDownloadToken == token else { return }
                    self.state = .downloading(
                        info: info,
                        downloadedBytes: written,
                        totalBytes: total,
                        progress: progress
                    )
                }
            },
            onComplete: { [weak self] result in
                Task { @MainActor [weak self] in
                    guard let self, self.activeDownloadToken == token else { return }
                    self.downloadSession?.finishTasksAndInvalidate()
                    self.downloadSession = nil
                    self.downloadTask = nil

                    switch result {
                    case .failure(let err):
                        try? FileManager.default.removeItem(at: partURL)
                        if (err as NSError).code == NSURLErrorCancelled {
                            self.state = .available(info)
                        } else {
                            self.state = .error(info: info, message: err.localizedDescription)
                        }
                    case .success(let downloadedPartURL):
                        self.state = .verifying(info)
                        let valid = await Task.detached(priority: .utility) {
                            Self.isValidIpaFile(at: downloadedPartURL, info: info)
                        }.value
                        guard self.activeDownloadToken == token else { return }
                        if !valid {
                            try? FileManager.default.removeItem(at: downloadedPartURL)
                            self.state = .error(info: info, message: "安装包校验未通过，请重试")
                            return
                        }
                        do {
                            let fm = FileManager.default
                            if fm.fileExists(atPath: finalURL.path) {
                                try fm.removeItem(at: finalURL)
                            }
                            try fm.moveItem(at: downloadedPartURL, to: finalURL)
                            self.state = .readyToInstall(info: info, localFileURL: finalURL)
                        } catch {
                            try? FileManager.default.removeItem(at: downloadedPartURL)
                            self.state = .error(info: info, message: "保存安装包失败：\(error.localizedDescription)")
                        }
                    }
                }
            }
        )

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 600
        let dlSession = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        let task = dlSession.downloadTask(with: req)
        self.downloadSession = dlSession
        self.downloadTask = task
        task.resume()
    }

    func cancelDownload() {
        let cur = state
        activeDownloadToken = UUID()
        cancelActiveDownloadSession()
        if case .downloading(let info, _, _, _) = cur {
            state = .available(info)
        }
    }

    func dismiss() {
        activeDownloadToken = UUID()
        cancelActiveDownloadSession()
        state = .idle
    }

    private func cancelActiveDownloadSession() {
        downloadTask?.cancel()
        downloadTask = nil
        downloadSession?.invalidateAndCancel()
        downloadSession = nil
    }

    // MARK: - Network & Verification Helpers

    private nonisolated static func fetchLatestRelease(session: URLSession, currentVersion: String) async -> AppUpdateInfo? {
        if let apiURL = URL(string: latestReleaseApiUrl) {
            var apiReq = URLRequest(url: apiURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
            apiReq.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            apiReq.setValue("LXPure/\(normalizeVersion(currentVersion))", forHTTPHeaderField: "User-Agent")

            if let (data, resp) = try? await session.data(for: apiReq),
               let http = resp as? HTTPURLResponse,
               (200..<300).contains(http.statusCode),
               let text = String(data: data, encoding: .utf8),
               let parsed = parseGithubReleaseJson(text) {
                return parsed
            }
        }

        // Fallback via release redirect when GitHub API is rate-limited
        if let webURL = URL(string: latestReleaseWebUrl) {
            var webReq = URLRequest(url: webURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
            webReq.httpMethod = "HEAD"
            webReq.setValue("LXPure/\(normalizeVersion(currentVersion))", forHTTPHeaderField: "User-Agent")
            if let (_, resp) = try? await session.data(for: webReq),
               let finalUrl = resp.url?.absoluteString,
               let tag = extractTagFromReleaseUrl(finalUrl) {
                return buildFallbackReleaseInfo(tagName: tag)
            }
        }
        return nil
    }

    private nonisolated static func isValidIpaFile(at url: URL, info: AppUpdateInfo) -> Bool {
        let fm = FileManager.default
        guard let attrs = try? fm.attributesOfItem(atPath: url.path),
              let fileSize = attrs[.size] as? Int64,
              fileSize >= minValidIpaBytes else {
            return false
        }
        if info.assetSizeBytes > 0 && fileSize != info.assetSizeBytes {
            return false
        }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let magic = try? handle.read(upToCount: 4), isValidZipOrIpaMagic(magic) else {
            return false
        }

        if let expectedHash = info.expectedSha256, !expectedHash.isEmpty {
            try? handle.seek(toOffset: 0)
            var hasher = SHA256()
            while true {
                guard let chunk = try? handle.read(upToCount: 64 * 1024), !chunk.isEmpty else { break }
                hasher.update(data: chunk)
            }
            let actualHash = hasher.finalize().map { String(format: "%02x", $0) }.joined()
            if actualHash.lowercased() != expectedHash.lowercased() {
                return false
            }
        }
        return true
    }

    private nonisolated static func cleanupStaleCachedIpas(in dir: URL, currentVersion: String) {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        let currentNorm = normalizeVersion(currentVersion)
        let regex = try? NSRegularExpression(pattern: "v(\\d+(?:\\.\\d+)+)", options: .caseInsensitive)

        for file in files {
            let name = file.lastPathComponent
            if name.hasSuffix(".part") {
                try? fm.removeItem(at: file)
            } else if name.lowercased().hasSuffix(".ipa") {
                let nsName = name as NSString
                var fileVer: String?
                if let match = regex?.firstMatch(in: name, range: NSRange(location: 0, length: nsName.length)),
                   match.numberOfRanges > 1 {
                    fileVer = nsName.substring(with: match.range(at: 1))
                }
                if fileVer == nil || !isNewerVersion(current: currentNorm, remote: fileVer) {
                    try? fm.removeItem(at: file)
                }
            }
        }
    }
}
