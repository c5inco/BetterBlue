package com.betterblue.app.data.log

import com.betterblue.app.data.db.dao.HttpLogDao
import com.betterblue.app.data.db.entity.DeviceType
import com.betterblue.app.data.db.entity.HttpLogEntity
import com.betterblue.app.data.settings.AppSettings
import com.betterblue.app.di.AppScope
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.log.HttpLogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds [HttpLogSink]s for API clients. Logs are persisted only while debug
 * mode is enabled, and the table is capped at 100 rows / trimmed to 50 —
 * same policy as the iOS HTTPLogSinkManager.
 */
@Singleton
class HttpLogSinkManager @Inject constructor(
    private val httpLogDao: HttpLogDao,
    private val settings: AppSettings,
    @AppScope private val scope: CoroutineScope,
) {
    fun createLogSink(deviceType: DeviceType = DeviceType.PHONE): HttpLogSink = HttpLogSink { log ->
        scope.launch {
            try {
                if (!settings.isDebugModeEnabled()) return@launch
                httpLogDao.insertCapped(
                    HttpLogEntity(
                        timestamp = log.timestamp.toEpochMilli(),
                        accountId = log.accountId,
                        vin = log.vin,
                        requestType = log.requestType.name,
                        responseStatus = log.responseStatus,
                        isSuccess = log.isSuccess,
                        deviceType = deviceType,
                        log = log,
                    ),
                )
            } catch (e: Exception) {
                BBLogger.warning(BBLogCategory.API, "HttpLogSinkManager: failed to persist log: $e")
            }
        }
    }

    fun observeLogs() = httpLogDao.observeAll()

    suspend fun clearLogs() = httpLogDao.deleteAll()
}
