package com.careclose.feature.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.careclose.core.model.CaregiverAccessLevel
import com.careclose.core.model.CaregiverRole
import com.careclose.core.model.FamilyManifest
import com.careclose.core.model.PairingQrPayload
import com.careclose.core.model.RecipientRole
import com.careclose.core.model.Role
import com.careclose.core.sync.SyncConfigStore
import com.careclose.core.sync.SyncEngine
import com.careclose.core.sync.crypto.FamilyCrypto
import com.careclose.core.sync.drive.ServiceAccountJwtSigner
import com.careclose.core.sync.pairing.PairingPayloadCodec
import com.careclose.feature.onboarding.qr.CameraQrScanner
import com.careclose.feature.onboarding.qr.GoogleDriveFolderIdParser
import com.careclose.feature.onboarding.qr.QrBitmapGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private enum class OnboardingStep {
    CHOOSE_ROLE,
    CAREGIVER_DRIVE_SETUP,
    ELDER_PAIRING,
    ELDER_PERMISSIONS,
    HELPER_PAIRING,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    syncConfigStore: SyncConfigStore,
    onCreateSyncEngineForPayload: (PairingQrPayload) -> SyncEngine,
    onRoleSelected: (Role) -> Unit,
    onFinishOnboarding: (Role) -> Unit,
    modifier: Modifier = Modifier,
    isAddingNewProfile: Boolean = false,
    onCancelAddingProfile: (() -> Unit)? = null,
    onProfileConfigured: ((RecipientRole, CaregiverRole, String) -> Unit)? = null,
    onHelperConfigured: ((CaregiverRole, String?) -> Unit)? = null,
) {
    var step by remember {
        mutableStateOf(if (isAddingNewProfile) OnboardingStep.CAREGIVER_DRIVE_SETUP else OnboardingStep.CHOOSE_ROLE)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        when (step) {
            OnboardingStep.CHOOSE_ROLE -> {
                ChooseRoleStep(
                    onPickElder = {
                        onRoleSelected(Role.ELDER)
                        step = OnboardingStep.ELDER_PAIRING
                    },
                    onPickCaregiver = {
                        onRoleSelected(Role.CAREGIVER)
                        step = OnboardingStep.CAREGIVER_DRIVE_SETUP
                    },
                    onPickHelper = {
                        onRoleSelected(Role.CAREGIVER)
                        step = OnboardingStep.HELPER_PAIRING
                    },
                )
            }

            OnboardingStep.CAREGIVER_DRIVE_SETUP -> {
                CaregiverDriveSetupStep(
                    syncConfigStore = syncConfigStore,
                    onCreateSyncEngineForPayload = onCreateSyncEngineForPayload,
                    isAddingNewProfile = isAddingNewProfile,
                    onBack = onCancelAddingProfile ?: { step = OnboardingStep.CHOOSE_ROLE },
                    onComplete = { recipientRole, caregiverRole, familyName ->
                        onProfileConfigured?.invoke(recipientRole, caregiverRole, familyName)
                        onFinishOnboarding(Role.CAREGIVER)
                    },
                )
            }

            OnboardingStep.ELDER_PAIRING -> {
                ElderPairingStep(
                    syncConfigStore = syncConfigStore,
                    onCreateSyncEngineForPayload = onCreateSyncEngineForPayload,
                    onBack = { step = OnboardingStep.CHOOSE_ROLE },
                    onPairedOrSkipped = { step = OnboardingStep.ELDER_PERMISSIONS },
                )
            }

            OnboardingStep.ELDER_PERMISSIONS -> {
                ElderPermissionsChecklist(
                    onDone = {
                        // За потреби тут може бути додаткова синхронізація перед переходом
                        onFinishOnboarding(Role.ELDER)
                    },
                )
            }

            OnboardingStep.HELPER_PAIRING -> {
                HelperPairingStep(
                    syncConfigStore = syncConfigStore,
                    onCreateSyncEngineForPayload = onCreateSyncEngineForPayload,
                    onBack = { step = OnboardingStep.CHOOSE_ROLE },
                    onComplete = { role, name ->
                        onHelperConfigured?.invoke(role, name)
                        onFinishOnboarding(Role.CAREGIVER)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChooseRoleStep(
    onPickElder: () -> Unit,
    onPickCaregiver: () -> Unit,
    onPickHelper: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Ласкаво просимо до CareClose",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Оберіть, кому належить цей телефон:",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onPickElder,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        ) {
            Text("🧓  Я — Підопічний (Літня людина)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onPickCaregiver,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("📱  Я — Головний опікун (Дорослий)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        OutlinedButton(
            onClick = onPickHelper,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("🤝  Я — Помічник (Сусідка / Медсестра)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaregiverDriveSetupStep(
    syncConfigStore: SyncConfigStore,
    onCreateSyncEngineForPayload: (PairingQrPayload) -> SyncEngine,
    isAddingNewProfile: Boolean = false,
    onBack: () -> Unit,
    onComplete: (RecipientRole, CaregiverRole, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember { if (isAddingNewProfile) null else syncConfigStore.getPairingPayload() }

    var recipientRole by remember { mutableStateOf(RecipientRole.MOTHER) }
    var caregiverRole by remember { mutableStateOf(CaregiverRole.DAUGHTER) }
    var familyName by remember { mutableStateOf(existing?.familyName ?: "Мама") }
    var folderInput by remember { mutableStateOf(existing?.folderId ?: "") }
    var saJson by remember {
        mutableStateOf(existing?.serviceAccountJson ?: syncConfigStore.getPairingPayload()?.serviceAccountJson ?: "")
    }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var qrString by remember {
        mutableStateOf(existing?.let { PairingPayloadCodec.encode(it) })
    }
    var qrBitmap by remember(qrString) {
        mutableStateOf<Bitmap?>(qrString?.let { QrBitmapGenerator.generate(it) })
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.onSuccess { text ->
                if (!text.isNullOrBlank()) {
                    saJson = text
                    errorMessage = null
                }
            }.onFailure {
                errorMessage = "Не вдалося прочитати JSON-файл: ${it.message}"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) {
            Text(if (isAddingNewProfile) "← Скасувати" else "← Назад до вибору ролі")
        }

        Text(
            text = if (isAddingNewProfile) "Додати нового підопічного" else "Підключення Google Drive (Опікун)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Створіть окрему папку на Google Drive для цієї близької людини та надайте доступ для Service Account з правами Редактора.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Кого ви доглядаєте?", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RecipientRole.MOTHER to "Мама",
                RecipientRole.FATHER to "Тато",
                RecipientRole.GRANDMOTHER to "Бабуся",
                RecipientRole.GRANDFATHER to "Дідусь",
                RecipientRole.OTHER to "Інше",
            ).forEach { (role, label) ->
                FilterChip(
                    selected = recipientRole == role,
                    onClick = {
                        recipientRole = role
                        if (familyName.isBlank() || familyName in listOf("Мама", "Тато", "Бабуся", "Дідусь", "Підопічний")) {
                            familyName = label
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        Text("Хто ви для підопічного?", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                CaregiverRole.DAUGHTER to "Донька",
                CaregiverRole.SON to "Син",
                CaregiverRole.GRANDDAUGHTER to "Онука",
                CaregiverRole.GRANDSON to "Онук",
                CaregiverRole.OTHER to "Опікун",
            ).forEach { (role, label) ->
                FilterChip(
                    selected = caregiverRole == role,
                    onClick = { caregiverRole = role },
                    label = { Text(label) },
                )
            }
        }

        OutlinedTextField(
            value = familyName,
            onValueChange = { familyName = it },
            label = { Text("Звертання / Ім'я (напр. Мама)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = folderInput,
            onValueChange = { folderInput = it },
            label = { Text("Посилання або ID папки Google Drive") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "*/*")) },
            ) {
                Text("Обрати .json ключ")
            }
        }

        OutlinedTextField(
            value = saJson,
            onValueChange = { saJson = it },
            label = { Text("Service Account JSON") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxLines = 5,
        )

        errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                val folderId = GoogleDriveFolderIdParser.extractFolderId(folderInput)
                if (folderId.isBlank() || saJson.isBlank()) {
                    errorMessage = "Заповніть ID/посилання папки та JSON-ключ Service Account"
                    return@Button
                }
                isBusy = true
                errorMessage = null
                scope.launch {
                    val result = runCatching {
                        ServiceAccountJwtSigner.parseCredentials(saJson)
                        val payload = PairingQrPayload(
                            familyId = existing?.familyId ?: "fam_${UUID.randomUUID().toString().take(8)}",
                            familyName = familyName.ifBlank { "Родина" },
                            folderId = folderId,
                            serviceAccountJson = saJson.trim(),
                            familyAesKeyBase64 = existing?.familyAesKeyBase64
                                ?: FamilyCrypto.generateNewKeyBase64(),
                            createdAtUtc = Instant.now().toString(),
                        )
                        val engine = onCreateSyncEngineForPayload(payload)
                        withContext(Dispatchers.IO) {
                            engine.ensureFamilyStructure(
                                FamilyManifest(
                                    familyId = payload.familyId,
                                    familyName = payload.familyName,
                                    timezone = ZoneId.systemDefault().id,
                                    createdAtUtc = payload.createdAtUtc,
                                ),
                            )
                        }
                        syncConfigStore.savePairingPayload(payload)
                        PairingPayloadCodec.encode(payload)
                    }
                    isBusy = false
                    result.onSuccess { encoded ->
                        qrString = encoded
                    }.onFailure { err ->
                        errorMessage = "Помилка підключення: ${err.message}"
                    }
                }
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Перевірити папку і створити QR-код")
            }
        }

        val currentQr = qrBitmap
        val currentQrText = qrString
        if (currentQr != null && currentQrText != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Відскануйте на телефоні підопічного",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Image(
                        bitmap = currentQr.asImageBitmap(),
                        contentDescription = "QR-код прив'язки CareClose",
                        modifier = Modifier.size(240.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("CareClose Pairing", currentQrText))
                        },
                    ) {
                        Text("Скопіювати код текстом")
                    }
                }
            }

            Button(
                onClick = { onComplete(recipientRole, caregiverRole, familyName) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Перейти до панелі опікуна")
            }
        } else {
            TextButton(
                onClick = { onComplete(recipientRole, caregiverRole, familyName) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Продовжити офлайн (без Drive)")
            }
        }
    }
}

@Composable
private fun ElderPairingStep(
    syncConfigStore: SyncConfigStore,
    onCreateSyncEngineForPayload: (PairingQrPayload) -> SyncEngine,
    onBack: () -> Unit,
    onPairedOrSkipped: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showScanner by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun applyPairingCode(rawCode: String) {
        isSyncing = true
        errorText = null
        scope.launch {
            val result = runCatching {
                val payload = PairingPayloadCodec.decode(rawCode)
                syncConfigStore.savePairingPayload(payload)
                val engine = onCreateSyncEngineForPayload(payload)
                withContext(Dispatchers.IO) {
                    engine.pullAll()
                }
            }
            isSyncing = false
            result.onSuccess {
                onPairedOrSkipped()
            }.onFailure {
                errorText = "Помилка коду прив'язки або мережі: ${it.message}"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("← Назад до вибору ролі") }

        Text(
            text = "Підключення до родини",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (showScanner) {
            CameraQrScanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                onQrDecoded = { decoded ->
                    showScanner = false
                    manualCode = decoded
                    applyPairingCode(decoded)
                },
            )
            OutlinedButton(
                onClick = { showScanner = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Закрити камеру")
            }
        } else {
            Button(
                onClick = { showScanner = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text("Сканувати QR-код камерою", style = MaterialTheme.typography.titleMedium)
            }
        }

        OutlinedTextField(
            value = manualCode,
            onValueChange = { manualCode = it },
            label = { Text("Або вставте код прив'язки (CARECLOSE1:...)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )

        errorText?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = { applyPairingCode(manualCode) },
            enabled = manualCode.isNotBlank() && !isSyncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Підключитися")
            }
        }

        TextButton(
            onClick = onPairedOrSkipped,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Пропустити поки що (локальний режим)")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelperPairingStep(
    syncConfigStore: SyncConfigStore,
    onCreateSyncEngineForPayload: (PairingQrPayload) -> SyncEngine,
    onBack: () -> Unit,
    onComplete: (CaregiverRole, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showScanner by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pairedPayload by remember { mutableStateOf<PairingQrPayload?>(null) }

    var helperRole by remember { mutableStateOf(CaregiverRole.NEIGHBOR) }
    var helperName by remember { mutableStateOf("") }

    fun applyPairingCode(rawCode: String) {
        isSyncing = true
        errorText = null
        scope.launch {
            val result = runCatching {
                val payload = PairingPayloadCodec.decode(rawCode)
                val engine = onCreateSyncEngineForPayload(payload)
                withContext(Dispatchers.IO) {
                    engine.pullAll()
                }
                syncConfigStore.savePairingPayload(payload)
                payload
            }
            isSyncing = false
            result.onSuccess { payload ->
                pairedPayload = payload
                payload.targetRole?.let { helperRole = it }
            }.onFailure {
                errorText = "Помилка коду помічника: ${it.message}"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("← Назад до вибору ролі") }

        Text(
            text = "Підключення помічника",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        val currentPaired = pairedPayload
        if (currentPaired == null) {
            Text(
                text = "Відскануйте QR-код помічника, наданий головним опікуном (донькою/сином):",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (showScanner) {
                CameraQrScanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    onQrDecoded = { decoded ->
                        showScanner = false
                        manualCode = decoded
                        applyPairingCode(decoded)
                    },
                )
                OutlinedButton(
                    onClick = { showScanner = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Закрити камеру")
                }
            } else {
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    Text("Сканувати QR-код камерою", style = MaterialTheme.typography.titleMedium)
                }
            }

            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text("Або вставте код прив'язки (CARECLOSE1:...)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )

            errorText?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { applyPairingCode(manualCode) },
                enabled = manualCode.isNotBlank() && !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Підключитися")
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("✓ Успішно підключено!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("Підопічний / Сім'я: ${currentPaired.familyName}")
                }
            }

            Text("Хто ви для підопічного?", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    CaregiverRole.NEIGHBOR to "Сусідка / Сусід",
                    CaregiverRole.AIDE to "Сиділка",
                    CaregiverRole.NURSE to "Медсестра",
                    CaregiverRole.VOLUNTEER to "Волонтер",
                    CaregiverRole.OTHER to "Інше",
                ).forEach { (role, label) ->
                    FilterChip(
                        selected = helperRole == role,
                        onClick = { helperRole = role },
                        label = { Text(label) },
                    )
                }
            }

            OutlinedTextField(
                value = helperName,
                onValueChange = { helperName = it },
                label = { Text("Ваше ім'я (наприклад, Оксана, Марія)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = { onComplete(helperRole, helperName.trim().ifEmpty { null }) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Перейти до розкладу")
            }
        }
    }
}

