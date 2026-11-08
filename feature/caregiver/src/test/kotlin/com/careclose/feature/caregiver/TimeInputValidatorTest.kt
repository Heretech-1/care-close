package com.careclose.feature.caregiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeInputValidatorTest {

    @Test
    fun `normalizes single-digit hour to HH_mm`() {
        assertEquals("08:30", TimeInputValidator.normalizeTimeOrNull("8:30"))
        assertEquals("08:05", TimeInputValidator.normalizeTimeOrNull(" 8:5 "))
        assertEquals("23:59", TimeInputValidator.normalizeTimeOrNull("23:59"))
        assertEquals("00:00", TimeInputValidator.normalizeTimeOrNull("0:0"))
    }

    @Test
    fun `rejects invalid time strings`() {
        assertNull(TimeInputValidator.normalizeTimeOrNull(""))
        assertNull(TimeInputValidator.normalizeTimeOrNull("25:00"))
        assertNull(TimeInputValidator.normalizeTimeOrNull("12:60"))
        assertNull(TimeInputValidator.normalizeTimeOrNull("08-30"))
        assertNull(TimeInputValidator.normalizeTimeOrNull("08:30:00"))
        assertNull(TimeInputValidator.normalizeTimeOrNull("abc:de"))
    }
}

