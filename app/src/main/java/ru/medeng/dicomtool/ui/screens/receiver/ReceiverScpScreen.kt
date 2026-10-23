package ru.medeng.dicomtool.ui.screens.receiver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ru.medeng.dicomtool.domain.model.ScpReceiverState
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.ui.components.DiagnosticOutlinedField
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.components.AppDeviceIpCard
import ru.medeng.dicomtool.ui.components.AppInfoBanner
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBg
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBorder
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.StopButtonRed
import ru.medeng.dicomtool.ui.theme.SuccessGreen
import ru.medeng.dicomtool.ui.theme.SuccessGreenBackground
import ru.medeng.dicomtool.ui.theme.SuccessGreenBorder
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.OutlinedButton
import ru.medeng.dicomtool.data.storage.StorageLocationInfo
import ru.medeng.dicomtool.data.storage.StorageType

/**
 * Экран 1 и Экран 2 референса ui-light.png: «DICOM Приёмник (SCP)».
 * Подключён к настоящему серверу DicomStorageScpServer (dcm4che 5.33.1) через Foreground Service.
 */
@Composable
fun ReceiverScpScreen(
    state: ScpReceiverState,
    aeTitleInput: String,
    portInput: String,
    isExpandedWidth: Boolean,
    storageInfo: StorageLocationInfo? = null,
    onOpenFolder: (() -> Unit)? = null,
    onAeTitleChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onIncrementPort: (Int) -> Unit,
    onRefreshNetwork: () -> Unit,
    onToggleServerState: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onToggleServerState()
    }

    val handleStartStopClick = {
        val isRunning = state.status == ScpServerStatus.RUNNING
        if (!isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onToggleServerState()
            }
        } else {
            onToggleServerState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (isExpandedWidth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    IpAddressCard(
                        ipv4 = state.networkInfo.ipv4Address,
                        interfaceName = state.networkInfo.interfaceName,
                        onRefresh = onRefreshNetwork
                    )
                    AeAndPortControls(
                        aeTitle = aeTitleInput,
                        portText = portInput,
                        isRunning = state.status == ScpServerStatus.RUNNING,
                        busy = state.serviceStopping || state.status in listOf(ScpServerStatus.STARTING, ScpServerStatus.STOPPING),
                        validationError = state.validationError,
                        errorMessage = state.errorMessage,
                        noticeId = state.noticeId,
                        onAeTitleChange = onAeTitleChange,
                        onPortChange = onPortChange,
                        onIncrementPort = onIncrementPort,
                        onToggleServerState = handleStartStopClick
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ScpStatusCard(
                        state = state,
                        storageInfo = storageInfo,
                        onOpenFolder = onOpenFolder
                    )
                }
            }
        } else {
            IpAddressCard(
                ipv4 = state.networkInfo.ipv4Address,
                interfaceName = state.networkInfo.interfaceName,
                onRefresh = onRefreshNetwork
            )
            AeAndPortControls(
                aeTitle = aeTitleInput,
                portText = portInput,
                isRunning = state.status == ScpServerStatus.RUNNING,
                        busy = state.serviceStopping || state.status in listOf(ScpServerStatus.STARTING, ScpServerStatus.STOPPING),
                validationError = state.validationError,
                errorMessage = state.errorMessage,
                        noticeId = state.noticeId,
                onAeTitleChange = onAeTitleChange,
                onPortChange = onPortChange,
                onIncrementPort = onIncrementPort,
                onToggleServerState = handleStartStopClick
            )
            ScpStatusCard(
                state = state,
                storageInfo = storageInfo,
                onOpenFolder = onOpenFolder
            )
        }
    }
}

@Composable
fun ScpServiceNotice(
    title: String,
    detailedText: String,
    modifier: Modifier = Modifier
) {
    AppInfoBanner(title = title, detailedText = detailedText, modifier = modifier)
}

@Composable
private fun IpAddressCard(
    ipv4: String,
    interfaceName: String,
    onRefresh: () -> Unit
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "IP адрес устройства",
                    fontSize = 13.sp,
                    color = TextSecondarySlate
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = ipv4,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Интерфейс: $interfaceName",
                        fontSize = 13.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Обновить IP",
                        tint = PrimaryBlue
                    )
                }
                val ctx = LocalContext.current
                IconButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("SCP IP", ipv4))
                        Toast.makeText(ctx, "Скопировано: $ipv4", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Скопировать IP",
                        tint = TextSecondarySlate
                    )
                }
            }
        }
    }
}

@Composable
private fun AeAndPortControls(
    aeTitle: String,
    portText: String,
    isRunning: Boolean,
    busy: Boolean,
    validationError: String?,
    errorMessage: String?,
    noticeId: String,
    onAeTitleChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onIncrementPort: (Int) -> Unit,
    onToggleServerState: () -> Unit
) {
    DiagnosticOutlinedField(
        label = "AE Title (Called / Local SCP)",
        value = aeTitle,
        enabled = !isRunning && !busy,
        isError = validationError != null,
        onValueChange = onAeTitleChange
    )

    DiagnosticOutlinedField(
        label = "Порт (1024..65535)",
        value = portText,
        enabled = !isRunning && !busy,
        isError = validationError != null,
        onValueChange = onPortChange,
        keyboardType = KeyboardType.Number,
        trailingIcon = {
            if (!isRunning && !busy) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Увеличить порт",
                        tint = TextSecondarySlate,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onIncrementPort(1) }
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Уменьшить порт",
                        tint = TextSecondarySlate,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onIncrementPort(-1) }
                    )
                }
            }
        }
    )

    val displayError = validationError ?: errorMessage
    if (!displayError.isNullOrBlank()) {
        ru.medeng.dicomtool.ui.components.AppNotice("scp-error:$noticeId:$aeTitle:$portText:$displayError", displayError, error = true)
    }

    Button(
        onClick = onToggleServerState,
        enabled = !busy && (isRunning || validationError == null),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) StopButtonRed else PrimaryBlue,
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (busy) "Ожидание завершения…" else if (isRunning) "Остановить сервер" else "Запустить сервер",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ScpStatusCard(
    state: ScpReceiverState,
    storageInfo: StorageLocationInfo? = null,
    onOpenFolder: (() -> Unit)? = null
) {
    val isRunning = state.status == ScpServerStatus.RUNNING
    val isError = state.status == ScpServerStatus.ERROR
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val cardBg = when {
        isRunning -> SuccessGreenBackground
        isError -> DangerActionSoftBg
        else -> CardSurfaceWhite
    }
    val cardBorder = when {
        isRunning -> SuccessGreenBorder
        isError -> DangerActionSoftBorder
        else -> CardBorderGray
    }

    WhiteDiagnosticCard(
        backgroundColor = cardBg,
        borderColor = cardBorder
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isRunning -> SuccessGreen
                            isError -> ErrorRed
                            else -> Color(0xFF94A3B8)
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    state.serviceStopping || state.status == ScpServerStatus.STOPPING -> "SCP останавливается: ожидание освобождения ресурсов"
                    state.status == ScpServerStatus.STARTING -> "SCP запускается"
                    isRunning -> "Сервер запущен (${state.callingAeTitle}:${state.tcpPort})"
                    isError -> "Ошибка запуска сервера"
                    else -> "Сервер остановлен"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isRunning -> SuccessGreen
                    isError -> ErrorRed
                    else -> TextPrimaryDark
                },
                softWrap = true
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isRunning) {
                "Ожидает подключения · C-ECHO и приём исследований"
            } else if (state.serviceStopping || state.status == ScpServerStatus.STOPPING) {
                "Новые соединения отключены; выполняется освобождение ресурсов."
            } else {
                "Ожидание запуска приёмника..."
            },
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = TextSecondarySlate,
            softWrap = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { showDetails = !showDetails }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (showDetails) "Скрыть технические сведения" else "Подробнее",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryBlue
            )
            Icon(
                imageVector = if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (showDetails) "Скрыть подробности" else "Показать подробности",
                tint = PrimaryBlue,
                modifier = Modifier.size(16.dp)
            )
        }

        if (showDetails) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = InputBackgroundGray,
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Параметры сокета: 0.0.0.0:${state.tcpPort} (C-ECHO SCP, C-STORE SCP Implicit/Explicit VR Little Endian)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                    Text(
                        text = "Сеанс приёма до 4 часов. Фоновая работа может быть ограничена Android при нехватке памяти или энергосбережении.",
                        fontSize = 11.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        StatusCounterLine("Принято исследований:", state.receivedStudiesCount.toString())
        StatusCounterLine("Принято изображений:", state.receivedImagesCount.toString())
        if (isRunning) {
            StatusCounterLine("Активных соединений:", state.activeConnectionsCount.toString())
            StatusCounterLine("Время работы:", state.uptimeFormatted)
        }

        Spacer(modifier = Modifier.height(10.dp))
        val isSaf = storageInfo != null && storageInfo.type == StorageType.SAF
        val isAccessible = storageInfo?.isAccessible != false

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isSaf) "Папка хранения (Пользовательская папка):" else "Папка хранения (Внутреннее хранилище):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondarySlate
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (storageInfo != null && storageInfo.type == StorageType.SAF) {
                    val folderName = storageInfo.folderName.ifBlank { "Пользовательская папка" }
                    val location = storageInfo.readableLocation
                    Text(
                        text = folderName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    if (location.isNotBlank() && location != folderName) {
                        Text(
                            text = location,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = TextSecondarySlate,
                            softWrap = true
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = state.storagePath.ifBlank { storageInfo?.displayPath ?: "" },
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimaryDark,
                            softWrap = true
                        )
                    }
                }
            }

            if (onOpenFolder != null && isSaf) {
                OutlinedButton(
                    onClick = onOpenFolder,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, CardBorderGray),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = PrimaryBlue
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Открыть",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryBlue
                    )
                }
            }
        }

        val storageWarning = listOfNotNull(storageInfo?.warningMessage, storageInfo?.retainedIncoming?.warningMessage)
            .joinToString("\n").ifBlank { null }
        if ((isSaf && !isAccessible) || storageWarning != null) {
            ru.medeng.dicomtool.ui.components.AppNotice("scp-storage:${storageInfo?.displayPath}:$storageWarning:$isAccessible",
                storageWarning ?: "Выбранная папка недоступна. Новые файлы не могут быть сохранены. Подробности и восстановление — в настройках хранилища.")
        }

        if (state.connectedNodes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Подключавшиеся DICOM-узлы:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondarySlate
            )
            state.connectedNodes.forEach { node ->
                Text(
                    text = "• $node",
                    fontSize = 13.sp,
                    color = TextPrimaryDark,
                    softWrap = true,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusCounterLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondarySlate,
            softWrap = true,
            modifier = Modifier.weight(0.65f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimaryDark,
            modifier = Modifier.weight(0.35f)
        )
    }
}
