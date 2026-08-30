package com.betterblue.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.betterblue.app.data.db.entity.HttpLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HttpLogDao {
    @Query("SELECT * FROM http_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HttpLogEntity>>

    @Query("SELECT * FROM http_logs WHERE accountId = :accountId ORDER BY timestamp DESC")
    fun observeForAccount(accountId: String): Flow<List<HttpLogEntity>>

    @Query("SELECT COUNT(*) FROM http_logs")
    suspend fun count(): Int

    @Insert
    suspend fun insert(log: HttpLogEntity)

    @Query(
        "DELETE FROM http_logs WHERE id NOT IN " +
            "(SELECT id FROM http_logs ORDER BY timestamp DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM http_logs")
    suspend fun deleteAll()

    /**
     * Insert, then trim to [keep] once the table crosses [cap] — same
     * 100-row cap / 50-row trim policy as the iOS HTTPLogSinkManager.
     */
    @Transaction
    suspend fun insertCapped(log: HttpLogEntity, cap: Int = 100, keep: Int = 50) {
        insert(log)
        if (count() > cap) trimTo(keep)
    }
}
