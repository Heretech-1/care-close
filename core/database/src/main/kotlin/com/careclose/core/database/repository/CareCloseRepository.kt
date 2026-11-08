package com.careclose.core.database.repository

import com.careclose.core.database.CareCloseDatabase
import com.careclose.core.database.entity.CareEventEntity
import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.entity.PlanEntity
import com.careclose.core.database.entity.PresenceEntity
import com.careclose.core.database.entity.SyncState
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

interface CareCloseRepository {
    fun observeActivePlan(): Flow<PlanSnapshot?>
    suspend fun getActivePlan(): PlanSnapshot?
    suspend fun saveActivePlan(plan: PlanSnapshot, savedAtUtc: Instant = Instant.now())

    fun observeEventsForDate(logicalDate: String): Flow<List<CareEvent>>
    fun observeAllEvents(): Flow<List<CareEvent>>
    suspend fun recordLocalEvent(event: CareEvent)
    suspend fun importRemoteEvents(events: List<CareEvent>)
    suspend fun getPendingUploadEvents(): List<CareEvent>
    suspend fun markEventsSynced(eventIds: List<String>)
    suspend fun recordSyncAttemptFailure(eventIds: List<String>, attemptAt: Instant = Instant.now())

    fun observeLatestElderPresence(): Flow<DevicePresence?>
    suspend fun savePresence(presence: DevicePresence)

    suspend fun getCachedMedia(mediaId: String): MediaCacheEntity?
    suspend fun saveCachedMedia(media: MediaCacheEntity)
}

class RoomCareCloseRepository(
    private val database: CareCloseDatabase,
) : CareCloseRepository {

    private val json = CareCloseJson.instance
    private val planDao = database.planDao()
    private val eventDao = database.careEventDao()
    private val presenceDao = database.presenceDao()
    private val mediaCacheDao = database.mediaCacheDao()

    override fun observeActivePlan(): Flow<PlanSnapshot?> {
        return planDao.observeActivePlan().map { entity ->
            entity?.let { json.decodeFromString(PlanSnapshot.serializer(), it.rawJson) }
        }
    }

    override suspend fun getActivePlan(): PlanSnapshot? {
        return planDao.getActivePlan()?.let {
            json.decodeFromString(PlanSnapshot.serializer(), it.rawJson)
        }
    }

    override suspend fun saveActivePlan(plan: PlanSnapshot, savedAtUtc: Instant) {
        val raw = json.encodeToString(PlanSnapshot.serializer(), plan)
        planDao.saveAndActivate(
            PlanEntity(
                planVersion = plan.planVersion,
                effectiveFromUtc = plan.effectiveFromUtc,
                rawJson = raw,
                isActive = true,
                savedAtUtc = savedAtUtc.toString(),
            ),
        )
    }

    override fun observeEventsForDate(logicalDate: String): Flow<List<CareEvent>> {
        return eventDao.observeEventsForDate(logicalDate).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun observeAllEvents(): Flow<List<CareEvent>> {
        return eventDao.observeAllEvents().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun recordLocalEvent(event: CareEvent) {
        eventDao.insertIgnore(listOf(event.toEntity(SyncState.PENDING_UPLOAD)))
    }

    override suspend fun importRemoteEvents(events: List<CareEvent>) {
        if (events.isEmpty()) return
        eventDao.insertIgnore(events.map { it.toEntity(SyncState.SYNCED) })
    }

    override suspend fun getPendingUploadEvents(): List<CareEvent> {
        return eventDao.getBySyncState(SyncState.PENDING_UPLOAD).map { it.toDomain() }
    }

    override suspend fun markEventsSynced(eventIds: List<String>) {
        eventIds.chunked(SQLITE_MAX_BIND_CHUNK).forEach { chunk ->
            eventDao.updateSyncState(chunk, SyncState.SYNCED)
        }
    }

    override suspend fun recordSyncAttemptFailure(eventIds: List<String>, attemptAt: Instant) {
        val attemptUtc = attemptAt.toString()
        eventIds.chunked(SQLITE_MAX_BIND_CHUNK).forEach { chunk ->
            eventDao.incrementRetry(chunk, attemptUtc)
        }
    }

    override fun observeLatestElderPresence(): Flow<DevicePresence?> {
        return presenceDao.observeLatestByRole(Role.ELDER.name).map { entity ->
            entity?.let { json.decodeFromString(DevicePresence.serializer(), it.rawJson) }
        }
    }

    override suspend fun savePresence(presence: DevicePresence) {
        presenceDao.upsert(
            PresenceEntity(
                deviceId = presence.deviceId,
                role = presence.role.name,
                updatedAtUtc = presence.updatedAtUtc,
                rawJson = json.encodeToString(DevicePresence.serializer(), presence),
            ),
        )
    }

    override suspend fun getCachedMedia(mediaId: String): MediaCacheEntity? {
        return mediaCacheDao.getById(mediaId)
    }

    override suspend fun saveCachedMedia(media: MediaCacheEntity) {
        mediaCacheDao.upsert(media)
    }

    private fun CareEvent.toEntity(syncState: SyncState): CareEventEntity {
        return CareEventEntity(
            eventId = eventId,
            deviceId = deviceId,
            actorRole = actorRole.name,
            logicalDate = logicalDate,
            planVersion = planVersion,
            taskId = taskId,
            type = type.name,
            occurredAtUtc = occurredAtUtc,
            snoozeUntilUtc = snoozeUntilUtc,
            targetEventId = targetEventId,
            note = note,
            syncState = syncState,
        )
    }

    private fun CareEventEntity.toDomain(): CareEvent {
        return CareEvent(
            eventId = eventId,
            deviceId = deviceId,
            actorRole = Role.valueOf(actorRole),
            logicalDate = logicalDate,
            planVersion = planVersion,
            taskId = taskId,
            type = CareEventType.valueOf(type),
            occurredAtUtc = occurredAtUtc,
            snoozeUntilUtc = snoozeUntilUtc,
            targetEventId = targetEventId,
            note = note,
        )
    }

    private companion object {
        const val SQLITE_MAX_BIND_CHUNK = 500
    }
}

