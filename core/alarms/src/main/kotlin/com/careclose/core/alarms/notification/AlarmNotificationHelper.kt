package com.careclose.core.alarms.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.careclose.core.alarms.AlarmExtras
import com.careclose.core.alarms.AlarmPayload
import com.careclose.core.alarms.receiver.AlarmActionReceiver
import com.careclose.core.alarms.ui.FullScreenAlarmActivity
import com.careclose.core.model.CareCloseJson

object AlarmNotificationHelper {

    const val ELDER_ALARM_CHANNEL_ID = "careclose_elder_alarms"
    const val CAREGIVER_ALERTS_CHANNEL_ID = "careclose_caregiver_alerts"
    const val ELDER_ONGOING_STATUS_CHANNEL_ID = "careclose_elder_ongoing_status"
    const val ELDER_ALARM_NOTIFICATION_ID = 4001
    const val ELDER_ONGOING_NOTIFICATION_ID = 4002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val elderChannel = NotificationChannel(
            ELDER_ALARM_CHANNEL_ID,
            "Нагадування про ліки та справи",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Повноекранні нагадування для підопічного"
            setSound(null, null) // Звуком керує ElderAlarmAudioPlayer через STREAM_ALARM
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val caregiverChannel = NotificationChannel(
            CAREGIVER_ALERTS_CHANNEL_ID,
            "Тривоги опікуна",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Повідомлення про пропущені критичні ліки або втрату зв'язку"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val ongoingChannel = NotificationChannel(
            ELDER_ONGOING_STATUS_CHANNEL_ID,
            "Швидке повернення та статус дня",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постійне закріплене сповіщення для швидкого повернення в CareClose"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(elderChannel)
        manager.createNotificationChannel(caregiverChannel)
        manager.createNotificationChannel(ongoingChannel)
    }

    fun buildElderAlarmNotification(
        context: Context,
        payload: AlarmPayload,
    ): Notification {
        ensureChannels(context)
        val payloadJson = CareCloseJson.instance.encodeToString(AlarmPayload.serializer(), payload)

        val fullScreenIntent = Intent(context, FullScreenAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmExtras.EXTRA_PAYLOAD_JSON, payloadJson)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            payload.targetId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val takenIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmExtras.ACTION_MARK_TAKEN
            putExtra(AlarmExtras.EXTRA_PAYLOAD_JSON, payloadJson)
        }
        val takenPendingIntent = PendingIntent.getBroadcast(
            context,
            payload.targetId.hashCode() + 1,
            takenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snoozeIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = AlarmExtras.ACTION_SNOOZE_10M
            putExtra(AlarmExtras.EXTRA_PAYLOAD_JSON, payloadJson)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            payload.targetId.hashCode() + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = listOfNotNull(payload.subtitle, payload.instruction)
            .joinToString(" · ")
            .ifBlank { "Час виконати заплановану подію" }

        return NotificationCompat.Builder(context, ELDER_ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(payload.title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "ПРИЙНЯТО", takenPendingIntent)
            .addAction(0, "+10 ХВ", snoozePendingIntent)
            .build()
    }

    fun postCaregiverAlert(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent? = null,
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CAREGIVER_ALERTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()

        manager.notify(notificationId, notification)
    }

    fun cancelElderAlarmNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(ELDER_ALARM_NOTIFICATION_ID)
    }

    fun postElderOngoingStatus(
        context: Context,
        title: String,
        statusText: String,
        mainActivityIntent: Intent,
    ) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val pendingIntent = PendingIntent.getActivity(
            context,
            ELDER_ONGOING_NOTIFICATION_ID,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ELDER_ONGOING_STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        manager.notify(ELDER_ONGOING_NOTIFICATION_ID, notification)
    }

    fun cancelElderOngoingStatus(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(ELDER_ONGOING_NOTIFICATION_ID)
    }

    fun triggerEmergencySiren(context: Context, payload: AlarmPayload) {
        com.careclose.core.alarms.service.AlarmRingingService.start(context, payload)
        val payloadJson = CareCloseJson.instance.encodeToString(AlarmPayload.serializer(), payload)
        val fullScreenIntent = Intent(context, FullScreenAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmExtras.EXTRA_PAYLOAD_JSON, payloadJson)
        }
        runCatching { context.startActivity(fullScreenIntent) }
    }
}

