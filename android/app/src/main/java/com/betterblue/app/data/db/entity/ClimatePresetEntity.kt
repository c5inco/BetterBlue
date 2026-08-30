package com.betterblue.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.betterblue.kit.model.ClimateOptions

@Entity(
    tableName = "climate_presets",
    foreignKeys = [
        ForeignKey(
            entity = VehicleEntity::class,
            parentColumns = ["vin"],
            childColumns = ["vehicleVin"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vehicleVin")],
)
data class ClimatePresetEntity(
    @PrimaryKey val id: String,
    val vehicleVin: String,
    val name: String,
    val iconName: String,
    val climateOptions: ClimateOptions,
    val isSelected: Boolean = false,
    val sortOrder: Int = 0,
)

/** Icons offered by the preset editor (Material icon keys). */
object ClimatePresetIcons {
    val available: List<Pair<String, String>> =
        listOf(
            "fan" to "Fan",
            "thermometer" to "Thermometer",
            "snowflake" to "Snowflake",
            "sun" to "Sun",
            "wind" to "Wind",
            "cloud_snow" to "Snow",
        )
}
