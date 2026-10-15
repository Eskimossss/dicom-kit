package ru.medeng.dicomtool.ui.screens.settings

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.BuildConfig
import ru.medeng.dicomtool.data.storage.StorageLocationInfo
import ru.medeng.dicomtool.data.storage.StorageType
import ru.medeng.dicomtool.ui.components.DicomToolSubScreenTopBar
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.theme.AppBackgroundGray
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBg
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBorder
import ru.medeng.dicomtool.ui.theme.ErrorRed
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.SuccessGreen
import ru.medeng.dicomtool.ui.theme.TextMutedSlate
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import java.util.Locale

/**
 * Экран настроек и управления данными DICOM Tool (Этап 3.1):
 * 1. Настраиваемое хранилище DICOM через Storage Access Framework (SAF, Intent.ACTION_OPEN_DOCUMENT_TREE).
 *    - По умолчанию: внутреннее хранилище (filesDir/received_dicom).
 *    - Поддержка пользовательских папок на устройстве и SD-картах без MANAGE_EXTERNAL_STORAGE.
 *    - Безопасный перенос с проверкой целостности и возможностью оставить файлы на прежнем месте.
 * 2. Очистка принятых исследований и журнала.
 * 3. Окно «О приложении» с BuildConfig.VERSION_NAME и ссылкой на Telegram @eskimossss.
 * 4. Пункт «Тема оформления» полностью удалён (светлая тема фиксирована).
 */
@Composable
fun SettingsAndCleanupScreen(
    storageInfo: StorageLocationInfo,
    receptionOptions: ru.medeng.dicomtool.data.storage.ReceptionStorageOptions,
    onReceptionOptionsChange: (ru.medeng.dicomtool.data.storage.ReceptionStorageOptions) -> Unit,
    onRecoverIncoming: () -> Unit,
    isMigrating: Boolean,
    migrationStatusMessage: String?,
    studiesCount: Int,
    filesCount: Int,
    totalBytes: Long,
    logsCount: Int,
    onBack: () -> Unit,
    onSelectSafStorage: (Uri) -> Unit,
    onResetToInternalStorage: () -> Unit,
    onMigrateStudies: (Boolean) -> Unit,
    onClearAllStudiesConfirmed: () -> Unit,
    onClearAllLogsConfirmed: () -> Unit
) {
    var confirmClearStudies by remember { mutableStateOf(false) }
    var confirmClearLogs by remember { mutableStateOf(false) }
    var confirmMigrateStudies by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current

    // SAF-лаунчер выбора папки через ACTION_OPEN_DOCUMENT_TREE
    val safFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSelectSafStorage(treeUri)
        }
    }

    if (confirmClearStudies) {
        AlertDialog(
            onDismissRequest = { confirmClearStudies = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "Удалить все принятые исследования?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            },
            text = {
                Text(
                    text = "Будут удалены подтверждённые файлы приложения из хранилища. Неизвестные SAF-документы и сохранённые входящие .incoming_tmp/*.part не удаляются. Технический журнал сохранится. Это действие нельзя отменить." +
                        (storageInfo.retainedIncoming.warningMessage?.let { "\n\n$it" } ?: ""),
                    color = TextSecondarySlate,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearStudies = false
                        onClearAllStudiesConfirmed()
                    }
                ) {
                    Text("Удалить все", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearStudies = false }) {
                    Text("Отмена", color = TextSecondarySlate)
                }
            }
        )
    }

    if (confirmClearLogs) {
        AlertDialog(
            onDismissRequest = { confirmClearLogs = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "Очистить технический журнал?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            },
            text = {
                Text(
                    text = "Удаляются все записи из журнала DICOM ($logsCount зап.). Принятые исследования при этом не затрагиваются.",
                    color = TextSecondarySlate,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearLogs = false
                        onClearAllLogsConfirmed()
                    }
                ) {
                    Text("Очистить журнал", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLogs = false }) {
                    Text("Отмена", color = TextSecondarySlate)
                }
            }
        )
    }

    if (confirmMigrateStudies) {
        val toSaf = storageInfo.type == StorageType.SAF
        AlertDialog(
            onDismissRequest = { confirmMigrateStudies = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "Перенести сохранённые исследования?",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
            },
            text = {
                Text(
                    text = if (toSaf) {
                        "Все исследования из внутреннего хранилища будут безопасно скопированы в выбранную SAF-папку с обязательной проверкой целостности каждого файла перед удалением исходника.\n\nЕсли вы отмените перенос, существующие исследования останутся на прежнем месте и по-прежнему будут отображаться в списке."
                    } else {
                        "Все исследования из SAF-папки будут скопированы во внутреннее хранилище приложения с обязательной проверкой целостности.\n\nЕсли вы отмените перенос, ранее сохранённые исследования останутся на прежнем месте."
                    },
                    color = TextSecondarySlate,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    softWrap = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmMigrateStudies = false
                        onMigrateStudies(toSaf)
                    }
                ) {
                    Text("Начать перенос", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMigrateStudies = false }) {
                    Text("Оставить на месте", color = TextSecondarySlate)
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            containerColor = CardSurfaceWhite,
            title = {
                Text(
                    text = "DICOM Kit",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimaryDark
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Версия: ${BuildConfig.VERSION_NAME}",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Автономное мобильное приложение для сервисных инженеров медицинского оборудования. Предназначено для локальной диагностики DICOM Storage SCP и SCU (C-ECHO, C-STORE, просмотр снимков и анализ структуры тегов).",
                        color = TextSecondarySlate,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    HorizontalDivider(color = CardBorderGray, thickness = 0.8.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    uriHandler.openUri("https://t.me/eskimossss")
                                } catch (_: Exception) {}
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Telegram",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Разработчик в Telegram:",
                                fontSize = 12.sp,
                                color = TextSecondarySlate
                            )
                            Text(
                                text = "@eskimossss",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Закрыть", color = PrimaryBlue, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundGray)
    ) {
        DicomToolSubScreenTopBar(
            title = "DICOM Kit — Действия и настройки",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Раздел 1: Настраиваемое хранилище DICOM (SAF)
            Text(
                text = "Хранилище принимаемых исследований",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )

            WhiteDiagnosticCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (storageInfo.type == StorageType.SAF) {
                                "Пользовательская папка (SAF)"
                            } else {
                                "Внутреннее хранилище (по умолчанию)"
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = storageInfo.displayPath,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondarySlate,
                                softWrap = true
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Использовано: ${formatBytes(totalBytes)}   Файлов: $filesCount   Исследований: $studiesCount",
                            fontSize = 12.sp,
                            color = TextSecondarySlate,
                            softWrap = true
                        )
                        storageInfo.retainedIncoming.warningMessage?.let { warning ->
                            Text(text = warning, color = ErrorRed, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        if (storageInfo.type == StorageType.SAF) {
                            Text(
                                text = "Постоянный доступ через Storage Access Framework без MANAGE_EXTERNAL_STORAGE",
                                fontSize = 11.sp,
                                color = SuccessGreen,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = InputBackgroundGray,
                        border = BorderStroke(1.dp, CardBorderGray)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (storageInfo.type == StorageType.SAF) Icons.Filled.FolderOpen else Icons.Outlined.Folder,
                                contentDescription = "Папка хранения",
                                tint = if (storageInfo.type == StorageType.SAF) PrimaryBlue else TextSecondarySlate
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (storageInfo.retainedIncoming.filesCount > 0) {
                    TextButton(onClick = onRecoverIncoming, enabled = !isMigrating) { Text("Повторить сохранение входящих файлов") }
                }
                Text("Повторные передачи", fontWeight = FontWeight.SemiBold)
                ru.medeng.dicomtool.data.storage.IncomingDuplicatePolicy.entries.forEach { policy ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = !isMigrating) { onReceptionOptionsChange(receptionOptions.copy(duplicates = policy)) }) {
                        androidx.compose.material3.RadioButton(selected = receptionOptions.duplicates == policy,
                            enabled = !isMigrating, onClick = { onReceptionOptionsChange(receptionOptions.copy(duplicates = policy)) })
                        Text(policy.label, fontSize = 12.sp)
                    }
                }
                Text("При переносе и восстановлении точные повторы объединяются в одну проверенную копию; разные данные сохраняются отдельно.", fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = receptionOptions.patientFolderNames, enabled = !isMigrating,
                        onCheckedChange = { onReceptionOptionsChange(receptionOptions.copy(patientFolderNames = it)) })
                    Text("ФИО + UID для папок новых исследований", fontSize = 12.sp)
                }
                Text("ФИО в имени папки содержит персональные данные. Старые папки не переименовываются; продолжение исследования сохраняется в существующую папку.", fontSize = 11.sp)
                // Кнопки управления хранилищем
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { safFolderPicker.launch(null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PrimaryBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Выбрать папку", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (storageInfo.type == StorageType.SAF) {
                        OutlinedButton(
                            onClick = onResetToInternalStorage,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CardBorderGray),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondarySlate)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("По умолчанию", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Перенос существующих исследований
                if (filesCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmMigrateStudies = true },
                        enabled = !isMigrating,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CardBorderGray)
                    ) {
                        if (isMigrating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = PrimaryBlue,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (storageInfo.type == StorageType.SAF) {
                                "Перенести файлы из внутренней памяти в SAF"
                            } else {
                                "Перенести файлы в активную директорию"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                }

                if (!migrationStatusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = migrationStatusMessage,
                        fontSize = 12.sp,
                        color = if (migrationStatusMessage.contains("Ошибок")) ErrorRed else PrimaryBlue,
                        lineHeight = 16.sp
                    )
                }
            }

            // Раздел 2: Действия очистки данных
            Text(
                text = "Очистка данных",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { confirmClearStudies = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DangerActionSoftBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DangerActionSoftBg,
                        contentColor = ErrorRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Удалить все принятые исследования",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Удаляются файлы исследований. Пустые SAF-папки могут остаться для защиты сторонних документов. Журнал и оставшиеся входящие данные не удаляются.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = TextSecondarySlate,
                    softWrap = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { confirmClearLogs = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DangerActionSoftBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DangerActionSoftBg,
                        contentColor = ErrorRed
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Очистить журнал",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Удаляются все записи из журнала DICOM ($logsCount зап.). Это действие нельзя отменить.",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = TextSecondarySlate,
                    softWrap = true
                )
            }

            // Раздел 3: Информация о приложении
            Text(
                text = "Информация",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                modifier = Modifier.padding(top = 4.dp)
            )

            WhiteDiagnosticCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAboutDialog = true }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "О приложении DICOM Kit",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimaryDark
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextMutedSlate
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}
