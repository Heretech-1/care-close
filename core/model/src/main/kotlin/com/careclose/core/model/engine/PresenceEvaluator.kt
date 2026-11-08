package com.careclose.core.model.engine

import com.careclose.core.model.DevicePresence
import java.time.Duration
import java.time.Instant

enum class PresenceWarning {
    DEVICE_OFFLINE,
    LOW_BATTERY_NOT_CHARGING,
    ALARM_VOLUME_LOW,
    DO_NOT_DISTURB_ACTIVE,
    OUTDATED_PLAN_VERSION,
}

data class PresenceHealth(
    val isOnline: Boolean,
    val minutesSinceLastSeen: Long?,
    val batteryPercent: Int?,
    val isCharging: Boolean?,
    val alarmVolumePercent: Int?,
    val warnings: List<PresenceWarning>,
)

object PresenceEvaluator {

    fun evaluate(
        presence: DevicePresence?,
        expectedPlanVersion: Long,
        now: Instant,
        offlineThresholdMinutes: Long = 60L,
        lowBatteryPercent: Int = 15,
        minAlarmVolumePercent: Int = 35,
    ): PresenceHealth {
        if (presence == null) {
            return PresenceHealth(
                isOnline = false,
                minutesSinceLastSeen = null,
                batteryPercent = null,
                isCharging = null,
                alarmVolumePercent = null,
                warnings = listOf(PresenceWarning.DEVICE_OFFLINE),
            )
        }

        val lastSeen = Instant.parse(presence.updatedAtUtc)
        val minutesSince = Duration.between(lastSeen, now).toMinutes().coerceAtLeast(0L)
        val isOnline = minutesSince <= offlineThresholdMinutes

        val warnings = buildList {
            if (!isOnline) add(PresenceWarning.DEVICE_OFFLINE)
            if (presence.batteryPercent <= lowBatteryPercent && !presence.isCharging) {
                add(PresenceWarning.LOW_BATTERY_NOT_CHARGING)
            }
            if (presence.alarmVolumePercent < minAlarmVolumePercent) {
                add(PresenceWarning.ALARM_VOLUME_LOW)
            }
            if (presence.isDoNotDisturbActive) {
                add(PresenceWarning.DO_NOT_DISTURB_ACTIVE)
            }
            if (presence.activePlanVersion < expectedPlanVersion) {
                add(PresenceWarning.OUTDATED_PLAN_VERSION)
            }
        }

        return PresenceHealth(
            isOnline = isOnline,
            minutesSinceLastSeen = minutesSince,
            batteryPercent = presence.batteryPercent,
            isCharging = presence.isCharging,
            alarmVolumePercent = presence.alarmVolumePercent,
            warnings = warnings,
        )
    }
}

