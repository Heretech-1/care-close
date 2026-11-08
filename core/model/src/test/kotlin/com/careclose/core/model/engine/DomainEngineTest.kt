package com.careclose.core.model.engine

import com.careclose.core.model.AnchorRule
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Criticality
import com.careclose.core.model.DevicePresence
import com.careclose.core.model.EscalationPolicy
import com.careclose.core.model.NetworkType
import com.careclose.core.model.PillInventory
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.Role
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DomainEngineTest {

    private val kyivZone = ZoneId.of("Europe/Kyiv")
    private val testDate = LocalDate.of(2026, 11, 8) // Sunday (dayOfWeek = 7)

    private fun kyivInstant(hour: Int, minute: Int, day: LocalDate = testDate): Instant {
        return ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, hour, minute, 0, 0, kyivZone).toInstant()
    }

    private fun basePlan(): PlanSnapshot = PlanSnapshot(
        planVersion = 14L,
        effectiveFromUtc = "2026-11-08T00:00:00Z",
        timezone = "Europe/Kyiv",
        anchors = listOf(
            AnchorRule(
                id = "anchor_breakfast",
                title = "Сніданок",
                windowStart = "07:30",
                windowEnd = "10:30",
                softReminderAt = "09:00",
            ),
        ),
        tasks = listOf(
            TaskRule(
                id = "med_morning",
                type = TaskType.MEDICATION,
                criticality = Criticality.CRITICAL,
                title = "Еутирокс",
                schedule = ScheduleRule.Fixed(time = "08:00"),
                escalation = EscalationPolicy(repeatIntervalMin = 10, maxRepeats = 2, caregiverDeadlineMin = 30),
                inventory = PillInventory(
                    trackStock = true,
                    remainingUnits = 10.0,
                    unitsPerDose = 1.0,
                    lowThresholdUnits = 8.0,
                ),
            ),
            TaskRule(
                id = "med_after_breakfast",
                type = TaskType.MEDICATION,
                criticality = Criticality.CRITICAL,
                title = "Метформін",
                schedule = ScheduleRule.RelativeToAnchor(anchorId = "anchor_breakfast", offsetMinutes = 20),
                escalation = EscalationPolicy(repeatIntervalMin = 10, maxRepeats = 2, caregiverDeadlineMin = 30),
            ),
            TaskRule(
                id = "habit_water",
                type = TaskType.ROUTINE,
                criticality = Criticality.SOFT,
                title = "Вода",
                schedule = ScheduleRule.Fixed(time = "11:00"),
            ),
        ),
    )

    @Test
    fun `relative task stays waiting until anchor is confirmed and then shifts dynamically`() {
        val plan = basePlan()
        val nowBeforeAnchor = kyivInstant(8, 10)
        val freshPresence = DevicePresence(
            deviceId = "elder_1",
            role = Role.ELDER,
            updatedAtUtc = kyivInstant(8, 5).toString(),
            batteryPercent = 80,
            isCharging = false,
            networkType = NetworkType.WIFI,
            alarmVolumePercent = 80,
            isDoNotDisturbActive = false,
            activePlanVersion = 14L,
        )

        val beforeTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = emptyList(),
            elderPresence = freshPresence,
            now = nowBeforeAnchor,
        )

        val relativeBefore = beforeTimeline.tasks.first { it.task.id == "med_after_breakfast" }
        assertEquals(ResolvedTaskStatus.WAITING_FOR_ANCHOR, relativeBefore.status)
        assertNull(relativeBefore.scheduledAt)
        assertEquals("Сніданок", relativeBefore.anchorTitle)

        // Elder confirms breakfast at 09:15
        val breakfastEvent = CareEvent(
            eventId = "ev_anchor_1",
            deviceId = "elder_1",
            actorRole = Role.ELDER,
            logicalDate = testDate.toString(),
            planVersion = 14L,
            taskId = "anchor_breakfast",
            type = CareEventType.ANCHOR_CONFIRMED,
            occurredAtUtc = kyivInstant(9, 15).toString(),
        )

        val afterTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = listOf(breakfastEvent),
            elderPresence = freshPresence,
            now = kyivInstant(9, 20),
        )

        val relativeAfter = afterTimeline.tasks.first { it.task.id == "med_after_breakfast" }
        assertEquals(ResolvedTaskStatus.PENDING, relativeAfter.status)
        assertEquals(kyivInstant(9, 35), relativeAfter.scheduledAt)
        assertEquals(kyivInstant(9, 35), afterTimeline.nextAlarmCandidateAt)
    }

    @Test
    fun `missed critical task distinguishes online ignore from offline device`() {
        val plan = basePlan()
        val checkTime = kyivInstant(8, 35) // 08:00 + 30 min deadline passed

        val onlinePresence = DevicePresence(
            deviceId = "elder_1",
            role = Role.ELDER,
            updatedAtUtc = kyivInstant(8, 20).toString(),
            batteryPercent = 70,
            isCharging = false,
            networkType = NetworkType.WIFI,
            alarmVolumePercent = 80,
            isDoNotDisturbActive = false,
            activePlanVersion = 14L,
        )

        val timelineOnline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = emptyList(),
            elderPresence = onlinePresence,
            now = checkTime,
        )
        assertEquals(
            ResolvedTaskStatus.MISSED_ONLINE,
            timelineOnline.tasks.first { it.task.id == "med_morning" }.status,
        )

        val stalePresence = onlinePresence.copy(
            updatedAtUtc = kyivInstant(7, 0).toString(),
        )
        val timelineOffline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = emptyList(),
            elderPresence = stalePresence,
            now = checkTime,
        )
        assertEquals(
            ResolvedTaskStatus.MISSED_DEVICE_OFFLINE,
            timelineOffline.tasks.first { it.task.id == "med_morning" }.status,
        )
    }

    @Test
    fun `undo event reverts accidental taken and caregiver override completes task`() {
        val plan = basePlan()
        val events = listOf(
            CareEvent(
                eventId = "ev_1",
                deviceId = "elder_1",
                actorRole = Role.ELDER,
                logicalDate = testDate.toString(),
                planVersion = 14L,
                taskId = "med_morning",
                type = CareEventType.TAKEN,
                occurredAtUtc = kyivInstant(8, 2).toString(),
            ),
            CareEvent(
                eventId = "ev_2",
                deviceId = "elder_1",
                actorRole = Role.ELDER,
                logicalDate = testDate.toString(),
                planVersion = 14L,
                taskId = "med_morning",
                type = CareEventType.TAKEN_UNDONE,
                occurredAtUtc = kyivInstant(8, 2).plusSeconds(3).toString(),
                targetEventId = "ev_1",
            ),
        )

        val timelineAfterUndo = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = events,
            elderPresence = null,
            now = kyivInstant(8, 5),
        )
        assertEquals(
            ResolvedTaskStatus.DUE_NOW,
            timelineAfterUndo.tasks.first { it.task.id == "med_morning" }.status,
        )

        val overrideEvent = CareEvent(
            eventId = "ev_3",
            deviceId = "caregiver_1",
            actorRole = Role.CAREGIVER,
            logicalDate = testDate.toString(),
            planVersion = 14L,
            taskId = "med_morning",
            type = CareEventType.CAREGIVER_OVERRIDE_TAKEN,
            occurredAtUtc = kyivInstant(8, 7).toString(),
            note = "Дав особисто",
        )

        val timelineAfterOverride = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = events + overrideEvent,
            elderPresence = null,
            now = kyivInstant(8, 8),
        )
        val med = timelineAfterOverride.tasks.first { it.task.id == "med_morning" }
        assertEquals(ResolvedTaskStatus.COMPLETED, med.status)
        assertEquals(Role.CAREGIVER, med.completedBy)
    }

    @Test
    fun `inventory deducts unique taken days and flags low stock`() {
        val plan = basePlan()
        val events = listOf(
            CareEvent(
                eventId = "e1",
                deviceId = "elder_1",
                actorRole = Role.ELDER,
                logicalDate = "2026-11-08",
                planVersion = 14L,
                taskId = "med_morning",
                type = CareEventType.TAKEN,
                occurredAtUtc = "2026-11-08T08:05:00Z",
            ),
            CareEvent(
                eventId = "e2",
                deviceId = "elder_1",
                actorRole = Role.ELDER,
                logicalDate = "2026-11-09",
                planVersion = 14L,
                taskId = "med_morning",
                type = CareEventType.TAKEN,
                occurredAtUtc = "2026-11-09T08:05:00Z",
            ),
            // Duplicate tap on same day should not double-deduct
            CareEvent(
                eventId = "e3",
                deviceId = "caregiver_1",
                actorRole = Role.CAREGIVER,
                logicalDate = "2026-11-09",
                planVersion = 14L,
                taskId = "med_morning",
                type = CareEventType.CAREGIVER_OVERRIDE_TAKEN,
                occurredAtUtc = "2026-11-09T08:10:00Z",
            ),
        )

        val stock = InventoryCalculator.calculate(plan, events).single()
        assertEquals(8.0, stock.remainingUnits, 0.001)
        assertTrue(stock.isLowStock)
        assertEquals(8, stock.estimatedDaysRemaining)
    }

    @Test
    fun `presence evaluator flags low alarm volume dnd and low battery`() {
        val now = kyivInstant(14, 0)
        val presence = DevicePresence(
            deviceId = "elder_1",
            role = Role.ELDER,
            updatedAtUtc = kyivInstant(13, 50).toString(),
            batteryPercent = 12,
            isCharging = false,
            networkType = NetworkType.CELLULAR,
            alarmVolumePercent = 10,
            isDoNotDisturbActive = true,
            activePlanVersion = 13L,
        )

        val health = PresenceEvaluator.evaluate(
            presence = presence,
            expectedPlanVersion = 14L,
            now = now,
        )

        assertTrue(health.isOnline)
        assertFalse(PresenceWarning.DEVICE_OFFLINE in health.warnings)
        assertTrue(PresenceWarning.LOW_BATTERY_NOT_CHARGING in health.warnings)
        assertTrue(PresenceWarning.ALARM_VOLUME_LOW in health.warnings)
        assertTrue(PresenceWarning.DO_NOT_DISTURB_ACTIVE in health.warnings)
        assertTrue(PresenceWarning.OUTDATED_PLAN_VERSION in health.warnings)
    }

    @Test
    fun `polymorphic schedule rule serializes with mode discriminator`() {
        val plan = basePlan()
        val encoded = CareCloseJson.instance.encodeToString(PlanSnapshot.serializer(), plan)
        assertTrue(encoded.contains("\"mode\":\"FIXED\""))
        assertTrue(encoded.contains("\"mode\":\"RELATIVE_TO_ANCHOR\""))

        val decoded = CareCloseJson.instance.decodeFromString(PlanSnapshot.serializer(), encoded)
        assertNotNull(decoded)
        assertEquals(plan, decoded)
    }

    @Test
    fun `anchor soft reminder and expiration behavior in daily timeline`() {
        val plan = basePlan()
        val morningBeforeWindow = kyivInstant(7, 0)
        val timeline1 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = emptyList(),
            elderPresence = null,
            now = morningBeforeWindow,
        )
        val anchor1 = timeline1.anchors.single()
        assertFalse(anchor1.isConfirmed)
        assertFalse(anchor1.isWindowExpired)
        assertEquals(kyivInstant(9, 0), anchor1.softReminderAt)
        // Soft reminder at 09:00 is a candidate alarm for elder
        assertTrue(timeline1.nextAlarmCandidateAt == kyivInstant(8, 0)) // 08:00 fixed med is earlier

        val afterWindowExpired = kyivInstant(11, 0)
        val timeline2 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = emptyList(),
            elderPresence = null,
            now = afterWindowExpired,
        )
        val anchor2 = timeline2.anchors.single()
        assertFalse(anchor2.isConfirmed)
        assertTrue(anchor2.isWindowExpired)
    }

    @Test
    fun `snoozed task schedules candidate alarm and returns to due now after snooze expires`() {
        val plan = basePlan()
        val snoozeEvent = CareEvent(
            eventId = "ev_snooze_1",
            deviceId = "elder_1",
            actorRole = Role.ELDER,
            logicalDate = testDate.toString(),
            planVersion = 14L,
            taskId = "med_morning",
            type = CareEventType.SNOOZED,
            occurredAtUtc = kyivInstant(8, 2).toString(),
            snoozeUntilUtc = kyivInstant(8, 12).toString(),
        )

        // During snooze (08:05): status is SNOOZED, next alarm candidate is 08:12
        val timelineDuringSnooze = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = listOf(snoozeEvent),
            elderPresence = null,
            now = kyivInstant(8, 5),
        )
        val taskDuring = timelineDuringSnooze.tasks.first { it.task.id == "med_morning" }
        assertEquals(ResolvedTaskStatus.SNOOZED, taskDuring.status)
        assertEquals(kyivInstant(8, 12), taskDuring.snoozedUntil)
        assertEquals(kyivInstant(8, 12), timelineDuringSnooze.nextAlarmCandidateAt)

        // After snooze elapsed (08:15): status returns to DUE_NOW
        val timelineAfterSnooze = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = testDate,
            events = listOf(snoozeEvent),
            elderPresence = null,
            now = kyivInstant(8, 15),
        )
        val taskAfter = timelineAfterSnooze.tasks.first { it.task.id == "med_morning" }
        assertEquals(ResolvedTaskStatus.DUE_NOW, taskAfter.status)
    }

    @Test
    fun `verifies ElderQuickReturnMode serialization and default values`() {
        val plan = basePlan()
        assertEquals(com.careclose.core.model.ElderQuickReturnMode.BOTH, plan.quickReturnMode)
        assertTrue(plan.quickReturnMode.isBubbleEnabled)
        assertTrue(plan.quickReturnMode.isNotificationEnabled)

        val jsonStr = CareCloseJson.instance.encodeToString(PlanSnapshot.serializer(), plan)
        val decoded = CareCloseJson.instance.decodeFromString(PlanSnapshot.serializer(), jsonStr)
        assertEquals(com.careclose.core.model.ElderQuickReturnMode.BOTH, decoded.quickReturnMode)

        val bubbleOnly = plan.copy(quickReturnMode = com.careclose.core.model.ElderQuickReturnMode.FLOATING_BUBBLE_ONLY)
        assertTrue(bubbleOnly.quickReturnMode.isBubbleEnabled)
        assertFalse(bubbleOnly.quickReturnMode.isNotificationEnabled)

        val notifOnly = plan.copy(quickReturnMode = com.careclose.core.model.ElderQuickReturnMode.ONGOING_NOTIFICATION_ONLY)
        assertFalse(notifOnly.quickReturnMode.isBubbleEnabled)
        assertTrue(notifOnly.quickReturnMode.isNotificationEnabled)
    }

    @Test
    fun `verifies EmergencyPing care event types and caregiverPhone in PlanSnapshot`() {
        val plan = basePlan().copy(caregiverPhone = "+380509998877")
        val jsonStr = CareCloseJson.instance.encodeToString(PlanSnapshot.serializer(), plan)
        val decoded = CareCloseJson.instance.decodeFromString(PlanSnapshot.serializer(), jsonStr)
        assertEquals("+380509998877", decoded.caregiverPhone)

        val pingEvent = CareEvent(
            eventId = "evt_ping_1",
            deviceId = "dev_cg_1",
            actorRole = Role.CAREGIVER,
            logicalDate = "2026-11-08",
            planVersion = 14L,
            taskId = "emergency_ping",
            type = CareEventType.EMERGENCY_PING,
            occurredAtUtc = "2026-11-08T15:00:00Z",
        )
        val eventJson = CareCloseJson.instance.encodeToString(CareEvent.serializer(), pingEvent)
        val decodedEvent = CareCloseJson.instance.decodeFromString(CareEvent.serializer(), eventJson)
        assertEquals(CareEventType.EMERGENCY_PING, decodedEvent.type)
    }

    @Test
    fun `one-off task resolves only on its designated date`() {
        val oneOffTask = TaskRule(
            id = "task_doctor",
            type = TaskType.ROUTINE,
            criticality = Criticality.CRITICAL,
            title = "Візит до лікаря",
            schedule = ScheduleRule.OneOff(date = "2026-11-08", time = "14:30"),
        )
        val plan = basePlan().copy(tasks = listOf(oneOffTask))

        val todayTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-08"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(10, 0),
        )
        assertEquals(1, todayTimeline.tasks.size)
        assertEquals("Візит до лікаря", todayTimeline.tasks.first().task.title)

        val tomorrowTimeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-09"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(10, 0),
        )
        assertTrue(tomorrowTimeline.tasks.isEmpty())

        val jsonStr = CareCloseJson.instance.encodeToString(PlanSnapshot.serializer(), plan)
        val decoded = CareCloseJson.instance.decodeFromString(PlanSnapshot.serializer(), jsonStr)
        val decodedRule = decoded.tasks.first().schedule as ScheduleRule.OneOff
        assertEquals("2026-11-08", decodedRule.date)
        assertEquals("14:30", decodedRule.time)
    }

    @Test
    fun `every N days task resolves accurately according to interval and start date`() {
        val intervalTask = TaskRule(
            id = "task_patch",
            type = TaskType.MEDICATION,
            criticality = Criticality.CRITICAL,
            title = "Зміна пластиру",
            schedule = ScheduleRule.EveryNDays(time = "09:00", intervalDays = 3, startDate = "2026-11-08"),
        )
        val plan = basePlan().copy(tasks = listOf(intervalTask))

        val day0 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-08"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(8, 0),
        )
        assertEquals(1, day0.tasks.size)

        val day1 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-09"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(8, 0),
        )
        assertTrue(day1.tasks.isEmpty())

        val day2 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-10"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(8, 0),
        )
        assertTrue(day2.tasks.isEmpty())

        val day3 = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-11"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(8, 0),
        )
        assertEquals(1, day3.tasks.size)

        val beforeStart = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = LocalDate.parse("2026-11-07"),
            events = emptyList(),
            elderPresence = null,
            now = kyivInstant(8, 0),
        )
        assertTrue(beforeStart.tasks.isEmpty())
    }
}

