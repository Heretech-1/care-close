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

