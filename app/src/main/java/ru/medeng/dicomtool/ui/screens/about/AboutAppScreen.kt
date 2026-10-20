package ru.medeng.dicomtool.ui.screens.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.BuildConfig
import ru.medeng.dicomtool.ui.components.DicomToolSubScreenTopBar
import ru.medeng.dicomtool.ui.components.WhiteDiagnosticCard
import ru.medeng.dicomtool.ui.screens.settings.ThirdPartyLicensesDialog
import ru.medeng.dicomtool.ui.theme.AppBackgroundGray
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate

/**
 * Экран «О приложении» DICOM Kit.
 *
 * Отображает:
 * - Название: DICOM Kit
 * - Версию приложения (versionName)
 * - Version Code
 * - Тип сборки (Debug / Release)
 * - Git-ветку
 * - Короткий SHA коммита (с пометкой -dirty при наличии незакоммиченных правок)
 * - Дату и время сборки
 * - Кнопку «Копировать информацию о сборке»
 * - Описание программы и ссылку на контакты
 */
@Composable
fun AboutAppScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showLicensesDialog by remember { mutableStateOf(false) }

    val appName = "DICOM Kit"
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE.toString()
    val buildType = BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() }
    val gitBranch = BuildConfig.GIT_BRANCH
    val gitCommit = BuildConfig.GIT_COMMIT_SHA
    val buildTime = BuildConfig.BUILD_TIME

    val buildInfoSummary = buildString {
        appendLine("DICOM Kit — Информация о сборке")
        appendLine("--------------------------------")
        appendLine("Приложение: $appName")
        appendLine("Версия: $versionName")
        appendLine("Version Code: $versionCode")
        appendLine("Тип сборки: $buildType")
        appendLine("Git-ветка: $gitBranch")
        appendLine("Коммит: $gitCommit")
        appendLine("Дата сборки: $buildTime")
    }

    if (showLicensesDialog) {
        ThirdPartyLicensesDialog(
            onDismissRequest = { showLicensesDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackgroundGray)
    ) {
        DicomToolSubScreenTopBar(
            title = "О приложении",
            subtitle = "Версия $versionName ($buildType)",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Карточка 1: Название и назначение
            WhiteDiagnosticCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = appName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Диагностический комплекс DICOM для сервисных инженеров",
                            fontSize = 12.sp,
                            color = TextSecondarySlate,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Карточка 2: Параметры сборки
            WhiteDiagnosticCard {
                Text(
                    text = "Параметры сборки",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(10.dp))

                AboutInfoRow("Название", appName)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Версия", versionName)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Version Code", versionCode)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Тип сборки", buildType)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Git-ветка", gitBranch, isMono = true)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Коммит SHA", gitCommit, isMono = true)
                HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))
                AboutInfoRow("Дата сборки", buildTime)

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("DICOM Kit Build Info", buildInfoSummary)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Информация о сборке скопирована", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Копировать информацию о сборке",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Карточка 3: Функционал и контакты
            WhiteDiagnosticCard {
                Text(
                    text = "О программе",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Автономное мобильное приложение для проверки и наладки медицинского оборудования (рентген, маммографы, флюорографы, КТ, МРТ, УЗИ).\n\nПоддерживаемые сетевые службы:\n• DICOM Storage SCP и SCU (C-ECHO, C-STORE)\n• DICOM Modality Worklist (MWL SCP и SCU, C-FIND)\n• Анализ структуры тегов, просмотр снимков и диагностический журнал.",
                    fontSize = 13.sp,
                    color = TextSecondarySlate,
                    lineHeight = 18.sp
                )

                HorizontalDivider(color = CardBorderGray, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 10.dp))

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
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
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

                HorizontalDivider(color = CardBorderGray, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showLicensesDialog = true }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Лицензии сторонних компонентов",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    isMono: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondarySlate
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimaryDark,
            fontFamily = if (isMono) FontFamily.Monospace else FontFamily.Default
        )
    }
}
