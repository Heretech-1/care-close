package com.careclose.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SyncState {
    PENDING_UPLOAD,
    SYNCED,
}

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val planVersion: Long,
    val effectiveFromUtc: String,
    val rawJson: String,
    val isActive: Boolean,
    val savedAtUtc: String,
)

@Entity(
    tableName = "care_events",
    indices = [
        Index(value = ["logicalDate", "taskId"]),
        Index(value = ["syncState"]),
    ],
)
data class CareEventEntity(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val actorRole: String,
    val logicalDate: String,
    val planVersion: Long,
    val taskId: String,
    val type: String,
    val occurredAtUtc: String,
    val snoozeUntilUtc: String?,
    val targetEventId: String?,
    val note: String?,
    val syncState: SyncState,
    val retryCount: Int = 0,
    val lastAttemptUtc: String? = null,
)

@Entity(tableName = "device_presence")
data class PresenceEntity(
    @PrimaryKey val deviceId: String,
    val role: String,
    val updatedAtUtc: String,
    val rawJson: String,
)

@Entity(tableName = "media_cache")
data class MediaCacheEntity(
    @PrimaryKey val mediaId: String,
    val localFilePath: String,
    val md5Checksum: String?,
    val downloadedAtUtc: String,
)

