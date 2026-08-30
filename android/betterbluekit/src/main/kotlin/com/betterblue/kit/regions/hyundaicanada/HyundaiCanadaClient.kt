package com.betterblue.kit.regions.hyundaicanada

import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientBase
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.HttpMethod
import com.betterblue.kit.HyundaiCanadaVariant
import com.betterblue.kit.MfaMethod
import com.betterblue.kit.MfaVerification
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpRequestType
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.util.UUID

class HyundaiCanadaClient(config: ApiClientConfig) : ApiClientBase(config), ApiClient {

    internal val clientId = "HATAHSPACA0232141ED9722C67715A0B"
    internal val clientSecret = "CLISCR01AHSPA"

    /** The selected connection variant for this account. */
    internal val variant: HyundaiCanadaVariant get() = config.hyundaiCanadaVariant

    /** User-Agent for the selected variant. */
    internal val userAgent: String
        get() = if (variant == HyundaiCanadaVariant.NATIVE_APP) NATIVE_USER_AGENT else WEB_USER_AGENT

    /** `from` header value for the selected variant. */
    internal val fromHeader: String
        get() = if (variant == HyundaiCanadaVariant.NATIVE_APP) "SPA" else "CWP"

    /**
     * Stable per-account device ID. Hyundai Canada's anti-fraud challenge fires
     * every time a "new device" logs in — using a fresh random UUID per session
     * guarantees the user sees an OTP challenge (errorCode 7110) on every
     * login. The host app generates and persists a stable UUID per account;
     * honor that when present, fall back to a random UUID only if the host
     * didn't supply one (e.g. a CLI without a stored config). (Matches the
     * hyundai_kia_connect_api Python reference, which derives a deterministic
     * device ID from MAC + hostname for the same reason.)
     */
    internal val deviceId: String by lazy { config.deviceId ?: UUID.randomUUID().toString().uppercase() }

    // Note: temperature lookup tables live on `Temperature` (the canonical
    // Standard table), and the HEX climate encoding goes through
    // `Temperature.encodeAirTempToHex`.

    internal var cloudFlareCookie: String? = null

    // Location strategy state
    //
    // Which endpoint/header pairing this account's location works with varies
    // (see [LocationStrategy]), so it is discovered once and reused.
    // Per-instance, not persisted: the client is recreated on login, and
    // re-discovering costs at most two extra calls.
    internal var locationStrategy: LocationStrategy? = null

    /**
     * When the last full sweep ran, so an account where *nothing* works backs
     * off instead of sweeping on every status refresh.
     */
    internal var lastLocationSweep: Instant? = null

    // MFA flow state
    //
    // Hyundai Canada's MFA differs slightly from Kia USA's: the OTP key is
    // only issued AFTER the user picks email/SMS (Kia returns it in the
    // initial challenge). We stash everything we learn at each step here so
    // the interface's three-method MFA contract still works. Plain vars —
    // these are only touched from suspend methods called sequentially by one
    // login flow.

    /**
     * `userInfoUuid` returned by `mfa/selverifmeth`. Surfaced as `xid` in the
     * requires-MFA error and threaded through every later call.
     */
    internal var mfaUserInfoUuid: String? = null

    /**
     * Email associated with the account, returned by `selverifmeth` and echoed
     * back by `sendotp` / `genmfatkn`.
     */
    internal var mfaEmail: String? = null

    /** `otpKey` returned by `mfa/sendotp`, consumed by `mfa/validateotp`. */
    internal var mfaOtpKey: String? = null

    /**
     * Last-4 (or full, depending on server) of the SMS number echoed by
     * `selverifmeth`. Threaded back into `sendotp` for the SMS delivery path.
     */
    internal var mfaPhone: String? = null

    /**
     * Final auth token built from `mfa/genmfatkn`'s response. Returned from
     * [completeMfaLogin] so the caller never sees the multi-step dance under
     * the hood.
     */
    internal var mfaCompletedAuthToken: AuthToken? = null

    internal val baseUrl: String get() = region.apiBaseUrl(Brand.HYUNDAI)
    internal val apiBaseUrl: String get() = "$baseUrl/tods/api"
    internal val apiHost: String get() = "mybluelink.ca"

    override val apiName: String get() = "HyundaiCanada"

    // ApiClient implementation

    // Declared here rather than in the MFA file so one list covers every
    // optional capability the Canada client implements.
    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> =
        setOf(OptionalApiFeature.MFA, OptionalApiFeature.SURROUND_VIEW)

    override suspend fun login(): AuthToken {
        BBLogger.info(BBLogCategory.AUTH, "HyundaiCanada: starting login")

        val cookie = ensureCloudFlareCookie()

        val loginHeaders = headers() + ("Cookie" to cookie)

        val (_, result) = performJsonRequest(
            url = "$apiBaseUrl/v2/login",
            method = HttpMethod.POST,
            headers = loginHeaders,
            body = buildJsonObject {
                put("loginId", username)
                put("password", password)
            },
            requestType = HttpRequestType.LOGIN,
        )

        // Intercept the OTP-required response (errorCode 7110) before the
        // generic parser runs — it would otherwise throw a generic "Canada
        // login failed" error and the caller couldn't tell that an MFA
        // challenge is what's needed. `beginMfaFlow` always throws a
        // requires-MFA exception on success; control only returns here on a
        // non-7110 response, which the regular parser handles.
        if (isOtpRequiredResponse(result.body)) {
            beginMfaFlow(cookie)
        }

        return parseCanadaLoginResponse(result.body)
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> {
        ensureCloudFlareCookie()

        val (_, result) = performJsonRequest(
            url = "$apiBaseUrl/vhcllst",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken),
            requestType = HttpRequestType.FETCH_VEHICLES,
        )

        return parseCanadaVehiclesResponse(result.body)
    }

    // Cached (sltvhcl) vs real-time (rltmvhclsts) status. Real-time wakes the
    // vehicle modem; use sparingly.
    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        ensureCloudFlareCookie()

        val statusEndpoint = if (cached) "sltvhcl" else "rltmvhclsts"
        val (_, primaryResult) = performJsonRequest(
            url = "$apiBaseUrl/$statusEndpoint",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicleId = vehicle.regId),
            body = buildJsonObject { put("vehicleId", vehicle.regId) },
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )
        val primaryData = primaryResult.body

        val statusData = if (cached) {
            primaryData
        } else {
            fetchRealtimeStatusData(primaryData, vehicle, authToken)
        }
        val finalData = injectLocationCoordinates(statusData, vehicle, authToken)

        return try {
            parseCanadaVehicleStatusResponse(finalData, vehicle)
        } catch (error: Exception) {
            BBLogger.debug(BBLogCategory.API, "HyundaiCanada: parsing final status payload failed: $error")
            parseCanadaVehicleStatusResponse(primaryData, vehicle)
        }
    }

    /** Real-time polls re-fetch the cached `sltvhcl` payload for complete vehicle metadata. */
    private suspend fun fetchRealtimeStatusData(
        primaryData: ByteArray,
        vehicle: Vehicle,
        authToken: AuthToken,
    ): ByteArray = try {
        val (_, cachedResult) = performJsonRequest(
            url = "$apiBaseUrl/sltvhcl",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicleId = vehicle.regId),
            body = buildJsonObject { put("vehicleId", vehicle.regId) },
            requestType = HttpRequestType.FETCH_VEHICLE_STATUS,
            vin = vehicle.vin,
        )
        cachedResult.body
    } catch (error: Exception) {
        BBLogger.debug(BBLogCategory.API, "HyundaiCanada: failed fetching sltvhcl: $error")
        primaryData
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        ensureCloudFlareCookie()

        val authCode = fetchCommandAuthCode(authToken)

        sendCommandRequest(vehicle, command, authToken, authCode)
    }

    // Command flow

    /** PIN → pAuth exchange; every command request carries the resulting code. */
    internal suspend fun fetchCommandAuthCode(authToken: AuthToken): String {
        val (_, result) = performJsonRequest(
            url = "$apiBaseUrl/vrfypin",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken),
            body = buildJsonObject { put("pin", pin) },
            requestType = HttpRequestType.SEND_COMMAND,
        )

        return parseCommandAuthResponse(result.body)
    }

    private suspend fun sendCommandRequest(
        vehicle: Vehicle,
        command: VehicleCommand,
        authToken: AuthToken,
        authCode: String,
    ) {
        if (command is VehicleCommand.StartClimate) {
            // Climate wraps its options in `hvacInfo` on most vehicles, but
            // some only accept the older `remoteControl` wrapper — try the
            // modern shape first and fall back on any failure.
            try {
                sendCommandRequest(vehicle, command, authToken, authCode, useRemoteControl = false)
            } catch (_: Exception) {
                sendCommandRequest(vehicle, command, authToken, authCode, useRemoteControl = true)
            }
            return
        }

        sendCommandRequest(vehicle, command, authToken, authCode, useRemoteControl = false)
    }

    private suspend fun sendCommandRequest(
        vehicle: Vehicle,
        command: VehicleCommand,
        authToken: AuthToken,
        authCode: String,
        useRemoteControl: Boolean,
    ) {
        val (_, result) = performJsonRequest(
            url = "$apiBaseUrl/${commandPath(command)}",
            method = HttpMethod.POST,
            headers = authorizedHeaders(authToken, vehicleId = vehicle.regId, pAuth = authCode),
            body = makeCommandBody(command, useRemoteControl),
            requestType = HttpRequestType.SEND_COMMAND,
            vin = vehicle.vin,
        )

        validateCommandResponse(result.body, context = "command")
    }

    internal suspend fun ensureCloudFlareCookie(): String {
        cloudFlareCookie?.takeIf { it.isNotEmpty() }?.let { return it }

        val cookie = fetchCloudFlareCookie()
        cloudFlareCookie = cookie
        return cookie
    }

    // MFA + surround view interface overrides delegate to the extension
    // implementations in HyundaiCanadaMfa.kt / HyundaiCanadaSurroundView.kt.

    override suspend fun sendMfaCode(xid: String, otpKey: String, method: MfaMethod) =
        sendMfaCodeImpl(xid, method)

    override suspend fun verifyMfaCode(xid: String, otpKey: String, code: String): MfaVerification =
        verifyMfaCodeImpl(xid, code)

    override suspend fun completeMfaLogin(sid: String, rmToken: String): AuthToken =
        completeMfaLoginImpl()

    override suspend fun requestSurroundViewCapture(vehicle: Vehicle, authToken: AuthToken) =
        requestSurroundViewCaptureImpl(vehicle, authToken)

    override suspend fun fetchSurroundViewCaptures(vehicle: Vehicle, authToken: AuthToken) =
        fetchSurroundViewCapturesImpl(vehicle, authToken)

    override suspend fun registerDevice(): String? = super<ApiClientBase>.registerDevice()

    companion object {
        // Hyundai Canada sits behind Cloudflare and its behavior varies by
        // user/IP, so the header identity is user-selectable (see
        // [HyundaiCanadaVariant]). The `/login` GET that mints the `__cf_bm`
        // cookie only returns it to a client Cloudflare trusts; for most users
        // that's a browser-like client (webPortal), but some users only
        // connect as the native MyHyundai app (nativeApp). The picker lets
        // each user choose. (GitHub #67, #79, #35.)
        internal const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        internal const val NATIVE_USER_AGENT = "MyHyundai/2.0.25 (iPhone; iOS 18.3; Scale/3.00)"

        internal val LOCATION_SWEEP_INTERVAL: Duration = Duration.ofMinutes(30)
    }
}
