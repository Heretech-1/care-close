package com.careclose.feature.elder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.careclose.core.alarms.AlarmPermissionChecker
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.AppLanguage
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Gender
import com.careclose.core.model.Role
import com.careclose.core.model.engine.CareCloseStrings
import com.careclose.core.model.engine.CareCloseWording
import com.careclose.core.model.engine.DailyScheduleResolver
import com.careclose.core.model.engine.ResolvedDailyAnchor
import com.careclose.core.model.engine.ResolvedDailyTask
import com.careclose.core.model.engine.ResolvedTaskStatus
import com.careclose.core.sync.SyncEngine
import com.careclose.feature.elder.widget.ElderGlanceWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

@Composable
fun ElderDashboardScreen(
    repository: CareCloseRepository,
    deviceConfigStore: DeviceConfigStore,
    syncEngineProvider: () -> SyncEngine?,
    onEventRecorded: suspend () -> Unit,
    onOpenPermissionsSetup: () -> Unit,
    onOpenServiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val activePlan by repository.observeActivePlan().collectAsState(initial = null)
    val planZone = remember(activePlan?.timezone) {
        activePlan?.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
    }

    var currentInstant by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentInstant = Instant.now()
            delay(30_000L)
        }
    }

    val todayDate = remember(currentInstant, planZone) { LocalDate.now(planZone) }
    val events by repository.observeEventsForDate(todayDate.toString()).collectAsState(initial = emptyList())
    val elderPresence by repository.observeLatestElderPresence().collectAsState(initial = null)

    var permissionStatus by remember { mutableStateOf(AlarmPermissionChecker.check(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = AlarmPermissionChecker.check(context)
                currentInstant = Instant.now()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Фонове кешування фото таблеток і голосових нагадувань
    LaunchedEffect(activePlan?.planVersion) {
        val plan = activePlan ?: return@LaunchedEffect
        val engine = syncEngineProvider() ?: return@LaunchedEffect
        val mediaCacheDir = File(context.filesDir, "careclose_media")
        val mediaIds = plan.tasks.flatMap { listOfNotNull(it.photoMediaId, it.voiceMediaId) }.distinct()
        withContext(Dispatchers.IO) {
            mediaIds.forEach { mediaId ->
                runCatching { engine.ensureMediaCached(mediaId, mediaCacheDir) }
            }
        }
    }

    var undoBannerTask by remember { mutableStateOf<UndoTarget?>(null) }
    LaunchedEffect(undoBannerTask) {
        if (undoBannerTask != null) {
            delay(5_000L)
            undoBannerTask = null
        }
    }

    val timeline = remember(activePlan, todayDate, events, elderPresence, currentInstant) {
        activePlan?.let { plan ->
            DailyScheduleResolver.resolve(
                plan = plan,
                logicalDate = todayDate,
                events = events,
                elderPresence = elderPresence,
                now = currentInstant,
            )
        }
    }

    val appLanguage = remember { deviceConfigStore.getAppLanguage() }
    val effectiveLocale = remember(appLanguage) { CareCloseStrings.getLocale(appLanguage) }

    val clockFormatter = remember(planZone) { DateTimeFormatter.ofPattern("HH:mm").withZone(planZone) }
    val dateFormatter = remember(planZone, effectiveLocale) {
        DateTimeFormatter.ofPattern("EEEE, d MMMM", effectiveLocale).withZone(planZone)
    }
    val dayPeriod = remember(currentInstant, planZone, appLanguage) {
        val hour = ZonedDateTime.ofInstant(currentInstant, planZone).hour
        when (hour) {
            in 5..11 -> CareCloseStrings.periodMorning(appLanguage)
            in 12..17 -> CareCloseStrings.periodAfternoon(appLanguage)
            in 18..22 -> CareCloseStrings.periodEvening(appLanguage)
            else -> CareCloseStrings.periodNight(appLanguage)
        }
    }

    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = clockFormatter.format(currentInstant),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "${dateFormatter.format(currentInstant).replaceFirstChar { it.uppercase() }} · $dayPeriod",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val contact = activePlan?.emergencyContact
                if (contact != null && contact.phone.isNotBlank()) {
                    Button(
                        onClick = {
                            runCatching {
                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone.trim()}"))
                                context.startActivity(dialIntent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                        ),
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 56.dp),
                    ) {
                        Icon(imageVector = Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = contact.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (!permissionStatus.allCriticalGranted) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = CareCloseStrings.elderAlarmsPermissionNeeded(appLanguage),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Button(
                            onClick = onOpenPermissionsSetup,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(CareCloseStrings.elderEnable(appLanguage), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        undoBannerTask?.let { undoTarget ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val prefix = activePlan?.let { p ->
                            CareCloseWording.elderRecordedConfirmation(
                                CareCloseWording.recipientDisplayName(p, appLanguage),
                                p.recipientRole.gender,
                                undoTarget.taskType,
                                appLanguage,
                            )
                        } ?: if (appLanguage == AppLanguage.ENGLISH) "Recorded" else "Записано"
                        Text(
                            text = "$prefix: ${undoTarget.title}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        OutlinedButton(
                            onClick = {
                                val targetPlan = activePlan ?: return@OutlinedButton
                                undoBannerTask = null
                                scope.launch {
                                    repository.recordLocalEvent(
                                        CareEvent(
                                            eventId = UUID.randomUUID().toString(),
                                            deviceId = deviceConfigStore.getDeviceId(),
                                            actorRole = Role.ELDER,
                                            logicalDate = todayDate.toString(),
                                            planVersion = targetPlan.planVersion,
                                            taskId = undoTarget.taskId,
                                            type = CareEventType.TAKEN_UNDONE,
                                            occurredAtUtc = Instant.now().toString(),
                                            targetEventId = undoTarget.eventId,
                                        ),
                                    )
                                    onEventRecorded()
                                    ElderGlanceWidget.requestUpdate(context)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        ) {
                            Text(
                                CareCloseStrings.elderUndo(appLanguage),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (timeline != null) {
            val activeFocusTask = pickFocusTask(timeline.tasks)
            item {
                if (activeFocusTask != null) {
                    FocusTaskCard(
                        item = activeFocusTask,
                        repository = repository,
                        timeFormatter = clockFormatter,
                        recipientGender = activePlan?.recipientRole?.gender ?: Gender.UNSPECIFIED,
                        appLanguage = appLanguage,
                        onMarkTaken = {
                            val eventId = UUID.randomUUID().toString()
                            scope.launch {
                                repository.recordLocalEvent(
                                    CareEvent(
                                        eventId = eventId,
                                        deviceId = deviceConfigStore.getDeviceId(),
                                        actorRole = Role.ELDER,
                                        logicalDate = timeline.date.toString(),
                                        planVersion = timeline.planVersion,
                                        taskId = activeFocusTask.task.id,
                                        type = CareEventType.TAKEN,
                                        occurredAtUtc = Instant.now().toString(),
                                    ),
                                )
                                undoBannerTask = UndoTarget(
                                    eventId = eventId,
                                    taskId = activeFocusTask.task.id,
                                    title = activeFocusTask.task.title,
                                    taskType = activeFocusTask.task.type,
                                )
                                onEventRecorded()
                                ElderGlanceWidget.requestUpdate(context)
                            }
                        },
                        onSnooze = {
                            scope.launch {
                                val now = Instant.now()
                                repository.recordLocalEvent(
                                    CareEvent(
                                        eventId = UUID.randomUUID().toString(),
                                        deviceId = deviceConfigStore.getDeviceId(),
                                        actorRole = Role.ELDER,
                                        logicalDate = timeline.date.toString(),
                                        planVersion = timeline.planVersion,
                                        taskId = activeFocusTask.task.id,
                                        type = CareEventType.SNOOZED,
                                        occurredAtUtc = now.toString(),
                                        snoozeUntilUtc = now.plusSeconds(10 * 60L).toString(),
                                    ),
                                )
                                onEventRecorded()
                                ElderGlanceWidget.requestUpdate(context)
                            }
                        },
                    )
                } else {
                    AllDoneCard(appLanguage = appLanguage)
                }
            }

            val pendingAnchors = timeline.anchors.filter { !it.isConfirmed }
            if (pendingAnchors.isNotEmpty()) {
                item {
                    Text(
                        text = CareCloseStrings.elderMarkWhenStart(appLanguage),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                items(pendingAnchors, key = { it.anchor.id }) { anchorItem ->
                    AnchorActionCard(
                        anchor = anchorItem,
                        appLanguage = appLanguage,
                        onConfirm = {
                            scope.launch {
                                repository.recordLocalEvent(
                                    CareEvent(
                                        eventId = UUID.randomUUID().toString(),
                                        deviceId = deviceConfigStore.getDeviceId(),
                                        actorRole = Role.ELDER,
                                        logicalDate = timeline.date.toString(),
                                        planVersion = timeline.planVersion,
                                        taskId = anchorItem.anchor.id,
                                        type = CareEventType.ANCHOR_CONFIRMED,
                                        occurredAtUtc = Instant.now().toString(),
                                    ),
                                )
                                onEventRecorded()
                                ElderGlanceWidget.requestUpdate(context)
                            }
                        },
                    )
                }
            }

            val upcomingTasks = timeline.tasks.filter { item ->
                item.task.id != activeFocusTask?.task?.id &&
                    item.status != ResolvedTaskStatus.COMPLETED &&
                    !item.isMissingAnchor
            }
            if (upcomingTasks.isNotEmpty()) {
                item {
                    Text(
                        text = CareCloseStrings.elderUpcomingToday(appLanguage),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                items(upcomingTasks, key = { "upcoming_${it.task.id}" }) { upcomingItem ->
                    UpcomingTaskRow(item = upcomingItem, timeFormatter = clockFormatter)
                }
            }

            val completedToday = timeline.tasks.filter { it.status == ResolvedTaskStatus.COMPLETED }
            if (completedToday.isNotEmpty()) {
                item {
                    Text(
                        text = CareCloseStrings.elderCompletedToday(appLanguage),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(completedToday, key = { "done_${it.task.id}" }) { doneItem ->
                    CompletedTaskRow(item = doneItem, timeFormatter = clockFormatter)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            var holdStartTime by remember { mutableStateOf(0L) }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                pinInput = ""
                                pinError = false
                                showPinDialog = true
                            },
                            onLongPress = {
                                // Fallback emergency long press: direct open if held 4+ seconds
                                onOpenServiceSettings()
                            },
                        )
                    }
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = CareCloseStrings.elderServiceSettingsProtected(appLanguage),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPinDialog) {
        val expectedPin = remember { deviceConfigStore.getCaregiverPin() }
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                pinInput = ""
                pinError = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (appLanguage == AppLanguage.ENGLISH) "Caregiver Entrance" else "Вхід для опікуна",
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = CareCloseStrings.elderEnterCaregiverPin(appLanguage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                                pinInput = it
                                pinError = false
                                if (it == expectedPin) {
                                    showPinDialog = false
                                    onOpenServiceSettings()
                                }
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = pinError,
                        label = { Text("PIN (4 цифри)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (pinError) {
                        Text(
                            text = CareCloseStrings.elderPinIncorrect(appLanguage),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text = CareCloseStrings.elderHoldToUnlock(appLanguage),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInput == expectedPin) {
                            showPinDialog = false
                            onOpenServiceSettings()
                        } else {
                            pinError = true
                        }
                    },
                ) {
                    Text(CareCloseStrings.elderConfirm(appLanguage))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinDialog = false
                        pinInput = ""
                        pinError = false
                    },
                ) {
                    Text(CareCloseStrings.elderCancel(appLanguage))
                }
            },
        )
    }
}

@Composable
private fun FocusTaskCard(
    item: ResolvedDailyTask,
    repository: CareCloseRepository,
    timeFormatter: DateTimeFormatter,
    recipientGender: Gender = Gender.UNSPECIFIED,
    appLanguage: AppLanguage = AppLanguage.UKRAINIAN,
    onMarkTaken: () -> Unit,
    onSnooze: () -> Unit,
) {
    val isUrgent = item.status == ResolvedTaskStatus.DUE_NOW ||
        item.status == ResolvedTaskStatus.MISSED_ONLINE ||
        item.status == ResolvedTaskStatus.MISSED_DEVICE_OFFLINE

    val pillBitmap by produceState<Bitmap?>(initialValue = null, item.task.photoMediaId) {
        val mediaId = item.task.photoMediaId ?: return@produceState
        val cached = repository.getCachedMedia(mediaId) ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(cached.localFilePath) }.getOrNull()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUrgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        ),
        border = if (isUrgent) {
            BorderStroke(3.dp, MaterialTheme.colorScheme.error)
        } else {
            BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val badgeText = when {
                item.isMissingAnchor -> if (appLanguage == AppLanguage.ENGLISH) "SETUP REQUIRED" else "ПОТРІБНЕ НАЛАШТУВАННЯ"
                isUrgent -> if (appLanguage == AppLanguage.ENGLISH) "ACTION NEEDED NOW" else "ЗАРАЗ ПОТРІБНО"
                item.status == ResolvedTaskStatus.SNOOZED && item.snoozedUntil != null ->
                    if (appLanguage == AppLanguage.ENGLISH) "SNOOZED UNTIL ${timeFormatter.format(item.snoozedUntil)}" else "ВІДКЛАДЕНО ДО ${timeFormatter.format(item.snoozedUntil)}"
                item.scheduledAt != null -> if (appLanguage == AppLanguage.ENGLISH) "SCHEDULED FOR ${timeFormatter.format(item.scheduledAt)}" else "ЗАПЛАНОВАНО НА ${timeFormatter.format(item.scheduledAt)}"
                else -> if (appLanguage == AppLanguage.ENGLISH) "AFTER EVENT: ${item.anchorTitle?.uppercase() ?: "MEAL"}" else "ПІСЛЯ ПОДІЇ: ${item.anchorTitle?.uppercase() ?: "ЇЖІ"}"
            }

            Surface(
                color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = badgeText,
                    color = if (isUrgent) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = item.task.title,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            pillBitmap?.let { bmp ->
                Spacer(modifier = Modifier.height(14.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = item.task.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }

            item.task.doseVisualCount?.let { count ->
                if (count > 0.0) {
                    Spacer(modifier = Modifier.height(14.dp))
                    DoseCirclesIndicator(count = count)
                }
            }

            item.task.doseLabel?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = it,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            item.task.instruction?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (item.isMissingAnchor) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = CareCloseStrings.elderContactCaregiver(appLanguage),
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            } else if (item.status != ResolvedTaskStatus.WAITING_FOR_ANCHOR) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onMarkTaken,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 76.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = CareCloseWording.elderActionTakenButton(recipientGender, item.task.type, appLanguage),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                if (isUrgent) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Text(
                            text = CareCloseStrings.elderRemind10Min(appLanguage),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchorActionCard(
    anchor: ResolvedDailyAnchor,
    appLanguage: AppLanguage = AppLanguage.UKRAINIAN,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = anchor.anchor.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (appLanguage == AppLanguage.ENGLISH) "Tap when you begin this event" else "Натисніть, коли почнете подію",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier.defaultMinSize(minHeight = 52.dp),
            ) {
                Text(
                    text = CareCloseStrings.elderIStarted(appLanguage),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CompletedTaskRow(
    item: ResolvedDailyTask,
    timeFormatter: DateTimeFormatter,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.task.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            item.completedAt?.let { doneAt ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = timeFormatter.format(doneAt),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingTaskRow(
    item: ResolvedDailyTask,
    timeFormatter: DateTimeFormatter,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    val timeLabel = item.scheduledAt?.let { timeFormatter.format(it) }
                        ?: "Після: ${item.anchorTitle ?: "події"}"
                    Text(
                        text = timeLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = item.task.title,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    item.task.doseLabel?.let { dose ->
                        Text(
                            text = dose,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                text = if (item.task.type == com.careclose.core.model.TaskType.MEDICATION) "💊" else "📋",
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun AllDoneCard(appLanguage: AppLanguage = AppLanguage.UKRAINIAN) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = CareCloseStrings.elderAllDone(appLanguage),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = CareCloseStrings.elderAllDoneSub(appLanguage),
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DoseCirclesIndicator(count: Double) {
    val fullPills = count.toInt().coerceIn(0, 6)
    val hasHalf = (count - fullPills) >= 0.25
    Surface(
        color = Color(0xFFE3F2FD),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(fullPills) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0D47A1)),
                )
            }
            if (hasHalf) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF64B5F6)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("½", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                }
            }
        }
    }
}

private data class UndoTarget(
    val eventId: String,
    val taskId: String,
    val title: String,
    val taskType: com.careclose.core.model.TaskType = com.careclose.core.model.TaskType.MEDICATION,
)

private fun pickFocusTask(tasks: List<ResolvedDailyTask>): ResolvedDailyTask? {
    val validTasks = tasks.filterNot { it.isMissingAnchor }
    val candidateList = if (validTasks.isNotEmpty()) validTasks else tasks
    val priorityOrder = listOf(
        ResolvedTaskStatus.MISSED_ONLINE,
        ResolvedTaskStatus.MISSED_DEVICE_OFFLINE,
        ResolvedTaskStatus.DUE_NOW,
        ResolvedTaskStatus.SNOOZED,
        ResolvedTaskStatus.PENDING,
        ResolvedTaskStatus.WAITING_FOR_ANCHOR,
    )
    for (status in priorityOrder) {
        val match = candidateList.firstOrNull { it.status == status }
        if (match != null) return match
    }
    return null
}
