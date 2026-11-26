package com.lxpure.music.domain.model

import java.util.Locale

/**
 * Result of laying out interactive artist tokens on a single horizontal player metadata line.
 */
data class ArtistRowPlan(
    val visibleCount: Int,
    val hiddenCount: Int,
    val showAlbum: Boolean,
    val truncateSingleVisibleArtist: Boolean,
    val showCompactSummaryOnly: Boolean,
    val singleArtistMaxWidth: Float? = null
)

/**
 * Extracts clean, deduplicated, searchable individual artist names from a [Track]
 * (preferring structured provider artist arrays when available, and falling back to
 * delimiter-aware parsing for legacy persisted tracks, Kuwo, Kugou fallback, and local ID3 tags).
 */
object ArtistParser {

    private data class ProtectedBandSpec(
        val canonical: String,
        val hasAmpersand: Boolean,
        val pattern: String
    )

    /**
     * Well-known single artists/groups whose canonical names contain `/`, `&`, or `,`.
     * Sorted longest-first so composite names match before shorter substrings.
     */
    private val protectedAtomicNames = listOf(
        "Crosby, Stills, Nash & Young",
        "Tom Petty & The Heartbreakers",
        "Daryl Hall & John Oates",
        "Bob Marley & The Wailers",
        "Nick Cave & The Bad Seeds",
        "Sly & The Family Stone",
        "Emerson, Lake & Palmer",
        "Florence & The Machine",
        "The Mamas & The Papas",
        "Hootie & The Blowfish",
        "Echo & The Bunnymen",
        "Crosby, Stills & Nash",
        "Blood, Sweat & Tears",
        "Earth, Wind & Fire",
        "Angus & Julia Stone",
        "Peter, Paul & Mary",
        "Tyler, The Creator",
        "Simon & Garfunkel",
        "Belle & Sebastian",
        "Of Monsters & Men",
        "SUPER JUNIOR-D&E",
        "Coheed & Cambria",
        "Kool & The Gang",
        "Mumford & Sons",
        "Above & Beyond",
        "Brooks & Dunn",
        "Chase & Status",
        "Hall & Oates",
        "Tegan & Sara",
        "Iron & Wine",
        "She & Him",
        "AC/DC",
        "K/DA"
    ).sortedByDescending { it.length }

    private val protectedBandSpecs: List<ProtectedBandSpec> = protectedAtomicNames.map { canonical ->
        val hasAmp = canonical.contains('&')
        val pattern = buildProtectedPattern(canonical)
        ProtectedBandSpec(canonical = canonical, hasAmpersand = hasAmp, pattern = pattern)
    }

    // Combined single-pass alternation regex (longest-first) so we never run 30+ full-string regex passes
    private val combinedProtectedRegex: Regex = Regex(
        "(?i)(?<![A-Za-z0-9])(?:" + protectedBandSpecs.joinToString("|") { it.pattern } + ")(?![A-Za-z0-9])"
    )

    // Invisible junk, soft hyphen, and Bidi control chars (preserves internal ZWJ U+200D for multi-codepoint emoji)
    private val invisibleJunkRegex = Regex("[\\u00AD\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]")
    private val unicodeHorizontalSpaceRegex = Regex("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]")
    private val horizontalWhitespaceRegex = Regex("[ \\t]+")
    private val controlSeparatorsRegex = Regex("[\\u0000\\r\\n]+")
    private val whitespaceRegex = Regex("\\s+")

    // Standalone "N/A" placeholder stripped before '/' splitting so it never becomes ["N", "A"]
    private val standaloneNaRegex = Regex("(?i)(?<![A-Za-z0-9])n/a(?![A-Za-z0-9])")

    // Matches unparenthesized featuring clauses preceded by a non-delimiter char (protects "Artist A / Ft. Worth", "Daft Punk", "Taylor Swift")
    private val inlineFeatRegex = Regex(
        "(?i)(?<=[^\\s/／、,，;；&＆|｜])(?:\\s*[-–—])?\\s+(?:(?:feat|ft)\\.\\s*|featuring[\\s.:：]+|(?:feat|ft)[\\s:：]+)"
    )

    // Matches spaced Unicode multiplication sign "A × B" while preserving names like "SPY×FAMILY"
    private val spacedTimesRegex = Regex("\\s+×\\s+")

    // Primary multi-artist delimiters used across platforms and local ID3/FLAC tags (preserves numeric commas like "10,000 Maniacs")
    private val rawSplitRegex = Regex("[/／、，;；&＆|｜]|(?<!\\d),|,(?!\\d)")

    // Unambiguous multi-artist delimiters inside structured provider artist entries (preserves "O'Connor &amp; Co." and "Steins;Gate")
    private val unambiguousStructuredSplitRegex = Regex("(?:\\s+/\\s+|[／、；\\u0000\\r\\n]+)")

    // Punctuation-only junk tokens (preserves valid names like "f(x)", "P!nk", "!!!")
    private val punctuationOnlyRegex = Regex("^[-—–?？._·…]+$")

    private val unknownPlaceholders = setOf(
        "",
        "未知歌手",
        "未知",
        "未知艺术家",
        "未知艺人",
        "未知音乐人",
        "佚名",
        "本地音频",
        "本地音乐",
        "unknown",
        "unknown artist",
        "<unknown>",
        "(unknown)",
        "[unknown]",
        "various artists",
        "v.a.",
        "null",
        "nil",
        "none",
        "n/a",
        "-",
        "--",
        "---",
        "?",
        "??",
        "???",
        "(",
        ")",
        "[",
        "]",
        "（",
        "）",
        "【",
        "】"
    )

    private const val MAX_BRACKET_SCAN_WINDOW = 256

    private fun buildProtectedPattern(canonical: String): String {
        val sb = StringBuilder(canonical.length * 2)
        var i = 0
        while (i < canonical.length) {
            when {
                canonical.startsWith(" & ", i) -> {
                    sb.append("\\s*(?:&|/)\\s*")
                    i += 3
                }
                canonical.startsWith(", ", i) -> {
                    sb.append(",\\s*")
                    i += 2
                }
                canonical[i] == ' ' -> {
                    sb.append("\\s+")
                    i += 1
                }
                else -> {
                    sb.append(Regex.escape(canonical[i].toString()))
                    i += 1
                }
            }
        }
        return sb.toString()
    }

    private fun normalizeProtectedMatch(matched: String): String {
        val collapsed = normalizeText(matched)
        val hasSlash = collapsed.contains('/')
        val isSlashBand = collapsed.equals("ac/dc", ignoreCase = true) ||
            collapsed.equals("k/da", ignoreCase = true)
        var result = if (hasSlash && !isSlashBand) {
            collapsed.replace(Regex("\\s*/\\s*"), " & ")
        } else {
            collapsed
        }
        if (!isSlashBand && result.contains('&') && !result.contains("D&E", ignoreCase = true)) {
            result = result.replace(Regex("\\s*&\\s*"), " & ")
        }
        if (result.contains(',')) {
            result = result.replace(Regex(",\\s*"), ", ")
        }
        return result
    }

    fun isUnknownPlaceholder(raw: String): Boolean {
        val cleaned = normalizeText(raw).lowercase(Locale.ROOT)
        return cleaned in unknownPlaceholders || punctuationOnlyRegex.matches(cleaned)
    }

    /**
     * Produces a clean non-clickable display label when [extractArtists] returns empty.
     * Turns raw garbage (`"/ / & ,"`, `"<unknown>"`, zero-width strings) into `"未知歌手"`,
     * while preserving readable non-searchable labels like `"本地音频"`.
     */
    fun fallbackDisplayName(rawArtist: String): String {
        val cleaned = normalizeText(rawArtist)
        if (cleaned.isEmpty()) return "未知歌手"
        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower == "本地音频" || lower == "本地音乐") return cleaned
        val strippedDelims = rawSplitRegex.replace(cleaned, "").trim()
        if (strippedDelims.isEmpty() || isUnknownPlaceholder(cleaned)) return "未知歌手"
        return cleaned
    }

    fun extractArtists(track: Track): List<String> =
        extractArtists(rawArtist = track.artist, structuredArtists = track.artistNames)

    fun extractArtists(rawArtist: String, structuredArtists: List<String> = emptyList()): List<String> {
        if (structuredArtists.isNotEmpty()) {
            val expanded = ArrayList<String>(structuredArtists.size)
            for (entry in structuredArtists) {
                val preCleaned = entry
                    .replace(invisibleJunkRegex, "")
                    .replace(unicodeHorizontalSpaceRegex, " ")
                    .trim { it == ' ' || it == '\t' || it == '\u200C' || it == '\u200D' }
                if (preCleaned.isEmpty()) continue

                if (unambiguousStructuredSplitRegex.containsMatchIn(preCleaned)) {
                    val parts = unambiguousStructuredSplitRegex.split(preCleaned)
                    for (part in parts) {
                        val normPart = normalizeText(part)
                        if (normPart.isEmpty()) continue
                        if (containsFeaturingOrSpacedTimes(normPart)) {
                            expanded.addAll(parseRawString(normPart))
                        } else {
                            expanded.add(normPart)
                        }
                    }
                } else {
                    val normEntry = normalizeText(preCleaned)
                    if (normEntry.isEmpty()) continue
                    if (containsFeaturingOrSpacedTimes(normEntry)) {
                        expanded.addAll(parseRawString(normEntry))
                    } else {
                        expanded.add(normEntry)
                    }
                }
            }
            val fromStructured = collectDeduplicated(expanded)
            if (fromStructured.isNotEmpty()) return fromStructured
        }
        return parseRawString(rawArtist)
    }

    fun parseRawString(raw: String): List<String> {
        val preNormalized = raw
            .replace(invisibleJunkRegex, "")
            .replace(unicodeHorizontalSpaceRegex, " ")
            .replace(controlSeparatorsRegex, " / ")
            .replace(horizontalWhitespaceRegex, " ")
            .trim { it == ' ' || it == '\t' || it == '\u200C' || it == '\u200D' }
        if (preNormalized.isEmpty() || isUnknownPlaceholder(preNormalized)) return emptyList()

        // 1. Protect known atomic artist names (single pass; skipped entirely if no '/', '&', or ',' present)
        val savedTokens = mutableListOf<String>()
        var sentinelPrefix = "\uE000P"
        var working = preNormalized
        if (working.indexOf('/') >= 0 || working.indexOf('&') >= 0 || working.indexOf(',') >= 0) {
            while (working.contains(sentinelPrefix)) {
                sentinelPrefix += "_"
            }
            working = combinedProtectedRegex.replace(working) { match ->
                val idx = savedTokens.size
                savedTokens.add(normalizeProtectedMatch(match.value))
                "$sentinelPrefix${idx}\uE001"
            }
        }

        // 2. Strip standalone "N/A" before '/' splitting so it never splits into ["N", "A"]
        if (working.indexOf('/') >= 0) {
            working = standaloneNaRegex.replace(working, " / ")
        }

        // 3. Unwrap featuring annotations in linear O(N) time + spaced ' × '
        working = unwrapBracketedFeaturing(working)
        working = inlineFeatRegex.replace(working, " / ")
        working = spacedTimesRegex.replace(working, " / ")

        // 4. Split on multi-artist delimiters and restore protected names in O(N)
        val sentinelRegex = if (savedTokens.isNotEmpty()) {
            Regex("${Regex.escape(sentinelPrefix)}(\\d+)\uE001")
        } else {
            null
        }
        val candidates = rawSplitRegex.split(working).map { part ->
            val restored = if (sentinelRegex != null && part.contains(sentinelPrefix)) {
                sentinelRegex.replace(part) { m ->
                    val idx = m.groupValues[1].toIntOrNull()
                    if (idx != null && idx in savedTokens.indices) savedTokens[idx] else m.value
                }
            } else {
                part
            }
            normalizeText(restored)
        }

        return collectDeduplicated(candidates)
    }

    private fun containsFeaturingOrSpacedTimes(text: String): Boolean {
        if (spacedTimesRegex.containsMatchIn(text)) return true
        if (inlineFeatRegex.containsMatchIn(text)) return true
        return unwrapBracketedFeaturing(text) != text
    }

    /**
     * Linear-time O(N) balanced-bracket scanner that unwraps featuring annotations like
     * `(feat. B)`, `[ft. B & C]`, `（feat. 王菲 (Live)）`, and `(feat.)` without any regex backtracking.
     */
    private fun unwrapBracketedFeaturing(input: String): String {
        val len = input.length
        if (len < 5) return input
        var hasOpenBracket = false
        for (idx in 0 until len) {
            val ch = input[idx]
            if (ch == '(' || ch == '[' || ch == '（' || ch == '【') {
                hasOpenBracket = true
                break
            }
        }
        if (!hasOpenBracket) return input

        // Pre-check whether any closing bracket exists at all to short-circuit pathological unclosed inputs
        var lastCloseBracket = -1
        for (idx in len - 1 downTo 0) {
            val ch = input[idx]
            if (ch == ')' || ch == ']' || ch == '）' || ch == '】') {
                lastCloseBracket = idx
                break
            }
        }
        if (lastCloseBracket < 0) return input

        val sb = StringBuilder(len + 16)
        var i = 0
        while (i < len) {
            val open = input[i]
            val expectedClose = matchingCloseBracket(open)
            if (expectedClose != null && i < lastCloseBracket) {
                val contentStart = parseFeatKeywordAfterBracket(input, i + 1, expectedClose)
                if (contentStart >= 0) {
                    val scanEnd = minOf(len, contentStart + MAX_BRACKET_SCAN_WINDOW)
                    var depth = 1
                    var j = contentStart
                    var foundClose = -1
                    while (j < scanEnd) {
                        val c = input[j]
                        if (c == '(' || c == '[' || c == '（' || c == '【') {
                            depth++
                        } else if (c == ')' || c == ']' || c == '）' || c == '】') {
                            depth--
                            if (depth == 0) {
                                foundClose = j
                                break
                            }
                        }
                        j++
                    }
                    if (foundClose >= 0) {
                        val inner = input.substring(contentStart, foundClose).trim()
                        if (inner.isNotEmpty()) {
                            sb.append(" / ").append(inner).append(" / ")
                        } else {
                            sb.append(' ')
                        }
                        i = foundClose + 1
                        continue
                    }
                }
            }
            sb.append(open)
            i++
        }
        return sb.toString()
    }

    private fun matchingCloseBracket(ch: Char): Char? = when (ch) {
        '(' -> ')'
        '[' -> ']'
        '（' -> '）'
        '【' -> '】'
        else -> null
    }

    /**
     * If `input` starting at [startIndex] begins with a featuring keyword (`feat.`, `ft.`, `featuring`,
     * `feat `, `ft `, `feat:`, etc.), returns the index where the featured artist text starts; else `-1`.
     */
    private fun parseFeatKeywordAfterBracket(input: String, startIndex: Int, closeBracket: Char): Int {
        val len = input.length
        var p = startIndex
        while (p < len && input[p] == ' ') p++
        if (p >= len) return -1

        val kwLen = when {
            input.regionMatches(p, "featuring", 0, 9, ignoreCase = true) -> 9
            input.regionMatches(p, "feat", 0, 4, ignoreCase = true) -> 4
            input.regionMatches(p, "ft", 0, 2, ignoreCase = true) -> 2
            else -> return -1
        }
        var after = p + kwLen
        if (after >= len) return -1

        val next = input[after]
        return when {
            // "feat." / "ft." / "featuring." optionally followed by spaces/colons or immediate close bracket "(feat.)"
            next == '.' -> {
                after++
                while (after < len && (input[after] == ' ' || input[after] == ':' || input[after] == '：')) after++
                after
            }
            // "feat " / "ft " / "featuring " / "feat:" / "featuring："
            next == ' ' || next == ':' || next == '：' -> {
                while (after < len && (input[after] == ' ' || input[after] == '.' || input[after] == ':' || input[after] == '：')) after++
                after
            }
            // Immediate close bracket e.g. "(feat)"
            next == closeBracket -> after
            // Anything else like "(ftw)" -> NOT a featuring tag
            else -> -1
        }
    }

    private fun normalizeText(input: String): String =
        input
            .replace(invisibleJunkRegex, "")
            .replace(unicodeHorizontalSpaceRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim { it.isWhitespace() || it == '\u200C' || it == '\u200D' }

    private fun collectDeduplicated(candidates: List<String>): List<String> {
        val seen = HashSet<String>(candidates.size)
        val result = ArrayList<String>(candidates.size)
        for (candidate in candidates) {
            val cleaned = normalizeText(candidate)
            if (cleaned.isEmpty() || isUnknownPlaceholder(cleaned)) continue
            val key = cleaned.lowercase(Locale.ROOT)
            if (seen.add(key)) {
                result.add(cleaned)
            }
        }
        return result
    }
}

/**
 * Pure, framework-independent layout planner for the single-line artist/album metadata row
 * in the full-screen player. Guarantees zero clipping/overflow across single artists,
 * multi-artist collaborations, huge choruses, narrow screens, and large accessibility fonts.
 */
object ArtistRowLayoutPlanner {

    private fun sanitizeDimension(value: Float): Float =
        if (value.isFinite() && value > 0f) value else 0f

    fun plan(
        artistCount: Int,
        maxWidth: Float,
        artistWidthAt: (Int) -> Float,
        separatorWidth: Float,
        singleArtistChevronWidth: Float,
        overflowBadgeWidthForHidden: (Int) -> Float,
        albumFullWidth: Float,
        minAlbumDisplayWidth: Float,
        minTruncatedArtistWidth: Float,
        albumLeadingGap: Float = 0f
    ): ArtistRowPlan {
        if (artistCount <= 0 || !maxWidth.isFinite() || maxWidth <= 0f) {
            return ArtistRowPlan(
                visibleCount = 0,
                hiddenCount = 0,
                showAlbum = false,
                truncateSingleVisibleArtist = false,
                showCompactSummaryOnly = false,
                singleArtistMaxWidth = null
            )
        }

        val safeSep = sanitizeDimension(separatorWidth)
        val safeChevron = sanitizeDimension(singleArtistChevronWidth)
        val safeAlbumFull = sanitizeDimension(albumFullWidth)
        val safeMinAlbum = sanitizeDimension(minAlbumDisplayWidth)
        val safeMinTrunc = sanitizeDimension(minTruncatedArtistWidth)
        val safeAlbumGap = sanitizeDimension(albumLeadingGap)

        if (artistCount == 1) {
            val w0 = sanitizeDimension(artistWidthAt(0))
            val artistTotal = w0 + safeChevron
            if (safeAlbumFull <= 0f) {
                return ArtistRowPlan(
                    visibleCount = 1,
                    hiddenCount = 0,
                    showAlbum = false,
                    truncateSingleVisibleArtist = artistTotal > maxWidth,
                    showCompactSummaryOnly = false,
                    singleArtistMaxWidth = null
                )
            }

            val effectiveMinAlbum = minOf(safeAlbumFull, safeMinAlbum)
            val fitsArtistAndFullAlbum = artistTotal + safeAlbumFull <= maxWidth
            val fitsFullArtistAndMinAlbum = artistTotal <= maxWidth && (maxWidth - artistTotal) >= effectiveMinAlbum && effectiveMinAlbum > 0f
            if (fitsArtistAndFullAlbum || fitsFullArtistAndMinAlbum) {
                return ArtistRowPlan(
                    visibleCount = 1,
                    hiddenCount = 0,
                    showAlbum = true,
                    truncateSingleVisibleArtist = false,
                    showCompactSummaryOnly = false,
                    singleArtistMaxWidth = null
                )
            }

            // Long single artist + album: never let album crush the primary artist below 60% / minBudget
            val minArtistBudget = safeMinTrunc + safeChevron
            val minShareCap = maxOf(maxWidth * 0.60f, minArtistBudget).coerceAtMost(maxWidth)
            val preferredArtistCap = maxOf(maxWidth * 0.68f, minArtistBudget).coerceAtMost(maxWidth)
            val canShowFullShortAlbum = effectiveMinAlbum > 0f && safeAlbumFull <= (maxWidth - minShareCap)
            val canShowSplitAlbum = effectiveMinAlbum > 0f && (maxWidth - preferredArtistCap) >= effectiveMinAlbum
            val showAlbum = canShowFullShortAlbum || canShowSplitAlbum
            if (!showAlbum) {
                return ArtistRowPlan(
                    visibleCount = 1,
                    hiddenCount = 0,
                    showAlbum = false,
                    truncateSingleVisibleArtist = artistTotal > maxWidth,
                    showCompactSummaryOnly = false,
                    singleArtistMaxWidth = null
                )
            }

            val artistCap = if (canShowFullShortAlbum) {
                (maxWidth - safeAlbumFull).coerceIn(minShareCap, maxWidth)
            } else {
                preferredArtistCap
            }
            return ArtistRowPlan(
                visibleCount = 1,
                hiddenCount = 0,
                showAlbum = true,
                truncateSingleVisibleArtist = artistTotal > artistCap,
                showCompactSummaryOnly = false,
                singleArtistMaxWidth = artistCap
            )
        }

        // Multi-artist (2+): measure lazily only until cumulative width exceeds maxWidth
        val measured = ArrayList<Float>(minOf(artistCount, 12))
        var runningWidth = 0f
        var allFit = true
        for (i in 0 until artistCount) {
            val w = sanitizeDimension(artistWidthAt(i))
            measured.add(w)
            val nextWidth = if (i == 0) w else runningWidth + safeSep + w
            if (nextWidth > maxWidth) {
                allFit = false
                break
            }
            runningWidth = nextWidth
        }

        if (allFit) {
            val remaining = maxWidth - runningWidth
            val effectiveMinAlbum = minOf(safeAlbumFull, safeMinAlbum)
            val showAlbum = safeAlbumFull > 0f && remaining >= safeAlbumGap + effectiveMinAlbum
            return ArtistRowPlan(
                visibleCount = artistCount,
                hiddenCount = 0,
                showAlbum = showAlbum,
                truncateSingleVisibleArtist = false,
                showCompactSummaryOnly = false,
                singleArtistMaxWidth = null
            )
        }

        // Overflow: find largest prefix k (1 <= k < artistCount) that fits alongside the "+hidden" badge
        var bestK = 0
        var prefixWidth = 0f
        val maxCandidateK = minOf(measured.size, artistCount - 1)
        for (k in 1..maxCandidateK) {
            prefixWidth = if (k == 1) measured[0] else prefixWidth + safeSep + measured[k - 1]
            val badgeW = sanitizeDimension(overflowBadgeWidthForHidden(artistCount - k))
            if (prefixWidth + badgeW <= maxWidth) {
                bestK = k
            } else {
                break
            }
        }

        if (bestK >= 1) {
            return ArtistRowPlan(
                visibleCount = bestK,
                hiddenCount = artistCount - bestK,
                showAlbum = false,
                truncateSingleVisibleArtist = false,
                showCompactSummaryOnly = false,
                singleArtistMaxWidth = null
            )
        }

        // Even artist #1 alone + badge exceeds maxWidth: truncate artist #1 if enough readable width remains
        val badgeW = sanitizeDimension(overflowBadgeWidthForHidden(artistCount - 1))
        return if (maxWidth - badgeW >= safeMinTrunc) {
            ArtistRowPlan(
                visibleCount = 1,
                hiddenCount = artistCount - 1,
                showAlbum = false,
                truncateSingleVisibleArtist = true,
                showCompactSummaryOnly = false,
                singleArtistMaxWidth = null
            )
        } else {
            // Extreme narrow viewport / oversized accessibility font: show compact summary pill only
            ArtistRowPlan(
                visibleCount = 0,
                hiddenCount = artistCount,
                showAlbum = false,
                truncateSingleVisibleArtist = false,
                showCompactSummaryOnly = true,
                singleArtistMaxWidth = null
            )
        }
    }
}
