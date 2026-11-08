package com.careclose.core.alarms

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.careclose.core.alarms.scheduler.AlarmCoordinator
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.Criticality
import com.careclose.core.model.Gender
import kotlinx.serialization.Serializable

enum class AlarmKind {
    TASK_DUE,
    TASK_ESCALATION_REPEAT,
    ANCHOR_SOFT_REMINDER,
    EMERGENCY_CALL_PING,
}

@Serializable
data class AlarmPayload(
    val kind: AlarmKind,
    val targetId: String,
    val logicalDate: String,
    val planVersion: Long,
    val title: String,
    val subtitle: String? = null,
    val instruction: String? = null,
    val doseVisualCount: Double? = null,
    val criticality: Criticality = Criticality.CRITICAL,
    val voiceMediaPath: String? = null,
    val photoMediaPath: String? = null,
    val scheduledAtUtc: String,
    val attemptNumber: Int = 0,
    val recipientGender: Gender = Gender.UNSPECIFIED,
)

data class AlarmPermissionStatus(
    val canScheduleExactAlarms: Boolean,
    val canUseFullScreenIntent: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val canPostNotifications: Boolean,
    val canDrawOverlays: Boolean = true,
    val canAccessNotificationPolicy: Boolean = true,
) {
    val allCriticalGranted: Boolean
        get() = canScheduleExactAlarms && canUseFullScreenIntent && canPostNotifications
}

object AlarmPermissionChecker {

    fun check(context: Context): AlarmPermissionStatus {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)

        val exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

        val fullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager?.canUseFullScreenIntent() == true
        } else {
            true
        }

        val postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            notificationManager?.areNotificationsEnabled() == true
        }

        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        val drawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        val dndAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }

        return AlarmPermissionStatus(
            canScheduleExactAlarms = exactAlarms,
            canUseFullScreenIntent = fullScreenIntent,
            isIgnoringBatteryOptimizations = batteryUnrestricted,
            canPostNotifications = postNotifications,
            canDrawOverlays = drawOverlays,
            canAccessNotificationPolicy = dndAccess,
        )
    }

    fun notificationPolicySettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    fun overlaySettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            appDetailsIntent(context)
        }
    }

    fun exactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            appDetailsIntent(context)
        }
    }

    fun fullScreenIntentSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            appDetailsIntent(context)
        }
    }

    fun batterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}

fun interface CaregiverCheckHandler {
    suspend fun runPredictiveCheck()
}

interface AlarmDependenciesProvider {
    val careCloseRepository: CareCloseRepository
    val deviceConfigStore: DeviceConfigStore
    val alarmCoordinator: AlarmCoordinator
    val caregiverCheckHandler: CaregiverCheckHandler
    fun createMainActivityIntent(context: Context): Intent
    fun onLocalEventRecorded() {}
}

internal object AlarmExtras {
    const val EXTRA_PAYLOAD_JSON = "careclose_alarm_payload_json"
    const val ACTION_MARK_TAKEN = "com.careclose.core.alarms.ACTION_MARK_TAKEN"
    const val ACTION_SNOOZE_10M = "com.careclose.core.alarms.ACTION_SNOOZE_10M"
    const val ACTION_STOP_RINGING = "com.careclose.core.alarms.ACTION_STOP_RINGING"
}

