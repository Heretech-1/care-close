package com.careclose.feature.caregiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.careclose.core.database.entity.MediaCacheEntity
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.model.AnchorRule
import com.careclose.core.model.AppThemeMode
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.CaregiverAccessLevel
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Criticality
import com.careclose.core.model.ElderQuickReturnMode
import com.careclose.core.model.EmergencyContact
import com.careclose.core.model.FloatingButtonSize
import com.careclose.core.model.PairingQrPayload
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
import com.careclose.core.sync.pairing.PairingPayloadCodec
import com.careclose.core.sync.pairing.QrBitmapGenerator
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
    onThemeChanged: (AppThemeMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaCacheDir = remember { File(context.filesDir, "careclose_media") }

    val accessLevel = remember { deviceConfigStore.getCaregiverAccessLevel() }
    val myCaregiverName = remember { deviceConfigStore.getCaregiverName() }
    var localThemeMode by remember { mutableStateOf(deviceConfigStore.getThemeMode()) }

    val allProfiles = remember(syncConfigStore) { syncConfigStore.getAllProfiles() }
    val currentProfile = remember(syncConfigStore, allProfiles) { syncConfigStore.getPairingPayload() }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var showShareHelperDialog by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf(CaregiverTab.TODAY) }
    val activePlan by repository.observeActivePlan().collectAsState(initial = null)
    val allEvents by repository.observeAllEvents().collectAsState(initial = emptyList())
    val elderPresence by repository.observeLatestElderPresence().collectAsState(initial = null)

    val myCaregiverRole = remember(accessLevel, activePlan) {
        deviceConfigStore.getCaregiverRole() ?: activePlan?.caregiverRole ?: CaregiverRole.OTHER
    }

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
    var showOneOffDialog by remember { mutableStateOf(false) }
    var showAnchorDialog by remember { mutableStateOf(false) }
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var anchorBlockedDeleteInfo by remember { mutableStateOf<Pair<AnchorRule, List<TaskRule>>?>(null) }
    var anchorConfirmDelete by remember { mutableStateOf<AnchorRule?>(null) }

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
                    val roleBadge = if (accessLevel == CaregiverAccessLevel.HELPER) {
                        val nameStr = myCaregiverName?.let { " $it" } ?: ""
                        " (${myCaregiverRole.defaultTitle}$nameStr)"
                    } else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "CareClose",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (accessLevel == CaregiverAccessLevel.PRIMARY) {
                            Surface(
                                onClick = { profileMenuExpanded = true },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = recipientName,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Icon(
                                        Icons.Rounded.ArrowDropDown,
                                        contentDescription = "Вибір підопічного",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
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
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "$recipientName$roleBadge",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activePlan != null) {
                            IconButton(
                                onClick = { showEmergencyDialog = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = "Екстрений зв'язок",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
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
            if (activeTab == CaregiverTab.SCHEDULE && activePlan != null && accessLevel == CaregiverAccessLevel.PRIMARY) {
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
                        activeCaregiverRole = myCaregiverRole,
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
                                        note = CareCloseWording.caregiverOverrideNote(myCaregiverRole, myCaregiverName, task.task.type),
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
                        isReadOnly = (accessLevel == CaregiverAccessLevel.HELPER),
                        onAddTask = {
                            editingTask = null
                            showTaskDialog = true
                        },
                        onAddOneOff = {
                            showOneOffDialog = true
                        },
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
                            val anchor = plan.anchors.firstOrNull { it.id == anchorId }
                            if (anchor != null) {
                                val linked = plan.tasksLinkedToAnchor(anchorId)
                                if (linked.isNotEmpty()) {
                                    anchorBlockedDeleteInfo = anchor to linked
                                } else {
                                    anchorConfirmDelete = anchor
                                }
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
                        plan = plan,
                        planVersion = plan.planVersion,
                        currentQuickReturnMode = plan.quickReturnMode,
                        currentButtonSize = plan.floatingButtonSize,
                        currentElderTheme = plan.elderThemeMode,
                        localThemeMode = localThemeMode,
                        accessLevel = accessLevel,
                        onOpenOnboarding = onOpenOnboardingSettings,
                        onOpenShareHelperDialog = { showShareHelperDialog = true },
                        onUpdateContact = { contact ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                emergencyContact = contact,
                                caregiverPhone = contact.phone,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка збереження контакту: ${it.message}" }
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
                        onUpdateQuickReturnMode = { newMode ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                quickReturnMode = newMode,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onSuccess { statusBanner = "Режим швидкого повернення оновлено: ${newMode.title}" }
                                    .onFailure { statusBanner = "Помилка оновлення: ${it.message}" }
                            }
                        },
                        onUpdateFloatingButtonSize = { newSize ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                floatingButtonSize = newSize,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onSuccess { statusBanner = "Розмір плаваючої кнопки оновлено: ${newSize.title}" }
                                    .onFailure { statusBanner = "Помилка оновлення розміру кнопки: ${it.message}" }
                            }
                        },
                        onUpdateElderTheme = { newTheme ->
                            val updated = plan.copy(
                                planVersion = plan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                elderThemeMode = newTheme,
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onSuccess { statusBanner = "Тему на телефоні підопічного оновлено: ${newTheme.title}" }
                                    .onFailure { statusBanner = "Помилка оновлення теми: ${it.message}" }
                            }
                        },
                        onUpdateLocalTheme = { newTheme ->
                            deviceConfigStore.setThemeMode(newTheme)
                            localThemeMode = newTheme
                            onThemeChanged(newTheme)
                        },
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
            onSave = { savedTasks, uploads ->
                showTaskDialog = false
                var newTasks = currentPlan.tasks
                for (savedTask in savedTasks) {
                    val exists = newTasks.any { it.id == savedTask.id }
                    newTasks = if (exists) {
                        newTasks.map { if (it.id == savedTask.id) savedTask else it }
                    } else {
                        newTasks + savedTask
                    }
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

    if (showOneOffDialog && currentPlan != null) {
        OneOffTaskDialog(
            initialDate = selectedDate,
            onDismiss = { showOneOffDialog = false },
            onSave = { oneOffTask ->
                showOneOffDialog = false
                val updatedPlan = currentPlan.copy(
                    planVersion = currentPlan.planVersion + 1L,
                    effectiveFromUtc = Instant.now().toString(),
                    tasks = currentPlan.tasks + oneOffTask,
                )
                scope.launch {
                    runCatching { publishUpdatedPlan(updatedPlan) }
                        .onSuccess { statusBanner = "Одноразове нагадування додано" }
                        .onFailure { err -> statusBanner = "Помилка збереження нагадування: ${err.message}" }
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

    anchorBlockedDeleteInfo?.let { (blockedAnchor, linkedTasks) ->
        AlertDialog(
            onDismissRequest = { anchorBlockedDeleteInfo = null },
            title = {
                Text("Неможливо видалити якір", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "До якоря «${blockedAnchor.title}» прив'язано ${linkedTasks.size} ${if (linkedTasks.size == 1) "завдання" else "завдань"}:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            linkedTasks.forEach { task ->
                                Text("• ${task.title}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(
                        text = "Щоб видалити цей якір, спочатку змініть прив'язку цих справ до іншого часу або видаліть їх.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { anchorBlockedDeleteInfo = null }) {
                    Text("Зрозуміло")
                }
            },
        )
    }

    anchorConfirmDelete?.let { targetAnchor ->
        AlertDialog(
            onDismissRequest = { anchorConfirmDelete = null },
            title = { Text("Видалити якір «${targetAnchor.title}»?", fontWeight = FontWeight.Bold) },
            text = {
                Text("До цього якоря не прив'язано жодних справ. Видалити його з розкладу?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDeleteId = targetAnchor.id
                        anchorConfirmDelete = null
                        if (currentPlan != null) {
                            val updated = currentPlan.copy(
                                planVersion = currentPlan.planVersion + 1L,
                                effectiveFromUtc = Instant.now().toString(),
                                anchors = currentPlan.anchors.filterNot { it.id == toDeleteId },
                            )
                            scope.launch {
                                runCatching { publishUpdatedPlan(updated) }
                                    .onFailure { statusBanner = "Помилка видалення якоря: ${it.message}" }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Видалити")
                }
            },
            dismissButton = {
                TextButton(onClick = { anchorConfirmDelete = null }) {
                    Text("Скасувати")
                }
            },
        )
    }

    val pairing = currentProfile
    if (showShareHelperDialog && pairing != null) {
        ShareHelperDialog(
            pairing = pairing,
            onDismiss = { showShareHelperDialog = false },
        )
    }

    if (showEmergencyDialog && currentPlan != null) {
        EmergencyConnectionDialog(
            plan = currentPlan,
            allEvents = allEvents,
            onDismiss = { showEmergencyDialog = false },
            onTriggerSiren = {
                scope.launch {
                    val event = CareEvent(
                        eventId = java.util.UUID.randomUUID().toString(),
                        deviceId = deviceConfigStore.getDeviceId(),
                        actorRole = Role.CAREGIVER,
                        logicalDate = selectedDate.toString(),
                        planVersion = currentPlan.planVersion,
                        taskId = "emergency_siren",
                        type = CareEventType.EMERGENCY_PING,
                        occurredAtUtc = Instant.now().toString(),
                        note = "Caregiver emergency ping",
                    )
                    repository.recordLocalEvent(event)
                    onLocalEventRecorded()
                    statusBanner = "Екстрений сигнал сирени надіслано на пристрій підопічного"
                }
            },
            onStopSiren = {
                scope.launch {
                    val event = CareEvent(
                        eventId = java.util.UUID.randomUUID().toString(),
                        deviceId = deviceConfigStore.getDeviceId(),
                        actorRole = Role.CAREGIVER,
                        logicalDate = selectedDate.toString(),
                        planVersion = currentPlan.planVersion,
                        taskId = "emergency_siren",
                        type = CareEventType.EMERGENCY_PING_DISMISSED,
                        occurredAtUtc = Instant.now().toString(),
                        note = "Caregiver stopped siren",
                    )
                    repository.recordLocalEvent(event)
                    onLocalEventRecorded()
                    statusBanner = "Сирену зупинено"
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
    activeCaregiverRole: CaregiverRole = plan.caregiverRole,
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
                activeCaregiverRole = activeCaregiverRole,
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasWarnings) Color(0xFFFFF8E1) else Color(0xFFF1F8E9),
        ),
        border = BorderStroke(1.dp, if (hasWarnings) Color(0xFFFFCC80) else Color(0xFFC8E6C9)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val statusTitle = CareCloseWording.onlinePresenceStatus(
                plan = plan,
                isOnline = health.isOnline,
                minutesSinceLastSeen = health.minutesSinceLastSeen,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B5E20),
                )
            }

            if (health.batteryPercent != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chargeLabel = if (health.isCharging == true) " ⚡" else ""
                    Surface(
                        color = Color.White.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Батарея", style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555))
                            Text(
                                text = "${health.batteryPercent}%$chargeLabel",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Гучність", style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555))
                            Text(
                                text = "${health.alarmVolumePercent ?: "?"}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            health.warnings.forEach { warning ->
                val text = when (warning) {
                    PresenceWarning.DEVICE_OFFLINE -> "⚠️ Пристрій давно не передавав статус"
                    PresenceWarning.LOW_BATTERY_NOT_CHARGING -> "⚠️ Низький заряд батареї, не на зарядці"
                    PresenceWarning.ALARM_VOLUME_LOW -> "⚠️ Гучність будильника занадто тиха!"
                    PresenceWarning.DO_NOT_DISTURB_ACTIVE -> "⚠️ Увімкнено режим «Не турбувати»"
                    PresenceWarning.OUTDATED_PLAN_VERSION -> "ℹ️ Ще не завантажено новий розклад"
                }
                Text(text = text, color = Color(0xFFD84315), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CaregiverTimelineTaskCard(
    plan: PlanSnapshot,
    item: ResolvedDailyTask,
    timeFormatter: DateTimeFormatter,
    activeCaregiverRole: CaregiverRole = plan.caregiverRole,
    onOverrideTaken: () -> Unit,
    onUndoCompleted: () -> Unit,
) {
    val isMissingAnchor = item.isMissingAnchor
    val containerColor = when {
        isMissingAnchor -> Color(0xFFFFEBEE)
        item.status == ResolvedTaskStatus.COMPLETED -> Color(0xFFF1F8E9)
        item.status == ResolvedTaskStatus.MISSED_ONLINE -> Color(0xFFFFEBEE)
        item.status == ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> Color(0xFFFFF3E0)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            val timeStr = when {
                isMissingAnchor -> "⚠️ Якір відсутній"
                item.scheduledAt != null -> timeFormatter.format(item.scheduledAt)
                else -> "Після: ${item.anchorTitle ?: "події"}"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$timeStr · ${item.task.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isMissingAnchor -> Color(0xFFFFCDD2)
                        item.status == ResolvedTaskStatus.COMPLETED -> Color(0xFFC8E6C9)
                        item.status == ResolvedTaskStatus.MISSED_ONLINE -> Color(0xFFFFCDD2)
                        item.status == ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> Color(0xFFFFE0B2)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = statusLabelUk(item),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isMissingAnchor -> Color(0xFFB71C1C)
                            item.status == ResolvedTaskStatus.COMPLETED -> Color(0xFF1B5E20)
                            item.status == ResolvedTaskStatus.MISSED_ONLINE -> Color(0xFFB71C1C)
                            item.status == ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> Color(0xFFE65100)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            item.task.doseLabel?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF555555))
            }

            if (isMissingAnchor) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ Помилка: якір прив'язки видалено з розкладу! Перейдіть у вкладку «Розклад», щоб призначити новий якір або точний час.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB71C1C),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            if (item.status == ResolvedTaskStatus.COMPLETED) {
                val completionBadge = CareCloseWording.completedTaskBadge(
                    completedBy = item.completedBy,
                    completionNote = item.completionNote,
                    recipientName = CareCloseWording.recipientDisplayName(plan),
                    gender = plan.recipientRole.gender,
                    taskType = item.task.type,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = completionBadge,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1B5E20),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onUndoCompleted) {
                        Text("Скасувати", color = Color(0xFF555555))
                    }
                }
            } else {
                Button(
                    onClick = onOverrideTaken,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = CareCloseWording.caregiverOverrideButton(activeCaregiverRole, item.task.type),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorContent(
    plan: PlanSnapshot,
    isReadOnly: Boolean = false,
    onAddTask: () -> Unit,
    onAddOneOff: () -> Unit,
    onEditTask: (TaskRule) -> Unit,
    onDeleteTask: (TaskRule) -> Unit,
    onAddAnchor: () -> Unit,
    onDeleteAnchor: (String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isReadOnly) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Режим помічника", fontWeight = FontWeight.Bold)
                        Text(
                            text = "Розклад і дозування формуються головним опікуном. Ви можете переглядати графік та інструкції.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (!isReadOnly) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onAddTask,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Справа / ліки", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onAddOneOff,
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Одноразове", style = MaterialTheme.typography.labelLarge)
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
                if (!isReadOnly) {
                    TextButton(onClick = onAddAnchor) { Text("+ Додати якір") }
                }
            }
        }

        items(plan.anchors, key = { it.id }) { anchor ->
            val linkedTasks = plan.tasksLinkedToAnchor(anchor.id)
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
                        if (linkedTasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "🔗 Прив'язано справ: ${linkedTasks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (!isReadOnly) {
                        IconButton(onClick = { onDeleteAnchor(anchor.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Видалити")
                        }
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
            val isMissingAnchor = task.isMissingAnchor(plan.anchors)
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = if (isMissingAnchor) BorderStroke(1.5.dp, Color(0xFFD32F2F)) else null,
                colors = CardDefaults.cardColors(
                    containerColor = if (isMissingAnchor) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface,
                ),
            ) {
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
                                val anchor = plan.anchors.firstOrNull { it.id == rule.anchorId }
                                if (anchor != null) {
                                    "Через ${rule.offsetMinutes} хв після «${anchor.title}»"
                                } else {
                                    "⚠️ Якір «${rule.anchorId}» видалено!"
                                }
                            }
                            is ScheduleRule.OneOff -> "Одноразово: ${rule.date} о ${rule.time}"
                            is ScheduleRule.EveryNDays -> "Кожні ${rule.intervalDays} дн. о ${rule.time} (з ${rule.startDate})"
                        }
                        val badges = buildList {
                            add(ruleDesc)
                            if (task.criticality == Criticality.CRITICAL) add("Критично")
                            if (task.photoMediaId != null) add("📷")
                            if (task.voiceMediaId != null) add("🎙")
                        }.joinToString(" · ")
                        Text(
                            text = badges,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isMissingAnchor) Color(0xFFB71C1C) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isMissingAnchor) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (isMissingAnchor) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Натисніть олівець, щоб призначити дійсний якір або фіксований час",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB71C1C),
                            )
                        }
                    }
                    if (!isReadOnly) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaregiverSettingsContent(
    syncConfigStore: SyncConfigStore,
    plan: PlanSnapshot,
    planVersion: Long,
    currentQuickReturnMode: ElderQuickReturnMode = ElderQuickReturnMode.BOTH,
    currentButtonSize: FloatingButtonSize = FloatingButtonSize.LARGE,
    currentElderTheme: AppThemeMode = AppThemeMode.SYSTEM,
    localThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    accessLevel: CaregiverAccessLevel = CaregiverAccessLevel.PRIMARY,
    onOpenOnboarding: () -> Unit,
    onOpenShareHelperDialog: () -> Unit = {},
    onUpdateContact: (EmergencyContact) -> Unit = {},
    onUpdateRoles: (RecipientRole, CaregiverRole, String?) -> Unit = { _, _, _ -> },
    onUpdateQuickReturnMode: (ElderQuickReturnMode) -> Unit = {},
    onUpdateFloatingButtonSize: (FloatingButtonSize) -> Unit = {},
    onUpdateElderTheme: (AppThemeMode) -> Unit = {},
    onUpdateLocalTheme: (AppThemeMode) -> Unit = {},
    onPublishCurrentPlan: () -> Unit,
    onTestMissedCheck: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = remember { syncConfigStore.getPairingPayload() }

    var selectedRecipientRole by remember(plan.recipientRole) { mutableStateOf(plan.recipientRole) }
    var selectedCaregiverRole by remember(plan.caregiverRole) { mutableStateOf(plan.caregiverRole) }
    var recipientCustomName by remember(plan.recipientName) { mutableStateOf(plan.recipientName ?: "") }

    var contactName by remember(plan.emergencyContact?.name) {
        mutableStateOf(plan.emergencyContact?.name ?: "Опікун")
    }
    var contactPhone by remember(plan.emergencyContact?.phone) {
        mutableStateOf(plan.emergencyContact?.phone ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (accessLevel == CaregiverAccessLevel.HELPER) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Режим допоміжного опікуна", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Ви маєте безпечний доступ до перегляду розкладу та відмітки виконання («Я поруч / Допомогла / Дала ліки»). Зміна плану та налаштувань заблокована.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (accessLevel == CaregiverAccessLevel.PRIMARY) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Кнопка швидкого дзвінка у підопічного", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (accessLevel == CaregiverAccessLevel.HELPER) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(contactName, fontWeight = FontWeight.SemiBold)
                            Text(contactPhone, style = MaterialTheme.typography.bodySmall)
                        }
                        if (contactPhone.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contactPhone.trim()}")))
                                    }
                                },
                            ) {
                                Icon(Icons.Rounded.Call, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Зателефонувати")
                            }
                        }
                    }
                } else {
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
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Зберегти контакт")
                    }
                }
            }
        }

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

        if (accessLevel == CaregiverAccessLevel.PRIMARY) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Швидке повернення для підопічного",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Як підопічному повертатися в CareClose, якщо він згорнув додаток або перейшов у YouTube, дзвінки чи браузер:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ElderQuickReturnMode.entries.forEach { mode ->
                            FilterChip(
                                selected = (currentQuickReturnMode == mode),
                                onClick = { onUpdateQuickReturnMode(mode) },
                                label = { Text(mode.title) },
                            )
                        }
                    }
                    Text(
                        text = when (currentQuickReturnMode) {
                            ElderQuickReturnMode.BOTH -> "Активні плаваюча серцева кнопка поверх екрану та закріплене сповіщення у верхній шторці Android."
                            ElderQuickReturnMode.FLOATING_BUBBLE_ONLY -> "Плаваюча серцева кнопка поверх усіх додатків. Дозволяє повернутися в один дотик. Потребує системного дозволу на накладання."
                            ElderQuickReturnMode.ONGOING_NOTIFICATION_ONLY -> "Закріплене сповіщення у верхній шторці Android з інформацією про наступний прийом ліків."
                            ElderQuickReturnMode.DISABLED -> "Вимкнено. Підопічний запускає CareClose звичайним значком з головного екрану."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (currentQuickReturnMode.isBubbleEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Розмір плаваючої кнопки на екрані:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FloatingButtonSize.entries.forEach { size ->
                                FilterChip(
                                    selected = (currentButtonSize == size),
                                    onClick = { onUpdateFloatingButtonSize(size) },
                                    label = { Text(size.title) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Оформлення та кольорова тема",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Тема оформлення опікуна (на цьому пристрої):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = localThemeMode == mode,
                            onClick = { onUpdateLocalTheme(mode) },
                            label = { Text(mode.title) },
                        )
                    }
                }

                if (accessLevel == CaregiverAccessLevel.PRIMARY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Тема на телефоні підопічного (передається в плані):",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = currentElderTheme == mode,
                                onClick = { onUpdateElderTheme(mode) },
                                label = { Text(mode.title) },
                            )
                        }
                    }
                    Text(
                        text = "💡 Контрастна тема використовує суцільний чорний фон та яскраві неонові акценти без блідих півтонів — це значно спрощує сприйняття для людей із віковими вадами зору або катарактою.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (accessLevel == CaregiverAccessLevel.PRIMARY && pairing != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Спільний догляд (Помічники)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Надайте доступ сиділці, медсестрі або сусідам. Вони бачитимуть сьогоднішні справи та зможуть відмічати виконання без права змінювати схему лікування.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onOpenShareHelperDialog,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Поділитися з помічником (QR-код)")
                    }
                }
            }
        }

        if (accessLevel == CaregiverAccessLevel.PRIMARY && pairing != null) {
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
            Text(if (accessLevel == CaregiverAccessLevel.PRIMARY) "Налаштувати Drive / показати QR-код / змінити роль" else "Змінити профіль або роль")
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
private fun ShareHelperDialog(
    pairing: PairingQrPayload,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val helperPayload = remember(pairing) {
        pairing.copy(accessLevel = CaregiverAccessLevel.HELPER)
    }
    val qrString = remember(helperPayload) { PairingPayloadCodec.encode(helperPayload) }
    val qrBitmap = remember(qrString) { QrBitmapGenerator.generate(qrString, 480) }
    var isCopied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доступ для помічника", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Покажіть цей QR-код сиділці, медсестрі чи сусідам. Вони бачитимуть розклад та зможуть відмічати виконання, без можливості змінити графік.",
                    style = MaterialTheme.typography.bodySmall,
                )
                qrBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR-код для помічника",
                        modifier = Modifier.size(220.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("CareClose Helper Code", qrString))
                        isCopied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isCopied) "✓ Скопійовано в буфер!" else "Скопіювати текстовий код")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрити")
            }
        },
    )
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
    if (item.isMissingAnchor) return "⚠️ Помилка якоря"
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

@Composable
private fun EmergencyConnectionDialog(
    plan: PlanSnapshot,
    allEvents: List<CareEvent>,
    onDismiss: () -> Unit,
    onTriggerSiren: () -> Unit,
    onStopSiren: () -> Unit,
) {
    val context = LocalContext.current
    val contact = plan.emergencyContact
    val elderPhone = contact?.phone?.takeIf { it.isNotBlank() }
    val elderName = CareCloseWording.recipientDisplayName(plan)

    val lastPing = remember(allEvents) {
        allEvents.filter { it.type == CareEventType.EMERGENCY_PING }
            .maxByOrNull { it.occurredAtUtc }
    }
    val isDismissed = remember(allEvents, lastPing) {
        if (lastPing == null) true
        else allEvents.any {
            it.type == CareEventType.EMERGENCY_PING_DISMISSED &&
                it.occurredAtUtc >= lastPing.occurredAtUtc
        }
    }
    val isSirenActive = lastPing != null && !isDismissed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚨 Екстрений зв'язок")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Зв'язок із: $elderName",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("1. Прямий мобільний дзвінок", fontWeight = FontWeight.Bold)
                        Text(
                            "Якщо ваш номер додано в «Обрані контакти» у телефоні підопічного, виклик пробиває режим «Не турбувати» миттєво і без інтернету.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (elderPhone != null) {
                            Button(
                                onClick = {
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${elderPhone.trim()}"))
                                    context.startActivity(dialIntent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                            ) {
                                Icon(Icons.Rounded.Call, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Подзвонити на $elderPhone")
                            }
                        } else {
                            Text(
                                "Номер підопічного не вказано в налаштуваннях.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSirenActive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "2. Пошукова сирена CareClose",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Вмикає на телефоні підопічного звук тривоги на 100% гучності поверх беззвучного режиму та запалює екран.",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        if (isSirenActive) {
                            val time = lastPing?.occurredAtUtc?.let {
                                runCatching {
                                    val instant = Instant.parse(it)
                                    val lt = instant.atZone(plan.safeZoneId).toLocalTime()
                                    String.format("%02d:%02d", lt.hour, lt.minute)
                                }.getOrNull()
                            } ?: ""
                            Text(
                                text = "⚠️ Сирену активовано о $time. Очікуємо відповіді підопічного...",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = onStopSiren,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Зупинити сирену")
                            }
                        } else {
                            Button(
                                onClick = onTriggerSiren,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            ) {
                                Text("Увімкнути сирену на телефоні")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрити")
            }
        },
    )
}
