package com.lxpure.music.core.musicsdk

import com.lxpure.music.domain.model.LyricLine
import com.lxpure.music.domain.model.ParsedLyrics
import kotlin.math.abs

object LrcParser {
    private val timeTagRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d+))?]")
    private val offsetTagRegex = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)
    private val yrcLineRegex = Regex("^\\[(\\d+),\\d+]")
    private val yrcWordRegex = Regex("\\(-?\\d+,-?\\d+(?:,-?\\d+)?\\)")
    private val qishuiWordRegex = Regex("<-?\\d+,-?\\d+(?:,-?\\d+)?>")

    fun parse(rawLrc: String?, rawTranslation: String? = null): ParsedLyrics {
        val (primary, primaryOffsetMs) = parseSingle(rawLrc)
        if (primary.isEmpty()) return ParsedLyrics()

        // Inherit primary [offset:] when translation track omits its own [offset:] tag
        val (rawTranslations, _) = parseSingle(rawTranslation, defaultOffsetMs = primaryOffsetMs)
        val translations = rawTranslations
            .filter { it.text.isNotBlank() && it.text != "//" }
        if (translations.isEmpty()) {
            return ParsedLyrics(lines = primary, hasTranslation = false)
        }

        // 1-to-1 nearest timestamp matching prevents a single translation line from bleeding onto neighbors
        val usedTransIndices = HashSet<Int>()
        val merged = primary.map { line ->
            var bestIdx = -1
            var bestDiff = Long.MAX_VALUE
            for (i in translations.indices) {
                if (i in usedTransIndices) continue
                val diff = abs(translations[i].timeMs - line.timeMs)
                if (diff <= 450L && diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            val matchedText = if (bestIdx >= 0) {
                usedTransIndices.add(bestIdx)
                translations[bestIdx].text
            } else {
                null
            }
            line.copy(translation = matchedText)
        }
        return ParsedLyrics(
            lines = merged,
            hasTranslation = merged.any { !it.translation.isNullOrBlank() }
        )
    }

    private fun parseSingle(raw: String?, defaultOffsetMs: Long = 0L): Pair<List<LyricLine>, Long> {
        if (raw.isNullOrBlank()) return emptyList<LyricLine>() to defaultOffsetMs
        val cleanedRaw = raw.removePrefix("\uFEFF").replace("\r", "")
        val result = mutableListOf<LyricLine>()
        var offsetMs = defaultOffsetMs

        cleanedRaw.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("{\"")) return@forEach

            val offsetMatch = offsetTagRegex.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            // Support NetEase YRC `[startMs,duration](word...)` and Qishui `[startMs,duration]<word...>` lines
            val yrcMatch = yrcLineRegex.find(line)
            if (yrcMatch != null) {
                val timeMs = yrcMatch.groupValues[1].toLongOrNull() ?: return@forEach
                val text = FormatUtils.decodeHtml(
                    line.substring(yrcMatch.range.last + 1)
                        .replace(yrcWordRegex, "")
                        .replace(qishuiWordRegex, "")
                        .trim()
                )
                if (text.isNotEmpty()) {
                    result.add(LyricLine(timeMs = (timeMs - offsetMs).coerceAtLeast(0L), text = text))
                }
                return@forEach
            }

            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            val text = FormatUtils.decodeHtml(
                line.replace(timeTagRegex, "")
                    .replace(qishuiWordRegex, "")
                    .trim()
            )
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
                val shifted = (min * 60_000L + sec * 1_000L + ms - offsetMs).coerceAtLeast(0L)
                result.add(LyricLine(timeMs = shifted, text = text))
            }
        }

        return result.sortedBy { it.timeMs } to offsetMs
    }
}
