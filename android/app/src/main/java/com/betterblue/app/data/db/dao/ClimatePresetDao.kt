package com.betterblue.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.betterblue.app.data.db.entity.ClimatePresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClimatePresetDao {
    @Query("SELECT * FROM climate_presets WHERE vehicleVin = :vin ORDER BY sortOrder ASC")
    fun observeForVehicle(vin: String): Flow<List<ClimatePresetEntity>>

    @Query("SELECT * FROM climate_presets WHERE vehicleVin = :vin ORDER BY sortOrder ASC")
    suspend fun getForVehicle(vin: String): List<ClimatePresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: ClimatePresetEntity)

    @Update
    suspend fun update(preset: ClimatePresetEntity)

    @Delete
    suspend fun delete(preset: ClimatePresetEntity)

    /** Marks one preset selected and clears the flag on the vehicle's others. */
    @Query("UPDATE climate_presets SET isSelected = (id = :presetId) WHERE vehicleVin = :vin")
    suspend fun select(vin: String, presetId: String)
}
