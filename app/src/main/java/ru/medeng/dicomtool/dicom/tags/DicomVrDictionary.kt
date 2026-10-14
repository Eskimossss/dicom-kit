package ru.medeng.dicomtool.dicom.tags

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ru.medeng.dicomtool.ui.theme.CardBorderGray
import ru.medeng.dicomtool.ui.theme.CardSurfaceWhite
import ru.medeng.dicomtool.ui.theme.InputBackgroundGray
import ru.medeng.dicomtool.ui.theme.PrimaryBlue
import ru.medeng.dicomtool.ui.theme.PrimaryBlueLight
import ru.medeng.dicomtool.ui.theme.TextMutedSlate
import ru.medeng.dicomtool.ui.theme.TextPrimaryDark
import ru.medeng.dicomtool.ui.theme.TextSecondarySlate
import java.util.Locale

/**
 * Описание типа данных DICOM VR (Value Representation) по стандарту DICOM PS3.5 (Раздел 6.2).
 */
data class DicomVrDefinition(
    val code: String,
    val nameEn: String,
    val descriptionRu: String,
    val shortSummaryRu: String
)

/**
 * Полный офлайн-справочник всех 33 стандартных типов данных DICOM VR согласно стандарту DICOM PS3.5 Table 6.2-1.
 */
object DicomVrDictionary {

    val allVrs: List<DicomVrDefinition> = listOf(
        DicomVrDefinition(
            code = "AE",
            nameEn = "Application Entity",
            descriptionRu = "Имя сущности приложения. Строка идентификатора DICOM-узла длиной до 16 байт (без начальных и конечных пробелов).",
            shortSummaryRu = "Имя сущности приложения (AE Title)"
        ),
        DicomVrDefinition(
            code = "AS",
            nameEn = "Age String",
            descriptionRu = "Строка возраста пациента в формате nnnD (дни), nnnW (недели), nnnM (месяцы), nnnY (годы). Фиксированная длина 4 байта.",
            shortSummaryRu = "Строка возраста пациента"
        ),
        DicomVrDefinition(
            code = "AT",
            nameEn = "Attribute Tag",
            descriptionRu = "Тег атрибута DICOM. 16-битные целые числа группы и элемента тега (ровно 4 байта в прямом или обратном порядке байт).",
            shortSummaryRu = "Тег атрибута DICOM"
        ),
        DicomVrDefinition(
            code = "CS",
            nameEn = "Code String",
            descriptionRu = "Кодовая строка. Текстовый идентификатор или ключевое слово до 16 символов (заглавные буквы, цифры, пробел, подчеркивание).",
            shortSummaryRu = "Кодовая строка"
        ),
        DicomVrDefinition(
            code = "DA",
            nameEn = "Date",
            descriptionRu = "Дата в формате YYYYMMDD (8 байт: 4 цифры года, 2 цифры месяца, 2 цифры дня). Разделители не используются.",
            shortSummaryRu = "Дата (ГГГГММДД)"
        ),
        DicomVrDefinition(
            code = "DS",
            nameEn = "Decimal String",
            descriptionRu = "Десятичное число в текстовом виде. До 16 байт (символы +, -, E, e, цифры и десятичная точка).",
            shortSummaryRu = "Десятичное число в тексте"
        ),
        DicomVrDefinition(
            code = "DT",
            nameEn = "Date Time",
            descriptionRu = "Дата и время в формате YYYYMMDDHHMMSS.FFFFFF&ZZXX (до 26 байт с поддержкой микросекунд и часового пояса).",
            shortSummaryRu = "Дата и время"
        ),
        DicomVrDefinition(
            code = "FD",
            nameEn = "Floating Point Double",
            descriptionRu = "Число с плавающей точкой двойной точности IEEE 754 (8 байт, 64-битное вещественное число).",
            shortSummaryRu = "Вещественное число двойной точности (64 бит)"
        ),
        DicomVrDefinition(
            code = "FL",
            nameEn = "Floating Point Single",
            descriptionRu = "Число с плавающей точкой одинарной точности IEEE 754 (4 байта, 32-битное вещественное число).",
            shortSummaryRu = "Вещественное число (32 бит)"
        ),
        DicomVrDefinition(
            code = "IS",
            nameEn = "Integer String",
            descriptionRu = "Целое число в текстовом представлении со знаком или без него (до 12 байт, диапазон от -2^31 до +2^31-1).",
            shortSummaryRu = "Целое число в тексте"
        ),
        DicomVrDefinition(
            code = "LO",
            nameEn = "Long String",
            descriptionRu = "Длинная символьная строка до 64 символов (поддерживает расширенные кодировки, без управляющих символов кроме CR, LF, FF, TAB).",
            shortSummaryRu = "Длинная текстовая строка"
        ),
        DicomVrDefinition(
            code = "LT",
            nameEn = "Long Text",
            descriptionRu = "Длинный текст до 10 240 символов с возможностью форматирования и разбивки на строки и абзацы.",
            shortSummaryRu = "Длинный текст"
        ),
        DicomVrDefinition(
            code = "OB",
            nameEn = "Other Byte",
            descriptionRu = "Массив произвольных байтов или сжатых пиксельных данных, не интерпретируемый парсером по отдельности.",
            shortSummaryRu = "Массив байтов (бинарные данные)"
        ),
        DicomVrDefinition(
            code = "OD",
            nameEn = "Other Double",
            descriptionRu = "Массив 64-битных чисел с плавающей точкой IEEE 754 (длина массива кратна 8 байтам).",
            shortSummaryRu = "Массив вещественных чисел (64 бит)"
        ),
        DicomVrDefinition(
            code = "OF",
            nameEn = "Other Float",
            descriptionRu = "Массив 32-битных чисел с плавающей точкой IEEE 754 (длина массива кратна 4 байтам).",
            shortSummaryRu = "Массив вещественных чисел (32 бит)"
        ),
        DicomVrDefinition(
            code = "OL",
            nameEn = "Other Long",
            descriptionRu = "Массив 32-битных беззнаковых целых чисел (длина массива кратна 4 байтам).",
            shortSummaryRu = "Массив 32-битных целых чисел"
        ),
        DicomVrDefinition(
            code = "OV",
            nameEn = "Other 64-bit Very Long",
            descriptionRu = "Массив 64-битных целых чисел без знака (длина массива кратна 8 байтам). Добавлен в DICOM 2021c.",
            shortSummaryRu = "Массив 64-битных целых чисел"
        ),
        DicomVrDefinition(
            code = "OW",
            nameEn = "Other Word",
            descriptionRu = "Массив 16-битных машинных слов (длина массива кратна 2 байтам, применяется для несжатых 16-битных пикселей).",
            shortSummaryRu = "Массив 16-битных слов"
        ),
        DicomVrDefinition(
            code = "PN",
            nameEn = "Person Name",
            descriptionRu = "Имя человека с разделителями компонентов '^' (Фамилия^Имя^Отчество^Префикс^Суффикс) и кодировочных групп через '='.",
            shortSummaryRu = "Имя человека"
        ),
        DicomVrDefinition(
            code = "SH",
            nameEn = "Short String",
            descriptionRu = "Короткая символьная строка до 16 символов (поддерживает расширенные кодировки, без управляющих символов).",
            shortSummaryRu = "Короткая текстовая строка"
        ),
        DicomVrDefinition(
            code = "SL",
            nameEn = "Signed Long",
            descriptionRu = "32-битное знаковое целое число в дополнительном коде (4 байта, от -2 147 483 648 до +2 147 483 647).",
            shortSummaryRu = "Знаковое целое число (32 бит)"
        ),
        DicomVrDefinition(
            code = "SQ",
            nameEn = "Sequence of Items",
            descriptionRu = "Последовательность из нуля или более вложенных наборов данных (Dataset Items), образующих древовидную структуру.",
            shortSummaryRu = "Последовательность вложенных элементов"
        ),
        DicomVrDefinition(
            code = "SS",
            nameEn = "Signed Short",
            descriptionRu = "16-битное знаковое целое число в дополнительном коде (2 байта, диапазон от -32 768 до +32 767).",
            shortSummaryRu = "Знаковое целое число (16 бит)"
        ),
        DicomVrDefinition(
            code = "ST",
            nameEn = "Short Text",
            descriptionRu = "Короткий форматированный текст до 1024 символов с поддержкой переносов строк.",
            shortSummaryRu = "Короткий текст"
        ),
        DicomVrDefinition(
            code = "SV",
            nameEn = "Signed 64-bit Very Long",
            descriptionRu = "64-битное знаковое целое число в дополнительном коде (8 байт, от -2^63 до 2^63-1). Добавлен в DICOM 2021c.",
            shortSummaryRu = "Знаковое целое число (64 бит)"
        ),
        DicomVrDefinition(
            code = "UC",
            nameEn = "Unlimited Characters",
            descriptionRu = "Текстовая строка неограниченной длины (до 2^32 - 2 символов с поддержкой расширенных кодировок).",
            shortSummaryRu = "Неограниченная строка текста"
        ),
        DicomVrDefinition(
            code = "UI",
            nameEn = "Unique Identifier (UID)",
            descriptionRu = "Уникальный идентификатор DICOM (до 64 байт, только цифры и точки, без лидирующих нулей в компонентах).",
            shortSummaryRu = "Уникальный идентификатор (UID)"
        ),
        DicomVrDefinition(
            code = "UL",
            nameEn = "Unsigned Long",
            descriptionRu = "32-битное целое число без знака (4 байта, диапазон значений от 0 до 4 294 967 295).",
            shortSummaryRu = "Беззнаковое целое число (32 бит)"
        ),
        DicomVrDefinition(
            code = "UN",
            nameEn = "Unknown",
            descriptionRu = "Неизвестный тип данных или байтовая последовательность нестандартного, частного или скрытого атрибута.",
            shortSummaryRu = "Неизвестный тип данных"
        ),
        DicomVrDefinition(
            code = "UR",
            nameEn = "Universal Resource Identifier",
            descriptionRu = "Универсальный идентификатор ресурса (URI или URL длиной до 2^32 - 2 байт).",
            shortSummaryRu = "Универсальный идентификатор (URI/URL)"
        ),
        DicomVrDefinition(
            code = "US",
            nameEn = "Unsigned Short",
            descriptionRu = "16-битное целое число без знака (2 байта, диапазон значений от 0 до 65 535).",
            shortSummaryRu = "Беззнаковое целое число (16 бит)"
        ),
        DicomVrDefinition(
            code = "UT",
            nameEn = "Unlimited Text",
            descriptionRu = "Форматированный текст неограниченной длины (до 2^32 - 2 символов с переносами строк и абзацев).",
            shortSummaryRu = "Текст неограниченной длины"
        ),
        DicomVrDefinition(
            code = "UV",
            nameEn = "Unsigned 64-bit Very Long",
            descriptionRu = "64-битное беззнаковое целое число (8 байт, диапазон значений от 0 до 2^64 - 1). Добавлен в DICOM 2021c.",
            shortSummaryRu = "Беззнаковое целое число (64 бит)"
        )
    )

    private val vrMap = allVrs.associateBy { it.code.uppercase(Locale.ROOT) }

    fun find(code: String?): DicomVrDefinition? {
        if (code.isNullOrBlank()) return null
        return vrMap[code.trim().uppercase(Locale.ROOT)]
    }

    fun getShortDescription(code: String?): String {
        return find(code)?.shortSummaryRu ?: "Тип данных: $code"
    }
}

/**
 * Диалоговое окно справочника всех стандартных типов данных DICOM VR (Value Representation) по PS3.5.
 */
@Composable
fun DicomVrDictionaryDialog(
    highlightedVr: String? = null,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val cleanTarget = highlightedVr?.trim()?.uppercase(Locale.ROOT)

    val filteredList = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            DicomVrDictionary.allVrs
        } else {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            DicomVrDictionary.allVrs.filter { vr ->
                vr.code.lowercase(Locale.ROOT).contains(q) ||
                    vr.nameEn.lowercase(Locale.ROOT).contains(q) ||
                    vr.descriptionRu.lowercase(Locale.ROOT).contains(q) ||
                    vr.shortSummaryRu.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    val listState = rememberLazyListState()

    // Автоматическая прокрутка к выбранному VR при открытии
    LaunchedEffect(cleanTarget) {
        if (!cleanTarget.isNullOrBlank()) {
            val idx = filteredList.indexOfFirst { it.code.equals(cleanTarget, ignoreCase = true) }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 640.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
            border = BorderStroke(1.dp, CardBorderGray),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Заголовок
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Справочник DICOM VR",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Value Representation (DICOM PS3.5)",
                            fontSize = 12.sp,
                            color = TextSecondarySlate
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = TextSecondarySlate
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Поиск по справочнику
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Поиск по коду, названию или описанию...",
                            fontSize = 12.sp,
                            color = TextMutedSlate
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TextMutedSlate,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Очистить",
                                    tint = TextMutedSlate,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBackgroundGray,
                        unfocusedContainerColor = InputBackgroundGray,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = CardBorderGray
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Заголовок таблицы
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(InputBackgroundGray, shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondarySlate,
                        modifier = Modifier.width(42.dp)
                    )
                    Text(
                        text = "Название (EN)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondarySlate,
                        modifier = Modifier.weight(0.38f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Описание (RU)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondarySlate,
                        modifier = Modifier.weight(0.62f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Список типов данных VR
                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ничего не найдено",
                            color = TextSecondarySlate,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(filteredList, key = { it.code }) { vr ->
                            val isHighlighted = cleanTarget != null && vr.code.equals(cleanTarget, ignoreCase = true)
                            val rowBg = if (isHighlighted) PrimaryBlueLight else CardSurfaceWhite
                            val rowBorder = if (isHighlighted) BorderStroke(1.dp, PrimaryBlue) else null

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowBg, shape = RoundedCornerShape(6.dp))
                                    .then(if (rowBorder != null) Modifier.border(rowBorder, RoundedCornerShape(6.dp)) else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isHighlighted) PrimaryBlue else InputBackgroundGray,
                                    modifier = Modifier.width(42.dp)
                                ) {
                                    Text(
                                        text = vr.code,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isHighlighted) CardSurfaceWhite else PrimaryBlue,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(0.38f)) {
                                    Text(
                                        text = vr.nameEn,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimaryDark,
                                        lineHeight = 15.sp,
                                        softWrap = true
                                    )
                                    Text(
                                        text = vr.shortSummaryRu,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        color = PrimaryBlue,
                                        softWrap = true
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = vr.descriptionRu,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = TextSecondarySlate,
                                    softWrap = true,
                                    modifier = Modifier.weight(0.62f)
                                )
                            }
                            HorizontalDivider(color = CardBorderGray.copy(alpha = 0.5f), thickness = 0.6.dp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Закрыть",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                }
            }
        }
    }
}
