package com.careclose.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.careclose.core.database.entity.CareEventEntity
import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.entity.PlanEntity
import com.careclose.core.database.entity.PresenceEntity
import com.careclose.core.database.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {

    @Query("SELECT * FROM plans WHERE isActive = 1 LIMIT 1")
    fun observeActivePlan(): Flow<PlanEntity?>

    @Query("SELECT * FROM plans WHERE isActive = 1 LIMIT 1")
    suspend fun getActivePlan(): PlanEntity?

    @Query("SELECT * FROM plans WHERE planVersion = :planVersion LIMIT 1")
    suspend fun getByVersion(planVersion: Long): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanEntity)

    @Query("UPDATE plans SET isActive = CASE WHEN planVersion = :planVersion THEN 1 ELSE 0 END")
    suspend fun markOnlyActive(planVersion: Long)

    @Transaction
    suspend fun saveAndActivate(plan: PlanEntity) {
        upsert(plan.copy(isActive = true))
        markOnlyActive(plan.planVersion)
    }
}

@Dao
interface CareEventDao {

    @Query("SELECT * FROM care_events WHERE logicalDate = :logicalDate ORDER BY occurredAtUtc ASC, eventId ASC")
    fun observeEventsForDate(logicalDate: String): Flow<List<CareEventEntity>>

    @Query("SELECT * FROM care_events ORDER BY occurredAtUtc ASC, eventId ASC")
    fun observeAllEvents(): Flow<List<CareEventEntity>>

    @Query("SELECT * FROM care_events WHERE syncState = :syncState ORDER BY occurredAtUtc ASC, eventId ASC")
    suspend fun getBySyncState(syncState: SyncState): List<CareEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(events: List<CareEventEntity>)

    @Query("UPDATE care_events SET syncState = :syncState WHERE eventId IN (:eventIds)")
    suspend fun updateSyncState(eventIds: List<String>, syncState: SyncState)

    @Query("UPDATE care_events SET retryCount = retryCount + 1, lastAttemptUtc = :attemptUtc WHERE eventId IN (:eventIds)")
    suspend fun incrementRetry(eventIds: List<String>, attemptUtc: String)
}

@Dao
interface PresenceDao {

    @Query("SELECT * FROM device_presence WHERE deviceId = :deviceId LIMIT 1")
    fun observeByDeviceId(deviceId: String): Flow<PresenceEntity?>

    @Query("SELECT * FROM device_presence WHERE role = :role ORDER BY updatedAtUtc DESC LIMIT 1")
    fun observeLatestByRole(role: String): Flow<PresenceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PresenceEntity)
}

@Dao
interface MediaCacheDao {

    @Query("SELECT * FROM media_cache WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getById(mediaId: String): MediaCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaCacheEntity)

    @Query("DELETE FROM media_cache WHERE mediaId = :mediaId")
    suspend fun deleteById(mediaId: String)
}

