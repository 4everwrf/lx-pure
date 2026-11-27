package com.lxpure.music.core.userapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.UUID

data class ParsedScriptMeta(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String
)

data class BundledScriptEntry(
    val fallbackName: String,
    val script: String
)

sealed class ScriptBundleParseResult {
    data object NotBundle : ScriptBundleParseResult()
    data class ValidBundle(
        val entries: List<BundledScriptEntry>,
        val skippedCount: Int = 0
    ) : ScriptBundleParseResult()
    data class InvalidBundle(val errorMessage: String) : ScriptBundleParseResult()
}

object LxScriptParser {
    const val MAX_SINGLE_SCRIPT_BYTES = 1 * 1024 * 1024
    const val MAX_BUNDLE_PAYLOAD_BYTES = 4 * 1024 * 1024
    const val MAX_BUNDLE_SCRIPT_COUNT = 50

    // Matches both /** ... */ and /*! ... */ header comment blocks used by community LX scripts
    private val headerBlockRegex = Regex("/\\*[*!]([\\s\\S]*?)\\*/")
    private val tagRegex = Regex("@([a-zA-Z_]+)\\s+(.+)")
    private val bundleJson = Json { ignoreUnknownKeys = true; isLenient = false }
    private val bundleKeys = listOf("scripts", "sources", "userApis")
    private val scriptCodeKeys = listOf("code", "script", "content")
    private val brokenJsonHintRegex = Regex("\"(scripts|sources|userApis|code|script|content|app|type)\"\\s*:")

    /**
     * Strips any leading UTF-8 BOM and normalizes CRLF/CR line endings to Unix LF (`\n`)
     * so scripts that verify MD5 checksums of `lx.currentScriptInfo.rawScript` behave
     * identically whether imported from Windows files, clipboard, or remote URLs.
     */
    fun normalizeScript(raw: String): String {
        val withoutBom = raw.removePrefix("\uFEFF")
        return if ('\r' in withoutBom) {
            withoutBom.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            withoutBom
        }
    }

    internal fun hasLxScriptMarkers(text: String): Boolean =
        text.contains("lx", ignoreCase = true) ||
            text.contains("\\x6c\\x78", ignoreCase = true) ||
            text.contains("@name") ||
            text.contains("globalThis")

    private fun cleanFallbackName(rawName: String?): String {
        val trimmed = rawName.orEmpty().trim()
        if (trimmed.isEmpty()) return "自定义音源"
        val withoutExt = if (trimmed.endsWith(".js", ignoreCase = true)) {
            trimmed.dropLast(3).trim()
        } else {
            trimmed
        }
        return withoutExt.take(60).ifEmpty { "自定义音源" }
    }

    private fun isValidBundledScriptCode(normalizedCode: String, minLength: Int = 20): Boolean {
        val trimmed = normalizedCode.trim()
        if (trimmed.length < minLength) return false
        if (normalizedCode.contains('\u0000')) return false
        if (normalizedCode.toByteArray(StandardCharsets.UTF_8).size > MAX_SINGLE_SCRIPT_BYTES) return false
        if (trimmed.startsWith("<") || trimmed.startsWith("{")) return false
        return hasLxScriptMarkers(trimmed)
    }

    /**
     * Detects and unpacks multi-script JSON bundles (such as Melora `{"app":"melora","type":"lx-sources","scripts":[...]}`
     * or generic `scripts`/`sources`/`userApis` arrays) while rejecting API error JSON and malformed payloads.
     */
    fun parseScriptBundle(rawContent: String): ScriptBundleParseResult {
        val withoutBom = rawContent.removePrefix("\uFEFF")
        val trimmed = withoutBom.trim()
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            return ScriptBundleParseResult.NotBundle
        }
        if (rawContent.contains('\u0000')) {
            return ScriptBundleParseResult.InvalidBundle("文件包含二进制内容，不是有效的音源包")
        }
        if (rawContent.toByteArray(StandardCharsets.UTF_8).size > MAX_BUNDLE_PAYLOAD_BYTES) {
            return ScriptBundleParseResult.InvalidBundle("音源包体积过大（上限 4 MB），请拆分后导入")
        }

        val root = runCatching { bundleJson.parseToJsonElement(trimmed) }.getOrElse {
            return if (!brokenJsonHintRegex.containsMatchIn(trimmed) && hasLxScriptMarkers(trimmed)) {
                ScriptBundleParseResult.NotBundle
            } else {
                ScriptBundleParseResult.InvalidBundle("JSON 音源包格式错误，无法解析")
            }
        }

        val candidateArray: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val foundArray = bundleKeys.firstNotNullOfOrNull { key -> root[key] as? JsonArray }
                if (foundArray != null) {
                    foundArray
                } else {
                    val singleRawCode = scriptCodeKeys.firstNotNullOfOrNull { key ->
                        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    if (singleRawCode != null) {
                        val normalized = normalizeScript(singleRawCode)
                        if (isValidBundledScriptCode(normalized, minLength = 80)) {
                            val rawName = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            return ScriptBundleParseResult.ValidBundle(
                                entries = listOf(BundledScriptEntry(cleanFallbackName(rawName), normalized)),
                                skippedCount = 0
                            )
                        }
                    }
                    return ScriptBundleParseResult.InvalidBundle("该 JSON 内容不是有效的洛雪 / Melora 音源包")
                }
            }
            else -> return ScriptBundleParseResult.InvalidBundle("该 JSON 内容不是有效的洛雪 / Melora 音源包")
        }

        if (candidateArray.isEmpty()) {
            return ScriptBundleParseResult.InvalidBundle("音源包中未包含任何音源脚本")
        }
        if (candidateArray.size > MAX_BUNDLE_SCRIPT_COUNT) {
            return ScriptBundleParseResult.InvalidBundle("单个音源包最多支持 $MAX_BUNDLE_SCRIPT_COUNT 个脚本，请拆分后导入")
        }

        val entries = mutableListOf<BundledScriptEntry>()
        var skipped = 0
        for (element in candidateArray) {
            val rawCode: String?
            val rawName: String?
            when (element) {
                is JsonObject -> {
                    rawCode = scriptCodeKeys.firstNotNullOfOrNull { key ->
                        (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                    rawName = (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                }
                is JsonPrimitive -> {
                    rawCode = if (element.isString) element.content else null
                    rawName = null
                }
                else -> {
                    rawCode = null
                    rawName = null
                }
            }
            if (rawCode == null) {
                skipped++
                continue
            }
            val normalized = normalizeScript(rawCode)
            if (isValidBundledScriptCode(normalized)) {
                entries.add(BundledScriptEntry(cleanFallbackName(rawName), normalized))
            } else {
                skipped++
            }
        }

        if (entries.isEmpty()) {
            return ScriptBundleParseResult.InvalidBundle("音源包中未包含有效的洛雪 JS 音源脚本")
        }
        return ScriptBundleParseResult.ValidBundle(entries = entries, skippedCount = skipped)
    }

    fun parse(script: String, fallbackName: String = "自定义音源"): ParsedScriptMeta {
        val normalized = normalizeScript(script)
        val tags = mutableMapOf<String, String>()
        for (blockMatch in headerBlockRegex.findAll(normalized)) {
            val header = blockMatch.groupValues.getOrNull(1) ?: continue
            val blockTags = mutableMapOf<String, String>()
            header.lines().forEach { rawLine ->
                val line = rawLine.trim().removePrefix("*").trim()
                val match = tagRegex.find(line)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    if (value.isNotEmpty() && !blockTags.containsKey(key)) {
                        blockTags[key] = value
                    }
                }
            }
            if (blockTags.containsKey("name")) {
                tags.putAll(blockTags)
                break
            }
            if (tags.isEmpty() && blockTags.isNotEmpty()) {
                tags.putAll(blockTags)
            }
        }
        val hasDeclaredMeta = tags.containsKey("name") || tags.containsKey("author") || tags.containsKey("homepage")
        val name = tags["name"]?.take(60) ?: fallbackName
        val idSeed = if (hasDeclaredMeta) {
            "${name}_${tags["author"].orEmpty()}_${tags["homepage"].orEmpty()}"
        } else {
            "${name}_${LxCrypto.md5Hex(normalized.trim()).take(16)}"
        }
        val stableId = "user_api_" + LxCrypto.md5Hex(idSeed).take(12).ifEmpty { UUID.randomUUID().toString().take(8) }
        return ParsedScriptMeta(
            id = stableId,
            name = name,
            description = tags["description"]?.take(200) ?: "",
            version = tags["version"]?.take(32) ?: "1.0.0",
            author = tags["author"]?.take(60) ?: "未知作者",
            homepage = tags["homepage"]?.take(200) ?: ""
        )
    }

    fun formatVersionBadge(rawVersion: String?): String {
        val clean = rawVersion.orEmpty().trim().trimStart('v', 'V').trim()
        return "v${clean.ifEmpty { "1.0.0" }}"
    }
}
