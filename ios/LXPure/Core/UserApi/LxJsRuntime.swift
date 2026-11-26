import CoreFoundation
import Foundation
import JavaScriptCore

struct ScriptInitResult: Sendable {
    let success: Bool
    let supportedPlatforms: [String: [String]]
    let errorMessage: String?

    init(
        success: Bool,
        supportedPlatforms: [String: [String]] = [:],
        errorMessage: String? = nil
    ) {
        self.success = success
        self.supportedPlatforms = supportedPlatforms
        self.errorMessage = errorMessage
    }
}

enum LxRuntimeError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let msg): return msg
        }
    }
}

private final class LxNoRedirectDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

/// Isolated single-queue JavaScriptCore sandbox compatible with LX Music Mobile custom `.js` scripts (v2).
final class LxJsRuntime: @unchecked Sendable {
    private static let maxActiveTimers = 256
    private static let maxHttpResponseBytes = 4 * 1024 * 1024 // 4 MB safety cap
    private static let formAllowedChars = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )
    private static let noRedirectDelegate = LxNoRedirectDelegate()
    private static let noRedirectSession: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 15
        cfg.httpShouldSetCookies = false
        return URLSession(configuration: cfg, delegate: noRedirectDelegate, delegateQueue: nil)
    }()

    private let session: URLSession
    private let source: UserApiSource
    private let secretKey = UUID().uuidString
    private let jsQueue: DispatchQueue
    private let stateLock = NSLock()

    private var jsVM: JSVirtualMachine?
    private var jsContext: JSContext?
    private var isDestroyed = false

    private var initStarted = false
    private var initWaiters: [UUID: CheckedContinuation<ScriptInitResult, Never>] = [:]
    private var initTimeoutWorkItem: DispatchWorkItem?
    private var initResultCached: ScriptInitResult?

    private var pendingActions: [String: CheckedContinuation<Result<Any?, Error>, Never>] = [:]
    private var pendingTimeouts: [String: DispatchWorkItem] = [:]
    private var activeTasks: [String: URLSessionDataTask] = [:]
    private var cancelledRequests = Set<String>()
    private var activeTimers: [Int64: DispatchWorkItem] = [:]

    var hasInFlightWork: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        if isDestroyed { return false }
        return !pendingActions.isEmpty ||
            !activeTasks.isEmpty ||
            (initResultCached == nil && !initWaiters.isEmpty)
    }

    init(session: URLSession, source: UserApiSource) {
        self.session = session
        self.source = source
        self.jsQueue = DispatchQueue(
            label: "com.lxpure.jsruntime.\(source.id.prefix(12))",
            qos: .userInitiated
        )
    }

    deinit {
        let tasks = Array(activeTasks.values)
        tasks.forEach { $0.cancel() }
        activeTimers.values.forEach { $0.cancel() }
        pendingTimeouts.values.forEach { $0.cancel() }
        initTimeoutWorkItem?.cancel()
    }

    static func parseLenientURL(_ raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let direct = URL(string: trimmed) {
            return direct
        }
        // Encode non-ASCII / spaces / braces / pipes without double-encoding existing %XX escapes
        let chars = Array(trimmed.utf8)
        var encoded = ""
        encoded.reserveCapacity(chars.count + 16)
        var i = 0
        while i < chars.count {
            let b = chars[i]
            if b == 0x25, i + 2 < chars.count, isHexByte(chars[i + 1]), isHexByte(chars[i + 2]) {
                encoded.append("%")
                encoded.append(Character(UnicodeScalar(chars[i + 1])))
                encoded.append(Character(UnicodeScalar(chars[i + 2])))
                i += 3
                continue
            }
            if isSafeUrlAsciiByte(b) {
                encoded.append(Character(UnicodeScalar(b)))
            } else {
                encoded.append(String(format: "%%%02X", b))
            }
            i += 1
        }
        return URL(string: encoded)
    }

    private static func isHexByte(_ b: UInt8) -> Bool {
        (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x46) || (b >= 0x61 && b <= 0x66)
    }

    private static func isSafeUrlAsciiByte(_ b: UInt8) -> Bool {
        switch b {
        case 0x30...0x39, // 0-9
             0x41...0x5A, // A-Z
             0x61...0x7A, // a-z
             0x2D, 0x2E, 0x5F, 0x7E, // - . _ ~
             0x3A, 0x2F, 0x3F, 0x23, 0x5B, 0x5D, 0x40, // : / ? # [ ] @
             0x21, 0x24, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x3B, 0x3D: // ! $ & ' ( ) * + , ; =
            return true
        default:
            return false
        }
    }

    static func formatFriendlyError(_ raw: String?) -> String {
        let cleaned = (raw ?? "")
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first(where: { !$0.isEmpty }) ?? ""
        if cleaned.isEmpty { return "音源暂时不可用" }

        let lower = cleaned.lowercased()
        if lower.contains("timed out") || lower.contains("timeout") {
            return "请求超时，请检查网络连接"
        }
        if lower.contains("could not find host") ||
            lower.contains("cannot find host") ||
            lower.contains("hostname could not be found") ||
            lower.contains("unable to resolve host") ||
            lower.contains("offline") ||
            lower.contains("not connected to the internet") {
            return "无法连接音源服务器，请检查网络"
        }
        if lower.contains("could not connect to the server") ||
            lower.contains("connection refused") ||
            lower.contains("connection lost") ||
            lower.contains("reset by peer") {
            return "音源服务器连接失败"
        }
        if lower.contains("ssl") || lower.contains("certificate") || lower.contains("ats") {
            return "安全连接校验失败"
        }
        if lower.contains("syntaxerror") || lower.contains("referenceerror") || lower.contains("typeerror") {
            return "音源脚本运行异常"
        }
        if lower.contains("request event is not defined") {
            return "脚本未正确注册解析事件"
        }
        if lower == "failed" {
            return "音源暂不支持解析当前曲目"
        }
        let hasCJK = cleaned.unicodeScalars.contains { $0.value >= 0x4E00 && $0.value <= 0x9FFF }
        if cleaned.count > 56 && !hasCJK {
            return "音源响应异常，请稍后重试"
        }
        return String(cleaned.prefix(64))
    }

    static func loadPreloadScript() -> String? {
        if let url = Bundle.main.url(forResource: "user-api-preload", withExtension: "js"),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            return text
        }
        if let url = Bundle(for: LxJsRuntime.self).url(forResource: "user-api-preload", withExtension: "js"),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            return text
        }
        return nil
    }

    func initialize(timeoutSeconds: Double = 15.0) async -> ScriptInitResult {
        guard let preloadScript = Self.loadPreloadScript() else {
            return ScriptInitResult(success: false, errorMessage: "缺少内置脚本桥接文件")
        }

        let waiterId = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { cont in
                var shouldStartJs = false
                var timeoutWork: DispatchWorkItem?

                stateLock.lock()
                if isDestroyed {
                    stateLock.unlock()
                    cont.resume(returning: ScriptInitResult(success: false, errorMessage: "音源已停用"))
                    return
                }
                if let cached = initResultCached {
                    stateLock.unlock()
                    cont.resume(returning: cached)
                    return
                }
                if Task.isCancelled {
                    stateLock.unlock()
                    cont.resume(returning: ScriptInitResult(success: false, errorMessage: "已取消"))
                    return
                }

                initWaiters[waiterId] = cont
                if !initStarted {
                    initStarted = true
                    shouldStartJs = true
                    let work = DispatchWorkItem { [weak self] in
                        self?.completeInit(
                            ScriptInitResult(success: false, errorMessage: "脚本初始化超时 (\(Int(timeoutSeconds))s)")
                        )
                    }
                    initTimeoutWorkItem = work
                    timeoutWork = work
                }
                stateLock.unlock()

                if let timeoutWork {
                    DispatchQueue.global(qos: .userInitiated).asyncAfter(
                        deadline: .now() + max(timeoutSeconds, 0.1),
                        execute: timeoutWork
                    )
                }

                guard shouldStartJs else { return }

                jsQueue.async { [weak self] in
                    guard let self else { return }
                    if self.checkDestroyed() {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: "音源已停用"))
                        return
                    }

                    let vm = JSVirtualMachine()
                    guard let ctx = JSContext(virtualMachine: vm) else {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: "脚本引擎初始化失败"))
                        return
                    }

                    self.jsVM = vm
                    self.jsContext = ctx

                    var lastEvalException: String?
                    ctx.exceptionHandler = { _, exception in
                        lastEvalException = exception?.toString() ?? "JS 运行异常"
                    }

                    self.bindNativeBridge(ctx: ctx)

                    // Lightweight ES2021 Promise.any polyfill
                    ctx.evaluateScript(
                        """
                        if (typeof Promise !== 'undefined' && typeof Promise.any !== 'function') {
                          Promise.any = function(iterable) {
                            return new Promise(function(resolve, reject) {
                              var items = Array.from(iterable || []);
                              if (items.length === 0) return reject(new Error('All promises were rejected'));
                              var errors = [], pending = items.length;
                              items.forEach(function(item, idx) {
                                Promise.resolve(item).then(resolve, function(err) {
                                  errors[idx] = err;
                                  if (--pending === 0) reject(new Error('All promises were rejected'));
                                });
                              });
                            });
                          };
                        }
                        """
                    )

                    ctx.evaluateScript(preloadScript, withSourceURL: URL(string: "lxpure://user-api-preload.js"))
                    if let err = lastEvalException {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                        return
                    }

                    let normalizedScript = LxScriptParser.normalizeScript(self.source.script)
                    if let setupFn = ctx.objectForKeyedSubscript("lx_setup"), !setupFn.isUndefined {
                        setupFn.call(withArguments: [
                            self.secretKey,
                            self.source.id,
                            self.source.name,
                            self.source.description,
                            self.source.version,
                            self.source.author,
                            self.source.homepage,
                            normalizedScript
                        ])
                    } else {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: "脚本桥接初始化失败"))
                        return
                    }

                    if let err = lastEvalException {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                        return
                    }

                    ctx.evaluateScript(
                        normalizedScript,
                        withSourceURL: URL(string: "lxpure://\(self.source.id).js")
                    )

                    if let err = lastEvalException {
                        self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                    }
                }
            }
        } onCancel: { [weak self] in
            self?.cancelInitWaiter(waiterId: waiterId)
        }
    }

    private func bindNativeBridge(ctx: JSContext) {
        let consoleLog: @convention(block) (JSValue?) -> Void = { _ in }
        let consoleObj = JSValue(newObjectIn: ctx)
        consoleObj?.setObject(unsafeBitCast(consoleLog, to: AnyObject.self), forKeyedSubscript: "log" as NSString)
        consoleObj?.setObject(unsafeBitCast(consoleLog, to: AnyObject.self), forKeyedSubscript: "info" as NSString)
        consoleObj?.setObject(unsafeBitCast(consoleLog, to: AnyObject.self), forKeyedSubscript: "warn" as NSString)
        consoleObj?.setObject(unsafeBitCast(consoleLog, to: AnyObject.self), forKeyedSubscript: "error" as NSString)
        ctx.setObject(consoleObj, forKeyedSubscript: "console" as NSString)

        let nativeCall: @convention(block) (JSValue?, JSValue?, JSValue?) -> Void = { [weak self] keyVal, actionVal, dataVal in
            guard let self else { return }
            let key = Self.jsString(keyVal)
            guard key == self.secretKey else { return }
            let action = Self.jsString(actionVal)
            let dataJson = Self.jsString(dataVal)
            self.handleScriptNativeCall(action: action, dataJson: dataJson)
        }
        ctx.setObject(unsafeBitCast(nativeCall, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__" as NSString)

        let str2b64: @convention(block) (JSValue?) -> String = { val in
            LxCrypto.str2b64(Self.jsString(val))
        }
        ctx.setObject(unsafeBitCast(str2b64, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_str2b64" as NSString)

        let btoaBlock: @convention(block) (JSValue?) -> String = { val in
            let raw = Self.jsString(val)
            let data = raw.data(using: .isoLatin1) ?? Data(raw.utf8)
            return data.base64EncodedString()
        }
        ctx.setObject(unsafeBitCast(btoaBlock, to: AnyObject.self), forKeyedSubscript: "btoa" as NSString)

        let atobBlock: @convention(block) (JSValue?) -> String = { val in
            let raw = Self.jsString(val)
            guard let data = LxCrypto.decodeBase64Flexible(raw) else { return "" }
            return String(data: data, encoding: .isoLatin1) ?? ""
        }
        ctx.setObject(unsafeBitCast(atobBlock, to: AnyObject.self), forKeyedSubscript: "atob" as NSString)

        let b642buf: @convention(block) (JSValue?) -> String = { val in
            LxCrypto.b64ToJsonByteArray(Self.jsString(val))
        }
        ctx.setObject(unsafeBitCast(b642buf, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_b642buf" as NSString)

        let str2md5: @convention(block) (JSValue?) -> String = { val in
            let raw = Self.jsString(val)
            let decoded = raw.removingPercentEncoding ?? raw
            return LxCrypto.md5Hex(decoded)
        }
        ctx.setObject(unsafeBitCast(str2md5, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_str2md5" as NSString)

        let aesEncrypt: @convention(block) (JSValue?, JSValue?, JSValue?, JSValue?) -> String = { dataVal, keyVal, ivVal, modeVal in
            LxCrypto.aesEncryptBase64(
                dataB64: Self.jsString(dataVal),
                keyB64: Self.jsString(keyVal),
                ivB64: Self.jsString(ivVal),
                mode: Self.jsString(modeVal)
            )
        }
        ctx.setObject(unsafeBitCast(aesEncrypt, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_aes_encrypt" as NSString)

        let rsaEncrypt: @convention(block) (JSValue?, JSValue?, JSValue?) -> String = { dataVal, keyVal, padVal in
            LxCrypto.rsaEncryptBase64(
                dataB64: Self.jsString(dataVal),
                publicKeyPemBody: Self.jsString(keyVal),
                padding: Self.jsString(padVal)
            )
        }
        ctx.setObject(unsafeBitCast(rsaEncrypt, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_rsa_encrypt" as NSString)

        let zlibInflate: @convention(block) (JSValue?) -> String = { val in
            LxCrypto.zlibInflateBase64(Self.jsString(val))
        }
        ctx.setObject(unsafeBitCast(zlibInflate, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_zlib_inflate" as NSString)

        let zlibDeflate: @convention(block) (JSValue?) -> String = { val in
            LxCrypto.zlibDeflateBase64(Self.jsString(val))
        }
        ctx.setObject(unsafeBitCast(zlibDeflate, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__utils_zlib_deflate" as NSString)

        let setTimeoutBlock: @convention(block) (JSValue?, JSValue?) -> Void = { [weak self] idVal, delayVal in
            guard let self else { return }
            let timerId = Int64(idVal?.toInt32() ?? 0)
            let rawDelay = Double(delayVal?.toInt32() ?? 0)
            let delaySec = min(max(rawDelay, 0.0), 60_000.0) / 1000.0

            let work = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.stateLock.lock()
                self.activeTimers.removeValue(forKey: timerId)
                let destroyedNow = self.isDestroyed
                self.stateLock.unlock()
                guard !destroyedNow else { return }
                self.callJs(action: "__set_timeout__", dataJson: String(timerId))
            }

            self.stateLock.lock()
            if self.isDestroyed || self.activeTimers.count >= Self.maxActiveTimers {
                self.stateLock.unlock()
                return
            }
            let old = self.activeTimers.updateValue(work, forKey: timerId)
            self.stateLock.unlock()
            old?.cancel()

            self.jsQueue.asyncAfter(deadline: .now() + delaySec, execute: work)
        }
        ctx.setObject(unsafeBitCast(setTimeoutBlock, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__set_timeout" as NSString)

        let clearTimeoutBlock: @convention(block) (JSValue?) -> Void = { [weak self] idVal in
            guard let self else { return }
            let timerId = Int64(idVal?.toInt32() ?? 0)
            self.stateLock.lock()
            let work = self.activeTimers.removeValue(forKey: timerId)
            self.stateLock.unlock()
            work?.cancel()
        }
        ctx.setObject(unsafeBitCast(clearTimeoutBlock, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__clear_timeout" as NSString)
    }

    private func handleScriptNativeCall(action: String, dataJson: String) {
        switch action {
        case "init":
            let obj = parseJsonObject(dataJson)
            let ok = (obj?["status"] as? Bool) == true
            let errMsg = (obj?["errorMessage"] as? String).map { Self.formatFriendlyError($0) }
            var platforms: [String: [String]] = [:]
            if let info = obj?["info"] as? [String: Any],
               let sourcesObj = info["sources"] as? [String: Any] {
                for (platformCode, rawVal) in sourcesObj {
                    guard let pObj = rawVal as? [String: Any] else { continue }
                    let actions = pObj["actions"] as? [String] ?? []
                    let qualities = pObj["qualitys"] as? [String] ?? []
                    if actions.contains("musicUrl") {
                        platforms[platformCode] = qualities
                    }
                }
            }
            let effectiveSuccess = ok && !platforms.isEmpty
            let effectiveErr: String?
            if effectiveSuccess {
                effectiveErr = nil
            } else if let errMsg, !errMsg.isEmpty {
                effectiveErr = errMsg
            } else if ok && platforms.isEmpty {
                effectiveErr = "音源未声明可用音乐平台"
            } else {
                effectiveErr = "初始化失败"
            }
            completeInit(ScriptInitResult(
                success: effectiveSuccess,
                supportedPlatforms: effectiveSuccess ? platforms : [:],
                errorMessage: effectiveErr
            ))

        case "request":
            guard let obj = parseJsonObject(dataJson),
                  let requestKey = obj["requestKey"] as? String else { return }
            guard let urlStr = obj["url"] as? String, !urlStr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                sendHttpErrorResponse(requestKey: requestKey, error: "Invalid URL")
                return
            }
            let options = obj["options"] as? [String: Any]
            executeHttpForScript(requestKey: requestKey, urlString: urlStr, options: options)

        case "cancelRequest":
            if let data = dataJson.data(using: .utf8),
               let key = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) as? String {
                cancelActiveTask(requestKey: key)
            }

        case "response":
            guard let obj = parseJsonObject(dataJson),
                  let requestKey = obj["requestKey"] as? String else { return }
            let status = (obj["status"] as? Bool) == true
            if status {
                completePendingAction(requestKey: requestKey, result: .success(obj["result"]))
            } else {
                let err = Self.formatFriendlyError((obj["errorMessage"] as? String) ?? "音源解析失败")
                completePendingAction(requestKey: requestKey, result: .failure(LxRuntimeError.message(err)))
            }

        case "showUpdateAlert":
            break

        default:
            break
        }
    }

    private func executeHttpForScript(requestKey: String, urlString: String, options: [String: Any]?) {
        guard let url = Self.parseLenientURL(urlString) else {
            sendHttpErrorResponse(requestKey: requestKey, error: "Invalid URL")
            return
        }

        let method = ((options?["method"] as? String) ?? "GET").uppercased()
        let rawTimeoutMs = (options?["timeout"] as? NSNumber)?.doubleValue ?? 8_000.0
        let timeoutSec = min(max(rawTimeoutMs, 1_000.0), 12_000.0) / 1000.0
        let isBinary = (options?["binary"] as? Bool) == true
        let followMax = (options?["follow_max"] as? NSNumber)?.intValue

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeoutSec)
        request.httpMethod = method
        request.httpShouldHandleCookies = false

        var hasUserAgent = false
        var explicitContentType: String?
        if let headers = options?["headers"] as? [String: Any] {
            for (k, v) in headers {
                let strVal: String
                if let s = v as? String {
                    strVal = s
                } else if let n = v as? NSNumber {
                    strVal = Self.stringifyJsonScalar(n)
                } else {
                    continue
                }
                if k.caseInsensitiveCompare("User-Agent") == .orderedSame { hasUserAgent = true }
                if k.caseInsensitiveCompare("Content-Type") == .orderedSame { explicitContentType = strVal }
                request.setValue(strVal, forHTTPHeaderField: k)
            }
        }
        if !hasUserAgent {
            request.setValue(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                forHTTPHeaderField: "User-Agent"
            )
        }

        if ["POST", "PUT", "PATCH", "DELETE"].contains(method) {
            if let form = options?["form"] as? [String: Any] {
                var pairs: [String] = []
                pairs.reserveCapacity(form.count)
                for (k, v) in form {
                    if v is NSNull { continue }
                    let valStr = Self.stringifyFormValue(v)
                    let encK = k.addingPercentEncoding(withAllowedCharacters: Self.formAllowedChars) ?? k
                    let encV = valStr.addingPercentEncoding(withAllowedCharacters: Self.formAllowedChars) ?? valStr
                    pairs.append("\(encK)=\(encV)")
                }
                request.httpBody = Data(pairs.joined(separator: "&").utf8)
                if explicitContentType == nil {
                    request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
                }
            } else if let formStr = options?["form"] as? String {
                request.httpBody = Data(formStr.utf8)
                if explicitContentType == nil {
                    request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
                }
            } else if let formData = options?["formData"] as? [String: Any] {
                let boundary = "Boundary-\(UUID().uuidString)"
                var body = Data()
                for (k, v) in formData {
                    if v is NSNull { continue }
                    let valStr = Self.stringifyFormValue(v)
                    body.append(Data("--\(boundary)\r\n".utf8))
                    body.append(Data("Content-Disposition: form-data; name=\"\(k)\"\r\n\r\n".utf8))
                    body.append(Data("\(valStr)\r\n".utf8))
                }
                body.append(Data("--\(boundary)--\r\n".utf8))
                request.httpBody = body
                if explicitContentType == nil {
                    request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
                }
            } else if let bodyObj = options?["body"], !(bodyObj is NSNull) {
                if let bodyStr = bodyObj as? String {
                    request.httpBody = Data(bodyStr.utf8)
                } else if JSONSerialization.isValidJSONObject(bodyObj),
                          let data = try? JSONSerialization.data(withJSONObject: bodyObj) {
                    request.httpBody = data
                }
                if explicitContentType == nil {
                    request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
                }
            } else {
                request.httpBody = Data()
            }
        }

        let targetSession = (followMax == 0) ? Self.noRedirectSession : session
        let task = targetSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            let wasCancelled = self.finishActiveTask(requestKey: requestKey)
            guard !wasCancelled, !self.checkDestroyed() else { return }

            if let error {
                self.sendHttpErrorResponse(requestKey: requestKey, error: error.localizedDescription)
                return
            }

            let httpResp = response as? HTTPURLResponse
            let statusCode = httpResp?.statusCode ?? 200
            let statusMessage = HTTPURLResponse.localizedString(forStatusCode: statusCode)
            var respHeaders: [String: String] = [:]
            if let allHeaders = httpResp?.allHeaderFields {
                for (k, v) in allHeaders {
                    respHeaders["\(k)".lowercased()] = "\(v)"
                }
            }

            let rawData = data ?? Data()
            let bodyData = rawData.count > Self.maxHttpResponseBytes
                ? rawData.prefix(Self.maxHttpResponseBytes)
                : rawData

            let bodyPayload: Any
            if isBinary {
                bodyPayload = bodyData.base64EncodedString()
            } else {
                var text = Self.decodeTextData(bodyData, encodingName: httpResp?.textEncodingName)
                if text.unicodeScalars.first == "\u{FEFF}" {
                    text = String(text.unicodeScalars.dropFirst())
                }
                let contentType = (httpResp?.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                let shouldParseJson = contentType.contains("json") ||
                    trimmed.hasPrefix("{") ||
                    trimmed.hasPrefix("[")
                if shouldParseJson,
                   let jsonData = text.data(using: .utf8),
                   let parsed = try? JSONSerialization.jsonObject(with: jsonData, options: []) {
                    bodyPayload = parsed
                } else {
                    bodyPayload = text
                }
            }

            let payload: [String: Any] = [
                "requestKey": requestKey,
                "error": NSNull(),
                "response": [
                    "statusCode": statusCode,
                    "statusMessage": statusMessage,
                    "headers": respHeaders,
                    "binary": isBinary,
                    "body": bodyPayload
                ]
            ]

            if let jsonStr = self.stringifyJson(payload) {
                self.postCallJs(action: "response", dataJson: jsonStr)
            } else {
                self.sendHttpErrorResponse(requestKey: requestKey, error: "Response serialization failed")
            }
        }

        stateLock.lock()
        if isDestroyed || cancelledRequests.remove(requestKey) != nil {
            stateLock.unlock()
            return
        }
        activeTasks[requestKey] = task
        stateLock.unlock()
        task.resume()
    }

    private static func stringifyFormValue(_ val: Any) -> String {
        if let s = val as? String { return s }
        if let n = val as? NSNumber { return stringifyJsonScalar(n) }
        return "\(val)"
    }

    private static func stringifyJsonScalar(_ n: NSNumber) -> String {
        if CFGetTypeID(n) == CFBooleanGetTypeID() {
            return n.boolValue ? "true" : "false"
        }
        return n.stringValue
    }

    private static func decodeTextData(_ data: Data, encodingName: String?) -> String {
        if let encodingName, !encodingName.isEmpty {
            let cfEnc = CFStringConvertIANACharSetNameToEncoding(encodingName as CFString)
            if cfEnc != kCFStringEncodingInvalidId {
                let nsEnc = CFStringConvertEncodingToNSStringEncoding(cfEnc)
                if let decoded = String(data: data, encoding: String.Encoding(rawValue: nsEnc)) {
                    return decoded
                }
            }
        }
        return String(decoding: data, as: UTF8.self)
    }

    private func sendHttpErrorResponse(requestKey: String, error: String) {
        let payload: [String: Any] = [
            "requestKey": requestKey,
            "error": error,
            "response": NSNull()
        ]
        if let jsonStr = stringifyJson(payload) {
            postCallJs(action: "response", dataJson: jsonStr)
        }
    }

    func requestMusicUrl(track: Track, quality: AudioQuality, timeoutSeconds: Double = 4.5) async -> Result<String, Error> {
        if checkDestroyed() {
            return .failure(LxRuntimeError.message("音源已卸载"))
        }
        if Task.isCancelled {
            return .failure(CancellationError())
        }

        let requestKey = "req_\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))"
        let payload: [String: Any] = [
            "requestKey": requestKey,
            "data": [
                "source": track.source.code,
                "action": "musicUrl",
                "info": [
                    "type": quality.lxType,
                    "musicInfo": track.toLxMusicInfoDictionary()
                ]
            ]
        ]

        guard let payloadJson = stringifyJson(payload) else {
            return .failure(LxRuntimeError.message("请求参数编码失败"))
        }

        let rawResult: Result<Any?, Error> = await withTaskCancellationHandler {
            await withCheckedContinuation { cont in
                stateLock.lock()
                if isDestroyed {
                    stateLock.unlock()
                    cont.resume(returning: .failure(LxRuntimeError.message("音源已卸载")))
                    return
                }
                if Task.isCancelled {
                    stateLock.unlock()
                    cont.resume(returning: .failure(CancellationError()))
                    return
                }
                pendingActions[requestKey] = cont
                let timeoutWork = DispatchWorkItem { [weak self] in
                    self?.completePendingAction(
                        requestKey: requestKey,
                        result: .failure(LxRuntimeError.message("请求超时，请检查网络连接"))
                    )
                }
                pendingTimeouts[requestKey] = timeoutWork
                stateLock.unlock()

                DispatchQueue.global(qos: .userInitiated).asyncAfter(
                    deadline: .now() + timeoutSeconds,
                    execute: timeoutWork
                )

                postCallJs(action: "request", dataJson: payloadJson)
            }
        } onCancel: { [weak self] in
            self?.completePendingAction(
                requestKey: requestKey,
                result: .failure(CancellationError())
            )
        }

        switch rawResult {
        case .success(let resultObj):
            if let dict = resultObj as? [String: Any],
               let dataDict = dict["data"] as? [String: Any],
               let rawUrl = dataDict["url"] as? String {
                let trimmedUrl = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
                let lower = trimmedUrl.lowercased()
                if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
                    return .success(trimmedUrl)
                }
            }
            return .failure(LxRuntimeError.message("未返回有效音频直链"))
        case .failure(let error):
            if error is CancellationError {
                return .failure(error)
            }
            return .failure(LxRuntimeError.message(Self.formatFriendlyError(error.localizedDescription)))
        }
    }

    func destroy() {
        stateLock.lock()
        if isDestroyed {
            stateLock.unlock()
            return
        }
        isDestroyed = true
        let tasksToCancel = Array(activeTasks.values)
        activeTasks.removeAll()
        cancelledRequests.removeAll()
        let timersToCancel = Array(activeTimers.values)
        activeTimers.removeAll()
        let timeoutsToCancel = Array(pendingTimeouts.values)
        pendingTimeouts.removeAll()
        let initTimeout = initTimeoutWorkItem
        initTimeoutWorkItem = nil
        let pendingToFail = Array(pendingActions.values)
        pendingActions.removeAll()
        let waiters = Array(initWaiters.values)
        initWaiters.removeAll()
        stateLock.unlock()

        initTimeout?.cancel()
        timersToCancel.forEach { $0.cancel() }
        timeoutsToCancel.forEach { $0.cancel() }
        tasksToCancel.forEach { $0.cancel() }
        pendingToFail.forEach {
            $0.resume(returning: .failure(LxRuntimeError.message("音源已切换或停止")))
        }
        waiters.forEach {
            $0.resume(returning: ScriptInitResult(success: false, errorMessage: "音源已停用"))
        }

        // Strongly capture self in this one-shot teardown block so JSVirtualMachine/JSContext
        // always deallocate off-main on jsQueue rather than synchronously on @MainActor.
        jsQueue.async {
            self.jsContext?.exceptionHandler = nil
            self.jsContext = nil
            self.jsVM = nil
        }
    }

    private func completeInit(_ result: ScriptInitResult) {
        stateLock.lock()
        if initResultCached != nil {
            stateLock.unlock()
            return
        }
        initResultCached = result
        let timeoutWork = initTimeoutWorkItem
        initTimeoutWorkItem = nil
        let waiters = Array(initWaiters.values)
        initWaiters.removeAll()
        stateLock.unlock()

        timeoutWork?.cancel()
        waiters.forEach { $0.resume(returning: result) }
    }

    private func cancelInitWaiter(waiterId: UUID) {
        stateLock.lock()
        let cont = initWaiters.removeValue(forKey: waiterId)
        stateLock.unlock()
        cont?.resume(returning: ScriptInitResult(success: false, errorMessage: "已取消"))
    }

    private func completePendingAction(requestKey: String, result: Result<Any?, Error>) {
        let shouldCancelOrphans: Bool
        if case .failure = result {
            shouldCancelOrphans = true
        } else {
            shouldCancelOrphans = false
        }

        stateLock.lock()
        let cont = pendingActions.removeValue(forKey: requestKey)
        let timeoutWork = pendingTimeouts.removeValue(forKey: requestKey)
        var orphanedTasks: [URLSessionDataTask] = []
        if shouldCancelOrphans && pendingActions.isEmpty && initResultCached != nil {
            for (k, task) in activeTasks {
                cancelledRequests.insert(k)
                orphanedTasks.append(task)
            }
            activeTasks.removeAll()
        }
        stateLock.unlock()
        timeoutWork?.cancel()
        orphanedTasks.forEach { $0.cancel() }
        cont?.resume(returning: result)
    }

    private func cancelActiveTask(requestKey: String) {
        stateLock.lock()
        cancelledRequests.insert(requestKey)
        let task = activeTasks.removeValue(forKey: requestKey)
        stateLock.unlock()
        task?.cancel()
    }

    private func finishActiveTask(requestKey: String) -> Bool {
        stateLock.lock()
        activeTasks.removeValue(forKey: requestKey)
        let wasCancelled = cancelledRequests.remove(requestKey) != nil
        stateLock.unlock()
        return wasCancelled
    }

    private func checkDestroyed() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return isDestroyed
    }

    private func postCallJs(action: String, dataJson: String) {
        if checkDestroyed() { return }
        jsQueue.async { [weak self] in
            guard let self, !self.checkDestroyed() else { return }
            self.callJs(action: action, dataJson: dataJson)
        }
    }

    private func callJs(action: String, dataJson: String? = nil) {
        guard let ctx = jsContext,
              let fn = ctx.objectForKeyedSubscript("__lx_native__"),
              !fn.isUndefined else { return }
        if let dataJson {
            fn.call(withArguments: [secretKey, action, dataJson])
        } else {
            fn.call(withArguments: [secretKey, action])
        }
    }

    private static func jsString(_ val: JSValue?) -> String {
        guard let val, !val.isUndefined, !val.isNull else { return "" }
        return val.toString() ?? ""
    }

    private func parseJsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private func stringifyJson(_ obj: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(obj),
              let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
