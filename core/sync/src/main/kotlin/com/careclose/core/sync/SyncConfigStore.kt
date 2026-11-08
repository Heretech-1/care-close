package com.careclose.core.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.PairingQrPayload
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SyncConfigStore {
    fun getPairingPayload(): PairingQrPayload?
    fun savePairingPayload(payload: PairingQrPayload)
    fun clearPairing()
    fun isPaired(): Boolean

    fun getAllProfiles(): List<PairingQrPayload>
    fun getActiveProfileId(): String?
    fun setActiveProfileId(familyId: String)
    fun removeProfile(familyId: String)
}

class AndroidSyncConfigStore(context: Context) : SyncConfigStore, SyncCheckpointStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = CareCloseJson.instance

    override fun getPairingPayload(): PairingQrPayload? {
        val activeId = getActiveProfileId()
        if (activeId != null) {
            val fromList = getAllProfiles().firstOrNull { it.familyId == activeId }
            if (fromList != null) return fromList
        }
        val stored = prefs.getString(KEY_ENCRYPTED_PAIRING, null) ?: return null
        return runCatching {
            val rawJson = decryptWithKeystore(stored)
            json.decodeFromString(PairingQrPayload.serializer(), rawJson)
        }.getOrNull()
    }

    override fun savePairingPayload(payload: PairingQrPayload) {
        val currentProfiles = getAllProfiles().filterNot { it.familyId == payload.familyId }
        val updatedList = currentProfiles + payload
        saveProfileList(updatedList)
        setActiveProfileId(payload.familyId)
    }

    override fun getAllProfiles(): List<PairingQrPayload> {
        val storedList = prefs.getString(KEY_ENCRYPTED_PROFILES, null)
        if (storedList != null) {
            val list = runCatching {
                val rawJson = decryptWithKeystore(storedList)
                json.decodeFromString(ListSerializer(PairingQrPayload.serializer()), rawJson)
            }.getOrNull()
            if (!list.isNullOrEmpty()) return list
        }
        val legacy = runCatching {
            val legacyRaw = prefs.getString(KEY_ENCRYPTED_PAIRING, null)?.let { decryptWithKeystore(it) }
            legacyRaw?.let { json.decodeFromString(PairingQrPayload.serializer(), it) }
        }.getOrNull()
        return listOfNotNull(legacy)
    }

    override fun getActiveProfileId(): String? {
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
    }

    override fun setActiveProfileId(familyId: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, familyId).apply()
        val match = getAllProfiles().firstOrNull { it.familyId == familyId }
        if (match != null) {
            val rawJson = json.encodeToString(PairingQrPayload.serializer(), match)
            val encrypted = encryptWithKeystore(rawJson)
            prefs.edit().putString(KEY_ENCRYPTED_PAIRING, encrypted).apply()
        }
    }

    override fun removeProfile(familyId: String) {
        val updated = getAllProfiles().filterNot { it.familyId == familyId }
        saveProfileList(updated)
        if (getActiveProfileId() == familyId) {
            val next = updated.firstOrNull()
            if (next != null) {
                setActiveProfileId(next.familyId)
            } else {
                clearPairing()
            }
        }
    }

    override fun clearPairing() {
        prefs.edit().clear().apply()
    }

    override fun isPaired(): Boolean = prefs.contains(KEY_ENCRYPTED_PAIRING) || prefs.contains(KEY_ENCRYPTED_PROFILES)

    override fun getCachedChecksum(fileId: String): String? {
        return prefs.getString("$CHECKPOINT_PREFIX$fileId", null)
    }

    override fun putCachedChecksum(fileId: String, checksum: String) {
        prefs.edit().putString("$CHECKPOINT_PREFIX$fileId", checksum).apply()
    }

    private fun saveProfileList(profiles: List<PairingQrPayload>) {
        val rawJson = json.encodeToString(ListSerializer(PairingQrPayload.serializer()), profiles)
        val encrypted = encryptWithKeystore(rawJson)
        prefs.edit().putString(KEY_ENCRYPTED_PROFILES, encrypted).apply()
    }

    private fun encryptWithKeystore(plainText: String): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ivBase64 = Base64.getEncoder().encodeToString(cipher.iv)
        val dataBase64 = Base64.getEncoder().encodeToString(
            cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)),
        )
        return "$ivBase64:$dataBase64"
    }

    private fun decryptWithKeystore(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2) { "Invalid keystore blob" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ciphertext = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "careclose_sync_store"
        const val KEY_ENCRYPTED_PAIRING = "encrypted_pairing_payload"
        const val KEY_ENCRYPTED_PROFILES = "encrypted_profiles_list"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        const val CHECKPOINT_PREFIX = "md5_"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "careclose_pairing_master_key"
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
