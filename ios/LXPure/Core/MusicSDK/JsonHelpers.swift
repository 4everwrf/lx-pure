import Foundation

enum JsonSafe {
    static func parseObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any]
    }

    static func parseObject(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])) as? [String: Any]
    }

    static func stringify(_ obj: Any) -> String {
        guard JSONSerialization.isValidJSONObject(obj),
              let data = try? JSONSerialization.data(withJSONObject: obj, options: []),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }

    static func asObject(_ value: Any?) -> [String: Any]? {
        guard let value, !(value is NSNull) else { return nil }
        return value as? [String: Any]
    }

    static func asArray(_ value: Any?) -> [Any]? {
        guard let value, !(value is NSNull) else { return nil }
        return value as? [Any]
    }

    static func asString(_ value: Any?) -> String? {
        guard let value, !(value is NSNull) else { return nil }
        if let s = value as? String { return s }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() {
                return n.boolValue ? "true" : "false"
            }
            return n.stringValue
        }
        return nil
    }

    static func asNonBlankString(_ value: Any?) -> String? {
        guard let str = asString(value)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !str.isEmpty else {
            return nil
        }
        return str
    }

    static func asInt(_ value: Any?) -> Int? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.intValue
        }
        if let s = value as? String {
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if let v = Int(trimmed) { return v }
            if let d = Double(trimmed), let exact = Int(exactly: d) {
                return exact
            }
        }
        return nil
    }

    static func asInt64(_ value: Any?) -> Int64? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.int64Value
        }
        if let s = value as? String {
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if let v = Int64(trimmed) { return v }
            if let d = Double(trimmed), let exact = Int64(exactly: d) {
                return exact
            }
        }
        return nil
    }

    static func asDouble(_ value: Any?) -> Double? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.doubleValue
        }
        if let s = value as? String {
            return Double(s.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    static func asBool(_ value: Any?) -> Bool? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() {
            return n.boolValue
        }
        if let s = value as? String {
            switch s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1": return true
            case "false", "0": return false
            default: return nil
            }
        }
        return nil
    }
}

func urlEncodeFormComponent(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}

func buildFormUrlEncodedBody(_ params: [String: String]) -> Data {
    let encoded = params.map { "\(urlEncodeFormComponent($0.key))=\(urlEncodeFormComponent($0.value))" }
        .joined(separator: "&")
    return Data(encoded.utf8)
}

func firstRegexCapture(pattern: String, in text: String, group: Int = 1) -> String? {
    guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
    let ns = text as NSString
    let range = NSRange(location: 0, length: ns.length)
    guard let match = regex.firstMatch(in: text, options: [], range: range),
          group < match.numberOfRanges else { return nil }
    let gRange = match.range(at: group)
    guard gRange.location != NSNotFound else { return nil }
    return ns.substring(with: gRange)
}

/// Extracts the first HTTP(S) URL from pasted share copy while stopping before CJK text or trailing punctuation.
func extractFirstHttpUrl(from raw: String) -> String? {
    guard let rawMatch = firstRegexCapture(
        pattern: "(https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'*+,;=%]+)",
        in: raw,
        group: 1
    ) else {
        return nil
    }
    let trimSet = CharacterSet(charactersIn: ".,;:!?) ]}'\"")
    let cleaned = rawMatch.trimmingCharacters(in: trimSet)
    let lower = cleaned.lowercased()
    guard lower.hasPrefix("http://") || lower.hasPrefix("https://") else { return nil }
    return cleaned
}
