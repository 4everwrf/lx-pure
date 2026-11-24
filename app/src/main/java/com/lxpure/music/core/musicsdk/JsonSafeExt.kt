package com.lxpure.music.core.musicsdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Null-safe JSON accessors that safely handle [JsonNull] and mismatched element types
 * without throwing [IllegalArgumentException].
 */
fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

fun JsonElement?.asJsonPrimitiveOrNull(): JsonPrimitive? =
    if (this is JsonPrimitive && this !is JsonNull) this else null

fun JsonElement?.asStringOrNull(): String? = asJsonPrimitiveOrNull()?.contentOrNull

fun JsonElement?.asNonBlankStringOrNull(): String? =
    asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }

fun JsonElement?.asIntOrNull(): Int? {
    val prim = asJsonPrimitiveOrNull() ?: return null
    prim.intOrNull?.let { return it }
    prim.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
    val dbl = prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull()
    if (dbl != null && dbl.isFinite() && dbl % 1.0 == 0.0 && dbl in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
        return dbl.toInt()
    }
    return null
}

fun JsonElement?.asLongOrNull(): Long? {
    val prim = asJsonPrimitiveOrNull() ?: return null
    prim.longOrNull?.let { return it }
    prim.contentOrNull?.trim()?.toLongOrNull()?.let { return it }
    val dbl = prim.doubleOrNull ?: prim.contentOrNull?.trim()?.toDoubleOrNull()
    if (dbl != null && dbl.isFinite() && dbl % 1.0 == 0.0 && dbl >= Long.MIN_VALUE.toDouble() && dbl <= Long.MAX_VALUE.toDouble()) {
        return dbl.toLong()
    }
    return null
}

fun JsonElement?.asDoubleOrNull(): Double? =
    asJsonPrimitiveOrNull()?.doubleOrNull ?: asStringOrNull()?.trim()?.toDoubleOrNull()

fun JsonElement?.asBooleanOrNull(): Boolean? =
    asJsonPrimitiveOrNull()?.booleanOrNull ?: asStringOrNull()?.trim()?.lowercase(Locale.ROOT)?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

private val httpUrlRegex = Regex("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+", RegexOption.IGNORE_CASE)

/**
 * Extracts the first HTTP(S) URL from pasted Chinese share copy, cleanly stopping before
 * CJK text or trailing punctuation (e.g. `http://163cn.tv/abc（来自@网易云音乐）`).
 */
internal fun extractFirstHttpUrl(raw: String): String? {
    val match = httpUrlRegex.find(raw)?.value ?: return null
    return match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
        .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
}

/**
 * Cancellation-safe wrapper around [runCatching] that always rethrows [CancellationException].
 */
internal inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (ce: CancellationException) {
    throw ce
} catch (t: Throwable) {
    Result.failure(t)
}

/**
 * Cancellable OkHttp [Call] execution that cancels the underlying socket immediately
 * when the parent coroutine or `withTimeoutOrNull` is cancelled.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(value = response, onCancellation = { _, res, _ ->
                    runCatching { res.close() }
                })
            } else {
                response.close()
            }
        }
    })
}
