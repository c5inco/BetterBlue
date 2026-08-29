package com.betterblue.kit.regions.hyundaiusa

// Hyundai USA "Find My Car SVM" — the 360° camera stills.
//
// Two endpoints under the same `/ac/v2/` base as the rest of this client:
//
//   svm/findMyCarSVM  (POST) — wakes the cameras, shoots, and uploads.
//                              Returns quickly; the image lands minutes later.
//                              Answers 502 + errorSubCode HT_533 when a
//                              previous request is still pending.
//   svm/getSVMDetails (GET)  — returns the captures the server is holding,
//                              each with its imagery base64-encoded in
//                              `svmImage`.
//
// This differs from Hyundai Canada in the envelope, not the imagery: the
// array is `svmDetails[].svmDetail` (Canada: `result.svmLocations[]`),
// coordinates are nested under `gpsDetail.coord` (Canada: flat
// `coordLat`/`coordLon`), and the trigger posts the vehicle identity in the
// body rather than relying on a stored PIN token.

import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.json.asBooleanOrNull
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

internal suspend fun HyundaiUsaClient.requestSurroundViewCaptureImpl(vehicle: Vehicle, authToken: AuthToken) {
    try {
        performJsonRequest(
            url = "$baseUrl/ac/v2/svm/findMyCarSVM",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicle),
            body = buildJsonObject {
                put("vin", vehicle.vin)
                put("username", username)
                put("gen", vehicle.generation.toString())
                put("blueLinkServicePin", pin)
            },
            requestType = HttpRequestType.REQUEST_SURROUND_VIEW,
            vin = vehicle.vin,
        )
    } catch (error: ApiException) {
        // "A capture is already pending" arrives as HTTP 502 with errorSubCode
        // HT_533; validateHttpResponse folds the body into the error message.
        // Remap it so the UI shows a "request in progress" state.
        if (error.errorType == ApiErrorType.SERVER_ERROR && error.message.contains("HT_533")) {
            throw ApiException.concurrentRequest(
                "A surround view capture is already in progress. Please wait and try again.",
                apiName = apiName,
            )
        }
        throw error
    }
}

internal suspend fun HyundaiUsaClient.fetchSurroundViewCapturesImpl(
    vehicle: Vehicle,
    authToken: AuthToken,
): List<SurroundViewCapture> {
    val result = performRequest(
        url = "$baseUrl/ac/v2/svm/getSVMDetails",
        method = HttpMethod.GET,
        headers = authorizedHeaders(authToken, vehicle),
        requestType = HttpRequestType.FETCH_SURROUND_VIEW,
        vin = vehicle.vin,
    )

    return parseUsaSurroundViewResponse(result.body, vehicle)
}

internal fun HyundaiUsaClient.parseUsaSurroundViewResponse(
    data: ByteArray,
    vehicle: Vehicle,
): List<SurroundViewCapture> {
    val json = ApiClientBase.parseJsonObject(data)
    val details = json["svmDetails"] as? JsonArray
        ?: throw ApiException.logError("Invalid Hyundai USA surround view response", apiName = apiName)

    val captures = details.mapNotNull { entry ->
        val detail = (entry as? JsonObject)?.get("svmDetail") as? JsonObject ?: return@mapNotNull null
        parseUsaSurroundViewDetail(detail, vehicle)
    }

    // Newest first. Observed in that order already, but nothing documents the
    // guarantee.
    return captures.sortedByDescending { it.capturedAt ?: Instant.MIN }
}

private fun HyundaiUsaClient.parseUsaSurroundViewDetail(
    detail: JsonObject,
    vehicle: Vehicle,
): SurroundViewCapture? {
    val encodedImage = detail["svmImage"].asStringOrNull()
    val imageData = encodedImage?.let {
        try {
            Base64.getMimeDecoder().decode(it)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    if (imageData == null) {
        BBLogger.debug(BBLogCategory.API, "HyundaiUSA: skipping surround view entry without decodable image")
        return null
    }

    val frames = SurroundViewDecoder.extractJpegFrames(imageData)
    if (frames.isEmpty()) {
        BBLogger.debug(BBLogCategory.API, "HyundaiUSA: surround view entry contained no JPEG frames")
        return null
    }

    val imageSize = (detail["imageSize"] as? JsonArray)?.mapNotNull { it.asIntOrNull() } ?: emptyList()
    val gpsDetail = detail["gpsDetail"] as? JsonObject

    return SurroundViewCapture(
        vin = vehicle.vin,
        // The real sample carries the stamp under `gpsDetail.time`, but read a
        // top-level `time` first: a capture taken without a GPS fix may drop
        // `gpsDetail`, and without a fallback every such capture would land on
        // the same "unknown" id and lose its order.
        capturedAt = parseUsaSurroundViewTimestamp(detail["time"] ?: gpsDetail?.get("time")),
        location = parseUsaSurroundViewLocation(gpsDetail),
        heading = gpsDetail?.get("head").asIntOrNull(),
        doorOpen = parseUsaSurroundViewDoors(detail["doorOpen"] as? JsonObject),
        trunkOpen = parseUsaSurroundViewFlag(detail["trunkOpen"]),
        sideMirrorOpen = parseUsaSurroundViewFlag(detail["sidemirrorOpen"]),
        frames = frames,
        tiles = SurroundViewDecoder.tiles(imageSize = imageSize, frameCount = frames.size),
    )
}

/**
 * `gpsDetail.time` is a bare `yyyyMMddHHmmss` stamp. Read as UTC to match the
 * Canada client; the app renders the capture time in the user's own timezone
 * regardless.
 */
private fun parseUsaSurroundViewTimestamp(value: JsonElement?): Instant? {
    val raw = value.asStringOrNull() ?: return null
    if (raw.length != 14) return null
    return BluelinkDates.parseBasic14Utc(raw)
}

/** USA nests coordinates as `gpsDetail.coord.{lat,lon}`. */
private fun parseUsaSurroundViewLocation(gpsDetail: JsonObject?): VehicleStatus.Location? {
    val coord = gpsDetail?.get("coord") as? JsonObject ?: return null
    val latitude = coord["lat"].asDoubleOrNull() ?: return null
    val longitude = coord["lon"].asDoubleOrNull() ?: return null

    val location = VehicleStatus.Location(latitude, longitude)
    return if (location.hasCoordinates) location else null
}

private fun parseUsaSurroundViewDoors(doors: JsonObject?): VehicleStatus.DoorStatus? {
    if (doors == null) return null

    fun isOpen(key: String): Boolean = parseUsaSurroundViewFlag(doors[key]) ?: false

    return VehicleStatus.DoorStatus(
        frontLeft = isOpen("frontLeft"),
        frontRight = isOpen("frontRight"),
        backLeft = isOpen("backLeft"),
        backRight = isOpen("backRight"),
    )
}

/**
 * Reads an open/closed flag that may arrive as a JSON boolean, 0/1, or a
 * string. Null only when the key is absent, so "closed" and "not reported"
 * stay distinguishable.
 */
private fun parseUsaSurroundViewFlag(value: JsonElement?): Boolean? {
    if (value == null) return null
    value.asBooleanOrNull()?.let { return it }
    value.asIntOrNull()?.let { return it != 0 }
    val string = value.asStringOrNull() ?: return null
    return listOf("true", "1", "y", "yes", "open").contains(string.lowercase())
}
