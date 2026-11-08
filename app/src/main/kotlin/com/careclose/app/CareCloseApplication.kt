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
import com.careclose.core.database.repository.RoomCareCloseRepository
import com.careclose.core.model.Role
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
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CareCloseApplication : Application(), AlarmDependenciesProvider, SyncDependenciesProvider {

    val database: CareCloseDatabase by lazy {
        CareCloseDatabase.build(this)
    }

    override val careCloseRepository: CareCloseRepository by lazy {
        RoomCareCloseRepository(database)
    }

    override val deviceConfigStore: DeviceConfigStore by lazy {
        SharedPrefsDeviceConfigStore(this)
    }

    override val alarmCoordinator: AlarmCoordinator by lazy {
        AlarmCoordinator(
            context = this,
            repository = careCloseRepository,
            deviceConfigStore = deviceConfigStore,
        )
    }

    private val androidSyncStore: AndroidSyncConfigStore by lazy {
        AndroidSyncConfigStore(this)
    }

    override val syncConfigStore: SyncConfigStore
        get() = androidSyncStore

    override val presenceCollector: AndroidPresenceCollector by lazy {
        AndroidPresenceCollector(
            context = this,
            repository = careCloseRepository,
            deviceConfigStore = deviceConfigStore,
        )
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
    }

    override fun onLocalEventRecorded() {
        ElderGlanceWidget.requestUpdateAsync(this)
        if (androidSyncStore.isPaired()) {
            SyncScheduler.enqueueImmediateSync(this)
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
            val today = LocalDate.now(ZoneId.of(plan.timezone))
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
                            title = "Немає підтвердження: ${task.task.title}",
                            body = "Дедлайн минув, але телефон підопічного був онлайн. Варто зателефонувати.",
                        )
                    }

                    ResolvedTaskStatus.MISSED_DEVICE_OFFLINE -> {
                        AlarmNotificationHelper.postCaregiverAlert(
                            context = this,
                            notificationId = task.task.id.hashCode(),
                            title = "Немає зв'язку та підтвердження: ${task.task.title}",
                            body = "Телефон підопічного не виходив на зв'язок перед дедлайном.",
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
    }

    override fun createMainActivityIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}
