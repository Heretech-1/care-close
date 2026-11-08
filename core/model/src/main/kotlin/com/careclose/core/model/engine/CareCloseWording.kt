package com.careclose.core.model.engine

import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Gender
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole
import com.careclose.core.model.TaskType

object CareCloseWording {

    fun recipientDisplayName(plan: PlanSnapshot): String {
        val customName = plan.recipientName?.trim()
        if (!customName.isNullOrEmpty()) return customName
        return if (plan.recipientRole != RecipientRole.OTHER) {
            plan.recipientRole.defaultTitle
        } else {
            "Підопічний"
        }
    }

    fun elderActionTakenButton(gender: Gender, taskType: TaskType = TaskType.MEDICATION): String = when (taskType) {
        TaskType.MEDICATION -> when (gender) {
            Gender.FEMALE -> "ПРИЙНЯЛА"
            Gender.MALE -> "ПРИЙНЯВ"
            Gender.UNSPECIFIED -> "ПРИЙНЯТО"
        }
        TaskType.ROUTINE, TaskType.MEAL -> when (gender) {
            Gender.FEMALE -> "ЗРОБИЛА"
            Gender.MALE -> "ЗРОБИВ"
            Gender.UNSPECIFIED -> "ВИКОНАНО"
        }
    }

    fun elderActionTakenPast(gender: Gender): String = when (gender) {
        Gender.FEMALE -> "прийняла"
        Gender.MALE -> "прийняв"
        Gender.UNSPECIFIED -> "прийнято"
    }

    fun elderActionDonePast(gender: Gender): String = when (gender) {
        Gender.FEMALE -> "зробила"
        Gender.MALE -> "зробив"
        Gender.UNSPECIFIED -> "виконано"
    }

    fun elderRecordedConfirmation(
        name: String,
        gender: Gender,
        taskType: TaskType = TaskType.MEDICATION,
    ): String = when (taskType) {
        TaskType.MEDICATION -> when (gender) {
            Gender.FEMALE -> "$name прийняла"
            Gender.MALE -> "$name прийняв"
            Gender.UNSPECIFIED -> "Записано"
        }
        TaskType.ROUTINE, TaskType.MEAL -> when (gender) {
            Gender.FEMALE -> "$name зробила"
            Gender.MALE -> "$name зробив"
            Gender.UNSPECIFIED -> "Виконано"
        }
    }

    fun caregiverOverrideButton(
        role: CaregiverRole,
        taskType: TaskType = TaskType.MEDICATION,
    ): String = when (role) {
        CaregiverRole.DAUGHTER, CaregiverRole.GRANDDAUGHTER -> {
            if (taskType == TaskType.MEDICATION) "Відмітити як виконано (Дала ліки)"
            else "Відмітити як виконано (Допомогла)"
        }
        CaregiverRole.SON, CaregiverRole.GRANDSON -> {
            if (taskType == TaskType.MEDICATION) "Відмітити як виконано (Дав ліки)"
            else "Відмітити як виконано (Допоміг)"
        }
        CaregiverRole.NURSE -> "Відмітити як виконано (Медсестра)"
        CaregiverRole.AIDE -> "Відмітити як виконано (Сиділка)"
        CaregiverRole.NEIGHBOR -> "Відмітити як виконано (Сусідка)"
        CaregiverRole.VOLUNTEER -> "Відмітити як виконано (Волонтер)"
        CaregiverRole.OTHER -> "Відмітити як виконано (Я поруч)"
    }

    fun caregiverOverrideNote(
        role: CaregiverRole,
        customName: String? = null,
        taskType: TaskType = TaskType.MEDICATION,
    ): String {
        val nameSuffix = customName?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return when (role) {
            CaregiverRole.DAUGHTER, CaregiverRole.GRANDDAUGHTER -> {
                if (taskType == TaskType.MEDICATION) "Дала особисто (${role.defaultTitle}$nameSuffix)"
                else "Допомогла особисто (${role.defaultTitle}$nameSuffix)"
            }
            CaregiverRole.SON, CaregiverRole.GRANDSON -> {
                if (taskType == TaskType.MEDICATION) "Дав особисто (${role.defaultTitle}$nameSuffix)"
                else "Допоміг особисто (${role.defaultTitle}$nameSuffix)"
            }
            CaregiverRole.NURSE -> "Виконала медсестра$nameSuffix"
            CaregiverRole.AIDE -> "Провідала сиділка$nameSuffix"
            CaregiverRole.NEIGHBOR -> "Перевірила сусідка$nameSuffix"
            CaregiverRole.VOLUNTEER -> "Допоміг волонтер$nameSuffix"
            CaregiverRole.OTHER -> "Відмічено опікуном$nameSuffix"
        }
    }

    fun completedTaskBadge(
        completedBy: com.careclose.core.model.Role?,
        completionNote: String?,
        recipientName: String,
        gender: Gender,
        taskType: TaskType = TaskType.MEDICATION,
    ): String {
        if (!completionNote.isNullOrBlank()) {
            return "✓ $completionNote"
        }
        return when (completedBy) {
            com.careclose.core.model.Role.ELDER -> when (taskType) {
                TaskType.MEDICATION -> when (gender) {
                    Gender.FEMALE -> "✓ $recipientName прийняла"
                    Gender.MALE -> "✓ $recipientName прийняв"
                    Gender.UNSPECIFIED -> "✓ Виконано підопічним"
                }
                TaskType.ROUTINE, TaskType.MEAL -> when (gender) {
                    Gender.FEMALE -> "✓ $recipientName зробила"
                    Gender.MALE -> "✓ $recipientName зробив"
                    Gender.UNSPECIFIED -> "✓ Виконано підопічним"
                }
            }
            com.careclose.core.model.Role.CAREGIVER -> "✓ Відмічено опікуном"
            null -> "✓ Виконано"
        }
    }

    fun onlinePresenceStatus(
        plan: PlanSnapshot,
        isOnline: Boolean,
        minutesSince: Long? = null,
        minutesSinceLastSeen: Long? = minutesSince,
    ): String {
        val mins = minutesSinceLastSeen ?: minutesSince
        val name = recipientDisplayName(plan)
        if (mins == null) {
            val verb = when (plan.recipientRole.gender) {
                Gender.FEMALE -> "ще не синхронізувалася"
                Gender.MALE -> "ще не синхронізувався"
                Gender.UNSPECIFIED -> "ще не синхронізувався(-лася)"
            }
            return "Телефон: $name $verb"
        }
        return if (isOnline) {
            "🟢 $name онлайн ($mins хв тому)"
        } else {
            val verb = when (plan.recipientRole.gender) {
                Gender.FEMALE -> "не виходила"
                Gender.MALE -> "не виходив"
                Gender.UNSPECIFIED -> "не виходив(-ла)"
            }
            "🔴 $name $verb на зв'язок ($mins хв тому)"
        }
    }

    fun caregiverAlertTitleMissed(plan: PlanSnapshot, taskTitle: String): String {
        val name = recipientDisplayName(plan)
        return "Немає підтвердження: $taskTitle ($name)"
    }

    fun caregiverAlertBodyMissedOnline(plan: PlanSnapshot): String {
        val name = recipientDisplayName(plan)
        return "Дедлайн минув, але телефон ($name) був онлайн. Варто зателефонувати."
    }

    fun caregiverAlertBodyMissedOffline(plan: PlanSnapshot): String {
        val name = recipientDisplayName(plan)
        return "Телефон ($name) не виходив на зв'язок перед дедлайном."
    }
}

