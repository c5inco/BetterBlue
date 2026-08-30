package com.betterblue.app.data.repo

import com.betterblue.app.data.db.dao.AccountDao
import com.betterblue.app.data.db.dao.ClimatePresetDao
import com.betterblue.app.data.db.dao.VehicleDao
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.settings.AppSettings
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.cache.CachedApiClient
import com.betterblue.kit.ApiException
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.EVTripInfo
import com.betterblue.kit.model.EVTripSummary
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Vehicle-facing operations: status fetches, commands with post-command
 * verification, trips, and surround view. Port of the vehicle-related
 * behavior on iOS `BBAccount` + `BBVehicle`.
 */
@Singleton
class VehicleRepository @Inject constructor(
    private val accounts: AccountRepository,
    private val accountDao: AccountDao,
    private val vehicleDao: VehicleDao,
    private val climatePresetDao: ClimatePresetDao,
    private val statusBus: StatusChangeBus,
    private val settings: AppSettings,
) {
    fun observeVehicles() = vehicleDao.observeAll()
    fun observeVisibleVehicles() = vehicleDao.observeVisible()
    fun observeVehicle(vin: String) = vehicleDao.observeByVin(vin)
    fun observeClimatePresets(vin: String) = climatePresetDao.observeForVehicle(vin)

    suspend fun getVehicle(vin: String): VehicleEntity? = vehicleDao.getByVin(vin)

    /** Persists user-facing edits (custom name, colors, visibility, overrides). */
    suspend fun updateVehicle(vehicle: VehicleEntity) = vehicleDao.update(vehicle)

    suspend fun updateSortOrders(vinsInOrder: List<String>) {
        vinsInOrder.forEachIndexed { index, vin -> vehicleDao.updateSortOrder(vin, index) }
    }

    /**
     * Fetches a vehicle's status. Pass `cached = false` when the user
     * explicitly asked for fresh data (manual sync, post-command
     * verification); background reads default to `true` so we don't
     * repeatedly wake the vehicle modem.
     */
    suspend fun fetchVehicleStatus(vin: String, cached: Boolean = true): VehicleStatus {
        val vehicle = vehicleDao.getByVin(vin)
            ?: throw ApiException("Vehicle not found: $vin", apiName = "VehicleRepository")
        val accountId = vehicle.accountId
        val entity = accountDao.getById(accountId)
            ?: throw ApiException("Account not found for vehicle", apiName = "VehicleRepository")

        // Kia (non-EU) commands need a vehicleKey; refetch the list when missing.
        val creds = accounts.credentials(entity)
        if (creds.brand == Brand.KIA && creds.region != Region.EUROPE && vehicle.vehicleKey == null) {
            BBLogger.debug(BBLogCategory.API, "VehicleRepository: Kia vehicle missing vehicleKey, fetching fresh data...")
            accounts.loadVehicles(accountId)
            return fetchVehicleStatusOnce(vin, cached)
        }

        return fetchVehicleStatusOnce(vin, cached)
    }

    private suspend fun fetchVehicleStatusOnce(vin: String, cached: Boolean): VehicleStatus {
        val vehicle = vehicleDao.getByVin(vin)
            ?: throw ApiException("Vehicle not found: $vin", apiName = "VehicleRepository")
        val accountId = vehicle.accountId
        val (api, token) = accounts.requireSession(accountId)

        val status = try {
            api.fetchVehicleStatus(vehicle.toVehicle(), token, cached)
        } catch (e: ApiException) {
            if (!accounts.shouldReauthenticate(e)) throw e
            accounts.handleApiError(accountId, e)
            val (retryApi, retryToken) = accounts.requireSession(accountId)
            retryApi.fetchVehicleStatus(vehicle.toVehicle(), retryToken, cached)
        }

        return status
    }

    /**
     * Fetches status and merges it into storage — also refreshing the
     * vehicle list first when it's stale (or the caller forces it).
     * Publishing on the status bus wakes any post-command waiters early.
     */
    suspend fun fetchAndUpdateVehicleStatus(
        vin: String,
        cached: Boolean = true,
        forceVehicleListRefresh: Boolean = false,
    ): VehicleStatus {
        val vehicle = vehicleDao.getByVin(vin)
            ?: throw ApiException("Vehicle not found: $vin", apiName = "VehicleRepository")
        val entity = accountDao.getById(vehicle.accountId)

        val isStale = entity?.lastVehiclesFetch?.let {
            System.currentTimeMillis() - it > AccountRepository.VEHICLE_LIST_MAX_AGE_MILLIS
        } ?: true

        if (forceVehicleListRefresh || isStale) {
            try {
                BBLogger.info(
                    BBLogCategory.API,
                    "VehicleRepository: refreshing vehicle list before status " +
                        "(force=$forceVehicleListRefresh, stale=$isStale)",
                )
                accounts.loadVehicles(vehicle.accountId)
            } catch (e: Exception) {
                if (forceVehicleListRefresh) {
                    // The user explicitly asked for fresh data — don't
                    // silently skip past a list-fetch failure.
                    throw e
                }
                // Best-effort on automatic paths: proceed with the stale list.
                BBLogger.warning(
                    BBLogCategory.API,
                    "VehicleRepository: vehicle list refresh failed during background poll, " +
                        "falling back to cached list: $e",
                )
            }
        }

        val status = fetchVehicleStatus(vin, cached)
        applyStatus(vin, status)
        return status
    }

    /** Merges a status into the stored row and wakes status waiters. */
    suspend fun applyStatus(vin: String, status: VehicleStatus) {
        vehicleDao.getByVin(vin)?.let { vehicleDao.update(it.updatedWithStatus(status)) }
        statusBus.publish(status)
    }

    // Commands

    suspend fun sendCommand(vin: String, command: VehicleCommand, allowAuthRetry: Boolean = true) {
        val vehicle = vehicleDao.getByVin(vin)
            ?: throw ApiException("Vehicle not found: $vin", apiName = "VehicleRepository")
        val accountId = vehicle.accountId
        val entity = accountDao.getById(accountId)
            ?: throw ApiException("Account not found for vehicle", apiName = "VehicleRepository")

        // Kia (non-EU) commands require the vehicleKey.
        val creds = accounts.credentials(entity)
        if (creds.brand == Brand.KIA && creds.region != Region.EUROPE && vehicle.vehicleKey == null) {
            BBLogger.debug(BBLogCategory.API, "VehicleRepository: Kia vehicle missing vehicleKey, fetching fresh data...")
            val (api, token) = accounts.requireSession(accountId)
            val fetched = api.fetchVehicles(token)
            val match = fetched.firstOrNull { it.vin == vin }
                ?: throw ApiException.logError("Vehicle not found in fetched data", apiName = "VehicleRepository")
            vehicleDao.update(vehicle.copy(vehicleKey = match.vehicleKey))
        }

        val current = vehicleDao.getByVin(vin) ?: vehicle
        val (api, token) = accounts.requireSession(accountId)
        try {
            api.sendCommand(current.toVehicle(), command, token)
        } catch (e: ApiException) {
            // A blind command retry can act on the car twice; only retry the
            // narrow set of pre-vehicle auth failures, and only once.
            if (!allowAuthRetry || !accounts.shouldRetryCommand(e)) throw e
            BBLogger.info(
                BBLogCategory.API,
                "VehicleRepository: command rejected (${e.errorType}), re-authenticating and retrying once",
            )
            accounts.handleApiError(accountId, e)
            val (retryApi, retryToken) = accounts.requireSession(accountId)
            retryApi.sendCommand(current.toVehicle(), command, retryToken)
        }
    }

    suspend fun lock(vin: String) = sendCommand(vin, VehicleCommand.Lock)
    suspend fun unlock(vin: String) = sendCommand(vin, VehicleCommand.Unlock)
    suspend fun stopClimate(vin: String) = sendCommand(vin, VehicleCommand.StopClimate)
    suspend fun startCharge(vin: String) = sendCommand(vin, VehicleCommand.StartCharge)
    suspend fun stopCharge(vin: String) = sendCommand(vin, VehicleCommand.StopCharge)

    suspend fun setTargetSoc(vin: String, acLevel: Int, dcLevel: Int) =
        sendCommand(vin, VehicleCommand.SetTargetSoc(acLevel = acLevel, dcLevel = dcLevel))

    /**
     * Starts climate using the given options, or the vehicle's presets:
     * selected preset first, then first preset, then defaults in the user's
     * preferred unit.
     */
    suspend fun startClimate(vin: String, options: ClimateOptions? = null) {
        val resolved = options ?: run {
            val presets = climatePresetDao.getForVehicle(vin)
            presets.firstOrNull { it.isSelected }?.climateOptions
                ?: presets.firstOrNull()?.climateOptions
                ?: ClimateOptions.forPreferredUnits(settings.currentTemperatureUnit())
        }
        sendCommand(vin, VehicleCommand.StartClimate(resolved))
    }

    /**
     * Interruptible post-command polling: waits [initialDelaySeconds], then
     * polls a real-time status up to [maxAttempts] times until [condition]
     * holds, pushing progress strings to [statusMessageUpdater]. Any status
     * arriving on the bus (from any source) both wakes the delay early and
     * can satisfy the condition. Port of iOS `BBVehicle.waitForStatusChange`.
     *
     * On exhaustion this throws STATUS_VERIFICATION_TIMEOUT — a soft
     * "awaiting confirmation" outcome, not a failure: the command was
     * already accepted upstream.
     */
    suspend fun waitForStatusChange(
        vin: String,
        condition: (VehicleStatus) -> Boolean,
        statusMessageUpdater: ((String) -> Unit)? = null,
        maxAttempts: Int = 3,
        initialDelaySeconds: Int = 10,
        retryDelaySeconds: Int = 10,
    ) {
        statusMessageUpdater?.invoke("Command sent")
        interruptibleDelay(vin, initialDelaySeconds)

        var attempt = 0
        while (attempt < maxAttempts) {
            // Post-command verification must reflect the vehicle's actual
            // state, not the backend's cached snapshot.
            val status = fetchVehicleStatus(vin, cached = false)
            applyStatus(vin, status)

            if (condition(status)) {
                BBLogger.info(BBLogCategory.VEHICLE, "VehicleRepository: status condition met for $vin")
                return
            }

            attempt += 1
            if (attempt < maxAttempts) {
                statusMessageUpdater?.invoke("Waiting for vehicle ($attempt/$maxAttempts)")
                interruptibleDelay(vin, retryDelaySeconds)
            }
        }

        throw ApiException(
            message = "The command was sent, but the vehicle hasn't confirmed the change yet. " +
                "It may still complete — refresh in a minute to check.",
            errorType = ApiErrorType.STATUS_VERIFICATION_TIMEOUT,
        )
    }

    /**
     * Runs [action] then verifies with [condition], translating the timeout
     * into [CommandOutcome.AwaitingConfirmation].
     */
    suspend fun executeAndVerify(
        vin: String,
        action: suspend () -> Unit,
        condition: (VehicleStatus) -> Boolean,
        statusMessageUpdater: ((String) -> Unit)? = null,
    ): CommandOutcome {
        try {
            action()
        } catch (e: ApiException) {
            return CommandOutcome.Failed(e)
        }
        return try {
            waitForStatusChange(vin, condition, statusMessageUpdater)
            CommandOutcome.Confirmed
        } catch (e: ApiException) {
            if (e.errorType == ApiErrorType.STATUS_VERIFICATION_TIMEOUT) {
                CommandOutcome.AwaitingConfirmation
            } else {
                CommandOutcome.Failed(e)
            }
        }
    }

    /**
     * A delay that an incoming status update for [vin] cuts short — the
     * analog of the iOS actor-held continuation race.
     */
    private suspend fun interruptibleDelay(vin: String, seconds: Int) {
        val woken = withTimeoutOrNull(seconds.seconds) {
            statusBus.statusUpdates.first { it.vin == vin }
        }
        if (woken != null) {
            BBLogger.debug(BBLogCategory.VEHICLE, "VehicleRepository: sleep interrupted by status update for $vin")
        }
    }

    // Trips & surround view

    suspend fun fetchEvTripSummary(vin: String): List<EVTripSummary>? = withReauth(vin) { api, token, vehicle ->
        api.fetchEvTripSummary(vehicle.toVehicle(), token)
    }

    suspend fun fetchEvTripInfo(vin: String, date: LocalDate): List<EVTripInfo>? = withReauth(vin) { api, token, vehicle ->
        api.fetchEvTripInfo(vehicle.toVehicle(), token, date)
    }

    suspend fun requestSurroundViewCapture(vin: String) {
        withReauth(vin) { api, token, vehicle ->
            api.requestSurroundViewCapture(vehicle.toVehicle(), token)
        }
    }

    suspend fun fetchSurroundViewCaptures(vin: String): List<SurroundViewCapture> =
        withReauth(vin) { api, token, vehicle ->
            api.fetchSurroundViewCaptures(vehicle.toVehicle(), token)
        }

    private suspend fun <T> withReauth(
        vin: String,
        block: suspend (CachedApiClient, com.betterblue.kit.model.AuthToken, VehicleEntity) -> T,
    ): T {
        val vehicle = vehicleDao.getByVin(vin)
            ?: throw ApiException("Vehicle not found: $vin", apiName = "VehicleRepository")
        val (api, token) = accounts.requireSession(vehicle.accountId)
        return try {
            block(api, token, vehicle)
        } catch (e: ApiException) {
            if (!accounts.shouldReauthenticate(e)) throw e
            accounts.handleApiError(vehicle.accountId, e)
            val (retryApi, retryToken) = accounts.requireSession(vehicle.accountId)
            block(retryApi, retryToken, vehicle)
        }
    }
}
