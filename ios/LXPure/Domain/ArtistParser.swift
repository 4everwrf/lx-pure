import Foundation

struct ArtistRowPlan: Equatable, Sendable {
    let visibleCount: Int
    let hiddenCount: Int
    let showAlbum: Bool
    let truncateSingleVisibleArtist: Bool
    let showCompactSummaryOnly: Bool
    let singleArtistMaxWidth: Double?

    init(
        visibleCount: Int,
        hiddenCount: Int,
        showAlbum: Bool,
        truncateSingleVisibleArtist: Bool,
        showCompactSummaryOnly: Bool,
        singleArtistMaxWidth: Double? = nil
    ) {
        self.visibleCount = visibleCount
        self.hiddenCount = hiddenCount
        self.showAlbum = showAlbum
        self.truncateSingleVisibleArtist = truncateSingleVisibleArtist
        self.showCompactSummaryOnly = showCompactSummaryOnly
        self.singleArtistMaxWidth = singleArtistMaxWidth
    }
}

enum ArtistParser {
    private static let maxBracketScanWindow = 256

    private static let protectedAtomicNames: [String] = [
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
    ].sorted { $0.count > $1.count }

    private static let combinedProtectedRegex: NSRegularExpression = {
        let alts = protectedAtomicNames.map { buildProtectedPattern($0) }.joined(separator: "|")
        return try! NSRegularExpression(
            pattern: "(?<![A-Za-z0-9])(?:\(alts))(?![A-Za-z0-9])",
            options: [.caseInsensitive]
        )
    }()

    // Note: ICU NSRegularExpression requires \uHHHH (no braces), NOT Swift \u{HHHH}.
    private static let invisibleJunkRegex = try! NSRegularExpression(
        pattern: "[\\u00AD\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]"
    )
    private static let unicodeHorizontalSpaceRegex = try! NSRegularExpression(
        pattern: "[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]"
    )
    private static let horizontalWhitespaceRegex = try! NSRegularExpression(pattern: "[ \\t]+")
    private static let controlSeparatorsRegex = try! NSRegularExpression(pattern: "[\\u0000\\r\\n]+")
    private static let whitespaceRegex = try! NSRegularExpression(pattern: "\\s+")
    private static let standaloneNaRegex = try! NSRegularExpression(
        pattern: "(?<![A-Za-z0-9])n/a(?![A-Za-z0-9])",
        options: [.caseInsensitive]
    )
    private static let inlineFeatRegex = try! NSRegularExpression(
        pattern: "(?<=[^\\s/／、,，;；&＆|｜])(?:\\s*[-–—])?\\s+(?:(?:feat|ft)\\.\\s*|featuring[\\s.:：]+|(?:feat|ft)[\\s:：]+)",
        options: [.caseInsensitive]
    )
    private static let spacedTimesRegex = try! NSRegularExpression(pattern: "\\s+×\\s+")
    private static let rawSplitRegex = try! NSRegularExpression(pattern: "[/／、，;；&＆|｜]|(?<!\\d),|,(?!\\d)")
    private static let unambiguousStructuredSplitRegex = try! NSRegularExpression(
        pattern: "(?:\\s+/\\s+|[／、；\\u0000\\r\\n]+)"
    )
    private static let punctuationOnlyRegex = try! NSRegularExpression(pattern: "^[-—–?？._·…]+$")
    private static let slashSpacesRegex = try! NSRegularExpression(pattern: "\\s*/\\s*")
    private static let ampSpacesRegex = try! NSRegularExpression(pattern: "\\s*&\\s*")
    private static let commaSpacesRegex = try! NSRegularExpression(pattern: ",\\s*")

    private static let unknownPlaceholders: Set<String> = [
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
    ]

    private static let boundaryTrimSet: CharacterSet = {
        var set = CharacterSet.whitespacesAndNewlines
        set.insert(charactersIn: "\u{200C}\u{200D}")
        return set
    }()

    private static func buildProtectedPattern(_ canonical: String) -> String {
        let chars = Array(canonical)
        var out = ""
        var i = 0
        while i < chars.count {
            if i + 2 < chars.count && chars[i] == " " && chars[i + 1] == "&" && chars[i + 2] == " " {
                out.append("\\s*(?:&|/)\\s*")
                i += 3
            } else if i + 1 < chars.count && chars[i] == "," && chars[i + 1] == " " {
                out.append(",\\s*")
                i += 2
            } else if chars[i] == " " {
                out.append("\\s+")
                i += 1
            } else {
                out.append(NSRegularExpression.escapedPattern(for: String(chars[i])))
                i += 1
            }
        }
        return out
    }

    private static func normalizeProtectedMatch(_ matched: String) -> String {
        let collapsed = normalizeText(matched)
        let lower = collapsed.lowercased()
        let isSlashBand = (lower == "ac/dc" || lower == "k/da")
        var result = (collapsed.contains("/") && !isSlashBand)
            ? replaceRegex(slashSpacesRegex, in: collapsed, with: " & ")
            : collapsed
        if !isSlashBand && result.contains("&") && !result.lowercased().contains("d&e") {
            result = replaceRegex(ampSpacesRegex, in: result, with: " & ")
        }
        if result.contains(",") {
            result = replaceRegex(commaSpacesRegex, in: result, with: ", ")
        }
        return result
    }

    static func isUnknownPlaceholder(_ raw: String) -> Bool {
        let cleaned = normalizeText(raw).lowercased()
        return unknownPlaceholders.contains(cleaned) || matchesRegex(punctuationOnlyRegex, in: cleaned)
    }

    static func fallbackDisplayName(_ rawArtist: String) -> String {
        let cleaned = normalizeText(rawArtist)
        if cleaned.isEmpty { return "未知歌手" }
        let lower = cleaned.lowercased()
        if lower == "本地音频" || lower == "本地音乐" { return cleaned }
        let strippedDelims = replaceRegex(rawSplitRegex, in: cleaned, with: "")
            .trimmingCharacters(in: boundaryTrimSet)
        if strippedDelims.isEmpty || isUnknownPlaceholder(cleaned) { return "未知歌手" }
        return cleaned
    }

    static func extractArtists(from track: Track) -> [String] {
        extractArtists(rawArtist: track.artist, structuredArtists: track.artistNames)
    }

    static func extractArtists(rawArtist: String, structuredArtists: [String] = []) -> [String] {
        if !structuredArtists.isEmpty {
            var expanded: [String] = []
            expanded.reserveCapacity(structuredArtists.count)
            for entry in structuredArtists {
                let preCleaned = replaceRegex(unicodeHorizontalSpaceRegex, in: replaceRegex(invisibleJunkRegex, in: entry, with: ""), with: " ")
                    .trimmingCharacters(in: CharacterSet(charactersIn: " \t\u{200C}\u{200D}"))
                if preCleaned.isEmpty { continue }

                if matchesRegex(unambiguousStructuredSplitRegex, in: preCleaned) {
                    let parts = splitByRegex(unambiguousStructuredSplitRegex, in: preCleaned)
                    for part in parts {
                        let normPart = normalizeText(part)
                        if normPart.isEmpty { continue }
                        if containsFeaturingOrSpacedTimes(normPart) {
                            expanded.append(contentsOf: parseRawString(normPart))
                        } else {
                            expanded.append(normPart)
                        }
                    }
                } else {
                    let normEntry = normalizeText(preCleaned)
                    if normEntry.isEmpty { continue }
                    if containsFeaturingOrSpacedTimes(normEntry) {
                        expanded.append(contentsOf: parseRawString(normEntry))
                    } else {
                        expanded.append(normEntry)
                    }
                }
            }
            let deduped = collectDeduplicated(expanded)
            if !deduped.isEmpty { return deduped }
        }
        return parseRawString(rawArtist)
    }

    static func parseRawString(_ raw: String) -> [String] {
        var working = replaceRegex(invisibleJunkRegex, in: raw, with: "")
        working = replaceRegex(unicodeHorizontalSpaceRegex, in: working, with: " ")
        working = replaceRegex(controlSeparatorsRegex, in: working, with: " / ")
        working = replaceRegex(horizontalWhitespaceRegex, in: working, with: " ")
            .trimmingCharacters(in: boundaryTrimSet)
        guard !working.isEmpty, !isUnknownPlaceholder(working) else { return [] }

        // 1. Protect known atomic artist names (single pass; skipped if no '/', '&', or ',')
        var savedTokens: [String] = []
        var sentinelPrefix = "\u{E000}P"
        if working.contains("/") || working.contains("&") || working.contains(",") {
            while working.contains(sentinelPrefix) {
                sentinelPrefix.append("_")
            }
            working = replaceMatches(combinedProtectedRegex, in: working) { matched in
                let idx = savedTokens.count
                savedTokens.append(normalizeProtectedMatch(matched))
                return "\(sentinelPrefix)\(idx)\u{E001}"
            }
        }

        // 2. Strip standalone "N/A" before '/' splitting so it never splits into ["N", "A"]
        if working.contains("/") {
            working = replaceRegex(standaloneNaRegex, in: working, with: " / ")
        }

        // 3. Unwrap featuring annotations in linear O(N) time + spaced ' × '
        working = unwrapBracketedFeaturing(working)
        working = replaceRegex(inlineFeatRegex, in: working, with: " / ")
        working = replaceRegex(spacedTimesRegex, in: working, with: " / ")

        // 4. Split on multi-artist delimiters and restore protected names in O(N)
        let sentinelRegex: NSRegularExpression? = savedTokens.isEmpty
            ? nil
            : (try? NSRegularExpression(pattern: "\(NSRegularExpression.escapedPattern(for: sentinelPrefix))(\\d+)\u{E001}"))
        let rawParts = splitByRegex(rawSplitRegex, in: working)
        let restoredParts = rawParts.map { part -> String in
            let restored: String
            if let sentinelRegex, part.contains(sentinelPrefix) {
                restored = restoreSentinels(sentinelRegex, in: part, savedTokens: savedTokens)
            } else {
                restored = part
            }
            return normalizeText(restored)
        }

        return collectDeduplicated(restoredParts)
    }

    private static func containsFeaturingOrSpacedTimes(_ text: String) -> Bool {
        if matchesRegex(spacedTimesRegex, in: text) { return true }
        if matchesRegex(inlineFeatRegex, in: text) { return true }
        return unwrapBracketedFeaturing(text) != text
    }

    private static func unwrapBracketedFeaturing(_ input: String) -> String {
        let units = Array(input.utf16)
        let len = units.count
        if len < 5 { return input }

        let openParen: UInt16 = 0x0028      // (
        let closeParen: UInt16 = 0x0029     // )
        let openSquare: UInt16 = 0x005B     // [
        let closeSquare: UInt16 = 0x005D    // ]
        let openFullParen: UInt16 = 0xFF08  // （
        let closeFullParen: UInt16 = 0xFF09 // ）
        let openFullBrack: UInt16 = 0x3010  // 【
        let closeFullBrack: UInt16 = 0x3011 // 】

        func isOpen(_ u: UInt16) -> Bool {
            u == openParen || u == openSquare || u == openFullParen || u == openFullBrack
        }
        func isClose(_ u: UInt16) -> Bool {
            u == closeParen || u == closeSquare || u == closeFullParen || u == closeFullBrack
        }
        func matchingClose(_ u: UInt16) -> UInt16? {
            switch u {
            case openParen: return closeParen
            case openSquare: return closeSquare
            case openFullParen: return closeFullParen
            case openFullBrack: return closeFullBrack
            default: return nil
            }
        }

        var hasOpen = false
        for u in units where isOpen(u) {
            hasOpen = true
            break
        }
        if !hasOpen { return input }

        var lastClose = -1
        for idx in stride(from: len - 1, through: 0, by: -1) {
            if isClose(units[idx]) {
                lastClose = idx
                break
            }
        }
        if lastClose < 0 { return input }

        let nsInput = input as NSString
        var output = ""
        output.reserveCapacity(input.count + 16)
        var i = 0
        var copyStart = 0

        while i < len {
            let u = units[i]
            if let expectedClose = matchingClose(u), i < lastClose {
                let contentStart = parseFeatKeywordAfterBracket(units: units, startIndex: i + 1, closeBracket: expectedClose)
                if contentStart >= 0 {
                    let scanEnd = min(len, contentStart + maxBracketScanWindow)
                    var depth = 1
                    var j = contentStart
                    var foundClose = -1
                    while j < scanEnd {
                        let c = units[j]
                        if isOpen(c) {
                            depth += 1
                        } else if isClose(c) {
                            depth -= 1
                            if depth == 0 {
                                foundClose = j
                                break
                            }
                        }
                        j += 1
                    }
                    if foundClose >= 0 {
                        if i > copyStart {
                            output.append(nsInput.substring(with: NSRange(location: copyStart, length: i - copyStart)))
                        }
                        let inner = nsInput
                            .substring(with: NSRange(location: contentStart, length: foundClose - contentStart))
                            .trimmingCharacters(in: boundaryTrimSet)
                        if !inner.isEmpty {
                            output.append(" / ")
                            output.append(inner)
                            output.append(" / ")
                        } else {
                            output.append(" ")
                        }
                        i = foundClose + 1
                        copyStart = i
                        continue
                    }
                }
            }
            i += 1
        }

        if copyStart == 0 { return input }
        if copyStart < len {
            output.append(nsInput.substring(with: NSRange(location: copyStart, length: len - copyStart)))
        }
        return output
    }

    private static func parseFeatKeywordAfterBracket(units: [UInt16], startIndex: Int, closeBracket: UInt16) -> Int {
        let len = units.count
        var p = startIndex
        while p < len && units[p] == 0x0020 { p += 1 }
        if p >= len { return -1 }

        func matchesASCII(_ kw: [UInt16], at offset: Int) -> Bool {
            guard offset + kw.count <= len else { return false }
            for k in 0..<kw.count {
                var ch = units[offset + k]
                if ch >= 65 && ch <= 90 { ch += 32 } // A-Z -> a-z
                if ch != kw[k] { return false }
            }
            return true
        }

        let kwFeaturing: [UInt16] = [102, 101, 97, 116, 117, 114, 105, 110, 103]
        let kwFeat: [UInt16] = [102, 101, 97, 116]
        let kwFt: [UInt16] = [102, 116]

        let kwLen: Int
        if matchesASCII(kwFeaturing, at: p) {
            kwLen = 9
        } else if matchesASCII(kwFeat, at: p) {
            kwLen = 4
        } else if matchesASCII(kwFt, at: p) {
            kwLen = 2
        } else {
            return -1
        }

        var after = p + kwLen
        if after >= len { return -1 }
        let next = units[after]
        let dot: UInt16 = 0x002E
        let space: UInt16 = 0x0020
        let colon: UInt16 = 0x003A
        let fullColon: UInt16 = 0xFF1A

        if next == dot {
            after += 1
            while after < len && (units[after] == space || units[after] == colon || units[after] == fullColon) {
                after += 1
            }
            return after
        } else if next == space || next == colon || next == fullColon {
            while after < len && (units[after] == space || units[after] == dot || units[after] == colon || units[after] == fullColon) {
                after += 1
            }
            return after
        } else if next == closeBracket {
            return after
        } else {
            return -1
        }
    }

    private static func normalizeText(_ input: String) -> String {
        let noJunk = replaceRegex(invisibleJunkRegex, in: input, with: "")
        let asciiSpaces = replaceRegex(unicodeHorizontalSpaceRegex, in: noJunk, with: " ")
        return replaceRegex(whitespaceRegex, in: asciiSpaces, with: " ")
            .trimmingCharacters(in: boundaryTrimSet)
    }

    private static func collectDeduplicated(_ candidates: [String]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        result.reserveCapacity(candidates.count)
        for candidate in candidates {
            let cleaned = normalizeText(candidate)
            if cleaned.isEmpty || isUnknownPlaceholder(cleaned) { continue }
            let key = cleaned.lowercased()
            if seen.insert(key).inserted {
                result.append(cleaned)
            }
        }
        return result
    }

    private static func matchesRegex(_ regex: NSRegularExpression, in text: String) -> Bool {
        let nsText = text as NSString
        return regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: nsText.length)) != nil
    }

    private static func replaceRegex(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        let nsText = text as NSString
        return regex.stringByReplacingMatches(in: text, options: [], range: NSRange(location: 0, length: nsText.length), withTemplate: template)
    }

    private static func replaceMatches(
        _ regex: NSRegularExpression,
        in text: String,
        transform: (String) -> String
    ) -> String {
        let nsText = text as NSString
        let matches = regex.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        guard !matches.isEmpty else { return text }
        var output = ""
        output.reserveCapacity(text.count)
        var cursor = 0
        for match in matches {
            let r = match.range
            if r.location > cursor {
                output.append(nsText.substring(with: NSRange(location: cursor, length: r.location - cursor)))
            }
            output.append(transform(nsText.substring(with: r)))
            cursor = r.location + r.length
        }
        if cursor < nsText.length {
            output.append(nsText.substring(with: NSRange(location: cursor, length: nsText.length - cursor)))
        }
        return output
    }

    private static func restoreSentinels(
        _ regex: NSRegularExpression,
        in text: String,
        savedTokens: [String]
    ) -> String {
        let nsText = text as NSString
        let matches = regex.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        guard !matches.isEmpty else { return text }
        var output = ""
        output.reserveCapacity(text.count)
        var cursor = 0
        for match in matches {
            let r = match.range
            if r.location > cursor {
                output.append(nsText.substring(with: NSRange(location: cursor, length: r.location - cursor)))
            }
            if match.numberOfRanges > 1,
               let idx = Int(nsText.substring(with: match.range(at: 1))),
               idx >= 0 && idx < savedTokens.count {
                output.append(savedTokens[idx])
            } else {
                output.append(nsText.substring(with: r))
            }
            cursor = r.location + r.length
        }
        if cursor < nsText.length {
            output.append(nsText.substring(with: NSRange(location: cursor, length: nsText.length - cursor)))
        }
        return output
    }

    private static func splitByRegex(_ regex: NSRegularExpression, in text: String) -> [String] {
        let nsText = text as NSString
        let matches = regex.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        guard !matches.isEmpty else { return [text] }
        var parts: [String] = []
        parts.reserveCapacity(matches.count + 1)
        var cursor = 0
        for match in matches {
            let r = match.range
            parts.append(nsText.substring(with: NSRange(location: cursor, length: r.location - cursor)))
            cursor = r.location + r.length
        }
        parts.append(nsText.substring(with: NSRange(location: cursor, length: nsText.length - cursor)))
        return parts
    }
}

enum ArtistRowLayoutPlanner {
    private static func sanitizeDimension(_ value: Double) -> Double {
        (value.isFinite && value > 0) ? value : 0
    }

    static func plan(
        artistCount: Int,
        maxWidth: Double,
        artistWidthAt: (Int) -> Double,
        separatorWidth: Double,
        singleArtistChevronWidth: Double,
        overflowBadgeWidthForHidden: (Int) -> Double,
        albumFullWidth: Double,
        minAlbumDisplayWidth: Double,
        minTruncatedArtistWidth: Double,
        albumLeadingGap: Double = 0
    ) -> ArtistRowPlan {
        if artistCount <= 0 || !maxWidth.isFinite || maxWidth <= 0 {
            return ArtistRowPlan(
                visibleCount: 0,
                hiddenCount: 0,
                showAlbum: false,
                truncateSingleVisibleArtist: false,
                showCompactSummaryOnly: false,
                singleArtistMaxWidth: nil
            )
        }

        let safeSep = sanitizeDimension(separatorWidth)
        let safeChevron = sanitizeDimension(singleArtistChevronWidth)
        let safeAlbumFull = sanitizeDimension(albumFullWidth)
        let safeMinAlbum = sanitizeDimension(minAlbumDisplayWidth)
        let safeMinTrunc = sanitizeDimension(minTruncatedArtistWidth)
        let safeAlbumGap = sanitizeDimension(albumLeadingGap)

        if artistCount == 1 {
            let w0 = sanitizeDimension(artistWidthAt(0))
            let artistTotal = w0 + safeChevron
            if safeAlbumFull <= 0 {
                return ArtistRowPlan(
                    visibleCount: 1,
                    hiddenCount: 0,
                    showAlbum: false,
                    truncateSingleVisibleArtist: artistTotal > maxWidth,
                    showCompactSummaryOnly: false,
                    singleArtistMaxWidth: nil
                )
            }

            let effectiveMinAlbum = min(safeAlbumFull, safeMinAlbum)
            let fitsArtistAndFullAlbum = artistTotal + safeAlbumFull <= maxWidth
            let fitsFullArtistAndMinAlbum = artistTotal <= maxWidth && (maxWidth - artistTotal) >= effectiveMinAlbum && effectiveMinAlbum > 0
            if fitsArtistAndFullAlbum || fitsFullArtistAndMinAlbum {
                return ArtistRowPlan(
                    visibleCount: 1,
                    hiddenCount: 0,
                    showAlbum: true,
                    truncateSingleVisibleArtist: false,
                    showCompactSummaryOnly: false,
                    singleArtistMaxWidth: nil
                )
            }

            let minArtistBudget = safeMinTrunc + safeChevron
            let minShareCap = min(max(maxWidth * 0.60, minArtistBudget), maxWidth)
            let preferredArtistCap = min(max(maxWidth * 0.68, minArtistBudget), maxWidth)
            let canShowFullShortAlbum = effectiveMinAlbum > 0 && safeAlbumFull <= (maxWidth - minShareCap)
            let canShowSplitAlbum = effectiveMinAlbum > 0 && (maxWidth - preferredArtistCap) >= effectiveMinAlbum
            let showAlbum = canShowFullShortAlbum || canShowSplitAlbum
            if !showAlbum {
                return ArtistRowPlan(
                    visibleCount: 1,
                    hiddenCount: 0,
                    showAlbum: false,
                    truncateSingleVisibleArtist: artistTotal > maxWidth,
                    showCompactSummaryOnly: false,
                    singleArtistMaxWidth: nil
                )
            }

            let artistCap: Double
            if canShowFullShortAlbum {
                artistCap = min(max(maxWidth - safeAlbumFull, minShareCap), maxWidth)
            } else {
                artistCap = preferredArtistCap
            }
            return ArtistRowPlan(
                visibleCount: 1,
                hiddenCount: 0,
                showAlbum: true,
                truncateSingleVisibleArtist: artistTotal > artistCap,
                showCompactSummaryOnly: false,
                singleArtistMaxWidth: artistCap
            )
        }

        // Multi-artist (2+): measure lazily only until cumulative width exceeds maxWidth
        var measured: [Double] = []
        measured.reserveCapacity(min(artistCount, 12))
        var runningWidth: Double = 0
        var allFit = true
        for idx in 0..<artistCount {
            let w = sanitizeDimension(artistWidthAt(idx))
            measured.append(w)
            let nextWidth = idx == 0 ? w : (runningWidth + safeSep + w)
            if nextWidth > maxWidth {
                allFit = false
                break
            }
            runningWidth = nextWidth
        }

        if allFit {
            let remaining = maxWidth - runningWidth
            let effectiveMinAlbum = min(safeAlbumFull, safeMinAlbum)
            let showAlbum = safeAlbumFull > 0 && remaining >= (safeAlbumGap + effectiveMinAlbum)
            return ArtistRowPlan(
                visibleCount: artistCount,
                hiddenCount: 0,
                showAlbum: showAlbum,
                truncateSingleVisibleArtist: false,
                showCompactSummaryOnly: false,
                singleArtistMaxWidth: nil
            )
        }

        var bestK = 0
        var prefixWidth: Double = 0
        let maxCandidateK = min(measured.count, artistCount - 1)
        if maxCandidateK >= 1 {
            for k in 1...maxCandidateK {
                prefixWidth = k == 1 ? measured[0] : (prefixWidth + safeSep + measured[k - 1])
                let badgeW = sanitizeDimension(overflowBadgeWidthForHidden(artistCount - k))
                if prefixWidth + badgeW <= maxWidth {
                    bestK = k
                } else {
                    break
                }
            }
        }

        if bestK >= 1 {
            return ArtistRowPlan(
                visibleCount: bestK,
                hiddenCount: artistCount - bestK,
                showAlbum: false,
                truncateSingleVisibleArtist: false,
                showCompactSummaryOnly: false,
                singleArtistMaxWidth: nil
            )
        }

        let badgeW = sanitizeDimension(overflowBadgeWidthForHidden(artistCount - 1))
        if (maxWidth - badgeW) >= safeMinTrunc {
            return ArtistRowPlan(
                visibleCount: 1,
                hiddenCount: artistCount - 1,
                showAlbum: false,
                truncateSingleVisibleArtist: true,
                showCompactSummaryOnly: false,
                singleArtistMaxWidth: nil
            )
        } else {
            return ArtistRowPlan(
                visibleCount: 0,
                hiddenCount: artistCount,
                showAlbum: false,
                truncateSingleVisibleArtist: false,
                showCompactSummaryOnly: true,
                singleArtistMaxWidth: nil
            )
        }
    }
}
