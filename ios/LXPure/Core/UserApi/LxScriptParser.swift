import Foundation

struct ParsedScriptMeta: Equatable, Sendable {
    let id: String
    let name: String
    let description: String
    let version: String
    let author: String
    let homepage: String
}

enum LxScriptParser {
    private static let headerBlockRegex = try! NSRegularExpression(
        pattern: "/\\*[*!]([\\s\\S]*?)\\*/",
        options: []
    )
    private static let tagRegex = try! NSRegularExpression(
        pattern: "@([a-zA-Z_]+)\\s+(.+)",
        options: []
    )

    static func parse(_ script: String, fallbackName: String = "自定义音源") -> ParsedScriptMeta {
        var tags: [String: String] = [:]
        let nsScript = script as NSString
        let fullRange = NSRange(location: 0, length: nsScript.length)

        if let match = headerBlockRegex.firstMatch(in: script, options: [], range: fullRange),
           match.numberOfRanges > 1 {
            let headerRange = match.range(at: 1)
            if headerRange.location != NSNotFound {
                let header = nsScript.substring(with: headerRange)
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
                        if !value.isEmpty && tags[key] == nil {
                            tags[key] = value
                        }
                    }
                }
            }
        }

        let name = String((tags["name"] ?? fallbackName).prefix(60))
        let authorRaw = tags["author"] ?? ""
        let homepageRaw = tags["homepage"] ?? ""
        let idSeed = "\(name)_\(authorRaw)_\(homepageRaw)"
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
