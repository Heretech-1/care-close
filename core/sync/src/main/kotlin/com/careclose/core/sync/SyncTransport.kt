package com.careclose.core.sync

import java.io.IOException

data class RemoteFileMeta(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTimeUtc: String? = null,
    val md5Checksum: String? = null,
    val sizeBytes: Long? = null,
)

class SyncException(
    message: String,
    val httpStatus: Int? = null,
    val isAuthError: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

interface SyncTransport {
    suspend fun ensureFolder(parentFolderId: String, name: String): String
    suspend fun listFiles(folderId: String): List<RemoteFileMeta>
    suspend fun findFile(folderId: String, name: String): RemoteFileMeta?
    suspend fun downloadBytes(fileId: String): ByteArray
    suspend fun uploadBytes(
        folderId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        existingFileId: String? = null,
    ): RemoteFileMeta
}

data class SyncReport(
    val uploadedEventsCount: Int,
    val importedEventsCount: Int,
    val updatedPlanVersion: Long?,
    val updatedPresenceCount: Int,
)

interface SyncCheckpointStore {
    fun getCachedChecksum(fileId: String): String?
    fun putCachedChecksum(fileId: String, checksum: String)
}

