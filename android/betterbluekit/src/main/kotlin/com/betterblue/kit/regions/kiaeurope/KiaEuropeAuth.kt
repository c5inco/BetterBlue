package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// The headless IDPConnect signin flow for Kia Europe, mirroring the Swift
// `+Auth` extension file:
//   authorize (cookies) → certs (JWK) → RSA-encrypted signin POST → 302 with
//   ?code= → token exchange.
//
// Every request here carries KiaEuropeClient.MOBILE_USER_AGENT — its
// `_CCS_APP_AOS` suffix is what clears Cloudflare on idpconnect-eu.kia.com.
// All calls skip response validation, matching Swift's raw URLSession usage;
// errors are surfaced from the final redirect URL / token parsing instead.

/** IDPConnect headless signin: authorize → certs → encrypted-signin → 302 with ?code=. */
internal suspend fun KiaEuropeClient.signin(): String {
    fetchAuthorizeCookies()
    val jwk = fetchSigninJwk()
    val encryptedHex = rsaEncryptPkcs1(password, jwk.modulus, jwk.exponent, apiName)
    val finalUrl = submitSignin(encryptedHex, jwk.kid)
    return parseSigninCode(finalUrl)
}

/**
 * Step 1: GET authorize — sets session cookies on idpconnect-eu.kia.com.
 * Response body discarded; we just need the cookies for the later POST, and
 * the client's per-instance [com.betterblue.kit.http.InMemoryCookieJar]
 * stores them automatically.
 */
private suspend fun KiaEuropeClient.fetchAuthorizeCookies() {
    val authorizeUrl =
        "$authBaseUrl/auth/api/v2/user/oauth2/authorize"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", KiaEuropeClient.CLIENT_ID)
            .addQueryParameter("redirect_uri", oauthRedirectUri)
            .addQueryParameter("lang", "en")
            .addQueryParameter("state", "ccsp")
            .addQueryParameter("country", "de")
            .build()

    performRequest(
        url = authorizeUrl.toString(),
        method = HttpMethod.GET,
        headers = mapOf("User-Agent" to KiaEuropeClient.MOBILE_USER_AGENT),
        requestType = HttpRequestType.LOGIN,
        validateResponse = false,
    )
}

/**
 * RSA public key returned by `/auth/api/v1/accounts/certs`. The IDP rotates
 * `kid`, so it has to be threaded back into the signin POST alongside the
 * encrypted password.
 */
internal data class KiaEuropeJwk(
    /** RSA modulus (JWK field `n`), base64url. */
    val modulus: String,
    /** RSA public exponent (JWK field `e`), base64url. */
    val exponent: String,
    /** Key id; threaded back into the signin POST. */
    val kid: String,
)

/** Step 2: GET certs — pull the JWK (modulus, exponent, kid) for password encryption. */
private suspend fun KiaEuropeClient.fetchSigninJwk(): KiaEuropeJwk {
    val result =
        performRequest(
            url = "$authBaseUrl/auth/api/v1/accounts/certs",
            method = HttpMethod.GET,
            headers = mapOf("User-Agent" to KiaEuropeClient.MOBILE_USER_AGENT),
            requestType = HttpRequestType.LOGIN,
            validateResponse = false,
        )
    val retValue = ApiClientBase.parseJsonObject(result.body)["retValue"] as? JsonObject
    val modulus = retValue?.get("n").asStringOrNull()
    val exponent = retValue?.get("e").asStringOrNull()
    val kid = retValue?.get("kid").asStringOrNull()
    if (modulus == null || exponent == null || kid == null) {
        throw ApiException("Failed to parse JWK from /accounts/certs", apiName = apiName)
    }
    return KiaEuropeJwk(modulus, exponent, kid)
}

/**
 * Step 4: POST signin (form-encoded, EXACT field order — the IDP is picky).
 * OkHttp follows the 302 to the redirect_uri; the FINAL URL carries `?code=…`
 * (or an error) in its query, which is why this returns
 * [com.betterblue.kit.http.HttpResult.finalUrl].
 */
private suspend fun KiaEuropeClient.submitSignin(encryptedHex: String, kid: String): String {
    val signinFields =
        listOf(
            "client_id" to KiaEuropeClient.CLIENT_ID,
            "encryptedPassword" to "true",
            "password" to encryptedHex,
            "redirect_uri" to oauthRedirectUri,
            "scope" to "",
            "nonce" to "",
            "state" to "ccsp",
            "username" to username,
            "connector_session_key" to "",
            "kid" to kid,
            "_csrf" to "",
        )
    val result =
        performRequest(
            url = "$authBaseUrl/auth/account/signin",
            method = HttpMethod.POST,
            headers =
                mapOf(
                    "User-Agent" to KiaEuropeClient.MOBILE_USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
            body = kiaFormEncode(signinFields).toByteArray(Charsets.UTF_8),
            requestType = HttpRequestType.LOGIN,
            validateResponse = false,
        )
    return result.finalUrl
}

/**
 * Parse the final URL's query for `code` (success) or translate an error
 * redirect into a typed [ApiException].
 */
internal fun KiaEuropeClient.parseSigninCode(finalUrl: String): String {
    val url =
        finalUrl.toHttpUrlOrNull()
            ?: throw ApiException.invalidCredentials("Signin returned no redirect", apiName = apiName)

    url.queryParameter("code")?.takeIf { it.isNotEmpty() }?.let { return it }

    url.queryParameter("error_description")?.let { errorDesc ->
        throw ApiException.invalidCredentials("Authentication rejected: $errorDesc", apiName = apiName)
    }
    if (url.encodedPath.contains("/web/v1/user/authorization")) {
        throw ApiException(
            "Kia account consent required — log in via a browser once to accept the terms",
            apiName = apiName,
        )
    }
    if (url.encodedPath.contains("authorize")) {
        throw ApiException.invalidCredentials(
            "Authentication failed — returned to login page. Check username and password.",
            apiName = apiName,
        )
    }
    throw ApiException("Unexpected redirect after signin: $finalUrl", apiName = apiName)
}

/** Exchange `?code=…` from the signin redirect for access + refresh tokens. */
internal suspend fun KiaEuropeClient.exchangeForToken(code: String): AuthToken =
    postTokenRequest(
        fields =
            listOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to oauthRedirectUri,
                "client_id" to KiaEuropeClient.CLIENT_ID,
                "client_secret" to KiaEuropeClient.CLIENT_SECRET,
            ),
        isRefresh = true,
    )

/** Refresh-grant: trade the stored refresh_token for a fresh access_token. */
internal suspend fun KiaEuropeClient.getAccessTokenFromRefreshToken(): AuthToken =
    postTokenRequest(
        fields =
            listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to (config.refreshToken ?: ""),
                "redirect_uri" to oauthRedirectUri,
                "client_id" to KiaEuropeClient.CLIENT_ID,
                "client_secret" to KiaEuropeClient.CLIENT_SECRET,
            ),
        isRefresh = false,
    )

/** Shared POST /oauth2/token helper for the two token grants above. */
private suspend fun KiaEuropeClient.postTokenRequest(
    fields: List<Pair<String, String>>,
    isRefresh: Boolean,
): AuthToken {
    val result =
        performRequest(
            url = "$authBaseUrl/auth/api/v2/user/oauth2/token",
            method = HttpMethod.POST,
            headers =
                mapOf(
                    "User-Agent" to KiaEuropeClient.MOBILE_USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
            body = kiaFormEncode(fields).toByteArray(Charsets.UTF_8),
            requestType = HttpRequestType.LOGIN,
            validateResponse = false,
        )
    return parseAuthToken(result.body, isRefresh = isRefresh)
}
