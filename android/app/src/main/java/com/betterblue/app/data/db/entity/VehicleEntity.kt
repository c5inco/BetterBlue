package com.betterblue.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.betterblue.kit.model.Distance
import com.betterblue.kit.model.VehicleMarketOptions
import com.betterblue.kit.model.VehicleStatus

/**
 * One vehicle, combining API identity, the latest fetched status, and
 * user-facing UI preferences. Status sub-objects are stored as JSON blobs via
 * type converters — they're read and written atomically with the row and
 * never queried by sub-field.
 */
@Entity(
    tableName = "vehicles",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("accountId")],
)
data class VehicleEntity(
    @PrimaryKey val vin: String,
    val regId: String,
    val model: String,
    val accountId: String,
    /** FuelType serial name — the self-healed/inferred value. */
    val fuelTypeRaw: String,
    val generation: Int,
    val odometer: Distance,
    val vehicleKey: String? = null,
    val marketOptions: VehicleMarketOptions? = null,
    // Latest status (all nullable — may never have been fetched)
    val lastUpdated: Long? = null,
    val syncDate: Long? = null,
    val gasRange: VehicleStatus.FuelRange? = null,
    val evStatus: VehicleStatus.EvStatus? = null,
    val location: VehicleStatus.Location? = null,
    /** LockStatus serial name. */
    val lockStatus: String? = null,
    val climateStatus: VehicleStatus.ClimateStatus? = null,
    val battery12V: Int? = null,
    val doorOpen: VehicleStatus.DoorStatus? = null,
    val trunkOpen: Boolean? = null,
    val hoodOpen: Boolean? = null,
    val tirePressureWarning: VehicleStatus.TirePressureWarning? = null,
    /**
     * Accessory power: true whenever the vehicle is "on" — engine running in
     * an ICE car, high-voltage system live in an EV.
     */
    val accessoryOn: Boolean? = null,
    // UI preferences
    val customName: String? = null,
    val isHidden: Boolean = false,
    val sortOrder: Int = 0,
    /** ChargePortType raw value: CCS1 | CCS2 | NACS. */
    val chargePortType: String = "CCS1",
    val enableSeatHeatControls: Boolean = false,
    /** null = automatic per-generation default; true/false force it. */
    val surroundViewOverride: Boolean? = null,
    /** null = regional default (USA hides the duration picker pre-gen3). */
    val showClimateDurationOverride: Boolean? = null,
    /** User override pinning the powertrain; null = trust the inferred value. */
    val fuelTypeOverrideRaw: String? = null,
    // Per-vehicle accent colors (palette names; null = per-slot default)
    val primaryColorName: String? = null,
    val chargingColorName: String? = null,
    val gasColorName: String? = null,
    val lockColorName: String? = null,
    val unlockColorName: String? = null,
    val startClimateColorName: String? = null,
    val stopColorName: String? = null,
    /** JSON-encoded DebugConfiguration for fake vehicles; null otherwise. */
    val debugConfigJson: String? = null,
)
