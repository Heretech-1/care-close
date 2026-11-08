package com.careclose.core.sync.drive

import com.careclose.core.model.CareCloseJson
import com.careclose.core.sync.RemoteFileMeta
import com.careclose.core.sync.SyncException
import com.careclose.core.sync.SyncTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600L,
)

@Serializable
private data class DriveFileDto(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val size: String? = null,
)

@Serializable
private data class DriveFileListDto(
    val files: List<DriveFileDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class CreateFileMetadataDto(
    val name: String,
    val parents: List<String>,
    val mimeType: String? = null,
)

class GoogleDriveServiceAccountTransport(
    serviceAccountJson: String,
    private val httpClient: OkHttpClient,
) : SyncTransport {

    constructor(serviceAccountJson: String) : this(
        serviceAccountJson = serviceAccountJson,
        httpClient = defaultOkHttpClient(),
    )

    private val credentials = ServiceAccountJwtSigner.parseCredentials(serviceAccountJson)
    private val json = CareCloseJson.instance
    private val tokenMutex = Mutex()
    private var cachedAccessToken: String? = null
    private var tokenExpiresAt: Instant = Instant.EPOCH

    override suspend fun ensureFolder(parentFolderId: String, name: String): String {
        val escapedName = escapeDriveQuery(name)
        val escapedParent = escapeDriveQuery(parentFolderId)
        val query = "'$escapedParent' in parents and name = '$escapedName' and " +
            "mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val existing = queryFiles(query).firstOrNull()
        if (existing != null) return existing.id

        val metaBody = json.encodeToString(
            CreateFileMetadataDto.serializer(),
            CreateFileMetadataDto(
                name = name,
                parents = listOf(parentFolderId),
                mimeType = FOLDER_MIME_TYPE,
            ),
        ).toRequestBody(JSON_MEDIA_TYPE)

        val url = DRIVE_FILES_URL.toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id")
            .addQueryParameter("supportsAllDrives", "true")
            .build()

        val responseText = executeAuthorized(
            buildRequest = { token ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(metaBody)
                    .build()
            },
        ) { response ->
            response.body?.string().orEmpty()
        }

        val created = json.decodeFromString(DriveFileDto.serializer(), responseText)
        return created.id
    }

    override suspend fun listFiles(folderId: String): List<RemoteFileMeta> {
        val escapedParent = escapeDriveQuery(folderId)
        return queryFiles("'$escapedParent' in parents and trashed = false")
    }

    override suspend fun findFile(folderId: String, name: String): RemoteFileMeta? {
        val escapedParent = escapeDriveQuery(folderId)
        val escapedName = escapeDriveQuery(name)
        val query = "'$escapedParent' in parents and name = '$escapedName' and trashed = false"
        return queryFiles(query).firstOrNull()
    }

    override suspend fun downloadBytes(fileId: String): ByteArray {
        val url = "$DRIVE_FILES_URL/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .build()

        return executeAuthorized(
            buildRequest = { token ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            },
        ) { response ->
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun uploadBytes(
        folderId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        existingFileId: String?,
    ): RemoteFileMeta {
        val responseText = if (existingFileId != null) {
            val url = "$DRIVE_UPLOAD_URL/$existingFileId".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "media")
                .addQueryParameter("fields", FILE_FIELDS)
                .addQueryParameter("supportsAllDrives", "true")
                .build()

            val body = bytes.toRequestBody(mimeType.toMediaType())
            executeAuthorized(
                buildRequest = { token ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .patch(body)
                        .build()
                },
            ) { response ->
                response.body?.string().orEmpty()
            }
        } else {
            val url = DRIVE_UPLOAD_URL.toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", FILE_FIELDS)
                .addQueryParameter("supportsAllDrives", "true")
                .build()

            val metadataJson = json.encodeToString(
                CreateFileMetadataDto.serializer(),
                CreateFileMetadataDto(
                    name = fileName,
                    parents = listOf(folderId),
                ),
            )

            val multipartBody = MultipartBody.Builder()
                .setType(MULTIPART_RELATED)
                .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .addPart(bytes.toRequestBody(mimeType.toMediaType()))
                .build()

            executeAuthorized(
                buildRequest = { token ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .post(multipartBody)
                        .build()
                },
            ) { response ->
                response.body?.string().orEmpty()
            }
        }

        return json.decodeFromString(DriveFileDto.serializer(), responseText).toRemoteMeta()
    }

    private suspend fun queryFiles(query: String): List<RemoteFileMeta> {
        val results = mutableListOf<RemoteFileMeta>()
        var pageToken: String? = null

        do {
            val urlBuilder = DRIVE_FILES_URL.toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")

            if (pageToken != null) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }

            val url = urlBuilder.build()
            val responseText = executeAuthorized(
                buildRequest = { token ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                },
            ) { response ->
                response.body?.string().orEmpty()
            }

            val page = json.decodeFromString(DriveFileListDto.serializer(), responseText)
            results.addAll(page.files.map { it.toRemoteMeta() })
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrBlank())

        return results
    }

    private suspend fun <T> executeAuthorized(
        buildRequest: (String) -> Request,
        parseBody: (okhttp3.Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        var retriedAfter401 = false
        while (true) {
            val token = getValidAccessToken(forceRefresh = retriedAfter401)
            val request = buildRequest(token)
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 401 && !retriedAfter401) {
                        retriedAfter401 = true
                        invalidateToken()
                        return@use
                    }
                    if (!response.isSuccessful) {
                        val errorSnippet = response.body?.string()?.take(512).orEmpty()
                        throw SyncException(
                            message = "Google Drive HTTP ${response.code}: $errorSnippet",
                            httpStatus = response.code,
                            isAuthError = response.code == 401 || response.code == 403,
                        )
                    }
                    return@withContext parseBody(response)
                }
            } catch (e: SyncException) {
                throw e
            } catch (e: IOException) {
                throw SyncException(
                    message = "Помилка мережі при зверненні до Google Drive: ${e.message}",
                    cause = e,
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw SyncException("Несподіваний стан запиту Google Drive")
    }

    private suspend fun getValidAccessToken(forceRefresh: Boolean): String = tokenMutex.withLock {
        val now = Instant.now()
        val cached = cachedAccessToken
        if (!forceRefresh && cached != null && now.isBefore(tokenExpiresAt.minusSeconds(60))) {
            return cached
        }

        val assertion = ServiceAccountJwtSigner.createSignedAssertion(credentials, now = now)
        val formBody = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", assertion)
            .build()

        val request = Request.Builder()
            .url(credentials.tokenUri)
            .post(formBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SyncException(
                        message = "OAuth token exchange HTTP ${response.code}: ${rawBody.take(512)}",
                        httpStatus = response.code,
                        isAuthError = true,
                    )
                }
                val tokenDto = json.decodeFromString(TokenResponse.serializer(), rawBody)
                cachedAccessToken = tokenDto.accessToken
                tokenExpiresAt = now.plusSeconds(tokenDto.expiresIn)
                return tokenDto.accessToken
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: IOException) {
            throw SyncException(
                message = "Не вдалося отримати OAuth-токен Google: ${e.message}",
                cause = e,
            )
        }
    }

    private suspend fun invalidateToken() = tokenMutex.withLock {
        cachedAccessToken = null
        tokenExpiresAt = Instant.EPOCH
    }

    private fun escapeDriveQuery(value: String): String {
        return value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun DriveFileDto.toRemoteMeta(): RemoteFileMeta {
        return RemoteFileMeta(
            id = id,
            name = name,
            mimeType = mimeType,
            modifiedTimeUtc = modifiedTime,
            md5Checksum = md5Checksum,
            sizeBytes = size?.toLongOrNull(),
        )
    }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FILE_FIELDS = "id,name,mimeType,modifiedTime,md5Checksum,size"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

