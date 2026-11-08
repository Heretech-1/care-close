package com.careclose.core.alarms.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.careclose.core.alarms.AlarmExtras
import com.careclose.core.alarms.AlarmKind
import com.careclose.core.alarms.AlarmPayload
import com.careclose.core.alarms.audio.ElderAlarmAudioPlayer
import com.careclose.core.alarms.notification.AlarmNotificationHelper
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.Criticality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmRingingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioPlayer: ElderAlarmAudioPlayer
    private var autoSilenceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        audioPlayer = ElderAlarmAudioPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AlarmExtras.ACTION_STOP_RINGING) {
            stopAndCleanup()
            return START_NOT_STICKY
        }

        val payloadJson = intent?.getStringExtra(AlarmExtras.EXTRA_PAYLOAD_JSON)
        val payload = payloadJson?.let {
            runCatching {
                CareCloseJson.instance.decodeFromString(AlarmPayload.serializer(), it)
            }.getOrNull()
        }

        if (payload == null) {
            stopAndCleanup()
            return START_NOT_STICKY
        }

        val notification = AlarmNotificationHelper.buildElderAlarmNotification(this, payload)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                AlarmNotificationHelper.ELDER_ALARM_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(AlarmNotificationHelper.ELDER_ALARM_NOTIFICATION_ID, notification)
        }

        val isEmergency = payload.kind == AlarmKind.EMERGENCY_CALL_PING
        audioPlayer.start(
            voiceMediaPath = payload.voiceMediaPath,
            vibrate = payload.criticality == Criticality.CRITICAL || isEmergency,
            isEmergency = isEmergency,
        )

        autoSilenceJob?.cancel()
        autoSilenceJob = serviceScope.launch {
            val silenceTimeout = if (isEmergency) 3 * 60 * 1000L else AUTO_SILENCE_MS
            delay(silenceTimeout)
            stopAndCleanup()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        autoSilenceJob?.cancel()
        audioPlayer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAndCleanup() {
        autoSilenceJob?.cancel()
        audioPlayer.stop()
        AlarmNotificationHelper.cancelElderAlarmNotification(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val AUTO_SILENCE_MS = 90_000L

        fun start(context: Context, payload: AlarmPayload) {
            val payloadJson = CareCloseJson.instance.encodeToString(AlarmPayload.serializer(), payload)
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                putExtra(AlarmExtras.EXTRA_PAYLOAD_JSON, payloadJson)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = AlarmExtras.ACTION_STOP_RINGING
            }
            runCatching { context.startService(intent) }
                .onFailure { AlarmNotificationHelper.cancelElderAlarmNotification(context) }
        }
    }
}

