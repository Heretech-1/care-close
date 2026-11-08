package com.careclose.core.model.engine

import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Gender
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareCloseWordingTest {

    private fun samplePlan(
        recipientRole: RecipientRole = RecipientRole.MOTHER,
        recipientName: String? = null,
        caregiverRole: CaregiverRole = CaregiverRole.DAUGHTER,
    ): PlanSnapshot = PlanSnapshot(
        planVersion = 1L,
        effectiveFromUtc = "2026-11-08T00:00:00Z",
        recipientRole = recipientRole,
        recipientName = recipientName,
        caregiverRole = caregiverRole,
    )

    @Test
    fun `formats mother and daughter wording naturally in Ukrainian`() {
        val plan = samplePlan()
        assertEquals("Мама", CareCloseWording.recipientDisplayName(plan))
        assertEquals("ПРИЙНЯЛА / ЗРОБИЛА", CareCloseWording.elderActionTakenButton(plan.recipientRole.gender))
        assertEquals("Відмітити як виконано (Дала ліки)", CareCloseWording.caregiverOverrideButton(plan.caregiverRole))
        assertEquals("Дала особисто (Донька)", CareCloseWording.caregiverOverrideNote(plan.caregiverRole))

        val online = CareCloseWording.onlinePresenceStatus(plan, isOnline = true, minutesSince = 5)
        assertEquals("🟢 Мама онлайн (5 хв тому)", online)

        val offline = CareCloseWording.onlinePresenceStatus(plan, isOnline = false, minutesSince = 70)
        assertEquals("🔴 Мама не виходила на зв'язок (70 хв тому)", offline)
    }

    @Test
    fun `formats father and son wording naturally in Ukrainian`() {
        val plan = samplePlan(
            recipientRole = RecipientRole.FATHER,
            recipientName = "Тато Богдан",
            caregiverRole = CaregiverRole.SON,
        )
        assertEquals("Тато Богдан", CareCloseWording.recipientDisplayName(plan))
        assertEquals("ПРИЙНЯВ / ЗРОБИВ", CareCloseWording.elderActionTakenButton(plan.recipientRole.gender))
        assertEquals("Відмітити як виконано (Дав ліки)", CareCloseWording.caregiverOverrideButton(plan.caregiverRole))
        assertEquals("Дав особисто (Син)", CareCloseWording.caregiverOverrideNote(plan.caregiverRole))

        val offline = CareCloseWording.onlinePresenceStatus(plan, isOnline = false, minutesSince = 90)
        assertEquals("🔴 Тато Богдан не виходив на зв'язок (90 хв тому)", offline)

        val alertTitle = CareCloseWording.caregiverAlertTitleMissed(plan, "Аспірин")
        assertTrue(alertTitle.contains("Тато Богдан"))
    }

    @Test
    fun `formats helper caregiver roles and notes correctly`() {
        assertEquals("Відмітити як виконано (Медсестра)", CareCloseWording.caregiverOverrideButton(CaregiverRole.NURSE))
        assertEquals("Відмітити як виконано (Сиділка)", CareCloseWording.caregiverOverrideButton(CaregiverRole.AIDE))
        assertEquals("Відмітити як виконано (Сусідка)", CareCloseWording.caregiverOverrideButton(CaregiverRole.NEIGHBOR))
        assertEquals("Відмітити як виконано (Волонтер)", CareCloseWording.caregiverOverrideButton(CaregiverRole.VOLUNTEER))

        assertEquals("Виконала медсестра", CareCloseWording.caregiverOverrideNote(CaregiverRole.NURSE))
        assertEquals("Виконала медсестра (Оксана)", CareCloseWording.caregiverOverrideNote(CaregiverRole.NURSE, "Оксана"))
        assertEquals("Перевірила сусідка (Галина)", CareCloseWording.caregiverOverrideNote(CaregiverRole.NEIGHBOR, "Галина"))
        assertEquals("Провідала сиділка (Надія)", CareCloseWording.caregiverOverrideNote(CaregiverRole.AIDE, "Надія"))
    }

    @Test
    fun `formats completedTaskBadge accurately with and without custom note`() {
        val helperBadge = CareCloseWording.completedTaskBadge(
            completedBy = com.careclose.core.model.Role.CAREGIVER,
            completionNote = "Виконала медсестра (Оксана)",
            recipientName = "Мама",
            gender = Gender.FEMALE,
        )
        assertEquals("✓ Виконала медсестра (Оксана)", helperBadge)

        val motherBadge = CareCloseWording.completedTaskBadge(
            completedBy = com.careclose.core.model.Role.ELDER,
            completionNote = null,
            recipientName = "Мама",
            gender = Gender.FEMALE,
        )
        assertEquals("✓ Мама прийняла", motherBadge)

        val fatherBadge = CareCloseWording.completedTaskBadge(
            completedBy = com.careclose.core.model.Role.ELDER,
            completionNote = null,
            recipientName = "Тато",
            gender = Gender.MALE,
        )
        assertEquals("✓ Тато прийняв", fatherBadge)
    }

    @Test
    fun `formats unspecified role neutrally`() {
        val plan = samplePlan(
            recipientRole = RecipientRole.OTHER,
            recipientName = null,
            caregiverRole = CaregiverRole.OTHER,
        )
        assertEquals("Підопічний", CareCloseWording.recipientDisplayName(plan))
        assertEquals("ПРИЙНЯТО / ЗРОБЛЕНО", CareCloseWording.elderActionTakenButton(Gender.UNSPECIFIED))
        assertEquals("Відмітити як виконано (Я поруч)", CareCloseWording.caregiverOverrideButton(CaregiverRole.OTHER))
    }
}

