package com.betterblue.kit.regions.kiaeurope

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.json.asIntOrNull
import com.betterblue.kit.json.asStringOrNull
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Kia Europe (CCSP / Kia Connect EU) client.
 * Based on KiaUvoApiEU from hyundai_kia_connect_api (PR #1123, v4.12.0),
 * which integrates a headless IDPConnect login flow and drops curl_cffi by
 * appending `_CCS_APP_AOS` to the User-Agent.
 */
class KiaEuropeClient(
    config: ApiClientConfig,
) : ApiClientBase(config),
    ApiClient {
    internal var commandToken: String = ""
    internal var commandTokenExpiration: Instant = Instant.now()

    /**
     * How long to wait after waking a CCS2 car before reading `/latest`.
     * ~20s matches the live-measured report latency in
     * hyundai_kia_connect_api. Injectable so tests don't sleep.
     */
    internal var forceRefreshDelayMillis: Long = 20_000L

    internal val baseUrl: String get() = region.apiBaseUrl(Brand.KIA)
    internal val authBaseUrl: String get() = "https://idpconnect-eu.kia.com"
    internal val apiHost: String get() = baseUrl.removePrefix("https://")
    internal val oauthRedirectUri: String get() = "$baseUrl/api/v1/user/oauth2/redirect"

    override val apiName: String get() = "KiaEurope"

    // Login

    override suspend fun login(): AuthToken {
        val refreshToken = config.refreshToken
        if (!refreshToken.isNullOrEmpty()) {
            BBLogger.info(BBLogCategory.AUTH, "KiaEurope: Starting login flow (refresh token)")
            val token =
                try {
                    getAccessTokenFromRefreshToken()
                } catch (e: ApiException) {
                    // Dead refresh token → clear it and recurse into the
                    // username/password flow (the recursion takes the else-branch
                    // because the stored token is now empty).
                    if (e.errorType == ApiErrorType.INVALID_CREDENTIALS && password.isNotEmpty()) {
                        config = config.copy(refreshToken = "")
                        return login()
                    }
                    throw e
                }
            BBLogger.info(BBLogCategory.AUTH, "KiaEurope: Login completed successfully")
            return token
        }

        BBLogger.info(
            BBLogCategory.AUTH,
            "KiaEurope: refresh token is nil or empty, using username/password login",
        )
        val code = signin()
        val token = exchangeForToken(code)
        config = config.copy(refreshToken = token.refreshToken)
        BBLogger.info(BBLogCategory.AUTH, "KiaEurope: Login completed successfully")
        return token
    }

    // Auth flow (signin / token exchange / refresh) lives in
    // KiaEuropeAuth.kt, mirroring the Swift +Auth extension file.

    // Device registration

    override suspend fun registerDevice(): String? {
        val stamp = generateStamp()
        val (json, _) =
            performJsonRequest(
                url = "$baseUrl/api/v1/spa/notifications/register",
                method = HttpMethod.POST,
                headers =
                    mapOf(
                        "ccsp-service-id" to CLIENT_ID,
                        "ccsp-application-id" to APP_ID,
                        "Stamp" to stamp,
                        "Content-Type" to "application/json;charset=UTF-8",
                        "Host" to apiHost,
                        "User-Agent" to "okhttp/3.14.9",
                    ),
                body =
                    buildJsonObject {
                        put("pushRegId", stamp)
                        put("pushType", PUSH_TYPE)
                        put("uuid", UUID.randomUUID().toString().uppercase())
                    },
                requestType = HttpRequestType.LOGIN,
                validateResponse = false,
            )

        val deviceId =
            (json["resMsg"] as? JsonObject)?.get("deviceId").asStringOrNull()
                ?: throw ApiException("Failed to get device id", apiName = apiName)
        config = config.copy(deviceId = deviceId)
        return deviceId
    }

    // Vehicles

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        val result =
            performRequest(
                url = "$baseUrl/api/v1/spa/vehicles",
                method = HttpMethod.GET,
                headers = authorizedHeaders(authToken),
                requestType = HttpRequestType.FETCH_VEHICLES,
            )
        return parseVehiclesResponse(result.body)
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        val ccs2 = vehicle.marketOptions.ccs2Supported

        // A manual / post-command refresh (cached == false) must wake the
        // car — the `/latest` snapshot is a passive cache and won't reflect a
        // just-sent command until the vehicle reports in. Mirrors
        // hyundai_kia_connect_api's force_refresh_vehicle_state for CCS2.
        if (!cached && ccs2) {
            forceRefreshCcs2(vehicle, authToken)
        }

        val endpoint = if (ccs2) "/ccs2/carstatus/latest" else "/status/latest"

        val statusResult =
            performRequest(
                url = "$baseUrl/api/v1/spa/vehicles/${vehicle.regId}$endpoint",
                method = HttpMethod.GET,
                headers = authorizedHeaders(authToken, ccs2 = ccs2),
                requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
                vin = vehicle.vin,
            )

        val parkResult =
            performRequest(
                url = "$baseUrl/api/v1/spa/vehicles/${vehicle.regId}/location/park",
                method = HttpMethod.GET,
                headers = authorizedHeaders(authToken, ccs2 = ccs2),
                requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
                vin = vehicle.vin,
            )

        return parseVehicleStatusResponse(statusResult.body, parkResult.body, vehicle)
    }

    // Command (control) token — PIN exchange

    /**
     * PIN → control token, refreshed ~5 min before expiry. ONLY CCS2 vehicles
     * use it; legacy vehicles command with the plain access token and never
     * touch the PIN endpoint.
     */
    internal suspend fun setCommandToken(authToken: AuthToken) {
        if (Instant.now().isBefore(commandTokenExpiration.minusSeconds(300)) && commandToken.isNotEmpty()) {
            return
        }

        val (json, _) =
            performJsonRequest(
                url = "$baseUrl/api/v1/user/pin?token=",
                method = HttpMethod.PUT,
                headers = authorizedHeaders(authToken),
                body =
                    buildJsonObject {
                        put("deviceId", config.deviceId ?: "")
                        put("pin", pin)
                    },
                requestType = HttpRequestType.SEND_COMMAND,
                validateResponse = false,
            )

        val token = json["controlToken"].asStringOrNull()
        val expires = json["expiresTime"].asIntOrNull()
        if (token == null || expires == null) {
            throw ApiException("Failed to get command token (check PIN)", apiName = apiName)
        }
        commandToken = token
        commandTokenExpiration = Instant.now().plusSeconds(expires.toLong())
    }

    /**
     * Wake a CCS2 vehicle so the subsequent `/latest` read returns current
     * state. `GET /ccs2/carstatus` (no `/latest`) triggers the wake and
     * returns an async ack envelope — its body is discarded, but errors
     * propagate so we never fall through to applying a stale snapshot. The
     * car reports back asynchronously; give it ~20s before reading `/latest`.
     */
    internal suspend fun forceRefreshCcs2(vehicle: Vehicle, authToken: AuthToken) {
        performRequest(
            url = "$baseUrl/api/v1/spa/vehicles/${vehicle.regId}/ccs2/carstatus",
            method = HttpMethod.GET,
            headers = authorizedHeaders(authToken, ccs2 = true),
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )
        delay(forceRefreshDelayMillis)
    }

    // Commands

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val ccs2 = vehicle.marketOptions.ccs2Supported
        // "R" only for the mile-based RHD markets (UK/Ireland); everything
        // else — including km-based continental EU — is "L". Mirrors
        // hyundai_kia_connect_api's `_get_drv_seat_loc`.
        val drvSeatLoc = if (vehicle.odometer.units == Distance.Units.MILES) "R" else "L"
        val (path, body) = commandPathAndBody(command, ccs2 = ccs2, drvSeatLoc = drvSeatLoc)
        val url = "$baseUrl/api/${if (ccs2) "v2" else "v1"}/spa/vehicles/${vehicle.regId}/$path"

        val headers =
            if (ccs2) {
                setCommandToken(authToken)
                commandHeaders(authToken, ccs2 = true)
            } else {
                authorizedHeaders(authToken, ccs2 = false)
            }

        performJsonRequest(
            url = url,
            method = HttpMethod.POST,
            headers = headers,
            body = body,
            requestType = HttpRequestType.SEND_COMMAND,
            vin = vehicle.vin,
        )
    }

    /** Kia EU has no optional features today (no EV trip history endpoints). */
    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> = emptySet()

    override suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken): List<EVTripSummary>? = null

    companion object {
        internal const val CLIENT_ID = "fdc85c00-0a2f-4c64-bcb4-2cfb1500730a"
        internal const val CLIENT_SECRET = "secret"
        internal const val APP_ID = "a2b8469b-30a3-4361-8e13-6fceea8fbe74"

        /** base64("<clientId>:<clientSecret>") — kept for parity with the Swift client. */
        internal const val BASIC_AUTHORIZATION =
            "Basic ZmRjODVjMDAtMGEyZi00YzY0LWJjYjQtMmNmYjE1MDA3MzBhOnNlY3JldA=="

        internal const val AUTH_CFB =
            "wLTVxwidmH8CfJYBWSnHD6E0huk0ozdiuygB4hLkM5XCgzAL1Dk5sE36d/bx5PFMbZs="
        internal const val PUSH_TYPE = "APNS"

        /**
         * The `_CCS_APP_AOS` suffix is LOAD-BEARING: it's what gets past
         * Cloudflare on `idpconnect-eu.kia.com` — without it the authorize
         * endpoint returns 400. Discovered in hyundai_kia_connect_api
         * PR #1123. Used on all four auth-flow requests; data-plane requests
         * use okhttp/3.14.9 instead.
         */
        internal const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 4.1.1; Galaxy Nexus Build/JRO03C) " +
                "AppleWebKit/535.19 (KHTML, like Gecko) " +
                "Chrome/18.0.1025.166 Mobile Safari/535.19_CCS_APP_AOS"
    }
}
