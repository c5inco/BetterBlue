package com.betterblue.kit.cache

import com.betterblue.kit.ApiClient
import com.betterblue.kit.OptionalApiFeature
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.SurroundViewCapture
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.model.VehicleCommand
import com.betterblue.kit.model.VehicleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A caching and request-deduplication decorator over an [ApiClient].
 *
 * - 5-second TTL cache for login / fetchVehicles / fetchVehicleStatus(vin).
 * - Concurrent identical calls join one in-flight request (`cached = false`
 *   bypasses the TTL — a recent widget refresh must not swallow a manual
 *   sync — but still coalesces in-flight work so a double-tap doesn't
 *   double-poll the vehicle's modem).
 * - Commands are never cached, invalidate the vehicle's status entry before
 *   sending, and are deduped per (vin, command).
 *
 * Shared work runs on [scope] (an app-scoped SupervisorJob) so a cancelled
 * caller doesn't kill a request another caller is awaiting.
 */
class CachedApiClient(
    val underlyingClient: ApiClient,
    private val scope: CoroutineScope,
    private val ttlMillis: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiClient {
    private sealed interface Key {
        data object Login : Key

        data object FetchVehicles : Key

        data class FetchStatus(
            val vin: String,
        ) : Key

        data class Command(
            val vin: String,
            val command: String,
        ) : Key
    }

    private class CacheEntry(
        val response: Any,
        val timestamp: Long,
    )

    private val mutex = Mutex()
    private val cache = mutableMapOf<Key, CacheEntry>()
    private val ongoing = mutableMapOf<Key, Deferred<Any?>>()

    private suspend fun <T : Any> cachedOrJoin(
        key: Key,
        useCache: Boolean,
        invalidateFirst: Boolean = false,
        fetch: suspend () -> T,
    ): T {
        val toAwait: Deferred<Any?> =
            mutex.withLock {
                if (invalidateFirst) cache.remove(key)

                if (useCache) {
                    val entry = cache[key]
                    if (entry != null && clock() - entry.timestamp < ttlMillis) {
                        BBLogger.debug(BBLogCategory.API, "CachedApiClient: using cached response for $key")
                        @Suppress("UNCHECKED_CAST")
                        return entry.response as T
                    }
                }

                ongoing[key]?.let {
                    BBLogger.debug(BBLogCategory.API, "CachedApiClient: joining in-flight request for $key")
                    it
                } ?: run {
                    BBLogger.debug(BBLogCategory.API, "CachedApiClient: performing new request for $key")
                    val deferred: Deferred<Any?> =
                        scope.async {
                            try {
                                val result = fetch()
                                mutex.withLock { cache[key] = CacheEntry(result, clock()) }
                                result
                            } finally {
                                mutex.withLock { ongoing.remove(key) }
                            }
                        }
                    ongoing[key] = deferred
                    deferred
                }
            }

        @Suppress("UNCHECKED_CAST")
        return toAwait.await() as T
    }

    override suspend fun login(): AuthToken =
        cachedOrJoin(Key.Login, useCache = true) { underlyingClient.login() }

    override suspend fun fetchVehicles(authToken: AuthToken): List<Vehicle> =
        cachedOrJoin(Key.FetchVehicles, useCache = true) { underlyingClient.fetchVehicles(authToken) }

    override suspend fun fetchVehicleStatus(
        vehicle: Vehicle,
        authToken: AuthToken,
        cached: Boolean,
    ): VehicleStatus =
        cachedOrJoin(
            Key.FetchStatus(vehicle.vin),
            useCache = cached,
            // A real-time request also invalidates any stale entry so later
            // callers see fresh data.
            invalidateFirst = !cached,
        ) { underlyingClient.fetchVehicleStatus(vehicle, authToken, cached) }

    override suspend fun sendCommand(vehicle: Vehicle, command: VehicleCommand, authToken: AuthToken) {
        val key = Key.Command(vehicle.vin, command.toString())

        val toAwait: Deferred<Any?> =
            mutex.withLock {
                ongoing[key]?.let {
                    BBLogger.debug(BBLogCategory.API, "CachedApiClient: joining in-flight command for ${vehicle.vin}")
                    it
                } ?: run {
                    // Invalidate cached status before sending so subsequent
                    // fetches reflect the command's effect.
                    cache.remove(Key.FetchStatus(vehicle.vin))
                    val deferred: Deferred<Any?> =
                        scope.async {
                            try {
                                underlyingClient.sendCommand(vehicle, command, authToken)
                                null
                            } finally {
                                mutex.withLock { ongoing.remove(key) }
                            }
                        }
                    ongoing[key] = deferred
                    deferred
                }
            }
        toAwait.await()
    }

    // Pure forwards — never cached

    override suspend fun fetchEvTripSummary(vehicle: Vehicle, authToken: AuthToken) =
        underlyingClient.fetchEvTripSummary(vehicle, authToken)

    override suspend fun fetchEvTripInfo(vehicle: Vehicle, authToken: AuthToken, date: java.time.LocalDate) =
        underlyingClient.fetchEvTripInfo(vehicle, authToken, date)

    override suspend fun requestSurroundViewCapture(vehicle: Vehicle, authToken: AuthToken) =
        underlyingClient.requestSurroundViewCapture(vehicle, authToken)

    override suspend fun fetchSurroundViewCaptures(vehicle: Vehicle, authToken: AuthToken): List<SurroundViewCapture> =
        underlyingClient.fetchSurroundViewCaptures(vehicle, authToken)

    /** Without this forward the interface default (empty) would answer for the wrapper. */
    override fun optionalFeaturesSupported(): Set<OptionalApiFeature> =
        underlyingClient.optionalFeaturesSupported()

    override suspend fun sendMfaCode(xid: String, otpKey: String, method: com.betterblue.kit.MfaMethod) =
        underlyingClient.sendMfaCode(xid, otpKey, method)

    override suspend fun verifyMfaCode(xid: String, otpKey: String, code: String) =
        underlyingClient.verifyMfaCode(xid, otpKey, code)

    override suspend fun completeMfaLogin(sid: String, rmToken: String): AuthToken =
        underlyingClient.completeMfaLogin(sid, rmToken)

    override suspend fun registerDevice(): String? = underlyingClient.registerDevice()

    suspend fun invalidateStatusCache(vin: String) {
        mutex.withLock { cache.remove(Key.FetchStatus(vin)) }
    }

    suspend fun clearCache() {
        mutex.withLock { cache.clear() }
        BBLogger.debug(BBLogCategory.API, "CachedApiClient: cache cleared")
    }
}
