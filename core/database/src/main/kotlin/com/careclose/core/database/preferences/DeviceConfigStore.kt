package com.careclose.core.database.preferences

import android.content.Context
import com.careclose.core.model.AppLanguage
import com.careclose.core.model.AppThemeMode
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

    fun getThemeMode(): AppThemeMode
    fun setThemeMode(mode: AppThemeMode)

    fun getAppLanguage(): AppLanguage
    fun setAppLanguage(lang: AppLanguage)

    fun getCaregiverPin(): String
    fun setCaregiverPin(pin: String)
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

    override fun getThemeMode(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, null) ?: return AppThemeMode.SYSTEM
        return runCatching { AppThemeMode.valueOf(raw) }.getOrDefault(AppThemeMode.SYSTEM)
    }

    override fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    override fun getAppLanguage(): AppLanguage {
        val raw = prefs.getString(KEY_APP_LANGUAGE, null) ?: return AppLanguage.SYSTEM
        return runCatching { AppLanguage.valueOf(raw) }.getOrDefault(AppLanguage.SYSTEM)
    }

    override fun setAppLanguage(lang: AppLanguage) {
        prefs.edit().putString(KEY_APP_LANGUAGE, lang.name).apply()
    }

    override fun getCaregiverPin(): String {
        return prefs.getString(KEY_CAREGIVER_PIN, "1234") ?: "1234"
    }

    override fun setCaregiverPin(pin: String) {
        val cleanPin = pin.trim().takeIf { it.length == 4 && it.all { ch -> ch.isDigit() } } ?: "1234"
        prefs.edit().putString(KEY_CAREGIVER_PIN, cleanPin).apply()
    }

    private companion object {
        const val PREFS_NAME = "careclose_device_config"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ROLE = "device_role"
        const val KEY_CAREGIVER_ACCESS_LEVEL = "caregiver_access_level"
        const val KEY_CAREGIVER_ROLE = "caregiver_role"
        const val KEY_CAREGIVER_NAME = "caregiver_name"
        const val KEY_THEME_MODE = "app_theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_CAREGIVER_PIN = "caregiver_pin"
    }
}


