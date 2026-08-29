package com.betterblue.kit.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitiveDataRedactorTest {

    @Test
    fun `redacts passwords pins and otp codes`() {
        val input = """{"password":"hunter2","pin":"1234","otpNo":"987654"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertEquals("""{"password":"[REDACTED]","pin":"[REDACTED]","otpNo":"[REDACTED]"}""", redacted)
    }

    @Test
    fun `redacts bearer tokens`() {
        val redacted = SensitiveDataRedactor.redact("Authorization: Bearer abc123.def-456_ghi")!!
        assertEquals("Authorization: Bearer [REDACTED]", redacted)
    }

    @Test
    fun `redacts token fields including escaped quotes`() {
        val input = """{"access_token":"tok-1","refreshToken":"tok-2","rememberMeToken":"tok-3"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertFalse(redacted.contains("tok-1"))
        assertFalse(redacted.contains("tok-2"))
        assertFalse(redacted.contains("tok-3"))
    }

    @Test
    fun `redacts bare and suffixed coordinate keys`() {
        val input = """{"latitude":43.65,"coordLat":"43.6532","gallon":3}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertFalse(redacted.contains("43.65"))
        // Innocent keys that merely end in "lat"-like text survive.
        assertTrue(redacted.contains("\"gallon\":3"))
    }

    @Test
    fun `redacts coordinate pairs`() {
        val redacted = SensitiveDataRedactor.redact("[43.6532,-79.3832]")!!
        assertEquals("[[LOCATION_REDACTED]]", redacted)
    }

    @Test
    fun `masks emails keeping first char and tld`() {
        val input = """{"username":"johndoe@example.com"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertEquals("""{"username":"j***@***.com"}""", redacted)
    }

    @Test
    fun `redacts emails embedded in url paths`() {
        val redacted = SensitiveDataRedactor.redact("/ac/v2/enrollment/details/user@example.com")!!
        assertEquals("/ac/v2/enrollment/details/[EMAIL_REDACTED]", redacted)
    }

    @Test
    fun `partially masks device ids keeping first 8 and last 4`() {
        val input = """{"deviceId":"ABCD1234EFGH5678IJKL"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertEquals("""{"deviceId":"ABCD1234…IJKL"}""", redacted)
    }

    @Test
    fun `blanks device ids too short to mask`() {
        val input = """{"deviceId":"short"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertEquals("""{"deviceId":"[REDACTED]"}""", redacted)
    }

    @Test
    fun `masks vins keeping first 3 and last 4`() {
        val input = """{"vin":"KMHL14JA5MA123456"}"""
        val redacted = SensitiveDataRedactor.redact(input)!!
        assertEquals("""{"vin":"KMH**********3456"}""", redacted)
    }

    @Test
    fun `redacts registration ids`() {
        val input = """{"regId":"REG-99887766"}"""
        assertEquals("""{"regId":"[REDACTED]"}""", SensitiveDataRedactor.redact(input))
    }

    @Test
    fun `null passes through`() {
        assertNull(SensitiveDataRedactor.redact(null))
        assertNull(SensitiveDataRedactor.elideOversizedValues(null))
    }

    // Header redaction

    @Test
    fun `redacts sensitive headers`() {
        val headers = mapOf(
            "Authorization" to "Bearer secret-token",
            "Cookie" to "__cf_bm=abc",
            "clientSecret" to "s3cret",
            "Accesstoken" to "tok",
            "Content-Type" to "application/json",
        )
        val redacted = SensitiveDataRedactor.redactHeaders(headers)
        assertEquals("Bearer [REDACTED]", redacted["Authorization"])
        assertEquals("[REDACTED]", redacted["Cookie"])
        assertEquals("[REDACTED]", redacted["clientSecret"])
        assertEquals("[REDACTED]", redacted["Accesstoken"])
        assertEquals("application/json", redacted["Content-Type"])
    }

    // Oversized value elision

    @Test
    fun `elides oversized string values but keeps structure`() {
        val bigValue = "A".repeat(5000)
        val input = """{"image":"$bigValue","meta":"small"}"""
        val elided = SensitiveDataRedactor.elideOversizedValues(input)!!
        assertEquals("""{"image":"[5000 characters elided]","meta":"small"}""", elided)
    }

    @Test
    fun `short payloads pass through untouched`() {
        val input = """{"a":"b"}"""
        assertEquals(input, SensitiveDataRedactor.elideOversizedValues(input))
    }

    @Test
    fun `elision honors escaped quotes inside values`() {
        val bigValue = ("x\\\"" + "y".repeat(5000))
        val input = """{"blob":"$bigValue","z":1}"""
        val elided = SensitiveDataRedactor.elideOversizedValues(input)!!
        assertTrue(elided.contains("characters elided"))
        assertTrue(elided.endsWith("\"z\":1}"))
    }

    @Test
    fun `unterminated string copies remainder verbatim`() {
        val big = "B".repeat(5000)
        val input = """{"a":"$big"""" .dropLast(1) // remove closing quote
        val elided = SensitiveDataRedactor.elideOversizedValues(input)!!
        assertTrue(elided.startsWith("""{"a":""""))
    }
}
