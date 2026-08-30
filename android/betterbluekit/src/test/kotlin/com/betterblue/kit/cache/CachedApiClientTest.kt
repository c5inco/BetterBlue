package com.betterblue.kit.cache

import com.betterblue.kit.ApiClient
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.FuelType
import com.betterblue.kit.model.Temperature
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private class CountingClient : ApiClient {
    val loginCalls = AtomicInteger(0)
    val statusCalls = AtomicInteger(0)
    val commandCalls = AtomicInteger(0)
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun login(): AuthToken {
        loginCalls.incrementAndGet()
        gate?.await()
        return AuthToken("access", "refresh", Instant.now().plusSeconds(3600))
    }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> = emptyList()

    override suspend fun fetchVehicleStatus(vehicle: Vehicle, authToken: AuthToken, cached: Boolean): VehicleStatus {
        statusCalls.incrementAndGet()
        gate?.await()
        return status(vehicle.vin)
    }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        commandCalls.incrementAndGet()
        gate?.await()
    }

    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> = setOf(OptionalApiFeature.MFA)

    companion object {
        fun status(vin: String) =
            VehicleStatus(
                vin = vin,
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
    }
}

private fun vehicle(vin: String = "VIN1") =
    Vehicle(
        vin = vin,
        regId = "reg",
        model = "model",
        accountId = "acc",
        fuelType = FuelType.ELECTRIC,
        generation = 3,
        odometer = Distance(0.0, Distance.Units.MILES),
    )

private fun token() = AuthToken("a", "r", Instant.now().plusSeconds(3600))

class CachedApiClientTest {
    @Test
    fun `login responses are cached within the ttl`() =
        runTest {
            var now = 0L
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext), clock = { now })

            client.login()
            client.login()
            assertEquals(1, underlying.loginCalls.get())

            // Past the TTL the cache expires.
            now += 6_000
            client.login()
            assertEquals(2, underlying.loginCalls.get())
        }

    @Test
    fun `concurrent identical requests join one in-flight call`() =
        runTest {
            val underlying = CountingClient()
            val gate = CompletableDeferred<Unit>()
            underlying.gate = gate
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))

            val first = async { client.fetchVehicleStatus(vehicle(), token(), cached = true) }
            val second = async { client.fetchVehicleStatus(vehicle(), token(), cached = true) }
            yield()
            gate.complete(Unit)
            first.await()
            second.await()

            assertEquals(1, underlying.statusCalls.get())
        }

    @Test
    fun `cached=false bypasses ttl but still coalesces in-flight requests`() =
        runTest {
            var now = 0L
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext), clock = { now })

            // Prime the cache.
            client.fetchVehicleStatus(vehicle(), token(), cached = true)
            assertEquals(1, underlying.statusCalls.get())

            // A cached read hits the TTL cache…
            client.fetchVehicleStatus(vehicle(), token(), cached = true)
            assertEquals(1, underlying.statusCalls.get())

            // …but a real-time read bypasses it.
            client.fetchVehicleStatus(vehicle(), token(), cached = false)
            assertEquals(2, underlying.statusCalls.get())

            // Two simultaneous real-time reads still only poll the modem once.
            val gate = CompletableDeferred<Unit>()
            underlying.gate = gate
            val first = async { client.fetchVehicleStatus(vehicle(), token(), cached = false) }
            val second = async { client.fetchVehicleStatus(vehicle(), token(), cached = false) }
            yield()
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(3, underlying.statusCalls.get())
        }

    @Test
    fun `statuses for different vins cache independently`() =
        runTest {
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))

            client.fetchVehicleStatus(vehicle("VIN1"), token(), cached = true)
            client.fetchVehicleStatus(vehicle("VIN2"), token(), cached = true)
            assertEquals(2, underlying.statusCalls.get())
        }

    @Test
    fun `commands are never cached but identical concurrent commands dedupe`() =
        runTest {
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))

            client.sendCommand(vehicle(), VehicleCommand.Lock, token())
            client.sendCommand(vehicle(), VehicleCommand.Lock, token())
            assertEquals(2, underlying.commandCalls.get())

            val gate = CompletableDeferred<Unit>()
            underlying.gate = gate
            val first = async { client.sendCommand(vehicle(), VehicleCommand.Lock, token()) }
            val second = async { client.sendCommand(vehicle(), VehicleCommand.Lock, token()) }
            yield()
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(3, underlying.commandCalls.get())
        }

    @Test
    fun `command invalidates the vehicle's cached status`() =
        runTest {
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))

            client.fetchVehicleStatus(vehicle(), token(), cached = true)
            assertEquals(1, underlying.statusCalls.get())

            client.sendCommand(vehicle(), VehicleCommand.Lock, token())

            // The cached entry was invalidated, so this refetches.
            client.fetchVehicleStatus(vehicle(), token(), cached = true)
            assertEquals(2, underlying.statusCalls.get())
        }

    @Test
    fun `a cancelled caller does not kill the shared request`() =
        runTest {
            val underlying = CountingClient()
            val gate = CompletableDeferred<Unit>()
            underlying.gate = gate
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))

            val doomed = async { client.fetchVehicleStatus(vehicle(), token(), cached = true) }
            val survivor = async { client.fetchVehicleStatus(vehicle(), token(), cached = true) }
            yield()
            doomed.cancelAndJoin()
            gate.complete(Unit)

            assertEquals("VIN1", survivor.await().vin)
            assertEquals(1, underlying.statusCalls.get())
        }

    @Test
    fun `capability queries forward to the underlying client`() =
        runTest {
            val underlying = CountingClient()
            val client = CachedApiClient(underlying, CoroutineScope(coroutineContext))
            // Without the forward, the interface default (empty) would answer for
            // the wrapper and hide MFA for every account.
            assertTrue(client.optionalFeaturesSupported().contains(OptionalApiFeature.MFA))
        }
}
