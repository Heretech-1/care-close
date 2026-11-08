package com.careclose.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.careclose.core.alarms.AlarmPermissionChecker

@Composable
fun ElderPermissionsChecklist(
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(AlarmPermissionChecker.check(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = AlarmPermissionChecker.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        status = AlarmPermissionChecker.check(context)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Дозволи для надійних будильників",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Щоб телефон точно розбудив і показав нагадування поверх екрана навіть у режимі сну:",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionItemCard(
            title = "Сповіщення",
            description = "Показ карток нагадувань та сповіщень",
            granted = status.canPostNotifications,
            actionLabel = "Дозволити",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(AlarmPermissionChecker.appDetailsIntent(context))
                }
            },
        )

        PermissionItemCard(
            title = "Точні будильники",
            description = "Пробудження пристрою точно у час прийому ліків",
            granted = status.canScheduleExactAlarms,
            actionLabel = "Налаштувати",
            onAction = { context.startActivity(AlarmPermissionChecker.exactAlarmSettingsIntent(context)) },
        )

        PermissionItemCard(
            title = "Показ на заблокованому екрані",
            description = "Повноекранна картка ліків поверх блокування",
            granted = status.canUseFullScreenIntent,
            actionLabel = "Налаштувати",
            onAction = { context.startActivity(AlarmPermissionChecker.fullScreenIntentSettings(context)) },
        )

        PermissionItemCard(
            title = "Без обмежень батареї (рекомендовано)",
            description = "На Xiaomi/Samsung вимкніть економію батареї та увімкніть Автозапуск",
            granted = status.isIgnoringBatteryOptimizations,
            actionLabel = "Відкрити",
            onAction = { context.startActivity(AlarmPermissionChecker.batterySettingsIntent()) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            val label = if (status.allCriticalGranted) {
                "Усе готово — перейти до екрану"
            } else {
                "Продовжити (можна доналаштувати пізніше)"
            }
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = (if (granted) "✅ " else "⚠️ ") + title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            if (!granted) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

