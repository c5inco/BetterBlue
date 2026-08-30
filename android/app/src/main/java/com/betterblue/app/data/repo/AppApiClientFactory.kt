package com.betterblue.app.data.repo

import com.betterblue.app.data.fake.RoomFakeVehicleProvider
import com.betterblue.app.data.log.HttpLogSinkManager
import com.betterblue.app.di.AppScope
import com.betterblue.kit.ApiClient
import com.betterblue.kit.ApiClientConfig
import com.betterblue.kit.cache.CachedApiClient
import com.betterblue.kit.createBetterBlueKitApiClient
import com.betterblue.kit.fake.FakeApiClient
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.isTestAccount
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the API client for an account: routes the fake brand (and test
 * credentials) to the Room-backed fake client, everything else to the kit
 * factory, and wraps the result in [CachedApiClient].
 *
 * Every configuration field is forwarded deliberately — `deviceId`,
 * `redactPii`, the Hyundai Canada variant, and the remember-me rotation
 * callback are all load-bearing for session stability; dropping them makes
 * MFA-gated backends treat each initialization as a brand-new device.
 */
@Singleton
class AppApiClientFactory @Inject constructor(
    private val fakeVehicleProvider: RoomFakeVehicleProvider,
    private val logSinkManager: HttpLogSinkManager,
    @AppScope private val appScope: CoroutineScope,
) {
    fun create(
        credentials: AccountCredentials,
        onRememberMeTokenRotated: ((String) -> Unit)? = null,
    ): CachedApiClient {
        val effectiveBrand = if (isTestAccount(credentials.username, credentials.password)) {
            Brand.FAKE
        } else {
            credentials.brand
        }

        val config = ApiClientConfig(
            region = credentials.region,
            brand = effectiveBrand,
            username = credentials.username,
            password = credentials.password,
            refreshToken = credentials.refreshToken,
            pin = credentials.pin,
            accountId = credentials.id,
            logSink = logSinkManager.createLogSink(),
            rememberMeToken = credentials.rememberMeToken,
            deviceId = credentials.deviceId,
            hyundaiCanadaVariant = credentials.hyundaiCanadaVariant,
            onRememberMeTokenRotated = onRememberMeTokenRotated,
        )

        val underlying: ApiClient = if (effectiveBrand == Brand.FAKE) {
            BBLogger.info(BBLogCategory.API, "AppApiClientFactory: creating Room-backed fake API client")
            FakeApiClient(config, fakeVehicleProvider)
        } else {
            BBLogger.info(
                BBLogCategory.API,
                "AppApiClientFactory: creating ${effectiveBrand.displayName} client for ${credentials.region}",
            )
            createBetterBlueKitApiClient(config)
        }

        return CachedApiClient(underlying, appScope)
    }
}
