package com.betterblue.kit

import com.betterblue.kit.log.HttpLogSink
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.EVTripInfo
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

/**
 * Which way the Hyundai Canada client presents itself to the backend.
 * Hyundai Canada's Cloudflare + endpoint behavior varies per user/IP, so no
 * single identity works for everyone — this lets a user pick the one that
 * connects for them (surfaced in the app as "Connection").
 */
@Serializable
enum class HyundaiCanadaVariant {
    /** Web-portal login (`from: CWP` + a browser User-Agent) — clears Cloudflare for most users. */
    @SerialName("webPortal")
    WEB_PORTAL,

    /**
     * Native-app identity everywhere (`from: SPA` + the MyHyundai iOS
     * User-Agent) — for users where Cloudflare blocks the web-portal identity
     * but the app one works.
     */
    @SerialName("nativeApp")
    NATIVE_APP,
    ;

    val displayName: String
        get() =
            when (this) {
                WEB_PORTAL -> "Web Portal"
                NATIVE_APP -> "Native App"
            }

    val summary: String
        get() =
            when (this) {
                WEB_PORTAL -> "Browser-style login (recommended). Best for clearing Cloudflare."
                NATIVE_APP -> "MyHyundai app-style login. Try this if Web Portal won't connect."
            }

    companion object {
        val DEFAULT = WEB_PORTAL
    }
}

data class ApiClientConfig(
    val region: Region,
    val brand: Brand,
    val username: String,
    val password: String,
    val refreshToken: String? = null,
    val pin: String,
    /** Owning account id (UUID string). */
    val accountId: String,
    val logSink: HttpLogSink? = null,
    val rememberMeToken: String? = null,
    val redactPii: Boolean = true,
    val deviceId: String? = null,
    /** Hyundai Canada connection variant (ignored by other brands/regions). */
    val hyundaiCanadaVariant: HyundaiCanadaVariant = HyundaiCanadaVariant.DEFAULT,
    /**
     * Invoked when the client observes a rotated `rmToken` (or equivalent
     * long-lived "remember-me" credential) in a login response. The caller
     * must persist the new value so subsequent logins present the latest
     * token. Currently used only by the Kia USA client.
     */
    val onRememberMeTokenRotated: ((String) -> Unit)? = null,
)

enum class MfaMethod { EMAIL, SMS }

enum class EVTripType { SUMMARY, INFO }

/**
 * The optional capabilities an API client can declare via
 * [ApiClient.optionalFeaturesSupported].
 */
enum class OptionalApiFeature {
    /** Multi-factor authentication (send/verify/complete MFA login). */
    MFA,

    /** Day- or trip-level driving history ([ApiClient.fetchEvTripSummary]). */
    EV_TRIP_SUMMARY,

    /** Per-trip drill-down for a specific date ([ApiClient.fetchEvTripInfo]). */
    EV_TRIP_INFO,

    /** 360° camera stills on demand. */
    SURROUND_VIEW,
}

data class MfaVerification(
    val rememberMeToken: String,
    val sid: String,
)

/** Client for communicating with a Hyundai/Kia region backend. */
interface ApiClient {
    suspend fun login(): AuthToken

    suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle>

    /**
     * Fetch the latest status for a vehicle.
     *
     * @param cached When true, return the server-cached snapshot (cheap,
     * instant). When false, request a real-time poll from the vehicle (slow,
     * wakes the modem, may be rate-limited). Manual user-initiated refreshes
     * and post-command verification should pass `false`; widget timelines and
     * background refreshes should pass `true`. Brands without a real-time
     * endpoint may treat both modes identically.
     */
    suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean = true): VehicleStatus

    suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken)

    /** Optional: EV trip summary (not all brands support this). */
    suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken): List<EVTripSummary>? = null

    /** Optional: EV trip info for a given date (not all brands support this). */
    suspend fun fetchEvTripInfo(vehicle: Vehicle, authToken: AuthToken, date: LocalDate): List<EVTripInfo>? = null

    /**
     * Optional: ask the vehicle to take a fresh surround-view capture. Returns
     * as soon as the request is accepted — the vehicle then wakes its cameras,
     * shoots, and uploads, which takes minutes. Poll
     * [fetchSurroundViewCaptures] for the result.
     */
    suspend fun requestSurroundViewCapture(vehicle: Vehicle, authToken: AuthToken) {
        throw ApiException("Surround view not supported for this API", apiName = "ApiClient")
    }

    /**
     * Optional: fetch the surround-view captures the server currently holds
     * for a vehicle, newest first. Never triggers a new capture.
     */
    suspend fun fetchSurroundViewCaptures(vehicle: Vehicle, authToken: AuthToken): List<SurroundViewCapture> =
        emptyList()

    /**
     * The optional capabilities this client implements beyond the required
     * surface. Clients override this single method; callers use the
     * convenience helpers ([supportsMfa], [supportedEvTripTypes]) instead of
     * inspecting the list directly.
     */
    fun optionalFeaturesSupported(): Set<OptionalApiFeature> = emptySet()

    // MFA support (optional)

    suspend fun sendMfaCode(xid: String, otpKey: String, method: MfaMethod) {
        throw ApiException("MFA not supported for this API", apiName = "ApiClient")
    }

    suspend fun verifyMfaCode(xid: String, otpKey: String, code: String): MfaVerification {
        throw ApiException("MFA not supported for this API", apiName = "ApiClient")
    }

    suspend fun completeMfaLogin(sid: String, rmToken: String): AuthToken {
        throw ApiException("MFA not supported for this API", apiName = "ApiClient")
    }

    /** Register device. Default is a random uppercase UUID. */
    suspend fun registerDevice(): String? = UUID.randomUUID().toString().uppercase()
}

fun ApiClient.supportsMfa(): Boolean = optionalFeaturesSupported().contains(OptionalApiFeature.MFA)

fun ApiClient.supportsSurroundView(): Boolean =
    optionalFeaturesSupported().contains(OptionalApiFeature.SURROUND_VIEW)

fun ApiClient.supportedEvTripTypes(): List<EVTripType> =
    buildList {
        val features = optionalFeaturesSupported()
        if (features.contains(OptionalApiFeature.EV_TRIP_SUMMARY)) add(EVTripType.SUMMARY)
        if (features.contains(OptionalApiFeature.EV_TRIP_INFO)) add(EVTripType.INFO)
    }

enum class HttpMethod { GET, POST, PUT, DELETE }
