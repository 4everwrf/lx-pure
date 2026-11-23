import Foundation

enum LrcParser {
    private static let timeTagRegex = try! NSRegularExpression(
        pattern: "\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?\\]",
        options: []
    )
    private static let yrcLineRegex = try! NSRegularExpression(
        pattern: "^\\[(\\d+),\\d+\\]",
        options: []
    )
    private static let yrcWordRegex = try! NSRegularExpression(
        pattern: "\\(\\d+,\\d+,\\d+\\)",
        options: []
    )

    static func parse(_ rawLrc: String?, translation rawTranslation: String? = nil) -> ParsedLyrics {
        let primary = parseSingle(rawLrc)
        if primary.isEmpty { return ParsedLyrics() }

        let translations = parseSingle(rawTranslation)
        if translations.isEmpty {
            return ParsedLyrics(lines: primary, hasTranslation: false)
        }

        let merged: [LyricLine] = primary.map { line in
            let best = translations.min { abs($0.timeMs - line.timeMs) < abs($1.timeMs - line.timeMs) }
            let matched = (best != nil && abs(best!.timeMs - line.timeMs) <= 450 && !best!.text.trimmingCharacters(in: .whitespaces).isEmpty)
                ? best?.text
                : nil
            return LyricLine(timeMs: line.timeMs, text: line.text, translation: matched)
        }

        return ParsedLyrics(
            lines: merged,
            hasTranslation: merged.contains { !($0.translation ?? "").isEmpty }
        )
    }

    private static func parseSingle(_ raw: String?) -> [LyricLine] {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return [] }
        var result: [LyricLine] = []

        for rawLine in raw.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n") {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || line.hasPrefix("{\"") { continue }

            let nsLine = line as NSString
            let fullRange = NSRange(location: 0, length: nsLine.length)

            // Support NetEase YRC `[startMs,duration](word...)` lines
            if let yrcMatch = yrcLineRegex.firstMatch(in: line, options: [], range: fullRange),
               yrcMatch.numberOfRanges > 1 {
                let timeStr = nsLine.substring(with: yrcMatch.range(at: 1))
                guard let timeMs = Int64(timeStr) else { continue }
                let remainder = nsLine.substring(from: yrcMatch.range.location + yrcMatch.range.length)
                let cleaned = yrcWordRegex.stringByReplacingMatches(
                    in: remainder,
                    options: [],
                    range: NSRange(location: 0, length: (remainder as NSString).length),
                    withTemplate: ""
                ).trimmingCharacters(in: .whitespacesAndNewlines)
                if !cleaned.isEmpty {
                    result.append(LyricLine(timeMs: timeMs, text: FormatUtils.decodeHtml(cleaned)))
                }
                continue
            }

            let matches = timeTagRegex.matches(in: line, options: [], range: fullRange)
            if matches.isEmpty { continue }

            let text = timeTagRegex.stringByReplacingMatches(
                in: line,
                options: [],
                range: fullRange,
                withTemplate: ""
            ).trimmingCharacters(in: .whitespacesAndNewlines)
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
                result.append(
                    LyricLine(
                        timeMs: minVal * 60_000 + secVal * 1_000 + ms,
                        text: FormatUtils.decodeHtml(text)
                    )
                )
            }
        }

        return result.sorted { $0.timeMs < $1.timeMs }
    }
}
