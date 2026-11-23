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

struct UpdateDownloadRoute: Equatable, Sendable {
    let label: String
    let url: String
}

enum AppUpdateState: Equatable {
    case idle
    case checking(manual: Bool)
    case available(AppUpdateInfo)
    case downloading(info: AppUpdateInfo, downloadedBytes: Int64, totalBytes: Int64, progress: Double, routeLabel: String)
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
    private nonisolated static let githubMirrorPrefixes: [(String, String)] = [
        ("镜像加速 1", "https://gh-proxy.org/"),
        ("镜像加速 2", "https://ghfast.top/")
    ]

    @Published private(set) var state: AppUpdateState = .idle
    let messages = PassthroughSubject<String, Never>()

    private let session: URLSession
    private var checkTask: Task<Void, Never>?
    private var downloadFlowTask: Task<Void, Never>?
    private var downloadSession: URLSession?
    private var downloadTask: URLSessionDownloadTask?
    private var activeDownloadToken = UUID()
    private var hasAutoChecked = false

    init(session: URLSession = .shared) {
        self.session = session
    }

    var currentAppVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.1.2"
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

    // MARK: - Pure Version, Route & Release Parsing Helpers

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
        var part = String(url[range.upperBound...])
        if let idx = part.firstIndex(of: "?") { part = String(part[..<idx]) }
        if let idx = part.firstIndex(of: "#") { part = String(part[..<idx]) }
        if let idx = part.firstIndex(of: "/") { part = String(part[..<idx]) }
        let tag = part.trimmingCharacters(in: .whitespacesAndNewlines)
        return tag.isEmpty ? nil : tag
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

    nonisolated static func buildDownloadRoutes(originalUrl: String) -> [UpdateDownloadRoute] {
        let clean = originalUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return [] }
        var routes = [UpdateDownloadRoute(label: "GitHub 直连", url: clean)]
        if clean.lowercased().hasPrefix("https://github.com/") {
            for (label, prefix) in githubMirrorPrefixes {
                routes.append(UpdateDownloadRoute(label: label, url: "\(prefix)\(clean)"))
            }
        }
        return routes
    }

    nonisolated static func formatDownloadError(_ error: Error?, triedMirrors: Bool = true) -> String {
        let nsErr = error as NSError?
        let rawMsg = error?.localizedDescription ?? ""
        let lower = rawMsg.lowercased()
        let code = nsErr?.code ?? 0

        let isTimeout = code == NSURLErrorTimedOut ||
            lower.contains("timed out") ||
            lower.contains("timeout")
        let isUnreachable = code == NSURLErrorCannotFindHost ||
            code == NSURLErrorCannotConnectToHost ||
            code == NSURLErrorNetworkConnectionLost ||
            code == NSURLErrorNotConnectedToInternet ||
            code == NSURLErrorSecureConnectionFailed ||
            lower.contains("could not connect") ||
            lower.contains("offline")
        let isVerifyError = lower.contains("校验")

        if isVerifyError {
            return "安装包校验未通过，请点击「重试下载」或通过「浏览器下载」手动安装。"
        }
        if isTimeout && triedMirrors {
            return "连接 GitHub 下载超时（直连与加速通道均未响应）。国内网络请检查代理/加速后重试，或点击「浏览器下载」。"
        }
        if isTimeout {
            return "连接 GitHub 下载超时，请检查网络或开启代理后重试，也可点击「浏览器下载」。"
        }
        if isUnreachable {
            return "无法连接 GitHub 下载服务器，请检查网络或开启代理后重试，也可点击「浏览器下载」。"
        }
        if rawMsg.contains("HTTP ") {
            return "\(rawMsg)，请稍后重试或点击「浏览器下载」。"
        }
        return "下载更新失败，请检查网络（国内建议开启代理）后重试，或点击「浏览器下载」。"
    }

    nonisolated static func parseReleaseNoteItems(_ body: String?) -> [String] {
        guard let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return ["常规体验优化与问题修复"]
        }
        let hashRegex = try? NSRegularExpression(pattern: "\\s*\\([0-9a-fA-F]{6,10}\\)\\s*$", options: [])
        let prefixRegex = try? NSRegularExpression(
            pattern: "^(feat|fix|perf|refactor|chore|style|docs|test|build)(\\([^)]+\\))?:\\s*",
            options: [.caseInsensitive]
        )

        var items: [String] = []
        for rawLine in body.components(separatedBy: .newlines) {
            let trimmed = rawLine.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }
            if trimmed.hasPrefix("### 重要提示") || trimmed.hasPrefix("### 校验信息") {
                break
            }
            if trimmed.hasPrefix("#") { continue }
            if trimmed.hasPrefix("- **Android") ||
                trimmed.hasPrefix("- **iOS") ||
                trimmed.hasPrefix("- SHA-256:") ||
                trimmed.hasPrefix("- 签名证书") {
                continue
            }

            var line = trimmed
            for bullet in ["- ", "* ", "• "] {
                if line.hasPrefix(bullet) {
                    line = String(line.dropFirst(bullet.count))
                    break
                }
            }
            if let prefixRegex {
                let range = NSRange(location: 0, length: (line as NSString).length)
                line = prefixRegex.stringByReplacingMatches(in: line, options: [], range: range, withTemplate: "")
            }
            if let hashRegex {
                let range = NSRange(location: 0, length: (line as NSString).length)
                line = hashRegex.stringByReplacingMatches(in: line, options: [], range: range, withTemplate: "")
            }
            line = line
                .replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "`", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !line.isEmpty {
                items.append(line)
            }
        }
        return items.isEmpty ? ["常规体验优化与问题修复"] : items
    }

    nonisolated static func sanitizeReleaseNotes(_ body: String?) -> String {
        parseReleaseNoteItems(body).joined(separator: "\n")
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
            releaseNotes: "发现新版本 \(cleanTag)，点击立即更新即可下载安装。",
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
                    self.messages.send("连接 GitHub 检查更新超时或失败，国内网络建议开启代理后重试")
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
        downloadFlowTask?.cancel()
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

        let routes = Self.buildDownloadRoutes(originalUrl: info.downloadUrl)
        guard !routes.isEmpty else {
            state = .error(info: info, message: "更新包下载地址无效，请通过浏览器下载")
            return
        }

        let userAgent = "LXPure/\(Self.normalizeVersion(currentAppVersion))"
        downloadFlowTask = Task { [weak self] in
            guard let self else { return }
            var lastError: Error?

            for route in routes {
                guard self.activeDownloadToken == token, !Task.isCancelled else { return }
                guard let remoteURL = URL(string: route.url) else { continue }

                self.state = .downloading(
                    info: info,
                    downloadedBytes: 0,
                    totalBytes: info.assetSizeBytes,
                    progress: 0,
                    routeLabel: route.label
                )

                do {
                    let downloadedPartURL = try await self.downloadRoute(
                        remoteURL: remoteURL,
                        routeLabel: route.label,
                        info: info,
                        partURL: partURL,
                        userAgent: userAgent,
                        token: token
                    )
                    guard self.activeDownloadToken == token, !Task.isCancelled else {
                        try? FileManager.default.removeItem(at: downloadedPartURL)
                        return
                    }

                    self.state = .verifying(info)
                    let valid = await Task.detached(priority: .utility) {
                        Self.isValidIpaFile(at: downloadedPartURL, info: info)
                    }.value
                    guard self.activeDownloadToken == token, !Task.isCancelled else { return }

                    if !valid {
                        try? FileManager.default.removeItem(at: downloadedPartURL)
                        lastError = NSError(
                            domain: "LXPureUpdate",
                            code: -2,
                            userInfo: [NSLocalizedDescriptionKey: "安装包校验未通过"]
                        )
                        continue
                    }

                    let fm = FileManager.default
                    if fm.fileExists(atPath: finalURL.path) {
                        try fm.removeItem(at: finalURL)
                    }
                    try fm.moveItem(at: downloadedPartURL, to: finalURL)
                    self.state = .readyToInstall(info: info, localFileURL: finalURL)
                    return
                } catch {
                    try? FileManager.default.removeItem(at: partURL)
                    if (error as NSError).code == NSURLErrorCancelled || Task.isCancelled || self.activeDownloadToken != token {
                        if self.activeDownloadToken == token {
                            self.state = .available(info)
                        }
                        return
                    }
                    lastError = error
                }
            }

            guard self.activeDownloadToken == token else { return }
            self.state = .error(
                info: info,
                message: Self.formatDownloadError(lastError, triedMirrors: routes.count > 1)
            )
        }
    }

    private func downloadRoute(
        remoteURL: URL,
        routeLabel: String,
        info: AppUpdateInfo,
        partURL: URL,
        userAgent: String,
        token: UUID
    ) async throws -> URL {
        defer {
            self.downloadSession?.finishTasksAndInvalidate()
            self.downloadSession = nil
            self.downloadTask = nil
        }
        return try await withCheckedThrowingContinuation { continuation in
            var req = URLRequest(url: remoteURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
            req.setValue(userAgent, forHTTPHeaderField: "User-Agent")

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
                            progress: progress,
                            routeLabel: routeLabel
                        )
                    }
                },
                onComplete: { result in
                    continuation.resume(with: result)
                }
            )

            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 15
            config.timeoutIntervalForResource = 180
            let dlSession = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
            let task = dlSession.downloadTask(with: req)
            self.downloadSession = dlSession
            self.downloadTask = task
            task.resume()
        }
    }

    func cancelDownload() {
        let cur = state
        activeDownloadToken = UUID()
        downloadFlowTask?.cancel()
        downloadFlowTask = nil
        cancelActiveDownloadSession()
        if case .downloading(let info, _, _, _, _) = cur {
            state = .available(info)
        }
    }

    func dismiss() {
        activeDownloadToken = UUID()
        downloadFlowTask?.cancel()
        downloadFlowTask = nil
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
            var apiReq = URLRequest(url: apiURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
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

        // Fallback via direct or mirror release redirect when GitHub API is blocked/rate-limited
        let redirectCandidates = [
            latestReleaseWebUrl,
            "https://ghfast.top/\(latestReleaseWebUrl)",
            "https://gh-proxy.org/\(latestReleaseWebUrl)"
        ]
        for candidate in redirectCandidates {
            guard let webURL = URL(string: candidate) else { continue }
            var webReq = URLRequest(url: webURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
            webReq.httpMethod = "HEAD"
            webReq.setValue("LXPure/\(normalizeVersion(currentVersion))", forHTTPHeaderField: "User-Agent")
            if let (_, resp) = try? await session.data(for: webReq) {
                let locationHeader = (resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Location")
                let candidateUrl = locationHeader ?? resp.url?.absoluteString
                if let tag = extractTagFromReleaseUrl(candidateUrl) {
                    return buildFallbackReleaseInfo(tagName: tag)
                }
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
