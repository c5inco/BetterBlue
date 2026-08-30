package com.betterblue.kit.policy

import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.VehicleStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetryPolicyTest {
    @Test
    fun `reauthentication fires only on session-invalidating errors`() {
        val shouldReauth =
            setOf(
                ApiErrorType.INVALID_CREDENTIALS,
                ApiErrorType.INVALID_VEHICLE_SESSION,
                ApiErrorType.FAILED_RETRY_LOGIN,
                ApiErrorType.KIA_INVALID_REQUEST,
            )
        // Exhaustive over the taxonomy: a new error type must be classified
        // deliberately rather than inheriting a default.
        for (type in ApiErrorType.entries) {
            assertEquals(type in shouldReauth, shouldReauthenticate(type), "reauth for $type")
        }
    }

    @Test
    fun `command retry is strictly narrower than reauthentication`() {
        // A blind command retry can act on the car twice, so the retry set
        // must never exceed the reauth set.
        for (type in ApiErrorType.entries) {
            if (shouldRetryCommand(type)) {
                assertTrue(shouldReauthenticate(type), "$type is retryable but not reauthable")
            }
        }
    }

    @Test
    fun `kiaInvalidRequest reauthenticates but never re-sends a command`() {
        // Kia's anti-fraud layer can return this AFTER accepting a command;
        // re-sending would risk a second lock/unlock.
        assertTrue(shouldReauthenticate(ApiErrorType.KIA_INVALID_REQUEST))
        assertFalse(shouldRetryCommand(ApiErrorType.KIA_INVALID_REQUEST))
    }

    @Test
    fun `transient failures never touch the session`() {
        for (type in listOf(
            ApiErrorType.SERVER_ERROR,
            ApiErrorType.CONCURRENT_REQUEST,
            ApiErrorType.GENERAL,
            ApiErrorType.INVALID_PIN,
        )) {
            assertFalse(shouldReauthenticate(type), "reauth for $type")
            assertFalse(shouldRetryCommand(type), "retry for $type")
        }
    }

    @Test
    fun `an MFA challenge is not a session failure`() {
        assertFalse(shouldReauthenticate(ApiErrorType.REQUIRES_MFA))
        assertFalse(shouldRetryCommand(ApiErrorType.REQUIRES_MFA))
    }

    @Test
    fun `a status verification timeout is soft and never retried`() {
        // The command was already accepted upstream; retrying would re-issue it.
        assertFalse(shouldReauthenticate(ApiErrorType.STATUS_VERIFICATION_TIMEOUT))
        assertFalse(shouldRetryCommand(ApiErrorType.STATUS_VERIFICATION_TIMEOUT))
    }
}

class FuelTypeInferenceTest {
    private fun status(
        ev: VehicleStatus.EvStatus? = null,
        gas: VehicleStatus.FuelRange? = null,
    ) = VehicleStatus(
        vin = "VIN",
        evStatus = ev,
        gasRange = gas,
        location = VehicleStatus.Location(0.0, 0.0),
        lockStatus = VehicleStatus.LockStatus.LOCKED,
        climateStatus =
            VehicleStatus.ClimateStatus(
                defrostOn = false,
                airControlOn = false,
                steeringWheelHeatingOn = false,
                temperature = Temperature(Temperature.Units.FAHRENHEIT, 70.0),
            ),
    )

    private val evStatus =
        VehicleStatus.EvStatus(
            charging = false,
            chargeSpeed = 0.0,
            evRange = VehicleStatus.FuelRange(Distance(200.0, Distance.Units.MILES), 80.0),
        )

    private val gasRange = VehicleStatus.FuelRange(Distance(300.0, Distance.Units.MILES), 50.0)

    @Test
    fun `payload shape determines the powertrain`() {
        assertEquals(FuelType.PHEV, inferFuelType(status(ev = evStatus, gas = gasRange)))
        assertEquals(FuelType.ELECTRIC, inferFuelType(status(ev = evStatus)))
        assertEquals(FuelType.GAS, inferFuelType(status(gas = gasRange)))
        assertNull(inferFuelType(status()))
    }

    @Test
    fun `upgrades move only toward greater specificity`() {
        assertTrue(isFuelTypeUpgrade(FuelType.GAS, FuelType.ELECTRIC))
        assertTrue(isFuelTypeUpgrade(FuelType.GAS, FuelType.PHEV))
        assertTrue(isFuelTypeUpgrade(FuelType.ELECTRIC, FuelType.PHEV))

        // Never demote, never self-upgrade.
        assertFalse(isFuelTypeUpgrade(FuelType.PHEV, FuelType.ELECTRIC))
        assertFalse(isFuelTypeUpgrade(FuelType.PHEV, FuelType.GAS))
        assertFalse(isFuelTypeUpgrade(FuelType.ELECTRIC, FuelType.GAS))
        for (type in FuelType.entries) assertFalse(isFuelTypeUpgrade(type, type))
    }

    @Test
    fun `a misclassified EV heals from its first status`() {
        // Kia USA reports most vehicles as gas; the status payload corrects it.
        assertEquals(FuelType.ELECTRIC, healedFuelType(FuelType.GAS, status(ev = evStatus)))
    }

    @Test
    fun `a PHEV is never demoted by a partial payload`() {
        // A PHEV that momentarily returns evStatus only must stay a PHEV.
        assertEquals(FuelType.PHEV, healedFuelType(FuelType.PHEV, status(ev = evStatus)))
        assertEquals(FuelType.PHEV, healedFuelType(FuelType.PHEV, status(gas = gasRange)))
        assertEquals(FuelType.PHEV, healedFuelType(FuelType.PHEV, status()))
    }

    @Test
    fun `an empty payload leaves the current type alone`() {
        for (type in FuelType.entries) {
            assertEquals(type, healedFuelType(type, status()))
        }
    }
}
