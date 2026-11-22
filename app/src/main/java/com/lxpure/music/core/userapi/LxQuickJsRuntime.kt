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
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
        private val loaderLock = Any()

        @Volatile
        private var quickJsLoaded = false

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

            val lower = cleaned.lowercase()
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
    }

    private val secretKey = UUID.randomUUID().toString()
    private val thread = HandlerThread("LxQuickJS-${source.id.take(8)}").apply { start() }
    private val jsHandler = Handler(thread.looper)
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var jsContext: QuickJSContext? = null
    private val destroyed = AtomicBoolean(false)
    private val initDeferred = CompletableDeferred<ScriptInitResult>()
    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement?>>>()
    private val activeHttpCalls = ConcurrentHashMap<String, Call>()

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
                ctx.globalObject.getJSFunction("lx_setup").call(
                    secretKey,
                    source.id,
                    source.name,
                    source.description,
                    source.version,
                    source.author,
                    source.homepage,
                    source.script
                )
                ctx.evaluate(source.script, "${source.name}.js")
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

        return runCatching {
            withTimeout(timeoutMs) { initDeferred.await() }
        }.getOrElse {
            ScriptInitResult(false, errorMessage = "脚本初始化超时 (${timeoutMs / 1000}s)")
        }
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
            android.util.Base64.encodeToString(raw.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)
        }
        global.setProperty("atob") { args ->
            val raw = args.getOrNull(0)?.toString().orEmpty()
            runCatching {
                String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            }.getOrDefault("")
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

        global.setProperty("__lx_native_call__set_timeout") { args ->
            val timerId = (args.getOrNull(0) as? Number)?.toLong() ?: args.getOrNull(0)?.toString()?.toLongOrNull() ?: 0L
            val delayMs = ((args.getOrNull(1) as? Number)?.toLong() ?: 0L).coerceIn(0L, 60_000L)
            jsHandler.postDelayed({
                if (!destroyed.get()) {
                    callJs("__set_timeout__", timerId.toString())
                }
            }, delayMs)
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
                    initDeferred.complete(
                        ScriptInitResult(
                            success = ok,
                            supportedPlatforms = platforms,
                            errorMessage = errMsg
                        )
                    )
                }
            }

            "request" -> {
                val obj = runCatching { json.parseToJsonElement(dataJson).asJsonObjectOrNull() }.getOrNull() ?: return
                val requestKey = obj["requestKey"].asStringOrNull() ?: return
                val url = obj["url"].asStringOrNull() ?: return
                val options = obj["options"].asJsonObjectOrNull()
                executeHttpForScript(requestKey, url, options)
            }

            "cancelRequest" -> {
                val requestKey = runCatching { json.parseToJsonElement(dataJson).asStringOrNull() }.getOrNull()
                if (requestKey != null) {
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

    private fun executeHttpForScript(requestKey: String, url: String, options: JsonObject?) {
        networkScope.launch {
            try {
                val method = options?.get("method").asStringOrNull()?.uppercase() ?: "GET"
                val timeoutMs = options?.get("timeout").asIntOrNull()?.toLong()?.coerceIn(1_000L, 12_000L) ?: 8_000L
                val isBinary = options?.get("binary").asBooleanOrNull() == true

                val reqBuilder = Request.Builder().url(url)
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
                    val bodyEl = options?.get("body")
                    val reqBody = when {
                        formEl is JsonObject -> {
                            FormBody.Builder().apply {
                                formEl.forEach { (k, v) ->
                                    add(k, v.asStringOrNull() ?: v.toString())
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

                val client = httpClient.newBuilder()
                    .connectTimeout(timeoutMs.coerceAtMost(5_000L), TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()

                val call = client.newCall(reqBuilder.build())
                activeHttpCalls[requestKey] = call

                call.execute().use { resp ->
                    activeHttpCalls.remove(requestKey)
                    val respHeaders = buildJsonObject {
                        resp.headers.names().forEach { name ->
                            put(name.lowercase(), resp.headers[name].orEmpty())
                        }
                    }

                    val bodyElement: JsonElement = if (isBinary) {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        JsonArray(bytes.map { JsonPrimitive(it.toInt() and 0xFF) })
                    } else {
                        val text = resp.body?.string().orEmpty()
                        runCatching { json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
                    }

                    val payload = buildJsonObject {
                        put("requestKey", requestKey)
                        put("error", JsonNull)
                        put("response", buildJsonObject {
                            put("statusCode", resp.code)
                            put("statusMessage", resp.message)
                            put("headers", respHeaders)
                            put("body", bodyElement)
                        })
                    }
                    postCallJs("response", payload.toString())
                }
            } catch (t: Throwable) {
                activeHttpCalls.remove(requestKey)
                val payload = buildJsonObject {
                    put("requestKey", requestKey)
                    put("error", t.message ?: "Network request error")
                    put("response", JsonNull)
                }
                postCallJs("response", payload.toString())
            }
        }
    }

    suspend fun requestMusicUrl(track: Track, quality: AudioQuality, timeoutMs: Long = 4_500L): Result<String> {
        if (destroyed.get()) return Result.failure(IllegalStateException("音源已卸载"))
        val requestKey = "req_${UUID.randomUUID().toString().replace("-", "")}"
        val deferred = CompletableDeferred<Result<JsonElement?>>()
        pendingActions[requestKey] = deferred

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
            val url = withTimeout(timeoutMs) {
                val res = deferred.await().getOrThrow()
                val extracted = res.asJsonObjectOrNull()
                    ?.get("data").asJsonObjectOrNull()
                    ?.get("url").asStringOrNull()
                if (extracted.isNullOrBlank() || !extracted.startsWith("http")) {
                    throw IllegalStateException("未返回有效音频直链")
                }
                extracted
            }
            Result.success(url)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) {
                throw t
            }
            Result.failure(IllegalStateException(formatFriendlyError(t.message)))
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
            val fn = ctx.globalObject.getJSFunction("__lx_native__")
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
        activeHttpCalls.values.forEach { runCatching { it.cancel() } }
        activeHttpCalls.clear()
        pendingActions.values.forEach {
            it.complete(Result.failure(IllegalStateException("音源已切换或停止")))
        }
        pendingActions.clear()
        networkScope.cancel()
        jsHandler.post {
            runCatching { jsContext?.destroy() }
            jsContext = null
            thread.quitSafely()
        }
    }
}
