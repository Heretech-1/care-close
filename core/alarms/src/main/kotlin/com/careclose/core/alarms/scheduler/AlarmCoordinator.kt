package com.careclose.core.alarms.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.careclose.core.alarms.AlarmDependenciesProvider
import com.careclose.core.alarms.AlarmKind
import com.careclose.core.alarms.AlarmPayload
import com.careclose.core.alarms.AlarmPermissionChecker
import com.careclose.core.alarms.receiver.AlarmFireReceiver
import com.careclose.core.alarms.receiver.CaregiverCheckReceiver
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.Criticality
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.Role
import com.careclose.core.model.engine.DailyScheduleResolver
import com.careclose.core.model.engine.ResolvedTaskStatus
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class AlarmCoordinator(
    private val context: Context,
    private val repository: CareCloseRepository,
    private val deviceConfigStore: DeviceConfigStore,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val workManager = WorkManager.getInstance(context)

    suspend fun rescheduleForCurrentRole(now: Instant = Instant.now()) {
        when (deviceConfigStore.getRole()) {
            Role.ELDER -> {
                cancelCaregiverSchedule()
                scheduleNextElderAlarm(now)
            }

            Role.CAREGIVER -> {
                cancelElderAlarm()
                scheduleNextCaregiverCheck(now)
                ensureCaregiverPeriodicBackup()
            }

            null -> {
                cancelAll()
            }
        }
    }

    suspend fun scheduleNextElderAlarm(now: Instant = Instant.now()): Instant? {
        val plan = repository.getActivePlan() ?: return null
        val zone = ZoneId.of(plan.timezone)
        val today = LocalDate.now(zone)

        val todayEvents = repository.observeEventsForDate(today.toString()).first()
        val presence = repository.observeLatestElderPresence().first()
        val todayTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = today,
            events = todayEvents,
            elderPresence = presence,
            now = now,
        )

        val tomorrow = today.plusDays(1)
        val tomorrowEvents = repository.observeEventsForDate(tomorrow.toString()).first()
        val tomorrowTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = tomorrow,
            events = tomorrowEvents,
            elderPresence = presence,
            now = now,
        )

        val nextAt = todayTimeline.nextAlarmCandidateAt ?: tomorrowTimeline.nextAlarmCandidateAt
        if (nextAt == null) {
            cancelElderAlarm()
            return null
        }

        val triggerMillis = maxOf(nextAt.toEpochMilli(), now.toEpochMilli() + 1_000L)
        val firePendingIntent = elderFirePendingIntent()
        val permissionStatus = AlarmPermissionChecker.check(context)

        if (permissionStatus.canScheduleExactAlarms && alarmManager != null) {
            val showIntent = (context.applicationContext as? AlarmDependenciesProvider)
                ?.createMainActivityIntent(context)
                ?: Intent()
            val showPendingIntent = PendingIntent.getActivity(
                context,
                REQ_ELDER_SHOW,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerMillis, showPendingIntent),
                    firePendingIntent,
                )
            }.onFailure {
                scheduleWorkFallback(triggerMillis, now)
            }
        } else if (alarmManager != null) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerMillis,
                firePendingIntent,
            )
        } else {
            scheduleWorkFallback(triggerMillis, now)
        }

        return Instant.ofEpochMilli(triggerMillis)
    }

    suspend fun scheduleNextCaregiverCheck(now: Instant = Instant.now()): Instant? {
        val plan = repository.getActivePlan() ?: return null
        val zone = ZoneId.of(plan.timezone)
        val today = LocalDate.now(zone)
        val todayEvents = repository.observeEventsForDate(today.toString()).first()
        val presence = repository.observeLatestElderPresence().first()

        val todayTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = today,
            events = todayEvents,
            elderPresence = presence,
            now = now,
        )
        val tomorrow = today.plusDays(1)
        val tomorrowEvents = repository.observeEventsForDate(tomorrow.toString()).first()
        val tomorrowTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = tomorrow,
            events = tomorrowEvents,
            elderPresence = presence,
            now = now,
        )

        val nextCheckAt = todayTimeline.nextCaregiverCheckAt ?: tomorrowTimeline.nextCaregiverCheckAt
        if (nextCheckAt == null) {
            cancelCaregiverAlarmOnly()
            return null
        }

        val triggerMillis = maxOf(nextCheckAt.toEpochMilli(), now.toEpochMilli() + 2_000L)
        val pendingIntent = caregiverCheckPendingIntent()
        val canExact = AlarmPermissionChecker.check(context).canScheduleExactAlarms

        if (alarmManager != null) {
            if (canExact) {
                runCatching {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerMillis,
                        pendingIntent,
                    )
                }.onFailure {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                }
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent,
                )
            }
        }

        return Instant.ofEpochMilli(triggerMillis)
    }

    suspend fun resolveDueAlarmPayload(now: Instant = Instant.now()): AlarmPayload? {
        val plan = repository.getActivePlan() ?: return null
        val zone = ZoneId.of(plan.timezone)
        val today = LocalDate.now(zone)
        val events = repository.observeEventsForDate(today.toString()).first()
        val presence = repository.observeLatestElderPresence().first()

        // Допуск +30 сек компенсує незначні відхилення таймера AlarmManager
        val effectiveNow = now.plusSeconds(30)
        val timeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = today,
            events = events,
            elderPresence = presence,
            now = effectiveNow,
        )

        val dueTask = timeline.tasks
            .filter { resolved ->
                when (resolved.status) {
                    ResolvedTaskStatus.DUE_NOW,
                    ResolvedTaskStatus.MISSED_ONLINE,
                    ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> true

                    ResolvedTaskStatus.SNOOZED -> {
                        val until = resolved.snoozedUntil
                        until != null && !effectiveNow.isBefore(until)
                    }

                    else -> false
                }
            }
            .sortedWith(
                compareByDescending<com.careclose.core.model.engine.ResolvedDailyTask> {
                    it.task.criticality == Criticality.CRITICAL
                }.thenBy { it.scheduledAt ?: Instant.MAX },
            )
            .firstOrNull()

        if (dueTask != null) {
            val scheduledAt = dueTask.scheduledAt ?: now
            val attempt = computeAttemptNumber(plan, dueTask.task.id, scheduledAt, effectiveNow)
            val voicePath = dueTask.task.voiceMediaId?.let { repository.getCachedMedia(it)?.localFilePath }
            val photoPath = dueTask.task.photoMediaId?.let { repository.getCachedMedia(it)?.localFilePath }
            return AlarmPayload(
                kind = if (attempt > 0) AlarmKind.TASK_ESCALATION_REPEAT else AlarmKind.TASK_DUE,
                targetId = dueTask.task.id,
                logicalDate = today.toString(),
                planVersion = plan.planVersion,
                title = dueTask.task.title,
                subtitle = dueTask.task.doseLabel,
                instruction = dueTask.task.instruction,
                doseVisualCount = dueTask.task.doseVisualCount,
                criticality = dueTask.task.criticality,
                voiceMediaPath = voicePath,
                photoMediaPath = photoPath,
                scheduledAtUtc = scheduledAt.toString(),
                attemptNumber = attempt,
                recipientGender = plan.recipientRole.gender,
            )
        }

        val dueAnchor = timeline.anchors.firstOrNull { anchor ->
            val softAt = anchor.softReminderAt
            !anchor.isConfirmed && softAt != null && !effectiveNow.isBefore(softAt)
        }

        return dueAnchor?.let {
            AlarmPayload(
                kind = AlarmKind.ANCHOR_SOFT_REMINDER,
                targetId = it.anchor.id,
                logicalDate = today.toString(),
                planVersion = plan.planVersion,
                title = "Час події: ${it.anchor.title}",
                subtitle = "Підтвердіть у додатку, коли почнете",
                criticality = Criticality.SOFT,
                scheduledAtUtc = (it.softReminderAt ?: now).toString(),
                recipientGender = plan.recipientRole.gender,
            )
        }
    }

    fun ensureCaregiverPeriodicBackup() {
        val request = PeriodicWorkRequestBuilder<CaregiverBackupSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            CAREGIVER_BACKUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll() {
        cancelElderAlarm()
        cancelCaregiverSchedule()
    }

    private fun cancelElderAlarm() {
        alarmManager?.cancel(elderFirePendingIntent())
        workManager.cancelUniqueWork(ELDER_FALLBACK_WORK_NAME)
    }

    private fun cancelCaregiverSchedule() {
        cancelCaregiverAlarmOnly()
        workManager.cancelUniqueWork(CAREGIVER_BACKUP_WORK_NAME)
    }

    private fun cancelCaregiverAlarmOnly() {
        alarmManager?.cancel(caregiverCheckPendingIntent())
    }

    private fun scheduleWorkFallback(triggerMillis: Long, now: Instant) {
        val delayMs = (triggerMillis - now.toEpochMilli()).coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<ElderFallbackAlarmWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            ELDER_FALLBACK_WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun computeAttemptNumber(
        plan: PlanSnapshot,
        taskId: String,
        scheduledAt: Instant,
        now: Instant,
    ): Int {
        val task = plan.tasks.firstOrNull { it.id == taskId } ?: return 0
        val interval = (task.escalation?.repeatIntervalMin ?: 10).coerceAtLeast(1)
        val elapsedMin = Duration.between(scheduledAt, now).toMinutes().coerceAtLeast(0L)
        return (elapsedMin / interval).toInt()
    }

    private fun elderFirePendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmFireReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ_ELDER_FIRE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun caregiverCheckPendingIntent(): PendingIntent {
        val intent = Intent(context, CaregiverCheckReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ_CAREGIVER_CHECK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val REQ_ELDER_FIRE = 1001
        const val REQ_ELDER_SHOW = 1002
        const val REQ_CAREGIVER_CHECK = 2001
        const val ELDER_FALLBACK_WORK_NAME = "careclose_elder_fallback_alarm"
        const val CAREGIVER_BACKUP_WORK_NAME = "careclose_caregiver_backup_sync"
    }
}

class CaregiverBackupSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val provider = applicationContext as? AlarmDependenciesProvider ?: return Result.success()
        if (provider.deviceConfigStore.getRole() != Role.CAREGIVER) return Result.success()
        provider.caregiverCheckHandler.runPredictiveCheck()
        provider.alarmCoordinator.scheduleNextCaregiverCheck()
        return Result.success()
    }
}

class ElderFallbackAlarmWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val provider = applicationContext as? AlarmDependenciesProvider ?: return Result.success()
        if (provider.deviceConfigStore.getRole() != Role.ELDER) return Result.success()
        val payload = provider.alarmCoordinator.resolveDueAlarmPayload()
        if (payload != null) {
            com.careclose.core.alarms.service.AlarmRingingService.start(applicationContext, payload)
        }
        provider.alarmCoordinator.scheduleNextElderAlarm()
        return Result.success()
    }
}

