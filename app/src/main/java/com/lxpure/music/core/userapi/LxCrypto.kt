package com.lxpure.music.core.userapi

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LxCrypto {

    fun str2b64(input: String): String =
        Base64.encodeToString(input.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    fun b64ToJsonByteArray(b64: String): String {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val sb = StringBuilder(bytes.size * 4 + 2)
        sb.append('[')
        for (i in bytes.indices) {
            sb.append(bytes[i].toInt())
            if (i < bytes.lastIndex) sb.append(',')
        }
        sb.append(']')
        return sb.toString()
    }

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun aesEncryptBase64(dataB64: String, keyB64: String, ivB64: String, mode: String): String {
        return runCatching {
            val data = Base64.decode(dataB64, Base64.DEFAULT)
            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val cipher = Cipher.getInstance(mode)
            val keySpec = SecretKeySpec(key, "AES")
            if (ivB64.isEmpty()) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            } else {
                val rawIv = Base64.decode(ivB64, Base64.DEFAULT)
                val finalIv = ByteArray(16)
                System.arraycopy(rawIv, 0, finalIv, 0, minOf(rawIv.size, 16))
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(finalIv))
            }
            Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun aesEncryptBytes(data: ByteArray, key: ByteArray, iv: ByteArray?, mode: String): ByteArray {
        val cipher = Cipher.getInstance(mode)
        val keySpec = SecretKeySpec(key, "AES")
        if (iv == null) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        } else {
            val finalIv = ByteArray(16)
            System.arraycopy(iv, 0, finalIv, 0, minOf(iv.size, 16))
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
            val keySpec = X509EncodedKeySpec(Base64.decode(cleanKey, Base64.DEFAULT))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
            val cipher = Cipher.getInstance(padding)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(Base64.decode(dataB64, Base64.DEFAULT))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrDefault("")
    }
}
