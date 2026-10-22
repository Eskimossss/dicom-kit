package ru.medeng.dicomtool.data.logging

import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry

internal enum class EventOutcome(val labelRu: String) {
    SUCCESS("Успешно"), SUCCESS_WITH_WARNINGS("Успешно · с предупреждениями"),
    WARNING("Предупреждение"), ERROR("Ошибка"), INFO("Информация")
}

internal data class PresentedDicomEvent(
    val title: String, val summary: List<String>, val details: List<Pair<String, String>>, val outcome: EventOutcome
) {
    fun contains(query: String): Boolean = title.contains(query, true) || outcome.labelRu.contains(query, true) ||
        summary.any { it.contains(query, true) } || details.any { (k, v) -> k.contains(query, true) || v.contains(query, true) }
}

/** Display-only Russian catalogue. No event is copied back into the logger or exporter.
 * Known, anchored templates retain captured identifiers; unfamiliar text is returned verbatim.
 * Kept independent of Android resources so UI and journal search use exactly the same text. */
internal object DicomEventPresentation {
    private data class Rule(val pattern: Regex, val render: (List<String>) -> String)
    private fun rule(pattern: String, render: (List<String>) -> String) = Rule(Regex(pattern), render)
    private val titles = listOf(
        rule("C-STORE-RQ receiving \\(Message ID: (.*)\\)") { "Получен запрос C-STORE-RQ (Message ID: ${it[1]})" },
        rule("C-ECHO-RQ received \\(Message ID: (.*)\\)") { "Получен запрос C-ECHO-RQ (Message ID: ${it[1]})" },
        rule("C-STORE storage verified \\((.*)\\); response pending") {
            "DICOM-файл сохранён и проверен (${quantity(it[1])}); ответ ещё не отправлен"
        },
        rule("C-STORE storage failed \\((.*)\\); response pending") { "Ошибка сохранения DICOM-файла (${it[1]}); ответ ещё не отправлен" },
        rule("(C_STORE_RSP|C_ECHO_RSP|C_FIND_RSP) local write attempted") { "Начата запись ответа ${command(it[1])} в соединение" },
        rule("(C_STORE_RSP|C_ECHO_RSP|C_FIND_RSP) local write completed; peer receipt unconfirmed") {
            "Ответ ${command(it[1])} записан в соединение; получение удалённой стороной не подтверждено"
        },
        rule("(C_STORE_RSP|C_ECHO_RSP|C_FIND_RSP) local write failed; peer result unknown") {
            "Ошибка записи ответа ${command(it[1])}; результат удалённой стороны неизвестен"
        },
        rule("(A-ASSOCIATE-AC|A-ASSOCIATE-RJ) local write completed; peer receipt unconfirmed") {
            "${it[1]} записан в соединение; получение удалённой стороной не подтверждено"
        },
        rule("TCP connected from (.*)") { "Установлено TCP-соединение с ${it[1]}" },
        rule("TCP connection failed from (.*)") { "Ошибка TCP-соединения с ${it[1]}" },
        rule("TCP connection rejected \\(blacklisted\\): (.*)") { "TCP-соединение отклонено: ${it[1]} в чёрном списке" },
        rule("TCP connection rejected: (.*)") { "TCP-соединение отклонено: ${it[1]}" },
        rule("DICOM Storage SCP started on (.*) \\(AE: (.*)\\)") { "Storage SCP запущен: ${it[1]} (AE Title: ${it[2]})" },
        rule("SCP start error on port (.*)") { "Ошибка запуска SCP на порту ${it[1]}" },
        rule("Association error: (.*)") { "Ошибка DICOM-соединения: ${it[1]}" },
        rule("A-ABORT \\((.*)\\)") { "DICOM-соединение аварийно прервано: A-ABORT (${it[1]})" },
        rule("Presentation Context (.*) accepted") { "Presentation Context ${it[1]} принят" },
        rule("Presentation Context (.*) rejected \\((.*)\\)") { "Presentation Context ${it[1]} отклонён (${it[2]})" },
        rule("C-FIND-RSP final ([0-9A-Fa-f]{4})") { "Итоговый ответ C-FIND-RSP: ${it[1]}" },
        rule("C-FIND final ([0-9A-Fa-f]{4})") { "Итоговый ответ C-FIND: ${it[1]}" },
        rule("MWL query failed: (.*)") { "Ошибка запроса MWL: ${it[1]}" },
        rule("Storage verification failed: (.*)") { "Ошибка проверки сохранённого файла: ${text(it[1])}" },
        rule("Invalid DICOM UID: (.*)") { "Некорректный DICOM UID: ${it[1]}" },
        rule("Invalid ((?:Study|Series|SOP) Instance UID \\([0-9A-Fa-f,]+\\)): (.*)") { "Некорректный ${it[1]}: ${it[2]}" },
        rule("Missing ((?:Study|Series|SOP) Instance UID \\([0-9A-Fa-f,]+\\))") { "Отсутствует ${it[1]}" }
    )
    private val exact = mapOf(
        "Association processing ended; release PDU not inferred" to "Обработка DICOM-соединения завершена; обмен A-RELEASE PDU не подтверждён",
        "TCP socket locally closed; release PDU not inferred" to "TCP-соединение закрыто локально; обмен A-RELEASE PDU не подтверждён",
        "No exception reported" to "Исключений не зарегистрировано",
        "No exception observed" to "Исключений не зарегистрировано",
        "A-ASSOCIATE-RQ received" to "Получен запрос DICOM-соединения (A-ASSOCIATE-RQ)",
        "A-ASSOCIATE-RQ MWL" to "Отправлен запрос DICOM-соединения MWL (A-ASSOCIATE-RQ)",
        "A-ASSOCIATE-AC MWL" to "DICOM-соединение MWL согласовано (A-ASSOCIATE-AC)",
        "A-ASSOCIATE-RJ selected; local write not yet confirmed" to "Выбран отказ A-ASSOCIATE-RJ; запись в соединение ещё не подтверждена",
        "dcm4che reported A-ABORT; wire direction not inferred" to "dcm4che сообщил об A-ABORT; направление обмена не установлено",
        "C-STORE processing failed; response pending" to "Ошибка обработки C-STORE; ответ ещё не отправлен",
        "Точный повтор отклонён настройкой приёма; response pending" to "Точный повтор отклонён настройкой приёма; ответ ещё не отправлен",
        "SCP stop: listener closed; connections drained or forcibly closed" to "SCP остановлен: приём соединений завершён, соединения закрыты",
        "SCP service warning" to "Предупреждение службы SCP",
        "C-CANCEL-FIND received" to "Получен запрос отмены C-CANCEL-FIND",
        "C-FIND-RQ Modality Worklist" to "Получен запрос C-FIND-RQ Modality Worklist",
        "C-FIND-RQ MWL" to "Отправлен запрос C-FIND-RQ MWL",
        "C-FIND failed" to "Ошибка запроса C-FIND",
        "A-RELEASE failed after confirmed DIMSE final" to "Ошибка A-RELEASE после подтверждённого итогового ответа DIMSE",
        "MWL cancelled locally; remote Cancel not confirmed" to "Запрос MWL отменён локально; отмена удалённой стороной не подтверждена",
        "Verified new instance" to "Новый экземпляр сохранён и проверен",
        "Different content preserved separately" to "Другие данные с тем же UID сохранены отдельным файлом",
        "Identical re-transmission (verified SHA-256)" to "Подтверждён точный повтор (проверен SHA-256)",
        "Identical re-transmission saved as separate copy" to "Точный повтор сохранён отдельным файлом",
        "Exact duplicate rejected by receiver policy" to "Точный повтор отклонён настройкой приёма",
        "Command/dataset SOP identifiers differ" to "SOP Class UID или SOP Instance UID команды и Dataset не совпадают"
    )
    private val keys = mapOf(
        "Association ID" to "Идентификатор соединения", "Message ID" to "Message ID",
        "Message ID Being Responded To" to "Message ID запроса", "Remote Socket" to "Удалённый адрес",
        "Remote Address" to "Удалённый адрес", "Remote IP" to "Удалённый IP", "Remote Port" to "Удалённый порт",
        "Local Port" to "Локальный порт", "Active Connections" to "Активные соединения",
        "Exception" to "Исключение", "Reason" to "Причина", "Duration" to "Длительность",
        "Storage Result" to "Результат сохранения", "Response Write" to "Запись ответа",
        "Saved Destination" to "Сохранённый файл", "Payload Size" to "Объём данных",
        "UID Warning" to "Предупреждение UID", "Duplicate Policy" to "Обработка повтора",
        "Storage Disposition" to "Результат обработки экземпляра", "Received Items" to "Получено записей"
    )
    private val uidWarning = Regex("(.*?): non-conformant UID \\(leading zero\\), preserved verbatim: (.*)")
    private val prefixedUidWarning = Regex("Предупреждение UID: (.*)")
    private val quantityPattern = Regex("([0-9]+(?:\\.[0-9]+)?) (bytes|B|KB|MB|GB)")
    private val summaryPrefix = Regex("(Error|Reason|Remote|Local Listen Port|SOP Class|Status): (.*)")
    private val statusPattern = Regex("^(?:0[xX])?([0-9a-fA-F]{4})(?:[Hh])?(?:$|[ \\(])")
    private val durationPattern = Regex("([0-9]+(?:\\.[0-9]+)?) s")
    private val payloadPattern = Regex("([0-9]+) bytes \\((.*)\\)")

    private fun command(raw: String) = when (raw) {
        "C_STORE_RSP" -> "C-STORE-RSP"; "C_ECHO_RSP" -> "C-ECHO-RSP"; else -> "C-FIND-RSP"
    }
    private fun quantity(raw: String): String {
        val m = quantityPattern.matchEntire(raw) ?: return raw
        val unit = mapOf("bytes" to "байт", "B" to "байт", "KB" to "КБ", "MB" to "МБ", "GB" to "ГБ")[m.groupValues[2]]
        return "${m.groupValues[1].replace('.', ',')} $unit"
    }
    private fun text(raw: String): String {
        exact[raw]?.let { return it }
        for (rule in titles) rule.pattern.matchEntire(raw)?.let { return rule.render(it.groupValues) }
        uidWarning.matchEntire(raw)?.let {
            return "${it.groupValues[1]}: некорректный UID — ведущий ноль. Оригинальное значение сохранено без изменений: ${it.groupValues[2]}"
        }
        prefixedUidWarning.matchEntire(raw)?.let { return "Предупреждение UID: ${text(it.groupValues[1])}" }
        summaryPrefix.matchEntire(raw)?.let {
            val label = when (it.groupValues[1]) {
                "Error" -> "Ошибка"; "Reason" -> "Причина"; "Remote" -> "Удалённый адрес"
                "Local Listen Port" -> "Локальный порт"; "Status" -> "DICOM Status"; else -> it.groupValues[1]
            }
            return "$label: ${it.groupValues[2]}"
        }
        return raw
    }
    private fun value(key: String, raw: String): String = when (key) {
        "UID Warning" -> raw.lines().joinToString("\n", transform = ::text)
        "Response Write" -> when (raw) {
            "ATTEMPTED" -> "Начата запись (ATTEMPTED)"
            "LOCAL_COMPLETED" -> "Записан в соединение; получение удалённой стороной не подтверждено (LOCAL_COMPLETED)"
            "FAILED" -> "Ошибка записи (FAILED)"; else -> raw
        }
        "Storage Result" -> when (raw) {
            "VERIFIED" -> "Сохранено и проверено (VERIFIED)"; "FAILED" -> "Ошибка сохранения (FAILED)"; else -> raw
        }
        "Exception" -> if (raw == "none") "Исключений не зарегистрировано (none)" else text(raw)
        "Duplicate Policy", "Reason" -> text(raw)
        "Storage Disposition" -> when (raw) {
            "NEW_COPY" -> "Новый экземпляр (NEW_COPY)"
            "DUPLICATE_CONFIRMED" -> "Точный повтор подтверждён (DUPLICATE_CONFIRMED)"
            "REPEAT_COPY" -> "Повтор сохранён отдельно (REPEAT_COPY)"
            "CONTENT_COLLISION" -> "Разные данные с тем же UID сохранены отдельно (CONTENT_COLLISION)"
            else -> raw
        }
        "Duration" -> durationPattern.matchEntire(raw)?.let { "${it.groupValues[1].replace('.', ',')} с" } ?: raw
        "Payload Size" -> payloadPattern.matchEntire(raw)?.let { "${it.groupValues[1]} байт (${quantity(it.groupValues[2])})" } ?: raw
        else -> raw
    }

    /** Only structured status fields are evidence; arbitrary words/hex in titles are not. */
    private fun status(entry: DicomLogEntry): Int? {
        val raw = listOf("DICOM Status", "Status", "Статус", "Код ответа").firstNotNullOfOrNull { entry.technicalDetails[it] } ?: return null
        val match = statusPattern.find(raw.trim()) ?: return null
        return match.groupValues[1].toIntOrNull(16)
    }
    fun outcome(entry: DicomLogEntry): EventOutcome {
        val d = entry.technicalDetails
        val status = status(entry)
        val warning = status in 0xB000..0xBFFF || !d["UID Warning"].isNullOrBlank() || !d["Warning"].isNullOrBlank() ||
            entry.summaryLines.any { it.startsWith("Предупреждение") || it.startsWith("Warning:") }
        return when {
            entry.isError || d["Storage Result"] == "FAILED" || d["Response Write"] == "FAILED" ||
                entry.category in setOf(DicomLogCategory.ASSOCIATE_RJ, DicomLogCategory.NETWORK_ERROR) -> EventOutcome.ERROR
            status != null && status != 0 && status !in setOf(0xFF00, 0xFF01, 0xFE00) && status !in 0xB000..0xBFFF -> EventOutcome.ERROR
            d["Response Write"] == "ATTEMPTED" -> if (warning) EventOutcome.WARNING else EventOutcome.INFO
            d["Storage Result"] == "VERIFIED" || (status == 0 && (d["Response Write"] == "LOCAL_COMPLETED" ||
                entry.category in setOf(DicomLogCategory.C_STORE_RSP, DicomLogCategory.C_ECHO_RSP))) ||
                entry.category == DicomLogCategory.ASSOCIATE_AC ->
                if (warning) EventOutcome.SUCCESS_WITH_WARNINGS else EventOutcome.SUCCESS
            status in 0xB000..0xBFFF || status == 0xFE00 || warning || entry.category == DicomLogCategory.ABORT -> EventOutcome.WARNING
            else -> EventOutcome.INFO
        }
    }
    fun present(entry: DicomLogEntry) = PresentedDicomEvent(text(entry.title), entry.summaryLines.map(::text),
        entry.technicalDetails.map { (k, v) -> (keys[k] ?: k) to value(k, v) }, outcome(entry))
}
