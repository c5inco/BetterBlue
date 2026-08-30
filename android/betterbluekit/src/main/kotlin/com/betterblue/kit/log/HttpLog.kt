package com.betterblue.kit.log

import com.betterblue.kit.json.InstantEpochMillisSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
enum class HttpRequestType {
    @SerialName("login")
    LOGIN,

    @SerialName("fetchVehicles")
    FETCH_VEHICLES,

    @SerialName("fetchVehicleStatus")
    FETCH_VEHICLE_STATUS,

    @SerialName("sendCommand")
    SEND_COMMAND,

    @SerialName("sendMFA")
    SEND_MFA,

    @SerialName("verifyMFA")
    VERIFY_MFA,

    @SerialName("fetchEVTripSummary")
    FETCH_EV_TRIP_SUMMARY,

    @SerialName("fetchEVTripInfo")
    FETCH_EV_TRIP_INFO,

    @SerialName("requestSurroundView")
    REQUEST_SURROUND_VIEW,

    @SerialName("fetchSurroundView")
    FETCH_SURROUND_VIEW,
    ;

    val displayName: String
        get() =
            when (this) {
                LOGIN -> "Login"
                FETCH_VEHICLES -> "Fetch Vehicles"
                FETCH_VEHICLE_STATUS -> "Fetch Status"
                SEND_COMMAND -> "Send Command"
                SEND_MFA -> "Send MFA"
                VERIFY_MFA -> "Verify MFA"
                FETCH_EV_TRIP_SUMMARY -> "Fetch Trip Summary"
                FETCH_EV_TRIP_INFO -> "Fetch Trip Info"
                REQUEST_SURROUND_VIEW -> "Request Surround View"
                FETCH_SURROUND_VIEW -> "Fetch Surround View"
            }
}

/** Receives one [HttpLog] per API request; implementations must be thread-safe. */
fun interface HttpLogSink {
    fun log(log: HttpLog)
}

@Serializable
data class HttpLog(
    @Serializable(with = InstantEpochMillisSerializer::class)
    val timestamp: Instant,
    /** Owning account id (UUID string). */
    val accountId: String,
    val requestType: HttpRequestType,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String? = null,
    val responseStatus: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    val error: String? = null,
    val apiError: String? = null,
    /** Wall-clock duration in seconds. */
    val duration: Double,
    val stackTrace: String? = null,
    /**
     * VIN of the vehicle this request pertains to, if any. Populated for
     * per-vehicle requests (status fetch, commands); null for account-wide
     * requests (login, fetchVehicles).
     */
    val vin: String? = null,
) {
    val statusText: String
        get() {
            val status = responseStatus ?: return if (error != null) "Error" else "Pending"
            return if (apiError != null) "$status (API Error)" else "$status"
        }

    val isSuccess: Boolean
        get() {
            val status = responseStatus ?: return false
            return status in 200..299 && error == null && apiError == null
        }

    val formattedDuration: String get() = "%.2fs".format(duration)

    val preciseTimestamp: String
        get() = PRECISE_FORMATTER.format(timestamp.atZone(ZoneId.systemDefault()))

    private companion object {
        val PRECISE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
