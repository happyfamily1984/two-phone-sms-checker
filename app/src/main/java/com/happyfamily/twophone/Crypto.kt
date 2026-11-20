package com.happyfamily.twophone

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 연결 코드 하나에서 "채널 이름"과 "암호 키"를 만들어 낸다.
 * 메시지는 폰에서 AES-256-GCM으로 암호화된 뒤 중계 서버를 지나가므로
 * 서버는 내용을 볼 수 없다.
 */
object Crypto {
    /** 헷갈리는 글자(I, O, 0, 1)를 뺀 32글자 */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 16

    private val random = SecureRandom()

    class Pairing(val topic: String, val key: ByteArray)

    fun newCode(): String =
        (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** 사용자가 입력한 코드에서 공백·하이픈 등을 걷어 낸다. */
    fun normalizeCode(input: String): String =
        input.uppercase().filter { it in ALPHABET }

    fun isValidCode(code: String) = code.length == CODE_LENGTH && code.all { it in ALPHABET }

    fun formatCode(code: String): String = code.chunked(4).joinToString("-")

    fun derive(code: String): Pairing {
        val spec = PBEKeySpec(code.toCharArray(), "twophone-v1".toByteArray(), 20000, 384)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val key = bytes.copyOfRange(0, 32)
        val topic = "tp" + bytes.copyOfRange(32, 48).joinToString("") { "%02x".format(it) }
        return Pairing(topic, key)
    }

    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    /** 복호화에 실패하면(다른 코드로 보낸 메시지·손상된 메시지) null */
    fun decrypt(key: ByteArray, encoded: String): String? = try {
        val raw = Base64.getDecoder().decode(encoded.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
