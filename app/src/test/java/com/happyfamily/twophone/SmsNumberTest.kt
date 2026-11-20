package com.happyfamily.twophone

import org.junit.Assert.*
import org.junit.Test

class SmsNumberTest {
    @Test fun cleansNumbers() {
        assertEquals("01012345678", SmsSender.cleanNumber("010-1234-5678"))
        assertEquals("01012345678", SmsSender.cleanNumber(" 010 1234 5678 "))
        assertEquals("+821012345678", SmsSender.cleanNumber("+82 10-1234-5678"))
        assertEquals("114", SmsSender.cleanNumber("114"))
    }

    @Test fun rejectsNonNumbers() {
        assertNull(SmsSender.cleanNumber(""))
        assertNull(SmsSender.cleanNumber("홍길동"))
        assertNull(SmsSender.cleanNumber("010-1234-5678 엄마"))
        assertNull(SmsSender.cleanNumber("12"))
        assertNull(SmsSender.cleanNumber("010+1234"))
    }
}
