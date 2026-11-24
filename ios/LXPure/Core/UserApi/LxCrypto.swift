import CommonCrypto
import CryptoKit
import Foundation
import Security

enum LxCrypto {
    static func str2b64(_ input: String) -> String {
        Data(input.utf8).base64EncodedString()
    }

    static func b64ToJsonByteArray(_ b64: String) -> String {
        guard let data = decodeBase64Flexible(b64) else { return "[]" }
        if data.isEmpty { return "[]" }
        var parts: [String] = []
        parts.reserveCapacity(data.count)
        for byte in data {
            parts.append(String(byte))
        }
        return "[" + parts.joined(separator: ",") + "]"
    }

    static func md5Hex(_ input: String) -> String {
        let digest = Insecure.MD5.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func sha1Hex(_ input: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func aesEncryptBase64(dataB64: String, keyB64: String, ivB64: String, mode: String) -> String {
        guard let data = decodeBase64Flexible(dataB64),
              let key = decodeBase64Flexible(keyB64) else {
            return ""
        }
        let upperMode = mode.uppercased()
        let isExplicitCBC = upperMode.contains("CBC")
        let iv: Data?
        if ivB64.isEmpty {
            iv = isExplicitCBC ? Data(repeating: 0, count: kCCBlockSizeAES128) : nil
        } else {
            guard let decodedIv = decodeBase64Flexible(ivB64) else { return "" }
            iv = decodedIv
        }
        guard let encrypted = aesEncryptBytes(data: data, key: key, iv: iv, mode: mode) else {
            return ""
        }
        return encrypted.base64EncodedString()
    }

    static func aesEncryptBytes(data: Data, key: Data, iv: Data?, mode: String) -> Data? {
        let upperMode = mode.uppercased()
        let isExplicitCBC = upperMode.contains("CBC")
        let isECB = upperMode.contains("ECB") || upperMode == "AES" || (!isExplicitCBC && iv == nil)
        let noPadding = upperMode.contains("NOPADDING")

        var options: CCOptions = 0
        if isECB {
            options |= CCOptions(kCCOptionECBMode)
        }
        if !noPadding {
            options |= CCOptions(kCCOptionPKCS7Padding)
        }

        var normalizedKey = key
        if normalizedKey.count < kCCKeySizeAES128 {
            normalizedKey.append(Data(repeating: 0, count: kCCKeySizeAES128 - normalizedKey.count))
        } else if normalizedKey.count > kCCKeySizeAES128 && normalizedKey.count < kCCKeySizeAES192 {
            normalizedKey = normalizedKey.prefix(kCCKeySizeAES128)
        } else if normalizedKey.count > kCCKeySizeAES192 && normalizedKey.count < kCCKeySizeAES256 {
            normalizedKey = normalizedKey.prefix(kCCKeySizeAES192)
        } else if normalizedKey.count > kCCKeySizeAES256 {
            normalizedKey = normalizedKey.prefix(kCCKeySizeAES256)
        }

        var ivBytes = [UInt8](repeating: 0, count: kCCBlockSizeAES128)
        if let iv, !isECB {
            let copyCount = min(iv.count, kCCBlockSizeAES128)
            iv.copyBytes(to: &ivBytes, count: copyCount)
        }

        let outCapacity = data.count + kCCBlockSizeAES128
        var outBytes = [UInt8](repeating: 0, count: outCapacity)
        var outLength: Int = 0

        let status: CCCryptorStatus = data.withUnsafeBytes { dataPtr in
            normalizedKey.withUnsafeBytes { keyPtr in
                CCCrypt(
                    CCOperation(kCCEncrypt),
                    CCAlgorithm(kCCAlgorithmAES),
                    options,
                    keyPtr.baseAddress,
                    normalizedKey.count,
                    isECB ? nil : ivBytes,
                    dataPtr.baseAddress,
                    data.count,
                    &outBytes,
                    outCapacity,
                    &outLength
                )
            }
        }

        guard status == kCCSuccess else { return nil }
        return Data(bytes: outBytes, count: outLength)
    }

    static func rsaEncryptBase64(dataB64: String, publicKeyPemBody: String, padding: String) -> String {
        guard let rawData = decodeBase64Flexible(dataB64),
              let encrypted = rsaEncryptBytes(data: rawData, publicKeyPemBody: publicKeyPemBody, padding: padding) else {
            return ""
        }
        return encrypted.base64EncodedString()
    }

    static func rsaEncryptBytes(data: Data, publicKeyPemBody: String, padding: String) -> Data? {
        guard let secKey = createRSAPublicKey(fromPEMBody: publicKeyPemBody) else { return nil }

        let algorithm: SecKeyAlgorithm
        let payload: Data

        if padding.uppercased().contains("OAEP") {
            algorithm = .rsaEncryptionOAEPSHA1
            payload = data
        } else {
            algorithm = .rsaEncryptionRaw
            let blockSize = SecKeyGetBlockSize(secKey)
            guard blockSize > 0, data.count <= blockSize else { return nil }
            if data.count < blockSize {
                var padded = Data(repeating: 0, count: blockSize - data.count)
                padded.append(data)
                payload = padded
            } else {
                payload = data
            }
        }

        guard SecKeyIsAlgorithmSupported(secKey, .encrypt, algorithm) else { return nil }
        var error: Unmanaged<CFError>?
        guard let encrypted = SecKeyCreateEncryptedData(secKey, algorithm, payload as CFData, &error) as Data? else {
            error?.release()
            return nil
        }
        return encrypted
    }

    /// Standard RFC 1950 zlib inflate (accepts standard zlib wrapper or raw deflate).
    static func zlibInflateBase64(_ dataB64: String) -> String {
        guard let input = decodeBase64Flexible(dataB64), !input.isEmpty else { return "" }
        let rawPayload: Data
        if input.count >= 6 {
            let cmf = input[input.startIndex]
            let flg = input[input.index(after: input.startIndex)]
            let headerCheck = ((Int(cmf) << 8) | Int(flg)) % 31 == 0
            if (cmf & 0x0F) == 8 && headerCheck {
                let headerLen = (flg & 0x20) != 0 ? 6 : 2
                if input.count > headerLen + 4 {
                    rawPayload = input.subdata(in: headerLen..<(input.count - 4))
                } else {
                    rawPayload = input
                }
            } else {
                rawPayload = input
            }
        } else {
            rawPayload = input
        }
        if let decompressed = try? (rawPayload as NSData).decompressed(using: .zlib) as Data {
            return decompressed.base64EncodedString()
        }
        return ""
    }

    /// Standard RFC 1950 zlib deflate (2-byte header + raw deflate + 4-byte Adler-32 checksum).
    static func zlibDeflateBase64(_ dataB64: String) -> String {
        guard let input = decodeBase64Flexible(dataB64), !input.isEmpty else { return "" }
        guard let rawCompressed = try? (input as NSData).compressed(using: .zlib) as Data else { return "" }
        let adler = adler32(input)
        var out = Data(capacity: 2 + rawCompressed.count + 4)
        out.append(contentsOf: [0x78, 0x9C])
        out.append(rawCompressed)
        out.append(UInt8((adler >> 24) & 0xFF))
        out.append(UInt8((adler >> 16) & 0xFF))
        out.append(UInt8((adler >> 8) & 0xFF))
        out.append(UInt8(adler & 0xFF))
        return out.base64EncodedString()
    }

    private static func adler32(_ data: Data) -> UInt32 {
        var s1: UInt32 = 1
        var s2: UInt32 = 0
        for byte in data {
            s1 = (s1 + UInt32(byte)) % 65521
            s2 = (s2 + s1) % 65521
        }
        return (s2 << 16) | s1
    }

    private static func createRSAPublicKey(fromPEMBody pemBody: String) -> SecKey? {
        let cleaned = pemBody
            .replacingOccurrences(of: "-----BEGIN PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----END PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----BEGIN RSA PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----END RSA PUBLIC KEY-----", with: "")
            .components(separatedBy: .whitespacesAndNewlines)
            .joined()

        guard let der = decodeBase64Flexible(cleaned) else { return nil }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic
        ]

        var error: Unmanaged<CFError>?
        if let key = SecKeyCreateWithData(der as CFData, attributes as CFDictionary, &error) {
            return key
        }
        error?.release()
        error = nil
        if let pkcs1 = stripX509SubjectPublicKeyInfoHeader(der) {
            if let key = SecKeyCreateWithData(pkcs1 as CFData, attributes as CFDictionary, &error) {
                return key
            }
            error?.release()
        }
        return nil
    }

    /// Strips ASN.1 X.509 SubjectPublicKeyInfo wrapper to extract inner PKCS#1 RSAPublicKey DER if needed.
    private static func stripX509SubjectPublicKeyInfoHeader(_ der: Data) -> Data? {
        let bytes = [UInt8](der)
        guard bytes.count > 24, bytes[0] == 0x30 else { return nil }

        // Locate OID 1.2.840.113549.1.1.1 (rsaEncryption): 06 09 2A 86 48 86 F7 0D 01 01 01
        let rsaOID: [UInt8] = [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01]
        guard let oidRange = bytes.firstRange(of: rsaOID) else { return nil }

        var idx = oidRange.upperBound
        // Skip optional NULL (05 00) inside AlgorithmIdentifier
        if idx + 1 < bytes.count && bytes[idx] == 0x05 && bytes[idx + 1] == 0x00 {
            idx += 2
        }
        // Expect BIT STRING tag (0x03)
        guard idx < bytes.count, bytes[idx] == 0x03 else { return nil }
        idx += 1
        guard idx < bytes.count else { return nil }

        // Parse DER length
        let firstLen = bytes[idx]
        idx += 1
        if firstLen & 0x80 != 0 {
            let count = Int(firstLen & 0x7F)
            guard idx + count <= bytes.count else { return nil }
            idx += count
        }
        // Skip unused-bits byte (0x00)
        guard idx < bytes.count, bytes[idx] == 0x00 else { return nil }
        idx += 1
        guard idx < bytes.count else { return nil }
        return Data(bytes[idx...])
    }

    static func decodeBase64Flexible(_ raw: String) -> Data? {
        var stripped = ""
        stripped.reserveCapacity(raw.count)
        for ch in raw {
            switch ch {
            case "-": stripped.append("+")
            case "_": stripped.append("/")
            case " ", "\t", "\r", "\n": continue
            default: stripped.append(ch)
            }
        }
        if stripped.isEmpty { return Data() }
        let remainder = stripped.count % 4
        let padded: String
        if remainder == 2 {
            padded = stripped + "=="
        } else if remainder == 3 {
            padded = stripped + "="
        } else if remainder == 1 {
            return nil
        } else {
            padded = stripped
        }
        return Data(base64Encoded: padded)
    }
}

private extension Array where Element == UInt8 {
    func firstRange(of needle: [UInt8]) -> Range<Int>? {
        guard !needle.isEmpty, count >= needle.count else { return nil }
        for i in 0...(count - needle.count) {
            var match = true
            for j in 0..<needle.count where self[i + j] != needle[j] {
                match = false
                break
            }
            if match {
                return i..<(i + needle.count)
            }
        }
        return nil
    }
}
