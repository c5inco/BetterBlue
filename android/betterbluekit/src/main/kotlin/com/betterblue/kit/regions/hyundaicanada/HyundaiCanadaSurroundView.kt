package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada Surround View Monitor (SVM). Mirrors HyundaiCanada+SurroundView.swift.
//
// Two endpoints, same "remote function call" family as `fndmcr`:
//
//   rfc/fndmcrsvm  — tells the vehicle to wake its cameras, shoot, and
//                    upload. Returns immediately; the images land on
//                    Hyundai's servers a few minutes later.
//   rfc/lastmcrsvm — returns the captures the server is holding (several,
//                    newest first), each with its imagery base64-encoded in
//                    `svmImage`.

import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.json.asDoubleOrNull
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.SurroundViewDecoder
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

internal suspend fun HyundaiCanadaClient.requestSurroundViewCaptureImpl(vehicle: Vehicle, authToken: AuthToken) {
    ensureCloudFlareCookie()
    val authCode = fetchCommandAuthCode(authToken)

    performSurroundViewRequest(
        path = "rfc/fndmcrsvm",
        vehicle = vehicle,
        authToken = authToken,
        authCode = authCode,
        requestType = HttpRequestType.REQUEST_SURROUND_VIEW,
    )
}

internal suspend fun HyundaiCanadaClient.fetchSurroundViewCapturesImpl(
    vehicle: Vehicle,
    authToken: AuthToken,
): List<SurroundViewCapture> {
    ensureCloudFlareCookie()
    val authCode = fetchCommandAuthCode(authToken)

    val data = performSurroundViewRequest(
        path = "rfc/lastmcrsvm",
        vehicle = vehicle,
        authToken = authToken,
        authCode = authCode,
        requestType = HttpRequestType.FETCH_SURROUND_VIEW,
    )

    return parseCanadaSurroundViewResponse(data, vehicle)
}

// Request

/**
 * Sends one SVM call with the native-app identity, falling back once to this
 * client's own login headers.
 *
 * Same rule as `fndmcr`: everything in the "find my car" family answers
 * `from: SPA` ([locationHeaders]) and rejects `from: CWP` with errorCode 6459,
 * regardless of which identity the account logged in with (BetterBlueKit#36).
 * Verified on a live account — a capture request that 6459'd on the
 * web-portal headers succeeded first try on these.
 *
 * SVM and `fndmcr` are the same remote-function family, so if the location
 * sweep has already learned this account answers only to its own login
 * identity, lead with that instead of re-paying the rejection on every poll
 * of a capture.
 *
 * The response is validated inside each attempt on purpose: this API signals
 * refusal as HTTP 200 with `responseCode: 1` in the body, so a fallback keyed
 * on transport errors alone would never fire.
 */
private suspend fun HyundaiCanadaClient.performSurroundViewRequest(
    path: String,
    vehicle: Vehicle,
    authToken: AuthToken,
    authCode: String,
    requestType: HttpRequestType,
): ByteArray {
    val native = locationHeaders(authToken, vehicleId = vehicle.regId, pAuth = authCode)
    val account = authorizedHeaders(authToken, vehicleId = vehicle.regId, pAuth = authCode)
    val ordered = if (locationStrategy == LocationStrategy.FIND_MY_CAR_ACCOUNT) {
        listOf(account, native)
    } else {
        listOf(native, account)
    }

    var firstError: Exception? = null
    for (requestHeaders in ordered) {
        try {
            return sendSurroundViewRequest(path, vehicle, requestHeaders, requestType)
        } catch (error: Exception) {
            if (firstError == null) firstError = error
            BBLogger.debug(BBLogCategory.API, "HyundaiCanada: $path failed, trying the other identity: $error")
        }
    }

    // Surface the FIRST failure: the fallback is a guess, so its error is
    // usually less informative than the one from the identity this account
    // actually logs in with.
    throw firstError ?: ApiException.logError("Surround view request failed", apiName = apiName)
}

private suspend fun HyundaiCanadaClient.sendSurroundViewRequest(
    path: String,
    vehicle: Vehicle,
    requestHeaders: Map<String, String>,
    requestType: HttpRequestType,
): ByteArray {
    val (_, result) = performJsonRequest(
        url = "$apiBaseUrl/$path",
        method = HttpMethod.POST,
        headers = requestHeaders,
        body = buildJsonObject { put("pin", pin) },
        requestType = requestType,
        vin = vehicle.vin,
    )

    parseCanadaResponse(result.body, context = "surround view")
    return result.body
}

// Parsing

internal fun HyundaiCanadaClient.parseCanadaSurroundViewResponse(
    data: ByteArray,
    vehicle: Vehicle,
): List<SurroundViewCapture> {
    val json = parseCanadaResponse(data, context = "surround view")

    val result = json["result"] as? JsonObject
    val locations = result?.get("svmLocations") as? JsonArray
        ?: throw ApiException.logError("Invalid Canada surround view response", apiName = apiName)

    val captures = locations.mapNotNull { entry ->
        (entry as? JsonObject)?.let { parseSurroundViewLocation(it, vehicle) }
    }

    // Newest first. The server has been observed returning them in that order
    // already, but nothing documents that guarantee.
    return captures.sortedByDescending { it.capturedAt ?: Instant.MIN }
}

private fun parseSurroundViewLocation(location: JsonObject, vehicle: Vehicle): SurroundViewCapture? {
    val encodedImage = location["svmImage"].asStringOrNull()
    val imageData = encodedImage?.let {
        try {
            // MIME decoder skips unknown characters, matching Swift's
            // `.ignoreUnknownCharacters` decode option.
            Base64.getMimeDecoder().decode(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    if (imageData == null) {
        BBLogger.debug(BBLogCategory.API, "HyundaiCanada: skipping surround view entry without decodable image")
        return null
    }

    val frames = SurroundViewDecoder.extractJpegFrames(imageData)
    if (frames.isEmpty()) {
        BBLogger.debug(BBLogCategory.API, "HyundaiCanada: surround view entry contained no JPEG frames")
        return null
    }

    val imageSize = (location["imageSize"] as? JsonArray)?.mapNotNull { it.asIntOrNull() } ?: emptyList()
    val gpsDetail = location["gpsDetail"] as? JsonObject

    return SurroundViewCapture(
        vin = vehicle.vin,
        capturedAt = parseSurroundViewTimestamp(location["utcTime"] ?: gpsDetail?.get("time")),
        location = parseSurroundViewLocationCoordinates(gpsDetail),
        heading = gpsDetail?.get("head").asIntOrNull(),
        doorOpen = parseSurroundViewDoors(location["doorOpen"] as? JsonObject),
        trunkOpen = parseSurroundViewFlag(location["trunkOpen"]),
        sideMirrorOpen = parseSurroundViewFlag(location["sidemirrorOpen"]),
        frames = frames,
        tiles = SurroundViewDecoder.tiles(imageSize = imageSize, frameCount = frames.size),
    )
}

/**
 * `utcTime` is a bare `yyyyMMddHHmmss` stamp in UTC, e.g. "20260826003935".
 * The sibling `offset` field is the vehicle's local timezone offset and is
 * deliberately ignored — the app renders the capture time in the user's own
 * timezone.
 */
private fun parseSurroundViewTimestamp(value: JsonElement?): Instant? {
    val raw = value.asStringOrNull() ?: return null
    if (raw.length != 14) return null
    return BluelinkDates.parseBasic14Utc(raw)
}

/**
 * SVM reports coordinates as flat `coordLat` / `coordLon` fields, unlike the
 * `coord: { lat, lon }` object the status endpoints use.
 */
private fun parseSurroundViewLocationCoordinates(gpsDetail: JsonObject?): VehicleStatus.Location? {
    if (gpsDetail == null) return null
    val latitude = gpsDetail["coordLat"].asDoubleOrNull() ?: return null
    val longitude = gpsDetail["coordLon"].asDoubleOrNull() ?: return null

    val location = VehicleStatus.Location(latitude, longitude)
    return if (location.hasCoordinates) location else null
}

/**
 * Reads an open/closed flag that may arrive as a JSON boolean or as 0/1. This
 * region mixes the two even within one payload — the same `unit` field is
 * `true` under `dte` and `1` under `distanceToEmpty` (BetterBlue#98) — so
 * never bet on a bare strict-boolean read. Returns null only when the key is
 * absent entirely.
 */
private fun parseSurroundViewFlag(value: JsonElement?): Boolean? {
    if (value == null) return null
    value.asJsonBooleanOrNull()?.let { return it }
    value.asIntOrNull()?.let { return it != 0 }
    val string = value.asStringOrNull() ?: return null
    return listOf("true", "1", "y", "yes", "open").contains(string.lowercase())
}

private fun parseSurroundViewDoors(doors: JsonObject?): VehicleStatus.DoorStatus? {
    if (doors == null) return null

    fun isOpen(key: String): Boolean = parseSurroundViewFlag(doors[key]) ?: false

    return VehicleStatus.DoorStatus(
        frontLeft = isOpen("frontLeft"),
        frontRight = isOpen("frontRight"),
        backLeft = isOpen("backLeft"),
        backRight = isOpen("backRight"),
    )
}
