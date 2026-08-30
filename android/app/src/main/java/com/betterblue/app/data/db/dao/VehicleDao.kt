package com.betterblue.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.betterblue.app.data.db.entity.VehicleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE isHidden = 0 ORDER BY sortOrder ASC")
    fun observeVisible(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE vin = :vin")
    fun observeByVin(vin: String): Flow<VehicleEntity?>

    @Query("SELECT * FROM vehicles ORDER BY sortOrder ASC")
    suspend fun getAll(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE vin = :vin")
    suspend fun getByVin(vin: String): VehicleEntity?

    @Query("SELECT * FROM vehicles WHERE accountId = :accountId ORDER BY sortOrder ASC")
    suspend fun getByAccount(accountId: String): List<VehicleEntity>

    @Query("SELECT MAX(sortOrder) FROM vehicles")
    suspend fun maxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vehicle: VehicleEntity)

    @Update
    suspend fun update(vehicle: VehicleEntity)

    @Query("DELETE FROM vehicles WHERE vin = :vin")
    suspend fun deleteByVin(vin: String)

    @Query("UPDATE vehicles SET sortOrder = :sortOrder WHERE vin = :vin")
    suspend fun updateSortOrder(vin: String, sortOrder: Int)
}
