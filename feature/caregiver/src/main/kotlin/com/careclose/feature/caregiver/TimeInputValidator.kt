package com.careclose.feature.caregiver

import java.time.LocalTime

object TimeInputValidator {

    /**
     * Парсить рядок часу ("8:05" або "08:05") і нормалізує його у формат HH:mm.
     * Повертає null, якщо формат або значення некоректні.
     */
    fun normalizeTimeOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val parsed = runCatching { LocalTime.of(hour, minute) }.getOrNull() ?: return null
        return "%02d:%02d".format(parsed.hour, parsed.minute)
    }
}

