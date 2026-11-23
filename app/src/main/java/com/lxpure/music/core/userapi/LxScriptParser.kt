package com.lxpure.music.core.userapi

import java.util.UUID

data class ParsedScriptMeta(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String
)

object LxScriptParser {
    // Matches both /** ... */ and /*! ... */ header comment blocks used by community LX scripts
    private val headerBlockRegex = Regex("/\\*[*!]([\\s\\S]*?)\\*/")
    private val tagRegex = Regex("@([a-zA-Z_]+)\\s+(.+)")

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

    fun parse(script: String, fallbackName: String = "自定义音源"): ParsedScriptMeta {
        val normalized = normalizeScript(script)
        val tags = mutableMapOf<String, String>()
        val header = headerBlockRegex.find(normalized)?.groupValues?.getOrNull(1)
        if (header != null) {
            header.lines().forEach { rawLine ->
                val line = rawLine.trim().removePrefix("*").trim()
                val match = tagRegex.find(line)
                if (match != null) {
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    if (value.isNotEmpty() && !tags.containsKey(key)) {
                        tags[key] = value
                    }
                }
            }
        }
        val name = tags["name"]?.take(60) ?: fallbackName
        val idSeed = "${name}_${tags["author"].orEmpty()}_${tags["homepage"].orEmpty()}"
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
