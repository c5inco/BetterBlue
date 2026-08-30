package com.betterblue.kit.regions.hyundaieurope

import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.regions.ccsp.CcspStamp

// Header builders for the Hyundai Europe client, split out to mirror the
// Swift `+Headers` extension file.
//
// Note vs. the Swift original: Swift set `Accept-Encoding: gzip` and
// `Connection: Keep-Alive` explicitly. OkHttp adds both itself and — crucially
// — only transparently gunzips the response when IT added the Accept-Encoding
// header, so we deliberately leave them off here; the bytes on the wire are
// identical.

internal fun HyundaiEuropeClient.authorizedHeaders(
    authToken: AuthToken,
    ccs2: Boolean = false,
): Map<String, String> =
    mapOf(
        "Authorization" to "Bearer ${authToken.accessToken}",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to "okhttp/3.14.9",
        "ccsp-service-id" to HyundaiEuropeClient.CLIENT_ID,
        "ccsp-application-id" to HyundaiEuropeClient.APP_ID,
        "ccsp-device-id" to (config.deviceId ?: ""),
        "Ccuccs2protocolsupport" to if (ccs2) "1" else "0",
        "Host" to apiHost,
        // Fresh stamp per request — the server validates the embedded timestamp
        // window (see CcspStamp).
        "Stamp" to generateStamp(),
    )

internal fun HyundaiEuropeClient.loginHeaders(): Map<String, String> =
    mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "okhttp/3.14.9",
    )

/**
 * Command headers: the plain Authorization is replaced by the PIN-derived
 * control token AND duplicated as AuthorizationCCSP — the control endpoints
 * check the latter.
 */
internal fun HyundaiEuropeClient.commandHeaders(
    authToken: AuthToken,
    ccs2: Boolean = false,
): Map<String, String> =
    authorizedHeaders(authToken, ccs2) +
        mapOf(
            "Authorization" to "Bearer $commandToken",
            "AuthorizationCCSP" to "Bearer $commandToken",
        )

internal fun HyundaiEuropeClient.generateStamp(): String =
    CcspStamp.generateStamp(HyundaiEuropeClient.APP_ID, HyundaiEuropeClient.AUTH_CFB)
