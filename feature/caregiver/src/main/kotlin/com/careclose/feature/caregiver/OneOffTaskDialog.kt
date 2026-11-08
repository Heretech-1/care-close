package com.careclose.feature.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.careclose.core.model.Criticality
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.TaskType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OneOffTaskDialog(
    initialDate: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onSave: (TaskRule) -> Unit,
) {
    val today = remember { initialDate }
    val tomorrow = remember { initialDate.plusDays(1) }
    val dayAfter = remember { initialDate.plusDays(2) }

    var title by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(today) }
    var customDateText by remember { mutableStateOf(today.toString()) }
    var timeText by remember { mutableStateOf("12:00") }
    var criticality by remember { mutableStateOf(Criticality.CRITICAL) }
    var instruction by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val presets = listOf(
        "🛒 Сходити в магазин",
        "💊 Сходити в аптеку",
        "🩺 Візит до лікаря",
        "📦 Забрати посилку",
        "💧 Випити води",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Одноразове нагадування",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Швидкі шаблони:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = title.startsWith(preset.substring(2).trim()),
                            onClick = {
                                title = preset.substring(2).trim()
                                if (preset.contains("аптеку") || preset.contains("лікаря")) {
                                    criticality = Criticality.CRITICAL
                                }
                            },
                            label = { Text(preset) },
                        )
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        validationError = null
                    },
                    label = { Text("Що потрібно зробити") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Коли виконати:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val dateFormatter = remember { DateTimeFormatter.ofPattern("dd.MM") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = customDateText == today.toString(),
                        onClick = {
                            selectedDate = today
                            customDateText = today.toString()
                        },
                        label = { Text("Сьогодні (${dateFormatter.format(today)})") },
                    )
                    FilterChip(
                        selected = customDateText == tomorrow.toString(),
                        onClick = {
                            selectedDate = tomorrow
                            customDateText = tomorrow.toString()
                        },
                        label = { Text("Завтра (${dateFormatter.format(tomorrow)})") },
                    )
                    FilterChip(
                        selected = customDateText == dayAfter.toString(),
                        onClick = {
                            selectedDate = dayAfter
                            customDateText = dayAfter.toString()
                        },
                        label = { Text("Післязавтра (${dateFormatter.format(dayAfter)})") },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customDateText,
                        onValueChange = {
                            customDateText = it
                            runCatching { LocalDate.parse(it.trim()) }.onSuccess { parsed ->
                                selectedDate = parsed
                            }
                        },
                        label = { Text("Дата (YYYY-MM-DD)") },
                        modifier = Modifier.weight(1.3f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = timeText,
                        onValueChange = { timeText = it },
                        label = { Text("Час (HH:mm)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Рівень важливості:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = criticality == Criticality.CRITICAL,
                        onClick = { criticality = Criticality.CRITICAL },
                        label = { Text("🔔 Гучний будильник") },
                    )
                    FilterChip(
                        selected = criticality == Criticality.SOFT,
                        onClick = { criticality = Criticality.SOFT },
                        label = { Text("💬 М'яке нагадування") },
                    )
                }

                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Подробиці / список (необов'язково)") },
                    placeholder = { Text("Наприклад: купити хліб, кефір і яблука") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                validationError?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = title.trim()
                    if (trimmed.isEmpty()) {
                        validationError = "Вкажіть назву завдання"
                        return@Button
                    }
                    val validDate = runCatching { LocalDate.parse(customDateText.trim()) }.getOrNull()
                    if (validDate == null) {
                        validationError = "Некоректний формат дати (має бути РРРР-ММ-ДД)"
                        return@Button
                    }
                    val normalizedTime = TimeInputValidator.normalizeTimeOrNull(timeText)
                    if (normalizedTime == null) {
                        validationError = "Некоректний час (має бути ГГ:ХХ, наприклад 14:30)"
                        return@Button
                    }

                    val task = TaskRule(
                        id = "oneoff_${UUID.randomUUID().toString().take(8)}",
                        type = TaskType.ROUTINE,
                        criticality = criticality,
                        title = trimmed,
                        instruction = instruction.trim().takeIf { it.isNotEmpty() },
                        schedule = ScheduleRule.OneOff(
                            date = validDate.toString(),
                            time = normalizedTime,
                        ),
                    )
                    onSave(task)
                },
            ) {
                Text("Створити нагадування")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        },
    )
}

