import Foundation

struct ParsedScriptMeta: Equatable, Sendable {
    let id: String
    let name: String
    let description: String
    let version: String
    let author: String
    let homepage: String
}

struct BundledScriptEntry: Equatable, Sendable {
    let fallbackName: String
    let script: String
}

enum ScriptBundleParseResult: Equatable, Sendable {
    case notBundle
    case validBundle(entries: [BundledScriptEntry], skippedCount: Int)
    case invalidBundle(String)
}

enum LxScriptParser {
    static let maxSingleScriptBytes = 1 * 1024 * 1024
    static let maxBundlePayloadBytes = 4 * 1024 * 1024
    static let maxBundleScriptCount = 50

    private static let headerBlockRegex = try! NSRegularExpression(
        pattern: "/\\*[*!]([\\s\\S]*?)\\*/",
        options: []
    )
    private static let tagRegex = try! NSRegularExpression(
        pattern: "@([a-zA-Z_]+)\\s+(.+)",
        options: []
    )
    private static let brokenJsonHintRegex = try! NSRegularExpression(
        pattern: "\"(scripts|sources|userApis|code|script|content|app|type)\"\\s*:",
        options: []
    )
    private static let bundleKeys = ["scripts", "sources", "userApis"]
    private static let scriptCodeKeys = ["code", "script", "content"]

    /// Strips any leading UTF-8 BOM and normalizes CRLF/CR line endings to Unix LF (`\n`)
    /// so scripts that verify MD5 checksums of `lx.currentScriptInfo.rawScript` behave
    /// identically whether imported from Windows files, clipboard, or remote URLs.
    static func normalizeScript(_ raw: String) -> String {
        var normalized = raw
        if normalized.unicodeScalars.first == "\u{FEFF}" {
            normalized = String(normalized.unicodeScalars.dropFirst())
        }
        if normalized.unicodeScalars.contains("\r") {
            normalized = normalized
                .replacingOccurrences(of: "\r\n", with: "\n")
                .replacingOccurrences(of: "\r", with: "\n")
        }
        return normalized
    }

    static func hasLxScriptMarkers(_ text: String) -> Bool {
        let lower = text.lowercased()
        return lower.contains("lx") ||
            lower.contains("\\x6c\\x78") ||
            lower.contains("@name") ||
            lower.contains("globalthis")
    }

    private static func cleanFallbackName(_ rawName: String?) -> String {
        let trimmed = (rawName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "自定义音源" }
        let withoutExt: String
        if trimmed.lowercased().hasSuffix(".js") {
            withoutExt = String(trimmed.dropLast(3)).trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            withoutExt = trimmed
        }
        let capped = String(withoutExt.prefix(60))
        return capped.isEmpty ? "自定义音源" : capped
    }

    private static func isValidBundledScriptCode(_ normalizedCode: String, minLength: Int = 20) -> Bool {
        let trimmed = normalizedCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minLength else { return false }
        guard !normalizedCode.unicodeScalars.contains("\u{0000}") else { return false }
        guard normalizedCode.utf8.count <= maxSingleScriptBytes else { return false }
        guard !trimmed.hasPrefix("<"), !trimmed.hasPrefix("{") else { return false }
        return hasLxScriptMarkers(trimmed)
    }

    private static func matchesBrokenJsonHint(_ text: String) -> Bool {
        let nsText = text as NSString
        let range = NSRange(location: 0, length: nsText.length)
        return brokenJsonHintRegex.firstMatch(in: text, options: [], range: range) != nil
    }

    /// Detects and unpacks multi-script JSON bundles (such as Melora `{"app":"melora","type":"lx-sources","scripts":[...]}`
    /// or generic `scripts`/`sources`/`userApis` arrays) while rejecting API error JSON and malformed payloads.
    static func parseScriptBundle(_ rawContent: String) -> ScriptBundleParseResult {
        var withoutBom = rawContent
        if withoutBom.unicodeScalars.first == "\u{FEFF}" {
            withoutBom = String(withoutBom.unicodeScalars.dropFirst())
        }
        let trimmed = withoutBom.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.hasPrefix("{") || trimmed.hasPrefix("[") else {
            return .notBundle
        }
        if rawContent.unicodeScalars.contains("\u{0000}") {
            return .invalidBundle("文件包含二进制内容，不是有效的音源包")
        }
        if rawContent.utf8.count > maxBundlePayloadBytes {
            return .invalidBundle("音源包体积过大（上限 4 MB），请拆分后导入")
        }

        let jsonObj: Any
        do {
            jsonObj = try JSONSerialization.jsonObject(with: Data(trimmed.utf8), options: [])
        } catch {
            if !matchesBrokenJsonHint(trimmed) && hasLxScriptMarkers(trimmed) {
                return .notBundle
            }
            return .invalidBundle("JSON 音源包格式错误，无法解析")
        }

        let candidateArray: [Any]
        if let arr = jsonObj as? [Any] {
            candidateArray = arr
        } else if let dict = jsonObj as? [String: Any] {
            var foundArray: [Any]?
            for key in bundleKeys {
                if let arr = dict[key] as? [Any] {
                    foundArray = arr
                    break
                }
            }
            if let foundArray {
                candidateArray = foundArray
            } else {
                var singleRawCode: String?
                for key in scriptCodeKeys {
                    if let str = dict[key] as? String {
                        singleRawCode = str
                        break
                    }
                }
                if let singleRawCode {
                    let normalized = normalizeScript(singleRawCode)
                    if isValidBundledScriptCode(normalized, minLength: 80) {
                        let rawName = dict["name"] as? String
                        return .validBundle(
                            entries: [BundledScriptEntry(fallbackName: cleanFallbackName(rawName), script: normalized)],
                            skippedCount: 0
                        )
                    }
                }
                return .invalidBundle("该 JSON 内容不是有效的洛雪 / Melora 音源包")
            }
        } else {
            return .invalidBundle("该 JSON 内容不是有效的洛雪 / Melora 音源包")
        }

        guard !candidateArray.isEmpty else {
            return .invalidBundle("音源包中未包含任何音源脚本")
        }
        guard candidateArray.count <= maxBundleScriptCount else {
            return .invalidBundle("单个音源包最多支持 \(maxBundleScriptCount) 个脚本，请拆分后导入")
        }

        var entries: [BundledScriptEntry] = []
        var skipped = 0
        for element in candidateArray {
            var rawCode: String?
            var rawName: String?
            if let obj = element as? [String: Any] {
                for key in scriptCodeKeys {
                    if let str = obj[key] as? String {
                        rawCode = str
                        break
                    }
                }
                rawName = obj["name"] as? String
            } else if let str = element as? String {
                rawCode = str
            }
            guard let rawCode else {
                skipped += 1
                continue
            }
            let normalized = normalizeScript(rawCode)
            if isValidBundledScriptCode(normalized) {
                entries.append(BundledScriptEntry(fallbackName: cleanFallbackName(rawName), script: normalized))
            } else {
                skipped += 1
            }
        }

        guard !entries.isEmpty else {
            return .invalidBundle("音源包中未包含有效的洛雪 JS 音源脚本")
        }
        return .validBundle(entries: entries, skippedCount: skipped)
    }

    static func parse(_ script: String, fallbackName: String = "自定义音源") -> ParsedScriptMeta {
        let normalized = normalizeScript(script)
        var tags: [String: String] = [:]
        let nsScript = normalized as NSString
        let fullRange = NSRange(location: 0, length: nsScript.length)

        let matches = headerBlockRegex.matches(in: normalized, options: [], range: fullRange)
        for match in matches where match.numberOfRanges > 1 {
            let headerRange = match.range(at: 1)
            guard headerRange.location != NSNotFound else { continue }
            let header = nsScript.substring(with: headerRange)
            var blockTags: [String: String] = [:]
            for rawLine in header.components(separatedBy: .newlines) {
                var line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
                if line.hasPrefix("*") {
                    line = String(line.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines)
                }
                let nsLine = line as NSString
                let lineRange = NSRange(location: 0, length: nsLine.length)
                if let tagMatch = tagRegex.firstMatch(in: line, options: [], range: lineRange),
                   tagMatch.numberOfRanges > 2 {
                    let key = nsLine.substring(with: tagMatch.range(at: 1)).lowercased()
                    let value = nsLine.substring(with: tagMatch.range(at: 2)).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !value.isEmpty && blockTags[key] == nil {
                        blockTags[key] = value
                    }
                }
            }
            if blockTags["name"] != nil {
                tags = blockTags
                break
            }
            if tags.isEmpty && !blockTags.isEmpty {
                tags = blockTags
            }
        }

        let hasDeclaredMeta = tags["name"] != nil || tags["author"] != nil || tags["homepage"] != nil
        let name = String((tags["name"] ?? fallbackName).prefix(60))
        let authorRaw = tags["author"] ?? ""
        let homepageRaw = tags["homepage"] ?? ""
        let idSeed: String
        if hasDeclaredMeta {
            idSeed = "\(name)_\(authorRaw)_\(homepageRaw)"
        } else {
            let contentHash = String(LxCrypto.md5Hex(normalized.trimmingCharacters(in: .whitespacesAndNewlines)).prefix(16))
            idSeed = "\(name)_\(contentHash)"
        }
        let md5Prefix = String(LxCrypto.md5Hex(idSeed).prefix(12))
        let stableId = "user_api_" + (md5Prefix.isEmpty ? String(UUID().uuidString.prefix(8)) : md5Prefix)

        return ParsedScriptMeta(
            id: stableId,
            name: name,
            description: String((tags["description"] ?? "").prefix(200)),
            version: String((tags["version"] ?? "1.0.0").prefix(32)),
            author: String((tags["author"] ?? "未知作者").prefix(60)),
            homepage: String(homepageRaw.prefix(200))
        )
    }

    static func formatVersionBadge(_ rawVersion: String?) -> String {
        var clean = (rawVersion ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        while clean.hasPrefix("v") || clean.hasPrefix("V") {
            clean.removeFirst()
        }
        clean = clean.trimmingCharacters(in: .whitespacesAndNewlines)
        return "v\(clean.isEmpty ? "1.0.0" : clean)"
    }
}
