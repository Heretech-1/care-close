package com.careclose.feature.caregiver

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.careclose.core.model.AnchorRule
import com.careclose.core.model.Criticality
import com.careclose.core.model.EscalationPolicy
import com.careclose.core.model.PillInventory
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.TaskType
import com.careclose.feature.caregiver.media.CaregiverVoicePreviewPlayer
import com.careclose.feature.caregiver.media.CaregiverVoiceRecorder
import com.careclose.feature.caregiver.media.PillPhotoProcessor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class ScheduleKind(val label: String) {
    DAILY("Щодня"),
    INTERVAL_DAYS("Кожні N днів"),
    HOURLY_INTERVAL("Кожні N годин"),
    ONE_OFF("Одноразово"),
    ANCHOR("Після події"),
}

data class PendingMediaUpload(
    val mediaId: String,
    val mimeType: String,
    val localFile: File,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorDialog(
    initialTask: TaskRule?,
    anchors: List<AnchorRule>,
    mediaCacheDir: File,
    onDismiss: () -> Unit,
    onSave: (List<TaskRule>, List<PendingMediaUpload>) -> Unit,
) {
    val context = LocalContext.current

    var taskType by remember { mutableStateOf(initialTask?.type ?: TaskType.MEDICATION) }
    var criticality by remember { mutableStateOf(initialTask?.criticality ?: Criticality.CRITICAL) }
    var title by remember { mutableStateOf(initialTask?.title ?: "") }
    var doseLabel by remember { mutableStateOf(initialTask?.doseLabel ?: "") }
    var doseCountText by remember {
        mutableStateOf(initialTask?.doseVisualCount?.let { formatNumber(it) } ?: "1")
    }
    var instruction by remember { mutableStateOf(initialTask?.instruction ?: "") }

    val initialRule = initialTask?.schedule
    var scheduleKind by remember {
        mutableStateOf(
            when (initialRule) {
                is ScheduleRule.RelativeToAnchor -> if (anchors.isNotEmpty()) ScheduleKind.ANCHOR else ScheduleKind.DAILY
                is ScheduleRule.OneOff -> ScheduleKind.ONE_OFF
                is ScheduleRule.EveryNDays -> ScheduleKind.INTERVAL_DAYS
                is ScheduleRule.Fixed, null -> ScheduleKind.DAILY
            },
        )
    }
    var fixedTimeText by remember {
        mutableStateOf(
            when (initialRule) {
                is ScheduleRule.Fixed -> initialRule.time
                is ScheduleRule.OneOff -> initialRule.time
                is ScheduleRule.EveryNDays -> initialRule.time
                else -> "08:00"
            },
        )
    }
    var intervalDaysText by remember {
        mutableStateOf(
            (initialRule as? ScheduleRule.EveryNDays)?.intervalDays?.toString() ?: "2",
        )
    }
    var intervalStartDateText by remember {
        mutableStateOf(
            (initialRule as? ScheduleRule.EveryNDays)?.startDate ?: LocalDate.now().toString(),
        )
    }
    var oneOffDateText by remember {
        mutableStateOf(
            (initialRule as? ScheduleRule.OneOff)?.date ?: LocalDate.now().toString(),
        )
    }
    var hourlyStartTimeText by remember { mutableStateOf("08:00") }
    var hourlyEndTimeText by remember { mutableStateOf("20:00") }
    var hourlyStepHours by remember { mutableStateOf(3) }

    var selectedAnchorId by remember {
        mutableStateOf(
            (initialRule as? ScheduleRule.RelativeToAnchor)?.anchorId?.takeIf { id -> anchors.any { it.id == id } }
                ?: anchors.firstOrNull()?.id.orEmpty(),
        )
    }
    var offsetMinutesText by remember {
        mutableStateOf((initialRule as? ScheduleRule.RelativeToAnchor)?.offsetMinutes?.toString() ?: "20")
    }

    var trackInventory by remember { mutableStateOf(initialTask?.inventory?.trackStock == true) }
    var remainingStockText by remember {
        mutableStateOf(initialTask?.inventory?.remainingUnits?.let { formatNumber(it) } ?: "30")
    }
    var lowThresholdText by remember {
        mutableStateOf(initialTask?.inventory?.lowThresholdUnits?.let { formatNumber(it) } ?: "5")
    }

    var photoMediaId by remember { mutableStateOf(initialTask?.photoMediaId) }
    var voiceMediaId by remember { mutableStateOf(initialTask?.voiceMediaId) }
    val pendingUploads = remember { mutableListOf<PendingMediaUpload>() }

    var isRecording by remember { mutableStateOf(false) }
    var activeRecordingFile by remember { mutableStateOf<File?>(null) }
    var isPlayingVoice by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val voiceRecorder = remember { CaregiverVoiceRecorder(context) }
    val voicePlayer = remember { CaregiverVoicePreviewPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) {
                voiceRecorder.stopRecording()
                activeRecordingFile?.delete()
            } else {
                voiceRecorder.release()
            }
            voicePlayer.stop()
        }
    }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) {
            val mediaId = "pill_${UUID.randomUUID().toString().take(8)}.jpg"
            val jpegBytes = PillPhotoProcessor.compressBitmapToJpeg(bitmap)
            if (!mediaCacheDir.exists()) mediaCacheDir.mkdirs()
            val target = File(mediaCacheDir, mediaId)
            target.writeBytes(jpegBytes)
            photoMediaId = mediaId
            pendingUploads.removeAll { it.mediaId.startsWith("pill_") }
            pendingUploads.add(PendingMediaUpload(mediaId, "image/jpeg", target))
        }
    }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val jpegBytes = PillPhotoProcessor.compressUriToJpeg(context, uri)
            if (jpegBytes != null) {
                val mediaId = "pill_${UUID.randomUUID().toString().take(8)}.jpg"
                if (!mediaCacheDir.exists()) mediaCacheDir.mkdirs()
                val target = File(mediaCacheDir, mediaId)
                target.writeBytes(jpegBytes)
                photoMediaId = mediaId
                pendingUploads.removeAll { it.mediaId.startsWith("pill_") }
                pendingUploads.add(PendingMediaUpload(mediaId, "image/jpeg", target))
            }
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            val mediaId = "voice_${UUID.randomUUID().toString().take(8)}.m4a"
            val target = File(mediaCacheDir, mediaId)
            runCatching {
                voicePlayer.stop()
                isPlayingVoice = false
                voiceRecorder.startRecording(target)
                activeRecordingFile = target
                isRecording = true
                validationError = null
            }.onFailure {
                activeRecordingFile = null
                validationError = "Не вдалося запустити мікрофон: ${it.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialTask == null) "Нова справа / ліки" else "Редагування",
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        TaskType.MEDICATION to "Ліки",
                        TaskType.MEAL to "Їжа",
                        TaskType.ROUTINE to "Справа",
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = taskType == type,
                            onClick = { taskType = type },
                            label = { Text(label) },
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = criticality == Criticality.CRITICAL,
                        onClick = { criticality = Criticality.CRITICAL },
                        label = { Text("Критично (з тривогою)") },
                    )
                    FilterChip(
                        selected = criticality == Criticality.SOFT,
                        onClick = { criticality = Criticality.SOFT },
                        label = { Text("М'яко") },
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Назва (напр. Еутирокс 50 мкг)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = doseCountText,
                        onValueChange = { doseCountText = it },
                        label = { Text("Кружечки (1 / 0.5)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = doseLabel,
                        onValueChange = { doseLabel = it },
                        label = { Text("Опис дози") },
                        modifier = Modifier.weight(1.6f),
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Підказка (напр. Запити водою)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(text = "Режим повторення:", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScheduleKind.entries.forEach { kind ->
                        if (kind != ScheduleKind.ANCHOR || anchors.isNotEmpty()) {
                            FilterChip(
                                selected = scheduleKind == kind,
                                onClick = { scheduleKind = kind },
                                label = { Text(kind.label) },
                            )
                        }
                    }
                }

                when (scheduleKind) {
                    ScheduleKind.DAILY -> {
                        OutlinedTextField(
                            value = fixedTimeText,
                            onValueChange = { fixedTimeText = it },
                            label = { Text("Час прийому (HH:mm, напр. 08:30)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    ScheduleKind.INTERVAL_DAYS -> {
                        Text("Інтервал повторення:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                2 to "Кожні 2 дні",
                                3 to "Кожні 3 дні",
                                4 to "Кожні 4 дні",
                                7 to "Кожні 7 днів",
                            ).forEach { (d, lbl) ->
                                FilterChip(
                                    selected = intervalDaysText == d.toString(),
                                    onClick = { intervalDaysText = d.toString() },
                                    label = { Text(lbl) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = intervalDaysText,
                                onValueChange = { intervalDaysText = it },
                                label = { Text("Інтервал (днів)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = fixedTimeText,
                                onValueChange = { fixedTimeText = it },
                                label = { Text("Час (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = intervalStartDateText,
                            onValueChange = { intervalStartDateText = it },
                            label = { Text("Дата початку відліку (YYYY-MM-DD)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    ScheduleKind.HOURLY_INTERVAL -> {
                        Text("Інтервал між прийомами:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(2, 3, 4, 6, 8).forEach { step ->
                                FilterChip(
                                    selected = hourlyStepHours == step,
                                    onClick = { hourlyStepHours = step },
                                    label = { Text("Кожні $step год") },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = hourlyStartTimeText,
                                onValueChange = { hourlyStartTimeText = it },
                                label = { Text("З котрої (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = hourlyEndTimeText,
                                onValueChange = { hourlyEndTimeText = it },
                                label = { Text("До котрої (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        val previewSlots = remember(hourlyStartTimeText, hourlyEndTimeText, hourlyStepHours) {
                            runCatching {
                                val s = TimeInputValidator.normalizeTimeOrNull(hourlyStartTimeText) ?: "08:00"
                                val e = TimeInputValidator.normalizeTimeOrNull(hourlyEndTimeText) ?: "20:00"
                                val sTime = LocalTime.parse(s)
                                val eTime = LocalTime.parse(e)
                                val list = mutableListOf<String>()
                                var c = sTime
                                while (!c.isAfter(eTime)) {
                                    list.add(String.format("%02d:%02d", c.hour, c.minute))
                                    c = c.plusHours(hourlyStepHours.toLong())
                                    if (c.isBefore(sTime)) break
                                }
                                list
                            }.getOrDefault(emptyList())
                        }
                        if (previewSlots.isNotEmpty()) {
                            Text(
                                text = "Буде створено ${previewSlots.size} прийомів: ${previewSlots.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    ScheduleKind.ONE_OFF -> {
                        val today = remember { LocalDate.now() }
                        val tomorrow = remember { today.plusDays(1) }
                        val dayAfter = remember { today.plusDays(2) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = oneOffDateText == today.toString(),
                                onClick = { oneOffDateText = today.toString() },
                                label = { Text("Сьогодні") },
                            )
                            FilterChip(
                                selected = oneOffDateText == tomorrow.toString(),
                                onClick = { oneOffDateText = tomorrow.toString() },
                                label = { Text("Завтра") },
                            )
                            FilterChip(
                                selected = oneOffDateText == dayAfter.toString(),
                                onClick = { oneOffDateText = dayAfter.toString() },
                                label = { Text("Післязавтра") },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = oneOffDateText,
                                onValueChange = { oneOffDateText = it },
                                label = { Text("Дата (YYYY-MM-DD)") },
                                modifier = Modifier.weight(1.3f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = fixedTimeText,
                                onValueChange = { fixedTimeText = it },
                                label = { Text("Час (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    }

                    ScheduleKind.ANCHOR -> {
                        if (anchors.isEmpty()) {
                            Text(
                                text = "⚠️ У розкладі немає жодного якоря. Будь ласка, оберіть інший режим часу або спочатку створіть якір.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            val originalAnchorDeleted = (initialRule as? ScheduleRule.RelativeToAnchor)?.let { r ->
                                anchors.none { it.id == r.anchorId }
                            } ?: false

                            if (originalAnchorDeleted) {
                                Text(
                                    text = "⚠️ Попередній якір видалено з розкладу! Будь ласка, оберіть новий якір зі списку нижче:",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                anchors.forEach { anchor ->
                                    FilterChip(
                                        selected = selectedAnchorId == anchor.id,
                                        onClick = { selectedAnchorId = anchor.id },
                                        label = { Text(anchor.title) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = offsetMinutesText,
                                onValueChange = { offsetMinutesText = it },
                                label = { Text("Через скільки хвилин (0 = одразу)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Фото та голос рідного:", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { takePhotoLauncher.launch(null) }) {
                        Text(if (photoMediaId != null) "📷 Змінити фото" else "📷 Зняти фото")
                    }
                    OutlinedButton(onClick = { pickPhotoLauncher.launch("image/*") }) {
                        Text("Галерея")
                    }
                }
                if (photoMediaId != null) {
                    Text(
                        text = "✅ Фото прикріплено ($photoMediaId)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isRecording) {
                                val recorded = voiceRecorder.stopRecording()
                                isRecording = false
                                activeRecordingFile = null
                                if (recorded != null) {
                                    val id = recorded.name
                                    voiceMediaId = id
                                    pendingUploads.removeAll { it.mediaId.startsWith("voice_") }
                                    pendingUploads.add(PendingMediaUpload(id, "audio/mp4", recorded))
                                } else {
                                    validationError = "Не вдалося зберегти аудіозапис"
                                }
                            } else if (hasAudioPermission) {
                                val mediaId = "voice_${UUID.randomUUID().toString().take(8)}.m4a"
                                val target = File(mediaCacheDir, mediaId)
                                runCatching {
                                    voicePlayer.stop()
                                    isPlayingVoice = false
                                    voiceRecorder.startRecording(target)
                                    activeRecordingFile = target
                                    isRecording = true
                                    validationError = null
                                }.onFailure {
                                    activeRecordingFile = null
                                    validationError = "Помилка запису: ${it.message}"
                                }
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        ),
                    ) {
                        Text(if (isRecording) "⏹ Зупинити запис" else "🎙 Записати голос")
                    }

                    val existingVoiceFile = voiceMediaId?.let { File(mediaCacheDir, it) }
                        ?.takeIf { it.exists() }
                    if (!isRecording && existingVoiceFile != null) {
                        OutlinedButton(
                            onClick = {
                                if (isPlayingVoice) {
                                    voicePlayer.stop()
                                    isPlayingVoice = false
                                } else {
                                    isPlayingVoice = true
                                    voicePlayer.play(existingVoiceFile) {
                                        isPlayingVoice = false
                                    }
                                }
                            },
                        ) {
                            Text(if (isPlayingVoice) "⏹ Стоп" else "▶ Прослухати")
                        }
                    }
                }
                if (voiceMediaId != null && !isRecording) {
                    Text(
                        text = "✅ Голосове нагадування готове",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (taskType == TaskType.MEDICATION) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = trackInventory,
                            onCheckedChange = { trackInventory = it },
                        )
                        Text("Відстежувати залишок в аптечці")
                    }
                    if (trackInventory) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = remainingStockText,
                                onValueChange = { remainingStockText = it },
                                label = { Text("Залишок (шт)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = lowThresholdText,
                                onValueChange = { lowThresholdText = it },
                                label = { Text("Поріг нагадування") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    }
                }

                validationError?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedTitle = title.trim()
                    if (trimmedTitle.isEmpty()) {
                        validationError = "Вкажіть назву"
                        return@Button
                    }

                    val doseCount = doseCountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
                    val inventory = if (taskType == TaskType.MEDICATION && trackInventory) {
                        val rem = remainingStockText.replace(',', '.').toDoubleOrNull() ?: 30.0
                        val threshold = lowThresholdText.replace(',', '.').toDoubleOrNull() ?: 5.0
                        PillInventory(
                            trackStock = true,
                            remainingUnits = rem,
                            unitsPerDose = doseCount ?: 1.0,
                            lowThresholdUnits = threshold,
                            stockUpdatedAtUtc = Instant.now().toString(),
                        )
                    } else {
                        null
                    }

                    if (scheduleKind == ScheduleKind.HOURLY_INTERVAL) {
                        val normStart = TimeInputValidator.normalizeTimeOrNull(hourlyStartTimeText)
                        val normEnd = TimeInputValidator.normalizeTimeOrNull(hourlyEndTimeText)
                        if (normStart == null || normEnd == null) {
                            validationError = "Формат часу має бути HH:mm (наприклад, 08:00)"
                            return@Button
                        }
                        val sTime = LocalTime.parse(normStart)
                        val eTime = LocalTime.parse(normEnd)
                        if (sTime.isAfter(eTime)) {
                            validationError = "Час початку не може бути пізніше за час закінчення"
                            return@Button
                        }
                        val slotList = mutableListOf<String>()
                        var curr = sTime
                        while (!curr.isAfter(eTime)) {
                            slotList.add(String.format("%02d:%02d", curr.hour, curr.minute))
                            curr = curr.plusHours(hourlyStepHours.toLong())
                            if (curr.isBefore(sTime)) break
                        }
                        if (slotList.isEmpty()) {
                            validationError = "Не вдалося згенерувати часові слоти"
                            return@Button
                        }

                        val generated = slotList.mapIndexed { index, slotTime ->
                            TaskRule(
                                id = if (index == 0) (initialTask?.id ?: "task_${UUID.randomUUID().toString().take(8)}") else "task_${UUID.randomUUID().toString().take(8)}",
                                type = taskType,
                                criticality = criticality,
                                title = trimmedTitle,
                                doseVisualCount = doseCount,
                                doseLabel = doseLabel.trim().takeIf { it.isNotEmpty() },
                                instruction = instruction.trim().takeIf { it.isNotEmpty() },
                                photoMediaId = photoMediaId,
                                voiceMediaId = voiceMediaId,
                                schedule = ScheduleRule.Fixed(time = slotTime),
                                escalation = if (criticality == Criticality.CRITICAL) {
                                    initialTask?.escalation ?: EscalationPolicy()
                                } else null,
                                inventory = inventory,
                            )
                        }
                        onSave(generated, pendingUploads.toList())
                        return@Button
                    }

                    val scheduleRule: ScheduleRule = when (scheduleKind) {
                        ScheduleKind.DAILY -> {
                            val normalized = TimeInputValidator.normalizeTimeOrNull(fixedTimeText)
                            if (normalized == null) {
                                validationError = "Формат часу має бути HH:mm (наприклад, 08:30)"
                                return@Button
                            }
                            ScheduleRule.Fixed(time = normalized)
                        }

                        ScheduleKind.INTERVAL_DAYS -> {
                            val normalized = TimeInputValidator.normalizeTimeOrNull(fixedTimeText)
                            if (normalized == null) {
                                validationError = "Формат часу має бути HH:mm (наприклад, 08:30)"
                                return@Button
                            }
                            val days = intervalDaysText.trim().toIntOrNull()?.takeIf { it >= 1 }
                            if (days == null) {
                                validationError = "Вкажіть коректний інтервал днів (від 1 і більше)"
                                return@Button
                            }
                            val validStart = runCatching { LocalDate.parse(intervalStartDateText.trim()) }.getOrNull()
                            if (validStart == null) {
                                validationError = "Некоректна дата початку (формат YYYY-MM-DD)"
                                return@Button
                            }
                            ScheduleRule.EveryNDays(time = normalized, intervalDays = days, startDate = validStart.toString())
                        }

                        ScheduleKind.ONE_OFF -> {
                            val normalized = TimeInputValidator.normalizeTimeOrNull(fixedTimeText)
                            if (normalized == null) {
                                validationError = "Формат часу має бути HH:mm (наприклад, 08:30)"
                                return@Button
                            }
                            val validDate = runCatching { LocalDate.parse(oneOffDateText.trim()) }.getOrNull()
                            if (validDate == null) {
                                validationError = "Некоректна дата (формат YYYY-MM-DD)"
                                return@Button
                            }
                            ScheduleRule.OneOff(date = validDate.toString(), time = normalized)
                        }

                        ScheduleKind.ANCHOR -> {
                            val offset = offsetMinutesText.trim().toIntOrNull()
                            if (selectedAnchorId.isBlank() || offset == null) {
                                validationError = "Оберіть якір та коректні хвилини"
                                return@Button
                            }
                            ScheduleRule.RelativeToAnchor(anchorId = selectedAnchorId, offsetMinutes = offset)
                        }

                        ScheduleKind.HOURLY_INTERVAL -> error("Handled above")
                    }

                    val newTask = TaskRule(
                        id = initialTask?.id ?: "task_${UUID.randomUUID().toString().take(8)}",
                        type = taskType,
                        criticality = criticality,
                        title = trimmedTitle,
                        doseVisualCount = doseCount,
                        doseLabel = doseLabel.trim().takeIf { it.isNotEmpty() },
                        instruction = instruction.trim().takeIf { it.isNotEmpty() },
                        photoMediaId = photoMediaId,
                        voiceMediaId = voiceMediaId,
                        schedule = scheduleRule,
                        escalation = if (criticality == Criticality.CRITICAL) {
                            initialTask?.escalation ?: EscalationPolicy()
                        } else {
                            null
                        },
                        inventory = inventory,
                    )

                    onSave(listOf(newTask), pendingUploads.toList())
                },
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати")
            }
        },
    )
}

private fun formatNumber(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}
