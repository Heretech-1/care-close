package com.careclose.app

import android.app.Application
import android.content.Context
import android.content.Intent
import com.careclose.core.alarms.AlarmDependenciesProvider
import com.careclose.core.alarms.CaregiverCheckHandler
import com.careclose.core.alarms.notification.AlarmNotificationHelper
import com.careclose.core.alarms.scheduler.AlarmCoordinator
import com.careclose.core.database.CareCloseDatabase
import com.careclose.core.database.preferences.DeviceConfigStore
import com.careclose.core.database.preferences.SharedPrefsDeviceConfigStore
import com.careclose.core.database.repository.CareCloseRepository
import com.careclose.core.database.repository.DelegatingCareCloseRepository
import com.careclose.core.database.repository.RoomCareCloseRepository
import com.careclose.core.model.Role
import com.careclose.core.model.engine.CareCloseWording
import com.careclose.core.model.engine.DailyScheduleResolver
import com.careclose.core.model.engine.ResolvedTaskStatus
import com.careclose.core.sync.AndroidSyncConfigStore
import com.careclose.core.sync.SyncConfigStore
import com.careclose.core.sync.SyncEngine
import com.careclose.core.sync.crypto.FamilyCrypto
import com.careclose.core.sync.drive.GoogleDriveServiceAccountTransport
import com.careclose.core.sync.telemetry.AndroidPresenceCollector
import com.careclose.core.sync.worker.SyncDependenciesProvider
import com.careclose.core.sync.worker.SyncScheduler
import com.careclose.feature.elder.widget.ElderGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CareCloseApplication : Application(), AlarmDependenciesProvider, SyncDependenciesProvider {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = mutableMapOf<String, CareCloseDatabase>()
    private val repositories = mutableMapOf<String, CareCloseRepository>()

    fun getDatabaseForProfile(profileId: String?): CareCloseDatabase {
        val key = profileId?.takeIf { it.isNotBlank() } ?: "default"
        return synchronized(databases) {
            databases.getOrPut(key) {
                val dbName = if (key == "default") "careclose.db" else "careclose_$key.db"
                CareCloseDatabase.build(this, dbName)
            }
        }
    }

    fun getRepositoryForProfile(profileId: String?): CareCloseRepository {
        val key = profileId?.takeIf { it.isNotBlank() } ?: "default"
        return synchronized(repositories) {
            repositories.getOrPut(key) {
                RoomCareCloseRepository(getDatabaseForProfile(profileId))
            }
        }
    }

    override val careCloseRepository: CareCloseRepository
        get() = getRepositoryForProfile(androidSyncStore.getActiveProfileId())

    override val deviceConfigStore: DeviceConfigStore by lazy {
        SharedPrefsDeviceConfigStore(this)
    }

    override val alarmCoordinator: AlarmCoordinator by lazy {
        AlarmCoordinator(
            context = this,
            repository = DelegatingCareCloseRepository { careCloseRepository },
            deviceConfigStore = deviceConfigStore,
        )
    }

    private val androidSyncStore: AndroidSyncConfigStore by lazy {
        AndroidSyncConfigStore(this)
    }

    override val syncConfigStore: SyncConfigStore
        get() = androidSyncStore

    override val presenceCollector: AndroidPresenceCollector
        get() = AndroidPresenceCollector(
            context = this,
            repository = careCloseRepository,
            deviceConfigStore = deviceConfigStore,
        )

    fun switchProfile(profileId: String) {
        androidSyncStore.setActiveProfileId(profileId)
        invalidateSyncEngine()
    }

    @Volatile
    private var cachedSyncEngine: SyncEngine? = null

    override fun getOrCreateSyncEngine(): SyncEngine? {
        cachedSyncEngine?.let { return it }
        val pairing = androidSyncStore.getPairingPayload() ?: return null
        return synchronized(this) {
            cachedSyncEngine ?: SyncEngine(
                transport = GoogleDriveServiceAccountTransport(pairing.serviceAccountJson),
                repository = careCloseRepository,
                crypto = FamilyCrypto(pairing.familyAesKeyBase64),
                checkpointStore = androidSyncStore,
                rootFolderId = pairing.folderId,
                deviceId = deviceConfigStore.getDeviceId(),
            ).also { cachedSyncEngine = it }
        }
    }

    fun invalidateSyncEngine() {
        cachedSyncEngine = null
    }

    override suspend fun onSyncPullCompleted() {
        alarmCoordinator.rescheduleForCurrentRole()
        ElderGlanceWidget.requestUpdate(this)
        updateElderOngoingStatusNotification()
    }

    override fun onLocalEventRecorded() {
        ElderGlanceWidget.requestUpdateAsync(this)
        if (androidSyncStore.isPaired()) {
            SyncScheduler.enqueueImmediateSync(this)
        }
        appScope.launch {
            updateElderOngoingStatusNotification()
        }
    }

    override val caregiverCheckHandler: CaregiverCheckHandler by lazy {
        CaregiverCheckHandler {
            runCatching {
                val engine = getOrCreateSyncEngine()
                if (engine != null) {
                    val presence = presenceCollector.collectNow()
                    engine.syncOnce(presenceSnapshot = presence)
                }
            }

            val plan = careCloseRepository.getActivePlan() ?: return@CaregiverCheckHandler
            val today = LocalDate.now(plan.safeZoneId)
            val events = careCloseRepository.observeEventsForDate(today.toString()).first()
            val presence = careCloseRepository.observeLatestElderPresence().first()
            val timeline = DailyScheduleResolver.resolve(
                plan = plan,
                logicalDate = today,
                events = events,
                elderPresence = presence,
                now = Instant.now(),
            )

            timeline.tasks.forEach { task ->
                when (task.status) {
                    ResolvedTaskStatus.MISSED_ONLINE -> {
                        AlarmNotificationHelper.postCaregiverAlert(
                            context = this,
                            notificationId = task.task.id.hashCode(),
                            title = CareCloseWording.caregiverAlertTitleMissed(plan, task.task.title),
                            body = CareCloseWording.caregiverAlertBodyMissedOnline(plan),
                        )
                    }

                    ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> {
                        AlarmNotificationHelper.postCaregiverAlert(
                            context = this,
                            notificationId = task.task.id.hashCode(),
                            title = CareCloseWording.caregiverAlertTitleMissed(plan, task.task.title),
                            body = CareCloseWording.caregiverAlertBodyMissedOffline(plan),
                        )
                    }

                    else -> Unit
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AlarmNotificationHelper.ensureChannels(this)
        if (deviceConfigStore.getRole() == Role.ELDER && androidSyncStore.isPaired()) {
            SyncScheduler.ensurePeriodicElderSync(this)
        }
        appScope.launch {
            updateElderOngoingStatusNotification()
        }
    }

    override fun createMainActivityIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    fun updateElderOngoingStatusNotificationAsync() {
        appScope.launch {
            updateElderOngoingStatusNotification()
        }
    }

    suspend fun updateElderOngoingStatusNotification() {
        if (deviceConfigStore.getRole() != Role.ELDER) {
            AlarmNotificationHelper.cancelElderOngoingStatus(this)
            return
        }

        val plan = careCloseRepository.getActivePlan()
        if (plan == null || !plan.quickReturnMode.isNotificationEnabled) {
            AlarmNotificationHelper.cancelElderOngoingStatus(this)
            return
        }

        val today = LocalDate.now(plan.safeZoneId)
        val events = careCloseRepository.observeEventsForDate(today.toString()).first()
        val timeline = DailyScheduleResolver.resolve(
            plan = plan,
            logicalDate = today,
            events = events,
            elderPresence = null,
            now = Instant.now(),
        )

        val activeOrNext = timeline.tasks.firstOrNull {
            it.status == ResolvedTaskStatus.DUE_NOW ||
                it.status == ResolvedTaskStatus.PENDING ||
                it.status == ResolvedTaskStatus.SNOOZED ||
                it.status == ResolvedTaskStatus.WAITING_FOR_ANCHOR
        }

        val (title, text) = if (activeOrNext != null) {
            val task = activeOrNext.task
            val timePrefix = activeOrNext.scheduledAt?.let { instant ->
                val localTime = instant.atZone(plan.safeZoneId).toLocalTime()
                String.format("%02d:%02d • ", localTime.hour, localTime.minute)
            } ?: ""
            val doseInfo = task.doseLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            val recipient = CareCloseWording.recipientDisplayName(plan)
            Pair(
                "$timePrefix${task.title}",
                "${task.title}$doseInfo. $recipient, торкніться для повернення в CareClose.",
            )
        } else {
            Pair(
                "CareClose • Спокій та піклування",
                "Усі заплановані завдання на сьогодні виконано. Торкніться для перегляду.",
            )
        }

        val intent = createMainActivityIntent(this)
        AlarmNotificationHelper.postElderOngoingStatus(
            context = this,
            title = title,
            statusText = text,
            mainActivityIntent = intent,
        )
    }
}
