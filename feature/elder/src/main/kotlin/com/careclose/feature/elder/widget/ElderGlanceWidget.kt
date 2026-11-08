package com.careclose.feature.elder.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.careclose.core.alarms.AlarmDependenciesProvider
import com.careclose.core.model.engine.DailyScheduleResolver
import com.careclose.core.model.engine.ResolvedTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ElderGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val provider = context.applicationContext as? AlarmDependenciesProvider
        val plan = provider?.careCloseRepository?.getActivePlan()

        val summary = if (plan != null) {
            val zone = plan.safeZoneId
            val today = LocalDate.now(zone)
            val events = provider.careCloseRepository.observeEventsForDate(today.toString()).first()
            val presence = provider.careCloseRepository.observeLatestElderPresence().first()
            val timeline = DailyScheduleResolver.resolve(
                plan = plan,
                logicalDate = today,
                events = events,
                elderPresence = presence,
                now = Instant.now(),
            )
            val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
            val completedCount = timeline.tasks.count { it.status == ResolvedTaskStatus.COMPLETED }
            val totalCount = timeline.tasks.size
            val nextTask = timeline.tasks.firstOrNull {
                it.status != ResolvedTaskStatus.COMPLETED && it.status != ResolvedTaskStatus.SKIPPED
            }
            val nextTitle = if (nextTask != null) {
                val timePart = nextTask.scheduledAt?.let { formatter.format(it) }
                    ?: nextTask.anchorTitle?.let { "Після: $it" }
                    ?: "Сьогодні"
                "$timePart · ${nextTask.task.title}"
            } else {
                "Усі справи на сьогодні виконано ✅"
            }
            val nextSubtitle = nextTask?.task?.doseLabel
                ?: nextTask?.task?.instruction
                ?: "Спокійного дня!"

            WidgetSummary(
                progressText = "Виконано: $completedCount із $totalCount",
                headline = nextTitle,
                subtitle = nextSubtitle,
                isUrgent = nextTask?.status == ResolvedTaskStatus.DUE_NOW ||
                    nextTask?.status == ResolvedTaskStatus.MISSED_ONLINE ||
                    nextTask?.status == ResolvedTaskStatus.MISSED_DEVICE_OFFLINE,
            )
        } else {
            WidgetSummary(
                progressText = "CareClose",
                headline = "Розклад ще не завантажено",
                subtitle = "Натисніть, щоб відкрити додаток",
                isUrgent = false,
            )
        }

        provideContent {
            GlanceTheme {
                val bgColor = if (summary.isUrgent) {
                    ColorProvider(Color(0xFFFFF3E0))
                } else {
                    ColorProvider(Color(0xFFF3F7F2))
                }
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(bgColor)
                        .cornerRadius(16.dp)
                        .padding(14.dp)
                        .clickable(actionRunCallback<OpenCareCloseAppAction>()),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.Start,
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Text(
                            text = summary.progressText,
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF1B5E20)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Text(
                            text = "↻ Оновити",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF0D47A1)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier = GlanceModifier.clickable(actionRunCallback<RefreshElderWidgetAction>()),
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(6.dp))

                    Text(
                        text = summary.headline,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF111418)),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )

                    Spacer(modifier = GlanceModifier.height(4.dp))

                    Text(
                        text = summary.subtitle,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF42474E)),
                            fontSize = 15.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    private data class WidgetSummary(
        val progressText: String,
        val headline: String,
        val subtitle: String,
        val isUrgent: Boolean,
    )

    companion object {
        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        suspend fun requestUpdate(context: Context) {
            runCatching { ElderGlanceWidget().updateAll(context.applicationContext) }
        }

        fun requestUpdateAsync(context: Context) {
            val appContext = context.applicationContext
            updateScope.launch {
                requestUpdate(appContext)
            }
        }
    }
}

class ElderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ElderGlanceWidget()
}

class OpenCareCloseAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val launchIntent = (context.applicationContext as? AlarmDependenciesProvider)
            ?.createMainActivityIntent(context)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}

class RefreshElderWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        ElderGlanceWidget.requestUpdate(context)
    }
}

