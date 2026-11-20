package com.happyfamily.twophone

import org.junit.Assert.*
import org.junit.Test

class CryptoTest {
    @Test fun codeIsValidAndNormalizes() {
        val code = Crypto.newCode()
        assertTrue(Crypto.isValidCode(code))
        val typed = Crypto.formatCode(code).lowercase().replace("-", " - ")
        assertEquals(code, Crypto.normalizeCode(typed))
        assertFalse(Crypto.isValidCode("ABC"))
    }

    @Test fun sameCodeGivesSameChannelAndKey() {
        val a = Crypto.derive("ABCDEFGHJKLMNPQR")
        val b = Crypto.derive("ABCDEFGHJKLMNPQR")
        val c = Crypto.derive("ABCDEFGHJKLMNPQS")
        assertEquals(a.topic, b.topic)
        assertArrayEquals(a.key, b.key)
        assertNotEquals(a.topic, c.topic)
        assertEquals(34, a.topic.length)
        assertEquals(32, a.key.size)
    }

    @Test fun roundTripAndWrongKey() {
        val a = Crypto.derive("ABCDEFGHJKLMNPQR")
        val c = Crypto.derive("ABCDEFGHJKLMNPQS")
        val msg = "안녕하세요 😀 [Web발신] 인증번호 123456"
        val enc = Crypto.encrypt(a.key, msg)
        assertEquals(msg, Crypto.decrypt(a.key, enc))
        assertNull(Crypto.decrypt(c.key, enc))
        assertNull(Crypto.decrypt(a.key, "hello"))
        assertNotEquals(enc, Crypto.encrypt(a.key, msg))
    }

    @Test fun longestMessageFitsRelayLimit() {
        val a = Crypto.derive("ABCDEFGHJKLMNPQR")
        // 제목 100자 + 본문 700자 한글, JSON 포장 여유 300바이트
        val enc = Crypto.encrypt(a.key, "가".repeat(800) + "x".repeat(300))
        assertTrue(enc.length < 4096)
    }
}
