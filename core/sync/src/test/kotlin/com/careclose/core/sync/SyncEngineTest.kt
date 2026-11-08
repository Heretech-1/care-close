package com.careclose.core.sync

import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Criticality
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.FamilyManifest
import com.careclose.core.model.NetworkType
import com.careclose.core.model.PairingQrPayload
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.Role
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.TaskType
import com.careclose.core.sync.crypto.FamilyCrypto
import com.careclose.core.sync.drive.GoogleDriveServiceAccountTransport
import com.careclose.core.sync.drive.ServiceAccountJwtSigner
import com.careclose.core.sync.pairing.PairingPayloadCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

class SyncEngineTest {

    @Test
    fun `aes gcm crypto encrypts and decrypts binary and json lines`() {
        val key = FamilyCrypto.generateNewKeyBase64()
        val crypto = FamilyCrypto(key)

        val plainBytes = "Привіт, CareClose!".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encryptBytes(plainBytes)
        assertArrayEquals(plainBytes, crypto.decryptBytes(encrypted))

        val plainLine = """{"eventId":"123","type":"TAKEN"}"""
        val encLine = crypto.encryptTextLine(plainLine)
        assertTrue(encLine.startsWith("CC1:"))
        assertEquals(plainLine, crypto.decryptTextLine(encLine))

        // Different key fails authentication tag check
        val wrongCrypto = FamilyCrypto(FamilyCrypto.generateNewKeyBase64())
        val wrongResult = runCatching { wrongCrypto.decryptBytes(encrypted) }
        assertTrue(wrongResult.isFailure)
    }

    @Test
    fun `pairing qr codec compresses and round trips payload`() {
        val fakeSa = """{"type":"service_account","client_email":"care@proj.iam.gserviceaccount.com","private_key":"-----BEGIN PRIVATE KEY-----\n${"A".repeat(800)}\n-----END PRIVATE KEY-----\n"}"""
        val payload = PairingQrPayload(
            familyId = "fam_1",
            familyName = "Бабуся Оля",
            folderId = "1aBcDeFgHiJk",
            serviceAccountJson = fakeSa,
            familyAesKeyBase64 = FamilyCrypto.generateNewKeyBase64(),
            createdAtUtc = "2026-11-08T12:00:00Z",
        )

        val encoded = PairingPayloadCodec.encode(payload)
        assertTrue(encoded.startsWith("CARECLOSE1:"))
        assertTrue(encoded.length < fakeSa.length)

        val decoded = PairingPayloadCodec.decode(encoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun `service account jwt signer produces valid rs256 signature`() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = kpg.generateKeyPair()
        val pkcs8Base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$pkcs8Base64\n-----END PRIVATE KEY-----\n"

        val saJson = """
            {
              "client_email": "careclose@demo.iam.gserviceaccount.com",
              "private_key": ${JsonEscaper.quote(pem)}
            }
        """.trimIndent()

        val creds = ServiceAccountJwtSigner.parseCredentials(saJson)
        val jwt = ServiceAccountJwtSigner.createSignedAssertion(creds, now = Instant.parse("2026-11-08T12:00:00Z"))
        val parts = jwt.split('.')
        assertEquals(3, parts.size)

        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val sigBytes = Base64.getUrlDecoder().decode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(signingInput)
        }
        assertTrue(verifier.verify(sigBytes))
    }

    @Test
    fun `full bidirectional sync publishes plan syncs events without conflicts and caches media`() = runTest {
        val transport = InMemorySyncTransport()
        val sharedAesKey = FamilyCrypto.generateNewKeyBase64()
        val crypto = FamilyCrypto(sharedAesKey)

        val caregiverRepo = InMemoryCareCloseRepository()
        val elderRepo = InMemoryCareCloseRepository()

        val caregiverEngine = SyncEngine(
            transport = transport,
            repository = caregiverRepo,
            crypto = crypto,
            checkpointStore = InMemoryCheckpointStore(),
            rootFolderId = transport.rootId,
            deviceId = "caregiver_1",
        )
        val elderEngine = SyncEngine(
            transport = transport,
            repository = elderRepo,
            crypto = crypto,
            checkpointStore = InMemoryCheckpointStore(),
            rootFolderId = transport.rootId,
            deviceId = "elder_1",
        )

        val manifest = FamilyManifest(
            familyId = "fam_1",
            familyName = "Мама",
            timezone = "Europe/Kyiv",
            createdAtUtc = Instant.now().toString(),
        )
        caregiverEngine.ensureFamilyStructure(manifest)

        val plan = PlanSnapshot(
            planVersion = 1L,
            effectiveFromUtc = Instant.now().toString(),
            timezone = "Europe/Kyiv",
            tasks = listOf(
                TaskRule(
                    id = "med_1",
                    type = TaskType.MEDICATION,
                    criticality = Criticality.CRITICAL,
                    title = "Аспірин",
                    schedule = ScheduleRule.Fixed(time = "08:00"),
                ),
            ),
        )
        caregiverEngine.publishPlan(plan)

        // Elder pulls initial plan
        val pull1 = elderEngine.pullAll()
        assertEquals(1L, pull1.updatedPlanVersion)
        assertEquals(plan, elderRepo.getActivePlan())

        // Second pull skips unchanged files via md5 checkpoint
        val downloadsBefore = transport.downloadCounter
        val pull2 = elderEngine.pullAll()
        assertEquals(null, pull2.updatedPlanVersion)
        assertEquals(downloadsBefore, transport.downloadCounter)

        // Both sides record events on the same day -> stored in isolated folders
        val today = LocalDate.now().toString()
        val elderEvent = CareEvent(
            eventId = "ev_elder_1",
            deviceId = "elder_1",
            actorRole = Role.ELDER,
            logicalDate = today,
            planVersion = 1L,
            taskId = "med_1",
            type = CareEventType.TAKEN,
            occurredAtUtc = Instant.now().toString(),
        )
        val caregiverOverride = CareEvent(
            eventId = "ev_cg_1",
            deviceId = "caregiver_1",
            actorRole = Role.CAREGIVER,
            logicalDate = today,
            planVersion = 1L,
            taskId = "med_2",
            type = CareEventType.CAREGIVER_OVERRIDE_TAKEN,
            occurredAtUtc = Instant.now().toString(),
        )
        elderRepo.recordLocalEvent(elderEvent)
        caregiverRepo.recordLocalEvent(caregiverOverride)

        val elderPresence = DevicePresence(
            deviceId = "elder_1",
            role = Role.ELDER,
            updatedAtUtc = Instant.now().toString(),
            batteryPercent = 72,
            isCharging = true,
            networkType = NetworkType.WIFI,
            alarmVolumePercent = 90,
            isDoNotDisturbActive = false,
            activePlanVersion = 1L,
        )

        val elderSyncReport = elderEngine.syncOnce(presenceSnapshot = elderPresence)
        assertEquals(1, elderSyncReport.uploadedEventsCount)
        assertTrue(elderRepo.getPendingUploadEvents().isEmpty())

        val cgSyncReport = caregiverEngine.syncOnce()
        assertEquals(1, cgSyncReport.uploadedEventsCount)
        assertEquals(1, cgSyncReport.importedEventsCount)
        assertEquals(1, cgSyncReport.updatedPresenceCount)

        // Elder pulls caregiver override too
        val elderPullOverride = elderEngine.pullAll()
        assertEquals(1, elderPullOverride.importedEventsCount)

        // Media upload & local encrypted cache check
        val photoBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        caregiverEngine.uploadMedia("pill_1.jpg", "image/jpeg", photoBytes)
        val cacheDir: File = Files.createTempDirectory("careclose_media_test").toFile()
        try {
            val cachedFile = elderEngine.ensureMediaCached("pill_1.jpg", cacheDir)
            assertNotNull(cachedFile)
            assertArrayEquals(photoBytes, cachedFile!!.readBytes())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}

private object JsonEscaper {
    fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }
}

private class InMemoryCheckpointStore : SyncCheckpointStore {
    private val map = mutableMapOf<String, String>()
    override fun getCachedChecksum(fileId: String): String? = map[fileId]
    override fun putCachedChecksum(fileId: String, checksum: String) {
        map[fileId] = checksum
    }
}

private class InMemorySyncTransport : SyncTransport {
    private data class Node(
        val id: String,
        val parentId: String?,
        val name: String,
        val mimeType: String,
        var bytes: ByteArray = ByteArray(0),
        var modifiedAt: Instant = Instant.now(),
    )

    val rootId = "root_family"
    private var idSeq = 1
    var downloadCounter = 0
    private val nodes = mutableMapOf<String, Node>(
        rootId to Node(
            id = rootId,
            parentId = null,
            name = "CareClose_Family",
            mimeType = GoogleDriveServiceAccountTransport.FOLDER_MIME_TYPE,
        ),
    )

    override suspend fun ensureFolder(parentFolderId: String, name: String): String {
        val existing = nodes.values.firstOrNull {
            it.parentId == parentFolderId &&
                it.name == name &&
                it.mimeType == GoogleDriveServiceAccountTransport.FOLDER_MIME_TYPE
        }
        if (existing != null) return existing.id
        val newId = "folder_${idSeq++}"
        nodes[newId] = Node(
            id = newId,
            parentId = parentFolderId,
            name = name,
            mimeType = GoogleDriveServiceAccountTransport.FOLDER_MIME_TYPE,
        )
        return newId
    }

    override suspend fun listFiles(folderId: String): List<RemoteFileMeta> {
        return nodes.values.filter { it.parentId == folderId }.map { it.toMeta() }
    }

    override suspend fun findFile(folderId: String, name: String): RemoteFileMeta? {
        return nodes.values.firstOrNull { it.parentId == folderId && it.name == name }?.toMeta()
    }

    override suspend fun downloadBytes(fileId: String): ByteArray {
        downloadCounter += 1
        return nodes[fileId]?.bytes?.copyOf() ?: error("File not found: $fileId")
    }

    override suspend fun uploadBytes(
        folderId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        existingFileId: String?,
    ): RemoteFileMeta {
        val target = if (existingFileId != null) {
            nodes[existingFileId] ?: error("Existing file not found: $existingFileId")
        } else {
            val newId = "file_${idSeq++}"
            Node(
                id = newId,
                parentId = folderId,
                name = fileName,
                mimeType = mimeType,
            ).also { nodes[newId] = it }
        }
        target.bytes = bytes.copyOf()
        target.modifiedAt = Instant.now()
        return target.toMeta()
    }

    private fun Node.toMeta(): RemoteFileMeta {
        val md5 = if (mimeType == GoogleDriveServiceAccountTransport.FOLDER_MIME_TYPE) {
            null
        } else {
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        return RemoteFileMeta(
            id = id,
            name = name,
            mimeType = mimeType,
            modifiedTimeUtc = modifiedAt.toString(),
            md5Checksum = md5,
            sizeBytes = bytes.size.toLong(),
        )
    }
}

private class InMemoryCareCloseRepository : CareCloseRepository {
    private val planFlow = MutableStateFlow<PlanSnapshot?>(null)
    private val eventsFlow = MutableStateFlow<List<CareEvent>>(emptyList())
    private val pendingIds = mutableSetOf<String>()
    private val presenceFlow = MutableStateFlow<DevicePresence?>(null)
    private val media = mutableMapOf<String, MediaCacheEntity>()

    override fun observeActivePlan(): Flow<PlanSnapshot?> = planFlow
    override suspend fun getActivePlan(): PlanSnapshot? = planFlow.value
    override suspend fun saveActivePlan(plan: PlanSnapshot, savedAtUtc: Instant) {
        planFlow.value = plan
    }

    override fun observeEventsForDate(logicalDate: String): Flow<List<CareEvent>> {
        return eventsFlow.map { list -> list.filter { it.logicalDate == logicalDate } }
    }

    override fun observeAllEvents(): Flow<List<CareEvent>> = eventsFlow

    override suspend fun recordLocalEvent(event: CareEvent) {
        if (eventsFlow.value.none { it.eventId == event.eventId }) {
            eventsFlow.value = eventsFlow.value + event
            pendingIds.add(event.eventId)
        }
    }

    override suspend fun importRemoteEvents(events: List<CareEvent>) {
        val existingIds = eventsFlow.value.map { it.eventId }.toSet()
        val toAdd = events.filter { it.eventId !in existingIds }
        if (toAdd.isNotEmpty()) {
            eventsFlow.value = eventsFlow.value + toAdd
        }
    }

    override suspend fun getPendingUploadEvents(): List<CareEvent> {
        return eventsFlow.value.filter { it.eventId in pendingIds }
    }

    override suspend fun markEventsSynced(eventIds: List<String>) {
        pendingIds.removeAll(eventIds.toSet())
    }

    override suspend fun recordSyncAttemptFailure(eventIds: List<String>, attemptAt: Instant) = Unit

    override fun observeLatestElderPresence(): Flow<DevicePresence?> = presenceFlow
    override suspend fun savePresence(presence: DevicePresence) {
        if (presence.role == Role.ELDER) {
            presenceFlow.value = presence
        }
    }

    override suspend fun getCachedMedia(mediaId: String): MediaCacheEntity? = media[mediaId]
    override suspend fun saveCachedMedia(media: MediaCacheEntity) {
        this.media[media.mediaId] = media
    }
}
