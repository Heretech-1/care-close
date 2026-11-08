package com.careclose.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.careclose.app.ui.theme.CareCloseTheme
import com.careclose.core.model.AnchorRule
import com.careclose.core.model.CaregiverAccessLevel
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.Criticality
import com.careclose.core.model.EmergencyContact
import com.careclose.core.model.EscalationPolicy
import com.careclose.core.model.PillInventory
import com.careclose.core.model.PlanSnapshot
import com.careclose.core.model.RecipientRole
import com.careclose.core.model.Role
import com.careclose.core.model.ScheduleRule
import com.careclose.core.model.TaskRule
import com.careclose.core.model.TaskType
import com.careclose.core.sync.AndroidSyncConfigStore
import com.careclose.core.sync.SyncEngine
import com.careclose.core.sync.crypto.FamilyCrypto
import com.careclose.core.sync.drive.GoogleDriveServiceAccountTransport
import com.careclose.core.sync.worker.SyncScheduler
import com.careclose.feature.caregiver.CaregiverDashboardScreen
import com.careclose.feature.elder.ElderDashboardScreen
import com.careclose.feature.onboarding.ElderPermissionsChecklist
import com.careclose.feature.onboarding.OnboardingScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CareCloseApplication

        setContent {
            CareCloseTheme {
                CareCloseShell(app = app)
            }
        }
    }
}

@Composable
private fun CareCloseShell(app: CareCloseApplication) {
    var activeProfileId by remember { mutableStateOf(app.syncConfigStore.getActiveProfileId()) }
    val repository = remember(activeProfileId) { app.careCloseRepository }
    val deviceConfigStore = app.deviceConfigStore
    val alarmCoordinator = app.alarmCoordinator

    var activeRole by remember { mutableStateOf(deviceConfigStore.getRole()) }
    var showOnboarding by remember { mutableStateOf(activeRole == null) }
    var isAddingNewProfile by remember { mutableStateOf(false) }
    var showElderPermissions by remember { mutableStateOf(false) }
    var configuredRoles by remember { mutableStateOf<Triple<RecipientRole, CaregiverRole, String?>?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(activeRole, showOnboarding, activeProfileId) {
        if (!showOnboarding && !isAddingNewProfile && activeRole != null && repository.getActivePlan() == null) {
            repository.saveActivePlan(
                createSamplePlan(
                    recipientRole = configuredRoles?.first ?: RecipientRole.MOTHER,
                    caregiverRole = configuredRoles?.second ?: CaregiverRole.DAUGHTER,
                    recipientName = configuredRoles?.third,
                ),
            )
            alarmCoordinator.rescheduleForCurrentRole()
        }
    }

    when {
        isAddingNewProfile -> {
            Scaffold { innerPadding ->
                OnboardingScreen(
                    syncConfigStore = app.syncConfigStore,
                    isAddingNewProfile = true,
                    onCreateSyncEngineForPayload = { payload ->
                        app.invalidateSyncEngine()
                        SyncEngine(
                            transport = GoogleDriveServiceAccountTransport(payload.serviceAccountJson),
                            repository = app.getRepositoryForProfile(payload.familyId),
                            crypto = FamilyCrypto(payload.familyAesKeyBase64),
                            checkpointStore = AndroidSyncConfigStore(app),
                            rootFolderId = payload.folderId,
                            deviceId = deviceConfigStore.getDeviceId(),
                        )
                    },
                    onRoleSelected = {},
                    onProfileConfigured = { recRole, cgRole, famName ->
                        configuredRoles = Triple(recRole, cgRole, famName)
                        deviceConfigStore.setCaregiverAccessLevel(CaregiverAccessLevel.PRIMARY)
                        deviceConfigStore.setCaregiverRole(cgRole)
                    },
                    onHelperConfigured = { helperRole, helperName ->
                        deviceConfigStore.setCaregiverAccessLevel(CaregiverAccessLevel.HELPER)
                        deviceConfigStore.setCaregiverRole(helperRole)
                        deviceConfigStore.setCaregiverName(helperName)
                    },
                    onFinishOnboarding = {
                        isAddingNewProfile = false
                        val newId = app.syncConfigStore.getActiveProfileId() ?: ""
                        activeProfileId = newId
                        app.switchProfile(newId)
                        scope.launch {
                            withTimeoutOrNull(4_000L) {
                                withContext(Dispatchers.IO) {
                                    runCatching { app.getOrCreateSyncEngine()?.pullAll() }
                                }
                            }
                            if (app.careCloseRepository.getActivePlan() == null) {
                                app.careCloseRepository.saveActivePlan(
                                    createSamplePlan(
                                        recipientRole = configuredRoles?.first ?: RecipientRole.MOTHER,
                                        caregiverRole = configuredRoles?.second ?: CaregiverRole.DAUGHTER,
                                        recipientName = configuredRoles?.third,
                                    ),
                                )
                            }
                            alarmCoordinator.rescheduleForCurrentRole()
                        }
                    },
                    onCancelAddingProfile = {
                        isAddingNewProfile = false
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        showOnboarding || activeRole == null -> {
            Scaffold { innerPadding ->
                OnboardingScreen(
                    syncConfigStore = app.syncConfigStore,
                    onCreateSyncEngineForPayload = { payload ->
                        app.invalidateSyncEngine()
                        SyncEngine(
                            transport = GoogleDriveServiceAccountTransport(payload.serviceAccountJson),
                            repository = repository,
                            crypto = FamilyCrypto(payload.familyAesKeyBase64),
                            checkpointStore = AndroidSyncConfigStore(app),
                            rootFolderId = payload.folderId,
                            deviceId = deviceConfigStore.getDeviceId(),
                        )
                    },
                    onRoleSelected = { chosen ->
                        activeRole = chosen
                        deviceConfigStore.setRole(chosen)
                    },
                    onProfileConfigured = { recRole, cgRole, famName ->
                        configuredRoles = Triple(recRole, cgRole, famName)
                        deviceConfigStore.setCaregiverAccessLevel(CaregiverAccessLevel.PRIMARY)
                        deviceConfigStore.setCaregiverRole(cgRole)
                    },
                    onHelperConfigured = { helperRole, helperName ->
                        deviceConfigStore.setCaregiverAccessLevel(CaregiverAccessLevel.HELPER)
                        deviceConfigStore.setCaregiverRole(helperRole)
                        deviceConfigStore.setCaregiverName(helperName)
                    },
                    onFinishOnboarding = { finishedRole ->
                        activeRole = finishedRole
                        deviceConfigStore.setRole(finishedRole)
                        activeProfileId = app.syncConfigStore.getActiveProfileId()
                        app.invalidateSyncEngine()
                        if (finishedRole == Role.ELDER && app.syncConfigStore.isPaired()) {
                            SyncScheduler.ensurePeriodicElderSync(app)
                        } else {
                            SyncScheduler.cancelPeriodicElderSync(app)
                        }
                        scope.launch {
                            if (finishedRole == Role.CAREGIVER && app.syncConfigStore.isPaired()) {
                                withTimeoutOrNull(4_000L) {
                                    withContext(Dispatchers.IO) {
                                        runCatching { app.getOrCreateSyncEngine()?.pullAll() }
                                    }
                                }
                            }
                            if (repository.getActivePlan() == null) {
                                repository.saveActivePlan(
                                    createSamplePlan(
                                        recipientRole = configuredRoles?.first ?: RecipientRole.MOTHER,
                                        caregiverRole = configuredRoles?.second ?: CaregiverRole.DAUGHTER,
                                        recipientName = configuredRoles?.third,
                                    ),
                                )
                            }
                            alarmCoordinator.rescheduleForCurrentRole()
                            showOnboarding = false
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        activeRole == Role.ELDER -> {
            Scaffold { innerPadding ->
                if (showElderPermissions) {
                    ElderPermissionsChecklist(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        onDone = { showElderPermissions = false },
                    )
                } else {
                    ElderDashboardScreen(
                        repository = repository,
                        deviceConfigStore = deviceConfigStore,
                        syncEngineProvider = { app.getOrCreateSyncEngine() },
                        onEventRecorded = {
                            app.onLocalEventRecorded()
                            alarmCoordinator.rescheduleForCurrentRole()
                        },
                        onOpenPermissionsSetup = { showElderPermissions = true },
                        onOpenServiceSettings = { showOnboarding = true },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }

        else -> {
            key(activeProfileId) {
                CaregiverDashboardScreen(
                    repository = repository,
                    deviceConfigStore = deviceConfigStore,
                    syncConfigStore = app.syncConfigStore,
                    syncEngineProvider = { app.getOrCreateSyncEngine() },
                    onLocalEventRecorded = {
                        app.onLocalEventRecorded()
                        alarmCoordinator.rescheduleForCurrentRole()
                    },
                    onScheduleOrSyncUpdated = {
                        app.onSyncPullCompleted()
                    },
                    onRunTestMissedCheck = {
                        app.caregiverCheckHandler.runPredictiveCheck()
                    },
                    onOpenOnboardingSettings = {
                        showOnboarding = true
                    },
                    onSwitchProfile = { profileId ->
                        app.switchProfile(profileId)
                        activeProfileId = profileId
                        scope.launch {
                            alarmCoordinator.rescheduleForCurrentRole()
                        }
                    },
                    onAddNewElderProfile = {
                        isAddingNewProfile = true
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun createSamplePlan(
    recipientRole: RecipientRole = RecipientRole.MOTHER,
    caregiverRole: CaregiverRole = CaregiverRole.DAUGHTER,
    recipientName: String? = null,
): PlanSnapshot {
    return PlanSnapshot(
        planVersion = 1L,
        effectiveFromUtc = Instant.now().toString(),
        timezone = ZoneId.systemDefault().id,
        recipientRole = recipientRole,
        recipientName = recipientName,
        caregiverRole = caregiverRole,
        emergencyContact = EmergencyContact(name = "Олексій", phone = "+380501234567"),
        anchors = listOf(
            AnchorRule(
                id = "anchor_breakfast",
                title = "Сніданок",
                windowStart = "07:30",
                windowEnd = "10:30",
                softReminderAt = "09:00",
            ),
            AnchorRule(
                id = "anchor_lunch",
                title = "Обід",
                windowStart = "12:30",
                windowEnd = "15:00",
                softReminderAt = "13:30",
            ),
        ),
        tasks = listOf(
            TaskRule(
                id = "med_morning_thyroxine",
                type = TaskType.MEDICATION,
                criticality = Criticality.CRITICAL,
                title = "Еутирокс 50 мкг",
                doseVisualCount = 1.0,
                doseLabel = "1 біла таблетка",
                instruction = "Натщесерце, запити водою",
                schedule = ScheduleRule.Fixed(time = "08:00"),
                escalation = EscalationPolicy(repeatIntervalMin = 10, maxRepeats = 2, caregiverDeadlineMin = 30),
                inventory = PillInventory(trackStock = true, remainingUnits = 28.0, lowThresholdUnits = 5.0),
            ),
            TaskRule(
                id = "med_after_breakfast",
                type = TaskType.MEDICATION,
                criticality = Criticality.CRITICAL,
                title = "Метформін",
                doseVisualCount = 1.0,
                doseLabel = "1 овальна таблетка",
                instruction = "Через 20 хв після сніданку",
                schedule = ScheduleRule.RelativeToAnchor(anchorId = "anchor_breakfast", offsetMinutes = 20),
                escalation = EscalationPolicy(repeatIntervalMin = 10, maxRepeats = 2, caregiverDeadlineMin = 30),
            ),
            TaskRule(
                id = "habit_afternoon_water",
                type = TaskType.ROUTINE,
                criticality = Criticality.SOFT,
                title = "Склянка теплої води",
                schedule = ScheduleRule.RelativeToAnchor(anchorId = "anchor_lunch", offsetMinutes = 60),
            ),
        ),
    )
}
