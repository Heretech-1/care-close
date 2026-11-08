package com.careclose.core.model.engine

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object PlanVersionHelper {

    private const val MIN_TIMESTAMP_VERSION = 20200000000000L

    private val ukLocale = Locale.forLanguageTag("uk-UA")

    /**
     * Generates a monotonically increasing timestamp-based plan version in format:
     * yyyyMMddHHmmss (e.g. 20261108211530L).
     * Fits cleanly within a 64-bit Long (max ~9.22e18 vs ~2.02e13).
     * Guarantees monotonicity: result is always strictly greater than [currentVersion].
     */
    fun nextVersion(
        currentVersion: Long = 0L,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val zdt = now.atZone(zoneId)
        val tsValue = String.format(
            Locale.US,
            "%04d%02d%02d%02d%02d%02d",
            zdt.year,
            zdt.monthValue,
            zdt.dayOfMonth,
            zdt.hour,
            zdt.minute,
            zdt.second,
        ).toLong()
        return maxOf(tsValue, currentVersion + 1L)
    }

    /**
     * Extracts LocalDateTime from a timestamp-based version (yyyyMMddHHmmss).
     * Returns null if the version is a legacy small counter.
     */
    fun parseVersionDateTimeOrNull(version: Long): LocalDateTime? {
        if (version < MIN_TIMESTAMP_VERSION) return null
        return runCatching {
            val year = (version / 10000000000L).toInt()
            val month = ((version / 100000000L) % 100).toInt()
            val day = ((version / 1000000L) % 100).toInt()
            val hour = ((version / 10000L) % 100).toInt()
            val minute = ((version / 100L) % 100).toInt()
            val second = (version % 100).toInt()
            LocalDateTime.of(year, month, day, hour, minute, second)
        }.getOrNull()
    }

    /**
     * Formats a plan version into a human-readable informative Ukrainian string.
     * Examples:
     * - "сьогодні о 21:15"
     * - "вчора о 18:30"
     * - "8 лист. о 21:15"
     * - "12 бер. 2025 о 10:00"
     * If version is legacy small number, tries to parse [effectiveFromUtc], or returns "v$version".
     */
    fun formatFriendly(
        planVersion: Long,
        effectiveFromUtc: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): String {
        val dt = parseVersionDateTimeOrNull(planVersion)
            ?: runCatching {
                effectiveFromUtc?.takeIf { it.isNotBlank() }?.let {
                    Instant.parse(it).atZone(zoneId).toLocalDateTime()
                }
            }.getOrNull()

        if (dt == null) {
            return "v$planVersion"
        }

        val today = LocalDate.now(zoneId)
        val planDate = dt.toLocalDate()
        val timeStr = String.format(Locale.US, "%02d:%02d", dt.hour, dt.minute)

        return when {
            planDate == today -> "сьогодні о $timeStr"
            planDate == today.minusDays(1) -> "вчора о $timeStr"
            planDate.year == today.year -> {
                val monthName = dt.format(DateTimeFormatter.ofPattern("d MMM", ukLocale))
                "$monthName о $timeStr"
            }
            else -> {
                val fullDate = dt.format(DateTimeFormatter.ofPattern("d MMM yyyy", ukLocale))
                "$fullDate о $timeStr"
            }
        }
    }

    /**
     * Returns a compact structured code representation, e.g. "v2026.11.08-21:15"
     * or "v14" for legacy plans.
     */
    fun formatShortCode(
        planVersion: Long,
        effectiveFromUtc: String? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dt = parseVersionDateTimeOrNull(planVersion)
            ?: runCatching {
                effectiveFromUtc?.takeIf { it.isNotBlank() }?.let {
                    Instant.parse(it).atZone(zoneId).toLocalDateTime()
                }
            }.getOrNull()

        if (dt == null) {
            return "v$planVersion"
        }

        return String.format(
            Locale.US,
            "v%04d.%02d.%02d-%02d:%02d",
            dt.year,
            dt.monthValue,
            dt.dayOfMonth,
            dt.hour,
            dt.minute,
        )
    }

    /**
     * Returns a time-only string e.g. "21:15".
     */
    fun formatTimeOnly(
        planVersion: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dt = parseVersionDateTimeOrNull(planVersion)
        if (dt != null) {
            return String.format(Locale.US, "%02d:%02d", dt.hour, dt.minute)
        }
        val now = Instant.now().atZone(zoneId)
        return String.format(Locale.US, "%02d:%02d", now.hour, now.minute)
    }
}

