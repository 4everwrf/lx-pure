package com.lxpure.music.core.musicsdk

import com.lxpure.music.core.userapi.LxCrypto
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import kotlin.random.Random

object NetEaseCrypto {
    private val iv = "0102030405060708".toByteArray(StandardCharsets.UTF_8)
    private val presetKey = "0CoJUm6Qyw8W8jud".toByteArray(StandardCharsets.UTF_8)
    private val linuxApiKey = "rFgB&h#%2?^eDg:Q".toByteArray(StandardCharsets.UTF_8)
    private val eapiKey = "e82ckenh8dichen8".toByteArray(StandardCharsets.UTF_8)
    private const val RSA_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"

    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun eapi(path: String, jsonBody: String): Map<String, String> {
        val message = "nobody${path}use${jsonBody}md5forencrypt"
        val digest = LxCrypto.md5Hex(message)
        val data = "$path-36cd479b6b5-$jsonBody-36cd479b6b5-$digest"
        val encrypted = LxCrypto.aesEncryptBytes(
            data = data.toByteArray(StandardCharsets.UTF_8),
            key = eapiKey,
            iv = null,
            mode = "AES/ECB/PKCS5Padding"
        )
        return mapOf("params" to encrypted.toHexUpper())
    }

    fun linuxApi(jsonBody: String): Map<String, String> {
        val encrypted = LxCrypto.aesEncryptBytes(
            data = jsonBody.toByteArray(StandardCharsets.UTF_8),
            key = linuxApiKey,
            iv = null,
            mode = "AES/ECB/PKCS5Padding"
        )
        return mapOf("eparams" to encrypted.toHexUpper())
    }

    fun weapi(jsonBody: String): Map<String, String> {
        val secretKey = buildString {
            repeat(16) { append(BASE62[Random.nextInt(BASE62.length)]) }
        }
        val firstEnc = LxCrypto.aesEncryptBytes(
            data = jsonBody.toByteArray(StandardCharsets.UTF_8),
            key = presetKey,
            iv = iv,
            mode = "AES/CBC/PKCS5Padding"
        )
        val firstB64 = Base64.getEncoder().encodeToString(firstEnc)
        val secondEnc = LxCrypto.aesEncryptBytes(
            data = firstB64.toByteArray(StandardCharsets.UTF_8),
            key = secretKey.toByteArray(StandardCharsets.UTF_8),
            iv = iv,
            mode = "AES/CBC/PKCS5Padding"
        )
        val params = Base64.getEncoder().encodeToString(secondEnc)

        // RSA NoPadding with zero-padded reversed secretKey (128 bytes)
        val reversed = secretKey.reversed().toByteArray(StandardCharsets.UTF_8)
        val padded = ByteArray(128)
        System.arraycopy(reversed, 0, padded, 128 - reversed.size, reversed.size)

        val keySpec = X509EncodedKeySpec(Base64.getDecoder().decode(RSA_PUBLIC_KEY))
        val pubKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        val encSecKey = cipher.doFinal(padded).toHexLower()

        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(Locale.US, it) }
    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(Locale.US, it) }
}

object QqSign {
    private val part1Indexes = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val part2Indexes = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val scrambleValues = intArrayOf(
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52,
        186, 123, 120, 64, 242, 133, 143, 161, 121, 179
    )

    fun zzcSign(payload: String): String {
        val hash = LxCrypto.sha1Hex(payload).uppercase(Locale.ROOT)
        val part1 = buildString {
            for (idx in part1Indexes) {
                if (idx < hash.length) append(hash[idx])
            }
        }
        val part2 = buildString {
            for (idx in part2Indexes) {
                if (idx < hash.length) append(hash[idx])
            }
        }
        val scrambled = ByteArray(scrambleValues.size) { i ->
            val byteVal = hash.substring(i * 2, i * 2 + 2).toInt(16)
            (scrambleValues[i] xor byteVal).toByte()
        }
        val b64 = Base64.getEncoder().encodeToString(scrambled)
            .replace("[/+=]".toRegex(), "")
        return "zzc${part1}${b64}${part2}".lowercase(Locale.ROOT)
    }
}

object FormatUtils {
    private val emTagRegex = Regex("</?em>", RegexOption.IGNORE_CASE)
    private val brTagRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val numericEntityRegex = Regex("&#(?:x([0-9a-fA-F]+)|(\\d+));")

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1fMB".format(Locale.US, mb) else "${(bytes / 1024).coerceAtLeast(1)}KB"
    }

    fun formatPlayCount(count: Long): String = when {
        count >= 100_000_000L -> "%.1f亿".format(Locale.US, count / 100_000_000.0)
        count >= 10_000L -> "%.1f万".format(Locale.US, count / 10_000.0)
        else -> count.toString()
    }

    fun decodeHtml(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val withoutTags = input
            .replace(emTagRegex, "")
            .replace(brTagRegex, "\n")
        val withNumericDecoded = numericEntityRegex.replace(withoutTags) { m ->
            val hex = m.groupValues[1]
            val dec = m.groupValues[2]
            val cp = if (hex.isNotEmpty()) hex.toIntOrNull(16) else dec.toIntOrNull(10)
            if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) {
                runCatching { String(Character.toChars(cp)) }.getOrDefault(m.value)
            } else {
                m.value
            }
        }
        return withNumericDecoded
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .trim()
    }
}
