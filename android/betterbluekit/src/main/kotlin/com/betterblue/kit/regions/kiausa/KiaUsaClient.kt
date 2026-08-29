package com.betterblue.kit.regions.kiausa

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.MfaMethod
import com.betterblue.kit.MfaVerification
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
import com.betterblue.kit.util.Uuid5
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The Kia US `date` header is RFC-1123 with a zero-padded day
 * (`Tue, 03 Jun 2008 11:05:30 GMT`) — java.time's RFC_1123_DATE_TIME drops
 * the padding, so use an explicit pattern.
 */
private val RFC_1123_GMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneId.of("GMT"))

class KiaUsaClient(config: ApiClientConfig) : ApiClientBase(config), ApiClient {

    // Device ID is a simple uppercase UUID (matches Python: str(uuid.uuid4()).upper()).
    // Use the persisted device ID from configuration if available, so the server
    // recognizes the same device across re-authentications and the rmToken
    // remains valid.
    internal val deviceId: String = config.deviceId ?: UUID.randomUUID().toString().uppercase()

    // Client UUID is a UUID5 (SHA-1) hash of deviceId in the DNS namespace,
    // lowercased (java.util.UUID.toString() is already lowercase).
    internal val clientUuid: String get() = Uuid5.of(Uuid5.NAMESPACE_DNS, deviceId).toString()

    internal val baseUrl: String get() = region.apiBaseUrl(Brand.KIA)
    internal val apiUrl: String get() = "$baseUrl/apigw/v1/"

    override val apiName: String get() = "KiaUSA"

    // Headers

    internal fun headers(): Map<String, String> {
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 3600
        val hostName = baseUrl.removePrefix("https://")

        return mapOf(
            "content-type" to "application/json;charset=utf-8",
            "accept" to "application/json",
            "accept-encoding" to "gzip, deflate, br",
            "accept-language" to "en-US,en;q=0.9",
            "accept-charset" to "utf-8",
            "apptype" to "L",
            "appversion" to "7.22.0",
            "clientid" to "SPACL716-APL",
            "clientuuid" to clientUuid,
            "from" to "SPA",
            "host" to hostName,
            "language" to "0",
            "offset" to offset.toString(),
            "ostype" to "iOS",
            "osversion" to "15.8.5",
            "phonebrand" to "iPhone",
            "secretkey" to "sydnat-9kykci-Kuhtep-h5nK",
            "to" to "APIGW",
            "tokentype" to "A",
            "user-agent" to "KIAPrimo_iOS/37 CFNetwork/1335.0.3.4 Darwin/21.6.0",
            "date" to RFC_1123_GMT.format(Instant.now()),
            "deviceid" to deviceId,
        )
    }

    internal fun authorizedHeaders(authToken: AuthToken, vehicleKey: String? = null): Map<String, String> =
        buildMap {
            putAll(headers())
            // For Kia US the session id IS the access token.
            put("sid", authToken.accessToken)
            if (vehicleKey != null) put("vinkey", vehicleKey)
        }

    // ApiClient implementation

    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> = setOf(OptionalApiFeature.MFA)

    override suspend fun login(): AuthToken = loginWithMfa(sid = null, rmToken = config.rememberMeToken)

    internal suspend fun loginWithMfa(sid: String?, rmToken: String?): AuthToken {
        BBLogger.info(BBLogCategory.AUTH, "KiaUSA: Attempting login for $username")

        val loginHeaders = buildMap {
            putAll(headers())
            if (rmToken != null) put("rmtoken", rmToken)
            if (sid != null) put("sid", sid)
        }

        val (_, result) = performJsonRequest(
            url = "${apiUrl}prof/authUser",
            method = HttpMethod.POST,
            headers = loginHeaders,
            body = buildJsonObject {
                put("deviceKey", deviceId)
                put("deviceType", 2)
                put("tncFlag", 1)
                put(
                    "userCredential",
                    buildJsonObject {
                        put("userId", username)
                        put("password", password)
                    },
                )
            },
            requestType = HttpRequestType.LOGIN,
        )

        // The session tokens come back in the RESPONSE HEADERS, not the body.
        return parseLoginResponse(result.body, result.headers)
    }

    override suspend fun sendMfaCode(xid: String, otpKey: String, method: MfaMethod) {
        BBLogger.info(BBLogCategory.MFA, "KiaUSA: Sending OTP via $method")

        val otpHeaders = buildMap {
            putAll(headers())
            put("otpkey", otpKey)
            put("notifytype", if (method == MfaMethod.EMAIL) "EMAIL" else "SMS")
            put("xid", xid)
        }

        val (_, result) = performJsonRequest(
            url = "${apiUrl}cmm/sendOTP",
            method = HttpMethod.POST,
            headers = otpHeaders,
            body = buildJsonObject {},
            requestType = HttpRequestType.SEND_MFA,
        )

        checkForKiaErrors(result.body)
        BBLogger.info(BBLogCategory.MFA, "KiaUSA: OTP sent successfully")
    }

    override suspend fun verifyMfaCode(xid: String, otpKey: String, code: String): MfaVerification {
        BBLogger.info(BBLogCategory.MFA, "KiaUSA: Verifying OTP")

        val verifyHeaders = buildMap {
            putAll(headers())
            put("otpkey", otpKey)
            put("xid", xid)
        }

        val (_, result) = performJsonRequest(
            url = "${apiUrl}cmm/verifyOTP",
            method = HttpMethod.POST,
            headers = verifyHeaders,
            body = buildJsonObject { put("otp", code) },
            requestType = HttpRequestType.VERIFY_MFA,
        )

        checkForKiaErrors(result.body)

        // The Swift client probed rmToken/rmtoken/RmToken and sid/Sid/SID by
        // hand; OkHttp's Headers are case-insensitive so one lookup covers all.
        val rmToken = result.header("rmToken")
        val sessionId = result.header("sid")
        if (rmToken == null || sessionId == null) {
            BBLogger.warning(BBLogCategory.MFA, "KiaUSA verifyOTP response headers: ${result.headers}")
            throw ApiException.logError("Verify OTP response missing tokens", apiName = apiName)
        }

        BBLogger.info(BBLogCategory.MFA, "KiaUSA OTP verified - rmToken: ${rmToken.take(10)}..., sid: $sessionId")
        return MfaVerification(rememberMeToken = rmToken, sid = sessionId)
    }

    override suspend fun completeMfaLogin(sid: String, rmToken: String): AuthToken {
        BBLogger.info(BBLogCategory.AUTH, "KiaUSA: Completing MFA login")
        return loginWithMfa(sid = sid, rmToken = rmToken)
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        val result = performRequest(
            url = "${apiUrl}ownr/gvl",
            method = HttpMethod.GET,
            headers = authorizedHeaders(authToken),
            requestType = HttpRequestType.FETCH_VEHICLES,
        )

        return parseVehiclesResponse(result.body)
    }

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        BBLogger.debug(
            BBLogCategory.API,
            "KiaUSA: Fetching status for VIN: ${vehicle.vin}, " +
                "vehicleKey: ${vehicle.vehicleKey ?: "nil"}, cached: $cached",
        )

        // When the caller asks for a real-time reading, hit `rems/rvs` first
        // to make Kia's backend poll the vehicle modem (same endpoint the
        // Kia Access app's pull-to-refresh uses). It's an async server call
        // that blocks until the vehicle responds (20–60 s typical). After
        // it returns we fall through to `cmm/gvi`, which now returns the
        // freshly-refreshed cached snapshot. If the real-time call fails
        // we log and continue — a stale snapshot beats surfacing an error.
        //
        // Cooldown: post-command status polling calls this every ~10–15 s,
        // and back-to-back rvs requests get throttled by Kia (returning
        // errors we'd swallow, i.e. stale snapshots anyway) while each call
        // can block for up to a minute. One successful modem poll per
        // window is enough — within it, `cmm/gvi` already returns the
        // freshly-refreshed snapshot.
        if (!cached) {
            val shouldPoll = refreshCooldownMutex.withLock {
                val last = lastRealTimeRefresh
                val elapsed = last?.let { Duration.between(it, Instant.now()).seconds }
                if (elapsed != null && elapsed < REAL_TIME_REFRESH_COOLDOWN_SECONDS) {
                    BBLogger.debug(
                        BBLogCategory.API,
                        "KiaUSA: skipping rems/rvs (last real-time refresh ${elapsed}s ago)",
                    )
                    false
                } else {
                    true
                }
            }
            if (shouldPoll && triggerRealTimeStatusRefresh(vehicle, authToken)) {
                // Stamp only successful modem polls — a failed/throttled one
                // should be retried on the next poll, not cooled down.
                refreshCooldownMutex.withLock { lastRealTimeRefresh = Instant.now() }
            }
        }

        // `cmm/gvi` only accepts `vehicleStatus: "1"`. Sending anything else
        // returns the server-side 9001 "Incorrect request payload format".
        val body = buildJsonObject {
            put(
                "vehicleConfigReq",
                buildJsonObject {
                    put("airTempRange", "0")
                    put("maintenance", "1")
                    put("seatHeatCoolOption", "0")
                    put("vehicle", "1")
                    put("vehicleFeature", "0")
                },
            )
            put(
                "vehicleInfoReq",
                buildJsonObject {
                    put("drivingActivty", "0")
                    put("dtc", "1")
                    put("enrollment", "1")
                    put("functionalCards", "0")
                    put("location", "1")
                    put("vehicleStatus", "1")
                    put("weather", "0")
                },
            )
            put("vinKey", buildJsonArray { add(vehicle.vehicleKey ?: "") })
        }

        val (_, result) = performJsonRequest(
            url = "${apiUrl}cmm/gvi",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicleKey = vehicle.vehicleKey),
            body = body,
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )

        return parseVehicleStatusResponse(result.body, vehicle)
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val method = commandMethod(command)

        val (_, result) = performJsonRequest(
            url = commandUrl(command),
            method = method,
            // The Kia stop commands (rems/stop, evc/cancel) are GETs with no body.
            headers = authorizedHeaders(authToken, vehicleKey = vehicle.vehicleKey),
            body = if (method == HttpMethod.GET) null else commandBody(command),
            requestType = HttpRequestType.SEND_COMMAND,
            vin = vehicle.vin,
        )

        checkForKiaErrors(result.body)
    }

    // Kia USA doesn't support EV trip details.
    override suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken): List<EVTripSummary>? = null

    override suspend fun registerDevice(): String? = super<ApiClientBase>.registerDevice()

    // Real-time status refresh

    /** When the last SUCCESSFUL `rems/rvs` modem poll completed. */
    private var lastRealTimeRefresh: Instant? = null
    private val refreshCooldownMutex = Mutex()

    /**
     * Asks Kia's backend to poll the vehicle's telematics modem for fresh
     * data. The response comes back once the modem replies — usually within
     * ~30 seconds. Returns whether the poll succeeded; errors are logged but
     * swallowed so the caller still gets a (possibly stale) snapshot from
     * the follow-up `cmm/gvi` call.
     */
    internal suspend fun triggerRealTimeStatusRefresh(vehicle: Vehicle, authToken: AuthToken): Boolean {
        BBLogger.info(BBLogCategory.API, "KiaUSA: Requesting real-time status refresh for VIN ${vehicle.vin}")

        try {
            val (_, result) = performJsonRequest(
                url = "${apiUrl}rems/rvs",
                method = HttpMethod.POST,
                headers = authorizedHeaders(authToken, vehicleKey = vehicle.vehicleKey),
                body = buildJsonObject { put("requestType", 0) },
                requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
                vin = vehicle.vin,
            )
            checkForKiaErrors(result.body)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Bubble auth failures up — the caller (BBAccount) knows how to
            // re-authenticate. Swallowing would mask a session that really
            // needs refreshing.
            if (e.errorType == ApiErrorType.INVALID_CREDENTIALS) throw e
            // Swallow everything else: the user-visible UX is "refresh took
            // a while and maybe the data is 30 s stale", not a failure.
            BBLogger.warning(
                BBLogCategory.API,
                "KiaUSA: rems/rvs real-time refresh failed, falling back to cached: $e",
            )
            return false
        } catch (e: Exception) {
            BBLogger.warning(
                BBLogCategory.API,
                "KiaUSA: rems/rvs real-time refresh failed, falling back to cached: $e",
            )
            return false
        }
    }

    companion object {
        /**
         * How long we treat a Kia session ID (`sid`) as valid before forcing
         * a re-login. Matches the Python `hyundai_kia_connect_api` reference's
         * `LOGIN_TOKEN_LIFETIME = 23h`. The previous BetterBlueKit value of
         * 1 hour drove ~23x more `authUser` calls than the reference and
         * appears to be the cause of the recurring MFA prompts users were
         * hitting on Kia USA accounts.
         */
        const val LOGIN_TOKEN_LIFETIME_SECONDS: Long = 23L * 3600

        /**
         * Cooldown between successful `rems/rvs` modem polls (see the callsite
         * comment in [fetchVehicleStatus]).
         */
        const val REAL_TIME_REFRESH_COOLDOWN_SECONDS: Long = 60
    }
}
