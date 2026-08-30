package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada shared helpers: header identities, the Cloudflare handshake,
// and the shared `{responseHeader, result, error}` envelope parser.
// Mirrors the Swift HyundaiCanada.swift extension.

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.json.isJsonBoolean
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZonedDateTime
import java.util.Locale

// Headers

internal fun HyundaiCanadaClient.headers(): Map<String, String> =
    // `from` + User-Agent depend on the selected connection variant
    // (web-portal vs native app) — see `HyundaiCanadaVariant`.
    mapOf(
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "Host" to apiHost,
        "deviceid" to deviceId,
        "from" to fromHeader,
        "language" to "0",
        "offset" to timezoneOffsetHeader,
        "User-Agent" to userAgent,
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "origin" to "https://$apiHost",
        "referer" to "https://$apiHost/login",
    )

/**
 * Native-app-style headers for the `evc/fme` vehicle-location endpoint used by
 * the web-portal variant. That endpoint requires the native-app identity
 * (`from: SPA`, `brand: H`, MyHyundai iOS User-Agent, lowercase header keys)
 * even when login used the web-portal client — a CA owner verified this in
 * BetterBlueKit#36.
 */
internal fun HyundaiCanadaClient.locationHeaders(
    authToken: AuthToken,
    vehicleId: String,
    pAuth: String,
): Map<String, String> = buildMap {
    put("client_id", clientId)
    put("client_secret", clientSecret)
    put("host", apiHost)
    put("deviceid", deviceId)
    put("from", "SPA")
    put("brand", "H")
    put("language", "0")
    put("offset", timezoneOffsetHeader)
    put("user-agent", HyundaiCanadaClient.NATIVE_USER_AGENT)
    put("content-type", "application/json")
    put("accept", "application/json")
    put("accesstoken", authToken.accessToken)
    put("vehicleid", vehicleId)
    put("pauth", pAuth)
    cloudFlareCookie?.let { put("cookie", it) }
}

internal fun HyundaiCanadaClient.authorizedHeaders(
    authToken: AuthToken,
    vehicleId: String? = null,
    pAuth: String? = null,
): Map<String, String> = buildMap {
    putAll(headers())
    put("Accesstoken", authToken.accessToken)

    if (vehicleId != null) put("Vehicleid", vehicleId)
    if (pAuth != null) put("Pauth", pAuth)
    cloudFlareCookie?.let { put("Cookie", it) }
}

// Cloudflare cookie

/**
 * GET the login page so Cloudflare mints a `__cf_bm` cookie, and return it in
 * `Cookie`-header form. Cached by [HyundaiCanadaClient.ensureCloudFlareCookie]
 * and attached explicitly to every subsequent API call, mirroring the Swift
 * client (the per-instance cookie jar would carry it too, but the explicit
 * header keeps behavior identical and visible in logs).
 */
internal suspend fun HyundaiCanadaClient.fetchCloudFlareCookie(): String {
    val result = performRequest(
        url = "https://$apiHost/login",
        method = HttpMethod.GET,
        headers = headers(),
        requestType = HttpRequestType.LOGIN,
    )

    val cookieValue = result.headers.values("Set-Cookie").firstNotNullOfOrNull { header ->
        val nameValue = header.substringBefore(';')
        val name = nameValue.substringBefore('=').trim()
        if (name.equals("__cf_bm", ignoreCase = true)) nameValue.substringAfter('=').trim() else null
    } ?: throw ApiException.logError("CloudFlare cookie missing from login response", apiName = apiName)

    return "__cf_bm=$cookieValue"
}

// Shared response parser

internal fun HyundaiCanadaClient.parseCanadaResponse(data: ByteArray, context: String): JsonObject {
    val json = ApiClientBase.parseJsonObject(data)
    if (json.isEmpty()) {
        throw ApiException.logError("Invalid JSON in Canada $context response", apiName = apiName)
    }

    val responseHeader = json["responseHeader"] as? JsonObject
        ?: throw ApiException.logError("Missing responseHeader in Canada $context response", apiName = apiName)

    if (isCanadaResponseSuccess(responseHeader["responseCode"])) {
        return json
    }

    val error = json["error"] as? JsonObject
    val errorDesc = error?.get("errorDesc").asStringOrNull() ?: "Unknown Canada API error: $json"
    val lower = errorDesc.lowercase()

    if (lower.contains("expired") || lower.contains("deleted") || lower.contains("ip validation")) {
        throw ApiException.invalidCredentials(errorDesc, apiName = apiName)
    }

    throw ApiException.logError("Canada $context failed: $errorDesc", apiName = apiName)
}

/**
 * Hyundai Canada's `responseCode` field has been observed in three shapes:
 * integer (`0`/`1`), string (`"0"`/`"1"`), and most recently — as of mid-2026
 * — JSON boolean (`false`/`true`). Boolean values are INVERTED: `false` means
 * success, `true` means failure (matching the `responseDesc: "Success"` /
 * `"Failure"` strings the same field carries).
 */
internal fun isCanadaResponseSuccess(value: JsonElement?): Boolean {
    val primitive = value as? JsonPrimitive ?: return false
    if (primitive is JsonNull) return false
    // JSON boolean — inverted: false means success.
    if (primitive.isJsonBoolean) return primitive.content == "false"
    if (primitive.isString) {
        return primitive.content == "0" || primitive.content.lowercase() == "false"
    }
    // Bare number: 0 (or 0.0) is success.
    return primitive.content.toDoubleOrNull() == 0.0
}

/** Local GMT offset in hours, formatted `%+03d` (e.g. "-05", "+01"). */
internal val HyundaiCanadaClient.timezoneOffsetHeader: String
    get() {
        val hours = ZonedDateTime.now().offset.totalSeconds / 3600
        return String.format(Locale.US, "%+03d", hours)
    }
