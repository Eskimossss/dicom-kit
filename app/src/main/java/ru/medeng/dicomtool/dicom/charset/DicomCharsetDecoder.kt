package ru.medeng.dicomtool.dicom.charset

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.SpecificCharacterSet
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Результат декодирования текстового DICOM-атрибута с диагностикой кодировки.
 */
data class DecodedDicomText(
    val value: String,
    val diagnosticWarning: String? = null
)

/**
 * Диагностический отчёт о кодировке DICOM-набора данных (0008,0005) Specific Character Set.
 */
data class DicomCharsetDiagnostics(
    val declaredTerms: List<String>,
    val effectiveCharsetDescription: String,
    val hasEncodingWarning: Boolean,
    val warningMessage: String? = null
)

/**
 * Декодер текстовых DICOM-атрибутов (PS3.5 Section 6.1 / 6.2) с поддержкой:
 * - ISO_IR 6 (Default ASCII)
 * - ISO_IR 100 (ISO-8859-1, Latin-1)
 * - ISO_IR 101 / 109 / 110 / 126 / 127 / 138 / 148 / 166
 * - ISO_IR 144 (ISO-8859-5, Кириллица)
 * - ISO_IR 192 (UTF-8)
 * - Многозначных Specific Character Set и ISO 2022 (ISO 2022 IR 6, ISO 2022 IR 100, ISO 2022 IR 144 и др.)
 * - DICOM Person Name (VR.PN) с сохранением групп представления (=) и компонентов (^)
 * - Диагностики повреждённых/несоответствующих кодировок в исходных DICOM-файлах без изменения самих файлов.
 */
object DicomCharsetDecoder {

    private val CHARSET_WIN1251: Charset = runCatching { Charset.forName("windows-1251") }
        .getOrElse { StandardCharsets.ISO_8859_1 }

    private val CHARSET_ISO_8859_5: Charset = runCatching { Charset.forName("ISO-8859-5") }
        .getOrElse { StandardCharsets.ISO_8859_1 }

    private val CHARSET_KOI8_R: Charset = runCatching { Charset.forName("KOI8-R") }
        .getOrElse { StandardCharsets.ISO_8859_1 }

    /**
     * VR, на которые распространяется действие (0008,0005) Specific Character Set согласно DICOM PS3.5 Section 6.1.2.3.
     */
    private val TEXT_VRS_AFFECTED_BY_CHARSET = setOf(
        VR.LO, VR.LT, VR.PN, VR.SH, VR.ST, VR.UC, VR.UT
    )

    fun isCharsetAffectedVr(vr: VR?): Boolean = vr in TEXT_VRS_AFFECTED_BY_CHARSET

    /**
     * Извлекает список объявленных значений (0008,0005) Specific Character Set с учётом иерархии родительских Attributes (для SQ).
     */
    fun getDeclaredSpecificCharacterSets(attrs: Attributes): List<String> {
        var current: Attributes? = attrs
        while (current != null) {
            val cur = current
            if (cur.contains(Tag.SpecificCharacterSet)) {
                val rawBytes = runCatching { cur.getBytes(Tag.SpecificCharacterSet) }.getOrNull()
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    val rawAscii = String(rawBytes, StandardCharsets.US_ASCII)
                        .trim { it <= ' ' || it == '\u0000' }
                    if (rawAscii.isEmpty()) return emptyList()
                    return rawAscii.split('\\').map { it.trim() }
                }
                val strings = cur.getStrings(Tag.SpecificCharacterSet)
                if (!strings.isNullOrEmpty()) {
                    return strings.map { it.trim() }.filter { it.isNotEmpty() }
                }
                return emptyList()
            }
            current = cur.parent
        }
        return emptyList()
    }

    /**
     * Нормализует имя кодировки DICOM к стандартному термину DICOM PS3.3 C.12.1.1.2.
     */
    fun normalizeDicomCharsetTerm(term: String): String {
        val cleaned = term.trim().uppercase(Locale.ROOT)
        if (cleaned.isEmpty()) return ""
        return when (cleaned.replace('-', '_').replace(Regex("\\s+"), " ")) {
            "ISO_IR 6", "ISO_IR_6", "ISO 646", "ASCII", "US_ASCII" -> "ISO_IR 6"
            "ISO_IR 100", "ISO_IR_100", "ISO_8859_1", "LATIN1", "ISO 8859_1" -> "ISO_IR 100"
            "ISO_IR 101", "ISO_IR_101", "ISO_8859_2" -> "ISO_IR 101"
            "ISO_IR 109", "ISO_IR_109", "ISO_8859_3" -> "ISO_IR 109"
            "ISO_IR 110", "ISO_IR_110", "ISO_8859_4" -> "ISO_IR 110"
            "ISO_IR 144", "ISO_IR_144", "ISO_8859_5", "ISO 8859_5", "CYRILLIC" -> "ISO_IR 144"
            "ISO_IR 127", "ISO_IR_127", "ISO_8859_6" -> "ISO_IR 127"
            "ISO_IR 126", "ISO_IR_126", "ISO_8859_7" -> "ISO_IR 126"
            "ISO_IR 138", "ISO_IR_138", "ISO_8859_8" -> "ISO_IR 138"
            "ISO_IR 148", "ISO_IR_148", "ISO_8859_9" -> "ISO_IR 148"
            "ISO_IR 166", "ISO_IR_166", "TIS_620" -> "ISO_IR 166"
            "ISO_IR 192", "ISO_IR_192", "UTF_8", "UTF8" -> "ISO_IR 192"
            "GB18030" -> "GB18030"
            "GBK" -> "GBK"
            "ISO 2022 IR 6", "ISO_2022_IR_6" -> "ISO 2022 IR 6"
            "ISO 2022 IR 100", "ISO_2022_IR_100" -> "ISO 2022 IR 100"
            "ISO 2022 IR 101", "ISO_2022_IR_101" -> "ISO 2022 IR 101"
            "ISO 2022 IR 109", "ISO_2022_IR_109" -> "ISO 2022 IR 109"
            "ISO 2022 IR 110", "ISO_2022_IR_110" -> "ISO 2022 IR 110"
            "ISO 2022 IR 144", "ISO_2022_IR_144" -> "ISO 2022 IR 144"
            "ISO 2022 IR 127", "ISO_2022_IR_127" -> "ISO 2022 IR 127"
            "ISO 2022 IR 126", "ISO_2022_IR_126" -> "ISO 2022 IR 126"
            "ISO 2022 IR 138", "ISO_2022_IR_138" -> "ISO 2022 IR 138"
            "ISO 2022 IR 148", "ISO_2022_IR_148" -> "ISO 2022 IR 148"
            "ISO 2022 IR 13", "ISO_2022_IR_13" -> "ISO 2022 IR 13"
            "ISO 2022 IR 166", "ISO_2022_IR_166" -> "ISO 2022 IR 166"
            "ISO 2022 IR 87", "ISO_2022_IR_87" -> "ISO 2022 IR 87"
            "ISO 2022 IR 159", "ISO_2022_IR_159" -> "ISO 2022 IR 159"
            "ISO 2022 IR 149", "ISO_2022_IR_149" -> "ISO 2022 IR 149"
            "ISO 2022 IR 58", "ISO_2022_IR_58" -> "ISO 2022 IR 58"
            else -> term.trim()
        }
    }

    private fun isNonStandardWindows1251Term(term: String): Boolean {
        val u = term.trim().uppercase(Locale.ROOT).replace('-', '_')
        return u in setOf("WINDOWS_1251", "CP1251", "WIN1251", "CP_1251", "ANSI_1251", "1251")
    }

    private fun isNonStandardKoi8RTerm(term: String): Boolean {
        val u = term.trim().uppercase(Locale.ROOT).replace('-', '_')
        return u in setOf("KOI8_R", "KOI8R", "KOI_8_R")
    }

    /**
     * Декодирует строковое значение тега с соблюдением (0008,0005) Specific Character Set
     * и диагностикой некорректно закодированных файлов.
     */
    fun decodeStringAttribute(
        attrs: Attributes,
        tag: Int,
        vr: VR = attrs.getVR(tag) ?: VR.LO,
        defaultVal: String = ""
    ): DecodedDicomText {
        if (!attrs.contains(tag)) {
            return DecodedDicomText(defaultVal, null)
        }

        val rawBytes = runCatching { attrs.getBytes(tag) }.getOrNull()
        if (rawBytes != null) {
            val trimmedBytes = trimDicomPadding(rawBytes, vr)
            if (trimmedBytes.isEmpty()) {
                return DecodedDicomText(defaultVal, null)
            }
            return decodeBytesWithDiagnostics(
                bytes = trimmedBytes,
                vr = vr,
                declaredTerms = getDeclaredSpecificCharacterSets(attrs)
            )
        }

        // Fallback для Attributes, созданных в памяти без byte[] (например, через setString в тестах)
        val strings = runCatching { attrs.getStrings(tag) }.getOrNull()
        if (!strings.isNullOrEmpty()) {
            val joined = strings.joinToString("\\").trim()
            return DecodedDicomText(joined.ifEmpty { defaultVal }, null)
        }

        return DecodedDicomText(defaultVal, null)
    }

    /**
     * Декодирует массив байт текстового тега согласно (0008,0005) Specific Character Set.
     * - Не применяет UTF-8 принудительно ко всем файлам.
     * - Корректно обрабатывает ISO_IR 6, ISO_IR 100, ISO_IR 144, ISO_IR 192 и многозначные ISO 2022.
     * - Если исходные байты нарушают заявленную кодировку или содержат не-ASCII байты при отсутствии (0008,0005),
     *   формирует явное диагностическое предупреждение.
     */
    fun decodeBytesWithDiagnostics(
        bytes: ByteArray,
        vr: VR,
        declaredTerms: List<String>
    ): DecodedDicomText {
        if (bytes.isEmpty()) return DecodedDicomText("", null)

        // Для нетекстовых VR (UI, DA, TM, IS, DS, CS, AE, AS) всегда используется ASCII (ISO_IR 6)
        if (!isCharsetAffectedVr(vr) && vr != VR.CS && vr != VR.SH && vr != VR.LO && vr != VR.PN) {
            val asciiText = String(bytes, StandardCharsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            return DecodedDicomText(asciiText, null)
        }

        val hasHighBytes = bytes.any { (it.toInt() and 0x80) != 0 }
        val hasIso2022Escape = bytes.any { it.toInt() == 0x1B }

        val nonBlankTerms = declaredTerms.filter { it.isNotBlank() }
        val normalizedTerms = nonBlankTerms.map { normalizeDicomCharsetTerm(it) }

        // 1. Проверка нестандартных объявлений кодировки в (0008,0005), встречающихся на медоборудовании СНГ
        if (nonBlankTerms.any { isNonStandardWindows1251Term(it) }) {
            val decoded = decodePersonNameOrText(bytes, vr, CHARSET_WIN1251)
            return DecodedDicomText(
                value = decoded,
                diagnosticWarning = "В теге (0008,0005) указано нестандартное значение '${nonBlankTerms.joinToString("\\")}'. Текст декодирован как Windows-1251."
            )
        }
        if (nonBlankTerms.any { isNonStandardKoi8RTerm(it) }) {
            val decoded = decodePersonNameOrText(bytes, vr, CHARSET_KOI8_R)
            return DecodedDicomText(
                value = decoded,
                diagnosticWarning = "В теге (0008,0005) указано нестандартное значение '${nonBlankTerms.joinToString("\\")}'. Текст декодирован как KOI8-R."
            )
        }

        // Проверяем, было ли имя кодировки записано с отклонением от формата DICOM (например "UTF-8" вместо "ISO_IR 192")
        val hasSyntaxDeviation = nonBlankTerms.zip(normalizedTerms).any { (raw, norm) ->
            !raw.equals(norm, ignoreCase = false)
        }
        val syntaxDeviationNote = if (hasSyntaxDeviation) {
            "В (0008,0005) указано '${nonBlankTerms.joinToString("\\")}' вместо стандартного термина DICOM '${normalizedTerms.joinToString("\\")}'."
        } else {
            null
        }

        // 2. Если есть escape-последовательности ISO 2022 (0x1B) или многозначный (0008,0005)
        if (hasIso2022Escape || normalizedTerms.size > 1 || normalizedTerms.any { it.startsWith("ISO 2022") }) {
            val dcmTerms = if (declaredTerms.isEmpty()) {
                arrayOf("ISO 2022 IR 6")
            } else {
                declaredTerms.map { normalizeDicomCharsetTerm(it) }.toTypedArray()
            }
            val scs = SpecificCharacterSet.valueOf(*dcmTerms)
            val decoded = decodeWithDcm4cheSpecificCharacterSet(bytes, vr, scs)
            if (!decoded.contains('\uFFFD')) {
                return DecodedDicomText(decoded, syntaxDeviationNote)
            }
        }

        val primaryTerm = normalizedTerms.firstOrNull() ?: ""

        // 3. Если в байтах только 7-битный ASCII (0x00..0x7F) и нет ISO 2022 escape
        if (!hasHighBytes) {
            val asciiDecoded = decodePersonNameOrText(bytes, vr, StandardCharsets.US_ASCII)
            return DecodedDicomText(asciiDecoded, syntaxDeviationNote)
        }

        // 4. В байтах присутствуют символы >= 0x80. Обрабатываем строго по заявленному (0008,0005):
        when (primaryTerm) {
            "ISO_IR 192" -> {
                val utf8Exact = tryDecodeStrictUtf8(bytes, vr)
                if (utf8Exact != null) {
                    return DecodedDicomText(utf8Exact, syntaxDeviationNote)
                }
                // Заявлен ISO_IR 192 (UTF-8), но байты НЕ являются валидным UTF-8!
                val (recoveredText, guessedCharsetName) = recoverMisencodedBytes(bytes, vr)
                return DecodedDicomText(
                    value = recoveredText,
                    diagnosticWarning = "Несоответствие кодировки: в (0008,0005) заявлена ISO_IR 192 (UTF-8), но байты тега не являются валидным UTF-8 (отображено с автокоррекцией $guessedCharsetName)."
                )
            }

            "ISO_IR 144" -> {
                // Проверяем, не записаны ли байты в Windows-1251 при ошибочно выставленном ISO_IR 144
                if (looksLikeWin1251InsteadOfIso8859_5(bytes)) {
                    val win1251Text = decodePersonNameOrText(bytes, vr, CHARSET_WIN1251)
                    return DecodedDicomText(
                        value = win1251Text,
                        diagnosticWarning = "Несоответствие кодировки: в (0008,0005) заявлена ISO_IR 144 (ISO-8859-5), однако коды байтов соответствуют Windows-1251 (CP1251)."
                    )
                }
                val utf8Exact = tryDecodeStrictUtf8(bytes, vr)
                if (utf8Exact != null && containsCyrillic(utf8Exact) && bytes.size >= 4) {
                    return DecodedDicomText(
                        value = utf8Exact,
                        diagnosticWarning = "Несоответствие кодировки: в (0008,0005) заявлена ISO_IR 144 (ISO-8859-5), но данные фактически закодированы в UTF-8."
                    )
                }
                // Стандартное декодирование ISO_IR 144 (ISO-8859-5, кириллица)
                val iso144Text = decodePersonNameOrText(bytes, vr, CHARSET_ISO_8859_5)
                return DecodedDicomText(iso144Text, syntaxDeviationNote)
            }

            "ISO_IR 100" -> {
                val iso100Text = decodePersonNameOrText(bytes, vr, StandardCharsets.ISO_8859_1)
                return DecodedDicomText(iso100Text, syntaxDeviationNote)
            }

            "ISO_IR 101", "ISO_IR 109", "ISO_IR 110", "ISO_IR 126", "ISO_IR 127",
            "ISO_IR 138", "ISO_IR 148", "ISO_IR 166", "GB18030", "GBK" -> {
                val scs = SpecificCharacterSet.valueOf(primaryTerm)
                val decoded = decodeWithDcm4cheSpecificCharacterSet(bytes, vr, scs)
                if (!decoded.contains('\uFFFD')) {
                    return DecodedDicomText(decoded, syntaxDeviationNote)
                }
                val (recoveredText, guessedCharsetName) = recoverMisencodedBytes(bytes, vr)
                return DecodedDicomText(
                    value = recoveredText,
                    diagnosticWarning = "Несоответствие кодировки: байты не декодируются заявленной $primaryTerm (отображено как $guessedCharsetName)."
                )
            }

            "", "ISO_IR 6" -> {
                // По стандарту DICOM PS3.5 при отсутствии (0008,0005) или при ISO_IR 6 допустимы только 7-битные символы ASCII (0x00..0x7F).
                // Поскольку обнаружены байты >= 0x80, исходный файл нарушает стандарт DICOM!
                val (recoveredText, guessedCharsetName) = recoverMisencodedBytes(bytes, vr)
                val declaredDesc = if (primaryTerm.isEmpty()) {
                    "тег (0008,0005) Specific Character Set отсутствует (по умолчанию ISO_IR 6 / ASCII)"
                } else {
                    "в (0008,0005) указана 7-битная кодировка ISO_IR 6 (ASCII)"
                }
                return DecodedDicomText(
                    value = recoveredText,
                    diagnosticWarning = "Предупреждение о кодировке DICOM: $declaredDesc, но в текстовых тегах обнаружены 8-битные символы ($guessedCharsetName)."
                )
            }

            else -> {
                val scs = SpecificCharacterSet.valueOf(primaryTerm)
                val decoded = decodeWithDcm4cheSpecificCharacterSet(bytes, vr, scs)
                if (!decoded.contains('\uFFFD')) {
                    return DecodedDicomText(decoded, syntaxDeviationNote)
                }
                val (recoveredText, guessedCharsetName) = recoverMisencodedBytes(bytes, vr)
                return DecodedDicomText(
                    value = recoveredText,
                    diagnosticWarning = "Неизвестная или некорректная кодировка в (0008,0005)='$primaryTerm'. Показано с декодированием $guessedCharsetName."
                )
            }
        }
    }

    /**
     * Формирует сводную диагностику кодировки для всего DICOM-набора данных (исследования/снимка).
     */
    fun analyzeDatasetCharset(attrs: Attributes): DicomCharsetDiagnostics {
        val declared = getDeclaredSpecificCharacterSets(attrs)
        val probeTags = intArrayOf(
            Tag.PatientName,
            Tag.StudyDescription,
            Tag.SeriesDescription,
            Tag.InstitutionName,
            Tag.Manufacturer,
            Tag.PerformingPhysicianName,
            Tag.ReferringPhysicianName,
            Tag.OperatorsName,
            Tag.ProtocolName,
            Tag.BodyPartExamined
        )

        val warnings = LinkedHashSet<String>()
        for (tag in probeTags) {
            if (attrs.contains(tag)) {
                val vr = attrs.getVR(tag) ?: VR.LO
                val res = decodeStringAttribute(attrs, tag, vr)
                if (!res.diagnosticWarning.isNullOrBlank()) {
                    warnings.add(res.diagnosticWarning)
                }
            }
        }

        val declaredLabel = if (declared.isEmpty()) {
            "Не указан (по умолчанию ISO_IR 6 / ASCII)"
        } else {
            declared.joinToString("\\")
        }

        return DicomCharsetDiagnostics(
            declaredTerms = declared,
            effectiveCharsetDescription = declaredLabel,
            hasEncodingWarning = warnings.isNotEmpty(),
            warningMessage = warnings.firstOrNull()
        )
    }

    /**
     * Форматирует значение DICOM Person Name (VR.PN) для удобного чтения в UI-карточках,
     * сохраняя структуру компонентов (Фамилия^Имя^Отчество^Префикс^Суффикс) и групп представления (=).
     */
    fun formatPersonNameReadable(rawPn: String): String {
        val trimmed = rawPn.trim()
        if (trimmed.isEmpty()) return "ANONYMOUS"
        // В таблице тегов мы показываем точное значение с '^', а в заголовках поддерживаем аккуратное отображение
        return trimmed
    }

    private fun decodePersonNameOrText(bytes: ByteArray, vr: VR, charset: Charset): String {
        if (vr == VR.PN) {
            // DICOM PS3.5 6.2.1: Person Name может иметь несколько значений через '\',
            // до 3 групп представления через '=' и до 5 компонентов через '^'.
            val rawString = String(bytes, charset)
            return rawString.split('\\').joinToString("\\") { nameVal ->
                nameVal.split('=').joinToString("=") { group ->
                    group.split('^').joinToString("^") { comp ->
                        comp.trim { it == '\u0000' }
                    }.trimEnd('^')
                }.trimEnd('=')
            }.trim { it <= ' ' || it == '\u0000' }
        }
        return String(bytes, charset).trim { it == ' ' || it == '\u0000' }
    }

    private fun decodeWithDcm4cheSpecificCharacterSet(
        bytes: ByteArray,
        vr: VR,
        scs: SpecificCharacterSet
    ): String {
        val raw = scs.decode(bytes)
        if (vr == VR.PN) {
            return raw.split('\\').joinToString("\\") { nameVal ->
                nameVal.split('=').joinToString("=") { group ->
                    group.split('^').joinToString("^") { comp ->
                        comp.trim { it == '\u0000' }
                    }.trimEnd('^')
                }.trimEnd('=')
            }.trim { it <= ' ' || it == '\u0000' }
        }
        return raw.trim { it == ' ' || it == '\u0000' }
    }

    private fun tryDecodeStrictUtf8(bytes: ByteArray, vr: VR): String? {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuf = decoder.decode(ByteBuffer.wrap(bytes))
            val raw = charBuf.toString()
            if (raw.contains('\uFFFD')) return null
            if (vr == VR.PN) {
                raw.split('\\').joinToString("\\") { nameVal ->
                    nameVal.split('=').joinToString("=") { group ->
                        group.split('^').joinToString("^") { comp ->
                            comp.trim { it == '\u0000' }
                        }.trimEnd('^')
                    }.trimEnd('=')
                }.trim { it <= ' ' || it == '\u0000' }
            } else {
                raw.trim { it == ' ' || it == '\u0000' }
            }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * Определяет случай, когда в (0008,0005) заявлен ISO_IR 144 (ISO-8859-5), но фактически записан Windows-1251:
     * - В ISO-8859-5 русские буквы А..Я, а..я лежат в диапазоне 0xB0..0xEF (плюс 0xA1 Ё, 0xF1 ё).
     *   Коды 0xF2..0xFF в ISO-8859-5 — это сербские/македонские/белорусские символы (ђ, ѓ, є, ѕ, і, ї, ј, љ, њ, ћ, ќ, §, ў, џ),
     *   которые в Windows-1251 являются строчными русскими буквами (т, у, ф, х, ц, ч, ш, щ, ъ, ы, ь, э, ю, я).
     * - Также в Windows-1251 русские буквы всегда лежат в 0xC0..0xFF (и 0xA8/0xB8), а в 0xB0..0xBF русских букв нет.
     */
    private fun looksLikeWin1251InsteadOfIso8859_5(bytes: ByteArray): Boolean {
        var countB0toBF = 0
        var countC0toEF = 0
        var countF2toFF = 0
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u in 0xB0..0xBF && u != 0xB8) countB0toBF++
            if (u in 0xC0..0xEF) countC0toEF++
            if (u in 0xF2..0xFF) countF2toFF++
        }
        // Если есть байты 0xF2..0xFF (строчные т..я в CP1251) и при этом нет заглавных А..П из ISO-8859-5 (0xB0..0xBF)
        if (countF2toFF > 0 && countB0toBF == 0) {
            return true
        }
        // Если все не-ASCII байты лежат строго в 0xC0..0xFF (плюс 0xA8/0xB8) и ни одного в 0xB0..0xBF,
        // проверим наличие гласных в Windows-1251 vs ISO-8859-5:
        if (countC0toEF >= 3 && countB0toBF == 0) {
            val winText = String(bytes, CHARSET_WIN1251)
            val isoText = String(bytes, CHARSET_ISO_8859_5)
            val winScore = scoreRussianPlausibility(winText)
            val isoScore = scoreRussianPlausibility(isoText)
            if (winScore > isoScore + 2) {
                return true
            }
        }
        return false
    }

    /**
     * Восстанавливает читаемый текст из некорректно закодированных байтов (при отсутствии (0008,0005) или ошибке UTF-8).
     */
    private fun recoverMisencodedBytes(bytes: ByteArray, vr: VR): Pair<String, String> {
        // 1. Сначала проверяем строгий UTF-8
        val utf8 = tryDecodeStrictUtf8(bytes, vr)
        if (utf8 != null) {
            return utf8 to "UTF-8 (ISO_IR 192)"
        }

        // 2. Сравниваем правдоподобность кириллицы в Windows-1251 и ISO-8859-5 (ISO_IR 144)
        val win1251Text = decodePersonNameOrText(bytes, vr, CHARSET_WIN1251)
        val iso88595Text = decodePersonNameOrText(bytes, vr, CHARSET_ISO_8859_5)

        val winScore = scoreRussianPlausibility(win1251Text)
        val isoScore = scoreRussianPlausibility(iso88595Text)

        return when {
            winScore > isoScore && winScore > 0 -> win1251Text to "Windows-1251 (CP1251)"
            isoScore > 0 -> iso88595Text to "ISO-8859-5 (ISO_IR 144)"
            else -> {
                val latin1Text = decodePersonNameOrText(bytes, vr, StandardCharsets.ISO_8859_1)
                latin1Text to "ISO-8859-1 (ISO_IR 100)"
            }
        }
    }

    private fun containsCyrillic(text: String): Boolean =
        text.any { it in '\u0400'..'\u04FF' }

    private fun scoreRussianPlausibility(text: String): Int {
        var score = 0
        val russianVowels = "АЕЁИОУЫЭЮЯаеёиоуыэюя"
        for (ch in text) {
            when {
                ch in 'А'..'я' || ch == 'Ё' || ch == 'ё' -> {
                    score += 2
                    if (russianVowels.indexOf(ch) >= 0) {
                        score += 1
                    }
                }
                // Редкие не-русские славянские символы из хвоста ISO-8859-5 (0xF2..0xFF) или спецсимволы CP1251 (0x80..0xBF)
                ch in '\u0452'..'\u045F' || ch in '\u0402'..'\u040F' -> score -= 4
                ch == '§' || ch == '№' || ch in '\u0080'..'\u00BF' -> score -= 3
            }
        }
        return score
    }

    private fun trimDicomPadding(bytes: ByteArray, vr: VR): ByteArray {
        var end = bytes.size
        while (end > 0) {
            val b = bytes[end - 1].toInt() and 0xFF
            if (b == 0x00 || (vr != VR.UT && vr != VR.LT && vr != VR.ST && b == 0x20)) {
                end--
            } else {
                break
            }
        }
        var start = 0
        if (vr != VR.UT && vr != VR.LT && vr != VR.ST) {
            while (start < end && (bytes[start].toInt() and 0xFF) == 0x20) {
                start++
            }
        }
        return if (start == 0 && end == bytes.size) bytes else bytes.copyOfRange(start, end)
    }

    /**
     * Преобразует значение DICOM Person Name (VR.PN, Family^Given^Middle^Prefix^Suffix)
     * в читаемый для пользователя формат.
     * Примеры:
     * - "Смирнова^А^В" -> "Смирнова А. В."
     * - "Smith^John" -> "Smith John"
     * - "Иванов^Иван^Иванович" -> "Иванов Иван Иванович"
     * - "Smith^John^Q.^Dr.^Jr." -> "Dr. Smith John Q. Jr."
     * - "Смирнова" -> "Смирнова"
     * - "ANONYMOUS" / "" -> ""
     */
    fun formatDicomPersonName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed == "-" || trimmed == "N/A" || trimmed.equals("ANONYMOUS", ignoreCase = true)) {
            return ""
        }

        // Берём первую группу представления (до '=', согласно PS3.5 Section 6.2)
        val singleGroup = trimmed.substringBefore('=').trim()
        if (singleGroup.isBlank()) return ""

        if (!singleGroup.contains('^')) {
            return singleGroup
        }

        val parts = singleGroup.split('^').map { it.trim() }
        val family = parts.getOrNull(0)?.ifBlank { null }
        val given = parts.getOrNull(1)?.ifBlank { null }
        val middle = parts.getOrNull(2)?.ifBlank { null }
        val prefix = parts.getOrNull(3)?.ifBlank { null }
        val suffix = parts.getOrNull(4)?.ifBlank { null }

        fun formatInitial(part: String): String {
            return if (part.length == 1 && part[0].isLetter()) "$part." else part
        }

        val resultParts = mutableListOf<String>()
        if (prefix != null) resultParts.add(prefix)
        if (family != null) resultParts.add(family)
        if (given != null) resultParts.add(formatInitial(given))
        if (middle != null) resultParts.add(formatInitial(middle))
        if (suffix != null) resultParts.add(suffix)

        return resultParts.joinToString(" ")
    }
}
