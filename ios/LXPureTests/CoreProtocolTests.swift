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
            tx: { type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k'] }
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

        let track = Track(id: "wy_12345", title: "测试曲", artist: "测试歌手", source: .wy, songmid: "12345")
        let urlRes = await runtime.requestMusicUrl(track: track, quality: .flac, timeoutSeconds: 5.0)
        switch urlRes {
        case .success(let url):
            let expectedDigest = LxCrypto.md5Hex("12345")
            XCTAssertEqual(url, "https://cdn.example.com/wy/flac/\(expectedDigest)/32.flac")
        case .failure(let err):
            XCTFail("Expected mock script URL resolution success, got error: \(err.localizedDescription)")
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

        let plId = store.createPlaylist(name: "测试歌单", tracks: [sample])
        XCTAssertEqual(store.userPlaylists.first?.name, "测试歌单")
        XCTAssertEqual(store.tracks(forPlaylistId: plId).count, 1)

        store.removeTrack(trackId: sample.id, fromPlaylistId: plId)
        XCTAssertEqual(store.tracks(forPlaylistId: plId).count, 0)

        store.deletePlaylist(id: plId)
        XCTAssertTrue(store.userPlaylists.isEmpty)
    }
}
