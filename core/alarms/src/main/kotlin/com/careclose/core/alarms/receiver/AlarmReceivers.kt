package com.careclose.core.alarms.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.careclose.core.alarms.AlarmDependenciesProvider
import com.careclose.core.alarms.AlarmExtras
import com.careclose.core.alarms.AlarmKind
import com.careclose.core.alarms.AlarmPayload
import com.careclose.core.alarms.service.AlarmRingingService
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class AlarmFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val provider = context.applicationContext as? AlarmDependenciesProvider ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (provider.deviceConfigStore.getRole() == Role.ELDER) {
                    val payload = provider.alarmCoordinator.resolveDueAlarmPayload()
                    if (payload != null) {
                        AlarmRingingService.start(context.applicationContext, payload)
                    }
                    provider.alarmCoordinator.scheduleNextElderAlarm()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val provider = context.applicationContext as? AlarmDependenciesProvider ?: return
        val payloadJson = intent.getStringExtra(AlarmExtras.EXTRA_PAYLOAD_JSON) ?: return
        val payload = runCatching {
            CareCloseJson.instance.decodeFromString(AlarmPayload.serializer(), payloadJson)
        }.getOrNull() ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                val deviceId = provider.deviceConfigStore.getDeviceId()

                val event = when (intent.action) {
                    AlarmExtras.ACTION_MARK_TAKEN -> CareEvent(
                        eventId = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        actorRole = Role.ELDER,
                        logicalDate = payload.logicalDate,
                        planVersion = payload.planVersion,
                        taskId = payload.targetId,
                        type = if (payload.kind == AlarmKind.ANCHOR_SOFT_REMINDER) {
                            CareEventType.ANCHOR_CONFIRMED
                        } else {
                            CareEventType.TAKEN
                        },
                        occurredAtUtc = now.toString(),
                    )

                    AlarmExtras.ACTION_SNOOZE_10M -> CareEvent(
                        eventId = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        actorRole = Role.ELDER,
                        logicalDate = payload.logicalDate,
                        planVersion = payload.planVersion,
                        taskId = payload.targetId,
                        type = CareEventType.SNOOZED,
                        occurredAtUtc = now.toString(),
                        snoozeUntilUtc = now.plusSeconds(10 * 60L).toString(),
                    )

                    else -> null
                }

                if (event != null) {
                    provider.careCloseRepository.recordLocalEvent(event)
                    provider.onLocalEventRecorded()
                }
                AlarmRingingService.stop(context.applicationContext)
                provider.alarmCoordinator.scheduleNextElderAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class CaregiverCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val provider = context.applicationContext as? AlarmDependenciesProvider ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (provider.deviceConfigStore.getRole() == Role.CAREGIVER) {
                    provider.caregiverCheckHandler.runPredictiveCheck()
                    provider.alarmCoordinator.scheduleNextCaregiverCheck()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val provider = context.applicationContext as? AlarmDependenciesProvider ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                provider.alarmCoordinator.rescheduleForCurrentRole()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}

