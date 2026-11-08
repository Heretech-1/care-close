package com.careclose.core.model.engine

import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Gender
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole

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

    fun elderActionTakenButton(gender: Gender): String = when (gender) {
        Gender.FEMALE -> "ПРИЙНЯЛА / ЗРОБИЛА"
        Gender.MALE -> "ПРИЙНЯВ / ЗРОБИВ"
        Gender.UNSPECIFIED -> "ПРИЙНЯТО / ЗРОБЛЕНО"
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

    fun elderRecordedConfirmation(name: String, gender: Gender): String = when (gender) {
        Gender.FEMALE -> "$name прийняла"
        Gender.MALE -> "$name прийняв"
        Gender.UNSPECIFIED -> "Записано"
    }

    fun caregiverOverrideButton(role: CaregiverRole): String = when (role) {
        CaregiverRole.DAUGHTER, CaregiverRole.GRANDDAUGHTER -> "Відмітити як виконано (Дала ліки)"
        CaregiverRole.SON, CaregiverRole.GRANDSON -> "Відмітити як виконано (Дав ліки)"
        CaregiverRole.NURSE -> "Відмітити як виконано (Медсестра)"
        CaregiverRole.AIDE -> "Відмітити як виконано (Сиділка)"
        CaregiverRole.NEIGHBOR -> "Відмітити як виконано (Сусідка)"
        CaregiverRole.VOLUNTEER -> "Відмітити як виконано (Волонтер)"
        CaregiverRole.OTHER -> "Відмітити як виконано (Я поруч)"
    }

    fun caregiverOverrideNote(role: CaregiverRole, customName: String? = null): String {
        val nameSuffix = customName?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return when (role) {
            CaregiverRole.DAUGHTER, CaregiverRole.GRANDDAUGHTER -> "Дала особисто (${role.defaultTitle}$nameSuffix)"
            CaregiverRole.SON, CaregiverRole.GRANDSON -> "Дав особисто (${role.defaultTitle}$nameSuffix)"
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
    ): String {
        if (!completionNote.isNullOrBlank()) {
            return "✓ $completionNote"
        }
        return when (completedBy) {
            com.careclose.core.model.Role.ELDER -> when (gender) {
                Gender.FEMALE -> "✓ $recipientName прийняла"
                Gender.MALE -> "✓ $recipientName прийняв"
                Gender.UNSPECIFIED -> "✓ Виконано підопічним"
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
            return "Телефон: $name ще не синхронізувався"
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

