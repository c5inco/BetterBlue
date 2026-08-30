package com.betterblue.app.data.fake

import com.betterblue.app.data.db.dao.VehicleDao
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.repo.toVehicle
import com.betterblue.kit.ApiException
import com.betterblue.kit.fake.FakeVehicleProvider
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Room-backed fake vehicle provider — the offline/demo mode the app is
 * developed against without a real car. Commands mutate the stored vehicle
 * row directly, and each vehicle's [DebugConfiguration] injects failures.
 */
@Singleton
class RoomFakeVehicleProvider @Inject constructor(
    private val vehicleDao: VehicleDao,
) : FakeVehicleProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getFakeVehicles(username: String, accountId: String): List<Vehicle> {
        val vehicles = vehicleDao.getByAccount(accountId)
        BBLogger.debug(
            BBLogCategory.FAKE_API,
            "RoomFakeVehicleProvider: found ${vehicles.size} existing fake vehicles for account",
        )
        return vehicles.map { it.toVehicle() }
    }

    override suspend fun getVehicleStatus(vin: String, accountId: String): VehicleStatus {
        val vehicle = requireVehicle(vin, accountId)
        // Stamp a fresh fetch time so the UI's "updated" line moves.
        val now = System.currentTimeMillis()
        vehicleDao.update(vehicle.copy(lastUpdated = now, syncDate = now))
        return statusFrom(vehicle, now)
    }

    override suspend fun executeCommand(command: VehicleCommand, vin: String, accountId: String) {
        val vehicle = requireVehicle(vin, accountId)

        val updated = when (command) {
            VehicleCommand.Lock -> {
                BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: locking fake vehicle '$vin'")
                vehicle.copy(lockStatus = "locked")
            }

            VehicleCommand.Unlock -> {
                BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: unlocking fake vehicle '$vin'")
                vehicle.copy(lockStatus = "unlocked")
            }

            is VehicleCommand.StartClimate -> {
                val options = command.options
                BBLogger.info(
                    BBLogCategory.FAKE_API,
                    "FakeAPI: starting climate for '$vin' at ${options.temperature.value}°",
                )
                vehicle.copy(
                    climateStatus = VehicleStatus.ClimateStatus(
                        defrostOn = options.defrost,
                        airControlOn = options.climate,
                        steeringWheelHeatingOn = options.steeringWheel != 0,
                        temperature = options.temperature,
                    ),
                )
            }

            VehicleCommand.StopClimate -> {
                BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: stopping climate for '$vin'")
                vehicle.climateStatus?.let { current ->
                    vehicle.copy(
                        climateStatus = current.copy(
                            defrostOn = false,
                            airControlOn = false,
                            steeringWheelHeatingOn = false,
                        ),
                    )
                } ?: vehicle
            }

            VehicleCommand.StartCharge -> {
                BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: starting charge for '$vin'")
                vehicle.evStatus?.let { ev ->
                    // ~1.5 minutes per remaining percent at a nominal 50 kW.
                    val remaining = 100.0 - ev.evRange.percentage
                    vehicle.copy(
                        evStatus = ev.copy(
                            charging = true,
                            chargeSpeed = 50.0,
                            plugType = if (ev.plugType != VehicleStatus.PlugType.UNPLUGGED) {
                                ev.plugType
                            } else {
                                VehicleStatus.PlugType.AC_CHARGER
                            },
                            chargeTimeSeconds = (remaining * 1.5).roundToLong() * 60,
                        ),
                    )
                } ?: vehicle
            }

            VehicleCommand.StopCharge -> {
                BBLogger.info(BBLogCategory.FAKE_API, "FakeAPI: stopping charge for '$vin'")
                vehicle.evStatus?.let { ev ->
                    vehicle.copy(
                        evStatus = ev.copy(charging = false, chargeSpeed = 0.0, chargeTimeSeconds = 0),
                    )
                } ?: vehicle
            }

            is VehicleCommand.SetTargetSoc -> {
                BBLogger.info(
                    BBLogCategory.FAKE_API,
                    "FakeAPI: setting target SOC for '$vin' - AC: ${command.acLevel}%, DC: ${command.dcLevel}%",
                )
                vehicle.evStatus?.let { ev ->
                    vehicle.copy(
                        evStatus = ev.copy(
                            targetSocAC = command.acLevel.toDouble(),
                            targetSocDC = command.dcLevel.toDouble(),
                        ),
                    )
                } ?: vehicle
            }
        }

        vehicleDao.update(updated.copy(lastUpdated = System.currentTimeMillis()))
    }

    // Failure injection

    override suspend fun shouldFailCredentialValidation(accountId: String): Boolean =
        accountConfigs(accountId).any { it.shouldFailCredentialValidation }

    override suspend fun shouldFailLogin(accountId: String): Boolean =
        accountConfigs(accountId).any { it.shouldFailLogin }

    override suspend fun shouldFailVehicleFetch(accountId: String): Boolean =
        accountConfigs(accountId).any { it.shouldFailVehicleFetch }

    override suspend fun shouldFailStatusFetch(vin: String, accountId: String): Boolean =
        config(vin, accountId)?.shouldFailStatusFetch ?: false

    override suspend fun shouldFailPinValidation(vin: String, accountId: String): Boolean =
        config(vin, accountId)?.shouldFailPinValidation ?: false

    override suspend fun shouldFailCommand(command: VehicleCommand, vin: String, accountId: String): Boolean =
        config(vin, accountId)?.shouldFailCommand(command) ?: false

    override suspend fun getCustomCredentialErrorMessage(accountId: String): String =
        accountConfigs(accountId).firstOrNull()?.customCredentialErrorMessage ?: "Invalid credentials"

    override suspend fun getCustomPinErrorMessage(vin: String, accountId: String): String =
        config(vin, accountId)?.customPinErrorMessage ?: "Invalid PIN"

    // Surround view

    override suspend fun requestSurroundViewCapture(vin: String, accountId: String) {
        requireVehicle(vin, accountId)
        throwIfSurroundViewDisabled(vin, accountId)
        // Both real Canada endpoints post the PIN, so a PIN refusal is the
        // most faithful failure to simulate here.
        if (shouldFailPinValidation(vin, accountId)) {
            throw ApiException.invalidPin(getCustomPinErrorMessage(vin, accountId), apiName = "FakeAPI")
        }
        // Image synthesis is a UI-layer concern; the capture request itself
        // just succeeds (or fails per the debug toggles above).
    }

    private suspend fun throwIfSurroundViewDisabled(vin: String, accountId: String) {
        if (config(vin, accountId)?.shouldFailSurroundView == true) {
            throw ApiException.logError("Surround view is not available for this vehicle", apiName = "FakeAPI")
        }
    }

    // Helpers

    private suspend fun requireVehicle(vin: String, accountId: String): VehicleEntity =
        vehicleDao.getByVin(vin)?.takeIf { it.accountId == accountId }
            ?: throw ApiException.logError("Fake vehicle not found: $vin", apiName = "FakeAPI")

    private suspend fun config(vin: String, accountId: String): DebugConfiguration? =
        vehicleDao.getByVin(vin)?.takeIf { it.accountId == accountId }?.debugConfig()

    private suspend fun accountConfigs(accountId: String): List<DebugConfiguration> =
        vehicleDao.getByAccount(accountId).mapNotNull { it.debugConfig() }

    private fun VehicleEntity.debugConfig(): DebugConfiguration? = debugConfigJson?.let {
        try {
            json.decodeFromString<DebugConfiguration>(it)
        } catch (_: Exception) {
            null
        }
    }

    private fun statusFrom(vehicle: VehicleEntity, nowMillis: Long): VehicleStatus = VehicleStatus(
        vin = vehicle.vin,
        lastUpdated = Instant.ofEpochMilli(vehicle.lastUpdated ?: nowMillis),
        syncDate = Instant.ofEpochMilli(vehicle.syncDate ?: nowMillis),
        gasRange = vehicle.gasRange,
        evStatus = vehicle.evStatus,
        location = vehicle.location ?: VehicleStatus.Location(0.0, 0.0),
        lockStatus = when (vehicle.lockStatus) {
            "locked" -> VehicleStatus.LockStatus.LOCKED
            "unlocked" -> VehicleStatus.LockStatus.UNLOCKED
            else -> VehicleStatus.LockStatus.UNKNOWN
        },
        climateStatus = vehicle.climateStatus ?: VehicleStatus.ClimateStatus(
            defrostOn = false,
            airControlOn = false,
            steeringWheelHeatingOn = false,
            temperature = Temperature(Temperature.Units.FAHRENHEIT, 70.0),
        ),
        odometer = vehicle.odometer,
        battery12V = vehicle.battery12V,
        doorOpen = vehicle.doorOpen,
        trunkOpen = vehicle.trunkOpen,
        hoodOpen = vehicle.hoodOpen,
        tirePressureWarning = vehicle.tirePressureWarning,
        accessoryOn = vehicle.accessoryOn,
    )
}
