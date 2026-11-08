package com.careclose.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
enum class Role {
    ELDER,
    CAREGIVER,
}

@Serializable
enum class Gender {
    MALE,
    FEMALE,
    UNSPECIFIED,
}

@Serializable
enum class RecipientRole(val defaultTitle: String, val gender: Gender) {
    MOTHER("Мама", Gender.FEMALE),
    FATHER("Тато", Gender.MALE),
    GRANDMOTHER("Бабуся", Gender.FEMALE),
    GRANDFATHER("Дідусь", Gender.MALE),
    OTHER("Підопічний", Gender.UNSPECIFIED),
}

@Serializable
enum class CaregiverRole(val defaultTitle: String, val gender: Gender) {
    DAUGHTER("Донька", Gender.FEMALE),
    SON("Син", Gender.MALE),
    GRANDDAUGHTER("Онука", Gender.FEMALE),
    GRANDSON("Онук", Gender.MALE),
    OTHER("Опікун", Gender.UNSPECIFIED),
}

@Serializable
enum class TaskType {
    MEDICATION,
    MEAL,
    ROUTINE,
}

@Serializable
enum class Criticality {
    CRITICAL,
    SOFT,
}

@Serializable
enum class NetworkType {
    WIFI,
    CELLULAR,
    NONE,
    UNKNOWN,
}

@Serializable
enum class CareEventType {
    ANCHOR_CONFIRMED,
    TAKEN,
    TAKEN_UNDONE,
    SNOOZED,
    SKIPPED,
    CAREGIVER_OVERRIDE_TAKEN,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("mode")
sealed interface ScheduleRule {
    val daysOfWeek: List<Int>

    @Serializable
    @SerialName("FIXED")
    data class Fixed(
        val time: String,
        override val daysOfWeek: List<Int> = ALL_DAYS,
    ) : ScheduleRule

    @Serializable
    @SerialName("RELATIVE_TO_ANCHOR")
    data class RelativeToAnchor(
        val anchorId: String,
        val offsetMinutes: Int,
        override val daysOfWeek: List<Int> = ALL_DAYS,
    ) : ScheduleRule

    companion object {
        val ALL_DAYS: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7)
    }
}

@Serializable
data class AnchorRule(
    val id: String,
    val title: String,
    val windowStart: String,
    val windowEnd: String,
    val softReminderAt: String? = null,
    val daysOfWeek: List<Int> = ScheduleRule.ALL_DAYS,
)

@Serializable
data class EscalationPolicy(
    val repeatIntervalMin: Int = 10,
    val maxRepeats: Int = 2,
    val caregiverDeadlineMin: Int = 30,
)

@Serializable
data class PillInventory(
    val trackStock: Boolean = true,
    val remainingUnits: Double,
    val unitsPerDose: Double = 1.0,
    val lowThresholdUnits: Double = 5.0,
    val stockUpdatedAtUtc: String? = null,
)

@Serializable
data class TaskRule(
    val id: String,
    val type: TaskType,
    val criticality: Criticality = Criticality.CRITICAL,
    val title: String,
    val doseVisualCount: Double? = null,
    val doseLabel: String? = null,
    val instruction: String? = null,
    val photoMediaId: String? = null,
    val voiceMediaId: String? = null,
    val schedule: ScheduleRule,
    val escalation: EscalationPolicy? = null,
    val inventory: PillInventory? = null,
)

@Serializable
data class EmergencyContact(
    val name: String,
    val phone: String,
)

@Serializable
data class PlanSnapshot(
    val schemaVersion: Int = 1,
    val planVersion: Long,
    val effectiveFromUtc: String,
    val timezone: String = "Europe/Kyiv",
    val recipientRole: RecipientRole = RecipientRole.OTHER,
    val recipientName: String? = null,
    val caregiverRole: CaregiverRole = CaregiverRole.OTHER,
    val emergencyContact: EmergencyContact? = null,
    val anchors: List<AnchorRule> = emptyList(),
    val tasks: List<TaskRule> = emptyList(),
)

@Serializable
data class ActivePlanPointer(
    val planVersion: Long,
    val fileName: String,
    val updatedAtUtc: String,
    val sha256: String? = null,
)

@Serializable
data class FamilyManifest(
    val schemaVersion: Int = 1,
    val familyId: String,
    val familyName: String,
    val timezone: String = "Europe/Kyiv",
    val createdAtUtc: String,
)

@Serializable
data class CareEvent(
    val eventId: String,
    val deviceId: String,
    val actorRole: Role,
    val logicalDate: String,
    val planVersion: Long,
    val taskId: String,
    val type: CareEventType,
    val occurredAtUtc: String,
    val snoozeUntilUtc: String? = null,
    val targetEventId: String? = null,
    val note: String? = null,
)

@Serializable
data class DevicePresence(
    val deviceId: String,
    val role: Role,
    val updatedAtUtc: String,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val networkType: NetworkType,
    val alarmVolumePercent: Int,
    val isDoNotDisturbActive: Boolean,
    val activePlanVersion: Long,
    val lastPendingSyncCount: Int = 0,
)

@Serializable
data class PairingQrPayload(
    val schemaVersion: Int = 1,
    val familyId: String,
    val familyName: String,
    val folderId: String,
    val serviceAccountJson: String,
    val familyAesKeyBase64: String,
    val createdAtUtc: String,
)

object CareCloseJson {
    @OptIn(ExperimentalSerializationApi::class)
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
