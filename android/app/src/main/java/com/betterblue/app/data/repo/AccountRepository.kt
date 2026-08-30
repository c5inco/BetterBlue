package com.betterblue.app.data.repo

import com.betterblue.app.data.db.dao.AccountDao
import com.betterblue.app.data.db.dao.VehicleDao
import com.betterblue.app.data.db.entity.AccountEntity
import com.betterblue.app.data.db.entity.VehicleEntity
import com.betterblue.app.data.security.CredentialCipher
import com.betterblue.app.di.AppScope
import com.betterblue.kit.ApiException
import com.betterblue.kit.HyundaiCanadaVariant
import com.betterblue.kit.MfaMethod
import com.betterblue.kit.cache.CachedApiClient
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.AuthToken
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.Vehicle
import com.betterblue.kit.policy.shouldReauthenticate
import com.betterblue.kit.policy.shouldRetryCommand
import com.betterblue.kit.supportsMfa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Decrypted, kit-facing view of a stored account. */
data class AccountCredentials(
    val id: String,
    val username: String,
    val password: String,
    val pin: String,
    val refreshToken: String?,
    val rememberMeToken: String?,
    val brand: Brand,
    val region: Region,
    val deviceId: String?,
    val hyundaiCanadaVariant: HyundaiCanadaVariant,
)

/** Per-account runtime session: the API client and auth token live here, not in the DB row. */
class AccountSession {
    var api: CachedApiClient? = null
    var authToken: AuthToken? = null
    var pendingMfaError: ApiException? = null
    val mutex = Mutex()
}

/**
 * Owns account lifecycle: login, session persistence, MFA, and the vehicle
 * list sync. This is the iOS `BBAccount` behavior split off the model.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val accountDao: AccountDao,
        private val vehicleDao: VehicleDao,
        private val cipher: CredentialCipher,
        private val clientFactory: AppApiClientFactory,
        @AppScope private val appScope: CoroutineScope,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        private val sessionsMutex = Mutex()
        private val sessions = mutableMapOf<String, AccountSession>()

        fun observeAccounts() = accountDao.observeAll()

        suspend fun getAccount(id: String): AccountEntity? = accountDao.getById(id)

        private suspend fun session(accountId: String): AccountSession =
            sessionsMutex.withLock {
                sessions.getOrPut(accountId) { AccountSession() }
            }

        fun credentials(entity: AccountEntity): AccountCredentials =
            AccountCredentials(
                id = entity.id,
                username = entity.username,
                password = cipher.decrypt(entity.encryptedPassword),
                pin = cipher.decrypt(entity.encryptedPin),
                refreshToken = cipher.decryptOrNull(entity.encryptedRefreshToken)?.takeUnless { it.isEmpty() },
                rememberMeToken = cipher.decryptOrNull(entity.encryptedRememberMeToken),
                brand = brandFromRaw(entity.brand),
                region = regionFromRaw(entity.region),
                deviceId = entity.deviceId,
                hyundaiCanadaVariant =
                    entity.hyundaiCanadaVariant?.let { raw ->
                        if (raw == "nativeApp") HyundaiCanadaVariant.NATIVE_APP else HyundaiCanadaVariant.WEB_PORTAL
                    } ?: HyundaiCanadaVariant.DEFAULT,
            )

        /** Creates and persists a new account, then initializes it (login / MFA challenge). */
        suspend fun addAccount(
            username: String,
            password: String,
            pin: String,
            refreshToken: String?,
            brand: Brand,
            region: Region,
            hyundaiCanadaVariant: HyundaiCanadaVariant = HyundaiCanadaVariant.DEFAULT,
        ): AccountEntity {
            val entity =
                AccountEntity(
                    id = UUID.randomUUID().toString(),
                    username = username,
                    encryptedPassword = cipher.encrypt(password),
                    encryptedRefreshToken = cipher.encryptOrNull(refreshToken),
                    encryptedPin = cipher.encrypt(pin),
                    brand = brand.toRaw(),
                    region = region.toRaw(),
                    dateCreated = System.currentTimeMillis(),
                    hyundaiCanadaVariant =
                        if (hyundaiCanadaVariant ==
                            HyundaiCanadaVariant.NATIVE_APP
                        ) {
                            "nativeApp"
                        } else {
                            "webPortal"
                        },
                )
            accountDao.upsert(entity)
            return entity
        }

        suspend fun updateAccount(id: String, password: String, pin: String, refreshToken: String?) {
            val entity = accountDao.getById(id) ?: return
            accountDao.update(
                entity.copy(
                    encryptedPassword = cipher.encrypt(password),
                    encryptedPin = cipher.encrypt(pin),
                    encryptedRefreshToken = cipher.encryptOrNull(refreshToken),
                ),
            )
        }

        suspend fun removeAccount(id: String) {
            accountDao.getById(id)?.let { accountDao.delete(it) }
            sessionsMutex.withLock { sessions.remove(id) }
        }

        /**
         * Ensures the account has a live API client and a valid auth token,
         * performing device registration and login as needed. Port of iOS
         * `BBAccount.initialize(modelContext:)`.
         */
        suspend fun initialize(accountId: String) {
            val session = session(accountId)
            session.mutex.withLock { initializeLocked(accountId, session) }
        }

        private suspend fun initializeLocked(accountId: String, session: AccountSession) {
            // If MFA is pending, surface that instead of using a stale token.
            session.pendingMfaError?.let { mfaError ->
                BBLogger.info(BBLogCategory.AUTH, "AccountRepository: MFA is pending, re-throwing MFA error")
                throw mfaError
            }

            val entity =
                accountDao.getById(accountId)
                    ?: throw ApiException("Account not found", apiName = "AccountRepository")

            if (session.api == null) {
                session.api =
                    clientFactory.create(
                        credentials(entity),
                        onRememberMeTokenRotated = { newToken ->
                            // Persist the rotated token so subsequent logins present it.
                            appScope.launch {
                                val current = accountDao.getById(accountId) ?: return@launch
                                val existing = cipher.decryptOrNull(current.encryptedRememberMeToken)
                                if (existing != newToken) {
                                    accountDao.update(current.copy(encryptedRememberMeToken = cipher.encrypt(newToken)))
                                    BBLogger.info(BBLogCategory.AUTH, "AccountRepository: persisted rotated rmToken")
                                }
                            }
                        },
                    )
            }

            // Reuse a valid persisted token
            session.authToken = session.authToken ?: loadPersistedToken(entity)
            session.authToken?.let { existing ->
                if (existing.isValid) {
                    BBLogger.info(
                        BBLogCategory.AUTH,
                        "AccountRepository: using persisted auth token (expires: ${existing.expiresAt})",
                    )
                    return
                }
            }

            BBLogger.info(BBLogCategory.AUTH, "AccountRepository: no valid token, performing login...")
            val api = session.api ?: throw ApiException("API client not initialized")

            try {
                // A stable device id is what MFA-gated backends recognize on the
                // next login; persist it immediately so a process death can't
                // lose it and force a fresh OTP challenge.
                if (entity.deviceId == null) {
                    val deviceId = api.registerDevice()
                    if (deviceId != null) {
                        accountDao.update((accountDao.getById(accountId) ?: entity).copy(deviceId = deviceId))
                    }
                }

                val token = api.login()
                session.authToken = token
                session.pendingMfaError = null

                val fresh = accountDao.getById(accountId) ?: entity
                accountDao.update(
                    fresh.copy(
                        encryptedAuthToken = cipher.encrypt(json.encodeToString(token)),
                        // The refresh token can be used for login when the access
                        // token expires (EU accounts especially).
                        encryptedRefreshToken = cipher.encrypt(token.refreshToken),
                    ),
                )
            } catch (e: ApiException) {
                if (e.errorType == ApiErrorType.REQUIRES_MFA) {
                    // Remember the challenge so parallel operations know MFA is required.
                    session.pendingMfaError = e
                }
                throw e
            }
        }

        /**
         * Clears the cached session and forces a fresh login. FULL reset: the
         * remember-me token and device id are dropped too — they're exactly what
         * made "reset" insufficient when they'd gone stale. Credentials and the
         * refresh token are preserved.
         */
        suspend fun resetSession(accountId: String) {
            val session = session(accountId)
            session.mutex.withLock {
                BBLogger.info(BBLogCategory.AUTH, "AccountRepository: resetting session for $accountId")
                session.api = null
                session.authToken = null
                session.pendingMfaError = null
                accountDao.getById(accountId)?.let { entity ->
                    accountDao.update(
                        entity.copy(
                            encryptedAuthToken = null,
                            encryptedRememberMeToken = null,
                            deviceId = null,
                        ),
                    )
                }
                initializeLocked(accountId, session)
            }
        }

        suspend fun sendMfa(accountId: String, otpKey: String, xid: String, method: MfaMethod = MfaMethod.SMS) {
            // Don't re-initialize during the MFA flow — that would trigger a new
            // login and reset the otpKey. Use the client from the login attempt
            // that raised the challenge.
            val session = session(accountId)
            val api =
                session.api?.underlyingClient
                    ?: throw ApiException(
                        "API not initialized. Please try logging in again.",
                        apiName = "AccountRepository",
                    )
            if (!api.supportsMfa()) {
                throw ApiException("MFA not supported for this brand", apiName = "AccountRepository")
            }
            api.sendMfaCode(xid = xid, otpKey = otpKey, method = method)
        }

        suspend fun verifyMfa(accountId: String, otpKey: String, xid: String, otp: String) {
            val session = session(accountId)
            val api =
                session.api?.underlyingClient
                    ?: throw ApiException(
                        "API not initialized. Please try logging in again.",
                        apiName = "AccountRepository",
                    )
            if (!api.supportsMfa()) {
                throw ApiException("MFA not supported for this brand", apiName = "AccountRepository")
            }

            val verification = api.verifyMfaCode(xid = xid, otpKey = otpKey, code = otp)
            val finalToken = api.completeMfaLogin(sid = verification.sid, rmToken = verification.rememberMeToken)

            session.authToken = finalToken
            session.pendingMfaError = null

            // Persist the just-earned session immediately — remember-me token,
            // auth token, and the device id the backend just accepted.
            accountDao.getById(accountId)?.let { entity ->
                accountDao.update(
                    entity.copy(
                        encryptedRememberMeToken = cipher.encrypt(verification.rememberMeToken),
                        encryptedAuthToken = cipher.encrypt(json.encodeToString(finalToken)),
                    ),
                )
            }
            BBLogger.info(BBLogCategory.MFA, "AccountRepository: MFA complete")
        }

        /** See the kit's `shouldReauthenticate` — exhaustively tested there. */
        fun shouldReauthenticate(error: ApiException): Boolean = shouldReauthenticate(error.errorType)

        /** See the kit's `shouldRetryCommand` — deliberately narrower than reauth. */
        fun shouldRetryCommand(error: ApiException): Boolean = shouldRetryCommand(error.errorType)

        /**
         * Full re-initialization after a session-invalidating error.
         * Deliberately KEEPS `deviceId` and `rememberMeToken` — they are
         * device-trust anchors, not session artifacts: clearing them on a
         * routine expiry made every re-auth look like a brand-new device, so
         * users re-verified MFA on each launch. Only the explicit
         * [resetSession] drops them.
         */
        suspend fun handleApiError(accountId: String, error: ApiException) {
            BBLogger.info(
                BBLogCategory.API,
                "AccountRepository: API error (${error.errorType}) detected, performing full re-initialization...",
            )
            val session = session(accountId)
            session.mutex.withLock {
                session.api?.clearCache()
                session.api = null
                session.authToken = null
                accountDao.getById(accountId)?.let { accountDao.update(it.copy(encryptedAuthToken = null)) }
                initializeLocked(accountId, session)
            }

            val (api, token) = requireSession(accountId)
            updateVehicles(accountId, api.fetchVehicles(token))
            BBLogger.info(BBLogCategory.API, "AccountRepository: re-initialization complete")
        }

        /** The live client + token, initializing if needed. */
        suspend fun requireSession(accountId: String): Pair<CachedApiClient, AuthToken> {
            val session = session(accountId)
            val api = session.api
            val token = session.authToken
            if (api != null && token != null) return api to token
            initialize(accountId)
            val after = session(accountId)
            return (after.api ?: throw ApiException.failedRetryLogin()) to
                (after.authToken ?: throw ApiException.failedRetryLogin())
        }

        suspend fun peekSession(accountId: String): AccountSession = session(accountId)

        /** Fetches the vehicle list and syncs it into the database. */
        suspend fun loadVehicles(accountId: String) {
            val (api, token) = requireSession(accountId)
            try {
                updateVehicles(accountId, api.fetchVehicles(token))
            } catch (e: ApiException) {
                if (!shouldReauthenticate(e)) throw e
                handleApiError(accountId, e)
            }
        }

        /**
         * Three-pass diff of the fetched vehicle list into storage, preserving
         * UI state (custom names, colors, sort order) on existing rows. Port of
         * iOS `BBAccount.updateVehicles`.
         */
        suspend fun updateVehicles(accountId: String, vehicles: List<Vehicle>) {
            val existing = vehicleDao.getByAccount(accountId).associateBy { it.vin }
            val maxSortOrder = vehicleDao.maxSortOrder() ?: 0
            val processedVins = mutableSetOf<String>()

            vehicles.forEachIndexed { index, vehicle ->
                processedVins.add(vehicle.vin)
                val current = existing[vehicle.vin]
                if (current != null) {
                    // Update core data, preserve UI state.
                    vehicleDao.update(
                        current.copy(
                            regId = vehicle.regId,
                            model = vehicle.model,
                            fuelTypeRaw = vehicle.fuelType.toRaw(),
                            generation = vehicle.generation,
                            odometer = vehicle.odometer,
                            vehicleKey = vehicle.vehicleKey,
                            marketOptions = vehicle.marketOptions,
                        ),
                    )
                } else {
                    vehicleDao.upsert(
                        VehicleEntity(
                            vin = vehicle.vin,
                            regId = vehicle.regId,
                            model = vehicle.model,
                            accountId = accountId,
                            fuelTypeRaw = vehicle.fuelType.toRaw(),
                            generation = vehicle.generation,
                            odometer = vehicle.odometer,
                            vehicleKey = vehicle.vehicleKey,
                            marketOptions = vehicle.marketOptions,
                            sortOrder = maxSortOrder + index + 1,
                        ),
                    )
                }
            }

            // Remove vehicles no longer returned by the API.
            for (stale in existing.values) {
                if (stale.vin !in processedVins) vehicleDao.deleteByVin(stale.vin)
            }

            // Mark the list fresh — drives the staleness check before background
            // status fetches.
            accountDao.getById(accountId)?.let {
                accountDao.update(it.copy(lastVehiclesFetch = System.currentTimeMillis()))
            }
        }

        private fun loadPersistedToken(entity: AccountEntity): AuthToken? {
            val serialized = cipher.decryptOrNull(entity.encryptedAuthToken) ?: return null
            return try {
                json.decodeFromString<AuthToken>(serialized)
            } catch (_: Exception) {
                null
            }
        }

        companion object {
            /** Max age of the cached vehicle list before it's refetched alongside a status request. */
            val VEHICLE_LIST_MAX_AGE_MILLIS: Long = 2 * 3600 * 1000L

            fun brandFromRaw(raw: String): Brand =
                when (raw) {
                    "kia" -> Brand.KIA
                    "fake" -> Brand.FAKE
                    else -> Brand.HYUNDAI
                }

            fun regionFromRaw(raw: String): Region =
                Region.entries.firstOrNull { it.toRaw() == raw } ?: Region.USA
        }
    }

fun Brand.toRaw(): String =
    when (this) {
        Brand.HYUNDAI -> "hyundai"
        Brand.KIA -> "kia"
        Brand.FAKE -> "fake"
    }

fun Region.toRaw(): String =
    when (this) {
        Region.USA -> "US"
        Region.CANADA -> "CA"
        Region.EUROPE -> "EU"
        Region.AUSTRALIA -> "AU"
        Region.CHINA -> "CN"
        Region.INDIA -> "IN"
    }

fun Instant.toEpochMilliOrNull(): Long = toEpochMilli()
