package com.betterblue.kit.regions.hyundaiusa

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

class HyundaiUsaClient(config: ApiClientConfig) : ApiClientBase(config), ApiClient {

    internal val clientId = "m66129Bb-em93-SPAHYN-bZ91-am4540zp19920"
    internal val clientSecret = "v558o935-6nne-423i-baa8"

    internal val baseUrl: String get() = region.apiBaseUrl(Brand.HYUNDAI)
    internal val apiHost: String get() = "api.telematics.hyundaiusa.com"

    override val apiName: String get() = "HyundaiUSA"

    // Headers

    internal fun headers(): Map<String, String> = mapOf(
        "client_id" to clientId,
        "clientSecret" to clientSecret,
        "Host" to apiHost,
        "User-Agent" to "okhttp/3.12.0",
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Encoding" to "gzip, deflate, br",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "Keep-Alive",
    )

    internal fun authorizedHeaders(
        authToken: AuthToken,
        vehicle: Vehicle? = null,
        refresh: Boolean = false,
    ): Map<String, String> = buildMap {
        putAll(headers())
        put("accessToken", authToken.accessToken)
        put("language", "0")
        put("to", "ISS")
        put("encryptFlag", "false")
        put("from", "SPA")
        put("offset", "-5")
        put("brandIndicator", "H")
        put("origin", "https://$apiHost")
        put("referer", "https://$apiHost/login")
        put("username", username)
        put("blueLinkServicePin", pin)
        // "refresh: true" forces the backend to poll the vehicle's modem for
        // current state instead of returning the last cached snapshot. This is
        // what MyHyundai uses for pull-to-refresh.
        put("refresh", if (refresh) "true" else "false")

        if (vehicle != null) {
            put("gen", vehicle.generation.toString())
            put("registrationId", vehicle.regId)
            put("vin", vehicle.vin)
            put("APPCLOUD-VIN", vehicle.vin)
        }

        put("payloadGenerated", BluelinkDates.formatBasic14(Instant.now(), ZoneId.systemDefault()))
        put("includeNonConnectedVehicles", "Y")
    }

    // ApiClient implementation

    override suspend fun login(): AuthToken {
        BBLogger.info(BBLogCategory.AUTH, "HyundaiUSA: Attempting login for $username")

        val (_, result) = performJsonRequest(
            url = "$baseUrl/v2/ac/oauth/token",
            method = HttpMethod.POST,
            headers = headers(),
            body = buildJsonObject {
                put("username", username)
                put("password", password)
            },
            requestType = HttpRequestType.LOGIN,
        )

        return parseLoginResponse(result.body)
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        val result = performRequest(
            url = "$baseUrl/ac/v2/enrollment/details/$username",
            method = HttpMethod.GET,
            headers = authorizedHeaders(authToken),
            requestType = HttpRequestType.FETCH_VEHICLES,
        )

        return parseVehiclesResponse(result.body)
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        val result = performRequest(
            url = "$baseUrl/ac/v2/rcs/rvs/vehicleStatus",
            method = HttpMethod.GET,
            headers = authorizedHeaders(authToken, vehicle, refresh = !cached),
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )

        return parseVehicleStatusResponse(result.body, vehicle)
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val result = performRequest(
            url = commandUrl(command, vehicle),
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicle),
            body = commandBody(command, vehicle).toString().toByteArray(Charsets.UTF_8),
            requestType = HttpRequestType.SEND_COMMAND,
            vin = vehicle.vin,
        )

        parseCommandResponse(result.body)
    }

    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> =
        setOf(OptionalApiFeature.EV_TRIP_SUMMARY, OptionalApiFeature.SURROUND_VIEW)

    override suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken): List<EVTripSummary>? {
        val tripHeaders = authorizedHeaders(authToken, vehicle) +
            mapOf("userId" to username, "access_token" to authToken.accessToken)

        val result = performRequest(
            url = "$baseUrl/ac/v2/ts/alerts/maintenance/evTripDetails",
            method = HttpMethod.GET,
            headers = tripHeaders,
            requestType = HttpRequestType.FETCH_EV_TRIP_SUMMARY,
            vin = vehicle.vin,
        )

        return parseEvTripSummaryResponse(result.body)
    }

    override suspend fun requestSurroundViewCapture(vehicle: Vehicle, authToken: AuthToken) =
        requestSurroundViewCaptureImpl(vehicle, authToken)

    override suspend fun fetchSurroundViewCaptures(vehicle: Vehicle, authToken: AuthToken) =
        fetchSurroundViewCapturesImpl(vehicle, authToken)

    override suspend fun registerDevice(): String? = super<ApiClientBase>.registerDevice()
}
