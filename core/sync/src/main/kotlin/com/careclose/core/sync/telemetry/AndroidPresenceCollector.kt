package com.careclose.core.sync.telemetry

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.NetworkType
import com.careclose.core.model.Role
import java.time.Instant

class AndroidPresenceCollector(
    private val context: Context,
    private val repository: CareCloseRepository,
    private val deviceConfigStore: DeviceConfigStore,
) {

    suspend fun collectNow(now: Instant = Instant.now()): DevicePresence {
        val role = deviceConfigStore.getRole() ?: Role.ELDER
        val deviceId = deviceConfigStore.getDeviceId()
        val (batteryPercent, isCharging) = readBatteryState()
        val networkType = readNetworkType()
        val alarmVolumePercent = readAlarmVolumePercent()
        val isDndActive = readDoNotDisturbActive()
        val activePlanVersion = repository.getActivePlan()?.planVersion ?: 0L
        val pendingCount = repository.getPendingUploadEvents().size

        return DevicePresence(
            deviceId = deviceId,
            role = role,
            updatedAtUtc = now.toString(),
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            networkType = networkType,
            alarmVolumePercent = alarmVolumePercent,
            isDoNotDisturbActive = isDndActive,
            activePlanVersion = activePlanVersion,
            lastPendingSyncCount = pendingCount,
        )
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return 0 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to isCharging
    }

    private fun readNetworkType(): NetworkType {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkType.UNKNOWN
        val network = cm.activeNetwork ?: return NetworkType.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.UNKNOWN
            else -> NetworkType.NONE
        }
    }

    private fun readAlarmVolumePercent(): Int {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 100
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return 100
        return ((current * 100f) / max).toInt().coerceIn(0, 100)
    }

    private fun readDoNotDisturbActive(): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }
}

