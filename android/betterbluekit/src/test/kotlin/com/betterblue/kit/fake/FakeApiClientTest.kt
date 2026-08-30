package com.betterblue.kit.fake

import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import com.betterblue.kit.supportsSurroundView
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Behavior coverage for the fake client. The Swift test suite has no
 * dedicated FakeAPIClient tests (only a factory test outside this port's
 * scope), so these tests pin the Swift implementation's documented behavior:
 * the failure-injection order, the error types/messages, and the delegation
 * to the provider.
 */
class FakeApiClientTest {

    private class TestProvider : FakeVehicleProvider {
        var failCredentialValidation = false
        var failLogin = false
        var failVehicleFetch = false
        var failStatusFetch = false
        var failPinValidation = false
        var failCommand = false
        var credentialErrorMessage = "Invalid username or password"
        var pinErrorMessage = "Invalid PIN"

        val executedCommands = mutableListOf<VehicleCommand>()
        var surroundViewRequests = 0

        override suspend fun getFakeVehicles(username: String, accountId: String): List<Vehicle> =
            listOf(testVehicle(accountId))

        override suspend fun getVehicleStatus(vin: String, accountId: String): VehicleStatus = VehicleStatus(
            vin = vin,
            location = VehicleStatus.Location(0.0, 0.0),
            lockStatus = VehicleStatus.LockStatus.LOCKED,
            climateStatus = VehicleStatus.ClimateStatus(
                defrostOn = false,
                airControlOn = false,
                steeringWheelHeatingOn = false,
                temperature = Temperature(Temperature.Units.FAHRENHEIT, 72.0),
            ),
        )

        override suspend fun executeCommand(command: VehicleCommand, vin: String, accountId: String) {
            executedCommands.add(command)
        }

        override suspend fun shouldFailCredentialValidation(accountId: String) = failCredentialValidation
        override suspend fun shouldFailLogin(accountId: String) = failLogin
        override suspend fun shouldFailVehicleFetch(accountId: String) = failVehicleFetch
        override suspend fun shouldFailStatusFetch(vin: String, accountId: String) = failStatusFetch
        override suspend fun shouldFailPinValidation(vin: String, accountId: String) = failPinValidation
        override suspend fun shouldFailCommand(command: VehicleCommand, vin: String, accountId: String) = failCommand
        override suspend fun getCustomCredentialErrorMessage(accountId: String) = credentialErrorMessage
        override suspend fun getCustomPinErrorMessage(vin: String, accountId: String) = pinErrorMessage

        override suspend fun requestSurroundViewCapture(vin: String, accountId: String) {
            surroundViewRequests += 1
        }
    }

    private companion object {
        const val ACCOUNT_ID = "00000000-0000-0000-0000-000000000001"

        fun testVehicle(accountId: String = ACCOUNT_ID) = Vehicle(
            vin = "FAKEVIN0000000001",
            regId = "reg",
            model = "Fake EV",
            accountId = accountId,
            fuelType = FuelType.ELECTRIC,
            generation = 3,
            odometer = Distance(1234.0, Distance.Units.KILOMETERS),
        )

        fun token() = AuthToken("access", "refresh", Instant.now().plusSeconds(3600))
    }

    private fun makeClient(provider: TestProvider) = FakeApiClient(
        configuration = ApiClientConfig(
            region = Region.USA,
            brand = Brand.FAKE,
            username = "testaccount@betterblue.com",
            password = "betterblue",
            pin = "1234",
            accountId = ACCOUNT_ID,
        ),
        vehicleProvider = provider,
    )

    // Login

    @Test
    fun `login returns a fresh fake token`() = runTest {
        val auth = makeClient(TestProvider()).login()
        assertTrue(auth.accessToken.startsWith("fake_access_token_"))
        assertTrue(auth.refreshToken.startsWith("fake_refresh_token_"))
        assertTrue(auth.isValid)
    }

    @Test
    fun `credential validation failure throws invalid credentials with the custom message`() = runTest {
        val provider = TestProvider().apply {
            failCredentialValidation = true
            credentialErrorMessage = "Custom credential error"
        }
        val error = assertThrows<ApiException> { makeClient(provider).login() }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, error.errorType)
        assertEquals("Custom credential error", error.message)
    }

    /** Credential validation is checked BEFORE the generic login toggle. */
    @Test
    fun `credential validation failure takes precedence over login failure`() = runTest {
        val provider = TestProvider().apply {
            failCredentialValidation = true
            failLogin = true
        }
        val error = assertThrows<ApiException> { makeClient(provider).login() }
        assertEquals(ApiErrorType.INVALID_CREDENTIALS, error.errorType)
    }

    @Test
    fun `login failure throws a general error`() = runTest {
        val provider = TestProvider().apply { failLogin = true }
        val error = assertThrows<ApiException> { makeClient(provider).login() }
        assertEquals(ApiErrorType.GENERAL, error.errorType)
        assertEquals("Debug: Simulated login failure", error.message)
        assertEquals(500, error.code)
    }

    // Vehicles

    @Test
    fun `fetchVehicles delegates to the provider`() = runTest {
        val vehicles = makeClient(TestProvider()).fetchVehicles(token())
        assertEquals(1, vehicles.size)
        assertEquals("FAKEVIN0000000001", vehicles.first().vin)
    }

    @Test
    fun `vehicle fetch failure throws`() = runTest {
        val provider = TestProvider().apply { failVehicleFetch = true }
        val error = assertThrows<ApiException> { makeClient(provider).fetchVehicles(token()) }
        assertEquals("Debug: Simulated vehicle fetch failure", error.message)
    }

    // Status

    @Test
    fun `fetchVehicleStatus delegates to the provider`() = runTest {
        val status = makeClient(TestProvider()).fetchVehicleStatus(testVehicle(), token())
        assertEquals("FAKEVIN0000000001", status.vin)
        assertEquals(VehicleStatus.LockStatus.LOCKED, status.lockStatus)
    }

    @Test
    fun `status fetch failure throws`() = runTest {
        val provider = TestProvider().apply { failStatusFetch = true }
        val error = assertThrows<ApiException> {
            makeClient(provider).fetchVehicleStatus(testVehicle(), token())
        }
        assertEquals("Debug: Simulated status fetch failure", error.message)
    }

    // Commands

    @Test
    fun `sendCommand executes via the provider on success`() = runTest {
        val provider = TestProvider()
        makeClient(provider).sendCommand(testVehicle(), VehicleCommand.Lock, token())
        assertEquals(listOf<VehicleCommand>(VehicleCommand.Lock), provider.executedCommands)
    }

    @Test
    fun `pin validation failure throws invalid pin with the custom message`() = runTest {
        val provider = TestProvider().apply {
            failPinValidation = true
            pinErrorMessage = "Custom PIN error"
        }
        val error = assertThrows<ApiException> {
            makeClient(provider).sendCommand(testVehicle(), VehicleCommand.Lock, token())
        }
        assertEquals(ApiErrorType.INVALID_PIN, error.errorType)
        assertEquals("Custom PIN error", error.message)
        assertTrue(provider.executedCommands.isEmpty())
    }

    /** PIN validation is checked BEFORE the command-specific toggle. */
    @Test
    fun `pin validation failure takes precedence over command failure`() = runTest {
        val provider = TestProvider().apply {
            failPinValidation = true
            failCommand = true
        }
        val error = assertThrows<ApiException> {
            makeClient(provider).sendCommand(testVehicle(), VehicleCommand.Lock, token())
        }
        assertEquals(ApiErrorType.INVALID_PIN, error.errorType)
    }

    @Test
    fun `command failure names the command in the message`() = runTest {
        val provider = TestProvider().apply { failCommand = true }
        val error = assertThrows<ApiException> {
            makeClient(provider).sendCommand(
                testVehicle(),
                VehicleCommand.StartClimate(com.betterblue.kit.model.ClimateOptions()),
                token(),
            )
        }
        assertEquals("Debug: Simulated startClimate failure", error.message)
        assertTrue(provider.executedCommands.isEmpty())
    }

    // Surround view

    @Test
    fun `fake client advertises surround view`() {
        val client = makeClient(TestProvider())
        assertEquals(setOf(OptionalApiFeature.SURROUND_VIEW), client.optionalFeaturesSupported())
        assertTrue(client.supportsSurroundView())
    }

    @Test
    fun `surround view request delegates to the provider`() = runTest {
        val provider = TestProvider()
        makeClient(provider).requestSurroundViewCapture(testVehicle(), token())
        assertEquals(1, provider.surroundViewRequests)
    }

    @Test
    fun `surround view captures default to empty via the provider default`() = runTest {
        val captures: List<SurroundViewCapture> =
            makeClient(TestProvider()).fetchSurroundViewCaptures(testVehicle(), token())
        assertTrue(captures.isEmpty())
    }
}
