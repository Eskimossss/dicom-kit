package ru.medeng.dicomtool.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBorder
import ru.medeng.dicomtool.ui.theme.DangerActionSoftBg
import ru.medeng.dicomtool.ui.theme.ErrorRed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueBorder
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate

/**
 * Верхняя панель приложения "DICOM Tool" с иконкой настроек.
 */
@Composable
fun DicomToolMainTopBar(
    title: String = "DICOM Kit",
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Настройки и очистка данных",
                    tint = TextSecondarySlate
                )
            }
        }
        HorizontalDivider(color = CardBorderGray, thickness = 1.dp)
    }
}

/**
 * Верхняя панель вложенных экранов со стрелкой назад (поддерживает перенос длинных заголовков без обрезания).
 */
@Composable
fun DicomToolSubScreenTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    titleModifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = TextPrimaryDark
                )
            }
            Column(
                modifier = Modifier.weight(1f).then(titleModifier)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark,
                    softWrap = true
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = TextSecondarySlate,
                        softWrap = true
                    )
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
        HorizontalDivider(color = CardBorderGray, thickness = 1.dp)
    }
}

/**
 * Стандартная белая карточка с тонкой серой рамкой из референса ui-light.png.
 */
@Composable
fun WhiteDiagnosticCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = CardSurfaceWhite,
    borderColor: Color = CardBorderGray,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * Информационная плашка в светло-синем стиле.
 */
@Composable
fun Stage1DemoNoticeBanner(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = PrimaryBlueLight,
        border = BorderStroke(1.dp, PrimaryBlueBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = PrimaryBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = TextPrimaryDark,
                softWrap = true
            )
        }
    }
}

/**
 * Поле ввода в светлом диагностическом стиле референса.
 * Поддерживает отображение полных значений без обрезания при переносе строки.
 */
@Composable
fun DiagnosticOutlinedField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimaryDark,
            softWrap = true,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            isError = isError,
            singleLine = false,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardSurfaceWhite,
                unfocusedContainerColor = CardSurfaceWhite,
                disabledContainerColor = Color(0xFFF1F5F9),
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = CardBorderGray,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark,
                disabledTextColor = TextSecondarySlate
            )
        )
    }
}

/**
 * Строка таблицы ключ-значение с переносом длинных значений (UID, путей, описаний) и возможностью выделения текста.
 */
@Composable
fun KeyValueTableRow(
    label: String,
    value: String,
    showDivider: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = TextSecondarySlate,
                softWrap = true,
                modifier = Modifier.weight(0.38f)
            )
            SelectionContainer(modifier = Modifier.weight(0.62f)) {
                Text(
                    text = value,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryDark,
                    softWrap = true
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = CardBorderGray.copy(alpha = 0.7f), thickness = 0.8.dp)
        }
    }
}

@Composable
fun ViewerToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (isActive) PrimaryBlue else CardSurfaceWhite,
        border = BorderStroke(1.dp, if (isActive) PrimaryBlue else CardBorderGray)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (isActive) Color.White else TextPrimaryDark,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) Color.White else TextPrimaryDark
            )
        }
    }
}

@Composable
fun ModalityFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) PrimaryBlueLight else CardSurfaceWhite,
        border = BorderStroke(1.dp, if (selected) PrimaryBlue else CardBorderGray)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) PrimaryBlue else TextSecondarySlate,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}



/**
 * Единая карточка IP-адреса устройства для DICOM SCP и MWL SCP.
 * Гарантирует одинаковую высоту, размер шрифта и отсутствие переноса IP на типичных смартфонах.
 */
@Composable
fun AppDeviceIpCard(
    ipv4: String,
    interfaceName: String,
    onRefresh: () -> Unit,
    refreshContentDescription: String = "Обновить сеть",
    copyLabel: String = "IP",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    WhiteDiagnosticCard(modifier = modifier) {
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
                Spacer(modifier = Modifier.height(3.dp))
                SelectionContainer {
                    Text(
                        text = ipv4,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Интерфейс: $interfaceName",
                        fontSize = 12.sp,
                        color = TextSecondarySlate,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = refreshContentDescription,
                        tint = PrimaryBlue
                    )
                }
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(copyLabel, ipv4))
                        Toast.makeText(context, "Скопировано: $ipv4", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Скопировать",
                        tint = TextSecondarySlate
                    )
                }
            }
        }
    }
}

/**
 * Единый компактный информационный баннер для подсказок и предупреждений.
 */
@Composable
fun AppInfoBanner(
    title: String,
    detailedText: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        color = InputBackgroundGray,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CardBorderGray),
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimaryDark,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (expanded) "Скрыть" else "Инфо",
                    fontSize = 11.sp,
                    color = PrimaryBlue,
                    fontWeight = FontWeight.Bold
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detailedText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextSecondarySlate
                )
            }
        }
    }
}

/**
 * Единый переключатель режимов (сегментированная кнопка) приложения.
 * Выбранный сегмент выделен заполненным синим фоном (PrimaryBlue) и белым текстом.
 */
@Composable
fun <T> AppSegmentedControl(
    items: List<Pair<T, String>>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = InputBackgroundGray,
        border = BorderStroke(1.dp, CardBorderGray)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items.forEach { (item, label) ->
                val selected = item == selectedItem
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onItemSelected(item) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) PrimaryBlue else Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Color.White else TextPrimaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Единая кнопка опасного / необратимого действия с корректным переносом длинных подписей.
 */
@Composable
fun AppDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = Icons.Outlined.DeleteOutline,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DangerActionSoftBorder),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = DangerActionSoftBg,
            contentColor = ErrorRed
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = ErrorRed
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = ErrorRed,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            softWrap = true
        )
    }
}

/**
 * Единый стиль вспомогательного / пояснительного текста.
 */
@Composable
fun AppHelperText(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondarySlate,
        softWrap = true,
        modifier = modifier
    )
}
