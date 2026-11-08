package com.careclose.core.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.careclose.core.sync.SyncConfigStore
import com.careclose.core.sync.SyncEngine
import com.careclose.core.sync.SyncException
import com.careclose.core.sync.telemetry.AndroidPresenceCollector
import java.util.concurrent.TimeUnit

interface SyncDependenciesProvider {
    val syncConfigStore: SyncConfigStore
    val presenceCollector: AndroidPresenceCollector
    fun getOrCreateSyncEngine(): SyncEngine?
    suspend fun onSyncPullCompleted()
}

object SyncScheduler {

    private const val PERIODIC_ELDER_SYNC = "careclose_periodic_elder_sync"
    private const val IMMEDIATE_SYNC = "careclose_immediate_sync"

    fun enqueueImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<CareCloseSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SYNC,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensurePeriodicElderSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<CareCloseSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_ELDER_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodicElderSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_ELDER_SYNC)
    }
}

class CareCloseSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val provider = applicationContext as? SyncDependenciesProvider ?: return Result.success()
        val engine = provider.getOrCreateSyncEngine() ?: return Result.success()

        return try {
            val presence = provider.presenceCollector.collectNow()
            val report = engine.syncOnce(presenceSnapshot = presence)
            if (report.updatedPlanVersion != null || report.importedEventsCount > 0) {
                provider.onSyncPullCompleted()
            }
            Result.success()
        } catch (e: SyncException) {
            if (e.isAuthError) Result.failure() else Result.retry()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
