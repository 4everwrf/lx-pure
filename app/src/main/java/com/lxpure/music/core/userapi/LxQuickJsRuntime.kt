package com.lxpure.music.core.userapi

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.lxpure.music.core.musicsdk.asBooleanOrNull
import com.lxpure.music.core.musicsdk.asIntOrNull
import com.lxpure.music.core.musicsdk.asJsonArrayOrNull
import com.lxpure.music.core.musicsdk.asJsonObjectOrNull
import com.lxpure.music.core.musicsdk.asStringOrNull
import com.lxpure.music.domain.model.AudioQuality
import com.lxpure.music.domain.model.Track
import com.lxpure.music.domain.model.UserApiSource
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ScriptInitResult(
    val success: Boolean,
    val supportedPlatforms: Map<String, List<String>> = emptyMap(),
    val errorMessage: String? = null
)

/**
 * Isolated single-threaded QuickJS sandbox compatible with LX Music Mobile custom `.js` scripts (v2).
 */
class LxQuickJsRuntime(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val source: UserApiSource
) {
    companion object {
        private const val TAG = "LxQuickJsRuntime"
        private const val MAX_ACTIVE_TIMERS = 256
        private const val MAX_HTTP_RESPONSE_BYTES = 4 * 1024 * 1024 // 4 MB safety cap
        private val loaderLock = Any()

        @Volatile
        private var quickJsLoaded = false

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val strictJson = Json { ignoreUnknownKeys = true; isLenient = false }

        /**
         * Guarantees QuickJS native `.so` library finishes loading synchronously exactly once
         * before any thread attempts to invoke [QuickJSContext.create].
         */
        fun ensureQuickJsLoaded() {
            if (quickJsLoaded) return
            synchronized(loaderLock) {
                if (!quickJsLoaded) {
                    QuickJSLoader.init()
                    quickJsLoaded = true
                }
            }
        }

        /**
         * Converts raw QuickJS / OkHttp / JNI exception messages into concise user-friendly Chinese copy.
         */
        fun formatFriendlyError(raw: String?): String {
            val cleaned = raw.orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (cleaned.isEmpty()) return "音源暂时不可用"

            val lower = cleaned.lowercase(Locale.ROOT)
            return when {
                lower.contains("the so library must be initialized") ||
                    lower.contains("no implementation found") ||
                    lower.contains("unsatisfiedlinkerror") ->
                    "脚本引擎加载异常，请重新初始化"

                lower.contains("timed out") || lower.contains("timeout") ->
                    "请求超时，请检查网络连接"

                lower.contains("unable to resolve host") ||
                    lower.contains("unknownhost") ||
                    lower.contains("no address associated") ->
                    "无法连接音源服务器，请检查网络"

                lower.contains("failed to connect") ||
                    lower.contains("connection refused") ||
                    lower.contains("connectexception") ||
                    lower.contains("reset by peer") ||
                    lower.contains("eofexception") ->
                    "音源服务器连接失败"

                lower.contains("ssl") ||
                    lower.contains("certpath") ||
                    lower.contains("handshake") ||
                    lower.contains("hostname") ->
                    "安全连接校验失败"

                lower.contains("syntaxerror") ||
                    lower.contains("referenceerror") ||
                    lower.contains("typeerror") ->
                    "音源脚本运行异常"

                lower.contains("request event is not defined") ->
                    "脚本未正确注册解析事件"

                lower == "failed" ->
                    "音源暂不支持解析当前曲目"

                cleaned.length > 56 && cleaned.none { it.code > 0x4E00 } ->
                    "音源响应异常，请稍后重试"

                else -> cleaned.take(64)
            }
        }

        private fun readBoundedBytes(body: ResponseBody?, maxBytes: Int): ByteArray {
            if (body == null) return ByteArray(0)
            body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val remaining = maxBytes - total
                    if (remaining <= 0) break
                    val toWrite = minOf(n, remaining)
                    out.write(buf, 0, toWrite)
                    total += toWrite
                    if (total >= maxBytes) break
                }
                return out.toByteArray()
            }
        }
    }

    private val secretKey = UUID.randomUUID().toString()
    private val thread = HandlerThread("LxQuickJS-${source.id.take(8)}").apply { start() }
    private val jsHandler = Handler(thread.looper)
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var jsContext: QuickJSContext? = null
    private var nativeBridgeFn: JSFunction? = null
    private val destroyed = AtomicBoolean(false)
    private val initDeferred = CompletableDeferred<ScriptInitResult>()
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement?>>>()
    private val activeHttpCalls = ConcurrentHashMap<String, Call>()
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()
    private val activeTimers = ConcurrentHashMap<Long, Runnable>()

    fun hasActiveRequests(): Boolean =
        pendingActions.isNotEmpty() || activeHttpCalls.isNotEmpty() || (!destroyed.get() && !initDeferred.isCompleted)

    suspend fun initialize(timeoutMs: Long = 15_000L): ScriptInitResult {
        runCatching { ensureQuickJsLoaded() }.onFailure { t ->
            Log.e(TAG, "QuickJSLoader.init() failed", t)
            return ScriptInitResult(false, errorMessage = formatFriendlyError(t.message))
        }

        jsHandler.post {
            if (destroyed.get()) {
                initDeferred.complete(ScriptInitResult(false, errorMessage = "音源已停用"))
                return@post
            }
            try {
                ensureQuickJsLoaded()
                val preloadScript = context.assets.open("script/user-api-preload.js")
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }

                val ctx = QuickJSContext.create()
                jsContext = ctx

                ctx.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String?) { Log.d(TAG, "[${source.name}] $info") }
                    override fun info(info: String?) { Log.i(TAG, "[${source.name}] $info") }
                    override fun warn(info: String?) { Log.w(TAG, "[${source.name}] $info") }
                    override fun error(info: String?) { Log.e(TAG, "[${source.name}] $info") }
                })

                bindNativeBridge(ctx)
                // Lightweight ES2021 Promise.any polyfill for QuickJS compatibility
                ctx.evaluate(
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
                    """.trimIndent(),
                    "es-polyfill.js"
                )
                ctx.evaluate(preloadScript, "user-api-preload.js")
                val normalizedScript = LxScriptParser.normalizeScript(source.script)
                val setupFn = ctx.globalObject.getJSFunction("lx_setup")
                try {
                    setupFn.call(
                        secretKey,
                        source.id,
                        source.name,
                        source.description,
                        source.version,
                        source.author,
                        source.homepage,
                        normalizedScript
                    )
                } finally {
                    runCatching { setupFn.release() }
                }
                nativeBridgeFn = runCatching { ctx.globalObject.getJSFunction("__lx_native__") }.getOrNull()
                ctx.evaluate(normalizedScript, "${source.name}.js")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize script ${source.name}", t)
                if (!initDeferred.isCompleted) {
                    initDeferred.complete(
                        ScriptInitResult(
                            success = false,
                            errorMessage = formatFriendlyError(t.message)
                        )
                    )
                }
            }
        }

        // Uses withTimeoutOrNull so external CancellationException always propagates cleanly
        return withTimeoutOrNull(timeoutMs) {
            initDeferred.await()
        } ?: ScriptInitResult(false, errorMessage = "脚本初始化超时 (${timeoutMs / 1000}s)")
    }

    private fun bindNativeBridge(ctx: QuickJSContext) {
        val global = ctx.globalObject

        global.setProperty("__lx_native_call__") { args ->
            if (args.size >= 3 && secretKey == args[0]?.toString()) {
                handleScriptNativeCall(args[1].toString(), args[2].toString())
            }
            null
        }

        global.setProperty("__lx_native_call__utils_str2b64") { args ->
            LxCrypto.str2b64(args.getOrNull(0)?.toString().orEmpty())
        }

        // Standard Web Base64 helpers (btoa / atob) for custom script compatibility
        global.setProperty("btoa") { args ->
            val raw = args.getOrNull(0)?.toString().orEmpty()
            Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.ISO_8859_1))
        }
        global.setProperty("atob") { args ->
            val raw = args.getOrNull(0)?.toString().orEmpty()
            val bytes = LxCrypto.decodeBase64Flexible(raw) ?: ByteArray(0)
            String(bytes, Charsets.ISO_8859_1)
        }

        global.setProperty("__lx_native_call__utils_b642buf") { args ->
            LxCrypto.b64ToJsonByteArray(args.getOrNull(0)?.toString().orEmpty())
        }

        global.setProperty("__lx_native_call__utils_str2md5") { args ->
            val raw = args.getOrNull(0)?.toString().orEmpty()
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            LxCrypto.md5Hex(decoded)
        }

        global.setProperty("__lx_native_call__utils_aes_encrypt") { args ->
            LxCrypto.aesEncryptBase64(
                dataB64 = args.getOrNull(0)?.toString().orEmpty(),
                keyB64 = args.getOrNull(1)?.toString().orEmpty(),
                ivB64 = args.getOrNull(2)?.toString().orEmpty(),
                mode = args.getOrNull(3)?.toString().orEmpty()
            )
        }

        global.setProperty("__lx_native_call__utils_rsa_encrypt") { args ->
            LxCrypto.rsaEncryptBase64(
                dataB64 = args.getOrNull(0)?.toString().orEmpty(),
                publicKeyPemBody = args.getOrNull(1)?.toString().orEmpty(),
                padding = args.getOrNull(2)?.toString().orEmpty()
            )
        }

        global.setProperty("__lx_native_call__utils_zlib_inflate") { args ->
            LxCrypto.zlibInflateBase64(args.getOrNull(0)?.toString().orEmpty())
        }

        global.setProperty("__lx_native_call__utils_zlib_deflate") { args ->
            LxCrypto.zlibDeflateBase64(args.getOrNull(0)?.toString().orEmpty())
        }

        global.setProperty("__lx_native_call__set_timeout") { args ->
            if (destroyed.get() || activeTimers.size >= MAX_ACTIVE_TIMERS) return@setProperty null
            val timerId = (args.getOrNull(0) as? Number)?.toLong() ?: args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
            val delayMs = ((args.getOrNull(1) as? Number)?.toLong() ?: 0L).coerceIn(0L, 60_000L)
            val runnable = Runnable {
                activeTimers.remove(timerId)
                if (!destroyed.get()) {
                    callJs("__set_timeout__", timerId.toString())
                }
            }
            activeTimers.put(timerId, runnable)?.let { old -> jsHandler.removeCallbacks(old) }
            jsHandler.postDelayed(runnable, delayMs)
            null
        }

        global.setProperty("__lx_native_call__clear_timeout") { args ->
            val timerId = (args.getOrNull(0) as? Number)?.toLong() ?: args.getOrNull(0)?.toString()?.toLongOrNull()
            if (timerId != null) {
                activeTimers.remove(timerId)?.let { runnable ->
                    jsHandler.removeCallbacks(runnable)
                }
            }
            null
        }
    }

    private fun handleScriptNativeCall(action: String, dataJson: String) {
        when (action) {
            "init" -> {
                val obj = runCatching { json.parseToJsonElement(dataJson).asJsonObjectOrNull() }.getOrNull()
                val ok = obj?.get("status").asBooleanOrNull() == true
                val errMsg = obj?.get("errorMessage").asStringOrNull()?.let { formatFriendlyError(it) }
                val platforms = mutableMapOf<String, List<String>>()
                val sourcesObj = obj?.get("info").asJsonObjectOrNull()?.get("sources").asJsonObjectOrNull()
                sourcesObj?.forEach { (platformCode, element) ->
                    val pObj = element.asJsonObjectOrNull() ?: return@forEach
                    val qualities = pObj["qualitys"].asJsonArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()
                    val actions = pObj["actions"].asJsonArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()
                    if (actions.contains("musicUrl")) {
                        platforms[platformCode] = qualities
                    }
                }
                if (!initDeferred.isCompleted) {
                    val effectiveSuccess = ok && platforms.isNotEmpty()
                    val effectiveErr = when {
                        effectiveSuccess -> null
                        !errMsg.isNullOrBlank() -> errMsg
                        ok && platforms.isEmpty() -> "音源未声明可用音乐平台"
                        else -> "初始化失败"
                    }
                    initDeferred.complete(
                        ScriptInitResult(
                            success = effectiveSuccess,
                            supportedPlatforms = if (effectiveSuccess) platforms else emptyMap(),
                            errorMessage = effectiveErr
                        )
                    )
                }
            }

            "request" -> {
                val obj = runCatching { json.parseToJsonElement(dataJson).asJsonObjectOrNull() }.getOrNull() ?: return
                val requestKey = obj["requestKey"].asStringOrNull() ?: return
                val url = obj["url"].asStringOrNull()
                if (url.isNullOrBlank()) {
                    sendHttpErrorResponse(requestKey, "Invalid URL")
                    return
                }
                val options = obj["options"].asJsonObjectOrNull()
                executeHttpForScript(requestKey, url, options)
            }

            "cancelRequest" -> {
                val requestKey = runCatching { json.parseToJsonElement(dataJson).asStringOrNull() }.getOrNull()
                if (requestKey != null) {
                    cancelledRequests.add(requestKey)
                    activeHttpCalls.remove(requestKey)?.cancel()
                }
            }

            "response" -> {
                val obj = runCatching { json.parseToJsonElement(dataJson).asJsonObjectOrNull() }.getOrNull() ?: return
                val requestKey = obj["requestKey"].asStringOrNull() ?: return
                val deferred = pendingActions.remove(requestKey) ?: return
                val status = obj["status"].asBooleanOrNull() == true
                if (status) {
                    deferred.complete(Result.success(obj["result"]))
                } else {
                    val err = formatFriendlyError(obj["errorMessage"].asStringOrNull() ?: "音源解析失败")
                    deferred.complete(Result.failure(IllegalStateException(err)))
                }
            }

            "showUpdateAlert" -> {
                Log.i(TAG, "[${source.name}] update alert: $dataJson")
            }
        }
    }

    private fun sendHttpErrorResponse(requestKey: String, errorMessage: String) {
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("error", errorMessage)
            put("response", JsonNull)
        }
        postCallJs("response", payload.toString())
    }

    private fun executeHttpForScript(requestKey: String, url: String, options: JsonObject?) {
        networkScope.launch {
            var activeCall: Call? = null
            try {
                if (destroyed.get() || cancelledRequests.remove(requestKey)) return@launch

                val method = options?.get("method").asStringOrNull()?.uppercase(Locale.ROOT) ?: "GET"
                val timeoutMs = options?.get("timeout").asIntOrNull()?.toLong()?.coerceIn(1_000L, 12_000L) ?: 8_000L
                val isBinary = options?.get("binary").asBooleanOrNull() == true
                val followMax = options?.get("follow_max").asIntOrNull()

                val reqBuilder = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                var hasUserAgent = false
                var explicitContentType: String? = null

                options?.get("headers").asJsonObjectOrNull()?.forEach { (k, v) ->
                    val value = v.asStringOrNull() ?: return@forEach
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    if (k.equals("Content-Type", ignoreCase = true)) explicitContentType = value
                    reqBuilder.header(k, value)
                }
                if (!hasUserAgent) {
                    reqBuilder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                }

                if (method in setOf("POST", "PUT", "PATCH", "DELETE")) {
                    val formEl = options?.get("form")
                    val formDataEl = options?.get("formData")
                    val bodyEl = options?.get("body")
                    val reqBody = when {
                        formEl is JsonObject -> {
                            FormBody.Builder().apply {
                                formEl.forEach { (k, v) ->
                                    if (v !is JsonNull) {
                                        add(k, v.asStringOrNull() ?: v.toString())
                                    }
                                }
                            }.build()
                        }
                        formEl is JsonPrimitive && formEl !is JsonNull && formEl.isString -> {
                            formEl.content.toRequestBody(
                                (explicitContentType ?: "application/x-www-form-urlencoded; charset=utf-8").toMediaTypeOrNull()
                            )
                        }
                        formDataEl is JsonObject -> {
                            MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                                formDataEl.forEach { (k, v) ->
                                    if (v !is JsonNull) {
                                        addFormDataPart(k, v.asStringOrNull() ?: v.toString())
                                    }
                                }
                            }.build()
                        }
                        bodyEl is JsonPrimitive && bodyEl !is JsonNull && bodyEl.isString -> {
                            bodyEl.content.toRequestBody((explicitContentType ?: "application/json; charset=utf-8").toMediaTypeOrNull())
                        }
                        bodyEl != null && bodyEl !is JsonNull -> {
                            bodyEl.toString().toRequestBody((explicitContentType ?: "application/json; charset=utf-8").toMediaTypeOrNull())
                        }
                        else -> "".toRequestBody((explicitContentType ?: "application/json; charset=utf-8").toMediaTypeOrNull())
                    }
                    reqBuilder.method(method, reqBody)
                } else {
                    reqBuilder.method(method, null)
                }

                val clientBuilder = httpClient.newBuilder()
                    .cache(null)
                    .connectTimeout(timeoutMs.coerceAtMost(5_000L), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                if (followMax == 0) {
                    clientBuilder.followRedirects(false).followSslRedirects(false)
                }

                val call = clientBuilder.build().newCall(reqBuilder.build())
                activeCall = call
                activeHttpCalls[requestKey] = call
                if (destroyed.get() || cancelledRequests.remove(requestKey)) {
                    activeHttpCalls.remove(requestKey, call)
                    runCatching { call.cancel() }
                    return@launch
                }

                call.execute().use { resp ->
                    activeHttpCalls.remove(requestKey, call)
                    if (destroyed.get() || cancelledRequests.remove(requestKey)) return@launch

                    val respHeaders = buildJsonObject {
                        resp.headers.names().forEach { name ->
                            val lowerName = name.lowercase(Locale.ROOT)
                            val values = resp.headers.values(name)
                            if (lowerName == "set-cookie") {
                                put(lowerName, JsonArray(values.map { JsonPrimitive(it) }))
                            } else {
                                put(lowerName, values.joinToString(", "))
                            }
                        }
                    }

                    val rawBytes = readBoundedBytes(resp.body, MAX_HTTP_RESPONSE_BYTES)
                    val bodyElement: JsonElement = if (isBinary) {
                        JsonPrimitive(Base64.getEncoder().encodeToString(rawBytes))
                    } else {
                        val charset = resp.body?.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
                        val text = String(rawBytes, charset)
                        val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                        val trimmed = text.trimStart()
                        val shouldParseJson = contentType.contains("json") ||
                            trimmed.startsWith("{") ||
                            trimmed.startsWith("[")
                        if (shouldParseJson) {
                            runCatching { strictJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                        } else {
                            JsonPrimitive(text)
                        }
                    }

                    val payload = buildJsonObject {
                        put("requestKey", requestKey)
                        put("error", JsonNull)
                        put("response", buildJsonObject {
                            put("statusCode", resp.code)
                            put("statusMessage", resp.message)
                            put("headers", respHeaders)
                            put("binary", isBinary)
                            put("body", bodyElement)
                        })
                    }
                    postCallJs("response", payload.toString())
                }
            } catch (t: Throwable) {
                activeCall?.let { activeHttpCalls.remove(requestKey, it) }
                if (!destroyed.get() && !cancelledRequests.remove(requestKey)) {
                    sendHttpErrorResponse(requestKey, t.message ?: "Network request error")
                }
            } finally {
                activeCall?.let { activeHttpCalls.remove(requestKey, it) }
                cancelledRequests.remove(requestKey)
            }
        }
    }

    suspend fun requestMusicUrl(track: Track, quality: AudioQuality, timeoutMs: Long = 4_500L): Result<String> {
        if (destroyed.get()) return Result.failure(IllegalStateException("音源已卸载"))
        val requestKey = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val deferred = CompletableDeferred<Result<JsonElement?>>()
        pendingActions[requestKey] = deferred
        // Re-check destroyed AFTER inserting into pendingActions to close TOCTOU window
        if (destroyed.get()) {
            pendingActions.remove(requestKey)
            return Result.failure(IllegalStateException("音源已卸载"))
        }

        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("data", buildJsonObject {
                put("source", track.source.code)
                put("action", "musicUrl")
                put("info", buildJsonObject {
                    put("type", quality.lxType)
                    put("musicInfo", track.toLxMusicInfoJson())
                })
            })
        }

        postCallJs("request", payload.toString())

        return try {
            val outcome = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: return Result.failure(IllegalStateException("请求超时，请检查网络连接"))

            val res = outcome.getOrElse { err ->
                return Result.failure(IllegalStateException(formatFriendlyError(err.message)))
            }
            val extracted = res.asJsonObjectOrNull()
                ?.get("data").asJsonObjectOrNull()
                ?.get("url").asStringOrNull()
            if (extracted.isNullOrBlank() || !extracted.startsWith("http")) {
                Result.failure(IllegalStateException("未返回有效音频直链"))
            } else {
                Result.success(extracted)
            }
        } finally {
            pendingActions.remove(requestKey)
        }
    }

    private fun postCallJs(action: String, dataJson: String) {
        if (destroyed.get()) return
        jsHandler.post {
            if (!destroyed.get()) {
                callJs(action, dataJson)
            }
        }
    }

    private fun callJs(action: String, dataJson: String? = null) {
        val ctx = jsContext ?: return
        try {
            val fn = nativeBridgeFn ?: ctx.globalObject.getJSFunction("__lx_native__").also {
                nativeBridgeFn = it
            }
            if (dataJson == null) {
                fn.call(secretKey, action)
            } else {
                fn.call(secretKey, action, dataJson)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "callJs($action) failed for ${source.name}", t)
        }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        initDeferred.complete(ScriptInitResult(false, errorMessage = "音源已停用"))
        activeHttpCalls.values.forEach { runCatching { it.cancel() } }
        activeHttpCalls.clear()
        cancelledRequests.clear()
        activeTimers.values.forEach { jsHandler.removeCallbacks(it) }
        activeTimers.clear()
        pendingActions.values.forEach {
            it.complete(Result.failure(IllegalStateException("音源已切换或停止")))
        }
        pendingActions.clear()
        networkScope.cancel()
        jsHandler.removeCallbacksAndMessages(null)
        jsHandler.post {
            runCatching { nativeBridgeFn?.release() }
            nativeBridgeFn = null
            runCatching { jsContext?.destroy() }
            jsContext = null
            thread.quitSafely()
        }
    }
}
