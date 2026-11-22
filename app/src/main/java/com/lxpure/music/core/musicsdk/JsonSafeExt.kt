package com.lxpure.music.core.musicsdk

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

/**
 * Null-safe JSON accessors that safely handle [JsonNull] and mismatched element types
 * without throwing [IllegalArgumentException].
 */
fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.asJsonArrayOrNull(): JsonArray? = this as? JsonArray

fun JsonElement?.asJsonPrimitiveOrNull(): JsonPrimitive? =
    if (this is JsonPrimitive && this !is JsonNull) this else null

fun JsonElement?.asStringOrNull(): String? = asJsonPrimitiveOrNull()?.contentOrNull

fun JsonElement?.asIntOrNull(): Int? =
    asJsonPrimitiveOrNull()?.intOrNull ?: asStringOrNull()?.trim()?.toIntOrNull()

fun JsonElement?.asLongOrNull(): Long? =
    asJsonPrimitiveOrNull()?.longOrNull ?: asStringOrNull()?.trim()?.toLongOrNull()

fun JsonElement?.asDoubleOrNull(): Double? =
    asJsonPrimitiveOrNull()?.doubleOrNull ?: asStringOrNull()?.trim()?.toDoubleOrNull()

fun JsonElement?.asBooleanOrNull(): Boolean? =
    asJsonPrimitiveOrNull()?.booleanOrNull ?: asStringOrNull()?.trim()?.lowercase()?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
