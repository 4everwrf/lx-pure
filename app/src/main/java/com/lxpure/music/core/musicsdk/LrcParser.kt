package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.LyricLine
import com.lxpure.music.domain.model.ParsedLyrics
import kotlin.math.abs

object LrcParser {
    private val timeTagRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?]")
    private val yrcLineRegex = Regex("^\\[(\\d+),\\d+]")
    private val yrcWordRegex = Regex("\\(\\d+,\\d+,\\d+\\)")

    fun parse(rawLrc: String?, rawTranslation: String? = null): ParsedLyrics {
        val primary = parseSingle(rawLrc)
        if (primary.isEmpty()) return ParsedLyrics()

        val translations = parseSingle(rawTranslation)
        if (translations.isEmpty()) {
            return ParsedLyrics(lines = primary, hasTranslation = false)
        }

        val merged = primary.map { line ->
            val matchedTrans = translations.minByOrNull { abs(it.timeMs - line.timeMs) }
                ?.takeIf { abs(it.timeMs - line.timeMs) <= 450L && it.text.isNotBlank() }
            line.copy(translation = matchedTrans?.text)
        }
        return ParsedLyrics(
            lines = merged,
            hasTranslation = merged.any { !it.translation.isNullOrBlank() }
        )
    }

    private fun parseSingle(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val result = mutableListOf<LyricLine>()

        raw.replace("\r", "").lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("{\"")) return@forEach

            // Support NetEase YRC `[startMs,duration](word...)` lines
            val yrcMatch = yrcLineRegex.find(line)
            if (yrcMatch != null) {
                val timeMs = yrcMatch.groupValues[1].toLongOrNull() ?: return@forEach
                val text = line.substring(yrcMatch.range.last + 1)
                    .replace(yrcWordRegex, "")
                    .trim()
                if (text.isNotEmpty()) {
                    result.add(LyricLine(timeMs = timeMs, text = FormatUtils.decodeHtml(text)))
                }
                return@forEach
            }

            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = line.replace(timeTagRegex, "").trim()
            if (text.isEmpty()) return@forEach

            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val ms = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                result.add(
                    LyricLine(
                        timeMs = min * 60_000L + sec * 1_000L + ms,
                        text = FormatUtils.decodeHtml(text)
                    )
                )
            }
        }

        return result.sortedBy { it.timeMs }
    }
}
