package ru.medeng.dicomtool.ui.screens.tags

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ru.medeng.dicomtool.dicom.tags.DicomVrDictionary
import ru.medeng.dicomtool.dicom.tags.DicomVrDictionaryDialog
import ru.medeng.dicomtool.domain.model.DicomEncodingOverride
import ru.medeng.dicomtool.domain.model.DicomSequenceItem
import ru.medeng.dicomtool.domain.model.DicomTagEntry
import ru.medeng.dicomtool.ui.components.KeyValueTableRow
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextMutedSlate
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import ru.medeng.dicomtool.ui.theme.WarningAmber
import ru.medeng.dicomtool.ui.theme.WarningAmberLight
import java.util.Locale

/**
 * Единый полнофункциональный компонент просмотра DICOM-тегов.
 *
 * Переиспользуется:
 * 1. В новом разделе «DICOM-теги» (5-я вкладка) при анализе файлов из SAF.
 * 2. Во вкладке «DICOM теги» внутри экрана просмотра принятых исследований.
 *
 * Возможности:
 * - Поиск по коду тега (0010,0010), английскому названию, русскому названию, значению.
 * - Фильтр «Только заполненные».
 * - Раскрываемая иерархия последовательностей Sequence (SQ) с аккордеоном элементов [Item #N].
 * - Поддержка вложенного поиска внутри элементов SQ.
 * - Отображение предупреждений о кодировке и ручной выбор альтернативной кодировки.
 * - Диалог подробностей тега с полным значением без обрезания, переносом строк и копированием кода и значения.
 * - Адаптивная верстка, устойчивая к длинным значениям и альбомной ориентации.
 */
@Composable
fun DicomTagsBrowserComponent(
    tags: List<DicomTagEntry>,
    searchQuery: String,
    encodingWarning: String? = null,
    currentEncodingOverride: DicomEncodingOverride = DicomEncodingOverride.AUTO,
    showEncodingSelector: Boolean = false,
    onSearchQueryChange: (String) -> Unit,
    onEncodingOverrideChange: ((DicomEncodingOverride) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var onlyFilled by rememberSaveable { mutableStateOf(false) }
    var selectedTagForDetail by remember { mutableStateOf<DicomTagEntry?>(null) }
    var showEncodingMenu by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            searchFocusRequester.requestFocus()
        }
    }

    // Состояние раскрытых элементов SQ: ключ = "${tagHex}_item_${itemIndex}"
    val expandedSqItems = remember { mutableStateMapOf<String, Boolean>() }

    // Фильтрация тегов с поддержкой поиска по вложенным SQ
    val filteredTags by remember(tags, searchQuery, onlyFilled) {
        derivedStateOf {
            filterTagsHierarchy(tags, searchQuery, onlyFilled)
        }
    }

    if (selectedTagForDetail != null) {
        DicomTagDetailDialog(
            tagEntry = selectedTagForDetail!!,
            onDismiss = { selectedTagForDetail = null }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CardSurfaceWhite)
    ) {
        // 1. Предупреждение о кодировке (если обнаружено несоответствие 0008,0005)
        if (!encodingWarning.isNullOrBlank()) {
            Surface(
                color = WarningAmberLight,
                border = BorderStroke(0.8.dp, WarningAmber),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = encodingWarning,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = TextPrimaryDark,
                        softWrap = true
                    )
                }
            }
        }

        // 2. Раскрытая строка поиска (если развернута)
        if (isSearchExpanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            text = "Поиск: (0010,0010), код, название, значение...",
                            fontSize = 12.sp,
                            color = TextMutedSlate
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearchQueryChange("") },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Очистить поиск",
                                    tint = TextMutedSlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBackgroundGray,
                        unfocusedContainerColor = InputBackgroundGray,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = CardBorderGray
                    )
                )
                IconButton(
                    onClick = { isSearchExpanded = false },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Свернуть поиск",
                        tint = TextSecondarySlate,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 3. Компактная панель инструментов (Фильтры + Кнопка поиска)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = onlyFilled,
                    onClick = { onlyFilled = !onlyFilled },
                    label = {
                        Text(
                            text = "Только заполненные",
                            fontSize = 11.sp,
                            fontWeight = if (onlyFilled) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    leadingIcon = {
                        if (onlyFilled) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryBlueLight,
                        selectedLabelColor = PrimaryBlue,
                        selectedLeadingIconColor = PrimaryBlue,
                        containerColor = CardSurfaceWhite,
                        labelColor = TextSecondarySlate
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = onlyFilled,
                        borderColor = if (onlyFilled) PrimaryBlue else CardBorderGray,
                        selectedBorderColor = PrimaryBlue
                    ),
                    modifier = Modifier.height(30.dp)
                )

                if (showEncodingSelector && onEncodingOverrideChange != null) {
                    Box {
                        OutlinedButton(
                            onClick = { showEncodingMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            border = BorderStroke(1.dp, if (currentEncodingOverride != DicomEncodingOverride.AUTO) PrimaryBlue else CardBorderGray),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = null,
                                tint = if (currentEncodingOverride != DicomEncodingOverride.AUTO) PrimaryBlue else TextSecondarySlate,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (currentEncodingOverride == DicomEncodingOverride.AUTO) "Кодировка" else currentEncodingOverride.labelRu.take(10) + "…",
                                fontSize = 11.sp,
                                color = if (currentEncodingOverride != DicomEncodingOverride.AUTO) PrimaryBlue else TextPrimaryDark,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        DropdownMenu(
                            expanded = showEncodingMenu,
                            onDismissRequest = { showEncodingMenu = false },
                            modifier = Modifier.background(CardSurfaceWhite)
                        ) {
                            DicomEncodingOverride.entries.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = opt.labelRu,
                                            fontSize = 13.sp,
                                            fontWeight = if (opt == currentEncodingOverride) FontWeight.Bold else FontWeight.Normal,
                                            color = if (opt == currentEncodingOverride) PrimaryBlue else TextPrimaryDark
                                        )
                                    },
                                    onClick = {
                                        showEncodingMenu = false
                                        onEncodingOverrideChange(opt)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Иконка лупы для раскрытия поиска (если поиск свернут)
            if (!isSearchExpanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searchQuery.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PrimaryBlueLight,
                            border = BorderStroke(1.dp, PrimaryBlue),
                            modifier = Modifier
                                .clickable { isSearchExpanded = true }
                                .padding(end = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = searchQuery.take(10) + if (searchQuery.length > 10) "…" else "",
                                    fontSize = 11.sp,
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Очистить поиск",
                                    tint = PrimaryBlue,
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clickable { onSearchQueryChange("") }
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { isSearchExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (searchQuery.isNotBlank()) PrimaryBlueLight else InputBackgroundGray,
                            border = BorderStroke(1.dp, if (searchQuery.isNotBlank()) PrimaryBlue else CardBorderGray),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Поиск",
                                    tint = if (searchQuery.isNotBlank()) PrimaryBlue else TextSecondarySlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Заголовок таблицы
        DicomTagsTableHeader()
        HorizontalDivider(color = CardBorderGray, thickness = 1.dp)

        // 5. Список тегов с иерархией SQ
        if (filteredTags.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isNotBlank() || onlyFilled) "Теги, соответствующие условиям, не найдены" else "Нет доступных DICOM-тегов",
                    color = TextSecondarySlate,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredTags, key = { "${it.tagHex}_${it.name}_${it.depth}" }) { tagEntry ->
                    DicomTagRowItem(
                        tag = tagEntry,
                        isExpanded = expandedSqItems["${tagEntry.tagHex}_root"] ?: false,
                        onToggleExpand = {
                            val key = "${tagEntry.tagHex}_root"
                            expandedSqItems[key] = !(expandedSqItems[key] ?: false)
                        },
                        expandedSqItems = expandedSqItems,
                        onToggleSqItem = { itemKey ->
                            expandedSqItems[itemKey] = !(expandedSqItems[itemKey] ?: false)
                        },
                        onClick = { selectedTagForDetail = tagEntry }
                    )
                    HorizontalDivider(color = CardBorderGray.copy(alpha = 0.5f), thickness = 0.6.dp)
                }
            }
        }
    }
}

@Composable
private fun DicomTagsTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBackgroundGray)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Тег / VR",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondarySlate,
            modifier = Modifier.weight(0.26f)
        )
        Text(
            text = "Название (EN / RU)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondarySlate,
            modifier = Modifier.weight(0.36f)
        )
        Text(
            text = "Значение (нажмите для деталей)",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondarySlate,
            modifier = Modifier.weight(0.38f)
        )
    }
}

@Composable
private fun DicomTagRowItem(
    tag: DicomTagEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    expandedSqItems: Map<String, Boolean>,
    onToggleSqItem: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Колонка 1: Код тега + VR
            Column(modifier = Modifier.weight(0.26f)) {
                Text(
                    text = tag.tagHex,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tag.isPrivate) Color(0xFFB45309) else TextSecondarySlate
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tag.vr,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue
                    )
                    if (tag.isPrivate) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PRIV",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB45309)
                        )
                    }
                }
            }

            // Колонка 2: Английское и русское название
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .padding(end = 6.dp)
            ) {
                Text(
                    text = tag.name,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimaryDark,
                    softWrap = true
                )
                if (!tag.nameRu.isNullOrBlank()) {
                    Text(
                        text = tag.nameRu,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = PrimaryBlue,
                        softWrap = true
                    )
                }
            }

            // Колонка 3: Значение тега
            Column(modifier = Modifier.weight(0.38f)) {
                if (tag.isSequence) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleExpand() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (tag.sequenceItems.isEmpty()) "Пустая последовательность (0 эл.)" else "Последовательность (${tag.sequenceItems.size} эл.)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            softWrap = true
                        )
                    }
                } else {
                    val displayValue = if (tag.value.isBlank()) "(пусто)" else tag.value
                    Text(
                        text = displayValue,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (tag.value.isBlank()) FontWeight.Normal else FontWeight.Medium,
                        color = if (tag.value.isBlank()) TextMutedSlate else TextPrimaryDark,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true
                    )
                }
            }
        }

        // Если это последовательность SQ и пользователь её развернул:
        if (tag.isSequence && tag.sequenceItems.isNotEmpty()) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 6.dp)
                ) {
                    tag.sequenceItems.forEach { seqItem ->
                        val itemKey = "${tag.tagHex}_item_${seqItem.itemIndex}"
                        val isItemOpen = expandedSqItems[itemKey] ?: true

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = InputBackgroundGray,
                            border = BorderStroke(0.6.dp, CardBorderGray),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleSqItem(itemKey) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isItemOpen) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = TextSecondarySlate,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Элемент #${seqItem.itemIndex} (${seqItem.tags.size} тегов)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryDark
                                    )
                                }

                                if (isItemOpen) {
                                    HorizontalDivider(color = CardBorderGray, thickness = 0.5.dp)
                                    Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                                        seqItem.tags.forEach { subTag ->
                                            NestedDicomTagRow(
                                                tag = subTag,
                                                onClick = { onClick() }
                                            )
                                            HorizontalDivider(color = CardBorderGray.copy(alpha = 0.4f), thickness = 0.5.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NestedDicomTagRow(
    tag: DicomTagEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(0.28f)) {
            Text(
                text = tag.tagHex,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondarySlate
            )
            Text(
                text = tag.vr,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue
            )
        }
        Column(
            modifier = Modifier
                .weight(0.36f)
                .padding(end = 4.dp)
        ) {
            Text(
                text = tag.name,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = TextPrimaryDark,
                softWrap = true
            )
            if (!tag.nameRu.isNullOrBlank()) {
                Text(
                    text = tag.nameRu,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = PrimaryBlue,
                    softWrap = true
                )
            }
        }
        Text(
            text = tag.value.ifBlank { "(пусто)" },
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = if (tag.value.isBlank()) TextMutedSlate else TextPrimaryDark,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            modifier = Modifier.weight(0.36f)
        )
    }
}

/**
 * Подробная карточка DICOM-тега с полным значением без обрезания,
 * поддержкой скролла, переноса строк и копирования кода и значения.
 */
@Composable
fun DicomTagDetailDialog(
    tagEntry: DicomTagEntry,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showVrDictionary by remember { mutableStateOf(false) }

    if (showVrDictionary) {
        DicomVrDictionaryDialog(
            highlightedVr = tagEntry.vr,
            onDismiss = { showVrDictionary = false }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 580.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
            border = BorderStroke(1.dp, CardBorderGray),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Подробности DICOM-тега",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PrimaryBlueLight,
                        border = BorderStroke(1.dp, PrimaryBlue),
                        modifier = Modifier.clickable { showVrDictionary = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "VR: ${tagEntry.vr}",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Справочник VR",
                                tint = PrimaryBlue,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = CardBorderGray, thickness = 1.dp)

                KeyValueTableRow(
                    label = "Номер тега:",
                    value = tagEntry.tagHex,
                    showDivider = true
                )
                KeyValueTableRow(
                    label = "Тип данных (VR):",
                    value = "${tagEntry.vr} — ${DicomVrDictionary.getShortDescription(tagEntry.vr)}",
                    showDivider = true
                )
                KeyValueTableRow(
                    label = "Название (EN):",
                    value = tagEntry.name,
                    showDivider = true
                )
                if (!tagEntry.nameRu.isNullOrBlank()) {
                    KeyValueTableRow(
                        label = "Описание (RU):",
                        value = tagEntry.nameRu,
                        showDivider = true
                    )
                }

                Text(
                    text = "Полное значение атрибута:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondarySlate
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 80.dp, max = 240.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = InputBackgroundGray,
                    border = BorderStroke(1.dp, CardBorderGray)
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = tagEntry.value.ifBlank { "(пустое значение)" },
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimaryDark,
                                softWrap = true
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                copyToClipboard(context, "DICOM Tag Code", tagEntry.tagHex)
                                Toast.makeText(context, "Код тега ${tagEntry.tagHex} скопирован", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = InputBackgroundGray, contentColor = TextPrimaryDark),
                            border = BorderStroke(1.dp, CardBorderGray)
                        ) {
                            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Код", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                copyToClipboard(context, "DICOM Tag Value", tagEntry.value)
                                Toast.makeText(context, "Значение тега скопировано", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, contentColor = Color.White)
                        ) {
                            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Значение", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    TextButton(onClick = onDismiss) {
                        Text("Закрыть", fontSize = 14.sp, color = TextSecondarySlate, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * Фильтрует плоские и вложенные теги по поисковой строке и признаку заполненности.
 */
private fun filterTagsHierarchy(
    tags: List<DicomTagEntry>,
    query: String,
    onlyFilled: Boolean
): List<DicomTagEntry> {
    val cleanQuery = query.trim().uppercase(Locale.ROOT)
        .replace("(", "")
        .replace(")", "")
        .replace(" ", "")

    return tags.mapNotNull { tag ->
        if (tag.isSequence) {
            // Для последовательностей проверяем совпадение самого тега SQ либо вложенных тегов
            val matchesSelf = matchesSearch(tag, cleanQuery)
            val filteredItems = tag.sequenceItems.mapNotNull { item ->
                val matchingSubTags = filterTagsHierarchy(item.tags, query, onlyFilled)
                if (matchesSelf || matchingSubTags.isNotEmpty()) {
                    item.copy(tags = if (cleanQuery.isBlank() && !onlyFilled) item.tags else matchingSubTags)
                } else null
            }

            if (matchesSelf || filteredItems.isNotEmpty()) {
                tag.copy(sequenceItems = filteredItems)
            } else null
        } else {
            if (onlyFilled && tag.value.isBlank()) return@mapNotNull null
            if (cleanQuery.isBlank() || matchesSearch(tag, cleanQuery)) tag else null
        }
    }
}

private fun matchesSearch(tag: DicomTagEntry, cleanQuery: String): Boolean {
    if (cleanQuery.isBlank()) return true

    val cleanHex = tag.tagHex.replace("(", "").replace(")", "").replace(",", "").replace(" ", "").uppercase(Locale.ROOT)
    val cleanHexWithComma = tag.tagHex.replace("(", "").replace(")", "").replace(" ", "").uppercase(Locale.ROOT)

    if (cleanHex.contains(cleanQuery) || cleanHexWithComma.contains(cleanQuery)) return true
    if (tag.vr.uppercase(Locale.ROOT).contains(cleanQuery)) return true
    if (tag.name.uppercase(Locale.ROOT).contains(cleanQuery)) return true
    if (tag.nameRu?.uppercase(Locale.ROOT)?.contains(cleanQuery) == true) return true
    if (tag.value.uppercase(Locale.ROOT).contains(cleanQuery)) return true

    return false
}
