import AVFoundation
import Combine
import Foundation
import MediaPlayer
import XCTest
@testable import LXPure

private struct MockHttpStub: Sendable {
    let statusCode: Int
    let headers: [String: String]
    let body: Data
    let delaySeconds: TimeInterval
    let error: NSError?

    init(
        statusCode: Int = 200,
        headers: [String: String] = ["Content-Type": "application/json"],
        body: Data = Data(),
        delaySeconds: TimeInterval = 0,
        error: NSError? = nil
    ) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.delaySeconds = delaySeconds
        self.error = error
    }
}

private func readRequestBodyText(_ req: URLRequest) -> String {
    if let data = req.httpBody {
        return String(data: data, encoding: .utf8) ?? ""
    }
    guard let stream = req.httpBodyStream else { return "" }
    stream.open()
    defer { stream.close() }
    var data = Data()
    var buffer = [UInt8](repeating: 0, count: 4096)
    while stream.hasBytesAvailable {
        let count = stream.read(&buffer, maxLength: buffer.count)
        if count > 0 {
            data.append(buffer, count: count)
        } else {
            break
        }
    }
    return String(data: data, encoding: .utf8) ?? ""
}

private final class SendableStringLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []

    func append(_ value: String) {
        lock.lock()
        items.append(value)
        lock.unlock()
    }

    var values: [String] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

private final class MockURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var requestHandler: (@Sendable (URLRequest) -> MockHttpStub)?
    private static var requestedURLsList: [String] = []
    private static var cancelledURLsList: [String] = []

    private let instanceLock = NSLock()
    private var cancelled = false
    private var scheduledWork: DispatchWorkItem?

    static func configure(handler: @escaping @Sendable (URLRequest) -> MockHttpStub) {
        lock.lock()
        requestHandler = handler
        requestedURLsList.removeAll()
        cancelledURLsList.removeAll()
        lock.unlock()
    }

    static func reset() {
        lock.lock()
        requestHandler = nil
        requestedURLsList.removeAll()
        cancelledURLsList.removeAll()
        lock.unlock()
    }

    static var requestedURLs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return requestedURLsList
    }

    static var cancelledURLs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return cancelledURLsList
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let urlStr = request.url?.absoluteString ?? ""
        Self.lock.lock()
        Self.requestedURLsList.append(urlStr)
        let handler = Self.requestHandler
        Self.lock.unlock()

        let stub = handler?(request) ?? MockHttpStub(statusCode: 404, body: Data())
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.instanceLock.lock()
            let isCancelled = self.cancelled
            self.scheduledWork = nil
            self.instanceLock.unlock()
            guard !isCancelled else { return }

            if let err = stub.error {
                self.client?.urlProtocol(self, didFailWithError: err)
                return
            }
            guard let url = self.request.url,
                  let resp = HTTPURLResponse(
                      url: url,
                      statusCode: stub.statusCode,
                      httpVersion: "HTTP/1.1",
                      headerFields: stub.headers
                  ) else {
                self.client?.urlProtocol(self, didFailWithError: URLError(.badURL))
                return
            }
            self.client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            if !stub.body.isEmpty {
                self.client?.urlProtocol(self, didLoad: stub.body)
            }
            self.client?.urlProtocolDidFinishLoading(self)
        }

        instanceLock.lock()
        if cancelled {
            instanceLock.unlock()
            return
        }
        scheduledWork = work
        instanceLock.unlock()

        if stub.delaySeconds > 0 {
            DispatchQueue.global(qos: .userInitiated).asyncAfter(
                deadline: .now() + stub.delaySeconds,
                execute: work
            )
        } else {
            DispatchQueue.global(qos: .userInitiated).async(execute: work)
        }
    }

    override func stopLoading() {
        let urlStr = request.url?.absoluteString ?? ""
        instanceLock.lock()
        cancelled = true
        let work = scheduledWork
        scheduledWork = nil
        instanceLock.unlock()
        work?.cancel()

        Self.lock.lock()
        Self.cancelledURLsList.append(urlStr)
        Self.lock.unlock()
    }
}

final class PlayerAndRacingTests: XCTestCase {
    private var tempDirs: [URL] = []
    private var userDefaultsSuites: [String] = []

    override func tearDown() {
        MockURLProtocol.reset()
        for suite in userDefaultsSuites {
            UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        }
        userDefaultsSuites.removeAll()
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
        super.tearDown()
    }

    private func makeTestEnvironment(
        isConnected: Bool = true,
        isMetered: Bool = false,
        autoSkipDelayNs: UInt64 = 25_000_000
    ) async throws -> (
        tempDir: URL,
        session: URLSession,
        advisor: NetworkQualityAdvisor,
        settingsStore: SettingsStore,
        libraryStore: LibraryStore,
        audioCache: AudioCacheManager,
        userApi: UserApiManager,
        player: PlayerController
    ) {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("LXPurePlayerTests_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        tempDirs.append(tempDir)

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        cfg.timeoutIntervalForRequest = 5
        cfg.timeoutIntervalForResource = 8
        let session = URLSession(configuration: cfg)

        let suiteName = "com.lxpure.tests.\(UUID().uuidString)"
        userDefaultsSuites.append(suiteName)
        let defaults = UserDefaults(suiteName: suiteName)!

        let advisor = NetworkQualityAdvisor(
            startMonitoring: false,
            initialConnected: isConnected,
            initialMetered: isMetered
        )
        let settingsStore = await MainActor.run { SettingsStore(defaults: defaults) }
        let libraryStore = await MainActor.run {
            LibraryStore(baseDirectory: tempDir.appendingPathComponent("lib", isDirectory: true))
        }
        let sessionStore = PlaybackSessionStore(baseDirectory: tempDir.appendingPathComponent("session", isDirectory: true))
        let audioCache = AudioCacheManager(
            session: session,
            cacheDirectory: tempDir.appendingPathComponent("cache", isDirectory: true)
        )
        let aggregator = MusicAggregator(session: session)
        let userApi = UserApiManager(
            libraryStore: libraryStore,
            session: session,
            aggregator: aggregator,
            networkAdvisor: advisor
        )
        let downloadManager = DownloadManager(
            libraryStore: libraryStore,
            session: session,
            userApiManager: userApi
        )
        await userApi.awaitInitialLoadIfNeeded(timeoutSec: 2.0)
        let player = await MainActor.run {
            PlayerController(
                networkAdvisor: advisor,
                audioCacheManager: audioCache,
                settingsStore: settingsStore,
                libraryStore: libraryStore,
                playbackSessionStore: sessionStore,
                userApiManager: userApi,
                musicAggregator: aggregator,
                downloadManager: downloadManager,
                autoSkipDelayNs: autoSkipDelayNs
            )
        }
        return (tempDir, session, advisor, settingsStore, libraryStore, audioCache, userApi, player)
    }

    private func makeTrack(_ index: Int, source: MusicSource = .wy) -> Track {
        Track(
            id: "\(source.code)_t\(index)",
            title: "Track \(index)",
            artist: "Artist \(index)",
            source: source,
            songmid: "\(1000 + index)",
            durationMs: 180_000,
            qualities: [QualityInfo(type: "128k"), QualityInfo(type: "320k")]
        )
    }

    private func makeHttpScript(
        name: String,
        version: String = "1.0.0",
        requestUrlExpr: String
    ) -> String {
        """
        /**
         * @name \(name)
         * @version \(version)
         * @author TestSuite
         */
        const { EVENT_NAMES, on, send, request } = globalThis.lx
        on(EVENT_NAMES.request, ({ action, info }) => {
          if (action !== 'musicUrl') return Promise.reject(new Error('unsupported'))
          return new Promise((resolve, reject) => {
            request(\(requestUrlExpr), { method: 'get', timeout: 5000 }, (err, resp, body) => {
              if (err) return reject(err)
              if (resp && resp.statusCode === 200 && body && body.url) {
                return resolve(body.url)
              }
              reject(new Error((body && body.error) || 'failed'))
            })
          })
        })
        send(EVENT_NAMES.inited, {
          status: true,
          sources: {
            wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] },
            tx: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] },
          }
        })
        """
    }

    private func makeFailingScript(name: String) -> String {
        """
        /**
         * @name \(name)
         * @version 1.0.0
         * @author TestSuite
         */
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, () => Promise.reject(new Error('failed')))
        send(EVENT_NAMES.inited, {
          status: true,
          sources: {
            wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] }
          }
        })
        """
    }

    private func waitUntil(
        timeoutSeconds: TimeInterval = 5.0,
        pollIntervalNs: UInt64 = 15_000_000,
        condition: @Sendable () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if condition() {
                return true
            }
            try? await Task.sleep(nanoseconds: pollIntervalNs)
        }
        return condition()
    }

    @MainActor
    private func waitUntilOnMain(
        timeoutSeconds: TimeInterval = 5.0,
        pollIntervalNs: UInt64 = 15_000_000,
        condition: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if condition() {
                return true
            }
            try? await Task.sleep(nanoseconds: pollIntervalNs)
        }
        return condition()
    }

    private func importAndWaitReady(
        _ script: String,
        into userApi: UserApiManager
    ) async throws -> UserApiSource {
        let res = await userApi.importRawScript(script)
        let source = try res.get()
        let sourceId = source.id
        let becameReady = await waitUntil(timeoutSeconds: 8.0) {
            userApi.readySourcesSnapshot().contains { $0.id == sourceId }
        }
        XCTAssertTrue(becameReady, "Source \(source.name) failed to become ready")
        return source
    }

    // MARK: - R2: Direct Previous Track Navigation

    @MainActor
    func testDirectPreviousNavigationAtUnderAndOverFiveSecondsAllModesAndRemoteCommand() async throws {
        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 404, body: Data())
        }
        let env = try await makeTestEnvironment(isConnected: false, autoSkipDelayNs: 60_000_000_000)
        let player = env.player
        defer { player.clearQueue() }
        let tracks = [makeTrack(0), makeTrack(1), makeTrack(2)]

        // 1. Sequential mode: previous at < 5s (2_000ms) and >= 5s (10_000ms) both jump directly
        player.playTracks(tracks, startIndex: 2)
        XCTAssertEqual(player.state.repeatMode, .listLoop)
        player.cycleRepeatMode() // -> .sequential
        XCTAssertEqual(player.state.repeatMode, .sequential)

        player.seek(to: 2_000)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 1)

        player.seek(to: 10_000)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 0)

        // Boundary at index 0 in sequential still restarts with message
        var receivedMessages: [String] = []
        let bag = player.events.sink { receivedMessages.append($0.message) }
        defer { bag.cancel() }

        player.seek(to: 12_000)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 0)
        XCTAssertTrue(receivedMessages.contains("已是第一首，已重新播放"))

        // 2. ListLoop mode: index 0 wraps to last track even when positionMs >= 5s
        player.cycleRepeatMode() // -> .shuffle
        player.cycleRepeatMode() // -> .singleLoop
        player.cycleRepeatMode() // -> .listLoop
        XCTAssertEqual(player.state.repeatMode, .listLoop)
        player.selectQueueIndex(0)
        player.seek(to: 14_000)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 2)

        // 3. SingleLoop mode: previous at >= 5s still switches to previous track & wraps 0 -> 2
        player.cycleRepeatMode() // -> .sequential
        player.cycleRepeatMode() // -> .shuffle
        player.cycleRepeatMode() // -> .singleLoop
        XCTAssertEqual(player.state.repeatMode, .singleLoop)
        player.selectQueueIndex(1)
        player.seek(to: 9_500)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 0)
        player.seek(to: 11_000)
        player.previous()
        XCTAssertEqual(player.state.currentIndex, 2)

        // 4. Shuffle mode + MPRemoteCommandCenter previousTrackCommand path at >= 5s
        player.cycleRepeatMode() // -> .listLoop
        player.cycleRepeatMode() // -> .sequential
        player.cycleRepeatMode() // -> .shuffle
        XCTAssertEqual(player.state.repeatMode, .shuffle)
        player.selectQueueIndex(0)
        player.next()
        let secondShuffleIdx = player.state.currentIndex
        XCTAssertNotEqual(secondShuffleIdx, 0)

        XCTAssertTrue(MPRemoteCommandCenter.shared().previousTrackCommand.isEnabled)
        player.seek(to: 16_000)
        player.handleRemotePreviousTrackCommand()
        XCTAssertEqual(player.state.currentIndex, 0)
    }

    // MARK: - R3: Auto-Skip Across All 4 Modes & Stop Boundaries

    @MainActor
    func testAutoSkipOnAllSourcesFailingAcrossAllFourModesAndStopBoundaries() async throws {
        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 404, body: Data())
        }
        let env = try await makeTestEnvironment(autoSkipDelayNs: 20_000_000)
        let player = env.player
        defer { player.clearQueue() }
        _ = try await importAndWaitReady(makeFailingScript(name: "AlwaysFail"), into: env.userApi)

        var events: [PlayerUiEvent] = []
        let bag = player.events.sink { events.append($0) }
        defer { bag.cancel() }

        let tracks3 = [makeTrack(0), makeTrack(1), makeTrack(2)]

        // 1. Sequential: 0 -> 1 -> 2 -> stops cleanly at index 2
        player.cycleRepeatMode() // .listLoop -> .sequential
        XCTAssertEqual(player.state.repeatMode, .sequential)
        player.playTracks(tracks3, startIndex: 0)

        let seqStopped = await waitUntilOnMain(timeoutSeconds: 10.0) {
            events.contains { $0.message == "《Track 2》暂无可用音源，已停止播放" }
        }
        XCTAssertTrue(seqStopped)
        XCTAssertEqual(player.state.currentIndex, 2)
        XCTAssertFalse(player.state.isPlaying)
        XCTAssertFalse(player.state.isBuffering)
        XCTAssertTrue(events.contains { $0.message == "《Track 0》暂无可用音源，已自动跳至下一首" })
        XCTAssertTrue(events.contains { $0.message == "《Track 1》暂无可用音源，已自动跳至下一首" })

        // 2. SingleLoop: 1 -> 2 -> 0 -> stops cleanly without infinite loop
        events.removeAll()
        player.cycleRepeatMode() // .shuffle
        player.cycleRepeatMode() // .singleLoop
        XCTAssertEqual(player.state.repeatMode, .singleLoop)
        player.playTracks(tracks3, startIndex: 1)

        let singleLoopStopped = await waitUntilOnMain(timeoutSeconds: 10.0) {
            events.contains { $0.message == "《Track 0》暂无可用音源，已停止播放" }
        }
        XCTAssertTrue(singleLoopStopped)
        XCTAssertEqual(player.state.currentIndex, 0)
        XCTAssertTrue(events.contains { $0.message == "《Track 1》暂无可用音源，已自动跳至下一首" })
        XCTAssertTrue(events.contains { $0.message == "《Track 2》暂无可用音源，已自动跳至下一首" })

        // 3. ListLoop with 2 tracks: 0 -> 1 -> stops cleanly (does not revisit 0)
        events.removeAll()
        player.cycleRepeatMode() // .listLoop
        XCTAssertEqual(player.state.repeatMode, .listLoop)
        player.playTracks([makeTrack(0), makeTrack(1)], startIndex: 0)

        let listLoopStopped = await waitUntilOnMain(timeoutSeconds: 10.0) {
            events.contains { $0.message == "《Track 1》暂无可用音源，已停止播放" }
        }
        XCTAssertTrue(listLoopStopped)
        XCTAssertEqual(player.state.currentIndex, 1)
        XCTAssertEqual(events.filter { $0.message.contains("已自动跳至下一首") }.count, 1)

        // 4. Shuffle with 3 tracks: visits all 3 and stops cleanly
        events.removeAll()
        player.cycleRepeatMode() // .sequential
        player.cycleRepeatMode() // .shuffle
        XCTAssertEqual(player.state.repeatMode, .shuffle)
        player.playTracks(tracks3, startIndex: 0)

        let shuffleStopped = await waitUntilOnMain(timeoutSeconds: 10.0) {
            events.contains { $0.message.hasSuffix("暂无可用音源，已停止播放") }
        }
        XCTAssertTrue(shuffleStopped)
        XCTAssertEqual(events.filter { $0.message.contains("已自动跳至下一首") }.count, 2)

        // 5. Single-track queue: stops immediately without skipping
        events.removeAll()
        player.playTracks([makeTrack(9)], startIndex: 0)
        let singleTrackStopped = await waitUntilOnMain(timeoutSeconds: 6.0) {
            events.contains { $0.message == "《Track 9》暂无可用音源，已停止播放" }
        }
        XCTAssertTrue(singleTrackStopped)
        XCTAssertEqual(events.filter { $0.message.contains("已自动跳至下一首") }.count, 0)
    }

    @MainActor
    func testAutoSkipOfflineFastFailAndNoReadySourcePrompt() async throws {
        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 404, body: Data())
        }

        // 1. Offline mode preserves A3 fast-fail (no auto-skip)
        let offlineEnv = try await makeTestEnvironment(isConnected: false, autoSkipDelayNs: 20_000_000)
        defer { offlineEnv.player.clearQueue() }
        _ = try await importAndWaitReady(makeFailingScript(name: "OfflineFail"), into: offlineEnv.userApi)
        var offlineEvents: [PlayerUiEvent] = []
        let offlineBag = offlineEnv.player.events.sink { offlineEvents.append($0) }
        defer { offlineBag.cancel() }

        offlineEnv.player.playTracks([makeTrack(0), makeTrack(1)], startIndex: 0)
        let gotOfflineFailure = await waitUntilOnMain(timeoutSeconds: 6.0) {
            !offlineEvents.isEmpty && !offlineEnv.player.state.isBuffering
        }
        XCTAssertTrue(gotOfflineFailure)
        try? await Task.sleep(nanoseconds: 80_000_000)
        XCTAssertEqual(offlineEnv.player.state.currentIndex, 0)
        XCTAssertFalse(offlineEvents.contains { $0.message.contains("已自动跳至下一首") })

        // 2. No ready sources emits openSourceManager action prompt and stops cleanly
        let noSourceEnv = try await makeTestEnvironment(isConnected: true, autoSkipDelayNs: 20_000_000)
        defer { noSourceEnv.player.clearQueue() }
        await noSourceEnv.userApi.awaitInitialLoadIfNeeded(timeoutSec: 1.0)
        var noSourceEvents: [PlayerUiEvent] = []
        let noSourceBag = noSourceEnv.player.events.sink { noSourceEvents.append($0) }
        defer { noSourceBag.cancel() }

        noSourceEnv.player.playTracks([makeTrack(0), makeTrack(1)], startIndex: 0)
        let gotImportPrompt = await waitUntilOnMain(timeoutSeconds: 6.0) {
            noSourceEvents.contains { $0.openSourceManager && $0.actionLabel == "导入音源" }
        }
        XCTAssertTrue(gotImportPrompt)
        XCTAssertEqual(noSourceEnv.player.state.currentIndex, 0)
    }

    @MainActor
    func testAutoSkipAfterExhaustedRuntimePlaybackRetries() async throws {
        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 404, body: Data())
        }
        let env = try await makeTestEnvironment(autoSkipDelayNs: 20_000_000)
        let player = env.player
        defer { player.clearQueue() }

        let track0 = makeTrack(0)
        let track1 = makeTrack(1)
        let fakeSource = try await importAndWaitReady(
            makeHttpScript(name: "RuntimeSource", requestUrlExpr: "'https://mock.runtime.test/resolve'"),
            into: env.userApi
        )
        let rev = UserApiManager.computeSourceRevision(for: fakeSource)
        let cacheKey = AudioCacheManager.cacheKey(
            trackId: track0.id,
            resolvedTrackId: track0.id,
            sourceId: fakeSource.id,
            sourceRevision: rev,
            quality: .q320k
        )
        let cacheDir = env.tempDir.appendingPathComponent("cache", isDirectory: true)
        try Data(repeating: 0xAA, count: 8_192).write(to: cacheDir.appendingPathComponent("\(cacheKey).mp3"))

        var events: [PlayerUiEvent] = []
        let bag = player.events.sink { events.append($0) }
        defer { bag.cancel() }

        player.playTracks([track0, track1], startIndex: 0)
        let loadedTrack0 = await waitUntilOnMain(timeoutSeconds: 6.0) {
            player.state.activeQualityBadge == "HQ · 缓存"
        }
        XCTAssertTrue(loadedTrack0)

        // Trigger a runtime playback error; recovery retries online, fails when all sources reject,
        // and transitions cleanly through handleResolutionFailure to auto-skip onto track 1.
        let dummyErr = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotOpenFile)
        player.simulateRuntimeErrorForTesting(error: dummyErr)

        let autoSkippedToTrack1 = await waitUntilOnMain(timeoutSeconds: 10.0) {
            player.state.currentIndex == 1 &&
                events.contains { $0.message == "《Track 0》暂无可用音源，已自动跳至下一首" }
        }
        XCTAssertTrue(autoSkippedToTrack1)
    }

    // MARK: - R4: Multi-Source Racing, Per-Request Cancellation & Pre-Resolution Cache

    func testMultiSourceRacingSelectsFasterLowerPrioritySourceCancelsSlowAndFallsBack() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/slow-api") {
                let body = Data(#"{"url":"https://mock.racing.test/slow.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 2.8)
            }
            if url.contains("/fast-api") {
                let body = Data(#"{"url":"https://mock.racing.test/fast.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 0.02)
            }
            if url.contains("/fast.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x11, count: 128)
                )
            }
            if url.contains("/slow.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x22, count: 128)
                )
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let env = try await makeTestEnvironment()
        let slowSource = try await importAndWaitReady(
            makeHttpScript(name: "SlowPriority0", requestUrlExpr: "'https://mock.racing.test/slow-api'"),
            into: env.userApi
        )
        let fastSource = try await importAndWaitReady(
            makeHttpScript(name: "FastPriority1", requestUrlExpr: "'https://mock.racing.test/fast-api'"),
            into: env.userApi
        )
        XCTAssertLessThan(slowSource.priority, fastSource.priority)

        let track = makeTrack(10)
        let started = Date()
        let result = await env.userApi.resolveStream(
            track: track,
            preferredQuality: .q320k,
            allowCrossPlatformFallback: false
        )
        let elapsed = Date().timeIntervalSince(started)

        let resolved = try result.get()
        XCTAssertEqual(resolved.sourceId, fastSource.id)
        XCTAssertEqual(resolved.url, "https://mock.racing.test/fast.mp3")
        XCTAssertFalse(resolved.switchedSource)
        XCTAssertLessThan(elapsed, 1.5, "Wave-1 racing must not block behind slow priority-0 source")

        let cancelledSlow = await waitUntil(timeoutSeconds: 3.0) {
            MockURLProtocol.cancelledURLs.contains { $0.contains("/slow-api") }
        }
        XCTAssertTrue(cancelledSlow, "Losing priority-0 HTTP task must be cancelled immediately")

        // Fallback test: fast source returns an unplayable 404 URL, slower source succeeds
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/fast-api") {
                let body = Data(#"{"url":"https://mock.racing.test/broken.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 0.01)
            }
            if url.contains("/slow-api") {
                let body = Data(#"{"url":"https://mock.racing.test/fallback-ok.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 0.08)
            }
            if url.contains("/fallback-ok.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x33, count: 128)
                )
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let fallbackResult = await env.userApi.resolveStream(
            track: track,
            preferredQuality: .q320k,
            allowCrossPlatformFallback: false
        )
        let fallbackResolved = try fallbackResult.get()
        XCTAssertEqual(fallbackResolved.sourceId, slowSource.id)
        XCTAssertEqual(fallbackResolved.url, "https://mock.racing.test/fallback-ok.mp3")
    }

    func testLxJsRuntimeCancelsChildHttpTasksWhenOtherRequestsPending() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("id=wy_slow") {
                let body = Data(#"{"url":"https://mock.js.test/slow.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 3.0)
            }
            if url.contains("id=wy_keep") {
                let body = Data(#"{"url":"https://mock.js.test/keep.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body, delaySeconds: 0.30)
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)

        let script = makeHttpScript(
            name: "ConcurrentJs",
            requestUrlExpr: "'https://mock.js.test/resolve?id=' + info.musicInfo.id"
        )
        let source = UserApiSource(
            id: "js_concurrent_test",
            name: "ConcurrentJs",
            description: "",
            version: "1.0.0",
            author: "TestSuite",
            homepage: "",
            script: script,
            sourceUrl: nil,
            enabled: true,
            priority: 0,
            status: .ready
        )
        let runtime = LxJsRuntime(session: session, source: source)
        defer { runtime.destroy() }

        let initRes = await runtime.initialize(timeoutSeconds: 5.0)
        XCTAssertTrue(initRes.success)

        let slowTrack = Track(id: "wy_slow", title: "Slow", artist: "A", source: .wy, songmid: "1")
        let keepTrack = Track(id: "wy_keep", title: "Keep", artist: "B", source: .wy, songmid: "2")

        let slowTask = Task {
            await runtime.requestMusicUrl(track: slowTrack, quality: .q320k, timeoutSeconds: 5.0)
        }
        let keepTask = Task {
            await runtime.requestMusicUrl(track: keepTrack, quality: .q320k, timeoutSeconds: 5.0)
        }

        let bothStarted = await waitUntil(timeoutSeconds: 5.0) {
            let urls = MockURLProtocol.requestedURLs
            return urls.contains { $0.contains("id=wy_slow") } && urls.contains { $0.contains("id=wy_keep") }
        }
        XCTAssertTrue(bothStarted)

        // Cancel slowTask WHILE keepTask is still pending (!pendingActions.isEmpty)
        slowTask.cancel()
        let slowRes = await slowTask.value
        if case .success = slowRes {
            XCTFail("Expected slowTask to fail/cancel")
        }

        let slowHttpCancelled = await waitUntil(timeoutSeconds: 3.0) {
            MockURLProtocol.cancelledURLs.contains { $0.contains("id=wy_slow") }
        }
        XCTAssertTrue(slowHttpCancelled, "Cancelled musicUrl must cancel its child URLSessionDataTask even while keepTask is pending")

        let keepRes = await keepTask.value
        let keepUrl = try keepRes.get()
        XCTAssertEqual(keepUrl, "https://mock.js.test/keep.mp3")
    }

    @MainActor
    func testPreResolutionCacheHitAndSourceRevisionIsolation() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/v1/resolve") {
                let body = Data(#"{"url":"https://mock.cache.test/v1/stream.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            if url.contains("/v1/stream.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x55, count: 128)
                )
            }
            return MockHttpStub(statusCode: 500, body: Data())
        }

        let env = try await makeTestEnvironment()
        let scriptV1 = makeHttpScript(name: "CacheSource", version: "1.0.0", requestUrlExpr: "'https://mock.cache.test/v1/resolve'")
        let sourceV1 = try await importAndWaitReady(scriptV1, into: env.userApi)
        let rev1 = UserApiManager.computeSourceRevision(for: sourceV1)

        let track = makeTrack(42)
        let keyV1 = AudioCacheManager.cacheKey(
            trackId: track.id,
            resolvedTrackId: track.id,
            sourceId: sourceV1.id,
            sourceRevision: rev1,
            quality: .q320k
        )
        let cacheDir = env.tempDir.appendingPathComponent("cache", isDirectory: true)
        try Data(repeating: 0x55, count: 8_192).write(to: cacheDir.appendingPathComponent("\(keyV1).mp3"))

        // With valid v1 cache, pre-resolution cache hit succeeds without invoking JS or stream probing
        // (unrelated background cover/lyric metadata fetches via MusicAggregator's shared session are ignored)
        env.player.playTracks([track], startIndex: 0)
        let hitCache = await waitUntilOnMain(timeoutSeconds: 6.0) {
            env.player.state.activeQualityBadge == "HQ · 缓存"
        }
        XCTAssertTrue(hitCache)
        XCTAssertEqual(env.player.state.activeProviderName, sourceV1.name)
        XCTAssertEqual(env.player.state.activeStreamSourceId, sourceV1.id)
        try? await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertFalse(
            MockURLProtocol.requestedURLs.contains { $0.contains("mock.cache.test") },
            "Pre-resolution cache hit must skip JS & HTTP probe"
        )
        env.player.clearQueue()

        // Updating the source script changes sourceRevision -> stale v1 cache MUST NOT be reused
        let scriptV2 = makeHttpScript(name: "CacheSource", version: "2.0.0", requestUrlExpr: "'https://mock.cache.test/v2/resolve'")
        let sourceV2 = try await importAndWaitReady(scriptV2, into: env.userApi)
        let rev2 = UserApiManager.computeSourceRevision(for: sourceV2)
        XCTAssertNotEqual(rev1, rev2)

        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 500, body: Data())
        }
        env.player.playTracks([track], startIndex: 0)
        let hitNetworkInstead = await waitUntil(timeoutSeconds: 6.0) {
            MockURLProtocol.requestedURLs.contains { $0.contains("https://mock.cache.test/v2") }
        }
        XCTAssertTrue(hitNetworkInstead, "After source revision bump, PlayerController must not read stale v1 cache")
        XCTAssertNotEqual(env.player.state.activeQualityBadge, "HQ · 缓存")
        env.player.clearQueue()
    }

    func testColdStartAwaitInitialLoadCapsPrimaryWaitWhenBackupReady() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/backup-ok") {
                let body = Data(#"{"url":"https://mock.coldstart.test/ok.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            if url.contains("/ok.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x44, count: 128)
                )
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let env = try await makeTestEnvironment()
        let slowInitScript = """
        /**
         * @name SlowInitPrimary
         * @version 1.0.0
         * @author TestSuite
         */
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, () => Promise.reject(new Error('slow')))
        setTimeout(() => {
          send(EVENT_NAMES.inited, {
            status: true,
            sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] } }
          })
        }, 3000)
        """
        let fastInitScript = makeHttpScript(
            name: "FastInitBackup",
            requestUrlExpr: "'https://mock.coldstart.test/backup-ok'"
        )

        // Import slow primary first (priority 0) without waiting for it to settle, then fast backup (priority 1)
        let slowSource = try await env.userApi.importRawScript(slowInitScript).get()
        let fastSource = try await importAndWaitReady(fastInitScript, into: env.userApi)

        let started = Date()
        let res = await env.userApi.resolveStream(
            track: makeTrack(77),
            preferredQuality: .q320k,
            allowCrossPlatformFallback: false
        )
        let elapsed = Date().timeIntervalSince(started)
        await env.userApi.toggleEnabled(id: slowSource.id, enabled: false)
        let resolved = try res.get()
        XCTAssertEqual(resolved.sourceId, fastSource.id)
        XCTAssertLessThan(elapsed, 1.8, "Cold-start wait must cap primary grace once a backup source is ready")
    }

    func testAwaitInitialLoadWaitsForPreferredSourceEvenWhenBackupReady() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/pref-ok") {
                let body = Data(#"{"url":"https://mock.pref.test/preferred.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            if url.contains("/backup-ok") {
                let body = Data(#"{"url":"https://mock.pref.test/backup.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            if url.contains("/preferred.mp3") || url.contains("/backup.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x55, count: 128)
                )
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let env = try await makeTestEnvironment()
        let fastBackupScript = makeHttpScript(
            name: "FastBackup",
            requestUrlExpr: "'https://mock.pref.test/backup-ok'"
        )
        let slowPreferredScript = """
        /**
         * @name SlowPreferred
         * @version 1.0.0
         * @author TestSuite
         */
        const { EVENT_NAMES, on, send, request } = globalThis.lx
        on(EVENT_NAMES.request, ({ action }) => {
          if (action !== 'musicUrl') return Promise.reject(new Error('unsupported'))
          return new Promise((resolve, reject) => {
            request('https://mock.pref.test/pref-ok', { method: 'get', timeout: 5000 }, (err, resp, body) => {
              if (err) return reject(err)
              if (resp && resp.statusCode === 200 && body && body.url) return resolve(body.url)
              reject(new Error('failed'))
            })
          })
        })
        setTimeout(() => {
          send(EVENT_NAMES.inited, {
            status: true,
            sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] } }
          })
        }, 1000)
        """

        let fastBackup = try await importAndWaitReady(fastBackupScript, into: env.userApi)
        let slowPreferred = try await env.userApi.importRawScript(slowPreferredScript).get()

        let res = await env.userApi.resolveStream(
            track: makeTrack(88),
            preferredQuality: .q320k,
            allowCrossPlatformFallback: false,
            preferredSourceId: slowPreferred.id
        )
        await env.userApi.toggleEnabled(id: slowPreferred.id, enabled: false)
        await env.userApi.toggleEnabled(id: fastBackup.id, enabled: false)

        let resolved = try res.get()
        XCTAssertEqual(resolved.sourceId, slowPreferred.id)
        XCTAssertEqual(resolved.url, "https://mock.pref.test/preferred.mp3")
        XCTAssertFalse(resolved.switchedSource)
    }

    @MainActor
    func testQueueEditAndCycleRepeatModeCancelPendingAutoSkipCountdown() async throws {
        MockURLProtocol.configure { _ in
            MockHttpStub(statusCode: 404, body: Data())
        }
        let env = try await makeTestEnvironment(autoSkipDelayNs: 500_000_000)
        let player = env.player
        defer { player.clearQueue() }
        _ = try await importAndWaitReady(makeFailingScript(name: "AlwaysFail"), into: env.userApi)

        var events: [PlayerUiEvent] = []
        let bag = player.events.sink { events.append($0) }
        defer { bag.cancel() }

        // 1. cycleRepeatMode cancels pending 500ms auto-skip countdown
        player.playTracks([makeTrack(0), makeTrack(1), makeTrack(2)], startIndex: 0)
        let enteredCountdownA = await waitUntilOnMain(timeoutSeconds: 8.0, pollIntervalNs: 10_000_000) {
            !player.state.isBuffering &&
                events.contains { $0.message == "《Track 0》暂无可用音源，已自动跳至下一首" }
        }
        XCTAssertTrue(enteredCountdownA)
        player.cycleRepeatMode()
        try? await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertEqual(player.state.currentIndex, 0)
        XCTAssertEqual(player.state.repeatMode, .sequential)

        // 2. playNext cancels pending 500ms auto-skip countdown
        events.removeAll()
        player.playTracks([makeTrack(0), makeTrack(1), makeTrack(2)], startIndex: 0)
        let enteredCountdownB = await waitUntilOnMain(timeoutSeconds: 8.0, pollIntervalNs: 10_000_000) {
            !player.state.isBuffering &&
                events.contains { $0.message == "《Track 0》暂无可用音源，已自动跳至下一首" }
        }
        XCTAssertTrue(enteredCountdownB)
        player.playNext(makeTrack(2))
        try? await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertEqual(player.state.currentIndex, 0)
        XCTAssertEqual(player.state.queue[1].id, makeTrack(2).id)

        // 3. removeFromQueue(at:) on non-current index cancels pending 500ms auto-skip countdown
        events.removeAll()
        player.playTracks([makeTrack(0), makeTrack(1), makeTrack(2)], startIndex: 0)
        let enteredCountdownC = await waitUntilOnMain(timeoutSeconds: 8.0, pollIntervalNs: 10_000_000) {
            !player.state.isBuffering &&
                events.contains { $0.message == "《Track 0》暂无可用音源，已自动跳至下一首" }
        }
        XCTAssertTrue(enteredCountdownC)
        player.removeFromQueue(at: 2)
        try? await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertEqual(player.state.currentIndex, 0)
        XCTAssertEqual(player.state.queue.count, 2)
    }

    func testLxJsRuntimeCancelsParentTimersWithoutAffectingOtherRequests() async throws {
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/keep") {
                let body = Data(#"{"url":"https://mock.timer.test/keep.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            if url.contains("/should-not-fire") {
                let body = Data(#"{"url":"https://mock.timer.test/never.mp3"}"#.utf8)
                return MockHttpStub(statusCode: 200, body: body)
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)

        let script = """
        /**
         * @name TimerCleanupJs
         * @version 1.0.0
         * @author TestSuite
         */
        const { EVENT_NAMES, on, send, request } = globalThis.lx
        on(EVENT_NAMES.request, ({ action, info }) => {
          if (action !== 'musicUrl') return Promise.reject(new Error('unsupported'))
          const target = info.musicInfo.id === 'wy_timer_cancel'
            ? 'https://mock.timer.test/should-not-fire'
            : 'https://mock.timer.test/keep'
          const delayMs = info.musicInfo.id === 'wy_timer_cancel' ? 250 : 80
          return new Promise((resolve, reject) => {
            setTimeout(() => {
              request(target, { method: 'get', timeout: 5000 }, (err, resp, body) => {
                if (err) return reject(err)
                if (resp && resp.statusCode === 200 && body && body.url) return resolve(body.url)
                reject(new Error('failed'))
              })
            }, delayMs)
          })
        })
        send(EVENT_NAMES.inited, {
          status: true,
          sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] } }
        })
        """
        let source = UserApiSource(
            id: "js_timer_cleanup_test",
            name: "TimerCleanupJs",
            description: "",
            version: "1.0.0",
            author: "TestSuite",
            homepage: "",
            script: script,
            sourceUrl: nil,
            enabled: true,
            priority: 0,
            status: .ready
        )
        let runtime = LxJsRuntime(session: session, source: source)
        defer { runtime.destroy() }

        let initRes = await runtime.initialize(timeoutSeconds: 5.0)
        XCTAssertTrue(initRes.success)

        let cancelTrack = Track(id: "wy_timer_cancel", title: "Cancel", artist: "A", source: .wy, songmid: "1")
        let keepTrack = Track(id: "wy_timer_keep", title: "Keep", artist: "B", source: .wy, songmid: "2")

        let cancelTask = Task {
            await runtime.requestMusicUrl(track: cancelTrack, quality: .q320k, timeoutSeconds: 5.0)
        }
        let keepTask = Task {
            await runtime.requestMusicUrl(track: keepTrack, quality: .q320k, timeoutSeconds: 5.0)
        }

        try? await Task.sleep(nanoseconds: 40_000_000)
        cancelTask.cancel()
        let cancelRes = await cancelTask.value
        if case .success = cancelRes {
            XCTFail("Expected cancelled requestMusicUrl task to fail")
        }

        let keepRes = await keepTask.value
        let keepUrl = try keepRes.get()
        XCTAssertEqual(keepUrl, "https://mock.timer.test/keep.mp3")

        try? await Task.sleep(nanoseconds: 350_000_000)
        XCTAssertFalse(
            MockURLProtocol.requestedURLs.contains { $0.contains("should-not-fire") },
            "Pending JS timer for cancelled parent request must be cancelled and never fire"
        )
    }

    func testNetEasePlaylistAndLeaderboardUseEapiAndSurviveLinuxForward502() async {
        let playlistJson = """
        {
          "code": 200,
          "playlist": {
            "id": 19723756,
            "name": "飙升榜",
            "coverImgUrl": "http://p1.music.126.net/cover.jpg",
            "playCount": 1234567,
            "description": "每日飙升",
            "trackCount": 1,
            "creator": { "nickname": "网易云音乐" },
            "tracks": [
              {
                "id": 1001,
                "name": "飙升测试曲",
                "ar": [{ "name": "歌手甲" }],
                "al": { "id": 2001, "name": "专辑A", "picUrl": "http://p2.music.126.net/song.jpg" },
                "dt": 210000,
                "l": { "size": 3200000 },
                "h": { "size": 8400000 }
              }
            ],
            "trackIds": [{ "id": 1001 }]
          },
          "privileges": [
            { "id": 1001, "maxbr": 320000, "maxBrLevel": "exhigh" }
          ]
        }
        """

        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("linux/forward") {
                return MockHttpStub(statusCode: 502, body: Data("502 Bad Gateway".utf8))
            }
            if url.contains("/eapi/v6/playlist/detail") {
                return MockHttpStub(statusCode: 200, body: Data(playlistJson.utf8))
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)
        let aggregator = MusicAggregator(session: session)

        let board = aggregator.getLeaderboards(source: .wy)[0]
        let result = await aggregator.getLeaderboardDetail(board: board)

        switch result {
        case .success(let detail):
            XCTAssertEqual(detail.playlist.id, "19723756")
            XCTAssertEqual(detail.playlist.name, "飙升榜")
            XCTAssertEqual(detail.playlist.coverUrl, "https://p1.music.126.net/cover.jpg")
            XCTAssertEqual(detail.tracks.count, 1)
            XCTAssertEqual(detail.tracks[0].id, "wy_1001")
            XCTAssertEqual(detail.tracks[0].coverUrl, "https://p2.music.126.net/song.jpg")
        case .failure(let error):
            XCTFail("Expected eapi/v6 leaderboard load to succeed, got error: \(error)")
        }

        let requested = MockURLProtocol.requestedURLs
        XCTAssertTrue(requested.contains { $0.contains("/eapi/v6/playlist/detail") })
        XCTAssertFalse(requested.contains { $0.contains("linux/forward") })
    }

    func testNetEasePlaylistDetailFallsBackToEapiV3AndSongDetailHydration() async {
        let v3PlaylistJson = """
        {
          "code": 200,
          "playlist": {
            "id": 18428485896,
            "name": "回退测试歌单",
            "coverImgUrl": "http://p1.music.126.net/pl.jpg",
            "playCount": 5000,
            "description": "v3 fallback",
            "trackCount": 2,
            "creator": { "nickname": "测试用户" },
            "tracks": [
              {
                "id": 1001,
                "name": "直出曲目",
                "ar": [{ "name": "歌手1" }],
                "al": { "id": 2001, "name": "专辑1", "picUrl": "http://p2.music.126.net/a1.jpg" },
                "dt": 180000
              }
            ],
            "trackIds": [{ "id": 1001 }, { "id": 1002 }]
          },
          "privileges": [
            { "id": 1001, "maxbr": 320000, "maxBrLevel": "exhigh" }
          ]
        }
        """
        let eapiSongDetailJson = """
        {
          "code": 200,
          "songs": [
            {
              "id": 1002,
              "name": "补全曲目",
              "ar": [{ "name": "歌手2" }],
              "al": { "id": 2002, "name": "专辑2", "picUrl": "http://p2.music.126.net/a2.jpg" },
              "dt": 200000
            }
          ],
          "privileges": [
            { "id": 1002, "maxbr": 999000, "maxBrLevel": "lossless" }
          ]
        }
        """

        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("linux/forward") {
                return MockHttpStub(statusCode: 502, body: Data("502 Bad Gateway".utf8))
            }
            if url.contains("/eapi/v6/playlist/detail") {
                return MockHttpStub(statusCode: 502, body: Data("502".utf8))
            }
            if url.contains("/eapi/v3/playlist/detail") {
                return MockHttpStub(statusCode: 200, body: Data(v3PlaylistJson.utf8))
            }
            if url.contains("/weapi/v3/song/detail") {
                return MockHttpStub(statusCode: 502, body: Data("502".utf8))
            }
            if url.contains("/eapi/v3/song/detail") {
                return MockHttpStub(statusCode: 200, body: Data(eapiSongDetailJson.utf8))
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)
        let aggregator = MusicAggregator(session: session)

        let successResult = await aggregator.getPlaylistDetail(source: .wy, idOrUrl: "18428485896")
        switch successResult {
        case .success(let detail):
            XCTAssertEqual(detail.tracks.map(\.songmid), ["1001", "1002"])
            XCTAssertEqual(detail.playlist.trackCount, 2)
        case .failure(let error):
            XCTFail("Expected v3 playlist + eapi song hydration fallback to succeed, got \(error)")
        }

        let requested = MockURLProtocol.requestedURLs
        XCTAssertTrue(requested.contains { $0.contains("/eapi/v6/playlist/detail") })
        XCTAssertTrue(requested.contains { $0.contains("/eapi/v3/playlist/detail") })
        XCTAssertTrue(requested.contains { $0.contains("/weapi/v3/song/detail") })
        XCTAssertTrue(requested.contains { $0.contains("/eapi/v3/song/detail") })
        XCTAssertFalse(requested.contains { $0.contains("linux/forward") })

        // Verify clean failure when both v6 (non-200 code) and v3 (502) fail
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("/eapi/v6/playlist/detail") {
                return MockHttpStub(statusCode: 200, body: Data(#"{"code":500}"#.utf8))
            }
            return MockHttpStub(statusCode: 502, body: Data())
        }
        let failResult = await aggregator.getPlaylistDetail(source: .wy, idOrUrl: "18428485896")
        if case .success = failResult {
            XCTFail("Expected failure when both eapi v6 and v3 fail")
        }
    }

    @MainActor
    func testDiscoverStatePreservesSelectedPlatformAndCacheAcrossBackNavigation() async {
        let wyHotJson = """
        {
          "code": 200,
          "playlists": [
            {
              "id": "wy_p1",
              "name": "网易热歌",
              "coverImgUrl": "http://p1.music.126.net/wy.jpg",
              "playCount": 10000,
              "description": "",
              "trackCount": 10,
              "creator": { "nickname": "WY" }
            }
          ]
        }
        """
        let txHotJson = """
        {
          "playlist": {
            "data": {
              "v_playlist": [
                {
                  "tid": "tx_p1",
                  "title": "QQ热歌",
                  "cover_url_medium": "http://y.qq.com/tx.jpg",
                  "access_num": 50000,
                  "desc": "",
                  "creator_info": { "nick": "TX" },
                  "song_ids": [1, 2]
                }
              ]
            }
          }
        }
        """
        let kwHotJson = """
        {
          "data": {
            "data": [
              {
                "id": "kw_p1",
                "name": "酷我热歌",
                "img": "http://img.kuwo.cn/kw.jpg",
                "uname": "KW",
                "listencnt": 20000,
                "desc": "",
                "total": 15
              }
            ]
          }
        }
        """
        let sodaHotJson = """
        {
          "inner_block": [
            {
              "resources": [
                {
                  "entity": {
                    "playlist": {
                      "id": "soda_p1",
                      "title": "汽水热歌",
                      "url_cover": {
                        "uri": "ies-music/cover1.jpeg",
                        "template_prefix": "tplv-b829550vbb",
                        "urls": ["https://p3-luna.douyinpic.com/img/"]
                      },
                      "owner": { "public_name": "汽水官方" },
                      "stats": { "count_collected": 30000 },
                      "resource_cnt": { "track_cnt": 25 }
                    }
                  }
                }
              ]
            }
          ]
        }
        """

        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("music.163.com/weapi/playlist/list") {
                return MockHttpStub(statusCode: 200, body: Data(wyHotJson.utf8))
            }
            if url.contains("u.y.qq.com/cgi-bin/musicu.fcg") {
                return MockHttpStub(statusCode: 200, body: Data(txHotJson.utf8))
            }
            if url.contains("wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList") {
                return MockHttpStub(statusCode: 200, body: Data(kwHotJson.utf8))
            }
            if url.contains("api.qishui.com/luna/pc/discover/mix") {
                return MockHttpStub(statusCode: 200, body: Data(sodaHotJson.utf8))
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        XCTAssertEqual(DiscoverState.supportedSources, [.wy, .tx, .kw, .soda])

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)
        let aggregator = MusicAggregator(session: session)
        let state = DiscoverState()

        XCTAssertEqual(state.selectedSource, .wy)
        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["wy_p1"])
        XCTAssertFalse(state.isLoading)

        // Switch to QQ Music (.tx), load, then simulate pop-back re-entry (force: false)
        state.selectSource(.tx)
        XCTAssertEqual(state.selectedSource, .tx)
        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["tx_p1"])
        let qqRequestsBeforePop = MockURLProtocol.requestedURLs.filter { $0.contains("u.y.qq.com") }.count

        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: false)
        XCTAssertEqual(state.selectedSource, .tx)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["tx_p1"])
        let qqRequestsAfterPop = MockURLProtocol.requestedURLs.filter { $0.contains("u.y.qq.com") }.count
        XCTAssertEqual(qqRequestsAfterPop, qqRequestsBeforePop, "Pop-back re-entry must reuse cached QQ playlists without duplicate HTTP requests")

        // Switch to Kuwo (.kw), load, simulate pop-back re-entry (force: false)
        state.selectSource(.kw)
        XCTAssertEqual(state.selectedSource, .kw)
        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["kw_p1"])
        let kwRequestsBeforePop = MockURLProtocol.requestedURLs.filter { $0.contains("wapi.kuwo.cn") }.count

        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: false)
        XCTAssertEqual(state.selectedSource, .kw)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["kw_p1"])
        let kwRequestsAfterPop = MockURLProtocol.requestedURLs.filter { $0.contains("wapi.kuwo.cn") }.count
        XCTAssertEqual(kwRequestsAfterPop, kwRequestsBeforePop, "Pop-back re-entry must reuse cached Kuwo playlists without duplicate HTTP requests")

        // Switch to Qishui (.soda), load, simulate pop-back re-entry (force: false) & force refresh
        state.selectSource(.soda)
        XCTAssertEqual(state.selectedSource, .soda)
        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["soda_p1"])
        let sodaRequestsBeforePop = MockURLProtocol.requestedURLs.filter { $0.contains("qishui.com/luna/pc/discover/mix") }.count
        XCTAssertEqual(sodaRequestsBeforePop, 2)

        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: false)
        XCTAssertEqual(state.selectedSource, .soda)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["soda_p1"])
        let sodaRequestsAfterPop = MockURLProtocol.requestedURLs.filter { $0.contains("qishui.com/luna/pc/discover/mix") }.count
        XCTAssertEqual(sodaRequestsAfterPop, sodaRequestsBeforePop, "Pop-back re-entry must reuse cached Qishui playlists")

        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: true)
        XCTAssertEqual(state.selectedSource, .soda)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["soda_p1"])
        let sodaRequestsAfterForce = MockURLProtocol.requestedURLs.filter { $0.contains("qishui.com/luna/pc/discover/mix") }.count
        XCTAssertEqual(sodaRequestsAfterForce, sodaRequestsBeforePop + 2)

        // Switch back to .tx -> cached immediately; manual force reload refreshes without resetting selectedSource
        state.selectSource(.tx)
        XCTAssertEqual(state.selectedSource, .tx)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["tx_p1"])
        await state.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: true)
        XCTAssertEqual(state.selectedSource, .tx)
        XCTAssertEqual(state.hotPlaylists.map(\.id), ["tx_p1"])
        let qqRequestsAfterForce = MockURLProtocol.requestedURLs.filter { $0.contains("u.y.qq.com") }.count
        XCTAssertEqual(qqRequestsAfterForce, qqRequestsBeforePop + 1)

        // Unsupported source normalizes to .wy
        state.selectSource(.kg)
        XCTAssertEqual(state.selectedSource, .wy)

        // Stale async response guard: slow .soda load must not overwrite .tx selection/cache
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("qishui.com/luna/pc/discover/mix") {
                return MockHttpStub(statusCode: 200, body: Data(sodaHotJson.utf8), delaySeconds: 0.25)
            }
            if url.contains("u.y.qq.com/cgi-bin/musicu.fcg") {
                return MockHttpStub(statusCode: 200, body: Data(txHotJson.utf8))
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }
        let raceState = DiscoverState(initialSource: .soda)
        let slowTask = Task { @MainActor in
            await raceState.loadHotPlaylistsIfNeeded(aggregator: aggregator, force: true)
        }
        try? await Task.sleep(nanoseconds: 40_000_000)
        raceState.selectSource(.tx)
        await raceState.loadHotPlaylistsIfNeeded(aggregator: aggregator)
        await slowTask.value
        XCTAssertEqual(raceState.selectedSource, .tx)
        XCTAssertEqual(raceState.hotPlaylists.map(\.id), ["tx_p1"])
        XCTAssertNil(raceState.hotPlaylistsBySource[.soda], "Stale Qishui response must not pollute cache after switching away")
    }

    func testQishuiProviderParsingPaginationDirtyJsonAndSearchIsolation() async throws {
        let mixChannel0Json = """
        {
          "inner_block": [
            {
              "resources": [
                {
                  "entity": {
                    "playlist": {
                      "id": "pl_empty",
                      "title": "空歌单应过滤",
                      "resource_cnt": { "track_cnt": 0 }
                    }
                  }
                },
                {
                  "entity": {
                    "playlist": {
                      "id": 7096700219496368135,
                      "title": "汽水精选集",
                      "desc": "好听 &amp; 精选",
                      "url_cover": {
                        "uri": "ies-music/단체1.jpeg",
                        "urls": ["http://p3-luna.douyinpic.com/img/"]
                      },
                      "owner": { "public_name": "编辑部" },
                      "stats": { "count_collected": 125000 },
                      "resource_cnt": { "track_cnt": 2 }
                    }
                  }
                }
              ]
            }
          ]
        }
        """
        let mixChannel14Json = """
        {
          "inner_block": [
            null,
            {
              "resources": [
                {
                  "entity": {
                    "playlist": {
                      "id": "7096700219496368135",
                      "title": "重复歌单应去重",
                      "resource_cnt": { "track_cnt": 2 }
                    }
                  }
                },
                {
                  "entity": {
                    "playlist": {
                      "id": "pl_pop",
                      "title": "流行热歌",
                      "url_cover": {
                        "uri": "/ies-music/pop_cover.jpg",
                        "template_prefix": "tplv-custom123"
                      },
                      "owner": { "nickname": "汽水流行" },
                      "stats": { "count_played": 8800 },
                      "count_tracks": 10
                    }
                  }
                }
              ]
            }
          ]
        }
        """
        let playlistPage1Json = """
        {
          "playlist": {
            "id": "7096700219496368135",
            "title": "汽水精选集",
            "desc": "分页测试",
            "url_cover": { "uri": "ies-music/pl_cover.jpg" },
            "owner": { "public_name": "编辑部" },
            "stats": { "count_collected": 125000 }
          },
          "has_more": true,
          "next_cursor": "cursor_2",
          "media_resources": [
            null,
            {
              "entity": {
                "track_wrapper": {
                  "track": {
                    "id": 9001,
                    "name": "第一首",
                    "duration": 205000,
                    "artists": [null, { "name": "歌手A" }, { "name": "歌手B" }],
                    "album": {
                      "id": "alb_1",
                      "name": "专辑一",
                      "url_cover": { "uri": "ies-music/t1.jpg" }
                    },
                    "bit_rates": [
                      { "quality": "medium", "size": 3145728 },
                      { "quality": "lossless", "size": 31457280 }
                    ]
                  }
                }
              }
            }
          ]
        }
        """
        let playlistPage2Json = """
        {
          "has_more": true,
          "next_cursor": "cursor_2",
          "media_resources": [
            {
              "entity": {
                "track": {
                  "id": "9002",
                  "name": "第二首",
                  "duration": null,
                  "artists": null,
                  "album": null,
                  "bit_rates": null
                }
              }
            }
          ]
        }
        """
        let playlistFallbackGetJson = """
        {
          "playlist": {
            "id": "7096700219496368999",
            "title": "首页回退歌单",
            "url_cover": { "uri": "ies-music/fallback_pl.jpg" }
          },
          "has_more": true,
          "next_cursor": "cursor_post",
          "media_resources": []
        }
        """
        let playlistFallbackPostJson = """
        {
          "playlist": {
            "id": "7096700219496368999",
            "title": "首页回退歌单",
            "url_cover": { "uri": "ies-music/fallback_pl.jpg" }
          },
          "has_more": true,
          "next_cursor": "cursor_empty_page",
          "media_resources": [
            {
              "entity": {
                "track": {
                  "id": "9901",
                  "name": "回退首曲",
                  "duration": 210000,
                  "artists": [{ "name": "回退歌手" }],
                  "bit_rates": [
                    { "quality": "hi_res", "size": 52428800 },
                    { "quality": "lossless", "size": 31457280 },
                    { "quality": "highest", "size": 6291456 },
                    { "quality": "higher", "size": 3145728 },
                    { "quality": "medium", "size": 1572864 }
                  ]
                }
              }
            }
          ]
        }
        """
        let playlistEmptyPageJson = """
        {
          "has_more": true,
          "next_cursor": "cursor_never_fetched",
          "media_resources": []
        }
        """
        let chartFallbackJson = """
        {
          "chart": {
            "id": "7036274230471712007",
            "title": "汽水热歌榜",
            "style": { "desc": "每周四更新" },
            "track_ranks": [
              {
                "track": {
                  "id": "8801",
                  "name": "榜单金曲",
                  "duration": 190000,
                  "artists": [{ "name": "榜首歌手" }],
                  "album": {
                    "name": "榜单专辑",
                    "url_cover": {
                      "uri": "ies-music/chart_track.png",
                      "template_prefix": "tplv-b829550vbb"
                    }
                  },
                  "bit_rates": [{ "quality": "hi_res" }]
                }
              }
            ]
          }
        }
        """
        let seoTrackJson = """
        {
          "lyric": {
            "content": "[00:02.50]<0,300,0>汽<300,300,0>水<600,400,0>词"
          }
        }
        """

        let mixBodies = SendableStringLog()
        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            let bodyText = readRequestBodyText(req)
            if url.contains("api.qishui.com/luna/pc/discover/mix") {
                mixBodies.append(bodyText)
                if bodyText.contains("\"14\"") {
                    return MockHttpStub(statusCode: 200, body: Data(mixChannel14Json.utf8))
                }
                return MockHttpStub(statusCode: 200, body: Data(mixChannel0Json.utf8))
            }
            if url.contains("api.qishui.com/luna/pc/playlist/detail") {
                if url.contains("playlist_id=7096700219496368999") {
                    if url.contains("cursor=cursor_empty_page") {
                        return MockHttpStub(statusCode: 200, body: Data(playlistEmptyPageJson.utf8))
                    }
                    return MockHttpStub(statusCode: 200, body: Data(playlistFallbackGetJson.utf8))
                }
                if url.contains("cursor=cursor_2") {
                    return MockHttpStub(statusCode: 200, body: Data(playlistPage2Json.utf8))
                }
                return MockHttpStub(statusCode: 200, body: Data(playlistPage1Json.utf8))
            }
            if url.contains("api.qishui.com/luna/playlist/detail") {
                if bodyText.contains("7096700219496368999") && !bodyText.contains("cursor_empty_page") {
                    return MockHttpStub(statusCode: 200, body: Data(playlistFallbackPostJson.utf8))
                }
                return MockHttpStub(statusCode: 200, body: Data(playlistEmptyPageJson.utf8))
            }
            if url.contains("api.qishui.com/luna/pc/charts/") {
                return MockHttpStub(statusCode: 502, body: Data())
            }
            if url.contains("api5-lf.qishui.com/luna/charts/") {
                return MockHttpStub(statusCode: 200, body: Data(chartFallbackJson.utf8))
            }
            if url.contains("beta-luna.douyin.com/luna/h5/seo_track") {
                return MockHttpStub(statusCode: 200, body: Data(seoTrackJson.utf8))
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: cfg)
        let aggregator = MusicAggregator(session: session)

        // 1. Hot playlists: merged channels 0 & 14, filtered trackCount > 0, deduped, encoded non-ASCII cover URI
        let hot = await aggregator.getHotPlaylists(source: .soda)
        XCTAssertEqual(hot.map(\.id), ["7096700219496368135", "pl_pop"])
        XCTAssertEqual(mixBodies.values.count, 2)
        for body in mixBodies.values {
            let parsedBody = JsonSafe.parseObject(body)
            XCTAssertEqual(JsonSafe.asString(parsedBody?["block_type"]), "discover_playlist_mix")
            XCTAssertEqual(JsonSafe.asInt(parsedBody?["count"]), 20)
        }
        XCTAssertEqual(hot[0].name, "汽水精选集")
        XCTAssertEqual(hot[0].description, "好听 & 精选")
        XCTAssertEqual(hot[0].playCount, "12.5万")
        XCTAssertTrue(hot[0].coverUrl.hasPrefix("https://p3-luna.douyinpic.com/img/ies-music/"))
        XCTAssertTrue(hot[0].coverUrl.contains("~tplv-b829550vbb-resize:500:500.png"))
        XCTAssertFalse(hot[0].coverUrl.contains("단체"))
        XCTAssertEqual(
            hot[1].coverUrl,
            "https://p3-luna.douyinpic.com/img/ies-music/pop_cover.jpg~tplv-custom123-resize:500:500.png"
        )

        // Never emit bare /img/{uri} and percent-encode non-ASCII in /img/ URL fallback
        XCTAssertNil(QishuiProvider.buildCoverUrl(from: ["urls": ["https://p3-luna.douyinpic.com/img/"]]))
        XCTAssertEqual(
            QishuiProvider.buildCoverUrl(from: ["urls": ["http://p3-luna.douyinpic.com/img/ies-music/단체 1.jpeg"]]),
            "https://p3-luna.douyinpic.com/img/ies-music/%EB%8B%A8%EC%B2%B4%201.jpeg~tplv-b829550vbb-resize:500:500.png"
        )

        // 2. Playlist detail: cursor pagination + repeated-cursor cycle guard + dirty JSON survival
        let detailRes = await aggregator.getPlaylistDetail(source: .soda, idOrUrl: "https://music.douyin.com/qishui/playlist/7096700219496368135")
        let detail = try detailRes.get()
        XCTAssertEqual(detail.playlist.source, .soda)
        XCTAssertEqual(detail.playlist.trackCount, 2)
        XCTAssertEqual(detail.tracks.map(\.id), ["soda_9001", "soda_9002"])
        XCTAssertEqual(detail.tracks[0].artist, "歌手A / 歌手B")
        XCTAssertEqual(detail.tracks[0].artistNames, ["歌手A", "歌手B"])
        XCTAssertEqual(detail.tracks[0].qualities.map(\.type), ["128k", "flac"])
        XCTAssertEqual(detail.tracks[1].artist, "未知歌手")
        XCTAssertEqual(detail.tracks[1].album, "")
        XCTAssertTrue(detail.tracks[1].qualities.isEmpty, "Missing/null bit_rates should return empty qualities")

        // 2b. Page-1 GET returning playlist metadata + empty media_resources falls back to POST /luna/playlist/detail
        // and stops pagination when subsequent page adds 0 tracks; bit_rates map higher->128k, highest->320k in canonical order
        let fallbackDetailRes = await aggregator.getPlaylistDetail(source: .soda, idOrUrl: "7096700219496368999")
        let fallbackDetail = try fallbackDetailRes.get()
        XCTAssertEqual(fallbackDetail.tracks.map(\.id), ["soda_9901"])
        XCTAssertEqual(fallbackDetail.tracks[0].qualities.map(\.type), ["128k", "320k", "flac", "flac24bit"])
        XCTAssertFalse(MockURLProtocol.requestedURLs.contains { $0.contains("cursor_never_fetched") })

        let qishuiProvider = QishuiProvider(session: session)
        let higherVsHighestTrack = qishuiProvider.parseTrackObj([
            "id": "9902",
            "name": "音质映射验证",
            "bit_rates": [
                ["quality": "highest", "size": 6_291_456],
                ["quality": "higher", "size": 3_145_728]
            ]
        ])
        XCTAssertEqual(higherVsHighestTrack?.qualities.map(\.type), ["128k", "320k"])
        XCTAssertEqual(higherVsHighestTrack?.qualities.map(\.size), ["3.0MB", "6.0MB"])

        // 3. Charts: 4 official boards & fallback endpoint
        let boards = aggregator.getLeaderboards(source: .soda)
        XCTAssertEqual(boards.map(\.bangId), [
            "7036274230471712007",
            "7060812597884869927",
            "7061475546400005410",
            "7415959718721494311"
        ])
        let boardDetail = try (await aggregator.getLeaderboardDetail(board: boards[0])).get()
        XCTAssertEqual(boardDetail.tracks.first?.id, "soda_8801")
        XCTAssertEqual(boardDetail.tracks.first?.qualities.map(\.type), ["flac24bit"])

        // 4. Lyrics fetch & <...> tag stripping
        let lyrics = await aggregator.fetchLyrics(for: boardDetail.tracks[0])
        XCTAssertEqual(lyrics.lines.count, 1)
        XCTAssertEqual(lyrics.lines[0].text, "汽水词")

        // 5. Search isolation: searchSinglePlatform(.soda), searchAggregated, findAlternativeTracks never query Qishui
        let beforeSearchRequests = MockURLProtocol.requestedURLs.count
        let directSodaSearch = await aggregator.searchSinglePlatform(source: .soda, query: "晴天")
        XCTAssertTrue(directSodaSearch.isEmpty)
        XCTAssertEqual(MockURLProtocol.requestedURLs.count, beforeSearchRequests)

        _ = await aggregator.searchAggregated(query: "晴天")
        _ = await aggregator.findAlternativeTracks(
            for: Track(id: "wy_probe", title: "晴天", artist: "周杰伦", source: .wy, songmid: "1"),
            allowedPlatformCodes: ["wy", "tx", "kw", "kg", "soda"]
        )
        let searchUrls = MockURLProtocol.requestedURLs.dropFirst(beforeSearchRequests)
        XCTAssertFalse(searchUrls.contains { $0.contains("qishui.com") || $0.contains("douyin.com") })
    }

    @MainActor
    func testQishuiPlaybackCrossPlatformFallbackAndUnsupportedFastFail() async throws {
        let weapiSearchJson = """
        {
          "code": 200,
          "result": {
            "songs": [
              {
                "id": 556677,
                "name": "汽水热曲",
                "ar": [{ "name": "汽水歌手" }],
                "al": { "id": 10, "name": "测试专辑", "picUrl": "https://p1.music.126.net/a.jpg" },
                "dt": 180000
              }
            ]
          }
        }
        """

        MockURLProtocol.configure { req in
            let url = req.url?.absoluteString ?? ""
            if url.contains("music.163.com/weapi/cloudsearch/get/web") {
                return MockHttpStub(statusCode: 200, body: Data(weapiSearchJson.utf8))
            }
            if url.contains("mock.qishui.fallback/wy") {
                return MockHttpStub(statusCode: 200, body: Data(#"{"url":"https://mock.qishui.fallback/stream.mp3"}"#.utf8))
            }
            if url.contains("mock.qishui.fallback/stream.mp3") {
                return MockHttpStub(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/mpeg", "Content-Length": "4096"],
                    body: Data(repeating: 0x44, count: 128)
                )
            }
            return MockHttpStub(statusCode: 404, body: Data())
        }

        let env = try await makeTestEnvironment(autoSkipDelayNs: 20_000_000)
        let player = env.player
        defer { player.clearQueue() }

        _ = try await importAndWaitReady(
            makeHttpScript(name: "WyOnlySource", requestUrlExpr: "'https://mock.qishui.fallback/' + info.musicInfo.source"),
            into: env.userApi
        )

        let sodaTrack0 = Track(
            id: "soda_101",
            title: "汽水热曲",
            artist: "汽水歌手",
            source: .soda,
            songmid: "101",
            durationMs: 180_000
        )
        let sodaTrack1 = Track(
            id: "soda_102",
            title: "第二首汽水",
            artist: "汽水歌手",
            source: .soda,
            songmid: "102",
            durationMs: 180_000
        )

        // 1. allowCrossPlatformFallback = true -> resolves via WY alternative match
        let fallbackRes = await env.userApi.resolveStream(
            track: sodaTrack0,
            preferredQuality: .q320k,
            allowCrossPlatformFallback: true
        )
        let resolved = try fallbackRes.get()
        XCTAssertTrue(resolved.switchedPlatform)
        XCTAssertEqual(resolved.resolvedTrack.source, .wy)
        XCTAssertEqual(resolved.resolvedTrack.songmid, "556677")
        XCTAssertEqual(resolved.url, "https://mock.qishui.fallback/stream.mp3")

        // 2. allowCrossPlatformFallback = true but no match on WY/TX/KW/KG -> explicit message
        MockURLProtocol.configure { _ in MockHttpStub(statusCode: 404, body: Data()) }
        let noMatchRes = await env.userApi.resolveStream(
            track: sodaTrack0,
            preferredQuality: .q320k,
            allowCrossPlatformFallback: true
        )
        if case .failure(let err) = noMatchRes {
            XCTAssertEqual(
                err.localizedDescription,
                "当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《汽水热曲》"
            )
        } else {
            XCTFail("Expected failure when no cross-platform match exists")
        }

        // 2b. PlayerController-level stop with autoFallbackSource = true and no cross-platform match
        // must preserve the explicit Qishui unsupported message (1-track queue & sequential boundary)
        var uiEvents: [PlayerUiEvent] = []
        let bag = player.events.sink { uiEvents.append($0) }
        defer { bag.cancel() }

        env.settingsStore.update { $0.autoFallbackSource = true }
        player.playTracks([sodaTrack0], startIndex: 0)
        let receivedSingleStopMsg = await waitUntilOnMain(timeoutSeconds: 5.0) {
            uiEvents.contains {
                $0.message == "当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《汽水热曲》"
            } && !player.state.isBuffering
        }
        XCTAssertTrue(receivedSingleStopMsg)
        XCTAssertFalse(player.state.isPlaying)

        uiEvents.removeAll()
        player.cycleRepeatMode() // .listLoop -> .sequential
        XCTAssertEqual(player.state.repeatMode, .sequential)
        player.playTracks([sodaTrack0, sodaTrack1], startIndex: 1)
        let receivedBoundaryStopMsg = await waitUntilOnMain(timeoutSeconds: 5.0) {
            uiEvents.contains {
                $0.message == "当前自定义音源不支持汽水音乐，且暂未在其他平台匹配到《第二首汽水》"
            } && !player.state.isBuffering
        }
        XCTAssertTrue(receivedBoundaryStopMsg)
        XCTAssertFalse(player.state.isPlaying)
        player.cycleRepeatMode() // .shuffle
        player.cycleRepeatMode() // .singleLoop
        player.cycleRepeatMode() // .listLoop

        // 3. allowCrossPlatformFallback = false -> fast-fails with zero search requests and no playlist auto-skip
        let beforeDisabledRequests = MockURLProtocol.requestedURLs.count
        let disabledRes = await env.userApi.resolveStream(
            track: sodaTrack0,
            preferredQuality: .q320k,
            allowCrossPlatformFallback: false
        )
        if case .failure(let err) = disabledRes {
            XCTAssertEqual(
                err.localizedDescription,
                "当前自定义音源不支持汽水音乐，请在设置开启「无感自动换源兜底」或导入支持汽水音乐的音源"
            )
        } else {
            XCTFail("Expected fast-fail when auto-fallback is disabled")
        }
        XCTAssertEqual(MockURLProtocol.requestedURLs.count, beforeDisabledRequests)

        uiEvents.removeAll()
        env.settingsStore.update { $0.autoFallbackSource = false }
        player.playTracks([sodaTrack0, sodaTrack1], startIndex: 0)
        let receivedUnsupportedEvent = await waitUntilOnMain(timeoutSeconds: 5.0) {
            uiEvents.contains { $0.message.contains("当前自定义音源不支持汽水音乐") }
        }
        XCTAssertTrue(receivedUnsupportedEvent)
        try? await Task.sleep(nanoseconds: 80_000_000)
        XCTAssertEqual(player.state.currentIndex, 0, "Unsupported Qishui track with fallback disabled must not auto-skip")
        XCTAssertFalse(player.state.isPlaying)
    }
}
