package com.lxpure.music.core.userapi

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LxCrypto {

    private const val MAX_ZLIB_OUTPUT_BYTES = 8 * 1024 * 1024

    fun str2b64(input: String): String =
        Base64.getEncoder().encodeToString(input.toByteArray(StandardCharsets.UTF_8))

    fun decodeBase64Flexible(raw: String): ByteArray? {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '-' -> append('+')
                    '_' -> append('/')
                    ' ', '\t', '\r', '\n' -> Unit
                    else -> append(ch)
                }
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
    }

    fun b64ToJsonByteArray(b64: String): String {
        val bytes = decodeBase64Flexible(b64) ?: return "[]"
        if (bytes.isEmpty()) return "[]"
        val sb = StringBuilder(bytes.size * 4 + 2)
        sb.append('[')
        for (i in bytes.indices) {
            sb.append(bytes[i].toInt() and 0xFF)
            if (i < bytes.lastIndex) sb.append(',')
        }
        sb.append(']')
        return sb.toString()
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }

    fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it) }
    }

    fun aesEncryptBase64(dataB64: String, keyB64: String, ivB64: String, mode: String): String {
        return runCatching {
            val data = decodeBase64Flexible(dataB64) ?: return ""
            val key = decodeBase64Flexible(keyB64) ?: return ""
            val isCbc = mode.uppercase(Locale.ROOT).contains("/CBC/")
            val ivBytes = when {
                ivB64.isEmpty() && isCbc -> ByteArray(16)
                ivB64.isEmpty() -> null
                else -> decodeBase64Flexible(ivB64) ?: return ""
            }
            val encrypted = aesEncryptBytes(data, key, ivBytes, mode)
            Base64.getEncoder().encodeToString(encrypted)
        }.getOrDefault("")
    }

    fun aesEncryptBytes(data: ByteArray, key: ByteArray, iv: ByteArray?, mode: String): ByteArray {
        val normalizedMode = mode.replace("PKCS7Padding", "PKCS5Padding", ignoreCase = true)
        val cipher = runCatching { Cipher.getInstance(mode) }.getOrElse { Cipher.getInstance(normalizedMode) }
        val keySpec = SecretKeySpec(key, "AES")
        val isCbc = mode.uppercase(Locale.ROOT).contains("/CBC/")
        if (iv == null && !isCbc) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        } else {
            val finalIv = ByteArray(16)
            if (iv != null) {
                System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
            }
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(finalIv))
        }
        return cipher.doFinal(data)
    }

    fun rsaEncryptBase64(dataB64: String, publicKeyPemBody: String, padding: String): String {
        return runCatching {
            val cleanKey = publicKeyPemBody
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = decodeBase64Flexible(cleanKey) ?: return ""
            val dataBytes = decodeBase64Flexible(dataB64) ?: return ""
            val keySpec = X509EncodedKeySpec(keyBytes)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
            val cipher = Cipher.getInstance(padding)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(dataBytes)
            Base64.getEncoder().encodeToString(encrypted)
        }.getOrDefault("")
    }

    fun zlibInflateBase64(dataB64: String): String {
        val input = decodeBase64Flexible(dataB64) ?: return ""
        if (input.isEmpty()) return ""
        return runCatching {
            val inflater = Inflater()
            try {
                inflater.setInput(input)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        if (out.size() + count > MAX_ZLIB_OUTPUT_BYTES) {
                            return ""
                        }
                        out.write(buf, 0, count)
                    }
                }
                Base64.getEncoder().encodeToString(out.toByteArray())
            } finally {
                inflater.end()
            }
        }.getOrDefault("")
    }

    fun zlibDeflateBase64(dataB64: String): String {
        val input = decodeBase64Flexible(dataB64) ?: return ""
        if (input.isEmpty()) return ""
        return runCatching {
            val deflater = Deflater()
            try {
                deflater.setInput(input)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buf)
                    if (count <= 0) break
                    out.write(buf, 0, count)
                }
                Base64.getEncoder().encodeToString(out.toByteArray())
            } finally {
                deflater.end()
            }
        }.getOrDefault("")
    }
}
