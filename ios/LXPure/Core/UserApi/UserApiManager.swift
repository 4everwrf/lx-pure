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

    private static let scriptUrlTimeoutSec: Double = 4.5
    private static let streamProbeTimeoutSec: Double = 2.5
    private static let cooldownDurationSec: TimeInterval = 45.0

    private let libraryStore: LibraryStore
    private let session: URLSession
    private let probeSession: URLSession
    private let aggregator: MusicAggregator
    private let networkAdvisor: NetworkQualityAdvisor

    private let lock = NSLock()
    private var runtimes: [String: LxJsRuntime] = [:]
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
    func awaitInitialLoadIfNeeded(timeoutSec: Double = 4.0) async {
        let deadline = Date().addingTimeInterval(timeoutSec)
        while Date() < deadline {
            let (loaded, list) = withLock { (hasCompletedInitialStoreLoad, sourcesSnapshot) }
            if loaded {
                let hasReady = list.contains { $0.enabled && $0.status == .ready }
                let hasInitializing = list.contains { $0.enabled && $0.status == .initializing }
                if hasReady || !hasInitializing {
                    break
                }
            }
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
    }

    /// Validates a downloaded custom source script while rejecting HTML/JSON error pages.
    static func isValidDownloadedScript(_ content: String?) -> Bool {
        guard let content, content.count >= 150 else { return false }
        let trimmed = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("<"), !trimmed.hasPrefix("{") else { return false }
        let lower = content.lowercased()
        return lower.contains("lx") ||
            lower.contains("\\x6c\\x78") ||
            lower.contains("@name") ||
            lower.contains("globalthis")
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
        let old = withLock { runtimes.removeValue(forKey: source.id) }
        old?.destroy()

        await updateSourceState(id: source.id) { item in
            item.status = .initializing
            item.errorMessage = nil
            item.latencyMs = nil
        }

        let runtime = LxJsRuntime(session: session, source: source)
        let result = await runtime.initialize()

        if result.success {
            withLock { runtimes[source.id] = runtime }
            clearCooldowns(forSourceId: source.id)
            await updateSourceState(id: source.id) { item in
                item.status = .ready
                item.supportedPlatforms = result.supportedPlatforms
                item.latencyMs = nil
                item.errorMessage = nil
            }
        } else {
            runtime.destroy()
            await updateSourceState(id: source.id) { item in
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
        guard let url = URL(string: urlString) else { return nil }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        req.setValue("LXPure/1.1", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              let httpResp = resp as? HTTPURLResponse,
              (200..<300).contains(httpResp.statusCode),
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

        let meta = LxScriptParser.parse(normalizedScript, fallbackName: fallbackName)
        var existing = await MainActor.run { libraryStore.loadSavedUserApis() }
        let existingIdx = existing.firstIndex { $0.id == meta.id }
        let priority = existingIdx.map { existing[$0].priority } ?? existing.count
        let mergedSourceUrl = sourceUrl ?? existingIdx.flatMap { existing[$0].sourceUrl }

        let model = UserApiSource(
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
            existing[idx] = model
        } else {
            existing.append(model)
        }
        let toSave = existing
        await MainActor.run { libraryStore.saveUserApis(toSave) }
        await reloadAllFromStore()

        if let live = currentSources().first(where: { $0.id == model.id }) {
            return .success(live)
        }
        return .success(model)
    }

    func toggleEnabled(id: String, enabled: Bool) async {
        var saved = await MainActor.run { libraryStore.loadSavedUserApis() }
        if let idx = saved.firstIndex(where: { $0.id == id }) {
            saved[idx].enabled = enabled
            let toSave = saved
            await MainActor.run { libraryStore.saveUserApis(toSave) }
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
            await updateSourceState(id: id) { item in
                item.enabled = true
            }
            if let target = currentSources().first(where: { $0.id == id }) {
                Task { await self.startRuntime(for: target) }
            }
        }
    }

    func removeSource(id: String) async {
        let old = withLock { runtimes.removeValue(forKey: id) }
        old?.destroy()
        clearCooldowns(forSourceId: id)

        var saved = await MainActor.run { libraryStore.loadSavedUserApis() }
        saved.removeAll { $0.id == id }
        for idx in saved.indices { saved[idx].priority = idx }
        let toSave = saved
        await MainActor.run { libraryStore.saveUserApis(toSave) }

        let updated = currentSources().filter { $0.id != id }
        await publishSources(updated)
    }

    func movePriorityUp(id: String) async {
        var saved = await MainActor.run { libraryStore.loadSavedUserApis() }
        guard let idx = saved.firstIndex(where: { $0.id == id }), idx > 0 else { return }
        let item = saved.remove(at: idx)
        saved.insert(item, at: idx - 1)
        for i in saved.indices { saved[i].priority = i }
        let toSave = saved
        await MainActor.run { libraryStore.saveUserApis(toSave) }

        var live = currentSources()
        if let liveIdx = live.firstIndex(where: { $0.id == id }), liveIdx > 0 {
            let liveItem = live.remove(at: liveIdx)
            live.insert(liveItem, at: liveIdx - 1)
            for i in live.indices { live[i].priority = i }
            await publishSources(live)
        }
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

        let oldHash = LxCrypto.md5Hex(
            LxScriptParser.normalizeScript(current.script).trimmingCharacters(in: .whitespacesAndNewlines)
        )
        let newHash = LxCrypto.md5Hex(fetchedScript.trimmingCharacters(in: .whitespacesAndNewlines))
        let parsed = LxScriptParser.parse(fetchedScript, fallbackName: current.name)

        if oldHash == newHash {
            if current.enabled && current.status == .error {
                await startRuntime(for: current)
            }
            return SourceUpdateResult(
                sourceName: current.name, oldVersion: current.version, newVersion: parsed.version,
                updated: false, alreadyLatest: true, errorMessage: nil
            )
        }

        var updatedModel = current
        updatedModel.name = parsed.name
        updatedModel.description = parsed.description.isEmpty ? current.description : parsed.description
        updatedModel.version = parsed.version
        updatedModel.author = parsed.author
        updatedModel.homepage = parsed.homepage.isEmpty ? current.homepage : parsed.homepage
        updatedModel.script = fetchedScript
        updatedModel.sourceUrl = usedUrl ?? current.sourceUrl
        let finalModel = updatedModel

        var saved = await MainActor.run { libraryStore.loadSavedUserApis() }
        if let idx = saved.firstIndex(where: { $0.id == current.id }) {
            saved[idx] = finalModel
            let toSave = saved
            await MainActor.run { libraryStore.saveUserApis(toSave) }
        }

        await updateSourceState(id: current.id) { item in
            item.name = finalModel.name
            item.description = finalModel.description
            item.version = finalModel.version
            item.author = finalModel.author
            item.homepage = finalModel.homepage
            item.script = finalModel.script
            item.sourceUrl = finalModel.sourceUrl
        }
        if finalModel.enabled {
            await startRuntime(for: finalModel)
        }

        return SourceUpdateResult(
            sourceName: finalModel.name,
            oldVersion: current.version,
            newVersion: finalModel.version,
            updated: true,
            alreadyLatest: false,
            errorMessage: nil
        )
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

        var activeRuntime = withLock { runtimes[id] }
        if activeRuntime == nil {
            await startRuntime(for: source)
            activeRuntime = withLock { runtimes[id] }
        }
        guard let runtime = activeRuntime else {
            let errMsg = currentSources().first(where: { $0.id == id })?.errorMessage ?? "音源初始化失败"
            return .failure(LxRuntimeError.message(errMsg))
        }

        let currentMeta = currentSources().first(where: { $0.id == id })
        let platformOrder = (currentMeta?.supportedPlatforms.keys.map { MusicSource.fromCode($0) } ?? [])
            .sorted { MusicAggregator.platformPriority($0) > MusicAggregator.platformPriority($1) }
        guard !platformOrder.isEmpty else {
            let msg = "音源未声明可用音乐平台"
            await updateSourceState(id: id) {
                $0.status = .error
                $0.latencyMs = nil
                $0.errorMessage = msg
            }
            return .failure(LxRuntimeError.message(msg))
        }

        var probeTrack: Track?
        for platform in platformOrder {
            if let found = await aggregator.searchSinglePlatform(source: platform, query: "晴天", page: 1, limit: 3).first {
                probeTrack = found
                break
            }
        }
        guard let probeTrack else {
            let msg = "测试曲目检索失败，请检查网络"
            await updateSourceState(id: id) {
                $0.status = .error
                $0.latencyMs = nil
                $0.errorMessage = msg
            }
            return .failure(LxRuntimeError.message(msg))
        }

        let start = Date()
        let probeRes = await runtime.requestMusicUrl(track: probeTrack, quality: .q128k, timeoutSeconds: 6.0)
        let elapsed = max(Int64(Date().timeIntervalSince(start) * 1000.0), 1)

        if case .success(let url) = probeRes, await verifyPlayableAudioUrl(url) {
            clearCooldowns(forSourceId: id)
            await updateSourceState(id: id) {
                $0.latencyMs = elapsed
                $0.status = .ready
                $0.errorMessage = nil
            }
            return .success(elapsed)
        } else {
            let rawErr: String
            if case .failure(let err) = probeRes {
                rawErr = err.localizedDescription
            } else {
                rawErr = "返回链接不可播放"
            }
            let friendly = LxJsRuntime.formatFriendlyError(rawErr)
            markCooldown(sourceId: id, platformCode: probeTrack.source.code)
            await updateSourceState(id: id) {
                $0.status = .error
                $0.latencyMs = nil
                $0.errorMessage = "测速失败：\(friendly)"
            }
            return .failure(LxRuntimeError.message(friendly))
        }
    }

    func resolveStream(
        track: Track,
        preferredQuality: AudioQuality,
        allowCrossPlatformFallback: Bool = true,
        preferredSourceId: String? = nil,
        excludedSourceIds: Set<String> = [],
        excludedTrackIds: Set<String> = []
    ) async -> Result<ResolvedStream, Error> {
        await awaitInitialLoadIfNeeded()
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

        let prunedBox = PrunedSetBox()

        // 1. Primary track
        if !excludedTrackIds.contains(track.id) {
            if let resolved = await tryResolveTracksByQualityTiers(
                tracks: [track],
                preferredQuality: preferredQuality,
                sources: readySources,
                switchedPlatform: false,
                preferredSourceId: preferredSourceId,
                prunedBox: prunedBox
            ) {
                return .success(resolved)
            }
        }

        // 2. Cross-platform fallback
        if allowCrossPlatformFallback {
            let attachedAlts = track.alternates
                .filter { !excludedTrackIds.contains($0.id) }
                .sorted { MusicAggregator.platformPriority($0.source) > MusicAggregator.platformPriority($1.source) }
            if !attachedAlts.isEmpty,
               let resolved = await tryResolveTracksByQualityTiers(
                   tracks: attachedAlts,
                   preferredQuality: preferredQuality,
                   sources: readySources,
                   switchedPlatform: true,
                   preferredSourceId: preferredSourceId,
                   prunedBox: prunedBox
               ) {
                return .success(resolved)
            }

            // 3. Dynamic fuzzy search across remaining platforms
            let triedCodes = Set(([track] + attachedAlts).map(\.source.code))
            let allSupported = Set(readySources.flatMap { $0.supportedPlatforms.keys })
            let remainingCodes = allSupported.subtracting(triedCodes)
            let searchCodes = remainingCodes.isEmpty ? allSupported : remainingCodes

            let dynamicAlts = await aggregator.findAlternativeTracks(for: track, allowedPlatformCodes: searchCodes)
                .filter { !excludedTrackIds.contains($0.id) }
            if !dynamicAlts.isEmpty {
                var seenPlatforms = Set<MusicSource>()
                let bestPerPlatform = dynamicAlts.filter { seenPlatforms.insert($0.source).inserted }.prefix(3)
                if let resolved = await tryResolveTracksByQualityTiers(
                    tracks: Array(bestPerPlatform),
                    preferredQuality: preferredQuality,
                    sources: readySources,
                    switchedPlatform: true,
                    preferredSourceId: preferredSourceId,
                    prunedBox: prunedBox
                ) {
                    return .success(resolved)
                }
            }
        }

        return .failure(LxRuntimeError.message("所有音源与平台均无法获取《\(track.title)》的可用音频流"))
    }

    private func tryResolveTracksByQualityTiers(
        tracks: [Track],
        preferredQuality: AudioQuality,
        sources: [UserApiSource],
        switchedPlatform: Bool,
        preferredSourceId: String?,
        prunedBox: PrunedSetBox
    ) async -> ResolvedStream? {
        let qualityChain = AudioQuality.fallbackChain(preferred: preferredQuality)

        for includeCooling in [false, true] {
            for quality in qualityChain {
                let candidates = buildTierCandidates(
                    tracks: tracks,
                    quality: quality,
                    sources: sources,
                    switchedPlatform: switchedPlatform,
                    prunedBox: prunedBox,
                    includeCooling: includeCooling
                )
                if candidates.isEmpty { continue }
                if let winner = await raceTier(
                    candidates: candidates,
                    preferredSourceId: preferredSourceId,
                    prunedBox: prunedBox
                ) {
                    return winner
                }
            }
        }
        return nil
    }

    private func buildTierCandidates(
        tracks: [Track],
        quality: AudioQuality,
        sources: [UserApiSource],
        switchedPlatform: Bool,
        prunedBox: PrunedSetBox,
        includeCooling: Bool
    ) -> [ResolveCandidate] {
        var result: [ResolveCandidate] = []
        for track in tracks {
            let platformCode = track.source.code
            for source in sources {
                guard let supportedQualities = source.supportedPlatforms[platformCode] else { continue }
                let supportsTier = supportedQualities.contains(quality.lxType) ||
                    (supportedQualities.isEmpty && quality == .q128k)
                if !supportsTier { continue }

                let key = cooldownKey(sourceId: source.id, platformCode: platformCode)
                if prunedBox.contains(key) { continue }
                let cooling = isInCooldown(key: key)
                if includeCooling != cooling { continue }

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

    private func raceTier(
        candidates: [ResolveCandidate],
        preferredSourceId: String?,
        prunedBox: PrunedSetBox
    ) async -> ResolvedStream? {
        guard !candidates.isEmpty else { return nil }
        if let preferredSourceId, !preferredSourceId.isEmpty {
            let preferred = candidates.filter { $0.source.id == preferredSourceId }
            let others = candidates.filter { $0.source.id != preferredSourceId }
            if !preferred.isEmpty {
                if let winner = await raceFirstValid(
                    candidates: preferred,
                    prunedBox: prunedBox,
                    switchedSourceOnWin: false
                ) {
                    return winner
                }
                if !others.isEmpty {
                    return await raceInBalancedWaves(
                        candidates: others,
                        prunedBox: prunedBox,
                        initialSwitched: true
                    )
                }
                return nil
            }
        }
        return await raceInBalancedWaves(
            candidates: candidates,
            prunedBox: prunedBox,
            initialSwitched: false
        )
    }

    private func raceInBalancedWaves(
        candidates: [ResolveCandidate],
        prunedBox: PrunedSetBox,
        initialSwitched: Bool
    ) async -> ResolvedStream? {
        let waveSize = networkAdvisor.isMeteredNetwork() ? 2 : 3
        let waves = stride(from: 0, to: candidates.count, by: waveSize).map {
            Array(candidates[$0..<min($0 + waveSize, candidates.count)])
        }
        for (waveIdx, wave) in waves.enumerated() {
            if let winner = await raceFirstValid(
                candidates: wave,
                prunedBox: prunedBox,
                switchedSourceOnWin: initialSwitched || waveIdx > 0
            ) {
                return winner
            }
        }
        return nil
    }

    private func raceFirstValid(
        candidates: [ResolveCandidate],
        prunedBox: PrunedSetBox,
        switchedSourceOnWin: Bool
    ) async -> ResolvedStream? {
        guard !candidates.isEmpty else { return nil }
        return await withTaskGroup(of: ResolvedStream?.self) { group in
            for candidate in candidates {
                group.addTask { [weak self] in
                    guard let self else { return nil }
                    let platformCode = candidate.track.source.code
                    let key = self.cooldownKey(sourceId: candidate.source.id, platformCode: platformCode)
                    let start = Date()

                    let activeRuntime: LxJsRuntime?
                    if let existing = candidate.runtime {
                        activeRuntime = existing
                    } else {
                        activeRuntime = await self.ensureRuntimeReady(for: candidate.source)
                    }
                    guard let runtime = activeRuntime else {
                        prunedBox.insert(key)
                        self.markCooldown(sourceId: candidate.source.id, platformCode: platformCode)
                        return nil
                    }

                    let res = await runtime.requestMusicUrl(
                        track: candidate.track,
                        quality: candidate.quality,
                        timeoutSeconds: Self.scriptUrlTimeoutSec
                    )
                    if Task.isCancelled { return nil }

                    if case .success(let url) = res, await self.verifyPlayableAudioUrl(url) {
                        if Task.isCancelled { return nil }
                        let elapsed = max(Int64(Date().timeIntervalSince(start) * 1000.0), 1)
                        self.clearCooldown(key: key)
                        await self.updateSourceState(id: candidate.source.id) { item in
                            if item.status == .ready {
                                item.latencyMs = elapsed
                            }
                        }
                        return ResolvedStream(
                            url: url,
                            quality: candidate.quality,
                            resolvedTrack: candidate.track,
                            sourceId: candidate.source.id,
                            providerName: candidate.source.name,
                            switchedPlatform: candidate.switchedPlatform,
                            switchedSource: switchedSourceOnWin
                        )
                    }

                    prunedBox.insert(key)
                    self.markCooldown(sourceId: candidate.source.id, platformCode: platformCode)
                    return nil
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

    private func verifyPlayableAudioUrl(_ urlString: String) async -> Bool {
        guard let url = URL(string: urlString) else { return false }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Self.streamProbeTimeoutSec)
        req.httpMethod = "GET"
        req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", forHTTPHeaderField: "User-Agent")
        req.setValue("bytes=0-127", forHTTPHeaderField: "Range")

        do {
            let (_, response) = try await probeSession.data(for: req)
            guard let httpResp = response as? HTTPURLResponse,
                  httpResp.statusCode == 200 || httpResp.statusCode == 206 else {
                return false
            }
            let contentType = (httpResp.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
            if contentType.contains("text/html") ||
                contentType.contains("application/json") ||
                contentType.contains("text/plain") {
                return false
            }
            if let lenStr = httpResp.value(forHTTPHeaderField: "Content-Length"),
               let len = Int64(lenStr), len == 0 {
                return false
            }
            return true
        } catch {
            return false
        }
    }

    func trimIdleRuntimes(keepTopCount: Int = 2) {
        let keepIds = Set(
            currentSources()
                .filter { $0.enabled && $0.status == .ready }
                .sorted { $0.priority < $1.priority }
                .prefix(max(keepTopCount, 1))
                .map(\.id)
        )
        let trimmed: [LxJsRuntime] = withLock {
            var toDestroy: [LxJsRuntime] = []
            for key in Array(runtimes.keys) where !keepIds.contains(key) {
                if let rt = runtimes.removeValue(forKey: key) {
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
        let runtime = LxJsRuntime(session: session, source: source)
        let initRes = await runtime.initialize(timeoutSeconds: 5.0)
        if initRes.success {
            withLock { runtimes[source.id] = runtime }
            return runtime
        } else {
            runtime.destroy()
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

    private func clearCooldowns(forSourceId sourceId: String) {
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
        await MainActor.run {
            self.sources = list
        }
    }

    private func updateSourceState(id: String, transform: @Sendable (inout UserApiSource) -> Void) async {
        let updated: [UserApiSource] = withLock {
            sourcesSnapshot = sourcesSnapshot.map { item in
                guard item.id == id else { return item }
                var copy = item
                transform(&copy)
                return copy
            }
            return sourcesSnapshot
        }
        await MainActor.run {
            self.sources = updated
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
    private var items = Set<String>()

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
