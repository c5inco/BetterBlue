package com.betterblue.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.betterblue.kit.log.HttpLog

enum class DeviceType(val displayName: String) {
    PHONE("Phone"),
    TABLET("Tablet"),
    WIDGET("Widget"),
    WEAR("Wear"),
}

/**
 * One persisted HTTP log row. The full [HttpLog] is stored as JSON; the extra
 * columns exist for list filtering and trimming without deserializing bodies.
 */
@Entity(
    tableName = "http_logs",
    indices = [Index("timestamp")],
)
data class HttpLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val accountId: String,
    val vin: String?,
    /** HttpRequestType serial name. */
    val requestType: String,
    val responseStatus: Int?,
    val isSuccess: Boolean,
    val deviceType: DeviceType,
    val log: HttpLog,
)
