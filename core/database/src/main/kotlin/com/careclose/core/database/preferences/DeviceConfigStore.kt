package com.careclose.core.database.preferences

import android.content.Context
import com.careclose.core.model.CaregiverAccessLevel
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Role
import java.util.UUID

interface DeviceConfigStore {
    fun getDeviceId(): String
    fun getRole(): Role?
    fun setRole(role: Role?)

    fun getCaregiverAccessLevel(): CaregiverAccessLevel
    fun setCaregiverAccessLevel(level: CaregiverAccessLevel)

    fun getCaregiverRole(): CaregiverRole?
    fun setCaregiverRole(role: CaregiverRole?)

    fun getCaregiverName(): String?
    fun setCaregiverName(name: String?)
}

class SharedPrefsDeviceConfigStore(context: Context) : DeviceConfigStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "dev_${UUID.randomUUID().toString().take(12)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    override fun getRole(): Role? {
        val raw = prefs.getString(KEY_ROLE, null) ?: return null
        return runCatching { Role.valueOf(raw) }.getOrNull()
    }

    override fun setRole(role: Role?) {
        prefs.edit().apply {
            if (role == null) {
                remove(KEY_ROLE)
            } else {
                putString(KEY_ROLE, role.name)
            }
        }.apply()
    }

    override fun getCaregiverAccessLevel(): CaregiverAccessLevel {
        val raw = prefs.getString(KEY_CAREGIVER_ACCESS_LEVEL, null) ?: return CaregiverAccessLevel.PRIMARY
        return runCatching { CaregiverAccessLevel.valueOf(raw) }.getOrDefault(CaregiverAccessLevel.PRIMARY)
    }

    override fun setCaregiverAccessLevel(level: CaregiverAccessLevel) {
        prefs.edit().putString(KEY_CAREGIVER_ACCESS_LEVEL, level.name).apply()
    }

    override fun getCaregiverRole(): CaregiverRole? {
        val raw = prefs.getString(KEY_CAREGIVER_ROLE, null) ?: return null
        return runCatching { CaregiverRole.valueOf(raw) }.getOrNull()
    }

    override fun setCaregiverRole(role: CaregiverRole?) {
        prefs.edit().apply {
            if (role == null) remove(KEY_CAREGIVER_ROLE) else putString(KEY_CAREGIVER_ROLE, role.name)
        }.apply()
    }

    override fun getCaregiverName(): String? {
        return prefs.getString(KEY_CAREGIVER_NAME, null)
    }

    override fun setCaregiverName(name: String?) {
        prefs.edit().apply {
            if (name.isNullOrBlank()) remove(KEY_CAREGIVER_NAME) else putString(KEY_CAREGIVER_NAME, name.trim())
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "careclose_device_config"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ROLE = "device_role"
        const val KEY_CAREGIVER_ACCESS_LEVEL = "caregiver_access_level"
        const val KEY_CAREGIVER_ROLE = "caregiver_role"
        const val KEY_CAREGIVER_NAME = "caregiver_name"
    }
}


