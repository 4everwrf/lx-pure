import Foundation

enum LrcParser {
    private static let timeTagRegex = try! NSRegularExpression(
        pattern: "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d+))?\\]",
        options: []
    )
    private static let offsetTagRegex = try! NSRegularExpression(
        pattern: "^\\[offset:([+-]?\\d+)\\]$",
        options: [.caseInsensitive]
    )
    private static let yrcLineRegex = try! NSRegularExpression(
        pattern: "^\\[(\\d+),\\d+\\]",
        options: []
    )
    private static let yrcWordRegex = try! NSRegularExpression(
        pattern: "\\(-?\\d+,-?\\d+(?:,-?\\d+)?\\)",
        options: []
    )
    private static let qishuiWordRegex = try! NSRegularExpression(
        pattern: "<-?\\d+,-?\\d+(?:,-?\\d+)?>",
        options: []
    )

    static func parse(_ rawLrc: String?, translation rawTranslation: String? = nil) -> ParsedLyrics {
        let (primary, primaryOffsetMs) = parseSingle(rawLrc)
        if primary.isEmpty { return ParsedLyrics() }

        // Inherit primary [offset:] when translation track omits its own [offset:] tag
        let (rawTranslations, _) = parseSingle(rawTranslation, defaultOffsetMs: primaryOffsetMs)
        let translations = rawTranslations.filter {
            let trimmed = $0.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.isEmpty && trimmed != "//"
        }
        if translations.isEmpty {
            let indexed = primary.enumerated().map { idx, line in
                LyricLine(timeMs: line.timeMs, text: line.text, translation: nil, lineIndex: idx)
            }
            return ParsedLyrics(lines: indexed, hasTranslation: false)
        }

        // 1-to-1 nearest matching avoids bleeding a single translation onto adjacent lines
        var usedTrans = Set<Int>()
        var merged: [LyricLine] = []
        merged.reserveCapacity(primary.count)

        for (idx, line) in primary.enumerated() {
            var bestIdx = -1
            var bestDiff = Int64.max
            for i in translations.indices {
                if usedTrans.contains(i) { continue }
                let diff = abs(translations[i].timeMs - line.timeMs)
                if diff <= 450 && diff < bestDiff {
                    bestDiff = diff
                    bestIdx = i
                }
            }
            let matched: String?
            if bestIdx >= 0 {
                usedTrans.insert(bestIdx)
                matched = translations[bestIdx].text
            } else {
                matched = nil
            }
            merged.append(LyricLine(timeMs: line.timeMs, text: line.text, translation: matched, lineIndex: idx))
        }

        return ParsedLyrics(
            lines: merged,
            hasTranslation: merged.contains { !($0.translation ?? "").isEmpty }
        )
    }

    private static func parseSingle(_ raw: String?, defaultOffsetMs: Int64 = 0) -> ([LyricLine], Int64) {
        guard var raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return ([], defaultOffsetMs) }
        if raw.unicodeScalars.first == "\u{FEFF}" {
            raw = String(raw.unicodeScalars.dropFirst())
        }
        var result: [LyricLine] = []
        var offsetMs: Int64 = defaultOffsetMs

        let normalizedRaw = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        for rawLine in normalizedRaw.components(separatedBy: "\n") {
            var line = rawLine
            if line.unicodeScalars.first == "\u{FEFF}" {
                line = String(line.unicodeScalars.dropFirst())
            }
            line = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("{\"") { continue }

            let nsLine = line as NSString
            let fullRange = NSRange(location: 0, length: nsLine.length)

            if let offsetMatch = offsetTagRegex.firstMatch(in: line, options: [], range: fullRange),
               offsetMatch.numberOfRanges > 1 {
                offsetMs = Int64(nsLine.substring(with: offsetMatch.range(at: 1))) ?? 0
                continue
            }

            // Support NetEase YRC `[startMs,duration](word...)` lines and Qishui `<start,dur,0>` word tags
            if let yrcMatch = yrcLineRegex.firstMatch(in: line, options: [], range: fullRange),
               yrcMatch.numberOfRanges > 1 {
                let timeStr = nsLine.substring(with: yrcMatch.range(at: 1))
                guard let timeMs = Int64(timeStr) else { continue }
                let remainder = nsLine.substring(from: yrcMatch.range.location + yrcMatch.range.length)
                let withoutYrc = yrcWordRegex.stringByReplacingMatches(
                    in: remainder,
                    options: [],
                    range: NSRange(location: 0, length: (remainder as NSString).length),
                    withTemplate: ""
                )
                let withoutQishui = qishuiWordRegex.stringByReplacingMatches(
                    in: withoutYrc,
                    options: [],
                    range: NSRange(location: 0, length: (withoutYrc as NSString).length),
                    withTemplate: ""
                )
                let cleaned = FormatUtils.decodeHtml(withoutQishui.trimmingCharacters(in: .whitespacesAndNewlines))
                if !cleaned.isEmpty {
                    result.append(LyricLine(timeMs: max(timeMs - offsetMs, 0), text: cleaned))
                }
                continue
            }

            let matches = timeTagRegex.matches(in: line, options: [], range: fullRange)
            if matches.isEmpty { continue }

            let withoutTimeTags = timeTagRegex.stringByReplacingMatches(
                in: line,
                options: [],
                range: fullRange,
                withTemplate: ""
            )
            let withoutQishuiWords = qishuiWordRegex.stringByReplacingMatches(
                in: withoutTimeTags,
                options: [],
                range: NSRange(location: 0, length: (withoutTimeTags as NSString).length),
                withTemplate: ""
            )
            let text = FormatUtils.decodeHtml(withoutQishuiWords.trimmingCharacters(in: .whitespacesAndNewlines))
            if text.isEmpty { continue }

            for m in matches {
                let minVal = Int64(nsLine.substring(with: m.range(at: 1))) ?? 0
                let secVal = Int64(nsLine.substring(with: m.range(at: 2))) ?? 0
                let fracRaw: String
                if m.range(at: 3).location != NSNotFound {
                    fracRaw = nsLine.substring(with: m.range(at: 3))
                } else {
                    fracRaw = ""
                }
                let ms: Int64
                switch fracRaw.count {
                case 0: ms = 0
                case 1: ms = (Int64(fracRaw) ?? 0) * 100
                case 2: ms = (Int64(fracRaw) ?? 0) * 10
                default: ms = Int64(String(fracRaw.prefix(3))) ?? 0
                }
                let shifted = max(minVal * 60_000 + secVal * 1_000 + ms - offsetMs, 0)
                result.append(LyricLine(timeMs: shifted, text: text))
            }
        }

        return (result.sorted { $0.timeMs < $1.timeMs }, offsetMs)
    }
}
