package ru.medeng.dicomtool.mwl.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.ui.components.DiagnosticOutlinedField
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.components.AppDeviceIpCard
import ru.medeng.dicomtool.ui.components.AppInfoBanner
import ru.medeng.dicomtool.ui.screens.receiver.ScpServiceNotice
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
import ru.medeng.dicomtool.ui.theme.WarningAmber
import ru.medeng.dicomtool.ui.theme.WarningAmberLight

/**
 * Экран сервера Modality Worklist (MWL SCP).
 * Приведён к общему визуальному шаблону с DICOM-приёмником SCP:
 * 1) Информационная подсказка
 * 2) Карточка IP-адреса устройства
 * 3) AE Title
 * 4) TCP Порт
 * 5) Кнопка Запуск / Остановка
 * 6) Карточка статуса сервера (под кнопкой)
 * 7) Дополнительные параметры (кодировка, белый список)
 */
@Composable
fun MwlScpTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val scpState by viewModel.scpState.collectAsStateWithLifecycle()
    val aeInput by viewModel.scpAeInput.collectAsStateWithLifecycle()
    val portInput by viewModel.scpPortInput.collectAsStateWithLifecycle()
    val whitelistMode by viewModel.scpWhitelistMode.collectAsStateWithLifecycle()
    val allowedAetsInput by viewModel.scpAllowedAetsInput.collectAsStateWithLifecycle()
    val allowedIpsInput by viewModel.scpAllowedIpsInput.collectAsStateWithLifecycle()
    val charset by viewModel.scpCharset.collectAsStateWithLifecycle()

    val isRunning = scpState.status == ScpServerStatus.RUNNING
    val isTransition = scpState.status == ScpServerStatus.STARTING || scpState.status == ScpServerStatus.STOPPING
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
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
                // Левая колонка: Сеть, Параметры, Кнопка
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MwlIpAddressCard(
                        ipv4 = scpState.networkInfo.ipv4Address,
                        interfaceName = scpState.networkInfo.interfaceName,
                        onRefresh = { viewModel.refreshNetworkInfo() }
                    )

                    MwlControlsCard(
                        aeTitle = aeInput,
                        portText = portInput,
                        isRunning = isRunning,
                        isTransition = isTransition,
                        onAeChange = { viewModel.scpAeInput.value = it.trim().uppercase() },
                        onPortChange = { viewModel.scpPortInput.value = it.filter { ch -> ch.isDigit() } },
                        onToggleServer = { viewModel.toggleScpServer() }
                    )
                }

                // Правая колонка: Статус сервера, Кодировка, Белый список
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MwlScpStatusCard(
                        status = scpState.status,
                        aeTitle = aeInput,
                        portText = portInput,
                        uptimeFormatted = scpState.uptimeFormatted,
                        totalRequestsCount = scpState.totalRequestsCount,
                        connectedNodesCount = scpState.connectedNodes.size,
                        errorMessage = scpState.errorMessage
                    )

                    MwlCharsetCard(
                        charset = charset,
                        isRunning = isRunning,
                        onCharsetChange = { viewModel.scpCharset.value = it }
                    )

                    MwlWhitelistCard(
                        whitelistMode = whitelistMode,
                        isRunning = isRunning,
                        allowedAetsInput = allowedAetsInput,
                        allowedIpsInput = allowedIpsInput,
                        onToggleMode = { viewModel.scpWhitelistMode.value = it },
                        onAetsChange = { viewModel.scpAllowedAetsInput.value = it },
                        onIpsChange = { viewModel.scpAllowedIpsInput.value = it }
                    )

                    if (scpState.connectedNodes.isNotEmpty()) {
                        MwlConnectedNodesCard(nodes = scpState.connectedNodes)
                    }
                }
            }
        } else {
            // Одноколоночный вид для смартфонов в строгом едином порядке
            MwlIpAddressCard(
                ipv4 = scpState.networkInfo.ipv4Address,
                interfaceName = scpState.networkInfo.interfaceName,
                onRefresh = { viewModel.refreshNetworkInfo() }
            )

            MwlControlsCard(
                aeTitle = aeInput,
                portText = portInput,
                isRunning = isRunning,
                isTransition = isTransition,
                onAeChange = { viewModel.scpAeInput.value = it.trim().uppercase() },
                onPortChange = { viewModel.scpPortInput.value = it.filter { ch -> ch.isDigit() } },
                onToggleServer = { viewModel.toggleScpServer() }
            )

            MwlScpStatusCard(
                status = scpState.status,
                aeTitle = aeInput,
                portText = portInput,
                uptimeFormatted = scpState.uptimeFormatted,
                totalRequestsCount = scpState.totalRequestsCount,
                connectedNodesCount = scpState.connectedNodes.size,
                errorMessage = scpState.errorMessage
            )

            MwlCharsetCard(
                charset = charset,
                isRunning = isRunning,
                onCharsetChange = { viewModel.scpCharset.value = it }
            )

            MwlWhitelistCard(
                whitelistMode = whitelistMode,
                isRunning = isRunning,
                allowedAetsInput = allowedAetsInput,
                allowedIpsInput = allowedIpsInput,
                onToggleMode = { viewModel.scpWhitelistMode.value = it },
                onAetsChange = { viewModel.scpAllowedAetsInput.value = it },
                onIpsChange = { viewModel.scpAllowedIpsInput.value = it }
            )

            if (scpState.connectedNodes.isNotEmpty()) {
                MwlConnectedNodesCard(nodes = scpState.connectedNodes)
            }
        }
    }
}

@Composable
private fun MwlIpAddressCard(
    ipv4: String,
    interfaceName: String,
    onRefresh: () -> Unit
) {
    AppDeviceIpCard(
        ipv4 = ipv4,
        interfaceName = interfaceName,
        onRefresh = onRefresh,
        refreshContentDescription = "Обновить сеть",
        copyLabel = "MWL IP"
    )
}

@Composable
private fun MwlControlsCard(
    aeTitle: String,
    portText: String,
    isRunning: Boolean,
    isTransition: Boolean,
    onAeChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onToggleServer: () -> Unit
) {
    DiagnosticOutlinedField(
        label = "AE Title (Called / Local SCP)",
        value = aeTitle,
        enabled = !isRunning && !isTransition,
        onValueChange = onAeChange
    )

    DiagnosticOutlinedField(
        label = "TCP Порт",
        value = portText,
        enabled = !isRunning && !isTransition,
        keyboardType = KeyboardType.Number,
        onValueChange = onPortChange
    )

    Button(
        onClick = onToggleServer,
        enabled = !isTransition,
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
            text = if (isTransition) "Ожидание завершения…" else if (isRunning) "Остановить сервер MWL SCP" else "Запустить сервер MWL SCP",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MwlScpStatusCard(
    status: ScpServerStatus,
    aeTitle: String,
    portText: String,
    uptimeFormatted: String,
    totalRequestsCount: Int,
    connectedNodesCount: Int,
    errorMessage: String?
) {
    val isRunning = status == ScpServerStatus.RUNNING
    val isError = status == ScpServerStatus.ERROR
    val isTransition = status == ScpServerStatus.STARTING || status == ScpServerStatus.STOPPING
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isRunning -> SuccessGreen
                            isError -> ErrorRed
                            isTransition -> WarningAmber
                            else -> Color(0xFF94A3B8)
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = when {
                    isTransition -> "Сервер переключается…"
                    isRunning -> "Сервер запущен ($aeTitle:$portText)"
                    isError -> "Ошибка сервера"
                    else -> "Сервер остановлен"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isRunning -> SuccessGreen
                    isError -> ErrorRed
                    isTransition -> WarningAmber
                    else -> TextPrimaryDark
                },
                softWrap = true
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isRunning) {
                "Ожидает запросы Worklist от оборудования"
            } else if (isTransition) {
                "Выполняется смена состояния сервера MWL SCP…"
            } else {
                "Ожидание запуска сервера MWL..."
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
                        text = "Параметры сокета: 0.0.0.0:$portText (C-FIND SCP · SOP Class 1.2.840.10008.5.1.4.31)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                    Text(
                        text = "Сеанс работы до 4 часов. Фоновая служба может быть остановлена Android при нехватке памяти.",
                        fontSize = 11.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        StatusCounterLine("Обработано запросов:", totalRequestsCount.toString())
        if (connectedNodesCount > 0) {
            StatusCounterLine("Активных клиентов:", connectedNodesCount.toString())
        }
        if (isRunning) {
            StatusCounterLine("Время работы:", uptimeFormatted)
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = Color(0xFFFEE2E2),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    color = ErrorRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MwlCharsetCard(
    charset: String,
    isRunning: Boolean,
    onCharsetChange: (String) -> Unit
) {
    WhiteDiagnosticCard {
        Text(
            text = "Параметры кодировки (0008,0005 Specific Character Set)",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(8.dp))

        val charsetOptions = listOf(
            "ISO_IR 192" to "UTF-8 (ISO_IR 192) — рекомендуется",
            "ISO_IR 144" to "ISO-8859-5 (ISO_IR 144) — стандартная кириллица",
            "ISO_IR 100" to "Latin-1 (ISO_IR 100) — западноевропейская",
            "ISO_IR 6" to "ASCII (ISO_IR 6) — только латиница"
        )
        var charsetExpanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = charsetExpanded,
            onExpandedChange = { if (!isRunning) charsetExpanded = it }
        ) {
            OutlinedTextField(
                value = charsetOptions.firstOrNull { it.first == charset }?.second ?: charset,
                onValueChange = {},
                readOnly = true,
                enabled = !isRunning,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = charsetExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = charsetExpanded,
                onDismissRequest = { charsetExpanded = false }
            ) {
                charsetOptions.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label, fontSize = 13.sp) },
                        onClick = {
                            onCharsetChange(code)
                            charsetExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MwlWhitelistCard(
    whitelistMode: Boolean,
    isRunning: Boolean,
    allowedAetsInput: String,
    allowedIpsInput: String,
    onToggleMode: (Boolean) -> Unit,
    onAetsChange: (String) -> Unit,
    onIpsChange: (String) -> Unit
) {
    WhiteDiagnosticCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Security, contentDescription = null, tint = PrimaryBlue)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Ограничения подключений (Белый список)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = if (whitelistMode) "Только разрешённые AE Title и IP" else "Разрешены все устройства в сети",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }
            }
            Switch(
                checked = whitelistMode,
                onCheckedChange = { if (!isRunning) onToggleMode(it) },
                enabled = !isRunning
            )
        }

        if (whitelistMode) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = CardBorderGray)
            Spacer(modifier = Modifier.height(12.dp))

            Text("Разрешённые Calling AE Title (через запятую)", fontSize = 12.sp, color = TextSecondarySlate)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = allowedAetsInput,
                onValueChange = onAetsChange,
                enabled = !isRunning,
                placeholder = { Text("Например: CR_STATION, DX_STATION") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text("Разрешённые IP-адреса (необязательно, через запятую)", fontSize = 12.sp, color = TextSecondarySlate)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = allowedIpsInput,
                onValueChange = onIpsChange,
                enabled = !isRunning,
                placeholder = { Text("Например: 192.168.1.50, 192.168.1.51") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MwlConnectedNodesCard(nodes: List<String>) {
    WhiteDiagnosticCard {
        Text(
            text = "Активность клиентов (${nodes.size})",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(8.dp))
        nodes.forEach { node ->
            Text(
                text = node,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondarySlate,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun StatusCounterLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextSecondarySlate)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimaryDark)
    }
}
