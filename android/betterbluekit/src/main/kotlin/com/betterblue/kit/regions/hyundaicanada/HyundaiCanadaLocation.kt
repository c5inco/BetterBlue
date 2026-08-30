package com.betterblue.kit.regions.hyundaicanada

// Hyundai Canada vehicle location. Mirrors HyundaiCanada+Location.swift.
//
// Split out of the client for size, but the code is one idea: this region has
// changed which endpoint/header pairing answers with coordinates at least
// twice, and the answer differs between accounts, so location is a ladder of
// strategies with the winner remembered.

import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Vehicle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * One way of asking this API where the vehicle is.
 *
 * The accepted combination has moved twice now — BetterBlueKit#36 switched the
 * web-portal variant to `evc/fme` with native-app headers, and a later account
 * found `evc/fme` timing out on every request while `fndmcr` answered, but
 * only to the native-app identity (`from: CWP` drew errorCode 6459 in the same
 * second `from: SPA` returned coordinates). Both reports are first-hand and
 * neither generalizes, so try each in turn instead of betting the region on
 * one.
 */
internal enum class LocationStrategy(
    val path: String,
) {
    /**
     * `fndmcr` with the native-app identity, whichever variant the account
     * logs in with. Most recently verified.
     */
    FIND_MY_CAR_NATIVE("fndmcr"),

    /** `fndmcr` with this account's own login identity. */
    FIND_MY_CAR_ACCOUNT("fndmcr"),

    /**
     * `evc/fme` with the native-app identity — the pairing a Canadian owner
     * verified in BetterBlueKit#36. Kept last rather than deleted: it was
     * right for at least one real account, and nothing shows it is dead
     * everywhere.
     */
    FIND_MY_ELECTRIC_NATIVE("evc/fme"),
}

/**
 * Fetches the vehicle's location, remembering which strategy worked.
 *
 * [injectLocationCoordinates] runs on *every* status fetch, so without
 * memoizing the winner an account served by the second or third strategy
 * would re-pay every failed request on every refresh — against an API whose
 * WAF has rate-limited this project before.
 */
internal suspend fun HyundaiCanadaClient.fetchLocationData(
    vehicle: Vehicle,
    authToken: AuthToken,
    pAuth: String,
): ByteArray {
    var firstError: Exception? = null

    for (strategy in orderedLocationStrategies()) {
        try {
            val data = sendLocationRequest(strategy, vehicle, authToken, pAuth)
            if (locationStrategy != strategy) {
                BBLogger.info(BBLogCategory.API, "HyundaiCanada: location strategy $strategy works; remembering it")
                locationStrategy = strategy
            }
            return data
        } catch (error: Exception) {
            if (firstError == null) firstError = error
            BBLogger.debug(BBLogCategory.API, "HyundaiCanada: location strategy $strategy failed: $error")
        }
    }

    // Nothing worked. Forget any stale winner and stamp the sweep so the next
    // status refresh retries one strategy rather than all.
    locationStrategy = null
    lastLocationSweep = Instant.now()

    // Surface the FIRST failure: later strategies are progressively less
    // likely to fit this account, so their errors are usually less informative
    // than the preferred combination's.
    throw firstError ?: ApiException.logError("No Canada location strategy succeeded", apiName = apiName)
}

/** Strategies to try, most-likely first. */
private fun HyundaiCanadaClient.orderedLocationStrategies(): List<LocationStrategy> {
    locationStrategy?.let { remembered ->
        return listOf(remembered) + LocationStrategy.entries.filter { it != remembered }
    }
    val sweep = lastLocationSweep
    if (sweep != null && Duration.between(sweep, Instant.now()) < HyundaiCanadaClient.LOCATION_SWEEP_INTERVAL) {
        // A recent sweep found nothing. Retry just the leading strategy until
        // the backoff expires; a vehicle that simply has location disabled
        // shouldn't cost three calls a refresh.
        return LocationStrategy.entries.take(1)
    }
    return LocationStrategy.entries.toList()
}

private suspend fun HyundaiCanadaClient.sendLocationRequest(
    strategy: LocationStrategy,
    vehicle: Vehicle,
    authToken: AuthToken,
    pAuth: String,
): ByteArray {
    val requestHeaders =
        when (strategy) {
            LocationStrategy.FIND_MY_CAR_NATIVE, LocationStrategy.FIND_MY_ELECTRIC_NATIVE -> {
                locationHeaders(authToken, vehicleId = vehicle.regId, pAuth = pAuth)
            }

            LocationStrategy.FIND_MY_CAR_ACCOUNT -> {
                authorizedHeaders(authToken, vehicleId = vehicle.regId, pAuth = pAuth)
            }
        }

    val (_, result) =
        performJsonRequest(
            url = "$apiBaseUrl/${strategy.path}",
            method = HttpMethod.POST,
            headers = requestHeaders,
            body = buildJsonObject { put("pin", pin) },
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )

    // Validated here so an API-level refusal (HTTP 200 with `responseCode: 1`)
    // still moves on to the next strategy.
    parseCanadaResponse(result.body, context = "location")
    return result.body
}

/**
 * Fetches the vehicle's location and rewrites `result.status.coord` /
 * `result.status.vehicleLocation.coord` in the status payload so the regular
 * status parser picks the coordinates up. kotlinx's JsonObject is immutable,
 * so the tree is rebuilt map-by-map. Never throws: on any failure the
 * original payload is returned unchanged.
 */
internal suspend fun HyundaiCanadaClient.injectLocationCoordinates(
    data: ByteArray,
    vehicle: Vehicle,
    authToken: AuthToken,
): ByteArray =
    try {
        val pAuth = fetchCommandAuthCode(authToken)
        val locationData = fetchLocationData(vehicle, authToken, pAuth)
        val location = parseCanadaLocationResponse(locationData)

        val root =
            com.betterblue.kit.ApiClientBase
                .parseJsonObject(data)
        if (root.isEmpty()) {
            data
        } else {
            val coord =
                buildJsonObject {
                    put("lat", location.latitude)
                    put("lon", location.longitude)
                }
            val result = root["result"] as? JsonObject ?: JsonObject(emptyMap())
            val status =
                result["status"] as? JsonObject
                    ?: result["vehicleStatus"] as? JsonObject
                    ?: JsonObject(emptyMap())
            val newStatus =
                JsonObject(
                    status +
                        mapOf(
                            "coord" to coord,
                            "vehicleLocation" to buildJsonObject { put("coord", coord) },
                        ),
                )
            val newResult = JsonObject(result + mapOf("status" to newStatus))
            val newRoot = JsonObject(root + mapOf("result" to newResult))
            newRoot.toString().toByteArray(Charsets.UTF_8)
        }
    } catch (error: Exception) {
        BBLogger.debug(BBLogCategory.API, "HyundaiCanada: failed injecting location: $error")
        data
    }
