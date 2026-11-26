import Combine
import Foundation

struct ResolvedStream: Sendable {
    let url: String
    let quality: AudioQuality
    let resolvedTrack: Track
    let sourceId: String
    let providerName: String
    let switchedPlatform: Bool
    let switchedSource: Bool
    let sourceRevision: String
}

struct SourceUpdateResult: Sendable {
    let sourceName: String
    let oldVersion: String
    let newVersion: String
    let updated: Bool
    let alreadyLatest: Bool
    let errorMessage: String?
}

struct BatchUpdateSummary: Sendable {
    let updatedNames: [String]
    let latestCount: Int
    let failedCount: Int
    let skippedLocalCount: Int

    func formatUserMessage() -> String {
        if !updatedNames.isEmpty {
            let base = "已更新 \(updatedNames.count) 个音源：\(updatedNames.joined(separator: "、"))"
            return failedCount > 0 ? "\(base)（另有 \(failedCount) 个拉取失败）" : base
        }
        if failedCount == 0 && latestCount > 0 {
            if skippedLocalCount > 0 {
                return "全部 \(latestCount) 个在线音源均已是最新版本（已跳过 \(skippedLocalCount) 个本地脚本）"
            }
            return "全部 \(latestCount) 个在线音源均已是最新版本"
        }
        if latestCount > 0 {
            return "\(latestCount) 个已最新，\(failedCount) 个拉取失败"
        }
        if failedCount == 0 && skippedLocalCount > 0 {
            return "当前均为本地导入脚本，未绑定在线更新链接"
        }
        if failedCount == 0 {
            return "暂无可检查更新的音源"
        }
        return "更新检查失败，请检查网络连接后重试"
    }
}

final class UserApiManager: ObservableObject, @unchecked Sendable {
    @MainActor @Published private(set) var sources: [UserApiSource] = []

    private static let scriptUrlTimeoutSec: Double = 5.5
    private static let streamProbeTimeoutSec: Double = 3.0
    private static let resolveTotalBudgetSec: TimeInterval = 18.0
    private static let cooldownDurationSec: TimeInterval = 45.0
    private static let maxScriptDownloadBytes: Int = 2 * 1024 * 1024
    private static let chromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private static let searchablePlatformCodes: Set<String> = [
        MusicSource.wy.code,
        MusicSource.tx.code,
        MusicSource.kw.code,
        MusicSource.kg.code
    ]
    private static let healthProbeQueries = ["海阔天空", "起风了", "晴天"]

    static func sourcePlatformKey(sourceId: String, platformCode: String) -> String {
        "\(sourceId)_\(platformCode.lowercased())"
    }

    static func sourceTrackKey(sourceId: String, trackId: String) -> String {
        "\(sourceId)_\(trackId)"
    }

    static func computeSourceRevision(for source: UserApiSource) -> String {
        let seed = "\(source.version):\(source.script.trimmingCharacters(in: .whitespacesAndNewlines))"
        return String(LxCrypto.md5Hex(seed).prefix(8))
    }

    static func isDeterministicTrackMiss(_ rawMessage: String?) -> Bool {
        let lower = (rawMessage ?? "").lowercased()
        guard !lower.isEmpty else { return false }
        // Never include VIP / 403 here: many sources block FLAC/320k as VIP while 128k remains playable.
        return lower.contains("无版权") ||
            lower.contains("版权") ||
            lower.contains("copyright") ||
            lower.contains("下架") ||
            lower.contains("不存在") ||
            lower.contains("not found") ||
            lower.contains("404")
    }

    private let libraryStore: LibraryStore
    private let session: URLSession
    private let probeSession: URLSession
    private let aggregator: MusicAggregator
    private let networkAdvisor: NetworkQualityAdvisor

    private let lock = NSLock()
    private var runtimes: [String: LxJsRuntime] = [:]
    private var inFlightRuntimes: [String: LxJsRuntime] = [:]
    private var inFlightWaiterCounts: [String: Int] = [:]
    private var runtimeGenerations: [String: UInt64] = [:]
    private var platformCooldowns: [String: Date] = [:]
    private var sourcesSnapshot: [UserApiSource] = []
    private var hasCompletedInitialStoreLoad = false

    private struct ResolveCandidate: Sendable {
        let track: Track
        let source: UserApiSource
        let runtime: LxJsRuntime?
        let quality: AudioQuality
        let switchedPlatform: Bool
    }

    init(
        libraryStore: LibraryStore,
        session: URLSession,
        aggregator: MusicAggregator,
        networkAdvisor: NetworkQualityAdvisor
    ) {
        self.libraryStore = libraryStore
        self.session = session
        self.aggregator = aggregator
        self.networkAdvisor = networkAdvisor

        let probeConfig = URLSessionConfiguration.ephemeral
        probeConfig.timeoutIntervalForRequest = Self.streamProbeTimeoutSec
        probeConfig.timeoutIntervalForResource = Self.streamProbeTimeoutSec + 0.5
        self.probeSession = URLSession(configuration: probeConfig)

        Task {
            await self.reloadAllFromStore()
            self.withLock { self.hasCompletedInitialStoreLoad = true }
        }
    }

    /// Waits briefly on cold launch so tapping Play on a restored track immediately after
    /// opening the app lets custom JS source runtimes finish initializing.
    func awaitInitialLoadIfNeeded(timeoutSec: Double = 4.5, preferredSourceId: String? = nil) async {
        let deadline = Date().addingTimeInterval(timeoutSec)
        while Date() < deadline && !Task.isCancelled {
            let (loaded, list) = withLock { (hasCompletedInitialStoreLoad, sourcesSnapshot) }
            if loaded {
                let enabled = list.filter(\.enabled).sorted { $0.priority < $1.priority }
                if enabled.isEmpty { break }
                let primary = preferredSourceId.flatMap { id in enabled.first(where: { $0.id == id }) } ?? enabled[0]
                let primarySettled = primary.status != .initializing
                let hasReady = enabled.contains { $0.status == .ready }
                let hasInitializing = enabled.contains { $0.status == .initializing }
                if (primarySettled && hasReady) || !hasInitializing {
                    break
                }
            }
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
    }

    /// Validates a downloaded custom source script while rejecting HTML/JSON error pages
    /// (including UTF-8 BOM prefixed responses).
    static func isValidDownloadedScript(_ content: String?) -> Bool {
        guard let content, content.count >= 150 else { return false }
        var cleaned = content
        if cleaned.unicodeScalars.first == "\u{FEFF}" {
            cleaned = String(cleaned.unicodeScalars.dropFirst())
        }
        let trimmed = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("<"), !trimmed.hasPrefix("{") else { return false }
        let lower = trimmed.lowercased()
        return lower.contains("lx") ||
            lower.contains("\\x6c\\x78") ||
            lower.contains("@name") ||
            lower.contains("globalthis")
    }

    /// Distinguishes infrastructure/runtime failures from per-track/per-tier misses
    /// so FLAC -> 320k -> 128k fallback stays intact on healthy sources.
    static func isTransportOrRuntimeFailure(_ rawMessage: String?) -> Bool {
        let lower = (rawMessage ?? "").lowercased()
        guard !lower.isEmpty else { return false }
        return lower.contains("超时") ||
            lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("无法连接") ||
            lower.contains("连接失败") ||
            lower.contains("校验失败") ||
            lower.contains("运行异常") ||
            lower.contains("引擎") ||
            lower.contains("未正确注册") ||
            lower.contains("已卸载") ||
            lower.contains("已停用") ||
            lower.contains("已切换")
    }

    private func bumpGeneration(for id: String) -> UInt64 {
        let (next, oldInFlight): (UInt64, LxJsRuntime?) = withLock {
            let n = (runtimeGenerations[id] ?? 0) &+ 1
            runtimeGenerations[id] = n
            let inflight = inFlightRuntimes.removeValue(forKey: id)
            inFlightWaiterCounts.removeValue(forKey: id)
            return (n, inflight)
        }
        oldInFlight?.destroy()
        return next
    }

    private func currentGeneration(for id: String) -> UInt64 {
        withLock { runtimeGenerations[id] ?? 0 }
    }

    func reloadAllFromStore() async {
        let stored = await MainActor.run { libraryStore.loadSavedUserApis() }
        let initial: [UserApiSource] = stored.map { item in
            var copy = item
            copy.status = item.enabled ? .initializing : .idle
            copy.errorMessage = nil
            copy.latencyMs = nil
            return copy
        }

        await publishSources(initial)

        let validIds = Set(stored.filter(\.enabled).map(\.id))
        let obsoleteRuntimes: [LxJsRuntime] = withLock {
            var toDestroy: [LxJsRuntime] = []
            for key in Array(runtimes.keys) where !validIds.contains(key) {
                runtimeGenerations[key] = (runtimeGenerations[key] ?? 0) &+ 1
                if let inflight = inFlightRuntimes.removeValue(forKey: key) {
                    inFlightWaiterCounts.removeValue(forKey: key)
                    toDestroy.append(inflight)
                }
                if let rt = runtimes.removeValue(forKey: key) {
                    toDestroy.append(rt)
                }
            }
            return toDestroy
        }
        obsoleteRuntimes.forEach { $0.destroy() }

        for source in initial where source.enabled {
            Task { await self.startRuntime(for: source) }
        }
    }

    private func startRuntime(for source: UserApiSource) async {
        let gen = bumpGeneration(for: source.id)
        let old = withLock { runtimes.removeValue(forKey: source.id) }
        old?.destroy()

        guard let liveBefore = currentSources().first(where: { $0.id == source.id }), liveBefore.enabled else {
            return
        }

        await updateSourceState(id: source.id) { item in
            item.status = .initializing
            item.errorMessage = nil
            item.latencyMs = nil
        }

        let runtime = LxJsRuntime(session: session, source: liveBefore)
        let result = await runtime.initialize()

        if result.success {
            let (installed, displaced): (Bool, LxJsRuntime?) = withLock {
                guard runtimeGenerations[source.id] == gen,
                      let live = sourcesSnapshot.first(where: { $0.id == source.id }),
                      live.enabled else {
                    return (false, nil)
                }
                let old = runtimes.updateValue(runtime, forKey: source.id)
                return (true, old)
            }
            guard installed else {
                runtime.destroy()
                return
            }
            if let displaced, displaced !== runtime {
                displaced.destroy()
            }
            clearCooldowns(forSourceId: source.id)
            await updateSourceState(id: source.id, expectedGeneration: gen) { item in
                guard item.enabled else { return }
                item.status = .ready
                item.supportedPlatforms = result.supportedPlatforms
                item.latencyMs = nil
                item.errorMessage = nil
            }
        } else {
            runtime.destroy()
            let stillValid: Bool = withLock {
                runtimeGenerations[source.id] == gen &&
                    (sourcesSnapshot.first(where: { $0.id == source.id })?.enabled == true)
            }
            guard stillValid else { return }
            await updateSourceState(id: source.id, expectedGeneration: gen) { item in
                guard item.enabled else { return }
                item.status = .error
                item.errorMessage = LxJsRuntime.formatFriendlyError(result.errorMessage ?? "初始化失败")
                item.latencyMs = nil
            }
        }
    }

    func importFromUrl(_ urlString: String) async -> Result<UserApiSource, Error> {
        let cleanUrl = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = cleanUrl.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else {
            return .failure(LxRuntimeError.message("请输入有效的 http:// 或 https:// 自定义源链接"))
        }
        guard let script = await downloadText(urlString: cleanUrl) else {
            return .failure(LxRuntimeError.message("无法下载该音源链接"))
        }
        guard Self.isValidDownloadedScript(script) else {
            return .failure(LxRuntimeError.message("下载内容不是有效的自定义源 JavaScript 脚本"))
        }
        return await importRawScript(script, sourceUrl: cleanUrl)
    }

    private func downloadText(urlString: String) async -> String? {
        guard let url = LxJsRuntime.parseLenientURL(urlString) else { return nil }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        req.setValue("LXPure/1.1", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode),
              data.count <= Self.maxScriptDownloadBytes,
              let text = String(data: data, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return text
    }

    func importRawScript(
        _ script: String,
        sourceUrl: String? = nil,
        fallbackName: String = "自定义音源"
    ) async -> Result<UserApiSource, Error> {
        let normalizedScript = LxScriptParser.normalizeScript(script)
        guard !normalizedScript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .failure(LxRuntimeError.message("脚本内容为空"))
        }
        guard !normalizedScript.unicodeScalars.contains("\u{0000}") else {
            return .failure(LxRuntimeError.message("文件包含二进制内容，不是有效的 JS 音源脚本"))
        }
        guard normalizedScript.utf8.count <= 1 * 1024 * 1024 else {
            return .failure(LxRuntimeError.message("脚本体积过大（上限 1 MB），请导入标准 JS 音源文件"))
        }

        let meta = LxScriptParser.parse(normalizedScript, fallbackName: fallbackName)
        let model: UserApiSource = await MainActor.run {
            var existing = libraryStore.loadSavedUserApis()
            let existingIdx = existing.firstIndex { $0.id == meta.id }
            let priority = existingIdx.map { existing[$0].priority }
                ?? ((existing.map(\.priority).max() ?? -1) + 1)
            let mergedSourceUrl = sourceUrl ?? existingIdx.flatMap { existing[$0].sourceUrl }

            let built = UserApiSource(
                id: meta.id,
                name: meta.name,
                description: meta.description,
                version: meta.version,
                author: meta.author,
                homepage: meta.homepage,
                script: normalizedScript,
                sourceUrl: mergedSourceUrl,
                enabled: true,
                priority: priority,
                status: .initializing
            )

            if let idx = existingIdx {
                existing[idx] = built
            } else {
                existing.append(built)
            }
            libraryStore.saveUserApis(existing)
            return built
        }

        // Bump generation before updating snapshot so an earlier finishing runtime for this id cannot clobber the import
        _ = bumpGeneration(for: model.id)

        // Update only the imported source in memory so other healthy runtimes keep playing uninterrupted
        let nextList: [UserApiSource] = withLock {
            var copy = sourcesSnapshot
            if let idx = copy.firstIndex(where: { $0.id == model.id }) {
                copy[idx] = model
            } else {
                copy.append(model)
            }
            copy.sort { $0.priority < $1.priority }
            sourcesSnapshot = copy
            return copy
        }
        await publishCurrentSnapshot()
        _ = nextList
        Task { await self.startRuntime(for: model) }
        return .success(model)
    }

    func toggleEnabled(id: String, enabled: Bool) async {
        _ = bumpGeneration(for: id)
        await MainActor.run {
            var saved = libraryStore.loadSavedUserApis()
            if let idx = saved.firstIndex(where: { $0.id == id }) {
                saved[idx].enabled = enabled
                libraryStore.saveUserApis(saved)
            }
        }

        if !enabled {
            let old = withLock { runtimes.removeValue(forKey: id) }
            old?.destroy()
            clearCooldowns(forSourceId: id)
            await updateSourceState(id: id) { item in
                item.enabled = false
                item.status = .idle
                item.errorMessage = nil
                item.latencyMs = nil
            }
        } else {
            clearCooldowns(forSourceId: id)
            await updateSourceState(id: id) { item in
                item.enabled = true
                item.status = .initializing
                item.errorMessage = nil
                item.latencyMs = nil
            }
            if let target = currentSources().first(where: { $0.id == id }) {
                Task { await self.startRuntime(for: target) }
            }
        }
    }

    func removeSource(id: String) async {
        _ = bumpGeneration(for: id)
        let old = withLock { runtimes.removeValue(forKey: id) }
        old?.destroy()
        clearCooldowns(forSourceId: id)

        await MainActor.run {
            var saved = libraryStore.loadSavedUserApis()
            saved.removeAll { $0.id == id }
            for idx in saved.indices { saved[idx].priority = idx }
            libraryStore.saveUserApis(saved)
        }

        withLock {
            sourcesSnapshot.removeAll { $0.id == id }
            for idx in sourcesSnapshot.indices { sourcesSnapshot[idx].priority = idx }
        }
        await publishCurrentSnapshot()
    }

    func movePriorityUp(id: String) async {
        let reorderedPriorities: [String: Int] = await MainActor.run {
            var saved = libraryStore.loadSavedUserApis()
            guard let idx = saved.firstIndex(where: { $0.id == id }), idx > 0 else { return [:] }
            let item = saved.remove(at: idx)
            saved.insert(item, at: idx - 1)
            var map: [String: Int] = [:]
            for i in saved.indices {
                saved[i].priority = i
                map[saved[i].id] = i
            }
            libraryStore.saveUserApis(saved)
            return map
        }
        guard !reorderedPriorities.isEmpty else { return }
        clearCooldowns(forSourceId: id)

        withLock {
            for i in sourcesSnapshot.indices {
                if let p = reorderedPriorities[sourcesSnapshot[i].id] {
                    sourcesSnapshot[i].priority = p
                }
            }
            sourcesSnapshot.sort { $0.priority < $1.priority }
        }
        await publishCurrentSnapshot()
    }

    func updateSingleSource(id: String) async -> SourceUpdateResult {
        guard let current = currentSources().first(where: { $0.id == id }) else {
            return SourceUpdateResult(
                sourceName: "未知音源", oldVersion: "", newVersion: "",
                updated: false, alreadyLatest: false, errorMessage: "未找到该音源"
            )
        }

        let candidateUrls = SourceUrlUtils.buildUpdateCandidateUrls(current.sourceUrl)
        guard !candidateUrls.isEmpty else {
            return SourceUpdateResult(
                sourceName: current.name, oldVersion: current.version, newVersion: current.version,
                updated: false, alreadyLatest: false, errorMessage: "该本地脚本未绑定在线更新链接"
            )
        }

        var fetchedScript: String?
        var usedUrl: String?
        for url in candidateUrls {
            if Task.isCancelled { break }
            if let content = await downloadText(urlString: url), Self.isValidDownloadedScript(content) {
                fetchedScript = LxScriptParser.normalizeScript(content)
                usedUrl = url
                break
            }
        }

        guard let fetchedScript else {
            return SourceUpdateResult(
                sourceName: current.name, oldVersion: current.version, newVersion: current.version,
                updated: false, alreadyLatest: false, errorMessage: "拉取最新脚本失败，请检查网络后重试"
            )
        }

        let parsed = LxScriptParser.parse(fetchedScript, fallbackName: current.name)
        let newHash = LxCrypto.md5Hex(fetchedScript.trimmingCharacters(in: .whitespacesAndNewlines))

        // Atomically merge onto live persisted record inside a single MainActor.run block
        // so concurrent updateAllSources() saves never clobber one another or resurrect deleted sources.
        enum MergeOutcome: Sendable {
            case deleted
            case alreadyLatest(live: UserApiSource)
            case updated(live: UserApiSource, oldVersion: String)
        }

        let resolvedUsedUrl = usedUrl
        let outcome: MergeOutcome = await MainActor.run {
            var saved = libraryStore.loadSavedUserApis()
            guard let idx = saved.firstIndex(where: { $0.id == id }) else {
                return .deleted
            }
            let live = saved[idx]
            let oldHash = LxCrypto.md5Hex(
                LxScriptParser.normalizeScript(live.script).trimmingCharacters(in: .whitespacesAndNewlines)
            )
            if oldHash == newHash {
                return .alreadyLatest(live: live)
            }
            var merged = live
            merged.name = parsed.name
            merged.description = parsed.description.isEmpty ? live.description : parsed.description
            merged.version = parsed.version
            merged.author = parsed.author
            merged.homepage = parsed.homepage.isEmpty ? live.homepage : parsed.homepage
            merged.script = fetchedScript
            merged.sourceUrl = resolvedUsedUrl ?? live.sourceUrl
            saved[idx] = merged
            libraryStore.saveUserApis(saved)
            return .updated(live: merged, oldVersion: live.version)
        }

        switch outcome {
        case .deleted:
            return SourceUpdateResult(
                sourceName: current.name, oldVersion: current.version, newVersion: current.version,
                updated: false, alreadyLatest: false, errorMessage: "该音源已被删除"
            )

        case .alreadyLatest(let live):
            let liveMem = currentSources().first(where: { $0.id == id })
            if live.enabled && liveMem?.status == .error {
                await startRuntime(for: live)
            }
            return SourceUpdateResult(
                sourceName: live.name, oldVersion: live.version, newVersion: parsed.version,
                updated: false, alreadyLatest: true, errorMessage: nil
            )

        case .updated(let merged, let oldVersion):
            await updateSourceState(id: id) { item in
                item.name = merged.name
                item.description = merged.description
                item.version = merged.version
                item.author = merged.author
                item.homepage = merged.homepage
                item.script = merged.script
                item.sourceUrl = merged.sourceUrl
            }
            if merged.enabled {
                await startRuntime(for: merged)
            }
            return SourceUpdateResult(
                sourceName: merged.name,
                oldVersion: oldVersion,
                newVersion: merged.version,
                updated: true,
                alreadyLatest: false,
                errorMessage: nil
            )
        }
    }

    func updateAllSources() async -> BatchUpdateSummary {
        let list = currentSources()
        let results: [SourceUpdateResult?] = await withTaskGroup(of: SourceUpdateResult?.self) { group in
            for src in list {
                group.addTask {
                    let urls = SourceUrlUtils.buildUpdateCandidateUrls(src.sourceUrl)
                    if urls.isEmpty { return nil }
                    return await self.updateSingleSource(id: src.id)
                }
            }
            var arr: [SourceUpdateResult?] = []
            for await r in group { arr.append(r) }
            return arr
        }

        var updatedNames: [String] = []
        var latestCount = 0
        var failedCount = 0
        var skippedLocal = 0

        for res in results {
            guard let res else {
                skippedLocal += 1
                continue
            }
            if res.updated {
                updatedNames.append("\(res.sourceName) \(LxScriptParser.formatVersionBadge(res.newVersion))")
            } else if res.alreadyLatest {
                latestCount += 1
            } else {
                failedCount += 1
            }
        }

        return BatchUpdateSummary(
            updatedNames: updatedNames,
            latestCount: latestCount,
            failedCount: failedCount,
            skippedLocalCount: skippedLocal
        )
    }

    func testSourceHealth(id: String) async -> Result<Int64, Error> {
        guard let source = currentSources().first(where: { $0.id == id }) else {
            return .failure(LxRuntimeError.message("音源不存在"))
        }
        guard source.enabled else {
            return .failure(LxRuntimeError.message("请先启用该音源后再测速"))
        }

        var activeRuntime = withLock { runtimes[id] }
        if activeRuntime == nil {
            await startRuntime(for: source)
            activeRuntime = withLock { runtimes[id] }
        }
        guard let runtime = activeRuntime else {
            let errMsg = currentSources().first(where: { $0.id == id })?.errorMessage ?? "音源初始化失败"
            return .failure(LxRuntimeError.message(errMsg))
        }
        let runtimeGen = currentGeneration(for: id)

        let currentMeta = currentSources().first(where: { $0.id == id })
        var seenPlatforms = Set<MusicSource>()
        let platformOrder = (currentMeta?.supportedPlatforms.keys.compactMap { code -> MusicSource? in
            let lower = code.lowercased()
            guard Self.searchablePlatformCodes.contains(lower),
                  let parsed = MusicSource(rawValue: lower) else { return nil }
            return seenPlatforms.insert(parsed).inserted ? parsed : nil
        } ?? [])
            .sorted { lhs, rhs in
                let rank: (MusicSource) -> Int = {
                    switch $0 {
                    case .kw: return 4
                    case .kg: return 3
                    case .tx: return 2
                    case .wy: return 1
                    default: return 0
                    }
                }
                return rank(lhs) > rank(rhs)
            }
        guard !platformOrder.isEmpty else {
            let msg = "音源未声明可用音乐平台"
            await updateSourceState(id: id, expectedGeneration: runtimeGen) {
                guard $0.enabled else { return }
                $0.status = .error
                $0.latencyMs = nil
                $0.errorMessage = msg
            }
            return .failure(LxRuntimeError.message(msg))
        }

        var lastFriendlyError: String?
        var testedAnyTrack = false

        for platform in platformOrder {
            if Task.isCancelled { return .failure(CancellationError()) }
            var probeTrack: Track?
            for query in Self.healthProbeQueries {
                if Task.isCancelled { return .failure(CancellationError()) }
                if let found = await aggregator.searchSinglePlatform(source: platform, query: query, page: 1, limit: 3).first {
                    probeTrack = found
                    break
                }
            }
            guard let probeTrack else { continue }
            testedAnyTrack = true

            let start = Date()
            let probeRes = await runtime.requestMusicUrl(track: probeTrack, quality: .q128k, timeoutSeconds: 6.0)
            if Task.isCancelled { return .failure(CancellationError()) }
            let elapsed = max(Int64(Date().timeIntervalSince(start) * 1000.0), 1)

            if case .success(let url) = probeRes, await verifyPlayableAudioUrl(url) {
                if currentGeneration(for: id) == runtimeGen {
                    clearCooldowns(forSourceId: id)
                    await updateSourceState(id: id, expectedGeneration: runtimeGen) {
                        guard $0.enabled else { return }
                        $0.latencyMs = elapsed
                        $0.status = .ready
                        $0.errorMessage = nil
                    }
                }
                return .success(elapsed)
            } else {
                if Task.isCancelled { return .failure(CancellationError()) }
                let rawErr: String
                if case .failure(let err) = probeRes {
                    rawErr = err.localizedDescription
                } else {
                    rawErr = "返回链接不可播放"
                }
                lastFriendlyError = LxJsRuntime.formatFriendlyError(rawErr)
                if currentGeneration(for: id) == runtimeGen {
                    markCooldown(sourceId: id, platformCode: probeTrack.source.code)
                }
            }
        }

        guard !Task.isCancelled else { return .failure(CancellationError()) }
        let finalMsg = !testedAnyTrack
            ? "测试曲目检索失败，请检查网络"
            : (lastFriendlyError ?? "当前各平台测试曲目均未返回可播链接")
        // Keep an initialized runtime .ready so a temporary health-probe miss never bricks playback;
        // surface a soft warning message on the card instead.
        await updateSourceState(id: id, expectedGeneration: runtimeGen) {
            guard $0.enabled else { return }
            $0.status = .ready
            $0.latencyMs = nil
            $0.errorMessage = "测速未通过：\(finalMsg)"
        }
        return .failure(LxRuntimeError.message(finalMsg))
    }

    func resolveStream(
        track: Track,
        preferredQuality: AudioQuality,
        allowCrossPlatformFallback: Bool = true,
        preferredSourceId: String? = nil,
        excludedSourceIds: Set<String> = [],
        excludedTrackIds: Set<String> = [],
        excludedSourcePlatforms: Set<String> = [],
        excludedSourceTracks: Set<String> = []
    ) async -> Result<ResolvedStream, Error> {
        await awaitInitialLoadIfNeeded(preferredSourceId: preferredSourceId)
        if Task.isCancelled { return .failure(CancellationError()) }

        let deadline = Date().addingTimeInterval(Self.resolveTotalBudgetSec)
        let allSources = currentSources()
        let readySources = allSources
            .filter { $0.enabled && $0.status == .ready && !excludedSourceIds.contains($0.id) }
            .sorted { lhs, rhs in
                let lp = (lhs.id == preferredSourceId) ? 0 : 1
                let rp = (rhs.id == preferredSourceId) ? 0 : 1
                if lp != rp { return lp < rp }
                return lhs.priority < rhs.priority
            }

        guard !readySources.isEmpty else {
            let msg: String
            if !excludedSourceIds.isEmpty {
                msg = "所有已启用音源均无法播放《\(track.title)》"
            } else if !allSources.isEmpty {
                msg = "当前无可用自定义音源，请检查音源状态或重新测速启用"
            } else {
                msg = "尚未导入自定义音源，点击前往导入 .js 自定义源"
            }
            return .failure(LxRuntimeError.message(msg))
        }

        let prunedPlatformsBox = PrunedSetBox(initial: excludedSourcePlatforms)
        let prunedTracksBox = PrunedSetBox(initial: excludedSourceTracks)
        let missCounterBox = MissCounterBox()

        var attemptedTrackIds = excludedTrackIds
        let attachedAlts = allowCrossPlatformFallback
            ? track.alternates
                .filter { !excludedTrackIds.contains($0.id) }
                .sorted { MusicAggregator.platformPriority($0.source) > MusicAggregator.platformPriority($1.source) }
            : []

        // Phase A: Healthy (non-cooling) sources — Primary -> Attached Alts -> Dynamic Alts
        if !excludedTrackIds.contains(track.id) {
            attemptedTrackIds.insert(track.id)
            if let resolved = await tryResolveTracks(
                tracks: [track],
                preferredQuality: preferredQuality,
                sources: readySources,
                switchedPlatform: false,
                preferredSourceId: preferredSourceId,
                prunedPlatformsBox: prunedPlatformsBox,
                prunedTracksBox: prunedTracksBox,
                missCounterBox: missCounterBox,
                includeCooling: false,
                deadline: deadline
            ) {
                return .success(resolved)
            }
        }
        if Task.isCancelled { return .failure(CancellationError()) }

        var dynamicCandidates: [Track] = []
        if allowCrossPlatformFallback && Date() < deadline {
            if !attachedAlts.isEmpty {
                attachedAlts.forEach { attemptedTrackIds.insert($0.id) }
                if let resolved = await tryResolveTracks(
                    tracks: attachedAlts,
                    preferredQuality: preferredQuality,
                    sources: readySources,
                    switchedPlatform: true,
                    preferredSourceId: preferredSourceId,
                    prunedPlatformsBox: prunedPlatformsBox,
                    prunedTracksBox: prunedTracksBox,
                    missCounterBox: missCounterBox,
                    includeCooling: false,
                    deadline: deadline
                ) {
                    return .success(resolved)
                }
            }
            if Task.isCancelled { return .failure(CancellationError()) }

            if Date() < deadline {
                let triedCodes = Set(([track] + attachedAlts).map { $0.source.code.lowercased() })
                let searchableSupported = Set(
                    readySources
                        .flatMap { $0.supportedPlatforms.keys }
                        .map { $0.lowercased() }
                        .filter { Self.searchablePlatformCodes.contains($0) }
                )
                let remainingCodes = searchableSupported.subtracting(triedCodes)
                let searchCodes = remainingCodes.isEmpty ? searchableSupported : remainingCodes

                let searchBudget = min(3.5, max(deadline.timeIntervalSinceNow, 0.5))
                let rawDynamicAlts: [Track] = searchCodes.isEmpty ? [] : (
                    await Self.withTimeoutOrNil(seconds: searchBudget) {
                        await self.aggregator.findAlternativeTracks(for: track, allowedPlatformCodes: searchCodes)
                    } ?? []
                )
                if Task.isCancelled { return .failure(CancellationError()) }

                let dynamicAlts = rawDynamicAlts.filter { !attemptedTrackIds.contains($0.id) }
                if !dynamicAlts.isEmpty {
                    var seenPlatforms = Set<MusicSource>()
                    dynamicCandidates = Array(dynamicAlts.filter { seenPlatforms.insert($0.source).inserted }.prefix(3))
                    dynamicCandidates.forEach { attemptedTrackIds.insert($0.id) }
                    if let resolved = await tryResolveTracks(
                        tracks: dynamicCandidates,
                        preferredQuality: preferredQuality,
                        sources: readySources,
                        switchedPlatform: true,
                        preferredSourceId: preferredSourceId,
                        prunedPlatformsBox: prunedPlatformsBox,
                        prunedTracksBox: prunedTracksBox,
                        missCounterBox: missCounterBox,
                        includeCooling: false,
                        deadline: deadline
                    ) {
                        return .success(resolved)
                    }
                }
            }
        }
        if Task.isCancelled { return .failure(CancellationError()) }

        // Phase B: Cooling sources fallback ONLY after healthy primary & cross-platform alternates are exhausted
        if Date() < deadline {
            if !excludedTrackIds.contains(track.id),
               let resolved = await tryResolveTracks(
                   tracks: [track],
                   preferredQuality: preferredQuality,
                   sources: readySources,
                   switchedPlatform: false,
                   preferredSourceId: preferredSourceId,
                   prunedPlatformsBox: prunedPlatformsBox,
                   prunedTracksBox: prunedTracksBox,
                   missCounterBox: missCounterBox,
                   includeCooling: true,
                   deadline: deadline
               ) {
                return .success(resolved)
            }
            if Task.isCancelled { return .failure(CancellationError()) }

            var seenAltIds = Set<String>()
            let allAlts = (attachedAlts + dynamicCandidates).filter { seenAltIds.insert($0.id).inserted }
            if allowCrossPlatformFallback && !allAlts.isEmpty && Date() < deadline,
               let resolved = await tryResolveTracks(
                   tracks: allAlts,
                   preferredQuality: preferredQuality,
                   sources: readySources,
                   switchedPlatform: true,
                   preferredSourceId: preferredSourceId,
                   prunedPlatformsBox: prunedPlatformsBox,
                   prunedTracksBox: prunedTracksBox,
                   missCounterBox: missCounterBox,
                   includeCooling: true,
                   deadline: deadline
               ) {
                return .success(resolved)
            }
        }

        if Task.isCancelled { return .failure(CancellationError()) }
        return .failure(LxRuntimeError.message("所有音源与平台均无法获取《\(track.title)》的可用音频流"))
    }

    private static func withTimeoutOrNil<T: Sendable>(
        seconds: Double,
        operation: @escaping @Sendable () async -> T
    ) async -> T? {
        await withTaskGroup(of: T?.self) { group in
            group.addTask {
                await operation()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(seconds, 0.1) * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private func tryResolveTracks(
        tracks: [Track],
        preferredQuality: AudioQuality,
        sources: [UserApiSource],
        switchedPlatform: Bool,
        preferredSourceId: String?,
        prunedPlatformsBox: PrunedSetBox,
        prunedTracksBox: PrunedSetBox,
        missCounterBox: MissCounterBox,
        includeCooling: Bool,
        deadline: Date
    ) async -> ResolvedStream? {
        let qualityChain = AudioQuality.fallbackChain(preferred: preferredQuality)

        // When user explicitly chose a source, honor it across its full downward quality chain first
        // (bypassing cooldown in Phase A so explicit manual picks always try the chosen source).
        if let preferredSourceId, !preferredSourceId.isEmpty {
            let preferredSources = sources.filter { $0.id == preferredSourceId }
            let otherSources = sources.filter { $0.id != preferredSourceId }
            if !preferredSources.isEmpty {
                if !includeCooling {
                    for quality in qualityChain {
                        if Task.isCancelled || Date() >= deadline { return nil }
                        let candidates = buildTierCandidates(
                            tracks: tracks,
                            quality: quality,
                            sources: preferredSources,
                            switchedPlatform: switchedPlatform,
                            prunedPlatformsBox: prunedPlatformsBox,
                            prunedTracksBox: prunedTracksBox,
                            missCounterBox: missCounterBox,
                            includeCooling: false,
                            ignoreCooldown: true
                        )
                        if candidates.isEmpty { continue }
                        if let winner = await raceFirstValid(
                            candidates: candidates,
                            prunedPlatformsBox: prunedPlatformsBox,
                            prunedTracksBox: prunedTracksBox,
                            missCounterBox: missCounterBox,
                            switchedSourceOnWin: false,
                            deadline: deadline
                        ) {
                            return winner
                        }
                    }
                }
                if otherSources.isEmpty || Task.isCancelled || Date() >= deadline { return nil }
                return await tryResolveQualityTiersOrdered(
                    tracks: tracks,
                    qualityChain: qualityChain,
                    sources: otherSources,
                    switchedPlatform: switchedPlatform,
                    prunedPlatformsBox: prunedPlatformsBox,
                    prunedTracksBox: prunedTracksBox,
                    missCounterBox: missCounterBox,
                    includeCooling: includeCooling,
                    initialSwitched: true,
                    deadline: deadline
                )
            }
        }

        return await tryResolveQualityTiersOrdered(
            tracks: tracks,
            qualityChain: qualityChain,
            sources: sources,
            switchedPlatform: switchedPlatform,
            prunedPlatformsBox: prunedPlatformsBox,
            prunedTracksBox: prunedTracksBox,
            missCounterBox: missCounterBox,
            includeCooling: includeCooling,
            initialSwitched: false,
            deadline: deadline
        )
    }

    private func tryResolveQualityTiersOrdered(
        tracks: [Track],
        qualityChain: [AudioQuality],
        sources: [UserApiSource],
        switchedPlatform: Bool,
        prunedPlatformsBox: PrunedSetBox,
        prunedTracksBox: PrunedSetBox,
        missCounterBox: MissCounterBox,
        includeCooling: Bool,
        initialSwitched: Bool,
        deadline: Date
    ) async -> ResolvedStream? {
        for quality in qualityChain {
            if Task.isCancelled || Date() >= deadline { return nil }
            let candidates = buildTierCandidates(
                tracks: tracks,
                quality: quality,
                sources: sources,
                switchedPlatform: switchedPlatform,
                prunedPlatformsBox: prunedPlatformsBox,
                prunedTracksBox: prunedTracksBox,
                missCounterBox: missCounterBox,
                includeCooling: includeCooling
            )
            if candidates.isEmpty { continue }

            if tracks.count == 1 && candidates.count > 1 {
                let primary = candidates[0]
                let backups = Array(candidates.dropFirst())
                if let winner = await raceFirstValid(
                    candidates: [primary],
                    prunedPlatformsBox: prunedPlatformsBox,
                    prunedTracksBox: prunedTracksBox,
                    missCounterBox: missCounterBox,
                    switchedSourceOnWin: initialSwitched,
                    deadline: deadline
                ) {
                    return winner
                }
                if Task.isCancelled || Date() >= deadline { return nil }
                if let winner = await raceInBalancedWaves(
                    candidates: backups,
                    prunedPlatformsBox: prunedPlatformsBox,
                    prunedTracksBox: prunedTracksBox,
                    missCounterBox: missCounterBox,
                    initialSwitched: true,
                    deadline: deadline
                ) {
                    return winner
                }
            } else if let winner = await raceInBalancedWaves(
                candidates: candidates,
                prunedPlatformsBox: prunedPlatformsBox,
                prunedTracksBox: prunedTracksBox,
                missCounterBox: missCounterBox,
                initialSwitched: initialSwitched,
                deadline: deadline
            ) {
                return winner
            }
        }
        return nil
    }

    private func buildTierCandidates(
        tracks: [Track],
        quality: AudioQuality,
        sources: [UserApiSource],
        switchedPlatform: Bool,
        prunedPlatformsBox: PrunedSetBox,
        prunedTracksBox: PrunedSetBox,
        missCounterBox: MissCounterBox,
        includeCooling: Bool,
        ignoreCooldown: Bool = false
    ) -> [ResolveCandidate] {
        var result: [ResolveCandidate] = []
        for track in tracks {
            let platformCode = track.source.code.lowercased()
            for source in sources {
                guard let supportedQualities = source.supportedPlatforms[platformCode] else { continue }
                let supportsTier = supportedQualities.contains(quality.lxType) ||
                    (supportedQualities.isEmpty && quality == .q128k)
                if !supportsTier { continue }

                let platKey = Self.sourcePlatformKey(sourceId: source.id, platformCode: platformCode)
                if prunedPlatformsBox.contains(platKey) { continue }
                let trackKey = Self.sourceTrackKey(sourceId: source.id, trackId: track.id)
                if prunedTracksBox.contains(trackKey) { continue }
                // Skip redundant intermediate tiers (e.g. 320k after FLAC24 + FLAC both missed),
                // but NEVER skip .q128k fallback which is frequently the only free/playable tier.
                if quality != .q128k && missCounterBox.count(for: trackKey) >= 2 { continue }
                if !ignoreCooldown {
                    let cooling = isInCooldown(key: platKey)
                    if includeCooling != cooling { continue }
                }

                let runtime = withLock { runtimes[source.id] }
                result.append(ResolveCandidate(
                    track: track,
                    source: source,
                    runtime: runtime,
                    quality: quality,
                    switchedPlatform: switchedPlatform
                ))
            }
        }
        return result
    }

    private func raceInBalancedWaves(
        candidates: [ResolveCandidate],
        prunedPlatformsBox: PrunedSetBox,
        prunedTracksBox: PrunedSetBox,
        missCounterBox: MissCounterBox,
        initialSwitched: Bool,
        deadline: Date
    ) async -> ResolvedStream? {
        let waveSize = networkAdvisor.isMeteredNetwork() ? 2 : 3
        let waves = stride(from: 0, to: candidates.count, by: waveSize).map {
            Array(candidates[$0..<min($0 + waveSize, candidates.count)])
        }
        for (waveIdx, wave) in waves.enumerated() {
            if Task.isCancelled || Date() >= deadline { return nil }
            if let winner = await raceFirstValid(
                candidates: wave,
                prunedPlatformsBox: prunedPlatformsBox,
                prunedTracksBox: prunedTracksBox,
                missCounterBox: missCounterBox,
                switchedSourceOnWin: initialSwitched || waveIdx > 0,
                deadline: deadline
            ) {
                return winner
            }
        }
        return nil
    }

    private func raceFirstValid(
        candidates: [ResolveCandidate],
        prunedPlatformsBox: PrunedSetBox,
        prunedTracksBox: PrunedSetBox,
        missCounterBox: MissCounterBox,
        switchedSourceOnWin: Bool,
        deadline: Date
    ) async -> ResolvedStream? {
        let remainingSec = deadline.timeIntervalSinceNow
        guard !candidates.isEmpty, !Task.isCancelled, remainingSec > 0.25 else { return nil }
        return await withTaskGroup(of: ResolvedStream?.self) { group in
            for candidate in candidates {
                group.addTask { [weak self] in
                    guard let self, !Task.isCancelled else { return nil }
                    let platformCode = candidate.track.source.code.lowercased()
                    let platKey = Self.sourcePlatformKey(sourceId: candidate.source.id, platformCode: platformCode)
                    let trackKey = Self.sourceTrackKey(sourceId: candidate.source.id, trackId: candidate.track.id)
                    let start = Date()

                    let activeRuntime: LxJsRuntime?
                    if let existing = candidate.runtime {
                        activeRuntime = existing
                    } else {
                        activeRuntime = await self.ensureRuntimeReady(for: candidate.source)
                    }
                    if Task.isCancelled { return nil }

                    guard let runtime = activeRuntime else {
                        prunedPlatformsBox.insert(platKey)
                        self.markCooldown(sourceId: candidate.source.id, platformCode: platformCode)
                        return nil
                    }

                    let reqTimeout = min(Self.scriptUrlTimeoutSec, max(deadline.timeIntervalSinceNow, 0.5))
                    let res = await runtime.requestMusicUrl(
                        track: candidate.track,
                        quality: candidate.quality,
                        timeoutSeconds: reqTimeout
                    )
                    if Task.isCancelled { return nil }

                    switch res {
                    case .success(let url):
                        let playable = await self.verifyPlayableAudioUrl(url)
                        if Task.isCancelled { return nil }
                        if playable {
                            let elapsed = max(Int64(Date().timeIntervalSince(start) * 1000.0), 1)
                            self.clearCooldown(key: platKey)
                            await self.updateSourceState(id: candidate.source.id) { item in
                                if item.status == .ready {
                                    item.latencyMs = elapsed
                                    item.errorMessage = nil
                                }
                            }
                            return ResolvedStream(
                                url: url,
                                quality: candidate.quality,
                                resolvedTrack: candidate.track,
                                sourceId: candidate.source.id,
                                providerName: candidate.source.name,
                                switchedPlatform: candidate.switchedPlatform,
                                switchedSource: switchedSourceOnWin,
                                sourceRevision: Self.computeSourceRevision(for: candidate.source)
                            )
                        }
                        _ = missCounterBox.increment(trackKey)
                        if candidate.quality == .q128k {
                            prunedTracksBox.insert(trackKey)
                        }
                        return nil

                    case .failure(let error):
                        if error is CancellationError || Task.isCancelled {
                            return nil
                        }
                        let msg = error.localizedDescription
                        if Self.isTransportOrRuntimeFailure(msg) {
                            prunedPlatformsBox.insert(platKey)
                            self.markCooldown(sourceId: candidate.source.id, platformCode: platformCode)
                        } else if Self.isDeterministicTrackMiss(msg) {
                            prunedTracksBox.insert(trackKey)
                        } else {
                            _ = missCounterBox.increment(trackKey)
                            if candidate.quality == .q128k {
                                prunedTracksBox.insert(trackKey)
                            }
                        }
                        return nil
                    }
                }
            }

            for await result in group {
                if let winner = result {
                    group.cancelAll()
                    return winner
                }
            }
            return nil
        }
    }

    private enum ProbeOutcome { case playable, retryWithoutRange, rejected }

    /// Header-only live stream probe using `URLSession.bytes(for:)` so CDNs ignoring `Range`
    /// never download 30-80MB audio files into RAM, with automatic HEAD fallback on 416/501.
    private func verifyPlayableAudioUrl(_ urlString: String) async -> Bool {
        guard !Task.isCancelled, let url = LxJsRuntime.parseLenientURL(urlString) else { return false }
        let rangeOutcome = await executeProbeRequest(url: url, useRange: true)
        if rangeOutcome == .playable { return true }
        if rangeOutcome == .retryWithoutRange {
            return await executeProbeRequest(url: url, useRange: false) == .playable
        }
        return false
    }

    private func executeProbeRequest(url: URL, useRange: Bool) async -> ProbeOutcome {
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.streamProbeTimeoutSec)
        req.httpMethod = useRange ? "GET" : "HEAD"
        req.setValue(Self.chromeUserAgent, forHTTPHeaderField: "User-Agent")
        if useRange {
            req.setValue("bytes=0-127", forHTTPHeaderField: "Range")
        }

        do {
            let response: URLResponse
            if useRange {
                let (bytes, resp) = try await probeSession.bytes(for: req)
                _ = bytes
                response = resp
            } else {
                let (_, resp) = try await probeSession.data(for: req)
                response = resp
            }
            if Task.isCancelled { return .rejected }
            guard let httpResp = response as? HTTPURLResponse else { return .rejected }
            if useRange && (httpResp.statusCode == 416 || httpResp.statusCode == 501) {
                return .retryWithoutRange
            }
            guard httpResp.statusCode == 200 || httpResp.statusCode == 206 else {
                return .rejected
            }
            let contentType = (httpResp.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
            if contentType.contains("text/html") ||
                contentType.contains("application/json") ||
                contentType.contains("application/xml") ||
                contentType.contains("text/xml") ||
                contentType.contains("application/javascript") ||
                contentType.hasPrefix("image/") {
                return .rejected
            }
            if let lenStr = httpResp.value(forHTTPHeaderField: "Content-Length"),
               let len = Int64(lenStr) {
                if len == 0 { return .rejected }
                if httpResp.statusCode == 200 && len < 1024 { return .rejected }
            }
            return .playable
        } catch {
            return .rejected
        }
    }

    func trimIdleRuntimes(keepTopCount: Int = 2) {
        let snapshot = currentSources()
        let initializingIds = Set(snapshot.filter { $0.status == .initializing }.map(\.id))
        let keepIds = Set(
            snapshot
                .filter { $0.enabled && $0.status == .ready }
                .sorted { $0.priority < $1.priority }
                .prefix(max(keepTopCount, 1))
                .map(\.id)
        ).union(initializingIds)

        let trimmed: [LxJsRuntime] = withLock {
            var toDestroy: [LxJsRuntime] = []
            for key in Array(runtimes.keys) where !keepIds.contains(key) {
                if let rt = runtimes[key], !rt.hasInFlightWork {
                    runtimes.removeValue(forKey: key)
                    toDestroy.append(rt)
                }
            }
            for key in Array(inFlightRuntimes.keys) where (inFlightWaiterCounts[key] ?? 0) <= 0 {
                if let rt = inFlightRuntimes.removeValue(forKey: key) {
                    inFlightWaiterCounts.removeValue(forKey: key)
                    toDestroy.append(rt)
                }
            }
            return toDestroy
        }
        trimmed.forEach { $0.destroy() }
    }

    func hasReadySource() -> Bool {
        currentSources().contains { $0.enabled && $0.status == .ready }
    }

    private func ensureRuntimeReady(for source: UserApiSource) async -> LxJsRuntime? {
        if let existing = withLock({ runtimes[source.id] }) {
            return existing
        }
        if Task.isCancelled { return nil }

        // Coalesce concurrent revivals onto the same LxJsRuntime instance (whose initialize()
        // supports multiple independently-cancellable waiters without blocking withTaskGroup).
        let (runtime, gen, countedAsWaiter): (LxJsRuntime?, UInt64, Bool) = withLock {
            if let existing = runtimes[source.id] {
                return (existing, runtimeGenerations[source.id] ?? 0, false)
            }
            guard let liveSource = sourcesSnapshot.first(where: { $0.id == source.id }),
                  liveSource.enabled else {
                return (nil, 0, false)
            }
            let currentGen = runtimeGenerations[source.id] ?? 0
            if let inflight = inFlightRuntimes[source.id] {
                inFlightWaiterCounts[source.id, default: 0] += 1
                return (inflight, currentGen, true)
            }
            let created = LxJsRuntime(session: session, source: liveSource)
            inFlightRuntimes[source.id] = created
            inFlightWaiterCounts[source.id] = 1
            return (created, currentGen, true)
        }

        guard let runtime else { return nil }
        if !countedAsWaiter {
            return runtime
        }

        let initRes = await runtime.initialize(timeoutSeconds: 5.0)
        if Task.isCancelled {
            let orphaned: LxJsRuntime? = withLock {
                guard inFlightRuntimes[source.id] === runtime else { return nil }
                let remaining = max((inFlightWaiterCounts[source.id] ?? 1) - 1, 0)
                if remaining == 0 {
                    inFlightRuntimes.removeValue(forKey: source.id)
                    inFlightWaiterCounts.removeValue(forKey: source.id)
                    return runtime
                } else {
                    inFlightWaiterCounts[source.id] = remaining
                    return nil
                }
            }
            orphaned?.destroy()
            return nil
        }

        let (installed, displaced, shouldDestroySelf): (Bool, LxJsRuntime?, Bool) = withLock {
            if inFlightRuntimes[source.id] === runtime {
                let remaining = max((inFlightWaiterCounts[source.id] ?? 1) - 1, 0)
                if remaining == 0 {
                    inFlightRuntimes.removeValue(forKey: source.id)
                    inFlightWaiterCounts.removeValue(forKey: source.id)
                } else {
                    inFlightWaiterCounts[source.id] = remaining
                }
            }
            if runtimes[source.id] === runtime {
                return (true, nil, false)
            }
            guard initRes.success,
                  (runtimeGenerations[source.id] ?? 0) == gen,
                  let postLive = sourcesSnapshot.first(where: { $0.id == source.id }),
                  postLive.enabled else {
                let stillReferenced = (inFlightRuntimes[source.id] === runtime)
                return (false, nil, !stillReferenced)
            }
            inFlightRuntimes.removeValue(forKey: source.id)
            inFlightWaiterCounts.removeValue(forKey: source.id)
            let old = runtimes.updateValue(runtime, forKey: source.id)
            return (true, old, false)
        }
        if let displaced, displaced !== runtime {
            displaced.destroy()
        }
        if installed {
            return runtime
        } else {
            if shouldDestroySelf {
                runtime.destroy()
            }
            return nil
        }
    }

    private func cooldownKey(sourceId: String, platformCode: String) -> String {
        "\(sourceId)_\(platformCode)"
    }

    private func isInCooldown(key: String) -> Bool {
        withLock {
            guard let until = platformCooldowns[key] else { return false }
            if Date() >= until {
                platformCooldowns.removeValue(forKey: key)
                return false
            }
            return true
        }
    }

    private func markCooldown(sourceId: String, platformCode: String) {
        let key = cooldownKey(sourceId: sourceId, platformCode: platformCode)
        withLock {
            platformCooldowns[key] = Date().addingTimeInterval(Self.cooldownDurationSec)
        }
    }

    private func clearCooldown(key: String) {
        withLock { _ = platformCooldowns.removeValue(forKey: key) }
    }

    func clearCooldowns(forSourceId sourceId: String) {
        let prefix = "\(sourceId)_"
        withLock {
            for key in Array(platformCooldowns.keys) where key.hasPrefix(prefix) {
                platformCooldowns.removeValue(forKey: key)
            }
        }
    }

    private func currentSources() -> [UserApiSource] {
        withLock { sourcesSnapshot }
    }

    private func publishSources(_ list: [UserApiSource]) async {
        withLock { sourcesSnapshot = list }
        await publishCurrentSnapshot()
    }

    private func publishCurrentSnapshot() async {
        await MainActor.run {
            self.sources = self.withLock { self.sourcesSnapshot }
        }
    }

    private func updateSourceState(
        id: String,
        expectedGeneration: UInt64? = nil,
        transform: @Sendable (inout UserApiSource) -> Void
    ) async {
        let updated: Bool = withLock {
            if let expectedGeneration, (runtimeGenerations[id] ?? 0) != expectedGeneration {
                return false
            }
            sourcesSnapshot = sourcesSnapshot.map { item in
                guard item.id == id else { return item }
                var copy = item
                transform(&copy)
                return copy
            }
            return true
        }
        if updated {
            await publishCurrentSnapshot()
        }
    }

    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}

private final class PrunedSetBox: @unchecked Sendable {
    private let lock = NSLock()
    private var items: Set<String>

    init(initial: Set<String> = []) {
        self.items = initial
    }

    func insert(_ key: String) {
        lock.lock()
        items.insert(key)
        lock.unlock()
    }

    func contains(_ key: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return items.contains(key)
    }
}

private final class MissCounterBox: @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]

    func increment(_ key: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        let next = (counts[key] ?? 0) + 1
        counts[key] = next
        return next
    }

    func count(for key: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return counts[key] ?? 0
    }
}
