package com.betterblue.kit

import com.betterblue.kit.http.HttpResult
import com.betterblue.kit.json.asBooleanOrNull
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.atPath
import com.betterblue.kit.json.isJsonBoolean
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

private class TestClient(config: ApiClientConfig) : ApiClientBase(config) {
    override val apiName: String get() = "TestClient"
}

private fun testConfig() = ApiClientConfig(
    region = Region.USA,
    brand = Brand.HYUNDAI,
    username = "user@example.com",
    password = "password",
    pin = "1234",
    accountId = "00000000-0000-0000-0000-000000000001",
)

private fun result(code: Int, body: String = "{}") = HttpResult(
    body = body.toByteArray(),
    code = code,
    headers = Headers.headersOf(),
    finalUrl = "https://example.com",
)

class ApiClientBaseTest {

    private val client = TestClient(testConfig())

    // HTTP status validation

    @Test
    fun `401 maps to invalid credentials`() {
        val e = assertThrows<ApiException> { client.validateHttpResponse(result(401), null) }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, e.errorType)
    }

    @Test
    fun `502 maps to server error`() {
        val e = assertThrows<ApiException> { client.validateHttpResponse(result(502), null) }
        assertEquals(ApiErrorType.SERVER_ERROR, e.errorType)
    }

    @Test
    fun `other 4xx maps to general error with code`() {
        val e = assertThrows<ApiException> { client.validateHttpResponse(result(404), null) }
        assertEquals(ApiErrorType.GENERAL, e.errorType)
        assertEquals(404, e.code)
    }

    @Test
    fun `2xx passes`() {
        assertDoesNotThrow { client.validateHttpResponse(result(200), null) }
    }

    // CCSP envelope mapping

    private fun ccsp(resCode: String) =
        """{"retCode":"F","resCode":"$resCode","resMsg":"message"}""".toByteArray()

    @Test
    fun `ccsp 7501 maps to invalid credentials`() {
        val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp("7501")) }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, e.errorType)
    }

    @Test
    fun `ccsp 4002 maps to invalid vehicle session`() {
        val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp("4002")) }
        assertEquals(ApiErrorType.INVALID_VEHICLE_SESSION, e.errorType)
    }

    @Test
    fun `ccsp 4004 maps to concurrent request`() {
        val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp("4004")) }
        assertEquals(ApiErrorType.CONCURRENT_REQUEST, e.errorType)
    }

    @Test
    fun `ccsp timeouts map to server error`() {
        for (code in listOf("4081", "9999", "5031", "5091")) {
            val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp(code)) }
            assertEquals(ApiErrorType.SERVER_ERROR, e.errorType, "resCode $code")
        }
    }

    @Test
    fun `ccsp 4005 and 5921 map to general 400`() {
        for (code in listOf("4005", "5921")) {
            val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp(code)) }
            assertEquals(ApiErrorType.GENERAL, e.errorType, "resCode $code")
            assertEquals(400, e.code, "resCode $code")
        }
    }

    @Test
    fun `ccsp unknown code maps to general with message`() {
        val e = assertThrows<ApiException> { client.checkCcspResponseForErrors(ccsp("1234")) }
        assertTrue(e.message.contains("1234"))
    }

    @Test
    fun `non-ccsp bodies are ignored`() {
        assertDoesNotThrow { client.checkCcspResponseForErrors("""{"retCode":"S"}""".toByteArray()) }
        assertDoesNotThrow { client.checkCcspResponseForErrors("""{"status":"ok"}""".toByteArray()) }
        assertDoesNotThrow { client.checkCcspResponseForErrors("not json".toByteArray()) }
    }

    // API error extraction

    @Test
    fun `extracts nested status errors`() {
        val body = """{"status":{"errorCode":1005,"errorMessage":"Session invalid"}}""".toByteArray()
        assertEquals("API Error 1005: Session invalid", client.extractApiError(body))
    }

    @Test
    fun `zero error code is not an error`() {
        val body = """{"status":{"errorCode":0,"errorMessage":"OK"}}""".toByteArray()
        assertNull(client.extractApiError(body))
    }

    @Test
    fun `extracts top-level error codes`() {
        val body = """{"errorCode":7110,"errorMessage":"MFA required"}""".toByteArray()
        assertEquals("API Error 7110: MFA required", client.extractApiError(body))
    }

    @Test
    fun `extracts plain error strings`() {
        assertEquals("API Error: boom", client.extractApiError("""{"error":"boom"}""".toByteArray()))
    }

    // requiresMFA userInfo packing

    @Test
    fun `requiresMfa packs the challenge into userInfo`() {
        val e = ApiException.requiresMfa(
            xid = "xid-1",
            otpKey = "otp-1",
            hasEmail = true,
            hasPhone = false,
            email = "j***@***.com",
            rmTokenExpired = true,
        )
        assertEquals(ApiErrorType.REQUIRES_MFA, e.errorType)
        assertEquals("xid-1", e.userInfo?.get("xid"))
        assertEquals("otp-1", e.userInfo?.get("otpKey"))
        assertEquals("true", e.userInfo?.get("hasEmail"))
        assertEquals("false", e.userInfo?.get("hasPhone"))
        assertEquals("j***@***.com", e.userInfo?.get("email"))
        assertEquals("true", e.userInfo?.get("rmTokenExpired"))
        assertEquals("Session expired - verification required", e.message)
    }

    // JSON helpers

    @Test
    fun `atPath walks objects and array indices`() {
        val json = Json.parseToJsonElement(
            """{"a":{"b":[{"c":42},{"c":43}]}}""",
        )
        assertEquals(42.0, json.atPath("a.b.0.c").asDoubleOrNull())
        assertEquals(43.0, json.atPath("a.b.1.c").asDoubleOrNull())
        assertNull(json.atPath("a.b.2.c"))
        assertNull(json.atPath("a.x"))
    }

    @Test
    fun `boolean fuelLevel is not coerced to a number`() {
        // Kia US sends fuelLevel:false for pure EVs; coercing to 0.0 painted
        // phantom empty gas tanks.
        val json = Json.parseToJsonElement("""{"fuelLevel":false,"batteryLevel":81}""").jsonObject
        assertTrue(json["fuelLevel"].isJsonBoolean)
        assertNull(json["fuelLevel"].asDoubleOrNull())
        assertEquals(81.0, json["batteryLevel"].asDoubleOrNull())
        assertFalse(json["batteryLevel"].isJsonBoolean)
    }

    @Test
    fun `numbers as strings still extract`() {
        val json = Json.parseToJsonElement("""{"expires_in":"3600"}""").jsonObject
        assertEquals(3600.0, json["expires_in"].asDoubleOrNull())
    }

    @Test
    fun `boolean spellings coerce through asBooleanOrNull`() {
        val json = Json.parseToJsonElement(
            """{"a":true,"b":"true","c":1,"d":"Y","e":"off","f":"maybe"}""",
        ).jsonObject
        assertEquals(true, json["a"].asBooleanOrNull())
        assertEquals(true, json["b"].asBooleanOrNull())
        assertEquals(true, json["c"].asBooleanOrNull())
        assertEquals(true, json["d"].asBooleanOrNull())
        assertEquals(false, json["e"].asBooleanOrNull())
        assertNull(json["f"].asBooleanOrNull())
    }
}
