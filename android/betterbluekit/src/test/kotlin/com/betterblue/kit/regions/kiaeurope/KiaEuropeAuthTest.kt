package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Cipher

/**
 * The Kia Europe headless IDPConnect signin: RSA-encrypted password, exact
 * form-field order, and reading the OAuth `code` out of the final redirect.
 */
class KiaEuropeAuthTest {

    private fun makeClient() = KiaEuropeClient(
        ApiClientConfig(
            region = Region.EUROPE,
            brand = Brand.KIA,
            username = "test@example.com",
            password = "password123",
            pin = "1234",
            accountId = "00000000-0000-0000-0000-000000000001",
        ),
    )

    // RSA password encryption

    @Test
    fun `password round-trips through PKCS1 encryption`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n = encoder.encodeToString(publicKey.modulus.toByteArray())
        val e = encoder.encodeToString(publicKey.publicExponent.toByteArray())

        val hex = rsaEncryptPkcs1("hunter2", n, e)

        // The IDP expects lowercase hex.
        assertEquals(hex.lowercase(), hex)
        assertTrue(hex.matches(Regex("[0-9a-f]+")))

        val decrypted = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            .apply { init(Cipher.DECRYPT_MODE, keyPair.private) }
            .doFinal(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

        assertEquals("hunter2", decrypted.toString(Charsets.UTF_8))
    }

    @Test
    fun `PKCS1 padding makes repeat encryptions differ`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n = encoder.encodeToString(publicKey.modulus.toByteArray())
        val e = encoder.encodeToString(publicKey.publicExponent.toByteArray())

        assertTrue(rsaEncryptPkcs1("same", n, e) != rsaEncryptPkcs1("same", n, e))
    }

    @Test
    fun `invalid jwk material is rejected`() {
        assertThrows<ApiException> { rsaEncryptPkcs1("pw", "not base64url!!", "AQAB") }
    }

    @Test
    fun `base64url decoding tolerates missing padding`() {
        // JWK values are unpadded base64url with - and _ substitutions.
        assertEquals("AQAB", Base64.getEncoder().encodeToString(base64UrlDecode("AQAB")!!).trimEnd('='))
        assertNull(base64UrlDecode("!!!not base64!!!"))
    }

    // Form encoding

    @Test
    fun `form encoding preserves field order`() {
        // The IDP is picky about ordering; a reordered body is rejected.
        val encoded = kiaFormEncode(
            listOf(
                "client_id" to "cid",
                "encryptedPassword" to "true",
                "password" to "deadbeef",
                "redirect_uri" to "https://example.com/cb",
                "scope" to "",
                "nonce" to "",
                "state" to "ccsp",
                "username" to "user@example.com",
                "connector_session_key" to "",
                "kid" to "key-1",
                "_csrf" to "",
            ),
        )
        val keys = encoded.split("&").map { it.substringBefore("=") }
        assertEquals(
            listOf(
                "client_id", "encryptedPassword", "password", "redirect_uri", "scope",
                "nonce", "state", "username", "connector_session_key", "kid", "_csrf",
            ),
            keys,
        )
    }

    @Test
    fun `form encoding escapes reserved characters in values`() {
        // A '+' or '&' surviving unescaped would corrupt the encrypted
        // password or the username.
        val encoded = kiaFormEncode(listOf("username" to "a+b&c=d@example.com"))
        assertTrue(encoded.startsWith("username="))
        assertTrue(!encoded.removePrefix("username=").contains("&"))
        assertTrue(!encoded.removePrefix("username=").contains("+b"))
    }

    // Redirect handling

    @Test
    fun `code is read from the final redirect url`() {
        assertEquals(
            "auth-code-123",
            makeClient().parseSigninCode("https://prd.eu-ccapi.kia.com:8080/api/v1/user/oauth2/redirect?code=auth-code-123&state=ccsp"),
        )
    }

    @Test
    fun `error_description becomes an invalid-credentials error`() {
        val e = assertThrows<ApiException> {
            makeClient().parseSigninCode("https://idpconnect-eu.kia.com/auth/account/signin?error_description=Bad%20password")
        }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, e.errorType)
        assertTrue(e.message.contains("Bad password"))
    }

    @Test
    fun `a consent redirect explains the browser step`() {
        val e = assertThrows<ApiException> {
            makeClient().parseSigninCode("https://idpconnect-eu.kia.com/web/v1/user/authorization?foo=1")
        }
        assertTrue(e.message.contains("consent"))
    }

    @Test
    fun `landing back on authorize means bad credentials`() {
        val e = assertThrows<ApiException> {
            makeClient().parseSigninCode("https://idpconnect-eu.kia.com/auth/api/v2/user/oauth2/authorize?client_id=x")
        }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, e.errorType)
    }

    @Test
    fun `a non-url redirect is rejected`() {
        assertThrows<ApiException> { makeClient().parseSigninCode("not a url") }
    }

    // Cloudflare-sensitive constants

    @Test
    fun `mobile user agent keeps the CCS_APP_AOS suffix`() {
        // Load-bearing: without the suffix Cloudflare answers 400 on authorize.
        assertTrue(KiaEuropeClient.MOBILE_USER_AGENT.endsWith("_CCS_APP_AOS"))
    }
}
