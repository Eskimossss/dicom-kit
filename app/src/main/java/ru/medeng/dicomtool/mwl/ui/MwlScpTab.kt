package ru.medeng.dicomtool.mwl.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.ui.components.DiagnosticOutlinedField
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.CardBorderGray
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MwlScpTab(
    viewModel: MwlViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Карточка 1: Статус сервера и управление
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Сервер Modality Worklist (SCP)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = "DIMSE C-FIND · SOP Class 1.2.840.10008.5.1.4.31",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                }

                // Индикатор состояния
                val (statusText, statusBg, statusColor) = when (scpState.status) {
                    ScpServerStatus.RUNNING -> Triple("АКТИВЕН", SuccessGreenBackground, SuccessGreen)
                    ScpServerStatus.STARTING -> Triple("ЗАПУСК…", WarningAmberLight, WarningAmber)
                    ScpServerStatus.STOPPING -> Triple("ОСТАНОВКА…", WarningAmberLight, WarningAmber)
                    ScpServerStatus.ERROR -> Triple("ОШИБКА", Color(0xFFFEE2E2), ErrorRed)
                    ScpServerStatus.STOPPED -> Triple("ОСТАНОВЛЕН", InputBackgroundGray, TextSecondarySlate)
                }

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Сетевой адрес и интерфейс
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = InputBackgroundGray,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, CardBorderGray)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "IP для подключения оборудования",
                                fontSize = 11.sp,
                                color = TextSecondarySlate
                            )
                            Text(
                                text = "${scpState.networkInfo.ipv4Address} : ${scpState.tcpPort}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = scpState.networkInfo.interfaceName,
                                fontSize = 11.sp,
                                color = TextSecondarySlate
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = { viewModel.refreshNetworkInfo() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить сеть", tint = PrimaryBlue)
                        }
                        IconButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("MWL IP", "${scpState.networkInfo.ipv4Address}:${scpState.tcpPort}"))
                            Toast.makeText(context, "Скопировано: ${scpState.networkInfo.ipv4Address}:${scpState.tcpPort}", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Скопировать", tint = TextSecondarySlate)
                        }
                    }
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Время работы: ${scpState.uptimeFormatted}",
                        fontSize = 12.sp,
                        color = TextSecondarySlate
                    )
                    Text(
                        text = "Обработано запросов: ${scpState.totalRequestsCount}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопка Запуск / Остановка
            Button(
                onClick = { viewModel.toggleScpServer() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isTransition,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) StopButtonRed else PrimaryBlue,
                    disabledContainerColor = CardBorderGray
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isRunning) "Остановить сервер MWL SCP" else "Запустить сервер MWL SCP",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!scpState.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = scpState.errorMessage.orEmpty(),
                        color = ErrorRed,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // Карточка 2: Параметры DICOM Association и кодировка
        WhiteDiagnosticCard {
            Text(
                text = "Параметры сервера (Association & Charset)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Called AE Title", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondarySlate)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = aeInput,
                        onValueChange = { viewModel.scpAeInput.value = it.trim().uppercase() },
                        enabled = !isRunning,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("TCP Порт", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondarySlate)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { viewModel.scpPortInput.value = it.filter { ch -> ch.isDigit() } },
                        enabled = !isRunning,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Выбор Specific Character Set (0008,0005)
            Text(
                text = "Кодировка ответов (0008,0005 Specific Character Set)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondarySlate
            )
            Spacer(modifier = Modifier.height(4.dp))

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
                                viewModel.scpCharset.value = code
                                charsetExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Карточка 3: Ограничения подключения (Whitelist)
        WhiteDiagnosticCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = PrimaryBlue)
                    Column {
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
                    onCheckedChange = { if (!isRunning) viewModel.scpWhitelistMode.value = it },
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
                    onValueChange = { viewModel.scpAllowedAetsInput.value = it },
                    enabled = !isRunning,
                    placeholder = { Text("Например: CR_STATION, DX_STATION") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Разрешённые IP-адреса (необязательно, через запятую)", fontSize = 12.sp, color = TextSecondarySlate)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = allowedIpsInput,
                    onValueChange = { viewModel.scpAllowedIpsInput.value = it },
                    enabled = !isRunning,
                    placeholder = { Text("Например: 192.168.1.50, 192.168.1.51") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Карточка 4: Подключённые узлы
        if (scpState.connectedNodes.isNotEmpty()) {
            WhiteDiagnosticCard {
                Text(
                    text = "Активность клиентов (${scpState.connectedNodes.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                scpState.connectedNodes.forEach { node ->
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
    }
}
