package com.betterblue.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.betterblue.app.data.db.dao.AccountDao
import com.betterblue.app.data.db.dao.ClimatePresetDao
import com.betterblue.app.data.db.dao.HttpLogDao
import com.betterblue.app.data.db.dao.VehicleDao
import com.betterblue.app.data.db.entity.AccountEntity
import com.betterblue.app.data.db.entity.ClimatePresetEntity
import com.betterblue.app.data.db.entity.HttpLogEntity
import com.betterblue.app.data.db.entity.VehicleEntity

@Database(
    entities = [
        AccountEntity::class,
        VehicleEntity::class,
        ClimatePresetEntity::class,
        HttpLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun vehicleDao(): VehicleDao

    abstract fun climatePresetDao(): ClimatePresetDao

    abstract fun httpLogDao(): HttpLogDao

    companion object {
        const val NAME = "betterblue.db"
    }
}
