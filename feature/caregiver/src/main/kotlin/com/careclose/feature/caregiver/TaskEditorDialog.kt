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
import java.util.UUID

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
    onSave: (TaskRule, List<PendingMediaUpload>) -> Unit,
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
    var isRelative by remember {
        mutableStateOf(initialRule is ScheduleRule.RelativeToAnchor && anchors.isNotEmpty())
    }
    var fixedTimeText by remember {
        mutableStateOf((initialRule as? ScheduleRule.Fixed)?.time ?: "08:00")
    }
    var selectedAnchorId by remember {
        mutableStateOf(
            (initialRule as? ScheduleRule.RelativeToAnchor)?.anchorId
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

                Text(text = "Коли нагадувати:", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isRelative,
                        onClick = { isRelative = false },
                        label = { Text("Фіксований час") },
                    )
                    if (anchors.isNotEmpty()) {
                        FilterChip(
                            selected = isRelative,
                            onClick = { isRelative = true },
                            label = { Text("Після події-якоря") },
                        )
                    }
                }

                if (!isRelative) {
                    OutlinedTextField(
                        value = fixedTimeText,
                        onValueChange = { fixedTimeText = it },
                        label = { Text("Час (HH:mm, напр. 08:30)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
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

                    val scheduleRule = if (!isRelative) {
                        val normalized = TimeInputValidator.normalizeTimeOrNull(fixedTimeText)
                        if (normalized == null) {
                            validationError = "Формат часу має бути HH:mm (наприклад, 08:30)"
                            return@Button
                        }
                        ScheduleRule.Fixed(time = normalized)
                    } else {
                        val offset = offsetMinutesText.trim().toIntOrNull()
                        if (selectedAnchorId.isBlank() || offset == null) {
                            validationError = "Оберіть якір та коректні хвилини"
                            return@Button
                        }
                        ScheduleRule.RelativeToAnchor(anchorId = selectedAnchorId, offsetMinutes = offset)
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

                    onSave(newTask, pendingUploads.toList())
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
