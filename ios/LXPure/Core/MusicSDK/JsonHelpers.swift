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

    static func asInt(_ value: Any?) -> Int? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.intValue
        }
        if let s = value as? String { return Int(s) }
        return nil
    }

    static func asInt64(_ value: Any?) -> Int64? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.int64Value
        }
        if let s = value as? String { return Int64(s) }
        return nil
    }

    static func asDouble(_ value: Any?) -> Double? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return nil }
            return n.doubleValue
        }
        if let s = value as? String { return Double(s) }
        return nil
    }

    static func asBool(_ value: Any?) -> Bool? {
        guard let value, !(value is NSNull) else { return nil }
        if let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() {
            return n.boolValue
        }
        if let s = value as? String {
            if s.lowercased() == "true" { return true }
            if s.lowercased() == "false" { return false }
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
