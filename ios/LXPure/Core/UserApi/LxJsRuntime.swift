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

/// Isolated single-queue JavaScriptCore sandbox compatible with LX Music Mobile custom `.js` scripts (v2).
final class LxJsRuntime: @unchecked Sendable {
    private let session: URLSession
    private let source: UserApiSource
    private let secretKey = UUID().uuidString
    private let jsQueue: DispatchQueue
    private let stateLock = NSLock()

    private var jsVM: JSVirtualMachine?
    private var jsContext: JSContext?
    private var isDestroyed = false

    private var initContinuation: CheckedContinuation<ScriptInitResult, Never>?
    private var initResultCached: ScriptInitResult?

    private var pendingActions: [String: CheckedContinuation<Result<Any?, Error>, Never>] = [:]
    private var activeTasks: [String: URLSessionDataTask] = [:]

    init(session: URLSession, source: UserApiSource) {
        self.session = session
        self.source = source
        self.jsQueue = DispatchQueue(
            label: "com.lxpure.jsruntime.\(source.id.prefix(12))",
            qos: .userInitiated
        )
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

        return await withCheckedContinuation { cont in
            let timeoutItem = DispatchWorkItem { [weak self] in
                self?.completeInit(
                    ScriptInitResult(success: false, errorMessage: "脚本初始化超时 (\(Int(timeoutSeconds))s)")
                )
            }

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
            initContinuation = cont
            stateLock.unlock()

            DispatchQueue.global(qos: .userInitiated).asyncAfter(
                deadline: .now() + timeoutSeconds,
                execute: timeoutItem
            )

            jsQueue.async { [weak self] in
                guard let self else {
                    timeoutItem.cancel()
                    return
                }
                if self.checkDestroyed() {
                    timeoutItem.cancel()
                    self.completeInit(ScriptInitResult(success: false, errorMessage: "音源已停用"))
                    return
                }

                let vm = JSVirtualMachine()
                guard let ctx = JSContext(virtualMachine: vm) else {
                    timeoutItem.cancel()
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
                    timeoutItem.cancel()
                    self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                    return
                }

                if let setupFn = ctx.objectForKeyedSubscript("lx_setup"), !setupFn.isUndefined {
                    setupFn.call(withArguments: [
                        self.secretKey,
                        self.source.id,
                        self.source.name,
                        self.source.description,
                        self.source.version,
                        self.source.author,
                        self.source.homepage,
                        self.source.script
                    ])
                } else {
                    timeoutItem.cancel()
                    self.completeInit(ScriptInitResult(success: false, errorMessage: "脚本桥接初始化失败"))
                    return
                }

                if let err = lastEvalException {
                    timeoutItem.cancel()
                    self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                    return
                }

                ctx.evaluateScript(
                    self.source.script,
                    withSourceURL: URL(string: "lxpure://\(self.source.id).js")
                )

                if let err = lastEvalException {
                    timeoutItem.cancel()
                    self.completeInit(ScriptInitResult(success: false, errorMessage: Self.formatFriendlyError(err)))
                }
            }
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

        let setTimeoutBlock: @convention(block) (JSValue?, JSValue?) -> Void = { [weak self] idVal, delayVal in
            guard let self else { return }
            let timerId = Int64(idVal?.toInt32() ?? 0)
            let rawDelay = Double(delayVal?.toInt32() ?? 0)
            let delaySec = min(max(rawDelay, 0.0), 60_000.0) / 1000.0
            self.jsQueue.asyncAfter(deadline: .now() + delaySec) { [weak self] in
                guard let self, !self.checkDestroyed() else { return }
                self.callJs(action: "__set_timeout__", dataJson: String(timerId))
            }
        }
        ctx.setObject(unsafeBitCast(setTimeoutBlock, to: AnyObject.self), forKeyedSubscript: "__lx_native_call__set_timeout" as NSString)
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
            completeInit(ScriptInitResult(success: ok, supportedPlatforms: platforms, errorMessage: errMsg))

        case "request":
            guard let obj = parseJsonObject(dataJson),
                  let requestKey = obj["requestKey"] as? String,
                  let urlStr = obj["url"] as? String else { return }
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
        guard let url = URL(string: urlString) else {
            sendHttpErrorResponse(requestKey: requestKey, error: "Invalid URL")
            return
        }

        let method = ((options?["method"] as? String) ?? "GET").uppercased()
        let rawTimeoutMs = (options?["timeout"] as? NSNumber)?.doubleValue ?? 8_000.0
        let timeoutSec = min(max(rawTimeoutMs, 1_000.0), 12_000.0) / 1000.0
        let isBinary = (options?["binary"] as? Bool) == true

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeoutSec)
        request.httpMethod = method

        var hasUserAgent = false
        var explicitContentType: String?
        if let headers = options?["headers"] as? [String: Any] {
            for (k, v) in headers {
                let strVal: String
                if let s = v as? String {
                    strVal = s
                } else if let n = v as? NSNumber {
                    strVal = n.stringValue
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
                var components = URLComponents()
                components.queryItems = form.map { k, v in
                    URLQueryItem(name: k, value: "\(v)")
                }
                let encoded = components.percentEncodedQuery ?? ""
                request.httpBody = Data(encoded.utf8)
                if explicitContentType == nil {
                    request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")
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

        let task = session.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            self.removeActiveTask(requestKey: requestKey)
            guard !self.checkDestroyed() else { return }

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

            let bodyData = data ?? Data()
            let bodyPayload: Any
            if isBinary {
                bodyPayload = bodyData.map { Int($0) }
            } else {
                let text = String(data: bodyData, encoding: .utf8) ?? ""
                if let parsed = try? JSONSerialization.jsonObject(with: bodyData, options: [.fragmentsAllowed]) {
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
                    "body": bodyPayload
                ]
            ]

            if let jsonStr = self.stringifyJson(payload) {
                self.postCallJs(action: "response", dataJson: jsonStr)
            }
        }

        stateLock.lock()
        if isDestroyed {
            stateLock.unlock()
            return
        }
        activeTasks[requestKey] = task
        stateLock.unlock()
        task.resume()
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
                pendingActions[requestKey] = cont
                stateLock.unlock()

                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + timeoutSeconds) { [weak self] in
                    self?.completePendingAction(
                        requestKey: requestKey,
                        result: .failure(LxRuntimeError.message("请求超时，请检查网络连接"))
                    )
                }

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
               let url = dataDict["url"] as? String,
               url.hasPrefix("http") {
                return .success(url)
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
        let pendingToFail = Array(pendingActions.values)
        pendingActions.removeAll()
        let pendingInit = initContinuation
        initContinuation = nil
        stateLock.unlock()

        tasksToCancel.forEach { $0.cancel() }
        pendingToFail.forEach {
            $0.resume(returning: .failure(LxRuntimeError.message("音源已切换或停止")))
        }
        pendingInit?.resume(returning: ScriptInitResult(success: false, errorMessage: "音源已停用"))

        jsQueue.async { [weak self] in
            guard let self else { return }
            self.jsContext?.exceptionHandler = nil
            self.jsContext = nil
            self.jsVM = nil
        }
    }

    private func completeInit(_ result: ScriptInitResult) {
        stateLock.lock()
        initResultCached = result
        let cont = initContinuation
        initContinuation = nil
        stateLock.unlock()
        cont?.resume(returning: result)
    }

    private func completePendingAction(requestKey: String, result: Result<Any?, Error>) {
        stateLock.lock()
        let cont = pendingActions.removeValue(forKey: requestKey)
        stateLock.unlock()
        cont?.resume(returning: result)
    }

    private func cancelActiveTask(requestKey: String) {
        stateLock.lock()
        let task = activeTasks.removeValue(forKey: requestKey)
        stateLock.unlock()
        task?.cancel()
    }

    private func removeActiveTask(requestKey: String) {
        stateLock.lock()
        activeTasks.removeValue(forKey: requestKey)
        stateLock.unlock()
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
