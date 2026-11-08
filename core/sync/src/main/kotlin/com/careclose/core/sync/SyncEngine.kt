package com.careclose.core.sync

import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.ActivePlanPointer
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.CareEvent
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.FamilyManifest
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.sync.crypto.FamilyCrypto
import com.careclose.core.sync.drive.GoogleDriveServiceAccountTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SyncEngine(
    private val transport: SyncTransport,
    private val repository: CareCloseRepository,
    private val crypto: FamilyCrypto,
    private val checkpointStore: SyncCheckpointStore,
    private val rootFolderId: String,
    private val deviceId: String,
) {

    private val json = CareCloseJson.instance
    private val folderCacheMutex = Mutex()
    private val folderIdCache = mutableMapOf<String, String>()

    suspend fun ensureFamilyStructure(manifest: FamilyManifest) {
        getFolderId(FOLDER_SCHEDULES)
        getFolderId(FOLDER_MEDIA)
        getFolderId(FOLDER_EVENTS)
        getFolderId(FOLDER_PRESENCE)

        val existingManifest = transport.findFile(rootFolderId, FILE_MANIFEST)
        if (existingManifest == null) {
            val encoded = json.encodeToString(FamilyManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            transport.uploadBytes(
                folderId = rootFolderId,
                fileName = FILE_MANIFEST,
                mimeType = MIME_JSON,
                bytes = crypto.encryptBytes(encoded),
            )
        }
    }

    suspend fun publishPlan(plan: PlanSnapshot) {
        val schedulesFolderId = getFolderId(FOLDER_SCHEDULES)
        val planFileName = "plan_v${plan.planVersion}.json"
        val plainBytes = json.encodeToString(PlanSnapshot.serializer(), plan).toByteArray(Charsets.UTF_8)
        val encryptedBytes = crypto.encryptBytes(plainBytes)

        val existingPlanFile = transport.findFile(schedulesFolderId, planFileName)
        transport.uploadBytes(
            folderId = schedulesFolderId,
            fileName = planFileName,
            mimeType = MIME_OCTET_STREAM,
            bytes = encryptedBytes,
            existingFileId = existingPlanFile?.id,
        )

        val pointer = ActivePlanPointer(
            planVersion = plan.planVersion,
            fileName = planFileName,
            updatedAtUtc = Instant.now().toString(),
            sha256 = sha256Hex(plainBytes),
        )
        val pointerBytes = crypto.encryptBytes(
            json.encodeToString(ActivePlanPointer.serializer(), pointer).toByteArray(Charsets.UTF_8),
        )
        val existingPointer = transport.findFile(schedulesFolderId, FILE_ACTIVE_POINTER)
        transport.uploadBytes(
            folderId = schedulesFolderId,
            fileName = FILE_ACTIVE_POINTER,
            mimeType = MIME_OCTET_STREAM,
            bytes = pointerBytes,
            existingFileId = existingPointer?.id,
        )

        repository.saveActivePlan(plan)
    }

    suspend fun pushPendingEvents(): Int {
        val pending = repository.getPendingUploadEvents()
        if (pending.isEmpty()) return 0

        val deviceEventsFolderId = getDeviceEventsFolderId(deviceId)
        val groupedByDate = pending.groupBy { it.logicalDate }
        var totalUploaded = 0

        for ((logicalDate, dayPending) in groupedByDate.toSortedMap()) {
            val fileName = "$logicalDate.jsonl"
            val existingRemote = transport.findFile(deviceEventsFolderId, fileName)

            val existingEvents = if (existingRemote != null) {
                val remoteBytes = transport.downloadBytes(existingRemote.id)
                decodeJsonLines(remoteBytes)
            } else {
                emptyList()
            }

            val merged = (existingEvents + dayPending)
                .distinctBy { it.eventId }
                .sortedWith(
                    compareBy<CareEvent> { Instant.parse(it.occurredAtUtc) }
                        .thenBy { it.eventId },
                )

            val encodedBytes = encodeJsonLines(merged)
            val uploadedMeta = transport.uploadBytes(
                folderId = deviceEventsFolderId,
                fileName = fileName,
                mimeType = MIME_TEXT_PLAIN,
                bytes = encodedBytes,
                existingFileId = existingRemote?.id,
            )

            val checksum = uploadedMeta.md5Checksum ?: uploadedMeta.modifiedTimeUtc
            if (checksum != null) {
                checkpointStore.putCachedChecksum(uploadedMeta.id, checksum)
            }

            repository.markEventsSynced(dayPending.map { it.eventId })
            totalUploaded += dayPending.size
        }

        return totalUploaded
    }

    suspend fun pullAll(
        daysBack: Long = 3L,
        now: Instant = Instant.now(),
    ): SyncReport {
        val updatedPlanVersion = pullActivePlanIfNewer()
        val activePlan = repository.getActivePlan()
        val zone = activePlan?.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val targetFileNames = (-daysBack..1L)
            .map { offset -> "${today.plusDays(offset)}.jsonl" }
            .toSet()

        val eventsRootId = getFolderId(FOLDER_EVENTS)
        val deviceFolders = transport.listFiles(eventsRootId)
            .filter { it.mimeType == GoogleDriveServiceAccountTransport.FOLDER_MIME_TYPE }

        var importedCount = 0
        for (folder in deviceFolders) {
            val dayFiles = transport.listFiles(folder.id)
                .filter { it.name in targetFileNames }

            for (file in dayFiles) {
                val checksumTag = file.md5Checksum ?: file.modifiedTimeUtc
                if (checksumTag != null && checkpointStore.getCachedChecksum(file.id) == checksumTag) {
                    continue
                }

                val rawBytes = transport.downloadBytes(file.id)
                val decoded = decodeJsonLines(rawBytes)
                if (decoded.isNotEmpty()) {
                    repository.importRemoteEvents(decoded)
                    importedCount += decoded.size
                }
                if (checksumTag != null) {
                    checkpointStore.putCachedChecksum(file.id, checksumTag)
                }
            }
        }

        val presenceFolderId = getFolderId(FOLDER_PRESENCE)
        val presenceFiles = transport.listFiles(presenceFolderId)
            .filter { it.name.endsWith(".json") }

        var presenceUpdated = 0
        for (file in presenceFiles) {
            val checksumTag = file.md5Checksum ?: file.modifiedTimeUtc
            if (checksumTag != null && checkpointStore.getCachedChecksum(file.id) == checksumTag) {
                continue
            }
            val encrypted = transport.downloadBytes(file.id)
            val plain = crypto.decryptBytes(encrypted)
            val presence = json.decodeFromString(DevicePresence.serializer(), plain.toString(Charsets.UTF_8))
            repository.savePresence(presence)
            presenceUpdated += 1
            if (checksumTag != null) {
                checkpointStore.putCachedChecksum(file.id, checksumTag)
            }
        }

        return SyncReport(
            uploadedEventsCount = 0,
            importedEventsCount = importedCount,
            updatedPlanVersion = updatedPlanVersion,
            updatedPresenceCount = presenceUpdated,
        )
    }

    suspend fun pushPresence(presence: DevicePresence) {
        val presenceFolderId = getFolderId(FOLDER_PRESENCE)
        val fileName = "${presence.deviceId}.json"
        val existing = transport.findFile(presenceFolderId, fileName)
        val plainBytes = json.encodeToString(DevicePresence.serializer(), presence).toByteArray(Charsets.UTF_8)
        val uploaded = transport.uploadBytes(
            folderId = presenceFolderId,
            fileName = fileName,
            mimeType = MIME_OCTET_STREAM,
            bytes = crypto.encryptBytes(plainBytes),
            existingFileId = existing?.id,
        )
        val checksum = uploaded.md5Checksum ?: uploaded.modifiedTimeUtc
        if (checksum != null) {
            checkpointStore.putCachedChecksum(uploaded.id, checksum)
        }
        repository.savePresence(presence)
    }

    suspend fun uploadMedia(
        mediaId: String,
        mimeType: String,
        plainBytes: ByteArray,
    ): RemoteFileMeta {
        val mediaFolderId = getFolderId(FOLDER_MEDIA)
        val existing = transport.findFile(mediaFolderId, mediaId)
        return transport.uploadBytes(
            folderId = mediaFolderId,
            fileName = mediaId,
            mimeType = mimeType,
            bytes = crypto.encryptBytes(plainBytes),
            existingFileId = existing?.id,
        )
    }

    suspend fun ensureMediaCached(
        mediaId: String,
        cacheDir: File,
    ): File? {
        val mediaFolderId = getFolderId(FOLDER_MEDIA)
        val remote = transport.findFile(mediaFolderId, mediaId) ?: return null
        val existingCache = repository.getCachedMedia(mediaId)
        val localFile = File(cacheDir, mediaId)

        if (existingCache != null &&
            localFile.exists() &&
            remote.md5Checksum != null &&
            existingCache.md5Checksum == remote.md5Checksum
        ) {
            return localFile
        }

        val encrypted = transport.downloadBytes(remote.id)
        val plain = crypto.decryptBytes(encrypted)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        localFile.writeBytes(plain)

        repository.saveCachedMedia(
            MediaCacheEntity(
                mediaId = mediaId,
                localFilePath = localFile.absolutePath,
                md5Checksum = remote.md5Checksum,
                downloadedAtUtc = Instant.now().toString(),
            ),
        )
        return localFile
    }

    suspend fun syncOnce(
        presenceSnapshot: DevicePresence? = null,
        daysBack: Long = 3L,
        now: Instant = Instant.now(),
    ): SyncReport {
        val pendingIds = repository.getPendingUploadEvents().map { it.eventId }
        val uploadedCount = try {
            pushPendingEvents()
        } catch (e: Throwable) {
            if (pendingIds.isNotEmpty()) {
                repository.recordSyncAttemptFailure(pendingIds, now)
            }
            throw e
        }

        val pullReport = pullAll(daysBack = daysBack, now = now)
        if (presenceSnapshot != null) {
            pushPresence(presenceSnapshot)
        }

        return pullReport.copy(uploadedEventsCount = uploadedCount)
    }

    private suspend fun pullActivePlanIfNewer(): Long? {
        val schedulesFolderId = getFolderId(FOLDER_SCHEDULES)
        val pointerMeta = transport.findFile(schedulesFolderId, FILE_ACTIVE_POINTER) ?: return null
        val pointerChecksum = pointerMeta.md5Checksum ?: pointerMeta.modifiedTimeUtc
        val localPlan = repository.getActivePlan()

        if (localPlan != null &&
            pointerChecksum != null &&
            checkpointStore.getCachedChecksum(pointerMeta.id) == pointerChecksum
        ) {
            return null
        }

        val pointerPlain = crypto.decryptBytes(transport.downloadBytes(pointerMeta.id))
        val pointer = json.decodeFromString(ActivePlanPointer.serializer(), pointerPlain.toString(Charsets.UTF_8))

        if (localPlan == null || pointer.planVersion > localPlan.planVersion) {
            val planMeta = transport.findFile(schedulesFolderId, pointer.fileName) ?: return null
            val planPlain = crypto.decryptBytes(transport.downloadBytes(planMeta.id))
            val plan = json.decodeFromString(PlanSnapshot.serializer(), planPlain.toString(Charsets.UTF_8))
            repository.saveActivePlan(plan)
            if (pointerChecksum != null) {
                checkpointStore.putCachedChecksum(pointerMeta.id, pointerChecksum)
            }
            return plan.planVersion
        }

        if (pointerChecksum != null) {
            checkpointStore.putCachedChecksum(pointerMeta.id, pointerChecksum)
        }
        return null
    }

    private suspend fun getFolderId(name: String): String = folderCacheMutex.withLock {
        val key = "root:$name"
        folderIdCache[key]?.let { return it }
        val id = transport.ensureFolder(rootFolderId, name)
        folderIdCache[key] = id
        return id
    }

    private suspend fun getDeviceEventsFolderId(targetDeviceId: String): String {
        val eventsFolderId = getFolderId(FOLDER_EVENTS)
        return folderCacheMutex.withLock {
            val key = "events:$targetDeviceId"
            folderIdCache[key]?.let { return it }
            val id = transport.ensureFolder(eventsFolderId, targetDeviceId)
            folderIdCache[key] = id
            return id
        }
    }

    private fun encodeJsonLines(events: List<CareEvent>): ByteArray {
        val content = events.joinToString(separator = "\n") { event ->
            val rawLine = json.encodeToString(CareEvent.serializer(), event)
            crypto.encryptTextLine(rawLine)
        }
        return (if (content.isNotEmpty()) "$content\n" else "").toByteArray(Charsets.UTF_8)
    }

    private fun decodeJsonLines(rawBytes: ByteArray): List<CareEvent> {
        val text = rawBytes.toString(Charsets.UTF_8)
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                runCatching {
                    val plain = crypto.decryptTextLine(line)
                    json.decodeFromString(CareEvent.serializer(), plain)
                }.getOrNull()
            }
            .toList()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FOLDER_SCHEDULES = "schedules"
        const val FOLDER_MEDIA = "media"
        const val FOLDER_EVENTS = "events"
        const val FOLDER_PRESENCE = "presence"
        const val FILE_MANIFEST = "manifest.json"
        const val FILE_ACTIVE_POINTER = "active_pointer.json"
        const val MIME_JSON = "application/json"
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_OCTET_STREAM = "application/octet-stream"
    }
}

