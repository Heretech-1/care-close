package com.careclose.feature.caregiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.AnchorRule
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Criticality
import com.careclose.core.model.EmergencyContact
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole
import com.careclose.core.model.Role
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.engine.CareCloseWording
import com.careclose.core.model.engine.DailyScheduleResolver
import com.careclose.core.model.engine.InventoryCalculator
import com.careclose.core.model.engine.InventoryStatus
import com.careclose.core.model.engine.PresenceEvaluator
import com.careclose.core.model.engine.PresenceHealth
import com.careclose.core.model.engine.PresenceWarning
import com.careclose.core.model.engine.ResolvedDailyTask
import com.careclose.core.model.engine.ResolvedTaskStatus
import com.careclose.core.sync.SyncConfigStore
import com.careclose.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private enum class CaregiverTab(val title: String) {
    TODAY("Сьогодні"),
    SCHEDULE("Розклад"),
    INVENTORY("Аптечка"),
    SETTINGS("Налаштування"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaregiverDashboardScreen(
    repository: CareCloseRepository,
    deviceConfigStore: DeviceConfigStore,
    syncConfigStore: SyncConfigStore,
    syncEngineProvider: () -> SyncEngine?,
    onLocalEventRecorded: suspend () -> Unit,
    onScheduleOrSyncUpdated: suspend () -> Unit,
    onRunTestMissedCheck: suspend () -> Unit,
    onOpenOnboardingSettings: () -> Unit,
    onSwitchProfile: (String) -> Unit = {},
    onAddNewElderProfile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaCacheDir = remember { File(context.filesDir, "careclose_media") }

    val allProfiles = remember(syncConfigStore) { syncConfigStore.getAllProfiles() }
    val currentProfile = remember(syncConfigStore, allProfiles) { syncConfigStore.getPairingPayload() }
    var profileMenuExpanded by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(CaregiverTab.TODAY) }
    val activePlan by repository.observeActivePlan().collectAsState(initial = null)
    val allEvents by repository.observeAllEvents().collectAsState(initial = emptyList())
    val elderPresence by repository.observeLatestElderPresence().collectAsState(initial = null)

    var currentInstant by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentInstant = Instant.now()
            delay(30_000L)
        }
    }

    val planZone = remember(activePlan?.timezone) {
        activePlan?.timezone?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
    }
    var selectedDate by remember(planZone) { mutableStateOf(LocalDate.now(planZone)) }
    val dayEvents by repository.observeEventsForDate(selectedDate.toString()).collectAsState(initial = emptyList())

    var isSyncing by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var editingTask by remember { mutableStateOf<TaskRule?>(null) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var showAnchorDialog by remember { mutableStateOf(false) }

    suspend fun publishUpdatedPlan(
        updatedPlan: PlanSnapshot,
        pendingMedia: List<PendingMediaUpload> = emptyList(),
    ) {
        isSyncing = true
        try {
            pendingMedia.forEach { upload ->
                repository.saveCachedMedia(
                    MediaCacheEntity(
                        mediaId = upload.mediaId,
                        localFilePath = upload.localFile.absolutePath,
                        md5Checksum = null,
                        downloadedAtUtc = Instant.now().toString(),
                    ),
                )
            }
            val engine = syncEngineProvider()
            if (engine != null) {
                withContext(Dispatchers.IO) {
                    pendingMedia.forEach { upload ->
                        val remoteMeta = engine.uploadMedia(
                            mediaId = upload.mediaId,
                            mimeType = upload.mimeType,
                            plainBytes = upload.localFile.readBytes(),
                        )
                        repository.saveCachedMedia(
                            MediaCacheEntity(
                                mediaId = upload.mediaId,
                                localFilePath = upload.localFile.absolutePath,
                                md5Checksum = remoteMeta.md5Checksum,
                                downloadedAtUtc = Instant.now().toString(),
                            ),
                        )
                    }
                    engine.publishPlan(updatedPlan)
                }
                statusBanner = "План v${updatedPlan.planVersion} опубліковано на Google Drive"
            } else {
                repository.saveActivePlan(updatedPlan)
                statusBanner = "План v${updatedPlan.planVersion} збережено локально"
            }
            currentInstant = Instant.now()
            onScheduleOrSyncUpdated()
        } finally {
            isSyncing = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val recipientName = currentProfile?.familyName
                        ?: activePlan?.let { CareCloseWording.recipientDisplayName(it) }
                        ?: "Опікун"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "CareClose · $recipientName",
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = { profileMenuExpanded = true }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Вибір підопічного")
                        }
                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                        ) {
                            allProfiles.forEach { profile ->
                                val isSelected = profile.familyId == currentProfile?.familyId
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${profile.familyName} ${if (isSelected) "✓" else ""}",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        profileMenuExpanded = false
                                        if (!isSelected) {
                                            onSwitchProfile(profile.familyId)
                                        }
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("+ Додати підопічного (Мама / Тато)") },
                                onClick = {
                                    profileMenuExpanded = false
                                    onAddNewElderProfile()
                                },
                            )
                        }
                    }
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = {
                                val engine = syncEngineProvider()
                                if (engine == null) {
                                    statusBanner = "Google Drive ще не підключено"
                                    return@IconButton
                                }
                                isSyncing = true
                                scope.launch {
                                    val res = runCatching {
                                        withContext(Dispatchers.IO) {
                                            engine.syncOnce()
                                        }
                                    }
                                    isSyncing = false
                                    res.onSuccess { report ->
                                        currentInstant = Instant.now()
                                        statusBanner = "Синхронізовано: +${report.importedEventsCount} подій"
                                        onScheduleOrSyncUpdated()
                                    }.onFailure { err ->
                                        statusBanner = "Помилка синхронізації: ${err.message}"
                                    }
                                }
                            },
                        ) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = "Синхронізувати")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == CaregiverTab.TODAY,
                    onClick = { activeTab = CaregiverTab.TODAY },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text(CaregiverTab.TODAY.title) },
                )
                NavigationBarItem(
                    selected = activeTab == CaregiverTab.SCHEDULE,
                    onClick = { activeTab = CaregiverTab.SCHEDULE },
                    icon = { Icon(Icons.Rounded.DateRange, contentDescription = null) },
                    label = { Text(CaregiverTab.SCHEDULE.title) },
                )
                NavigationBarItem(
                    selected = activeTab == CaregiverTab.INVENTORY,
                    onClick = { activeTab = CaregiverTab.INVENTORY },
                    icon = { Icon(Icons.Rounded.ShoppingCart, contentDescription = null) },
                    label = { Text(CaregiverTab.INVENTORY.title) },
                )
                NavigationBarItem(
                    selected = activeTab == CaregiverTab.SETTINGS,
                    onClick = { activeTab = CaregiverTab.SETTINGS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(CaregiverTab.SETTINGS.title) },
                )
            }
        },
        floatingActionButton = {
            if (activeTab == CaregiverTab.SCHEDULE && activePlan != null) {
                FloatingActionButton(
                    onClick = {
                        editingTask = null
                        showTaskDialog = true
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Додати")
                }
            }
        },
    ) { innerPadding ->
        val plan = activePlan
        if (plan == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("План догляду ще не створено", style = MaterialTheme.typography.titleLarge)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            statusBanner?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { statusBanner = null }) { Text("ОК") }
                    }
                }
            }

            when (activeTab) {
                CaregiverTab.TODAY -> {
                    val timeline = remember(plan, selectedDate, dayEvents, elderPresence, currentInstant) {
                        DailyScheduleResolver.resolve(
                            plan = plan,
                            logicalDate = selectedDate,
                            events = dayEvents,
                            elderPresence = elderPresence,
                            now = currentInstant,
                        )
                    }
                    val presenceHealth = remember(elderPresence, plan.planVersion, currentInstant) {
                        PresenceEvaluator.evaluate(
                            presence = elderPresence,
                            expectedPlanVersion = plan.planVersion,
                            now = currentInstant,
                        )
                    }
                    TodayDashboardContent(
                        plan = plan,
                        timeline = timeline,
                        selectedDate = selectedDate,
                        presenceHealth = presenceHealth,
                        onPrevDay = { selectedDate = selectedDate.minusDays(1) },
                        onToday = { selectedDate = LocalDate.now(planZone) },
                        onNextDay = { selectedDate = selectedDate.plusDays(1) },
                        onOverrideTaken = { task ->
                            scope.launch {
                                repository.recordLocalEvent(
                                    CareEvent(
                                        eventId = UUID.randomUUID().toString(),
                                        deviceId = deviceConfigStore.getDeviceId(),
                                        actorRole = Role.CAREGIVER,
                                        logicalDate = selectedDate.toString(),
                                        planVersion = plan.planVersion,
                                        taskId = task.task.id,
                                        type = CareEventType.CAREGIVER_OVERRIDE_TAKEN,
                                        occurredAtUtc = Instant.now().toString(),
                                        note = CareCloseWording.caregiverOverrideNote(plan.caregiverRole),
                                    ),
                                )
                                currentInstant = Instant.now()
                                onLocalEventRecorded()
                            }
                        },
                        onUndoCompleted = { task ->
                            val targetEventId = task.completedEventId ?: return@TodayDashboardContent
                            scope.launch {
                                repository.recordLocalEvent(
                                    CareEvent(
                                        eventId = UUID.randomUUID().toString(),
                                        deviceId = deviceConfigStore.getDeviceId(),
                                        actorRole = Role.CAREGIVER,
                                        logicalDate = selectedDate.toString(),
                                        planVersion = plan.planVersion,
                                        taskId = task.task.id,
                                        type = CareEventType.TAKEN_UNDONE,
                                        occurredAtUtc = Instant.now().toString(),
                                        targetEventId = targetEventId,
                                    ),
                                )
                                currentInstant = Instant.now()
                                onLocalEventRecorded()
                            }
                        },
                    )
                }

                CaregiverTab.SCHEDULE -> {
                    ScheduleEditorContent(
                        plan = plan,
                        onEditTask = {
                            editingTask = it
                            showTaskDialog = true
                        },
                        onDeleteTask = { target ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                tasks = plan.tasks.filterNot { it.id == target.id },
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка публікації: ${it.message}" }
                            }
                        },
                        onAddAnchor = { showAnchorDialog = true },
                        onDeleteAnchor = { anchorId ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                anchors = plan.anchors.filterNot { it.id == anchorId },
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка публікації: ${it.message}" }
                            }
                        },
                        onUpdateContact = { contact ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                emergencyContact = contact,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка публікації: ${it.message}" }
                            }
                        },
                        onUpdateRoles = { recRole, cgRole, customName ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                recipientRole = recRole,
                                caregiverRole = cgRole,
                                recipientName = customName?.takeIf { it.isNotBlank() },
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка збереження звертань: ${it.message}" }
                            }
                        },
                    )
                }

                CaregiverTab.INVENTORY -> {
                    val statuses = remember(plan, allEvents) {
                        InventoryCalculator.calculate(plan, allEvents)
                    }
                    InventoryContent(
                        statuses = statuses,
                        onRestock = { status, addUnits ->
                            val updatedTasks = plan.tasks.map { task ->
                                if (task.id == status.taskId && task.inventory != null) {
                                    val newUnits = status.remainingUnits + addUnits
                                    task.copy(
                                        inventory = task.inventory!!.copy(
                                            remainingUnits = newUnits,
                                            stockUpdatedAtUtc = Instant.now().toString(),
                                        ),
                                    )
                                } else {
                                    task
                                }
                            }
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                tasks = updatedTasks,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка оновлення запасу: ${it.message}" }
                            }
                        },
                    )
                }

                CaregiverTab.SETTINGS -> {
                    CaregiverSettingsContent(
                        syncConfigStore = syncConfigStore,
                        planVersion = plan.planVersion,
                        onOpenOnboarding = onOpenOnboardingSettings,
                        onPublishCurrentPlan = {
                            scope.launch {
                                runCatching { publishUpdatedPlan(plan) }
                                    .onFailure { statusBanner = "Помилка публікації: ${it.message}" }
                            }
                        },
                        onTestMissedCheck = {
                            scope.launch {
                                onRunTestMissedCheck()
                                statusBanner = "Перевірку пропусків виконано"
                            }
                        },
                    )
                }
            }
        }
    }

    val currentPlan = activePlan
    if (showTaskDialog && currentPlan != null) {
        TaskEditorDialog(
            initialTask = editingTask,
            anchors = currentPlan.anchors,
            mediaCacheDir = mediaCacheDir,
            onDismiss = { showTaskDialog = false },
            onSave = { savedTask, uploads ->
                showTaskDialog = false
                val exists = currentPlan.tasks.any { it.id == savedTask.id }
                val newTasks = if (exists) {
                    currentPlan.tasks.map { if (it.id == savedTask.id) savedTask else it }
                } else {
                    currentPlan.tasks + savedTask
                }
                val updatedPlan = currentPlan.copy(
                    planVersion = currentPlan.planVersion + 1L,
                    effectiveFromUtc = Instant.now().toString(),
                    tasks = newTasks,
                )
                scope.launch {
                    runCatching { publishUpdatedPlan(updatedPlan, uploads) }
                        .onFailure { err -> statusBanner = "Помилка збереження: ${err.message}" }
                }
            },
        )
    }

    if (showAnchorDialog && currentPlan != null) {
        AddAnchorDialog(
            onDismiss = { showAnchorDialog = false },
            onAdd = { anchor ->
                showAnchorDialog = false
                val updated = currentPlan.copy(
                    planVersion = currentPlan.planVersion + 1L,
                    effectiveFromUtc = Instant.now().toString(),
                    anchors = currentPlan.anchors + anchor,
                )
                scope.launch {
                    runCatching { publishUpdatedPlan(updated) }
                        .onFailure { statusBanner = "Помилка збереження якоря: ${it.message}" }
                }
            },
        )
    }
}

@Composable
private fun TodayDashboardContent(
    plan: PlanSnapshot,
    timeline: com.careclose.core.model.engine.DailyTimeline,
    selectedDate: LocalDate,
    presenceHealth: PresenceHealth,
    onPrevDay: () -> Unit,
    onToday: () -> Unit,
    onNextDay: () -> Unit,
    onOverrideTaken: (ResolvedDailyTask) -> Unit,
    onUndoCompleted: (ResolvedDailyTask) -> Unit,
) {
    val timeFormatter = remember(timeline.timezone) {
        DateTimeFormatter.ofPattern("HH:mm").withZone(timeline.timezone)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            PresenceHealthCard(plan = plan, health = presenceHealth)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onPrevDay) { Text("← Вчора") }
                TextButton(onClick = onToday) {
                    Text(
                        text = selectedDate.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(onClick = onNextDay) { Text("Далі →") }
            }
        }

        items(timeline.tasks, key = { it.task.id }) { item ->
            CaregiverTimelineTaskCard(
                plan = plan,
                item = item,
                timeFormatter = timeFormatter,
                onOverrideTaken = { onOverrideTaken(item) },
                onUndoCompleted = { onUndoCompleted(item) },
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun PresenceHealthCard(plan: PlanSnapshot, health: PresenceHealth) {
    val hasWarnings = health.warnings.isNotEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasWarnings) Color(0xFFFFF3E0) else Color(0xFFE8F5E9),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val statusTitle = CareCloseWording.onlinePresenceStatus(
                plan = plan,
                isOnline = health.isOnline,
                minutesSinceLastSeen = health.minutesSinceLastSeen,
            )
            Text(text = statusTitle, fontWeight = FontWeight.Bold)

            if (health.batteryPercent != null) {
                val chargeLabel = if (health.isCharging == true) " (заряджається)" else ""
                Text(
                    text = "Батарея: ${health.batteryPercent}%$chargeLabel · Гучність будильника: ${health.alarmVolumePercent ?: "?"}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            health.warnings.forEach { warning ->
                val text = when (warning) {
                    PresenceWarning.DEVICE_OFFLINE -> "⚠️ Пристрій давно не передавав статус"
                    PresenceWarning.LOW_BATTERY_NOT_CHARGING -> "⚠️ Низький заряд батареї, не на зарядці"
                    PresenceWarning.ALARM_VOLUME_LOW -> "⚠️ Гучність будильника занадто тиха!"
                    PresenceWarning.DO_NOT_DISTURB_ACTIVE -> "⚠️ Увімкнено режим «Не турбувати»"
                    PresenceWarning.OUTDATED_PLAN_VERSION -> "ℹ️ Ще не завантажено новий розклад"
                }
                Text(text = text, color = Color(0xFFE65100), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CaregiverTimelineTaskCard(
    plan: PlanSnapshot,
    item: ResolvedDailyTask,
    timeFormatter: DateTimeFormatter,
    onOverrideTaken: () -> Unit,
    onUndoCompleted: () -> Unit,
) {
    val containerColor = when (item.status) {
        ResolvedTaskStatus.COMPLETED -> Color(0xFFE8F5E9)
        ResolvedTaskStatus.MISSED_ONLINE -> Color(0xFFFFEBEE)
        ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> Color(0xFFFFF3E0)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val timeStr = item.scheduledAt?.let { timeFormatter.format(it) }
                ?: "Після: ${item.anchorTitle ?: "події"}"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$timeStr · ${item.task.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusLabelUk(item),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item.task.doseLabel?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (item.status == ResolvedTaskStatus.COMPLETED) {
                TextButton(onClick = onUndoCompleted) {
                    Text("Скасувати відмітку")
                }
            } else {
                Button(
                    onClick = onOverrideTaken,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(CareCloseWording.caregiverOverrideButton(plan.caregiverRole))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorContent(
    plan: PlanSnapshot,
    onEditTask: (TaskRule) -> Unit,
    onDeleteTask: (TaskRule) -> Unit,
    onAddAnchor: () -> Unit,
    onDeleteAnchor: (String) -> Unit,
    onUpdateContact: (EmergencyContact) -> Unit,
    onUpdateRoles: (RecipientRole, CaregiverRole, String?) -> Unit,
) {
    var selectedRecipientRole by remember(plan.recipientRole) { mutableStateOf(plan.recipientRole) }
    var selectedCaregiverRole by remember(plan.caregiverRole) { mutableStateOf(plan.caregiverRole) }
    var recipientCustomName by remember(plan.recipientName) { mutableStateOf(plan.recipientName ?: "") }

    var contactName by remember(plan.emergencyContact?.name) {
        mutableStateOf(plan.emergencyContact?.name ?: "Опікун")
    }
    var contactPhone by remember(plan.emergencyContact?.phone) {
        mutableStateOf(plan.emergencyContact?.phone ?: "")
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Родина та звертання", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Кого ви доглядаєте:", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RecipientRole.MOTHER to "Мама",
                            RecipientRole.FATHER to "Тато",
                            RecipientRole.GRANDMOTHER to "Бабуся",
                            RecipientRole.GRANDFATHER to "Дідусь",
                            RecipientRole.OTHER to "Інше",
                        ).forEach { (role, label) ->
                            FilterChip(
                                selected = selectedRecipientRole == role,
                                onClick = { selectedRecipientRole = role },
                                label = { Text(label) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = recipientCustomName,
                        onValueChange = { recipientCustomName = it },
                        label = { Text("Ім'я підопічного (необов'язково)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Text("Хто доглядає (ваш статус):", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CaregiverRole.DAUGHTER to "Донька",
                            CaregiverRole.SON to "Син",
                            CaregiverRole.GRANDDAUGHTER to "Онука",
                            CaregiverRole.GRANDSON to "Онук",
                            CaregiverRole.OTHER to "Опікун",
                        ).forEach { (role, label) ->
                            FilterChip(
                                selected = selectedCaregiverRole == role,
                                onClick = { selectedCaregiverRole = role },
                                label = { Text(label) },
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            onUpdateRoles(
                                selectedRecipientRole,
                                selectedCaregiverRole,
                                recipientCustomName.trim().ifEmpty { null },
                            )
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Зберегти звертання")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Кнопка швидкого дзвінка у підопічного", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            label = { Text("Ім'я") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = contactPhone,
                            onValueChange = { contactPhone = it },
                            label = { Text("Телефон") },
                            modifier = Modifier.weight(1.3f),
                            singleLine = true,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (contactName.isNotBlank() && contactPhone.isNotBlank()) {
                                onUpdateContact(EmergencyContact(contactName.trim(), contactPhone.trim()))
                            }
                        },
                    ) {
                        Text("Зберегти контакт")
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Якорі дня (їжа / підйом)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAddAnchor) { Text("+ Додати якір") }
            }
        }

        items(plan.anchors, key = { it.id }) { anchor ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(anchor.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Вікно: ${anchor.windowStart}–${anchor.windowEnd}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { onDeleteAnchor(anchor.id) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Видалити")
                    }
                }
            }
        }

        item {
            Text(
                text = "Ліки та справи (версія плану v${plan.planVersion})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        items(plan.tasks, key = { it.id }) { task ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.Bold)
                        val ruleDesc = when (val rule = task.schedule) {
                            is ScheduleRule.Fixed -> "О ${rule.time}"
                            is ScheduleRule.RelativeToAnchor -> {
                                val anchorTitle = plan.anchors.firstOrNull { it.id == rule.anchorId }?.title
                                    ?: rule.anchorId
                                "Через ${rule.offsetMinutes} хв після «$anchorTitle»"
                            }
                        }
                        val badges = buildList {
                            add(ruleDesc)
                            if (task.criticality == Criticality.CRITICAL) add("Критично")
                            if (task.photoMediaId != null) add("📷")
                            if (task.voiceMediaId != null) add("🎙")
                        }.joinToString(" · ")
                        Text(text = badges, style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        IconButton(onClick = { onEditTask(task) }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Редагувати")
                        }
                        IconButton(onClick = { onDeleteTask(task) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Видалити")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(72.dp)) }
    }
}

@Composable
private fun InventoryContent(
    statuses: List<InventoryStatus>,
    onRestock: (InventoryStatus, Double) -> Unit,
) {
    if (statuses.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Немає препаратів із відстеженням залишку",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Увімкніть «Відстежувати залишок» при редагуванні ліків у вкладці Розклад.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(modifier = Modifier.height(6.dp)) }
        items(statuses, key = { it.taskId }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (item.isLowStock) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val estText = item.estimatedDaysRemaining?.let { " (~$it дн.)" }.orEmpty()
                    Text("Залишилось: ${item.remainingUnits} шт.$estText")
                    val ratio = (item.remainingUnits / (item.lowThresholdUnits * 4.0).coerceAtLeast(10.0))
                        .toFloat()
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())

                    if (item.isLowStock) {
                        Text(
                            text = "⚠️ Запас закінчується — пора купити нову пачку",
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRestock(item, 10.0) }) { Text("+10 шт") }
                        OutlinedButton(onClick = { onRestock(item, 30.0) }) { Text("+30 шт") }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun CaregiverSettingsContent(
    syncConfigStore: SyncConfigStore,
    planVersion: Long,
    onOpenOnboarding: () -> Unit,
    onPublishCurrentPlan: () -> Unit,
    onTestMissedCheck: () -> Unit,
) {
    val pairing = remember { syncConfigStore.getPairingPayload() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Стан синхронізації", fontWeight = FontWeight.Bold)
                if (pairing != null) {
                    Text("Сім'я: ${pairing.familyName} (${pairing.familyId})")
                    Text("Папка Drive: ${pairing.folderId}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Google Drive не налаштовано (локальний режим)")
                }
                Text("Активна версія плану: v$planVersion", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (pairing != null) {
            OutlinedButton(
                onClick = onPublishCurrentPlan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Опублікувати поточний план на Drive")
            }
        }

        Button(
            onClick = onOpenOnboarding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Налаштувати Drive / показати QR-код / змінити роль")
        }

        OutlinedButton(
            onClick = onTestMissedCheck,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Запустити перевірку пропусків зараз")
        }
    }
}

@Composable
private fun AddAnchorDialog(
    onDismiss: () -> Unit,
    onAdd: (AnchorRule) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var windowStart by remember { mutableStateOf("18:00") }
    var windowEnd by remember { mutableStateOf("20:30") }
    var softReminder by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новий якір дня") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Назва (напр. Вечеря)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = windowStart,
                    onValueChange = { windowStart = it },
                    label = { Text("Початок вікна (HH:mm)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = windowEnd,
                    onValueChange = { windowEnd = it },
                    label = { Text("Кінець вікна (HH:mm)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = softReminder,
                    onValueChange = { softReminder = it },
                    label = { Text("М'яке нагадування (HH:mm, необов'язково)") },
                    singleLine = true,
                )
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
                        validationError = "Вкажіть назву якоря"
                        return@Button
                    }
                    val startNorm = TimeInputValidator.normalizeTimeOrNull(windowStart)
                    val endNorm = TimeInputValidator.normalizeTimeOrNull(windowEnd)
                    if (startNorm == null || endNorm == null) {
                        validationError = "Формат часу має бути HH:mm (напр. 18:00)"
                        return@Button
                    }
                    val softNorm = if (softReminder.isBlank()) {
                        null
                    } else {
                        TimeInputValidator.normalizeTimeOrNull(softReminder) ?: run {
                            validationError = "Некоректний час м'якого нагадування (HH:mm)"
                            return@Button
                        }
                    }
                    onAdd(
                        AnchorRule(
                            id = "anchor_${UUID.randomUUID().toString().take(8)}",
                            title = trimmedTitle,
                            windowStart = startNorm,
                            windowEnd = endNorm,
                            softReminderAt = softNorm,
                        ),
                    )
                },
            ) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

private fun statusLabelUk(item: ResolvedDailyTask): String {
    return when (item.status) {
        ResolvedTaskStatus.WAITING_FOR_ANCHOR -> "Очікує подію"
        ResolvedTaskStatus.PENDING -> "Очікується"
        ResolvedTaskStatus.DUE_NOW -> "Зараз час"
        ResolvedTaskStatus.SNOOZED -> "Відкладено"
        ResolvedTaskStatus.COMPLETED -> "✅ Виконано"
        ResolvedTaskStatus.SKIPPED -> "Пропущено"
        ResolvedTaskStatus.MISSED_ONLINE -> "❗ Пропуск (онлайн)"
        ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> "⚠️ Пропуск (офлайн)"
    }
}
