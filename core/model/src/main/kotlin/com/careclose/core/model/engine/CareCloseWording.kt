package com.careclose.core.model.engine

import com.careclose.core.model.AppLanguage
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Gender
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole
import com.careclose.core.model.TaskType

object CareCloseWording {

    fun recipientDisplayName(plan: PlanSnapshot, language: AppLanguage = AppLanguage.UKRAINIAN): String {
        val customName = plan.recipientName?.trim()
        if (!customName.isNullOrEmpty()) return customName
        return if (plan.recipientRole != RecipientRole.OTHER) {
            if (language == AppLanguage.ENGLISH) {
                when (plan.recipientRole) {
                    RecipientRole.MOTHER -> "Mom"
                    RecipientRole.FATHER -> "Dad"
                    RecipientRole.GRANDMOTHER -> "Grandmother"
                    RecipientRole.GRANDFATHER -> "Grandfather"
                    RecipientRole.OTHER -> "Ward"
                }
            } else {
                plan.recipientRole.defaultTitle
            }
        } else {
            if (language == AppLanguage.ENGLISH) "Ward" else "Підопічний"
        }
    }

    fun elderActionTakenButton(
        gender: Gender,
        taskType: TaskType = TaskType.MEDICATION,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String = when (language) {
        AppLanguage.ENGLISH -> when (taskType) {
            TaskType.MEDICATION -> "TAKEN"
            TaskType.ROUTINE, TaskType.MEAL -> "DONE"
        }
        else -> when (taskType) {
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
    }

    fun elderActionTakenPast(gender: Gender, language: AppLanguage = AppLanguage.UKRAINIAN): String = when (language) {
        AppLanguage.ENGLISH -> "taken"
        else -> when (gender) {
            Gender.FEMALE -> "прийняла"
            Gender.MALE -> "прийняв"
            Gender.UNSPECIFIED -> "прийнято"
        }
    }

    fun elderActionDonePast(gender: Gender, language: AppLanguage = AppLanguage.UKRAINIAN): String = when (language) {
        AppLanguage.ENGLISH -> "done"
        else -> when (gender) {
            Gender.FEMALE -> "зробила"
            Gender.MALE -> "зробив"
            Gender.UNSPECIFIED -> "виконано"
        }
    }

    fun elderRecordedConfirmation(
        name: String,
        gender: Gender,
        taskType: TaskType = TaskType.MEDICATION,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String = when (language) {
        AppLanguage.ENGLISH -> when (taskType) {
            TaskType.MEDICATION -> "$name has taken"
            TaskType.ROUTINE, TaskType.MEAL -> "$name has completed"
        }
        else -> when (taskType) {
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
    }

    fun caregiverOverrideButton(
        role: CaregiverRole,
        taskType: TaskType = TaskType.MEDICATION,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String = when (language) {
        AppLanguage.ENGLISH -> when (role) {
            CaregiverRole.DAUGHTER -> if (taskType == TaskType.MEDICATION) "Mark taken (Daughter)" else "Mark done (Daughter)"
            CaregiverRole.SON -> if (taskType == TaskType.MEDICATION) "Mark taken (Son)" else "Mark done (Son)"
            CaregiverRole.GRANDDAUGHTER -> if (taskType == TaskType.MEDICATION) "Mark taken (Granddaughter)" else "Mark done (Granddaughter)"
            CaregiverRole.GRANDSON -> if (taskType == TaskType.MEDICATION) "Mark taken (Grandson)" else "Mark done (Grandson)"
            CaregiverRole.NURSE -> "Mark done (Nurse)"
            CaregiverRole.AIDE -> "Mark done (Aide)"
            CaregiverRole.NEIGHBOR -> "Mark done (Neighbor)"
            CaregiverRole.VOLUNTEER -> "Mark done (Volunteer)"
            CaregiverRole.OTHER -> "Mark done (In person)"
        }
        else -> when (role) {
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
    }

    fun caregiverOverrideNote(
        role: CaregiverRole,
        customName: String? = null,
        taskType: TaskType = TaskType.MEDICATION,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String {
        val nameSuffix = customName?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return when (language) {
            AppLanguage.ENGLISH -> when (role) {
                CaregiverRole.DAUGHTER -> if (taskType == TaskType.MEDICATION) "Administered personally (Daughter$nameSuffix)" else "Assisted personally (Daughter$nameSuffix)"
                CaregiverRole.SON -> if (taskType == TaskType.MEDICATION) "Administered personally (Son$nameSuffix)" else "Assisted personally (Son$nameSuffix)"
                CaregiverRole.GRANDDAUGHTER -> if (taskType == TaskType.MEDICATION) "Administered personally (Granddaughter$nameSuffix)" else "Assisted personally (Granddaughter$nameSuffix)"
                CaregiverRole.GRANDSON -> if (taskType == TaskType.MEDICATION) "Administered personally (Grandson$nameSuffix)" else "Assisted personally (Grandson$nameSuffix)"
                CaregiverRole.NURSE -> "Administered by nurse$nameSuffix"
                CaregiverRole.AIDE -> "Administered by caregiver/aide$nameSuffix"
                CaregiverRole.NEIGHBOR -> "Checked by neighbor$nameSuffix"
                CaregiverRole.VOLUNTEER -> "Assisted by volunteer$nameSuffix"
                CaregiverRole.OTHER -> "Marked by caregiver$nameSuffix"
            }
            else -> when (role) {
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
    }

    fun completedTaskBadge(
        completedBy: com.careclose.core.model.Role?,
        completionNote: String?,
        recipientName: String,
        gender: Gender,
        taskType: TaskType = TaskType.MEDICATION,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String {
        if (!completionNote.isNullOrBlank()) {
            return "✓ $completionNote"
        }
        return when (language) {
            AppLanguage.ENGLISH -> when (completedBy) {
                com.careclose.core.model.Role.ELDER -> "✓ Completed by $recipientName"
                com.careclose.core.model.Role.CAREGIVER -> "✓ Marked by caregiver"
                null -> "✓ Completed"
            }
            else -> when (completedBy) {
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
    }

    fun onlinePresenceStatus(
        plan: PlanSnapshot,
        isOnline: Boolean,
        minutesSince: Long? = null,
        minutesSinceLastSeen: Long? = minutesSince,
        language: AppLanguage = AppLanguage.UKRAINIAN,
    ): String {
        val mins = minutesSinceLastSeen ?: minutesSince
        val name = recipientDisplayName(plan, language)
        if (mins == null) {
            return if (language == AppLanguage.ENGLISH) {
                "Device: $name hasn't synced yet"
            } else {
                val verb = when (plan.recipientRole.gender) {
                    Gender.FEMALE -> "ще не синхронізувалася"
                    Gender.MALE -> "ще не синхронізувався"
                    Gender.UNSPECIFIED -> "ще не синхронізувався(-лася)"
                }
                "Телефон: $name $verb"
            }
        }
        return if (language == AppLanguage.ENGLISH) {
            if (isOnline) "🟢 $name is online ($mins min ago)"
            else "🔴 $name hasn't connected ($mins min ago)"
        } else {
            if (isOnline) {
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
    }

    fun caregiverAlertTitleMissed(plan: PlanSnapshot, taskTitle: String, language: AppLanguage = AppLanguage.UKRAINIAN): String {
        val name = recipientDisplayName(plan, language)
        return if (language == AppLanguage.ENGLISH) {
            "No confirmation: $taskTitle ($name)"
        } else {
            "Немає підтвердження: $taskTitle ($name)"
        }
    }

    fun caregiverAlertBodyMissedOnline(plan: PlanSnapshot, language: AppLanguage = AppLanguage.UKRAINIAN): String {
        val name = recipientDisplayName(plan, language)
        return if (language == AppLanguage.ENGLISH) {
            "Deadline passed, but phone ($name) was online. Please consider calling."
        } else {
            "Дедлайн минув, але телефон ($name) був онлайн. Варто зателефонувати."
        }
    }

    fun caregiverAlertBodyMissedOffline(plan: PlanSnapshot, language: AppLanguage = AppLanguage.UKRAINIAN): String {
        val name = recipientDisplayName(plan, language)
        return if (language == AppLanguage.ENGLISH) {
            "Phone ($name) did not connect prior to the deadline."
        } else {
            "Телефон ($name) не виходив на зв'язок перед дедлайном."
        }
    }
}


