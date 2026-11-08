package com.careclose.core.model.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class PlanVersionHelperTest {

    private val kyivZone = ZoneId.of("Europe/Kyiv")

    @Test
    fun `nextVersion generates valid 14-digit timestamp number`() {
        val testInstant = ZonedDateTime.of(2026, 11, 8, 14, 25, 30, 0, kyivZone).toInstant()
        val version = PlanVersionHelper.nextVersion(currentVersion = 0L, now = testInstant, zoneId = kyivZone)

        assertEquals(20261108142530L, version)
        val dt = PlanVersionHelper.parseVersionDateTimeOrNull(version)
        assertNotNull(dt)
        assertEquals(2026, dt!!.year)
        assertEquals(11, dt.monthValue)
        assertEquals(8, dt.dayOfMonth)
        assertEquals(14, dt.hour)
        assertEquals(25, dt.minute)
        assertEquals(30, dt.second)
    }

    @Test
    fun `nextVersion guarantees strict monotonicity when called within same second`() {
        val testInstant = ZonedDateTime.of(2026, 11, 8, 14, 25, 30, 0, kyivZone).toInstant()
        val v1 = PlanVersionHelper.nextVersion(currentVersion = 14L, now = testInstant, zoneId = kyivZone)
        assertEquals(20261108142530L, v1)

        val v2 = PlanVersionHelper.nextVersion(currentVersion = v1, now = testInstant, zoneId = kyivZone)
        assertEquals(20261108142531L, v2)
        assertTrue(v2 > v1)

        val v3 = PlanVersionHelper.nextVersion(currentVersion = v2, now = testInstant, zoneId = kyivZone)
        assertEquals(20261108142532L, v3)
        assertTrue(v3 > v2)
    }

    @Test
    fun `formatFriendly formats today yesterday and past dates in Ukrainian`() {
        val nowInstant = ZonedDateTime.of(2026, 11, 8, 18, 0, 0, 0, kyivZone).toInstant()

        // Today 14:25
        val todayInstant = ZonedDateTime.of(2026, 11, 8, 14, 25, 0, 0, kyivZone).toInstant()
        val vToday = PlanVersionHelper.nextVersion(0L, now = todayInstant, zoneId = kyivZone)
        val friendlyToday = PlanVersionHelper.formatFriendly(vToday, zoneId = kyivZone, now = nowInstant)
        assertEquals("сьогодні о 14:25", friendlyToday)

        // Yesterday 20:10
        val yesterdayInstant = ZonedDateTime.of(2026, 11, 7, 20, 10, 0, 0, kyivZone).toInstant()
        val vYesterday = PlanVersionHelper.nextVersion(0L, now = yesterdayInstant, zoneId = kyivZone)
        val friendlyYesterday = PlanVersionHelper.formatFriendly(vYesterday, zoneId = kyivZone, now = nowInstant)
        assertEquals("вчора о 20:10", friendlyYesterday)

        // Legacy version with effectiveFromUtc
        val friendlyLegacy = PlanVersionHelper.formatFriendly(
            planVersion = 14L,
            effectiveFromUtc = todayInstant.toString(),
            zoneId = kyivZone,
            now = nowInstant,
        )
        assertEquals("сьогодні о 14:25", friendlyLegacy)

        // Legacy version without effectiveFromUtc
        val fallback = PlanVersionHelper.formatFriendly(planVersion = 14L, zoneId = kyivZone, now = nowInstant)
        assertEquals("v14", fallback)
    }

    @Test
    fun `formatShortCode produces compact version tag`() {
        val testInstant = ZonedDateTime.of(2026, 11, 8, 9, 5, 0, 0, kyivZone).toInstant()
        val version = PlanVersionHelper.nextVersion(0L, now = testInstant, zoneId = kyivZone)
        val code = PlanVersionHelper.formatShortCode(version, zoneId = kyivZone)
        assertEquals("v2026.11.08-09:05", code)

        val legacyCode = PlanVersionHelper.formatShortCode(14L)
        assertEquals("v14", legacyCode)
    }

    @Test
    fun `formatTimeOnly returns hour and minute`() {
        val testInstant = ZonedDateTime.of(2026, 11, 8, 9, 5, 0, 0, kyivZone).toInstant()
        val version = PlanVersionHelper.nextVersion(0L, now = testInstant, zoneId = kyivZone)
        val timeStr = PlanVersionHelper.formatTimeOnly(version, zoneId = kyivZone)
        assertEquals("09:05", timeStr)
    }
}

