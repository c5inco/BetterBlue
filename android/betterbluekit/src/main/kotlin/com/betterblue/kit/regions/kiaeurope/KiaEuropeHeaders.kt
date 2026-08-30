package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.ApiException
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.regions.ccsp.CcspStamp
import okhttp3.FormBody
import okio.Buffer
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

// Header builders, the stamp helper, and the RSA / encoding plumbing the
// headless signin flow needs — mirroring the Swift `+Headers` extension file.
//
// Note vs. the Swift original: Swift set `Accept-Encoding: gzip` and
// `Connection: Keep-Alive` explicitly. OkHttp adds both itself and only
// transparently gunzips the response when IT added Accept-Encoding, so we
// deliberately leave them off here; the bytes on the wire are identical.

internal fun KiaEuropeClient.authorizedHeaders(
    authToken: AuthToken,
    ccs2: Boolean = false,
): Map<String, String> = mapOf(
    "Authorization" to "Bearer ${authToken.accessToken}",
    "Content-Type" to "application/json",
    "Accept" to "application/json",
    "User-Agent" to "okhttp/3.14.9",
    "ccsp-service-id" to KiaEuropeClient.CLIENT_ID,
    "ccsp-application-id" to KiaEuropeClient.APP_ID,
    "ccsp-device-id" to (config.deviceId ?: ""),
    "Ccuccs2protocolsupport" to if (ccs2) "1" else "0",
    "Host" to apiHost,
    // Fresh stamp per request — the server validates the embedded timestamp
    // window (see CcspStamp; the prior HMAC form 403'd on control endpoints).
    "Stamp" to generateStamp(),
)

/**
 * Command headers: the plain Authorization is replaced by the PIN-derived
 * control token AND duplicated as AuthorizationCCSP.
 */
internal fun KiaEuropeClient.commandHeaders(
    authToken: AuthToken,
    ccs2: Boolean = false,
): Map<String, String> = authorizedHeaders(authToken, ccs2) + mapOf(
    "Authorization" to "Bearer $commandToken",
    "AuthorizationCCSP" to "Bearer $commandToken",
)

internal fun KiaEuropeClient.generateStamp(): String =
    CcspStamp.generateStamp(KiaEuropeClient.APP_ID, KiaEuropeClient.AUTH_CFB)

// RSA / encoding helpers (used by signin)

/**
 * PKCS#1 v1.5 encrypt [password] with the RSA public key described by the
 * JWK `(n, e)` pair, returning a LOWERCASE hex string (the IDP requires
 * lowercase). Unlike the Swift client, which had to hand-roll a DER blob for
 * SecKey, the JCA can build the key straight from the (n, e) integers.
 */
internal fun rsaEncryptPkcs1(
    password: String,
    jwkN: String,
    jwkE: String,
    apiName: String = "KiaEurope",
): String {
    val nBytes = base64UrlDecode(jwkN)
    val eBytes = base64UrlDecode(jwkE)
    if (nBytes == null || eBytes == null) {
        throw ApiException("Invalid base64url in JWK", apiName = apiName)
    }
    val encrypted = try {
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(1, nBytes), BigInteger(1, eBytes)),
        )
        Cipher.getInstance("RSA/ECB/PKCS1Padding")
            .apply { init(Cipher.ENCRYPT_MODE, publicKey) }
            .doFinal(password.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        throw ApiException("RSA encryption failed: ${e.message ?: e}", apiName = apiName, cause = e)
    }
    return encrypted.joinToString("") { "%02x".format(it) }
}

/** base64url → bytes, tolerating missing padding; null when unparseable. */
internal fun base64UrlDecode(input: String): ByteArray? = try {
    val normalized = input.replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    Base64.getDecoder().decode(padded)
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Form-encode key/value pairs preserving insertion order. Uses OkHttp's
 * [FormBody] encoder, which — like the Swift client's custom RFC 3986
 * encoder — percent-encodes `+`, `&`, and `=` inside values, so an encrypted
 * password hex string or an email address survives intact.
 */
internal fun kiaFormEncode(fields: List<Pair<String, String>>): String {
    val body = FormBody.Builder().apply {
        for ((key, value) in fields) add(key, value)
    }.build()
    return Buffer().also { body.writeTo(it) }.readUtf8()
}
