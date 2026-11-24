import Foundation

enum NetEaseCrypto {
    private static let iv = Data("0102030405060708".utf8)
    private static let presetKey = Data("0CoJUm6Qyw8W8jud".utf8)
    private static let linuxApiKey = Data("rFgB&h#%2?^eDg:Q".utf8)
    private static let eapiKey = Data("e82ckenh8dichen8".utf8)
    private static let rsaPublicKey =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
    private static let base62 = Array("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

    static func eapi(path: String, jsonBody: String) -> [String: String] {
        let message = "nobody\(path)use\(jsonBody)md5forencrypt"
        let digest = LxCrypto.md5Hex(message)
        let data = "\(path)-36cd479b6b5-\(jsonBody)-36cd479b6b5-\(digest)"
        let encrypted = LxCrypto.aesEncryptBytes(
            data: Data(data.utf8),
            key: eapiKey,
            iv: nil,
            mode: "AES/ECB/PKCS5Padding"
        ) ?? Data()
        return ["params": encrypted.hexUpper]
    }

    static func linuxApi(jsonBody: String) -> [String: String] {
        let encrypted = LxCrypto.aesEncryptBytes(
            data: Data(jsonBody.utf8),
            key: linuxApiKey,
            iv: nil,
            mode: "AES/ECB/PKCS5Padding"
        ) ?? Data()
        return ["eparams": encrypted.hexUpper]
    }

    static func weapi(jsonBody: String) -> [String: String] {
        let secretKey = String((0..<16).map { _ in base62[Int.random(in: 0..<base62.count)] })
        let firstEnc = LxCrypto.aesEncryptBytes(
            data: Data(jsonBody.utf8),
            key: presetKey,
            iv: iv,
            mode: "AES/CBC/PKCS5Padding"
        ) ?? Data()
        let firstB64 = firstEnc.base64EncodedString()
        let secondEnc = LxCrypto.aesEncryptBytes(
            data: Data(firstB64.utf8),
            key: Data(secretKey.utf8),
            iv: iv,
            mode: "AES/CBC/PKCS5Padding"
        ) ?? Data()
        let params = secondEnc.base64EncodedString()

        let reversed = Data(String(secretKey.reversed()).utf8)
        let encryptedKey = LxCrypto.rsaEncryptBytes(
            data: reversed,
            publicKeyPemBody: rsaPublicKey,
            padding: "RSA/ECB/NoPadding"
        ) ?? Data()

        return ["params": params, "encSecKey": encryptedKey.hexLower]
    }
}

enum QqSign {
    private static let part1Indexes = [23, 14, 6, 36, 16, 40, 7, 19]
    private static let part2Indexes = [16, 1, 32, 12, 19, 27, 8, 5]
    private static let scrambleValues: [UInt8] = [
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
        186, 123, 120, 64, 242, 133, 143, 161, 121, 179
    ]

    static func zzcSign(_ payload: String) -> String {
        let hash = Array(LxCrypto.sha1Hex(payload).uppercased())
        let part1 = String(part1Indexes.compactMap { $0 < hash.count ? hash[$0] : nil })
        let part2 = String(part2Indexes.compactMap { $0 < hash.count ? hash[$0] : nil })

        var scrambled = Data(capacity: scrambleValues.count)
        for i in 0..<scrambleValues.count {
            let start = i * 2
            guard start + 1 < hash.count else { break }
            let hexByte = String([hash[start], hash[start + 1]])
            let byteVal = UInt8(hexByte, radix: 16) ?? 0
            scrambled.append(scrambleValues[i] ^ byteVal)
        }

        let b64 = scrambled.base64EncodedString()
            .replacingOccurrences(of: "/", with: "")
            .replacingOccurrences(of: "+", with: "")
            .replacingOccurrences(of: "=", with: "")
        return "zzc\(part1)\(b64)\(part2)".lowercased()
    }
}

enum FormatUtils {
    static func formatSize(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "" }
        let mb = Double(bytes) / (1024.0 * 1024.0)
        if mb >= 1.0 {
            return String(format: "%.1fMB", mb)
        } else {
            return "\(max(bytes / 1024, 1))KB"
        }
    }

    static func formatPlayCount(_ count: Int64) -> String {
        if count >= 100_000_000 {
            return String(format: "%.1f亿", Double(count) / 100_000_000.0)
        } else if count >= 10_000 {
            return String(format: "%.1f万", Double(count) / 10_000.0)
        } else {
            return "\(count)"
        }
    }

    private static let emTagRegex = try! NSRegularExpression(pattern: "</?em>", options: [.caseInsensitive])
    private static let brTagRegex = try! NSRegularExpression(pattern: "<br\\s*/?>", options: [.caseInsensitive])
    private static let numericEntityRegex = try! NSRegularExpression(pattern: "&#(?:x([0-9a-fA-F]+)|(\\d+));", options: [])

    static func decodeHtml(_ input: String?) -> String {
        guard let input, !input.isEmpty else { return "" }
        var text = emTagRegex.stringByReplacingMatches(
            in: input,
            options: [],
            range: NSRange(location: 0, length: (input as NSString).length),
            withTemplate: ""
        )
        text = brTagRegex.stringByReplacingMatches(
            in: text,
            options: [],
            range: NSRange(location: 0, length: (text as NSString).length),
            withTemplate: "\n"
        )

        let nsText = text as NSString
        let matches = numericEntityRegex.matches(in: text, options: [], range: NSRange(location: 0, length: nsText.length))
        if !matches.isEmpty {
            let mutable = NSMutableString(string: text)
            for match in matches.reversed() {
                let hexRange = match.range(at: 1)
                let decRange = match.range(at: 2)
                var codePoint: UInt32?
                if hexRange.location != NSNotFound {
                    codePoint = UInt32(nsText.substring(with: hexRange), radix: 16)
                } else if decRange.location != NSNotFound {
                    codePoint = UInt32(nsText.substring(with: decRange), radix: 10)
                }
                if let cp = codePoint, let scalar = UnicodeScalar(cp) {
                    mutable.replaceCharacters(in: match.range, with: String(scalar))
                }
            }
            text = mutable as String
        }

        return text
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func formatTimeMs(_ ms: Int64) -> String {
        let clamped = max(ms, 0)
        let totalSec = clamped / 1000
        return String(format: "%02d:%02d", totalSec / 60, totalSec % 60)
    }
}

private extension Data {
    var hexUpper: String {
        map { String(format: "%02X", $0) }.joined()
    }

    var hexLower: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
