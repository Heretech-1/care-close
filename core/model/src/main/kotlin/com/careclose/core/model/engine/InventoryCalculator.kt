package com.careclose.core.model.engine

import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.TaskRule
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max

data class InventoryStatus(
    val taskId: String,
    val title: String,
    val remainingUnits: Double,
    val unitsPerDose: Double,
    val lowThresholdUnits: Double,
    val estimatedDaysRemaining: Int?,
    val isLowStock: Boolean,
)

object InventoryCalculator {

    fun calculate(
        plan: PlanSnapshot,
        events: List<CareEvent>,
    ): List<InventoryStatus> {
        val orderedEvents = events
            .distinctBy { it.eventId }
            .sortedWith(
                compareBy<CareEvent> { Instant.parse(it.occurredAtUtc) }
                    .thenBy { it.eventId },
            )

        return plan.tasks.mapNotNull { task ->
            val inventory = task.inventory ?: return@mapNotNull null
            if (!inventory.trackStock) return@mapNotNull null

            val baseline = Instant.parse(inventory.stockUpdatedAtUtc ?: plan.effectiveFromUtc)
            val netCompletedCount = countNetCompletedDoses(
                task = task,
                orderedEvents = orderedEvents,
                baselineInclusive = baseline,
            )

            val remaining = max(
                0.0,
                inventory.remainingUnits - netCompletedCount * inventory.unitsPerDose,
            )

            val daysPerWeek = task.schedule.daysOfWeek.distinct().size.coerceIn(0, 7)
            val avgDailyUsage = (daysPerWeek / 7.0) * inventory.unitsPerDose
            val estimatedDays = if (avgDailyUsage > 0.0) {
                floor(remaining / avgDailyUsage).toInt()
            } else {
                null
            }

            InventoryStatus(
                taskId = task.id,
                title = task.title,
                remainingUnits = remaining,
                unitsPerDose = inventory.unitsPerDose,
                lowThresholdUnits = inventory.lowThresholdUnits,
                estimatedDaysRemaining = estimatedDays,
                isLowStock = remaining <= inventory.lowThresholdUnits,
            )
        }
    }

    private fun countNetCompletedDoses(
        task: TaskRule,
        orderedEvents: List<CareEvent>,
        baselineInclusive: Instant,
    ): Int {
        val activeTakenByDay = mutableMapOf<String, String>()

        for (event in orderedEvents) {
            if (event.taskId != task.id) continue
            val occurredAt = Instant.parse(event.occurredAtUtc)
            if (occurredAt.isBefore(baselineInclusive)) continue

            when (event.type) {
                CareEventType.TAKEN,
                CareEventType.CAREGIVER_OVERRIDE_TAKEN -> {
                    activeTakenByDay[event.logicalDate] = event.eventId
                }

                CareEventType.TAKEN_UNDONE -> {
                    val currentId = activeTakenByDay[event.logicalDate]
                    if (event.targetEventId == null || event.targetEventId == currentId) {
                        activeTakenByDay.remove(event.logicalDate)
                    }
                }

                CareEventType.SNOOZED,
                CareEventType.SKIPPED,
                CareEventType.ANCHOR_CONFIRMED,
                CareEventType.EMERGENCY_PING,
                CareEventType.EMERGENCY_PING_DISMISSED -> Unit
            }
        }

        return activeTakenByDay.size
    }
}

