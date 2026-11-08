package com.careclose.core.alarms.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.careclose.core.alarms.AlarmDependenciesProvider
import com.careclose.core.alarms.AlarmExtras
import com.careclose.core.alarms.AlarmKind
import com.careclose.core.alarms.AlarmPayload
import com.careclose.core.alarms.service.AlarmRingingService
import com.careclose.core.model.CareCloseJson
import com.careclose.core.model.CareEvent
import com.careclose.core.model.CareEventType
import com.careclose.core.model.Role
import com.careclose.core.model.engine.CareCloseWording
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class FullScreenAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenBehavior()

        val payloadJson = intent.getStringExtra(AlarmExtras.EXTRA_PAYLOAD_JSON)
        val payload = payloadJson?.let {
            runCatching {
                CareCloseJson.instance.decodeFromString(AlarmPayload.serializer(), it)
            }.getOrNull()
        }

        if (payload == null) {
            AlarmRingingService.stop(this)
            finish()
            return
        }

        val provider = applicationContext as? AlarmDependenciesProvider

        setContent {
            MaterialTheme {
                FullScreenAlarmContent(
                    payload = payload,
                    onConfirmTaken = {
                        val repo = provider?.careCloseRepository ?: return@FullScreenAlarmContent null
                        val deviceId = provider.deviceConfigStore.getDeviceId()
                        val event = CareEvent(
                            eventId = UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            actorRole = Role.ELDER,
                            logicalDate = payload.logicalDate,
                            planVersion = payload.planVersion,
                            taskId = payload.targetId,
                            type = when (payload.kind) {
                                AlarmKind.ANCHOR_SOFT_REMINDER -> CareEventType.ANCHOR_CONFIRMED
                                AlarmKind.EMERGENCY_CALL_PING -> CareEventType.EMERGENCY_PING_DISMISSED
                                else -> CareEventType.TAKEN
                            },
                            occurredAtUtc = Instant.now().toString(),
                        )
                        repo.recordLocalEvent(event)
                        provider.onLocalEventRecorded()
                        AlarmRingingService.stop(this)
                        provider.alarmCoordinator.scheduleNextElderAlarm()
                        event.eventId
                    },
                    onUndoTaken = { takenEventId ->
                        val repo = provider?.careCloseRepository ?: return@FullScreenAlarmContent
                        val deviceId = provider.deviceConfigStore.getDeviceId()
                        val undoEvent = CareEvent(
                            eventId = UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            actorRole = Role.ELDER,
                            logicalDate = payload.logicalDate,
                            planVersion = payload.planVersion,
                            taskId = payload.targetId,
                            type = CareEventType.TAKEN_UNDONE,
                            occurredAtUtc = Instant.now().toString(),
                            targetEventId = takenEventId,
                        )
                        repo.recordLocalEvent(undoEvent)
                        provider.onLocalEventRecorded()
                        provider.alarmCoordinator.scheduleNextElderAlarm()
                    },
                    onSnooze10Min = {
                        val repo = provider?.careCloseRepository
                        if (repo != null) {
                            val now = Instant.now()
                            val snoozeEvent = CareEvent(
                                eventId = UUID.randomUUID().toString(),
                                deviceId = provider.deviceConfigStore.getDeviceId(),
                                actorRole = Role.ELDER,
                                logicalDate = payload.logicalDate,
                                planVersion = payload.planVersion,
                                taskId = payload.targetId,
                                type = CareEventType.SNOOZED,
                                occurredAtUtc = now.toString(),
                                snoozeUntilUtc = now.plusSeconds(10 * 60L).toString(),
                            )
                            repo.recordLocalEvent(snoozeEvent)
                            provider.onLocalEventRecorded()
                            provider.alarmCoordinator.scheduleNextElderAlarm()
                        }
                        AlarmRingingService.stop(this)
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun configureLockScreenBehavior() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }
}

@Composable
private fun FullScreenAlarmContent(
    payload: AlarmPayload,
    onConfirmTaken: suspend () -> String?,
    onUndoTaken: suspend (String) -> Unit,
    onSnooze10Min: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmedEventId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(confirmedEventId) {
        if (confirmedEventId != null) {
            delay(4_500L)
            onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF6F8F5),
    ) {
        if (confirmedEventId != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (payload.kind == AlarmKind.EMERGENCY_CALL_PING) "ВІДПОВІДЬ НАДІСЛАНО ✅" else "ЗАПИСАНО ✅",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B5E20),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (payload.kind == AlarmKind.EMERGENCY_CALL_PING) {
                        "Опікун отримає сповіщення, що ви на зв'язку і все гаразд."
                    } else {
                        payload.title
                    },
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    color = Color(0xFF37474F),
                )
                if (payload.kind != AlarmKind.EMERGENCY_CALL_PING) {
                    Spacer(modifier = Modifier.height(32.dp))
                    OutlinedButton(
                        onClick = {
                            val targetId = confirmedEventId ?: return@OutlinedButton
                            confirmedEventId = null
                            scope.launch { onUndoTaken(targetId) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                    ) {
                        Text(
                            text = "Скасувати (я випадково)",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else if (payload.kind == AlarmKind.EMERGENCY_CALL_PING) {
            val context = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFFFEBEE))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "🚨 ЕКСТРЕНИЙ СИГНАЛ ОПІКУНА",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = payload.title,
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFFB71C1C),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = payload.instruction ?: "Опікун хвилюється і терміново намагається зв'язатися з вами. Будь ласка, натисніть кнопку нижче або зателефонуйте.",
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF37474F),
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val id = onConfirmTaken()
                                if (id != null) {
                                    confirmedEventId = id
                                } else {
                                    onClose()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp),
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                    ) {
                        Text(
                            text = "🟢 Я ТУТ, УСЕ ГАРАЗД",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    val phone = payload.subtitle?.takeIf { it.isNotBlank() }
                    if (phone != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    onConfirmTaken()
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.trim()}"))
                                    context.startActivity(dialIntent)
                                    onClose()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                        ) {
                            Text(
                                text = "📞 ПОДЗВОНИТИ ОПІКУНУ",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "НАГАДУВАННЯ",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = payload.title,
                        fontSize = 34.sp,
                        lineHeight = 40.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF111418),
                    )

                    val visualCount = payload.doseVisualCount
                    if (visualCount != null && visualCount > 0.0) {
                        Spacer(modifier = Modifier.height(20.dp))
                        DoseVisualRow(count = visualCount)
                    }

                    payload.subtitle?.let {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = it,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }

                    payload.instruction?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = it,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF42474E),
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val id = onConfirmTaken()
                                if (id != null) {
                                    confirmedEventId = id
                                } else {
                                    onClose()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp),
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                    ) {
                        Text(
                            text = CareCloseWording.elderActionTakenButton(payload.recipientGender, payload.taskType),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 0.5.sp,
                        )
                    }

                    Button(
                        onClick = { scope.launch { onSnooze10Min() } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                        shape = RoundedCornerShape(20.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    ) {
                        Text(
                            text = "НАГАДАТИ ЧЕРЕЗ 10 ХВ",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DoseVisualRow(count: Double) {
    val fullPills = count.toInt().coerceIn(0, 6)
    val hasHalf = (count - fullPills) >= 0.25
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(fullPills) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0D47A1)),
            )
        }
        if (hasHalf) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF90CAF9)),
                contentAlignment = Alignment.Center,
            ) {
                Text("½", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
            }
        }
    }
}

