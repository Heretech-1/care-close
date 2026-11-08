package com.careclose.core.model.engine

import com.careclose.core.model.AnchorRule
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Criticality
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.Role
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ResolvedTaskStatus {
    WAITING_FOR_ANCHOR,
    PENDING,
    DUE_NOW,
    SNOOZED,
    COMPLETED,
    SKIPPED,
    MISSED_ONLINE,
    MISSED_DEVICE_OFFLINE,
}

data class ResolvedDailyAnchor(
    val anchor: AnchorRule,
    val logicalDate: LocalDate,
    val windowStartAt: Instant,
    val windowEndAt: Instant,
    val softReminderAt: Instant?,
    val confirmedAt: Instant?,
    val isConfirmed: Boolean,
    val isWindowExpired: Boolean,
)

data class ResolvedDailyTask(
    val task: TaskRule,
    val logicalDate: LocalDate,
    val scheduledAt: Instant?,
    val caregiverDeadlineAt: Instant?,
    val status: ResolvedTaskStatus,
    val completedAt: Instant?,
    val completedBy: Role?,
    val completedEventId: String?,
    val snoozedUntil: Instant?,
    val anchorTitle: String?,
    val completionNote: String? = null,
) {
    val isMissingAnchor: Boolean
        get() = (task.schedule is ScheduleRule.RelativeToAnchor) && anchorTitle == null
}

data class DailyTimeline(
    val date: LocalDate,
    val timezone: ZoneId,
    val planVersion: Long,
    val anchors: List<ResolvedDailyAnchor>,
    val tasks: List<ResolvedDailyTask>,
    val nextAlarmCandidateAt: Instant?,
    val nextCaregiverCheckAt: Instant?,
)

object DailyScheduleResolver {

    private const val DEFAULT_REPEAT_INTERVAL_MIN = 10
    private const val DEFAULT_MAX_REPEATS = 2
    private const val DEFAULT_CAREGIVER_DEADLINE_MIN = 30
    private const val CAREGIVER_CHECK_BUFFER_SEC = 120L

    fun resolve(
        plan: PlanSnapshot,
        logicalDate: LocalDate,
        events: List<CareEvent>,
        elderPresence: DevicePresence?,
        now: Instant,
        offlineThresholdMinutes: Long = 60L,
    ): DailyTimeline {
        val zone = plan.safeZoneId
        val isoDay = logicalDate.dayOfWeek.value
        val dayString = logicalDate.toString()

        val orderedEvents = events
            .asSequence()
            .filter { it.logicalDate == dayString }
            .distinctBy { it.eventId }
            .sortedWith(
                compareBy<CareEvent> { Instant.parse(it.occurredAtUtc) }
                    .thenBy { it.eventId },
            )
            .toList()

        val eventsByTaskId = orderedEvents.groupBy { it.taskId }

        val resolvedAnchors = plan.anchors
            .filter { isoDay in it.daysOfWeek }
            .map { anchor ->
                resolveAnchor(
                    anchor = anchor,
                    logicalDate = logicalDate,
                    zone = zone,
                    events = eventsByTaskId[anchor.id].orEmpty(),
                    now = now,
                )
            }

        val anchorById = resolvedAnchors.associateBy { it.anchor.id }
        val allAnchorDefinitions = plan.anchors.associateBy { it.id }

        val resolvedTasks = plan.tasks
            .filter { isTaskScheduledOnDate(it.schedule, logicalDate) }
            .map { task ->
                resolveTask(
                    task = task,
                    logicalDate = logicalDate,
                    zone = zone,
                    anchorById = anchorById,
                    allAnchorDefinitions = allAnchorDefinitions,
                    events = eventsByTaskId[task.id].orEmpty(),
                    elderPresence = elderPresence,
                    now = now,
                    offlineThresholdMinutes = offlineThresholdMinutes,
                )
            }
            .sortedWith(
                compareBy<ResolvedDailyTask> { task ->
                    task.scheduledAt
                        ?: (task.task.schedule as? ScheduleRule.RelativeToAnchor)
                            ?.let { anchorById[it.anchorId]?.windowStartAt }
                        ?: Instant.MAX
                }.thenBy { it.task.id },
            )

        val nextAlarmCandidateAt = computeNextElderAlarm(
            anchors = resolvedAnchors,
            tasks = resolvedTasks,
            now = now,
        )

        val nextCaregiverCheckAt = computeNextCaregiverCheck(
            tasks = resolvedTasks,
            now = now,
        )

        return DailyTimeline(
            date = logicalDate,
            timezone = zone,
            planVersion = plan.planVersion,
            anchors = resolvedAnchors,
            tasks = resolvedTasks,
            nextAlarmCandidateAt = nextAlarmCandidateAt,
            nextCaregiverCheckAt = nextCaregiverCheckAt,
        )
    }

    private fun resolveAnchor(
        anchor: AnchorRule,
        logicalDate: LocalDate,
        zone: ZoneId,
        events: List<CareEvent>,
        now: Instant,
    ): ResolvedDailyAnchor {
        val windowStartAt = logicalDate
            .atTime(LocalTime.parse(anchor.windowStart))
            .atZone(zone)
            .toInstant()

        val rawWindowEnd = logicalDate
            .atTime(LocalTime.parse(anchor.windowEnd))
            .atZone(zone)
            .toInstant()
        val windowEndAt = if (rawWindowEnd.isBefore(windowStartAt)) {
            rawWindowEnd.plus(Duration.ofDays(1))
        } else {
            rawWindowEnd
        }

        val softReminderAt = anchor.softReminderAt?.let { timeStr ->
            logicalDate.atTime(LocalTime.parse(timeStr)).atZone(zone).toInstant()
        }

        val latestConfirmation = events
            .lastOrNull { it.type == CareEventType.ANCHOR_CONFIRMED }
            ?.let { Instant.parse(it.occurredAtUtc) }

        return ResolvedDailyAnchor(
            anchor = anchor,
            logicalDate = logicalDate,
            windowStartAt = windowStartAt,
            windowEndAt = windowEndAt,
            softReminderAt = softReminderAt,
            confirmedAt = latestConfirmation,
            isConfirmed = latestConfirmation != null,
            isWindowExpired = latestConfirmation == null && !now.isBefore(windowEndAt),
        )
    }

    private fun resolveTask(
        task: TaskRule,
        logicalDate: LocalDate,
        zone: ZoneId,
        anchorById: Map<String, ResolvedDailyAnchor>,
        allAnchorDefinitions: Map<String, AnchorRule>,
        events: List<CareEvent>,
        elderPresence: DevicePresence?,
        now: Instant,
        offlineThresholdMinutes: Long,
    ): ResolvedDailyTask {
        var anchorTitle: String? = null
        val scheduledAt: Instant? = when (val rule = task.schedule) {
            is ScheduleRule.Fixed -> {
                logicalDate.atTime(LocalTime.parse(rule.time)).atZone(zone).toInstant()
            }

            is ScheduleRule.RelativeToAnchor -> {
                val resolvedAnchor = anchorById[rule.anchorId]
                anchorTitle = resolvedAnchor?.anchor?.title
                    ?: allAnchorDefinitions[rule.anchorId]?.title
                resolvedAnchor?.confirmedAt?.plusSeconds(rule.offsetMinutes * 60L)
            }

            is ScheduleRule.OneOff -> {
                logicalDate.atTime(LocalTime.parse(rule.time)).atZone(zone).toInstant()
            }

            is ScheduleRule.EveryNDays -> {
                logicalDate.atTime(LocalTime.parse(rule.time)).atZone(zone).toInstant()
            }
        }

        val deadlineMin = task.escalation?.caregiverDeadlineMin ?: DEFAULT_CAREGIVER_DEADLINE_MIN
        val caregiverDeadlineAt = scheduledAt?.plusSeconds(deadlineMin * 60L)

        var completedEventId: String? = null
        var completedAt: Instant? = null
        var completedBy: Role? = null
        var completionNote: String? = null
        var isSkipped = false
        var snoozedUntil: Instant? = null

        for (event in events) {
            val eventTime = Instant.parse(event.occurredAtUtc)
            when (event.type) {
                CareEventType.TAKEN,
                CareEventType.CAREGIVER_OVERRIDE_TAKEN -> {
                    completedEventId = event.eventId
                    completedAt = eventTime
                    completedBy = event.actorRole
                    completionNote = event.note
                    isSkipped = false
                    snoozedUntil = null
                }

                CareEventType.TAKEN_UNDONE -> {
                    if (event.targetEventId == null || event.targetEventId == completedEventId) {
                        completedEventId = null
                        completedAt = null
                        completedBy = null
                        completionNote = null
                    }
                }

                CareEventType.SKIPPED -> {
                    if (completedEventId == null) {
                        isSkipped = true
                        snoozedUntil = null
                    }
                }

                CareEventType.SNOOZED -> {
                    if (completedEventId == null && !isSkipped) {
                        val repeatMin = task.escalation?.repeatIntervalMin ?: DEFAULT_REPEAT_INTERVAL_MIN
                        snoozedUntil = event.snoozeUntilUtc
                            ?.let { Instant.parse(it) }
                            ?: eventTime.plusSeconds(repeatMin * 60L)
                    }
                }

                CareEventType.ANCHOR_CONFIRMED,
                CareEventType.EMERGENCY_PING,
                CareEventType.EMERGENCY_PING_DISMISSED -> Unit
            }
        }

        val status = when {
            completedEventId != null -> ResolvedTaskStatus.COMPLETED
            isSkipped -> ResolvedTaskStatus.SKIPPED
            scheduledAt == null -> ResolvedTaskStatus.WAITING_FOR_ANCHOR
            snoozedUntil != null && now.isBefore(snoozedUntil) -> ResolvedTaskStatus.SNOOZED
            now.isBefore(scheduledAt) -> ResolvedTaskStatus.PENDING
            task.criticality == Criticality.CRITICAL &&
                caregiverDeadlineAt != null &&
                !now.isBefore(caregiverDeadlineAt) -> {
                if (isPresenceFresh(elderPresence, now, offlineThresholdMinutes)) {
                    ResolvedTaskStatus.MISSED_ONLINE
                } else {
                    ResolvedTaskStatus.MISSED_DEVICE_OFFLINE
                }
            }

            else -> ResolvedTaskStatus.DUE_NOW
        }

        return ResolvedDailyTask(
            task = task,
            logicalDate = logicalDate,
            scheduledAt = scheduledAt,
            caregiverDeadlineAt = caregiverDeadlineAt,
            status = status,
            completedAt = completedAt,
            completedBy = completedBy,
            completedEventId = completedEventId,
            snoozedUntil = snoozedUntil,
            anchorTitle = anchorTitle,
            completionNote = completionNote,
        )
    }

    private fun isPresenceFresh(
        presence: DevicePresence?,
        now: Instant,
        offlineThresholdMinutes: Long,
    ): Boolean {
        if (presence == null) return false
        val updatedAt = Instant.parse(presence.updatedAtUtc)
        val minutesSinceUpdate = Duration.between(updatedAt, now).toMinutes()
        return minutesSinceUpdate <= offlineThresholdMinutes
    }

    private fun computeNextElderAlarm(
        anchors: List<ResolvedDailyAnchor>,
        tasks: List<ResolvedDailyTask>,
        now: Instant,
    ): Instant? {
        val candidates = mutableListOf<Instant>()

        for (anchor in anchors) {
            val softReminder = anchor.softReminderAt
            if (!anchor.isConfirmed && softReminder != null && !softReminder.isBefore(now)) {
                candidates.add(softReminder)
            }
        }

        for (resolved in tasks) {
            val scheduledAt = resolved.scheduledAt ?: continue
            when (resolved.status) {
                ResolvedTaskStatus.COMPLETED,
                ResolvedTaskStatus.SKIPPED,
                ResolvedTaskStatus.WAITING_FOR_ANCHOR -> Unit

                ResolvedTaskStatus.SNOOZED -> {
                    val until = resolved.snoozedUntil
                    if (until != null && !until.isBefore(now)) {
                        candidates.add(until)
                    }
                }

                ResolvedTaskStatus.PENDING -> {
                    if (!scheduledAt.isBefore(now)) {
                        candidates.add(scheduledAt)
                    }
                }

                ResolvedTaskStatus.DUE_NOW,
                ResolvedTaskStatus.MISSED_ONLINE,
                ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> {
                    val escalation = resolved.task.escalation
                    if (escalation != null || resolved.task.criticality == Criticality.CRITICAL) {
                        val repeatIntervalMin = escalation?.repeatIntervalMin ?: DEFAULT_REPEAT_INTERVAL_MIN
                        val maxRepeats = escalation?.maxRepeats ?: DEFAULT_MAX_REPEATS
                        for (attempt in 1..maxRepeats) {
                            val repeatAt = scheduledAt.plusSeconds(attempt * repeatIntervalMin * 60L)
                            if (!repeatAt.isBefore(now)) {
                                candidates.add(repeatAt)
                                break
                            }
                        }
                    }
                }
            }
        }

        return candidates.minOrNull()
    }

    private fun computeNextCaregiverCheck(
        tasks: List<ResolvedDailyTask>,
        now: Instant,
    ): Instant? {
        return tasks
            .asSequence()
            .filter { it.task.criticality == Criticality.CRITICAL }
            .filter {
                it.status == ResolvedTaskStatus.PENDING ||
                    it.status == ResolvedTaskStatus.DUE_NOW ||
                    it.status == ResolvedTaskStatus.SNOOZED
            }
            .mapNotNull { it.caregiverDeadlineAt?.plusSeconds(CAREGIVER_CHECK_BUFFER_SEC) }
            .filter { !it.isBefore(now) }
            .minOrNull()
    }

    fun isTaskScheduledOnDate(rule: ScheduleRule, logicalDate: LocalDate): Boolean = when (rule) {
        is ScheduleRule.Fixed -> logicalDate.dayOfWeek.value in rule.daysOfWeek
        is ScheduleRule.RelativeToAnchor -> logicalDate.dayOfWeek.value in rule.daysOfWeek
        is ScheduleRule.OneOff -> logicalDate.toString() == rule.date
        is ScheduleRule.EveryNDays -> {
            runCatching {
                val start = LocalDate.parse(rule.startDate)
                if (logicalDate.isBefore(start)) {
                    false
                } else {
                    ChronoUnit.DAYS.between(start, logicalDate) % rule.intervalDays.coerceAtLeast(1) == 0L
                }
            }.getOrDefault(false)
        }
    }
}
