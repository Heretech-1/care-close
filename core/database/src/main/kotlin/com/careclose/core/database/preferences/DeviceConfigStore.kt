package com.careclose.core.database.preferences

import android.content.Context
import com.careclose.core.model.Role
import java.util.UUID

interface DeviceConfigStore {
    fun getDeviceId(): String
    fun getRole(): Role?
    fun setRole(role: Role?)
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

    private companion object {
        const val PREFS_NAME = "careclose_device_config"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ROLE = "device_role"
    }
}

