package com.betterblue.kit.regions.hyundaieurope

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
import com.betterblue.kit.model.EVTripInfo
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.util.BluelinkDates
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Hyundai Europe (CCSP / Bluelink EU) client.
 * Based on https://github.com/andyfase/egmp-bluelink-scriptable and
 * hyundai_kia_connect_api's ApiImplType1.
 */
class HyundaiEuropeClient(
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

    internal val baseUrl: String get() = region.apiBaseUrl(Brand.HYUNDAI)
    internal val authBaseUrl: String get() = "https://idpconnect-eu.hyundai.com"
    internal val apiHost: String get() = baseUrl.removePrefix("https://")

    override val apiName: String get() = "HyundaiEurope"

    // Login (password or refresh-token flow)

    override suspend fun login(): AuthToken {
        val refreshToken = config.refreshToken
        if (!refreshToken.isNullOrEmpty()) {
            BBLogger.info(BBLogCategory.AUTH, "HyundaiEurope: Starting login flow (refresh token)")
            val token =
                try {
                    getAccessTokenFromRefreshToken()
                } catch (e: ApiException) {
                    // A dead/rotated refresh token isn't fatal: clear it and
                    // recurse into the username/password flow (once — the
                    // recursion takes the else-branch because the stored token
                    // is now empty).
                    if (e.errorType == ApiErrorType.INVALID_CREDENTIALS && password.isNotEmpty()) {
                        config = config.copy(refreshToken = "")
                        return login()
                    }
                    throw e
                }
            BBLogger.info(BBLogCategory.AUTH, "HyundaiEurope: Login completed successfully")
            return token
        }

        BBLogger.info(
            BBLogCategory.AUTH,
            "HyundaiEurope: refresh token is nil or empty, using username/password login",
        )
        val code = signin()
        val token = exchangeForToken(code)
        config = config.copy(refreshToken = token.refreshToken)
        BBLogger.info(BBLogCategory.AUTH, "HyundaiEurope: Login completed successfully")
        return token
    }

    /** Exchange the signin redirect's `code` for access + refresh tokens. */
    private suspend fun exchangeForToken(code: String): AuthToken {
        val result =
            performRequest(
                url = "$authBaseUrl/auth/api/v2/user/oauth2/token",
                method = HttpMethod.POST,
                headers = loginHeaders(),
                body =
                    buildJsonObject {
                        put("grant_type", "authorization_code")
                        put("code", code)
                        put("redirect_uri", "$baseUrl/api/v1/user/oauth2/token")
                        put("client_id", CLIENT_ID)
                        put("client_secret", CLIENT_SECRET)
                    }.toString().toByteArray(Charsets.UTF_8),
                requestType = HttpRequestType.LOGIN,
            )
        return parseAuthToken(result.body, isRefresh = true)
    }

    /**
     * JSON-body signin. The IDP answers with a redirect chain; the OAuth
     * `code` (and the echoed `state`) ride on the FINAL URL's query — which is
     * why this reads `HttpResult.finalUrl` and skips response validation
     * (mirroring the Swift client's raw URLSession call here).
     */
    private suspend fun signin(): String {
        val state = UUID.randomUUID().toString().uppercase()
        val result =
            performRequest(
                url = "$authBaseUrl/auth/account/signin",
                method = HttpMethod.POST,
                headers = loginHeaders(),
                body =
                    buildJsonObject {
                        put("client_id", CLIENT_ID)
                        put("encryptedPassword", "false")
                        put("username", username)
                        put("password", password)
                        put("redirect_uri", "$baseUrl/api/v1/user/oauth2/token")
                        put("state", state)
                        put("remember_me", "false")
                    }.toString().toByteArray(Charsets.UTF_8),
                requestType = HttpRequestType.LOGIN,
                validateResponse = false,
            )

        val finalUrl = result.finalUrl.toHttpUrlOrNull() ?: return ""
        // State validation — no CSRF possible anymore.
        if (finalUrl.queryParameter("state") != state) return ""
        return finalUrl.queryParameter("code")?.takeIf { it.isNotEmpty() } ?: ""
    }

    /** Refresh-grant: trade the stored refresh_token for a fresh access token. */
    private suspend fun getAccessTokenFromRefreshToken(): AuthToken {
        // Swift sends this via a raw URLSession call with only a Content-Type
        // header and no status validation — parseAuthToken's
        // invalidCredentials is what drives the fall-back-to-password flow.
        val result =
            performRequest(
                url = "$authBaseUrl/auth/api/v2/user/oauth2/token",
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json"),
                body =
                    buildJsonObject {
                        put("grant_type", "refresh_token")
                        put("refresh_token", config.refreshToken ?: "")
                        put("client_id", CLIENT_ID)
                        put("client_secret", CLIENT_SECRET)
                    }.toString().toByteArray(Charsets.UTF_8),
                requestType = HttpRequestType.LOGIN,
                validateResponse = false,
            )
        return parseAuthToken(result.body, isRefresh = false)
    }

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
                        put("pushType", "GCM")
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

    // Command (control) token

    /**
     * PIN → control token exchange, refreshed 300s before expiry. ONLY used
     * by CCS2 vehicles — legacy vehicles command with the plain access token
     * and never touch the PIN endpoint (calling it for them is what produced
     * the old "Failed to get command token" error).
     */
    internal suspend fun setCommandToken(authToken: AuthToken) {
        if (Instant.now().isBefore(commandTokenExpiration.minusSeconds(300)) && commandToken.isNotEmpty()) {
            return
        }

        // Routed through performJsonRequest so the PIN/control-token request
        // is captured in the HTTP logs and its status validated.
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
            )

        val token = json["controlToken"].asStringOrNull()
        val expires = json["expiresTime"].asIntOrNull()
        if (token == null || expires == null) {
            throw ApiException(
                "PIN verification failed — check that the account PIN is correct.",
                apiName = apiName,
            )
        }
        commandToken = token
        commandTokenExpiration = Instant.now().plusSeconds(expires.toLong())
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

    // Vehicle status

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        val ccs2 = vehicle.marketOptions.ccs2Supported

        // The `/latest` endpoint is a passive cache — it won't reflect a
        // just-sent command until the car next reports in on its own. A
        // manual / post-command refresh (cached == false) therefore has to
        // wake the car first. Mirrors hyundai_kia_connect_api's
        // force_refresh_vehicle_state for CCS2.
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
        // else — including km-based continental EU (e.g. NL) — is "L".
        // Mirrors hyundai_kia_connect_api's `_get_drv_seat_loc`.
        val drvSeatLoc = if (vehicle.odometer.units == Distance.Units.MILES) "R" else "L"
        val (path, body) = commandPathAndBody(command, ccs2 = ccs2, drvSeatLoc = drvSeatLoc)
        val url = "$baseUrl/api/${if (ccs2) "v2" else "v1"}/spa/vehicles/${vehicle.regId}/$path"

        // CCS2 cars authenticate commands with the PIN-derived control token;
        // legacy cars use the normal access token and have no PIN step.
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

    // EV trip history

    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> =
        setOf(OptionalApiFeature.EV_TRIP_SUMMARY, OptionalApiFeature.EV_TRIP_INFO)

    override suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken): List<EVTripSummary>? {
        val ccs2 = vehicle.marketOptions.ccs2Supported
        val result =
            performRequest(
                url = "$baseUrl/api/v1/spa/vehicles/${vehicle.regId}/drvhistory",
                method = HttpMethod.POST,
                headers = authorizedHeaders(authToken, ccs2 = ccs2),
                body = buildJsonObject { put("periodTarget", 0) }.toString().toByteArray(Charsets.UTF_8),
                requestType = HttpRequestType.FETCH_EV_TRIP_SUMMARY,
                vin = vehicle.vin,
            )
        return parseEvTripSummaryResponse(result.body, vehicle)
    }

    override suspend fun fetchEvTripInfo(
        vehicle: Vehicle,
        authToken: AuthToken,
        date: LocalDate,
    ): List<EVTripInfo>? {
        val ccs2 = vehicle.marketOptions.ccs2Supported
        val result =
            performRequest(
                url = "$baseUrl/api/v1/spa/vehicles/${vehicle.regId}/tripinfo",
                method = HttpMethod.POST,
                headers = authorizedHeaders(authToken, ccs2 = ccs2),
                body =
                    buildJsonObject {
                        put("tripPeriodType", 1)
                        put("setTripDay", BluelinkDates.formatDay(date))
                    }.toString().toByteArray(Charsets.UTF_8),
                requestType = HttpRequestType.FETCH_EV_TRIP_INFO,
                vin = vehicle.vin,
            )
        return parseIndividualTripsResponse(result.body)
    }

    companion object {
        internal const val CLIENT_ID = "6d477c38-3ca4-4cf3-9557-2a1929a94654"
        internal const val CLIENT_SECRET = "KUy49XxPzLpLuoK0xhBC77W6VXhmtQR9iQhmIFjjoY4IpxsV"
        internal const val APP_ID = "014d2225-8495-4735-812d-2616334fd15d"
        internal const val AUTH_CFB =
            "RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ="
    }
}
