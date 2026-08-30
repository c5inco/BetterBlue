package com.betterblue.app.data.db

import androidx.room.TypeConverter
import com.betterblue.app.data.db.entity.DeviceType
import com.betterblue.kit.log.HttpLog
import com.betterblue.kit.model.ClimateOptions
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.VehicleMarketOptions
import com.betterblue.kit.model.VehicleStatus
import kotlinx.serialization.json.Json

/**
 * JSON-in-column converters for the kit's value types. `ignoreUnknownKeys`
 * keeps blob evolution additive; `encodeDefaults` makes new-field defaults
 * explicit in stored payloads.
 */
object Converters {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Distance

    @TypeConverter
    fun distanceToJson(value: Distance?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun distanceFromJson(value: String?): Distance? = value?.let { decodeOrNull(it) }

    // VehicleStatus sub-objects

    @TypeConverter
    fun fuelRangeToJson(value: VehicleStatus.FuelRange?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun fuelRangeFromJson(value: String?): VehicleStatus.FuelRange? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun evStatusToJson(value: VehicleStatus.EvStatus?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun evStatusFromJson(value: String?): VehicleStatus.EvStatus? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun locationToJson(value: VehicleStatus.Location?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun locationFromJson(value: String?): VehicleStatus.Location? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun climateStatusToJson(value: VehicleStatus.ClimateStatus?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun climateStatusFromJson(value: String?): VehicleStatus.ClimateStatus? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun doorStatusToJson(value: VehicleStatus.DoorStatus?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun doorStatusFromJson(value: String?): VehicleStatus.DoorStatus? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun tirePressureToJson(value: VehicleStatus.TirePressureWarning?): String? =
        value?.let { json.encodeToString(it) }

    @TypeConverter
    fun tirePressureFromJson(value: String?): VehicleStatus.TirePressureWarning? = value?.let { decodeOrNull(it) }

    // Market options / climate options / http log

    @TypeConverter
    fun marketOptionsToJson(value: VehicleMarketOptions?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun marketOptionsFromJson(value: String?): VehicleMarketOptions? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun climateOptionsToJson(value: ClimateOptions?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun climateOptionsFromJson(value: String?): ClimateOptions? = value?.let { decodeOrNull(it) }

    @TypeConverter
    fun httpLogToJson(value: HttpLog?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun httpLogFromJson(value: String?): HttpLog? = value?.let { decodeOrNull(it) }

    // Enums

    @TypeConverter
    fun deviceTypeToString(value: DeviceType): String = value.name

    @TypeConverter
    fun deviceTypeFromString(value: String): DeviceType =
        DeviceType.entries.firstOrNull { it.name == value } ?: DeviceType.PHONE

    private inline fun <reified T> decodeOrNull(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        null
    }
}
