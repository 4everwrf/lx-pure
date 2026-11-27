import XCTest
@testable import LXPure

final class CoreProtocolTests: XCTestCase {

    func testParseLxScriptMetadataHeader() {
        let sampleScript = """
        /**
         * @name Demo LX Source
         * @description High quality community custom source
         * @version 2.3.1
         * @author OpenSourceDev
         * @homepage https://example.com
         */
        const { EVENT_NAMES, on, send } = globalThis.lx
        """

        let meta = LxScriptParser.parse(sampleScript)
        XCTAssertEqual(meta.name, "Demo LX Source")
        XCTAssertEqual(meta.description, "High quality community custom source")
        XCTAssertEqual(meta.version, "2.3.1")
        XCTAssertEqual(meta.author, "OpenSourceDev")
        XCTAssertEqual(meta.homepage, "https://example.com")
        XCTAssertTrue(meta.id.hasPrefix("user_api_"))

        let bangHeaderScript = """
        /*!
         * @name Demo Bang Header Source
         * @version v4.0.2
         * @author DemoTeam
         */
        """
        let bangMeta = LxScriptParser.parse(bangHeaderScript)
        XCTAssertEqual(bangMeta.name, "Demo Bang Header Source")
        XCTAssertEqual(bangMeta.version, "v4.0.2")
        XCTAssertEqual(bangMeta.author, "DemoTeam")
    }

    func testFormatVersionBadgeDeduplicatesVPrefix() {
        XCTAssertEqual(LxScriptParser.formatVersionBadge("3.2.18"), "v3.2.18")
        XCTAssertEqual(LxScriptParser.formatVersionBadge("v3.2.18"), "v3.2.18")
        XCTAssertEqual(LxScriptParser.formatVersionBadge("V3.2.18"), "v3.2.18")
        XCTAssertEqual(LxScriptParser.formatVersionBadge("vv1.0"), "v1.0")
        XCTAssertEqual(LxScriptParser.formatVersionBadge(""), "v1.0.0")
        XCTAssertEqual(LxScriptParser.formatVersionBadge(nil), "v1.0.0")
    }

    func testSafeJsonExtensionsAndNetEaseParseSurviveNull() {
        let dirtyJson = """
        {
          "id": 123456,
          "name": "热歌测试曲",
          "ar": [null, {"name": "歌手A"}, {"name": null}],
          "al": null,
          "dt": 215000,
          "l": null,
          "h": null,
          "sq": null,
          "hr": null,
          "flag": null
        }
        """

        let root = JsonSafe.parseObject(dirtyJson)
        XCTAssertNotNil(root)
        XCTAssertNil(JsonSafe.asObject(root?["al"]))
        XCTAssertNil(JsonSafe.asArray(root?["l"]))
        XCTAssertNil(JsonSafe.asString(root?["flag"]))
        XCTAssertNil(JsonSafe.asInt64(root?["flag"]))
        XCTAssertNil(JsonSafe.asInt(root?["flag"]))
        XCTAssertNil(JsonSafe.asBool(root?["flag"]))

        let privilege = JsonSafe.parseObject(#"{"maxbr": 999000, "maxBrLevel": "hires"}"#)
        let provider = NetEaseProvider(session: .shared)
        let track = provider.parseSongObj(root!, privilege: privilege)

        XCTAssertNotNil(track)
        XCTAssertEqual(track?.id, "wy_123456")
        XCTAssertEqual(track?.title, "热歌测试曲")
        XCTAssertEqual(track?.artist, "歌手A")
        XCTAssertEqual(track?.album, "")
        XCTAssertNil(track?.coverUrl)
        XCTAssertEqual(track?.durationMs, 215_000)
        XCTAssertEqual(track?.qualities.count, 4)
    }

    func testAggregatedSearchPrioritizesReliablePlatformsOverKugou() {
        let kgTrack = Track(
            id: "kg_1", title: "Someone Like You", artist: "Adele", source: .kg,
            coverUrl: "https://img.example.com/kg.jpg",
            qualities: [
                QualityInfo(type: "128k"), QualityInfo(type: "320k"),
                QualityInfo(type: "flac"), QualityInfo(type: "flac24bit")
            ]
        )
        let kwTrack = Track(
            id: "kw_1", title: "Someone Like You", artist: "Adele", source: .kw,
            coverUrl: nil, qualities: [QualityInfo(type: "128k")]
        )
        let txTrack = Track(
            id: "tx_1", title: "Someone Like You", artist: "Adele", source: .tx,
            coverUrl: "https://img.example.com/tx.jpg",
            qualities: [QualityInfo(type: "128k"), QualityInfo(type: "flac")]
        )
        let wyTrack = Track(
            id: "wy_1", title: "Someone Like You", artist: "Adele", source: .wy,
            coverUrl: nil, qualities: [QualityInfo(type: "128k")]
        )
        let mgTrack = Track(
            id: "mg_1", title: "Someone Like You", artist: "Adele", source: .mg,
            coverUrl: "https://img.example.com/mg.jpg",
            qualities: [QualityInfo(type: "128k"), QualityInfo(type: "320k"), QualityInfo(type: "flac")]
        )

        let selected = MusicAggregator.selectPrimaryAndAlternates([kgTrack, mgTrack, kwTrack, txTrack, wyTrack])
        XCTAssertEqual(selected.source, .wy)
        XCTAssertEqual(selected.alternates.map(\.source), [.tx, .kw, .kg, .mg])

        let withoutWy = MusicAggregator.selectPrimaryAndAlternates([kgTrack, txTrack, kwTrack])
        XCTAssertEqual(withoutWy.source, .tx)
        XCTAssertEqual(withoutWy.alternates.map(\.source), [.kw, .kg])
    }

    func testKuwoImageUrlNormalizationPreventsSslMismatch() {
        XCTAssertEqual(
            KuwoProvider.normalizeKuwoImageUrl("https://img1.kwcdn.kuwo.cn/star/albumcover/500/12/34.jpg"),
            "http://img1.kwcdn.kuwo.cn/star/albumcover/500/12/34.jpg"
        )
        XCTAssertEqual(
            KuwoProvider.normalizeKuwoImageUrl("120/abc.jpg"),
            "http://img1.kuwo.cn/star/albumcover/500/abc.jpg"
        )
        XCTAssertNil(KuwoProvider.normalizeKuwoImageUrl("   "))
    }

    func testParseLrcAndTranslationMerge() {
        let lrc = """
        [00:01.50]First line
        [00:05.20]Second line
        [00:10.00]Third line
        """
        let trans = """
        [00:01.55]第一句翻译
        [00:10.05]第三句翻译
        """

        let parsed = LrcParser.parse(lrc, translation: trans)
        XCTAssertEqual(parsed.lines.count, 3)
        XCTAssertTrue(parsed.hasTranslation)
        XCTAssertEqual(parsed.lines[0].translation, "第一句翻译")
        XCTAssertNil(parsed.lines[1].translation)
        XCTAssertEqual(parsed.lines[2].translation, "第三句翻译")
        XCTAssertEqual(parsed.activeIndex(at: 6_000), 1)
    }

    func testParseNetEaseYrcLines() {
        let yrc = """
        {"t":0,"c":[{"tx":"作词: Test"}]}
        [1230,2000](1230,500,0)Hello (1730,600,0)World
        [4500,1500](4500,800,0)Second
        """

        let parsed = LrcParser.parse(yrc)
        XCTAssertEqual(parsed.lines.count, 2)
        XCTAssertEqual(parsed.lines[0].timeMs, 1230)
        XCTAssertEqual(parsed.lines[0].text, "Hello World")
        XCTAssertEqual(parsed.lines[1].timeMs, 4500)
    }

    func testParseQishuiWordTimingLyricsAndMusicSourceSodaContract() {
        let qishuiLrc = """
        [00:01.20]<0,250,0>汽<250,300,0>水<550,400,0>音<950,300,0>乐
        [00:05.50]<0,500>Hello <500,600>World
        [6800,1200]<0,400,0>第<400,400,0>三<800,400,0>行
        """
        let parsed = LrcParser.parse(qishuiLrc)
        XCTAssertEqual(parsed.lines.count, 3)
        XCTAssertEqual(parsed.lines[0].timeMs, 1200)
        XCTAssertEqual(parsed.lines[0].text, "汽水音乐")
        XCTAssertEqual(parsed.lines[1].timeMs, 5500)
        XCTAssertEqual(parsed.lines[1].text, "Hello World")
        XCTAssertEqual(parsed.lines[2].timeMs, 6800)
        XCTAssertEqual(parsed.lines[2].text, "第三行")

        XCTAssertEqual(MusicSource.soda.code, "soda")
        XCTAssertEqual(MusicSource.soda.displayName, "汽水音乐")
        XCTAssertEqual(MusicSource.soda.shortLabel, "汽水")
        XCTAssertEqual(MusicSource.fromCode("SODA"), .soda)
        XCTAssertEqual(MusicAggregator.platformPriority(.soda), -1)

        let sodaTrack = Track(
            id: "soda_999",
            title: "测试汽水曲",
            artist: "测试歌手",
            source: .soda,
            songmid: "999",
            durationMs: 185_000
        )
        let dict = sodaTrack.toLxMusicInfoDictionary()
        XCTAssertEqual(dict["source"] as? String, "soda")
        XCTAssertEqual(dict["songmid"] as? String, "999")
    }

    func testTrackSerializesToLxMusicInfoContract() {
        let track = Track(
            id: "tx_001abc",
            title: "晴天",
            artist: "周杰伦",
            album: "叶惠美",
            source: .tx,
            songmid: "001abc",
            durationMs: 269_000,
            coverUrl: "https://example.com/cover.jpg",
            strMediaMid: "media_001",
            qualities: [
                QualityInfo(type: "128k", size: "4.1MB"),
                QualityInfo(type: "flac", size: "28.4MB")
            ]
        )

        let dict = track.toLxMusicInfoDictionary()
        XCTAssertEqual(dict["name"] as? String, "晴天")
        XCTAssertEqual(dict["singer"] as? String, "周杰伦")
        XCTAssertEqual(dict["source"] as? String, "tx")
        XCTAssertEqual(dict["songmid"] as? String, "001abc")
        XCTAssertEqual(dict["interval"] as? String, "04:29")
        XCTAssertEqual(dict["strMediaMid"] as? String, "media_001")
        XCTAssertEqual((dict["types"] as? [[String: Any]])?.count, 2)
        let typesMap = dict["_types"] as? [String: [String: Any]]
        XCTAssertEqual(typesMap?["flac"]?["size"] as? String, "28.4MB")
    }

    func testNetworkQualityAdvisorBalancesCellularAndWifi() {
        let cellularSaved = NetworkQualityAdvisor.decideQualityForNetwork(
            preferred: .flac, cellularDataSaverEnabled: true, isMetered: true
        )
        XCTAssertEqual(cellularSaved.quality, .q128k)
        XCTAssertTrue(cellularSaved.downgradedForCellular)

        let wifiLossless = NetworkQualityAdvisor.decideQualityForNetwork(
            preferred: .flac, cellularDataSaverEnabled: true, isMetered: false
        )
        XCTAssertEqual(wifiLossless.quality, .flac)
        XCTAssertFalse(wifiLossless.downgradedForCellular)

        let saverDisabled = NetworkQualityAdvisor.decideQualityForNetwork(
            preferred: .flac24, cellularDataSaverEnabled: false, isMetered: true
        )
        XCTAssertEqual(saverDisabled.quality, .flac24)
        XCTAssertFalse(saverDisabled.downgradedForCellular)
    }

    func testCoverUrlOptimizationReducesThumbnailTraffic() {
        XCTAssertEqual(
            optimizeCoverUrlForSize("https://p2.music.126.net/abc/123.jpg", isThumbnail: true),
            "https://p2.music.126.net/abc/123.jpg?param=200y200"
        )
        XCTAssertEqual(
            optimizeCoverUrlForSize("https://p2.music.126.net/abc/123.jpg", isThumbnail: false),
            "https://p2.music.126.net/abc/123.jpg?param=640y640"
        )
        XCTAssertEqual(
            optimizeCoverUrlForSize("https://y.gtimg.cn/music/photo_new/T002R500x500M000001abc.jpg", isThumbnail: true),
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000001abc.jpg"
        )
        XCTAssertEqual(
            optimizeCoverUrlForSize(
                "https://p3-luna.douyinpic.com/img/ies-music/abc.jpeg~tplv-b829550vbb-resize:500:500.png",
                isThumbnail: true
            ),
            "https://p3-luna.douyinpic.com/img/ies-music/abc.jpeg~tplv-b829550vbb-resize:200:200.png"
        )
        XCTAssertEqual(
            optimizeCoverUrlForSize(
                "https://p3-luna.douyinpic.com/img/ies-music/abc.jpeg~tplv-b829550vbb-resize:500:500.png",
                isThumbnail: false
            ),
            "https://p3-luna.douyinpic.com/img/ies-music/abc.jpeg~tplv-b829550vbb-resize:640:640.png"
        )
    }

    func testSourceUrlValidationAndScriptSanityCheck() {
        XCTAssertEqual(
            SourceUrlUtils.buildUpdateCandidateUrls("  https://example.com/custom-source.js  "),
            ["https://example.com/custom-source.js"]
        )
        XCTAssertTrue(SourceUrlUtils.buildUpdateCandidateUrls("file:///local.js").isEmpty)
        XCTAssertTrue(SourceUrlUtils.buildUpdateCandidateUrls(nil).isEmpty)

        let hexEscapedScript = "/**\n * @name Demo Hex Source\n * @version 1\n */\n" +
            "let x = globalThis['\\x6c\\x78']; " + String(repeating: "a", count: 200)
        XCTAssertTrue(UserApiManager.isValidDownloadedScript(hexEscapedScript))
        XCTAssertFalse(UserApiManager.isValidDownloadedScript("<html><body>404 Not Found</body></html>" + String(repeating: " ", count: 200)))
    }

    func testPlaybackRecoveryPolicyPreservesPositionAndNeverBansPrimaryTrack() {
        let netKind = PlaybackRecoveryPolicy.classifyError(
            errorDomain: NSURLErrorDomain,
            errorCode: NSURLErrorNotConnectedToInternet
        )
        XCTAssertEqual(netKind, .transientNetwork)
        let netPlan = PlaybackRecoveryPolicy.decideRecovery(
            kind: netKind, retryAttemptIndex: 0,
            primaryTrackId: "wy_1001", resolvedTrackId: "wy_1001"
        )
        XCTAssertFalse(netPlan.excludeCurrentSource)
        XCTAssertFalse(netPlan.excludeResolvedTrack)
        XCTAssertFalse(netPlan.evictStreamCache)
        XCTAssertTrue(netPlan.preferSameSourceFirst)
        XCTAssertTrue(netPlan.preservePosition)

        let expiredKind = PlaybackRecoveryPolicy.classifyError(httpStatusCode: 403)
        XCTAssertEqual(expiredKind, .expiredOrDeniedUrl)
        let refreshPlan = PlaybackRecoveryPolicy.decideRecovery(
            kind: expiredKind, retryAttemptIndex: 0,
            primaryTrackId: "wy_1001", resolvedTrackId: "wy_1001"
        )
        XCTAssertFalse(refreshPlan.excludeCurrentSource)
        XCTAssertTrue(refreshPlan.preferSameSourceFirst)
        XCTAssertTrue(refreshPlan.preservePosition)

        let brokenPrimary = PlaybackRecoveryPolicy.decideRecovery(
            kind: .streamOrSourceBroken, retryAttemptIndex: 0,
            primaryTrackId: "wy_1001", resolvedTrackId: "wy_1001"
        )
        XCTAssertTrue(brokenPrimary.excludeCurrentSource)
        XCTAssertFalse(brokenPrimary.excludeResolvedTrack)
        XCTAssertTrue(brokenPrimary.evictStreamCache)
        XCTAssertTrue(brokenPrimary.preservePosition)

        let brokenAlt = PlaybackRecoveryPolicy.decideRecovery(
            kind: .streamOrSourceBroken, retryAttemptIndex: 1,
            primaryTrackId: "wy_1001", resolvedTrackId: "tx_9999"
        )
        XCTAssertTrue(brokenAlt.excludeCurrentSource)
        XCTAssertTrue(brokenAlt.excludeResolvedTrack)
        XCTAssertTrue(brokenAlt.evictStreamCache)
    }

    func testCryptoPrimitivesAndPlatformSignatures() {
        XCTAssertEqual(LxCrypto.md5Hex("hello"), "5d41402abc4b2a76b9719d911017c592")
        XCTAssertEqual(LxCrypto.sha1Hex("hello"), "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d")

        let eapiParams = NetEaseCrypto.eapi(path: "/api/test", jsonBody: #"{"id":1}"#)
        XCTAssertFalse((eapiParams["params"] ?? "").isEmpty)

        let weapiParams = NetEaseCrypto.weapi(jsonBody: #"{"s":"test","type":1}"#)
        XCTAssertFalse((weapiParams["params"] ?? "").isEmpty)
        XCTAssertEqual(weapiParams["encSecKey"]?.count, 256) // 1024-bit RSA -> 128 bytes -> 256 hex chars

        let qqSign = QqSign.zzcSign(#"{"comm":{"ct":"19"}}"#)
        XCTAssertTrue(qqSign.hasPrefix("zzc"))
        XCTAssertGreaterThan(qqSign.count, 20)
    }

    func testJavaScriptCoreSandboxEndToEndWithMockLxScript() async {
        let mockScript = """
        /**
         * @name UnitTest Mock Source
         * @description Mock script verifying JSC preload bridge
         * @version 1.2.3
         * @author LXPureTests
         */
        const { EVENT_NAMES, on, send, utils } = globalThis.lx

        on(EVENT_NAMES.request, ({ source, action, info }) => {
          return new Promise((resolve, reject) => {
            if (action !== 'musicUrl') return reject(new Error('unsupported'))
            const digest = utils.crypto.md5(String(info.musicInfo.songmid))
            const aesBuf = utils.crypto.aesEncrypt('test', 'aes-128-cbc', '0123456789abcdef', '0123456789abcdef')
            const hexLen = utils.buffer.bufToString(aesBuf, 'hex').length
            setTimeout(() => {
              resolve(`https://cdn.example.com/${source}/${info.type}/${digest}/${hexLen}.flac`)
            }, 20)
          })
        })

        send(EVENT_NAMES.inited, {
          sources: {
            wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] },
            tx: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] },
            soda: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] }
          }
        })
        """

        let meta = LxScriptParser.parse(mockScript)
        let source = UserApiSource(
            id: meta.id,
            name: meta.name,
            description: meta.description,
            version: meta.version,
            author: meta.author,
            homepage: meta.homepage,
            script: mockScript,
            sourceUrl: nil,
            enabled: true,
            priority: 0
        )

        let runtime = LxJsRuntime(session: .shared, source: source)
        defer { runtime.destroy() }

        let initRes = await runtime.initialize(timeoutSeconds: 5.0)
        XCTAssertTrue(initRes.success, "Expected JSC init success, got error: \(initRes.errorMessage ?? "none")")
        XCTAssertEqual(initRes.supportedPlatforms["wy"], ["128k", "320k", "flac"])
        XCTAssertEqual(initRes.supportedPlatforms["tx"], ["128k", "320k"])
        XCTAssertEqual(initRes.supportedPlatforms["soda"], ["128k", "320k"])

        let track = Track(id: "wy_12345", title: "测试曲", artist: "测试歌手", source: .wy, songmid: "12345")
        let urlRes = await runtime.requestMusicUrl(track: track, quality: .flac, timeoutSeconds: 5.0)
        switch urlRes {
        case .success(let url):
            let expectedDigest = LxCrypto.md5Hex("12345")
            XCTAssertEqual(url, "https://cdn.example.com/wy/flac/\(expectedDigest)/32.flac")
        case .failure(let err):
            XCTFail("Expected mock script URL resolution success, got error: \(err.localizedDescription)")
        }

        let sodaTrack = Track(id: "soda_67890", title: "汽水测试曲", artist: "测试歌手", source: .soda, songmid: "67890")
        let sodaRes = await runtime.requestMusicUrl(track: sodaTrack, quality: .q320k, timeoutSeconds: 5.0)
        switch sodaRes {
        case .success(let url):
            let expectedDigest = LxCrypto.md5Hex("67890")
            XCTAssertEqual(url, "https://cdn.example.com/soda/320k/\(expectedDigest)/32.flac")
        case .failure(let err):
            XCTFail("Expected mock script soda URL resolution success, got error: \(err.localizedDescription)")
        }
    }

    @MainActor
    func testLibraryStorePersistenceRoundTrip() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("LXPureStoreTest_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let store = LibraryStore(baseDirectory: tempDir)
        let sample = Track(id: "wy_777", title: "夜曲", artist: "周杰伦", source: .wy, songmid: "777")

        XCTAssertTrue(store.toggleFavorite(sample))
        XCTAssertTrue(store.favoriteIds.contains("wy_777"))

        store.recordPlay(sample)
        XCTAssertEqual(store.recentHistory.first?.id, "wy_777")

        guard case .created(let plId) = store.createPlaylist(name: "测试歌单", tracks: [sample]) else {
            XCTFail("Expected playlist creation to succeed")
            return
        }
        XCTAssertEqual(store.userPlaylists.first?.name, "测试歌单")
        XCTAssertEqual(store.tracks(forPlaylistId: plId).count, 1)

        store.removeTrack(trackId: sample.id, fromPlaylistId: plId)
        XCTAssertEqual(store.tracks(forPlaylistId: plId).count, 0)

        store.deletePlaylist(id: plId)
        XCTAssertTrue(store.userPlaylists.isEmpty)
    }

    func testPlaybackSessionStorePersistsQueuePlaylistContextAndProgressRoundTrip() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("LXPureSessionTest_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let store = PlaybackSessionStore(baseDirectory: tempDir)
        let t1 = Track(id: "wy_1", title: "晴天", artist: "周杰伦", album: "叶惠美", source: .wy, songmid: "1", durationMs: 269_000)
        let t2 = Track(id: "tx_2", title: "稻香", artist: "周杰伦", album: "魔杰座", source: .tx, songmid: "2", durationMs: 223_000)
        let t3 = Track(id: "wy_3", title: "夜曲", artist: "周杰伦", album: "十一月的萧邦", source: .wy, songmid: "3", durationMs: 226_000)
        let source = PlaybackQueueSource(type: .userPlaylist, title: "通勤歌单", userPlaylistId: 42)

        store.saveQueueAndCursor(
            queue: [t1, t2, t3],
            queueSource: source,
            currentIndex: 1,
            positionMs: 95_000,
            durationMs: 223_000,
            repeatMode: .shuffle,
            activeQualityBadge: "SQ",
            activeProviderName: "测试源",
            synchronous: true
        )

        store.saveCursorOnly(
            currentIndex: 2,
            currentTrackId: "wy_3",
            positionMs: 142_500,
            durationMs: 226_000,
            repeatMode: .singleLoop,
            activeQualityBadge: "HR",
            activeProviderName: "测试源",
            synchronous: true
        )

        let reloaded = PlaybackSessionStore(baseDirectory: tempDir).loadSession()
        XCTAssertNotNil(reloaded)
        XCTAssertEqual(reloaded?.queue.count, 3)
        XCTAssertEqual(reloaded?.currentIndex, 2)
        XCTAssertEqual(reloaded?.queue[2].id, "wy_3")
        XCTAssertEqual(reloaded?.positionMs, 142_500)
        XCTAssertEqual(reloaded?.durationMs, 226_000)
        XCTAssertEqual(reloaded?.repeatMode, .singleLoop)
        XCTAssertEqual(reloaded?.queueSource?.title, "通勤歌单")
        XCTAssertEqual(reloaded?.queueSource?.userPlaylistId, 42)
        XCTAssertEqual(reloaded?.activeQualityBadge, "HR")

        // Index drift + near-end timestamp clamping
        let reconciled = PlaybackSessionStore.resolveRestoredSession(
            queueSnapshot: PlaybackQueueSnapshot(queue: [t1, t2, t3], queueSource: source),
            cursorSnapshot: PlaybackCursorSnapshot(currentIndex: 0, currentTrackId: "tx_2", positionMs: 222_500, durationMs: 223_000)
        )
        XCTAssertEqual(reconciled?.currentIndex, 1)
        XCTAssertEqual(reconciled?.positionMs, 221_000)

        // Oversized queue slicing
        let bigQueue = (0..<6000).map { Track(id: "wy_\($0)", title: "Song \($0)", artist: "Artist") }
        let (sliced, remapped) = PlaybackSessionStore.sliceQueueForPersistence(bigQueue, currentIndex: 3456)
        XCTAssertEqual(sliced.count, PlaybackSessionStore.maxPersistedQueueSize)
        XCTAssertEqual(sliced[remapped].id, "wy_3456")

        store.clear(synchronous: true)
        XCTAssertNil(store.loadSession())
    }

    @MainActor
    func testAppUpdateVersionComparisonAndGithubReleaseParsing() {
        XCTAssertTrue(AppUpdateManager.isNewerVersion(current: "1.1.0", remote: "1.1.1"))
        XCTAssertTrue(AppUpdateManager.isNewerVersion(current: "1.1.0-beta", remote: "v1.1.1"))
        XCTAssertTrue(AppUpdateManager.isNewerVersion(current: "1.2.0", remote: "1.10.0"))
        XCTAssertFalse(AppUpdateManager.isNewerVersion(current: "1.1.1", remote: "v1.1.1"))
        XCTAssertFalse(AppUpdateManager.isNewerVersion(current: "1.2.0", remote: "1.1.9"))
        XCTAssertEqual(AppUpdateManager.compareVersions(remote: "v1.2", current: "1.2.0"), 0)

        let releaseJson = """
        {
          "tag_name": "v1.1.1",
          "name": "v1.1.1",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/4everwrf/lx-pure/releases/tag/v1.1.1",
          "body": "## 轻雪音乐 (LX Pure) v1.1.1\\n\\n### 本版变更记录\\n- 修复播放页误触切歌\\n- 新增退出后自动记录歌单与进度",
          "assets": [
            {
              "name": "lx-pure-v1.1.1-release.apk",
              "size": 8200000,
              "digest": "sha256:5aa7222a07e8ce0775389a3f3611bc03c08a3f48dd02108de4a97b3acd76ed10",
              "browser_download_url": "https://github.com/4everwrf/lx-pure/releases/download/v1.1.1/lx-pure-v1.1.1-release.apk"
            },
            {
              "name": "lx-pure-ios-v1.1.1-unsigned.ipa",
              "size": 1350000,
              "digest": "sha256:311d2cd87bd93693f69b96f82dfa1394d64ef804fc40730aadb2d0647dc442b3",
              "browser_download_url": "https://github.com/4everwrf/lx-pure/releases/download/v1.1.1/lx-pure-ios-v1.1.1-unsigned.ipa"
            }
          ]
        }
        """

        let parsed = AppUpdateManager.parseGithubReleaseJson(releaseJson)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.versionName, "1.1.1")
        XCTAssertEqual(parsed?.tagName, "v1.1.1")
        XCTAssertEqual(parsed?.assetName, "lx-pure-ios-v1.1.1-unsigned.ipa")
        XCTAssertEqual(parsed?.assetSizeBytes, 1_350_000)
        XCTAssertEqual(parsed?.expectedSha256, "311d2cd87bd93693f69b96f82dfa1394d64ef804fc40730aadb2d0647dc442b3")
        XCTAssertTrue(parsed?.releaseNotes.contains("修复播放页误触切歌") == true)

        let draftJson = releaseJson.replacingOccurrences(of: "\"draft\": false", with: "\"draft\": true")
        XCTAssertNil(AppUpdateManager.parseGithubReleaseJson(draftJson))

        XCTAssertEqual(
            AppUpdateManager.extractTagFromReleaseUrl("https://github.com/4everwrf/lx-pure/releases/tag/v1.2.3?ref=app"),
            "v1.2.3"
        )
        XCTAssertTrue(AppUpdateManager.isValidZipOrIpaMagic(Data([0x50, 0x4B, 0x03, 0x04])))
        XCTAssertFalse(AppUpdateManager.isValidZipOrIpaMagic(Data("<html>".utf8)))
        XCTAssertNotNil(AppUpdateManager.trollStoreInstallURL(for: "https://github.com/4everwrf/lx-pure/releases/download/v1.1.1/lx-pure-ios-v1.1.1-unsigned.ipa"))
    }

    func testNormalizeScriptStripsBomAndUnifiesLineEndings() {
        let unixScript = "/**\n * @name Demo\n * @version 1\n */\nconst s = \"\\r\\n\";\n"
        let windowsBomScript = "\u{FEFF}/**\r\n * @name Demo\r\n * @version 1\r\n */\rconst s = \"\\r\\n\";\r\n"

        let normalized = LxScriptParser.normalizeScript(windowsBomScript)
        XCTAssertEqual(normalized, unixScript)
        XCTAssertEqual(
            LxCrypto.md5Hex(unixScript.trimmingCharacters(in: .whitespacesAndNewlines)),
            LxCrypto.md5Hex(normalized.trimmingCharacters(in: .whitespacesAndNewlines))
        )
        XCTAssertEqual(LxScriptParser.parse(windowsBomScript).name, "Demo")
    }

    func testBatchUpdateSummaryFormatsAccurateMessagesForLocalAndOnlineSources() {
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 0, failedCount: 0, skippedLocalCount: 7).formatUserMessage(),
            "当前均为本地导入脚本，未绑定在线更新链接"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 2, failedCount: 0, skippedLocalCount: 3).formatUserMessage(),
            "全部 2 个在线音源均已是最新版本（已跳过 3 个本地脚本）"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 2, failedCount: 0, skippedLocalCount: 0).formatUserMessage(),
            "全部 2 个在线音源均已是最新版本"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: ["源A v2.0"], latestCount: 0, failedCount: 1, skippedLocalCount: 2).formatUserMessage(),
            "已更新 1 个音源：源A v2.0（另有 1 个拉取失败）"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 1, failedCount: 2, skippedLocalCount: 0).formatUserMessage(),
            "1 个已最新，2 个拉取失败"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 0, failedCount: 2, skippedLocalCount: 3).formatUserMessage(),
            "更新检查失败，请检查网络连接后重试"
        )
        XCTAssertEqual(
            BatchUpdateSummary(updatedNames: [], latestCount: 0, failedCount: 0, skippedLocalCount: 0).formatUserMessage(),
            "暂无可检查更新的音源"
        )
    }

    @MainActor
    func testCustomPlaylistLimitAndAddToExternalPlaylist() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_playlist_limit_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let store = LibraryStore(baseDirectory: tempDir)
        XCTAssertEqual(LibraryStore.maxCustomPlaylists, 10)
        XCTAssertTrue(LibraryStore.isCustomPlaylist(sourceUrl: nil))
        XCTAssertTrue(LibraryStore.isCustomPlaylist(sourceUrl: "   "))
        XCTAssertFalse(LibraryStore.isCustomPlaylist(sourceUrl: "https://music.163.com/playlist?id=1"))

        let seedTrack = Track(id: "wy_seed", title: "原曲目", artist: "歌手A", source: .wy)
        let extRes = store.createPlaylist(
            name: "外部导入歌单",
            sourceUrl: "https://music.163.com/playlist?id=888",
            tracks: [seedTrack]
        )
        guard case .created(let externalId) = extRes else {
            XCTFail("Expected external playlist creation to succeed")
            return
        }
        XCTAssertEqual(store.customPlaylistCount, 0)
        XCTAssertTrue(store.canCreateCustomPlaylist)

        var createdCustomIds: [Int64] = []
        for idx in 1...LibraryStore.maxCustomPlaylists {
            let res = store.createPlaylist(name: "自建歌单\(idx)")
            guard case .created(let id) = res else {
                XCTFail("Expected custom playlist #\(idx) to succeed")
                return
            }
            createdCustomIds.append(id)
        }
        XCTAssertEqual(store.customPlaylistCount, 10)
        XCTAssertFalse(store.canCreateCustomPlaylist)

        // 11th custom playlist must be rejected
        XCTAssertEqual(
            store.createPlaylist(name: "超出上限歌单"),
            .limitReached(LibraryStore.maxCustomPlaylists)
        )
        XCTAssertEqual(store.userPlaylists.count, 11)

        // Adding a song to an externally imported playlist prepends to #1 and deduplicates properly
        let addedTrack = Track(id: "tx_new", title: "晴天", artist: "周杰伦", source: .tx, coverUrl: "https://example.com/c.jpg")
        let addRes = store.addTracks([addedTrack], toPlaylistId: externalId)
        XCTAssertEqual(addRes, AddTracksResult(addedCount: 1, duplicateCount: 0))
        XCTAssertTrue(store.playlistIdsContaining(trackId: "tx_new").contains(externalId))
        XCTAssertEqual(store.tracks(forPlaylistId: externalId).map(\.id), ["tx_new", "wy_seed"])

        // Appending explicitly (prepend: false) places track at bottom
        let tailTrack = Track(id: "kw_tail", title: "七里香", artist: "周杰伦", source: .kw)
        let tailRes = store.addTracks([tailTrack], toPlaylistId: externalId, prepend: false)
        XCTAssertEqual(tailRes, AddTracksResult(addedCount: 1, duplicateCount: 0))
        XCTAssertEqual(store.tracks(forPlaylistId: externalId).map(\.id), ["tx_new", "wy_seed", "kw_tail"])

        let dupRes = store.addTracks([addedTrack], toPlaylistId: externalId)
        XCTAssertEqual(dupRes, AddTracksResult(addedCount: 0, duplicateCount: 1))
        XCTAssertEqual(store.tracks(forPlaylistId: externalId).count, 3)

        // Deleting one custom playlist frees a slot
        store.deletePlaylist(id: createdCustomIds[0])
        XCTAssertEqual(store.customPlaylistCount, 9)
        XCTAssertTrue(store.canCreateCustomPlaylist)
        if case .created = store.createPlaylist(name: "补建歌单") {
            XCTAssertEqual(store.customPlaylistCount, 10)
        } else {
            XCTFail("Expected creating playlist after deletion to succeed")
        }
    }

    @MainActor
    func testLargePlaylistsAndTracksMemoryBoundsAndNoRetainCycle() {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_mem_stress_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        weak var weakStore: LibraryStore?
        var createdIds: [Int64] = []

        do {
            let store = LibraryStore(baseDirectory: tempDir)
            weakStore = store

            // Create 8 large external playlists (600 tracks each) + 10 custom playlists (200 tracks each) = 6,800 tracks
            for extIdx in 1...8 {
                let tracks = (0..<600).map { i in
                    Track(
                        id: "ext_\(extIdx)_\(i)",
                        title: "外部歌单曲目 \(extIdx)-\(i)",
                        artist: "歌手 \(i)",
                        album: "专辑 \(extIdx)",
                        source: .wy,
                        coverUrl: "https://p2.music.126.net/ext_\(extIdx)_\(i).jpg"
                    )
                }
                if case .created(let id) = store.createPlaylist(
                    name: "外部大歌单\(extIdx)",
                    sourceUrl: "https://music.163.com/playlist?id=\(10_000 + extIdx)",
                    tracks: tracks
                ) {
                    createdIds.append(id)
                } else {
                    XCTFail("Expected external playlist \(extIdx) creation to succeed")
                }
            }

            for customIdx in 1...LibraryStore.maxCustomPlaylists {
                let tracks = (0..<200).map { i in
                    Track(id: "cus_\(customIdx)_\(i)", title: "自建曲目 \(i)", artist: "歌手", source: .tx)
                }
                if case .created(let id) = store.createPlaylist(name: "自建大歌单\(customIdx)", tracks: tracks) {
                    createdIds.append(id)
                } else {
                    XCTFail("Expected custom playlist \(customIdx) creation to succeed")
                }
            }

            XCTAssertEqual(createdIds.count, 18)
            XCTAssertLessThanOrEqual(store.cachedTrackListCount, LibraryStore.maxCachedTrackLists)
            XCTAssertLessThanOrEqual(store.cachedTrackTotalCount, LibraryStore.maxCachedTracksTotal)

            // Flush writes, clear memory caches, and verify membership lookup uses ID-only decoding without inflating full-track cache
            store.flushPendingWrites()
            store.evictTrackCaches()
            XCTAssertEqual(store.cachedTrackListCount, 0)
            XCTAssertEqual(store.cachedTrackIdSetCount, 0)

            let matches = store.playlistIdsContaining(trackId: "ext_3_420")
            XCTAssertEqual(matches, Set([createdIds[2]]))
            XCTAssertEqual(store.cachedTrackListCount, 0, "Membership scan must not load full Track arrays into RAM")

            // Repeatedly browsing all 18 playlists keeps LRU track cache strictly bounded
            for id in createdIds {
                let loaded = store.tracks(forPlaylistId: id)
                XCTAssertFalse(loaded.isEmpty)
                XCTAssertLessThanOrEqual(store.cachedTrackListCount, LibraryStore.maxCachedTrackLists)
                XCTAssertLessThanOrEqual(store.cachedTrackTotalCount, LibraryStore.maxCachedTracksTotal)
            }

            // Deleting all playlists leaves zero cached lists, zero ID sets, and zero disk playlist files
            for id in createdIds {
                store.deletePlaylist(id: id)
            }
            store.flushPendingWrites()
            XCTAssertTrue(store.userPlaylists.isEmpty)
            XCTAssertEqual(store.cachedTrackListCount, 0)
            XCTAssertEqual(store.cachedTrackIdSetCount, 0)
            for id in createdIds {
                let path = tempDir.appendingPathComponent("playlist_\(id).json").path
                XCTAssertFalse(FileManager.default.fileExists(atPath: path))
            }
        }

        // Proves zero retain cycles in LibraryStore (Combine memory-warning subscription / ioQueue closures)
        XCTAssertNil(weakStore)
    }

    func testUpdateRoutesErrorMessagesNotesParsingAndFastScrollBounds() {
        let rawUrl = "https://github.com/4everwrf/lx-pure/releases/download/v1.1.2/lx-pure-ios-v1.1.2-unsigned.ipa"
        let routes = AppUpdateManager.buildDownloadRoutes(originalUrl: rawUrl)
        XCTAssertEqual(routes.count, 3)
        XCTAssertEqual(routes[0].label, "GitHub 直连")
        XCTAssertEqual(routes[0].url, rawUrl)
        XCTAssertTrue(routes[1].url.hasSuffix(rawUrl))
        XCTAssertTrue(routes[2].url.hasSuffix(rawUrl))

        let timeoutErr = NSError(domain: NSURLErrorDomain, code: NSURLErrorTimedOut, userInfo: [NSLocalizedDescriptionKey: "The request timed out."])
        let timeoutMsg = AppUpdateManager.formatDownloadError(timeoutErr, triedMirrors: true)
        XCTAssertTrue(timeoutMsg.contains("超时"))
        XCTAssertTrue(timeoutMsg.contains("代理"))

        let hostErr = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost, userInfo: nil)
        let hostMsg = AppUpdateManager.formatDownloadError(hostErr, triedMirrors: true)
        XCTAssertTrue(hostMsg.contains("无法连接"))

        let rawReleaseBody = """
        ## 轻雪音乐 (LX Pure) v1.1.2

        ### 本版变更记录
        - feat: 支持播放页添加到歌单 (6f4900e)
        - perf(ios): 修复大歌单缓存占用 (fb036db)

        ### 重要提示
        - Android 与 iOS 安装包均为**开源纯净版**

        ### 校验信息
        - SHA-256: `abc`
        """
        XCTAssertEqual(
            AppUpdateManager.parseReleaseNoteItems(rawReleaseBody),
            ["支持播放页添加到歌单", "修复大歌单缓存占用"]
        )

        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: 0.0, trackCount: 500), 0)
        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: -0.5, trackCount: 500), 0)
        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: 1.0, trackCount: 500), 499)
        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: 1.5, trackCount: 500), 499)
        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: 0.5, trackCount: 501), 250)
        XCTAssertEqual(TrackListScrollHelper.targetIndex(forFraction: 0.8, trackCount: 1), 0)
    }

    func testRound1PlaybackFixesUnderlyingErrorExtensionAndSessionMismatch() async {
        // 1. AVFoundationErrorDomain wrapping NSURLErrorNotConnectedToInternet with Chinese description
        let innerNetErr = NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorNotConnectedToInternet,
            userInfo: [NSLocalizedDescriptionKey: "似乎已断开与互联网的连接。"]
        )
        let outerAvErr = NSError(
            domain: "AVFoundationErrorDomain",
            code: -11800,
            userInfo: [
                NSLocalizedDescriptionKey: "这项操作无法完成",
                NSUnderlyingErrorKey: innerNetErr
            ]
        )
        XCTAssertEqual(PlaybackRecoveryPolicy.classify(error: outerAvErr), .transientNetwork)

        // 2. AudioCacheManager.inferExtension prioritizes URL extension > Content-Type > requested quality
        XCTAssertEqual(
            AudioCacheManager.inferExtension(
                urlString: "https://cdn.example.com/stream/abc.mp3?token=123",
                contentType: "audio/mpeg",
                quality: .flac
            ),
            "mp3"
        )
        XCTAssertEqual(
            AudioCacheManager.inferExtension(
                urlString: "https://cdn.example.com/stream/opaque-id?sig=1",
                contentType: "audio/mp4",
                quality: .flac
            ),
            "m4a"
        )
        XCTAssertEqual(
            AudioCacheManager.inferExtension(
                urlString: "https://cdn.example.com/stream/opaque-id",
                contentType: nil,
                quality: .flac
            ),
            "flac"
        )

        // 3. Mismatched currentTrackId resets positionMs & provider instead of applying deleted track's timestamp
        let t1 = Track(id: "wy_1", title: "Song 1", artist: "A", durationMs: 180_000)
        let t2 = Track(id: "wy_2", title: "Song 2", artist: "B", durationMs: 240_000)
        let mismatched = PlaybackSessionStore.resolveRestoredSession(
            queueSnapshot: PlaybackQueueSnapshot(queue: [t1, t2]),
            cursorSnapshot: PlaybackCursorSnapshot(
                currentIndex: 1,
                currentTrackId: "deleted_track",
                positionMs: 135_000,
                durationMs: 310_000,
                activeQualityBadge: "HR",
                activeProviderName: "OldSource"
            )
        )
        XCTAssertNotNil(mismatched)
        XCTAssertEqual(mismatched?.currentIndex, 1)
        XCTAssertEqual(mismatched?.positionMs, 0)
        XCTAssertEqual(mismatched?.durationMs, 240_000)
        XCTAssertNil(mismatched?.activeProviderName)

        // 4. Async load + flush round-trip
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_r1_session_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }
        let store = PlaybackSessionStore(baseDirectory: tempDir)
        store.saveQueueAndCursor(
            queue: [t1, t2],
            queueSource: nil,
            currentIndex: 1,
            positionMs: 42_000,
            durationMs: 240_000,
            repeatMode: .listLoop,
            activeQualityBadge: "SQ",
            activeProviderName: "Mock",
            synchronous: false
        )
        store.flush()
        let asyncLoaded = await store.loadSessionAsync()
        XCTAssertEqual(asyncLoaded?.currentIndex, 1)
        XCTAssertEqual(asyncLoaded?.positionMs, 42_000)
    }

    func testRound2SdkCryptoLyricsAndUserApiRegressionSuite() async {
        // 1. LxCrypto: URL-safe Base64, CBC empty IV -> zero IV (deterministic), zlib round-trip
        let raw = "LX Pure Round 2 音频沙箱 🎵"
        let b64 = LxCrypto.str2b64(raw)
        let compressed = LxCrypto.zlibDeflateBase64(b64)
        XCTAssertFalse(compressed.isEmpty)
        XCTAssertEqual(LxCrypto.zlibInflateBase64(compressed), b64)

        let keyB64 = LxCrypto.str2b64("0123456789abcdef")
        let cbc1 = LxCrypto.aesEncryptBase64(dataB64: b64, keyB64: keyB64, ivB64: "", mode: "AES/CBC/PKCS7Padding")
        let cbc2 = LxCrypto.aesEncryptBase64(dataB64: b64, keyB64: keyB64, ivB64: "", mode: "AES/CBC/PKCS7Padding")
        XCTAssertFalse(cbc1.isEmpty)
        XCTAssertEqual(cbc1, cbc2)

        // 2. Binary-safe bufToString(..., 'base64') & zlib in JavaScriptCore sandbox
        let binaryScript = """
        /**
         * @name Binary Buffer & Zlib Test Source
         * @version 1.0.0
         */
        const { EVENT_NAMES, on, send, utils } = globalThis.lx
        on(EVENT_NAMES.request, async () => {
          const b64 = utils.buffer.bufToString(new Uint8Array([0x80, 0xFF]), 'base64')
          const zipped = await utils.zlib.deflate('hello-lx-pure')
          const unzipped = await utils.zlib.inflate(zipped)
          const text = utils.buffer.bufToString(unzipped, 'utf8')
          return `https://cdn.example.com/${b64}/${text}`
        })
        send(EVENT_NAMES.inited, {
          sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
        """
        let meta = LxScriptParser.parse(binaryScript)
        let src = UserApiSource(
            id: meta.id, name: meta.name, description: meta.description, version: meta.version,
            author: meta.author, homepage: meta.homepage, script: binaryScript,
            sourceUrl: nil, enabled: true, priority: 0
        )
        let rt = LxJsRuntime(session: .shared, source: src)
        defer { rt.destroy() }
        let initRes = await rt.initialize(timeoutSeconds: 5.0)
        XCTAssertTrue(initRes.success)
        let track = Track(id: "wy_99", title: "Test", artist: "Artist", source: .wy, songmid: "99")
        let urlRes = await rt.requestMusicUrl(track: track, quality: .q128k, timeoutSeconds: 5.0)
        if case .success(let resolvedUrl) = urlRes {
            XCTAssertEqual(resolvedUrl, "https://cdn.example.com/gP8=/hello-lx-pure")
        } else {
            XCTFail("Expected binary Base64 + zlib round-trip to succeed")
        }

        // 3. BOM HTML rejection, lenient URL parser, and transport vs track error classification
        XCTAssertFalse(UserApiManager.isValidDownloadedScript("\u{FEFF}<!DOCTYPE html><html>lx</html>" + String(repeating: " ", count: 200)))
        XCTAssertNotNil(LxJsRuntime.parseLenientURL("https://example.com/music?q=周杰伦 晴天&ext={\"a\":1}"))
        XCTAssertTrue(UserApiManager.isTransportOrRuntimeFailure("请求超时，请检查网络连接"))
        XCTAssertFalse(UserApiManager.isTransportOrRuntimeFailure("音源暂不支持解析当前曲目"))

        // 4. LrcParser: BOM, [offset:], colon/4-digit ms, "//" filter, 1-to-1 trans, unique IDs
        let lrc = "\u{FEFF}[offset:-500]\n[00:01.0000]Line One\n[00:01:20]Line Two\n[00:05.00]Dup A\n[00:05.00]Dup A"
        let trans = "[00:01.00]第一句\n[00:05.00]//"
        let parsed = LrcParser.parse(lrc, translation: trans)
        XCTAssertEqual(parsed.lines.count, 4)
        XCTAssertEqual(parsed.lines[0].timeMs, 1_500)
        XCTAssertEqual(parsed.lines[0].translation, "第一句")
        XCTAssertNil(parsed.lines[1].translation)
        XCTAssertNil(parsed.lines[2].translation)
        XCTAssertNotEqual(parsed.lines[2].id, parsed.lines[3].id)
        XCTAssertEqual(parsed.activeIndex(at: 5_600), 2)

        // 5. FormatUtils.decodeHtml, CJK share URL extraction, and track matching
        XCTAssertEqual(
            FormatUtils.decodeHtml("<em>Rock</em> &#38; Roll &#x27;99 &amp;lt;Live&gt;"),
            "Rock & Roll '99 &lt;Live>"
        )
        XCTAssertEqual(
            extractFirstHttpUrl(from: "分享歌单 http://163cn.tv/abc123（来自@网易云音乐）"),
            "http://163cn.tv/abc123"
        )
        let original = Track(id: "wy_1", title: "千里之外", artist: "周杰伦 / 费玉清", durationMs: 256_000)
        let karaoke = Track(id: "tx_2", title: "千里之外 (伴奏)", artist: "周杰伦 / 费玉清", durationMs: 256_000)
        let reversedArtist = Track(id: "kw_3", title: "千里之外", artist: "费玉清 / 周杰伦", durationMs: 257_000)
        XCTAssertFalse(MusicAggregator.isTrackMatch(original, karaoke))
        XCTAssertTrue(MusicAggregator.isTrackMatch(original, reversedArtist))
    }

    @MainActor
    func testRound3DataDownloadLocalAndUpdaterSecurityRegressionSuite() {
        // 1. AppSettings forward-compatible decoding (partial JSON & unknown enum values do not wipe settings)
        let partialJson = #"{"preferredQuality":"unknown_future","autoFallbackSource":false,"themeMode":"light"}"#
        let decodedSettings = try? JSONDecoder().decode(AppSettings.self, from: Data(partialJson.utf8))
        XCTAssertNotNil(decodedSettings)
        XCTAssertEqual(decodedSettings?.preferredQuality, .q320k)
        XCTAssertEqual(decodedSettings?.autoFallbackSource, false)
        XCTAssertEqual(decodedSettings?.themeMode, .light)
        XCTAssertEqual(decodedSettings?.wifiOnlyHighQuality, true)

        // 2. DownloadManager MIME filter, filename sanitization, and LocalMusicManager subdirectory path distinctness
        XCTAssertTrue(DownloadManager.isDisallowedAudioContentType("text/html; charset=utf-8"))
        XCTAssertFalse(DownloadManager.isDisallowedAudioContentType("audio/flac"))
        XCTAssertEqual(DownloadManager.sanitizeFileName("...secret/track\n"), "secret_track")

        let root = URL(fileURLWithPath: "/tmp/LocalMusic", isDirectory: true)
        let a = root.appendingPathComponent("AlbumA/01.flac")
        let b = root.appendingPathComponent("AlbumB/01.flac")
        XCTAssertNotEqual(
            LocalMusicManager.relativeSubpath(of: a, under: root),
            LocalMusicManager.relativeSubpath(of: b, under: root)
        )

        // 3. AppUpdateManager SHA-256 extraction, mirror gating, and TrollStore % percent encoding
        let digestHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        let body = """
        ### 校验信息
        - **iOS IPA**: `lx-pure-ios-v1.1.4-unsigned.ipa`
          - SHA-256: `\(digestHex)`
        """
        XCTAssertEqual(
            AppUpdateManager.extractSha256FromReleaseBody(body, assetName: "lx-pure-ios-v1.1.4-unsigned.ipa"),
            digestHex
        )
        XCTAssertEqual(
            AppUpdateManager.extractSha256FromSumsText("\(digestHex)  lx-pure-ios-v1.1.4-unsigned.ipa", assetName: "lx-pure-ios-v1.1.4-unsigned.ipa"),
            digestHex
        )
        let unverifiedRoutes = AppUpdateManager.buildDownloadRoutes(
            originalUrl: "https://github.com/4everwrf/lx-pure/releases/download/v1.1.4/lx-pure-ios-v1.1.4-unsigned.ipa",
            allowMirrors: false
        )
        XCTAssertEqual(unverifiedRoutes.count, 1)
        let tsUrl = AppUpdateManager.trollStoreInstallURL(for: "https://example.com/a%20b.ipa?x=1&y=2")
        XCTAssertTrue(tsUrl?.absoluteString.contains("%2520") == true)

        // 4. LibraryStore orphan protection, empty cover fallback, and interrupted download recovery
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_r3_lib_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }
        let store = LibraryStore(baseDirectory: tempDir)
        let track = Track(id: "wy_r3", title: "Cover Song", artist: "Singer", coverUrl: "https://img.example.com/c.jpg")
        let addToMissing = store.addTracks([track], toPlaylistId: 999_999)
        XCTAssertEqual(addToMissing.addedCount, 0)
        _ = store.createPlaylist(name: "EmptyCover", coverUrl: "", tracks: [track])
        XCTAssertEqual(store.userPlaylists.first?.coverUrl, "https://img.example.com/c.jpg")

        store.upsertDownload(DownloadRecord(
            trackId: "wy_r3",
            track: track,
            quality: .flac,
            status: .downloading,
            progress: 0.4,
            filePath: "partial.flac",
            totalBytes: 1000,
            errorMessage: nil,
            updatedAt: 100
        ))
        let orphans = store.reconcileInterruptedDownloads()
        XCTAssertEqual(orphans, ["partial.flac"])
        XCTAssertEqual(store.downloadRecord(for: "wy_r3")?.status, .failed)
        XCTAssertNil(store.downloadRecord(for: "wy_r3")?.filePath)
    }

    @MainActor
    func testRound5JsonOverflowSafetyAndAsyncPlaylistLoadingRegression() async {
        // 1. IEEE-754 2^63 boundary must safely return nil instead of trapping Int64(d)
        XCTAssertNil(JsonSafe.asInt("9223372036854775808"))
        XCTAssertNil(JsonSafe.asInt64("9223372036854775808"))
        XCTAssertNil(JsonSafe.asInt64("9223372036854775807.0"))
        XCTAssertEqual(JsonSafe.asInt("42.0"), 42)
        XCTAssertEqual(JsonSafe.asInt64("123456.0"), 123456)

        // 2. Async playlist loading & membership lookup after cache eviction
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_r5_lib_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }
        let store = LibraryStore(baseDirectory: tempDir)
        let t1 = Track(id: "wy_r5_a", title: "Alpha", artist: "A")
        let t2 = Track(id: "wy_r5_b", title: "Beta", artist: "B")
        guard case .created(let plId) = store.createPlaylist(name: "AsyncTest", tracks: [t1]) else {
            XCTFail("Expected playlist creation to succeed")
            return
        }
        store.addTracks([t2], toPlaylistId: plId, prepend: true)
        store.flushPendingWrites()
        store.evictTrackCaches()

        XCTAssertNil(store.cachedTracksIfLoaded(forPlaylistId: plId))
        let loaded = await store.loadTracksAsync(forPlaylistId: plId)
        XCTAssertEqual(loaded.map(\.id), ["wy_r5_b", "wy_r5_a"])

        store.evictTrackCaches()
        let containing = await store.playlistIdsContainingAsync(trackId: "wy_r5_b")
        XCTAssertEqual(containing, [plId])
    }

    func testArtistParserAndLayoutPlannerHandleMultiArtistAndOverflowEdgeCases() {
        // Placeholders & empty strings (including \u{200B}, \u{0000}, N/A, 本地音频)
        XCTAssertTrue(ArtistParser.parseRawString("").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("   \u{200B} ").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("未知歌手").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("本地音频").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("<unknown>").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("Unknown Artist").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("Unknown\u{3000}Artist").isEmpty)
        XCTAssertTrue(ArtistParser.parseRawString("N/A").isEmpty)
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦 / N/A / 陈奕迅"), ["周杰伦", "陈奕迅"])
        XCTAssertTrue(ArtistParser.parseRawString(" / / & , ").isEmpty)
        XCTAssertEqual(ArtistParser.fallbackDisplayName(" / / & , "), "未知歌手")
        XCTAssertEqual(ArtistParser.fallbackDisplayName("本地音频"), "本地音频")

        // Multi-artist delimiters, NUL/newline, Kuwo pre-transformed bands, protected bands & ZWJ emoji
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦 / 费玉清"), ["周杰伦", "费玉清"])
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦、温岚、弹头"), ["周杰伦", "温岚", "弹头"])
        XCTAssertEqual(ArtistParser.parseRawString("林俊杰&蔡卓妍"), ["林俊杰", "蔡卓妍"])
        XCTAssertEqual(ArtistParser.parseRawString("陈奕迅；王菲"), ["陈奕迅", "王菲"])
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦 × 五月天"), ["周杰伦", "五月天"])
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦\u{3000}×\u{3000}五月天"), ["周杰伦", "五月天"])
        XCTAssertEqual(ArtistParser.parseRawString("SPY×FAMILY"), ["SPY×FAMILY"])
        XCTAssertEqual(ArtistParser.parseRawString("AC/DC / Metallica"), ["AC/DC", "Metallica"])
        XCTAssertEqual(ArtistParser.parseRawString("K/DA / (G)I-DLE"), ["K/DA", "(G)I-DLE"])
        XCTAssertEqual(ArtistParser.parseRawString("10,000 Maniacs"), ["10,000 Maniacs"])
        XCTAssertEqual(ArtistParser.parseRawString("Tyler, The Creator / Kali Uchis"), ["Tyler, The Creator", "Kali Uchis"])
        XCTAssertEqual(ArtistParser.parseRawString("Simon & Garfunkel"), ["Simon & Garfunkel"])
        XCTAssertEqual(ArtistParser.parseRawString("Simon  /  Garfunkel"), ["Simon & Garfunkel"])
        XCTAssertEqual(ArtistParser.parseRawString("Earth, Wind & Fire & The Emotions"), ["Earth, Wind & Fire", "The Emotions"])
        XCTAssertEqual(ArtistParser.parseRawString("Daft Punk"), ["Daft Punk"])
        XCTAssertEqual(ArtistParser.parseRawString("Taylor Swift"), ["Taylor Swift"])
        XCTAssertEqual(ArtistParser.parseRawString("Artist A / Ft. Worth"), ["Artist A", "Ft. Worth"])
        XCTAssertEqual(ArtistParser.parseRawString("Jay Chou (feat. Lara & Gary)"), ["Jay Chou", "Lara", "Gary"])
        XCTAssertEqual(ArtistParser.parseRawString("陈奕迅 (feat. 王菲 (Live))"), ["陈奕迅", "王菲 (Live)"])
        XCTAssertEqual(ArtistParser.parseRawString("A (feat.)"), ["A"])
        XCTAssertEqual(ArtistParser.parseRawString("Artist A\u{0000}Artist B"), ["Artist A", "Artist B"])
        XCTAssertEqual(ArtistParser.parseRawString("👩‍🎤 DJ"), ["👩‍🎤 DJ"])
        XCTAssertEqual(ArtistParser.parseRawString("周杰伦 / 未知歌手 / 周杰伦 / 费玉清"), ["周杰伦", "费玉清"])
        XCTAssertEqual(KuwoProvider.parseKuwoArtist("Simon &amp; Garfunkel").1, ["Simon & Garfunkel"])
        XCTAssertEqual(KuwoProvider.parseKuwoArtist("&amp;").0, "未知歌手")

        // Structured artistNames precedence, newlines, HTML entity safety, & legacy JSON compatibility
        let structuredTrack = Track(
            id: "wy_band",
            title: "Test",
            artist: "Custom/Band / Foo & Bar",
            artistNames: ["Custom/Band", "Foo & Bar", "O'Connor &amp; Co."]
        )
        XCTAssertEqual(ArtistParser.extractArtists(from: structuredTrack), ["Custom/Band", "Foo & Bar", "O'Connor &amp; Co."])
        XCTAssertEqual(ArtistParser.extractArtists(rawArtist: "", structuredArtists: ["Artist A\nArtist B"]), ["Artist A", "Artist B"])

        let legacyData = Data(#"{"id":"wy_legacy","title":"千里之外","artist":"周杰伦 / 费玉清","album":"依然范特西"}"#.utf8)
        let decoded = try? JSONDecoder().decode(Track.self, from: legacyData)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.artistNames ?? [], [])
        XCTAssertEqual(ArtistParser.extractArtists(from: decoded!), ["周杰伦", "费玉清"])

        // ReDoS safety: pathological unclosed featuring must stay linear
        let unclosedStart = Date()
        _ = ArtistParser.parseRawString("(feat." + String(repeating: " ", count: 10_000) + "x")
        XCTAssertLessThan(Date().timeIntervalSince(unclosedStart), 0.2)

        // Overflow layout planner: 70-artist chorus stops lazily and keeps badge visible
        var measureCalls = 0
        let chorusPlan = ArtistRowLayoutPlanner.plan(
            artistCount: 70,
            maxWidth: 240,
            artistWidthAt: { _ in
                measureCalls += 1
                return 80
            },
            separatorWidth: 6,
            singleArtistChevronWidth: 14,
            overflowBadgeWidthForHidden: { _ in 56 },
            albumFullWidth: 90,
            minAlbumDisplayWidth: 60,
            minTruncatedArtistWidth: 56
        )
        XCTAssertEqual(chorusPlan.visibleCount, 2)
        XCTAssertEqual(chorusPlan.hiddenCount, 68)
        XCTAssertFalse(chorusPlan.showAlbum)
        XCTAssertLessThanOrEqual(measureCalls, 4)

        // Long single artist + album: never crushed below 60%
        let balancedSingle = ArtistRowLayoutPlanner.plan(
            artistCount: 1,
            maxWidth: 300,
            artistWidthAt: { _ in 260 },
            separatorWidth: 6,
            singleArtistChevronWidth: 14,
            overflowBadgeWidthForHidden: { _ in 50 },
            albumFullWidth: 210,
            minAlbumDisplayWidth: 60,
            minTruncatedArtistWidth: 56
        )
        XCTAssertTrue(balancedSingle.showAlbum)
        XCTAssertTrue(balancedSingle.truncateSingleVisibleArtist)
        XCTAssertGreaterThanOrEqual(balancedSingle.singleArtistMaxWidth ?? 0, 180)

        // NaN / invalid maxWidth handled safely
        let nanPlan = ArtistRowLayoutPlanner.plan(
            artistCount: 5,
            maxWidth: .nan,
            artistWidthAt: { _ in 80 },
            separatorWidth: 6,
            singleArtistChevronWidth: 14,
            overflowBadgeWidthForHidden: { _ in 52 },
            albumFullWidth: 80,
            minAlbumDisplayWidth: 60,
            minTruncatedArtistWidth: 56
        )
        XCTAssertEqual(nanPlan.visibleCount, 0)
        XCTAssertFalse(nanPlan.showAlbum)

        // Absurdly narrow viewport -> compact summary pill only
        let tinyPlan = ArtistRowLayoutPlanner.plan(
            artistCount: 5,
            maxWidth: 90,
            artistWidthAt: { _ in 120 },
            separatorWidth: 6,
            singleArtistChevronWidth: 14,
            overflowBadgeWidthForHidden: { _ in 52 },
            albumFullWidth: 0,
            minAlbumDisplayWidth: 60,
            minTruncatedArtistWidth: 56
        )
        XCTAssertEqual(tinyPlan.visibleCount, 0)
        XCTAssertEqual(tinyPlan.hiddenCount, 5)
        XCTAssertTrue(tinyPlan.showCompactSummaryOnly)
    }

    private struct SeededRNG: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { self.state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
        mutating func next() -> UInt64 {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return state
        }
    }

    func testRepeatModeFourWayCycleAndQueueNavigatorBoundaries() {
        XCTAssertEqual(RepeatMode.listLoop.next(), .sequential)
        XCTAssertEqual(RepeatMode.sequential.next(), .shuffle)
        XCTAssertEqual(RepeatMode.shuffle.next(), .singleLoop)
        XCTAssertEqual(RepeatMode.singleLoop.next(), .listLoop)
        XCTAssertEqual(RepeatMode.sequential.label, "顺序播放")

        // Sequential boundaries (Previous always navigates directly regardless of positionMs)
        let nav = QueueNavigator(rng: SeededRNG(seed: 42))
        nav.onQueueReplaced(queueSize: 3, startIndex: 0, mode: .sequential)
        XCTAssertFalse(nav.canSkipPrevious(queueSize: 3, currentIndex: 0, mode: .sequential))
        XCTAssertTrue(nav.canSkipNext(queueSize: 3, currentIndex: 0, mode: .sequential))
        XCTAssertEqual(
            nav.navigatePrevious(queueSize: 3, currentIndex: 0, mode: .sequential, positionMs: 5_001),
            .restartCurrent(message: "已是第一首，已重新播放")
        )
        XCTAssertEqual(
            nav.navigatePrevious(queueSize: 3, currentIndex: 0, mode: .sequential, positionMs: 5_000),
            .restartCurrent(message: "已是第一首，已重新播放")
        )
        XCTAssertEqual(
            nav.navigatePrevious(queueSize: 3, currentIndex: 2, mode: .sequential, positionMs: 12_000),
            .switchTo(1)
        )
        XCTAssertEqual(
            nav.navigatePrevious(queueSize: 3, currentIndex: 2, mode: .sequential, positionMs: 1_500),
            .switchTo(1)
        )
        XCTAssertEqual(
            nav.navigateNext(queueSize: 3, currentIndex: 2, mode: .sequential, isAutoAdvance: false),
            .stayAtBoundary(message: "已是最后一首")
        )
        XCTAssertEqual(
            nav.navigateNext(queueSize: 3, currentIndex: 2, mode: .sequential, isAutoAdvance: true),
            .stopAtEnd(message: "顺序播放已结束")
        )
        XCTAssertFalse(nav.willAutoAdvanceOnEnd(queueSize: 3, currentIndex: 2, mode: .sequential))
        XCTAssertNil(nav.peekNextIndexForAutoAdvance(queueSize: 3, currentIndex: 2, mode: .sequential))

        // ListLoop & SingleLoop
        XCTAssertEqual(
            nav.navigatePrevious(queueSize: 4, currentIndex: 0, mode: .listLoop, positionMs: 0),
            .switchTo(3)
        )
        XCTAssertEqual(
            nav.navigateNext(queueSize: 4, currentIndex: 3, mode: .listLoop, isAutoAdvance: true),
            .switchTo(0)
        )
        XCTAssertEqual(
            nav.navigateNext(queueSize: 4, currentIndex: 2, mode: .singleLoop, isAutoAdvance: true),
            .restartCurrent(message: nil)
        )
        XCTAssertEqual(
            nav.navigateNext(queueSize: 4, currentIndex: 2, mode: .singleLoop, isAutoAdvance: false),
            .switchTo(3)
        )

        // Single-track queue
        for mode in RepeatMode.allCases {
            nav.onQueueReplaced(queueSize: 1, startIndex: 0, mode: mode)
            XCTAssertFalse(nav.canSkipPrevious(queueSize: 1, currentIndex: 0, mode: mode))
            XCTAssertFalse(nav.canSkipNext(queueSize: 1, currentIndex: 0, mode: mode))
            XCTAssertNil(nav.peekNextIndexForAutoAdvance(queueSize: 1, currentIndex: 0, mode: mode))
            XCTAssertEqual(nav.willAutoAdvanceOnEnd(queueSize: 1, currentIndex: 0, mode: mode), mode != .sequential)
        }

        // Shuffle no-repeat cycle, back/forward history, playNext, and cursor-0 removal safety
        let shuffleNav = QueueNavigator(rng: SeededRNG(seed: 12345))
        var cur = 1
        shuffleNav.onQueueReplaced(queueSize: 6, startIndex: cur, mode: .shuffle)
        XCTAssertFalse(shuffleNav.canSkipPrevious(queueSize: 6, currentIndex: cur, mode: .shuffle))
        var visited = [cur]
        for _ in 0..<5 {
            let peek = shuffleNav.peekNextIndexForAutoAdvance(queueSize: 6, currentIndex: cur, mode: .shuffle)
            guard case .switchTo(let nextIdx) = shuffleNav.navigateNext(queueSize: 6, currentIndex: cur, mode: .shuffle, isAutoAdvance: false) else {
                XCTFail("Expected switchTo in shuffle")
                return
            }
            XCTAssertEqual(peek, nextIdx)
            XCTAssertNotEqual(cur, nextIdx)
            cur = nextIdx
            visited.append(cur)
        }
        XCTAssertEqual(Set(visited), Set(0..<6))
        // Back one step returns to visited[4], forward replays visited[5]
        XCTAssertEqual(
            shuffleNav.navigatePrevious(queueSize: 6, currentIndex: cur, mode: .shuffle, positionMs: 0),
            .switchTo(visited[4])
        )
        XCTAssertEqual(
            shuffleNav.navigateNext(queueSize: 6, currentIndex: visited[4], mode: .shuffle, isAutoAdvance: false),
            .switchTo(visited[5])
        )

        // Removing track at cursor 0 must not leak future unplayed track into previous history
        let removeNav = QueueNavigator(rng: SeededRNG(seed: 77))
        removeNav.onQueueReplaced(queueSize: 3, startIndex: 0, mode: .shuffle)
        removeNav.onTrackRemovedFromQueue(removedIndex: 0, newCurrentIndex: 0, newQueueSize: 2, mode: .shuffle)
        XCTAssertFalse(removeNav.canSkipPrevious(queueSize: 2, currentIndex: 0, mode: .shuffle))

        // iOS 16-safe SF Symbol for sequential mode + resilient RepeatMode & cursor decoding
        XCTAssertEqual(RepeatMode.sequential.sfSymbol, "arrow.right.to.line")
        let uppercaseCursorJson = Data(#"{"currentIndex":2,"currentTrackId":"wy_2","positionMs":42000,"repeatMode":"SEQUENTIAL"}"#.utf8)
        let decodedUppercase = try? JSONDecoder().decode(PlaybackCursorSnapshot.self, from: uppercaseCursorJson)
        XCTAssertEqual(decodedUppercase?.repeatMode, .sequential)
        XCTAssertEqual(decodedUppercase?.currentIndex, 2)
        XCTAssertEqual(decodedUppercase?.positionMs, 42_000)

        let unknownCursorJson = Data(#"{"currentIndex":3,"currentTrackId":"wy_3","positionMs":15000,"repeatMode":"future_mode_v9"}"#.utf8)
        let decodedUnknown = try? JSONDecoder().decode(PlaybackCursorSnapshot.self, from: unknownCursorJson)
        XCTAssertEqual(decodedUnknown?.repeatMode, .listLoop)
        XCTAssertEqual(decodedUnknown?.currentIndex, 3)
        XCTAssertEqual(decodedUnknown?.positionMs, 15_000)

        // Hardening guards: degenerate append, OOB remove, redundant shuffle mode change
        let hardNav = QueueNavigator(rng: SeededRNG(seed: 9))
        hardNav.onQueueReplaced(queueSize: 1, startIndex: 0, mode: .shuffle)
        hardNav.onTrackAppendedToQueue(appendedIndex: 0, currentIndex: 0, newQueueSize: 1, mode: .shuffle)
        hardNav.onTrackAppendedToQueue(appendedIndex: 1, currentIndex: 0, newQueueSize: 2, mode: .shuffle)
        XCTAssertEqual(hardNav.peekNextIndexForAutoAdvance(queueSize: 2, currentIndex: 0, mode: .shuffle), 1)
        hardNav.onTrackRemovedFromQueue(removedIndex: -1, newCurrentIndex: 0, newQueueSize: 2, mode: .shuffle)
        XCTAssertEqual(hardNav.peekNextIndexForAutoAdvance(queueSize: 2, currentIndex: 0, mode: .shuffle), 1)
    }

    func testPlaybackFastFailAndSourceSwitchingParitySuite() {
        // 1. AudioQuality.fallbackChain must strictly descend (never escalate on cellular data saver)
        XCTAssertEqual(AudioQuality.fallbackChain(preferred: .flac24), [.flac24, .flac, .q320k, .q128k])
        XCTAssertEqual(AudioQuality.fallbackChain(preferred: .flac), [.flac, .q320k, .q128k])
        XCTAssertEqual(AudioQuality.fallbackChain(preferred: .q320k), [.q320k, .q128k])
        XCTAssertEqual(AudioQuality.fallbackChain(preferred: .q128k), [.q128k])

        // 2. Startup HTTP 403 (wasReady = false) immediately switches source; mid-song 403 refreshes same source first
        let midSong403 = PlaybackRecoveryPolicy.decideRecovery(
            kind: .expiredOrDeniedUrl,
            retryAttemptIndex: 0,
            primaryTrackId: "wy_1001",
            resolvedTrackId: "wy_1001",
            wasReady: true
        )
        XCTAssertFalse(midSong403.excludeCurrentSource)
        XCTAssertTrue(midSong403.preferSameSourceFirst)

        let startup403 = PlaybackRecoveryPolicy.decideRecovery(
            kind: .expiredOrDeniedUrl,
            retryAttemptIndex: 0,
            primaryTrackId: "wy_1001",
            resolvedTrackId: "wy_1001",
            wasReady: false
        )
        XCTAssertTrue(startup403.excludeCurrentSource)
        XCTAssertFalse(startup403.preferSameSourceFirst)
        XCTAssertTrue(startup403.evictStreamCache)
        XCTAssertFalse(startup403.excludeResolvedTrack)

        // 3. Scoped AudioCacheManager cache keys isolate sourceId, resolvedTrackId, and script revision
        let k1 = AudioCacheManager.cacheKey(
            trackId: "wy_1",
            resolvedTrackId: "wy_1",
            sourceId: "src_a",
            sourceRevision: "rev11111",
            quality: .flac
        )
        let k2 = AudioCacheManager.cacheKey(
            trackId: "wy_1",
            resolvedTrackId: "wy_1",
            sourceId: "src_b",
            sourceRevision: "rev11111",
            quality: .flac
        )
        let k3 = AudioCacheManager.cacheKey(
            trackId: "wy_1",
            resolvedTrackId: "wy_1",
            sourceId: "src_a",
            sourceRevision: "rev22222",
            quality: .flac
        )
        XCTAssertNotEqual(k1, k2)
        XCTAssertNotEqual(k1, k3)
        XCTAssertTrue(k1.hasSuffix("_flac"))

        // 4. Per-platform/track keys & deterministic track-miss classification
        XCTAssertEqual(UserApiManager.sourcePlatformKey(sourceId: "s1", platformCode: "WY"), "s1_wy")
        XCTAssertEqual(UserApiManager.sourceTrackKey(sourceId: "s1", trackId: "wy_1001"), "s1_wy_1001")
        XCTAssertTrue(UserApiManager.isDeterministicTrackMiss("该歌曲暂无版权"))
        XCTAssertTrue(UserApiManager.isDeterministicTrackMiss("歌曲已下架 404"))
        // VIP / 403 on higher tiers must NOT prune full-track fallback so 128k still runs
        XCTAssertFalse(UserApiManager.isDeterministicTrackMiss("需要VIP会员才可试听无损"))
        XCTAssertFalse(UserApiManager.isDeterministicTrackMiss("HTTP 403 Forbidden"))
        XCTAssertFalse(UserApiManager.isDeterministicTrackMiss("当前音质不可用"))
    }

    @MainActor
    func testParseAndImportMeloraJsonBundlesAndFinalUrlSandboxParity() async throws {
        let scriptA = "/**\r\n * @name Alpha Demo\r\n * @version 1.2.0\r\n * @author TesterA\r\n */\r\n" +
            "const { EVENT_NAMES, on, send } = globalThis.lx;\n" +
            "on(EVENT_NAMES.request, async () => 'https://cdn.example.com/a.mp3');\n" +
            "send(EVENT_NAMES.inited, { sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });"
        let scriptB = "globalThis.lx.on('request', async () => 'https://cdn.example.com/b.mp3');\n" +
            "globalThis.lx.send('inited', { sources: { kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } } });"

        let meloraObj: [String: Any] = [
            "app": "melora",
            "type": "lx-sources",
            "scripts": [
                ["name": "Alpha_v1.2.0.js", "code": scriptA],
                ["name": "FallbackSource.js", "code": scriptB],
                ["name": "BadNumberCode.js", "code": 404],
                ["name": "HtmlStub.js", "code": "<html><body>404 Not Found</body></html>"],
                ["name": "NonLxText.js", "code": "just plain text without markers"]
            ]
        ]
        let meloraData = try JSONSerialization.data(withJSONObject: meloraObj)
        let meloraJson = String(decoding: meloraData, as: UTF8.self)

        // 1. Melora bundle parsing with BOM, CRLF normalization, fallbackName cleanup, and skipping invalid items
        let meloraResult = LxScriptParser.parseScriptBundle("\u{FEFF}" + meloraJson)
        guard case .validBundle(let entries, let skippedCount) = meloraResult else {
            XCTFail("Expected validBundle for Melora JSON, got \(meloraResult)")
            return
        }
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(skippedCount, 3)
        XCTAssertEqual(entries[0].fallbackName, "Alpha_v1.2.0")
        XCTAssertFalse(entries[0].script.contains("\r"))
        XCTAssertEqual(LxScriptParser.parse(entries[0].script, fallbackName: entries[0].fallbackName).name, "Alpha Demo")
        XCTAssertEqual(entries[1].fallbackName, "FallbackSource")
        XCTAssertEqual(LxScriptParser.parse(entries[1].script, fallbackName: entries[1].fallbackName).name, "FallbackSource")

        // Payload validator accepts Melora bundle while single-script validator keeps rejecting JSON
        XCTAssertTrue(UserApiManager.isValidDownloadedPayload(meloraJson))
        XCTAssertFalse(UserApiManager.isValidDownloadedScript(meloraJson))

        // 2. Top-level array & single wrapped object formats
        let arrayData = try JSONSerialization.data(withJSONObject: [["name": "ArrSrc.js", "script": scriptA]])
        let arrayJson = String(decoding: arrayData, as: UTF8.self)
        if case .validBundle(let arrEntries, _) = LxScriptParser.parseScriptBundle(arrayJson) {
            XCTAssertEqual(arrEntries.count, 1)
        } else {
            XCTFail("Expected validBundle for top-level script array")
        }
        // P0-1 regression: top-level JSON array bundle must NEVER pass single-script validator (guards updateSingleSource)
        XCTAssertFalse(UserApiManager.isValidDownloadedScript(arrayJson))
        XCTAssertTrue(UserApiManager.isValidDownloadedPayload(arrayJson))

        let wrappedData = try JSONSerialization.data(withJSONObject: ["name": "Wrapped.js", "code": scriptA])
        let wrappedJson = String(decoding: wrappedData, as: UTF8.self)
        if case .validBundle(let wrapEntries, _) = LxScriptParser.parseScriptBundle(wrappedJson) {
            XCTAssertEqual(wrapEntries.count, 1)
        } else {
            XCTFail("Expected validBundle for single wrapped script object")
        }

        // 3. Regular standalone .js script returns .notBundle
        XCTAssertEqual(LxScriptParser.parseScriptBundle(scriptA), .notBundle)
        XCTAssertTrue(UserApiManager.isValidDownloadedScript(scriptA))
        XCTAssertTrue(UserApiManager.isValidDownloadedPayload(scriptA))

        // 4. Adversarial / dirty JSON & oversized inputs must be rejected cleanly (A4)
        let errorJsonNumeric = #"{"code": 404, "message": "not found"}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(errorJsonNumeric) {} else {
            XCTFail("Expected invalidBundle for numeric error JSON")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(errorJsonNumeric))

        let errorJsonString = #"{"code": "ERROR_LX_404", "message": "short string not script"}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(errorJsonString) {} else {
            XCTFail("Expected invalidBundle for short string error JSON")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(errorJsonString))

        let shortTagArray = #"{"sources":["lx-api"]}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(shortTagArray) {} else {
            XCTFail("Expected invalidBundle for short non-script array item")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(shortTagArray))

        let errorArrayLong = #"[{"error":"lx rate limit","detail":""# + String(repeating: "x", count: 200) + #""}]"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(errorArrayLong) {} else {
            XCTFail("Expected invalidBundle for error JSON array")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(errorArrayLong))

        let emptyScriptsJson = #"{"app":"melora","scripts":[]}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(emptyScriptsJson) {} else {
            XCTFail("Expected invalidBundle for empty scripts array")
        }

        let allInvalidJson = #"{"app":"melora","scripts":[{"name":"bad.js","code":"<html>404</html>"}]}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(allInvalidJson) {} else {
            XCTFail("Expected invalidBundle when all bundle entries are invalid")
        }

        let brokenJson = #"{"app":"melora","scripts":[{"name":"a","code":"broken"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(brokenJson) {} else {
            XCTFail("Expected invalidBundle for broken JSON")
        }

        let brokenContentJson = #"{"app":"melora","content":"globalThis.lx broken"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(brokenContentJson) {} else {
            XCTFail("Expected invalidBundle for broken content JSON")
        }

        let nulJson = "{\"app\":\"melora\",\"scripts\":[{\"name\":\"a\",\"code\":\"globalThis.lx\u{0000}\"}]}"
        if case .invalidBundle = LxScriptParser.parseScriptBundle(nulJson) {} else {
            XCTFail("Expected invalidBundle for NUL byte payload")
        }

        let escapedNulJson = #"{"app":"melora","scripts":[{"name":"a.js","code":"globalThis.lx.send('inited', {});\u0000padding"}]}"#
        if case .invalidBundle = LxScriptParser.parseScriptBundle(escapedNulJson) {} else {
            XCTFail("Expected invalidBundle for JSON-escaped NUL byte")
        }

        // Single-script download validator rejects NUL bytes and > 1 MB scripts
        XCTAssertFalse(UserApiManager.isValidDownloadedScript(scriptA + "\u{0000}"))
        let oversizedSingle = scriptA + "//" + String(repeating: "a", count: LxScriptParser.maxSingleScriptBytes)
        XCTAssertFalse(UserApiManager.isValidDownloadedScript(oversizedSingle))

        // > 1 MB entry inside bundle is skipped if valid siblings exist, and rejected if alone
        let mixedOversizedData = try JSONSerialization.data(withJSONObject: [
            "scripts": [
                ["name": "Huge.js", "code": oversizedSingle],
                ["name": "Ok.js", "code": scriptB]
            ]
        ])
        let mixedOversizedJson = String(decoding: mixedOversizedData, as: UTF8.self)
        if case .validBundle(let okEntries, let skippedOversized) = LxScriptParser.parseScriptBundle(mixedOversizedJson) {
            XCTAssertEqual(okEntries.count, 1)
            XCTAssertEqual(skippedOversized, 1)
        } else {
            XCTFail("Expected validBundle skipping oversized sibling entry")
        }

        let onlyOversizedData = try JSONSerialization.data(withJSONObject: [
            "scripts": [["name": "Huge.js", "code": oversizedSingle]]
        ])
        let onlyOversizedJson = String(decoding: onlyOversizedData, as: UTF8.self)
        if case .invalidBundle = LxScriptParser.parseScriptBundle(onlyOversizedJson) {} else {
            XCTFail("Expected invalidBundle when only entry exceeds 1 MB")
        }

        let hugePayload = "{\"scripts\":[\"" + String(repeating: "a", count: LxScriptParser.maxBundlePayloadBytes + 16) + "\"]}"
        if case .invalidBundle = LxScriptParser.parseScriptBundle(hugePayload) {} else {
            XCTFail("Expected invalidBundle when payload exceeds 4 MB")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(hugePayload))

        let fiftyOneItems: [[String: Any]] = (1...51).map { idx in
            ["name": "S\(idx).js", "code": scriptB]
        }
        let fiftyOneData = try JSONSerialization.data(withJSONObject: ["scripts": fiftyOneItems])
        let fiftyOneJson = String(decoding: fiftyOneData, as: UTF8.self)
        if case .invalidBundle = LxScriptParser.parseScriptBundle(fiftyOneJson) {} else {
            XCTFail("Expected invalidBundle when bundle exceeds 50 scripts")
        }
        let fiftyOneArrayData = try JSONSerialization.data(withJSONObject: fiftyOneItems)
        let fiftyOneArrayJson = String(decoding: fiftyOneArrayData, as: UTF8.self)
        if case .invalidBundle = LxScriptParser.parseScriptBundle(fiftyOneArrayJson) {} else {
            XCTFail("Expected invalidBundle when top-level array exceeds 50 scripts")
        }
        XCTAssertFalse(UserApiManager.isValidDownloadedPayload(fiftyOneArrayJson))

        // 5. SourceImportBatchResult message formatting parity
        let dummySource = UserApiSource(
            id: "s1", name: "Alpha Demo", description: "", version: "1.0.0",
            author: "Tester", homepage: "", script: scriptA, sourceUrl: nil, enabled: true, priority: 0
        )
        XCTAssertEqual(SourceImportBatchResult(imported: [], skippedCount: 0).formatUserMessage(), "未导入任何有效音源")
        XCTAssertEqual(SourceImportBatchResult(imported: [dummySource], skippedCount: 0).formatUserMessage(), "已导入音源：Alpha Demo")
        XCTAssertEqual(SourceImportBatchResult(imported: [dummySource, dummySource], skippedCount: 0).formatUserMessage(), "已导入 2 个音源")
        XCTAssertEqual(SourceImportBatchResult(imported: [dummySource], skippedCount: 2).formatUserMessage(), "已导入 1 个音源（跳过 2 个无效项）")

        // 6. End-to-end UserApiManager.importRawPayload:
        //    - Pre-existing single source keeps its sourceUrl & priority when re-imported via bundle
        //    - Duplicate ID inside same bundle deduplicates with latest winning and insertion order preserved
        //    - Bundle URL is NEVER bound as sourceUrl on newly imported scripts
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lxpure_bundle_test_\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let store = LibraryStore(baseDirectory: tempDir)
        let advisor = NetworkQualityAdvisor(startMonitoring: false, initialConnected: true, initialMetered: false)
        let userApi = UserApiManager(
            libraryStore: store,
            session: .shared,
            aggregator: MusicAggregator(session: .shared),
            networkAdvisor: advisor
        )
        await userApi.awaitInitialLoadIfNeeded(timeoutSec: 2.0)

        let existingSingleRes = await userApi.importRawScript(scriptA, sourceUrl: "https://example.com/alpha.js")
        let existingSingle = try existingSingleRes.get()
        XCTAssertEqual(existingSingle.priority, 0)
        XCTAssertEqual(existingSingle.sourceUrl, "https://example.com/alpha.js")

        let scriptAV2 = scriptA.replacingOccurrences(of: "@version 1.2.0", with: "@version 1.2.5")
        let dedupBundleData = try JSONSerialization.data(withJSONObject: [
            "app": "melora",
            "type": "lx-sources",
            "scripts": [
                ["name": "Alpha_old.js", "code": scriptA],
                ["name": "FallbackSource.js", "code": scriptB],
                ["name": "Alpha_new.js", "code": scriptAV2],
                ["name": "Invalid.js", "code": "<html>bad</html>"]
            ]
        ])
        let dedupBundleJson = String(decoding: dedupBundleData, as: UTF8.self)

        let batchRes = await userApi.importRawPayload(dedupBundleJson, sourceUrl: "https://example.com/melora-sources.json")
        let batch = try batchRes.get()
        XCTAssertEqual(batch.imported.count, 2)
        XCTAssertEqual(batch.skippedCount, 1)
        XCTAssertEqual(batch.imported[0].name, "Alpha Demo")
        XCTAssertEqual(batch.imported[0].version, "1.2.5", "Duplicate ID in same bundle must keep latest entry")
        XCTAssertEqual(batch.imported[0].priority, 0, "Existing source priority must be preserved")
        XCTAssertEqual(batch.imported[0].sourceUrl, "https://example.com/alpha.js", "Existing single-script sourceUrl must be preserved")
        XCTAssertEqual(batch.imported[1].name, "FallbackSource")
        XCTAssertEqual(batch.imported[1].priority, 1)
        XCTAssertNil(batch.imported[1].sourceUrl, "Bundle URL must not be bound as single-script update URL")

        // 7. End-to-end JavaScriptCore sandbox forwards resp.finalUrl through user-api-preload.js
        let finalUrlScript = """
        /**
         * @name FinalUrl Test Source
         * @version 1.0.0
         */
        const { EVENT_NAMES, on, send, request } = globalThis.lx
        on(EVENT_NAMES.request, () => new Promise((resolve, reject) => {
          request('https://redirect.example.com/audio.mp3', { method: 'get', timeout: 5000 }, (err, resp) => {
            if (err) return reject(err)
            resolve(resp.finalUrl || '')
          })
        }))
        send(EVENT_NAMES.inited, {
          sources: { wy: { type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
        })
        """
        let stubCfg = URLSessionConfiguration.ephemeral
        stubCfg.protocolClasses = [FinalUrlStubURLProtocol.self]
        let stubSession = URLSession(configuration: stubCfg)
        let finalUrlMeta = LxScriptParser.parse(finalUrlScript)
        let finalUrlSrc = UserApiSource(
            id: finalUrlMeta.id, name: finalUrlMeta.name, description: finalUrlMeta.description,
            version: finalUrlMeta.version, author: finalUrlMeta.author, homepage: finalUrlMeta.homepage,
            script: finalUrlScript, sourceUrl: nil, enabled: true, priority: 0
        )
        let finalUrlRt = LxJsRuntime(session: stubSession, source: finalUrlSrc)
        defer { finalUrlRt.destroy() }
        let finalInitRes = await finalUrlRt.initialize(timeoutSeconds: 5.0)
        XCTAssertTrue(finalInitRes.success, "Expected finalUrl mock script to initialize")
        let dummyTrack = Track(id: "wy_100", title: "Demo", artist: "Tester", source: .wy, songmid: "100")
        let finalRes = await finalUrlRt.requestMusicUrl(track: dummyTrack, quality: .q128k, timeoutSeconds: 5.0)
        if case .success(let resolvedFinalUrl) = finalRes {
            XCTAssertEqual(resolvedFinalUrl, "https://redirect.example.com/audio.mp3")
        } else {
            XCTFail("Expected resp.finalUrl to be forwarded through preload bridge")
        }

        // Cleanly tear down spawned JS runtimes and flush LibraryStore writes before tempDir removal
        for imported in batch.imported {
            await userApi.removeSource(id: imported.id)
        }
        store.flushPendingWrites()
    }
}

private final class FinalUrlStubURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url,
              let resp = HTTPURLResponse(
                  url: url,
                  statusCode: 200,
                  httpVersion: "HTTP/1.1",
                  headerFields: ["Content-Type": "application/json"]
              ) else {
            return
        }
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"ok":true}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
