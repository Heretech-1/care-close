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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.careclose.core.alarms.AlarmPermissionChecker
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Gender
import com.careclose.core.model.Role
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

    val ukLocale = remember { Locale.forLanguageTag("uk-UA") }
    val clockFormatter = remember(planZone) { DateTimeFormatter.ofPattern("HH:mm").withZone(planZone) }
    val dateFormatter = remember(planZone, ukLocale) {
        DateTimeFormatter.ofPattern("EEEE, d MMMM", ukLocale).withZone(planZone)
    }
    val dayPeriod = remember(currentInstant, planZone) {
        val hour = ZonedDateTime.ofInstant(currentInstant, planZone).hour
        when (hour) {
            in 5..11 -> "Ранок"
            in 12..17 -> "День"
            in 18..22 -> "Вечір"
            else -> "Ніч"
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF6F8F5))
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
                        color = Color(0xFF111418),
                    )
                    Text(
                        text = "${dateFormatter.format(currentInstant).replaceFirstChar { it.uppercase() }} · $dayPeriod",
                        fontSize = 18.sp,
                        color = Color(0xFF42474E),
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Icon(imageVector = Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = contact.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (!permissionStatus.allCriticalGranted) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
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
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = Color(0xFFE65100),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Потрібно увімкнути дозволи для будильників",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        TextButton(onClick = onOpenPermissionsSetup) {
                            Text("Увімкнути", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        undoBannerTask?.let { undoTarget ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFC8E6C9)),
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
                                CareCloseWording.recipientDisplayName(p),
                                p.recipientRole.gender,
                                undoTarget.taskType,
                            )
                        } ?: "Записано"
                        Text(
                            text = "$prefix: ${undoTarget.title}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
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
                        ) {
                            Text("Скасувати", fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                    AllDoneCard()
                }
            }

            val pendingAnchors = timeline.anchors.filter { !it.isConfirmed }
            if (pendingAnchors.isNotEmpty()) {
                item {
                    Text(
                        text = "Відмітьте, коли почнете:",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111418),
                    )
                }
                items(pendingAnchors, key = { it.anchor.id }) { anchorItem ->
                    AnchorActionCard(
                        anchor = anchorItem,
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

            val completedToday = timeline.tasks.filter { it.status == ResolvedTaskStatus.COMPLETED }
            if (completedToday.isNotEmpty()) {
                item {
                    Text(
                        text = "Вже зроблено сьогодні",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20),
                    )
                }
                items(completedToday, key = { "done_${it.task.id}" }) { doneItem ->
                    CompletedTaskRow(item = doneItem, timeFormatter = clockFormatter)
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onOpenServiceSettings() },
                        )
                    }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Сервісні налаштування (утримуйте для входу)",
                    fontSize = 14.sp,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FocusTaskCard(
    item: ResolvedDailyTask,
    repository: CareCloseRepository,
    timeFormatter: DateTimeFormatter,
    recipientGender: Gender = Gender.UNSPECIFIED,
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
            containerColor = if (isUrgent) Color(0xFFFFF8E1) else Color.White,
        ),
        border = if (isUrgent) BorderStroke(2.dp, Color(0xFFFFB74D)) else BorderStroke(1.dp, Color(0xFFE0E0E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val badgeText = when {
                isUrgent -> "ЗАРАЗ ПОТРІБНО"
                item.status == ResolvedTaskStatus.SNOOZED && item.snoozedUntil != null ->
                    "ВІДКЛАДЕНО ДО ${timeFormatter.format(item.snoozedUntil)}"
                item.scheduledAt != null -> "ЗАПЛАНОВАНО НА ${timeFormatter.format(item.scheduledAt)}"
                else -> "ПІСЛЯ ПОДІЇ: ${item.anchorTitle?.uppercase() ?: "ЇЖІ"}"
            }

            Surface(
                color = if (isUrgent) Color(0xFFE65100) else Color(0xFFE8F5E9),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = badgeText,
                    color = if (isUrgent) Color.White else Color(0xFF1B5E20),
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
                color = Color(0xFF111418),
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
                )
            }

            item.task.instruction?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    fontSize = 18.sp,
                    color = Color(0xFF42474E),
                    textAlign = TextAlign.Center,
                )
            }

            if (item.status != ResolvedTaskStatus.WAITING_FOR_ANCHOR) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onMarkTaken,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = CareCloseWording.elderActionTakenButton(recipientGender, item.task.type),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }

                if (isUrgent) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(2.dp, Color(0xFF78909C)),
                    ) {
                        Text(
                            text = "НАГАДАТИ ЧЕРЕЗ 10 ХВ",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF263238),
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
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
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
                    color = Color(0xFF0D47A1),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Натисніть, коли почнете подію",
                    fontSize = 15.sp,
                    color = Color(0xFF1565C0),
                )
            }
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D47A1)),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Text(
                    text = "ПОЧИНАЮ",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
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
                    color = Color(0xFF2E7D32),
                    shape = CircleShape,
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.task.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0A2E0D),
                )
            }
            item.completedAt?.let { doneAt ->
                Surface(
                    color = Color(0xFFC8E6C9),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = "о ${timeFormatter.format(doneAt)}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1B5E20),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AllDoneCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Усі справи на сьогодні виконано ✨",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B5E20),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Можна спокійно відпочивати",
                fontSize = 18.sp,
                color = Color(0xFF2E7D32),
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
    val priorityOrder = listOf(
        ResolvedTaskStatus.MISSED_ONLINE,
        ResolvedTaskStatus.MISSED_DEVICE_OFFLINE,
        ResolvedTaskStatus.DUE_NOW,
        ResolvedTaskStatus.SNOOZED,
        ResolvedTaskStatus.PENDING,
        ResolvedTaskStatus.WAITING_FOR_ANCHOR,
    )
    for (status in priorityOrder) {
        val match = tasks.firstOrNull { it.status == status }
        if (match != null) return match
    }
    return null
}
