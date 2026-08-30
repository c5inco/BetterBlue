package com.betterblue.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.betterblue.app.data.db.AppDatabase
import com.betterblue.app.data.db.dao.AccountDao
import com.betterblue.app.data.db.dao.ClimatePresetDao
import com.betterblue.app.data.db.dao.HttpLogDao
import com.betterblue.app.data.db.dao.VehicleDao
import com.betterblue.app.data.security.CredentialCipher
import com.betterblue.app.data.security.KeystoreCredentialCipher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-lifetime coroutine scope. Shared API work (request dedup,
 * rmToken persistence, log writes) runs here so a cancelled UI caller can't
 * kill work another caller awaits.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideVehicleDao(db: AppDatabase): VehicleDao = db.vehicleDao()

    @Provides
    fun provideClimatePresetDao(db: AppDatabase): ClimatePresetDao = db.climatePresetDao()

    @Provides
    fun provideHttpLogDao(db: AppDatabase): HttpLogDao = db.httpLogDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {

    @Binds
    @Singleton
    abstract fun bindCredentialCipher(impl: KeystoreCredentialCipher): CredentialCipher
}
