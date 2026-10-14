package ru.medeng.dicomtool.data.logging

import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Режим отображения журнала DICOM: Краткий (по сеансам) или Подробный (по событиям).
 */
enum class DicomLogDisplayMode(val labelRu: String) {
    BRIEF("Краткий"),
    DETAILED("Подробный")
}

/**
 * Фильтр направления обмена в журнале.
 */
enum class DicomTrafficFilter(val labelRu: String) {
    ALL("Все"),
    SCP_IN("SCP IN"),
    SCU_OUT("SCU OUT")
}

/**
 * Фильтр итогового результата в журнале.
 */
enum class DicomOutcomeFilter(val labelRu: String) {
    ALL("Все"),
    SUCCESS("Успешно"),
    ERRORS("Ошибки"),
    WARNINGS("Предупреждения"),
    IN_PROGRESS_OR_UNKNOWN("Выполняется / неизвестно")
}

/**
 * Итоговый статус DICOM-сеанса для сервисного инженера.
 */
enum class SessionOutcomeStatus(val labelRu: String) {
    SUCCESS("Успешно"),
    FAILURE("Ошибка"),
    WARNING("Предупреждение"),
    IN_PROGRESS("Выполняется"),
    UNKNOWN("Результат неизвестен")
}

/**
 * Описание переданного или принятого файла внутри DICOM-сеанса.
 */
data class TransferredFileItem(
    val fileName: String,
    val modality: String = "",
    val fileSizeFormatted: String = "",
    val sopInstanceUid: String = "",
    val sopClassUid: String = "",
    val statusText: String,
    val isSuccess: Boolean,
    val isWarning: Boolean = false,
    val isError: Boolean = false
) {
    val displayTitle: String
        get() {
            if (fileName.isBlank() || fileName == "Имя файла неизвестно" || fileName == "DICOM Object") {
                return "Имя файла неизвестно"
            }
            val parts = mutableListOf<String>()
            parts.add(fileName)
            if (modality.isNotBlank()) parts.add(modality)
            if (fileSizeFormatted.isNotBlank()) parts.add(fileSizeFormatted)
            return parts.joinToString(" · ")
        }
}

/**
 * Модель представления сгруппированного DICOM-сеанса (Краткий режим).
 */
data class DicomSessionSummary(
    val sessionKey: String,
    val sessionUid: String,
    val associationId: String,
    val direction: DicomTrafficDirection,
    val role: String,
    val primaryOperation: String,
    val status: SessionOutcomeStatus,
    val statusTitle: String,
    val statusSubtitle: String,
    val remoteAeTitle: String,
    val calledAeTitle: String,
    val callingAeTitle: String,
    val remoteHostAndPort: String,
    val filesCount: Int,
    val filesTransferred: List<TransferredFileItem>,
    val durationFormatted: String,
    val startTimeFormatted: String,
    val endTimeFormatted: String,
    val associationParameters: Map<String, String>,
    val events: List<DicomLogEntry>,
    val diagnosticRecommendations: List<DiagnosticRecommendation>
) {
    val formattedRemoteNode: String
        get() {
            val ae = remoteAeTitle.takeIf { it != "-" && it.isNotBlank() } ?: if (role == "SCU") "PACS" else "SCU"
            val socket = remoteHostAndPort.takeIf { it != "-" && it.isNotBlank() }
            return if (socket != null) "$ae · $socket" else ae
        }
}

/**
 * Логика безопасной группировки технических событий в диагностические сеансы.
 */
object DicomSessionGrouper {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Преобразует список сырых записей журнала в упорядоченный список сеансов (от новых к старым).
     */
    fun groupEvents(rawEntries: List<DicomLogEntry>): List<DicomSessionSummary> {
        if (rawEntries.isEmpty()) return emptyList()

        // 1. Разделяем события по сессионным корзинам с сохранением хронологического порядка
        val sessionBuckets = LinkedHashMap<String, MutableList<DicomLogEntry>>()

        // Вспомогательный трекер для старых записей без sessionUid
        var legacyCounter = 0
        var currentLegacyKey: String? = null
        var lastLegacyAssocId: String? = null
        var lastLegacyTimestampMs: Long = 0L

        for (entry in rawEntries) {
            val key: String
            if (entry.sessionUid.isNotBlank()) {
                key = entry.sessionUid
            } else {
                // Старые записи (до этапа 4.3.1): безопасное разбиение без ложного слияния разных сеансов
                val assocId = entry.associationId.trim()
                val entryTimeMs = parseTimeMillis(entry.timestampFormatted)
                val isBoundary = entry.category == DicomLogCategory.ASSOCIATE_RQ ||
                    entry.category == DicomLogCategory.TCP_CONNECT ||
                    entry.category == DicomLogCategory.SERVER_LIFECYCLE ||
                    assocId != lastLegacyAssocId ||
                    (entryTimeMs > 0 && lastLegacyTimestampMs > 0 && Math.abs(entryTimeMs - lastLegacyTimestampMs) > 120_000)

                if (isBoundary || currentLegacyKey == null) {
                    legacyCounter++
                    currentLegacyKey = "LEGACY-${assocId.ifBlank { "SYS" }}-$legacyCounter"
                }

                key = currentLegacyKey
                lastLegacyAssocId = assocId
                lastLegacyTimestampMs = entryTimeMs

                // Если сессия закрылась, следующая старая запись начнет новую группу
                if (entry.category == DicomLogCategory.RELEASE_RP ||
                    entry.category == DicomLogCategory.TCP_DISCONNECT ||
                    entry.category == DicomLogCategory.ABORT ||
                    entry.category == DicomLogCategory.ASSOCIATE_RJ
                ) {
                    currentLegacyKey = null
                }
            }

            sessionBuckets.getOrPut(key) { mutableListOf() }.add(entry)
        }

        // 2. Объединение одиноких предваряющих TCP_CONNECT событий с соответствующими ассоциациями SCP
        val mergedBuckets = mergeTcpEventsWithScpSessions(sessionBuckets)

        // 3. Формирование DicomSessionSummary для каждой корзины
        val summaries = mutableListOf<DicomSessionSummary>()
        for ((key, events) in mergedBuckets) {
            val summary = buildSessionSummary(key, events)
            summaries.add(summary)
        }

        // Возвращаем в обратном порядке (сначала самые свежие сеансы)
        return summaries.asReversed()
    }

    private fun mergeTcpEventsWithScpSessions(
        buckets: Map<String, List<DicomLogEntry>>
    ): Map<String, List<DicomLogEntry>> {
        val result = LinkedHashMap<String, MutableList<DicomLogEntry>>()
        val tcpPending = mutableMapOf<String, MutableList<DicomLogEntry>>() // remoteSocket -> events

        for ((key, events) in buckets) {
            val first = events.firstOrNull() ?: continue
            val isPureTcp = events.all { it.category == DicomLogCategory.TCP_CONNECT || it.category == DicomLogCategory.TCP_DISCONNECT }

            if (isPureTcp && key.startsWith("TCP-")) {
                val remoteSocket = first.technicalDetails["Remote Socket"]
                    ?: first.title.substringAfter("from ", "").trim()
                if (remoteSocket.isNotBlank()) {
                    tcpPending.getOrPut(remoteSocket) { mutableListOf() }.addAll(events)
                    continue
                }
            }

            // Проверяем, есть ли для этой ассоциации отложенный TCP_CONNECT с тем же сокетом
            val assocSocket = events.firstNotNullOfOrNull { it.technicalDetails["Remote Socket"] }
            if (assocSocket != null && tcpPending.containsKey(assocSocket)) {
                val prepended = tcpPending.remove(assocSocket)!!
                val combined = mutableListOf<DicomLogEntry>()
                combined.addAll(prepended)
                combined.addAll(events)
                result[key] = combined
                continue
            }

            result[key] = events.toMutableList()
        }

        // Оставшиеся неассоциированные TCP события возвращаем как самостоятельные сеансы
        for ((_, pendingEvents) in tcpPending) {
            val key = pendingEvents.first().sessionUid.ifBlank { "TCP-${pendingEvents.first().id}" }
            result[key] = pendingEvents
        }

        return result
    }

    private fun buildSessionSummary(
        sessionKey: String,
        events: List<DicomLogEntry>
    ): DicomSessionSummary {
        val firstEvent = events.first()
        val lastEvent = events.last()

        val role = determineRole(events)
        val direction = determineDirection(events, role)
        val associationId = events.firstOrNull { it.associationId != "-" && it.associationId != "LOG" }?.associationId
            ?: firstEvent.associationId

        // Извлечение AE Titles и сокета
        var remoteAe = "-"
        var calledAe = "-"
        var callingAe = "-"
        var remoteSocket = "-"

        for (e in events) {
            val details = e.technicalDetails
            if (calledAe == "-" && details.containsKey("Called AE Title")) {
                calledAe = details["Called AE Title"] ?: "-"
            }
            if (calledAe == "-" && details.containsKey("Called AE")) {
                calledAe = details["Called AE"] ?: "-"
            }
            if (callingAe == "-" && details.containsKey("Calling AE Title")) {
                callingAe = details["Calling AE Title"] ?: "-"
            }
            if (callingAe == "-" && details.containsKey("Calling AE")) {
                callingAe = details["Calling AE"] ?: "-"
            }
            if (remoteSocket == "-" && details.containsKey("Remote Socket")) {
                val s = details["Remote Socket"]?.trim() ?: ""
                if (s.isNotBlank() && s != "-") remoteSocket = s
            }
            if (remoteSocket == "-" && details.containsKey("PACS Host")) {
                val host = details["PACS Host"]?.trim() ?: ""
                val port = details["PACS Port"]?.trim() ?: ""
                if (host.isNotBlank()) remoteSocket = if (port.isNotBlank()) "$host:$port" else host
            }
            if (remoteSocket == "-" && details.containsKey("Удалённый хост")) {
                val host = details["Удалённый хост"]?.trim() ?: ""
                val port = details["TCP Порт"]?.trim() ?: ""
                if (host.isNotBlank()) remoteSocket = if (port.isNotBlank()) "$host:$port" else host
            }
            if (remoteSocket == "-" && details.containsKey("Удалённый узел")) {
                val u = details["Удалённый узел"]?.trim() ?: ""
                if (u.contains(":") && !u.contains(" ")) remoteSocket = u
            }
        }

        // Если сокет не найден в деталях, извлекаем из заголовка сетевого события
        if (remoteSocket == "-") {
            val netEvent = events.firstOrNull { it.category == DicomLogCategory.TCP_CONNECT || it.category == DicomLogCategory.ASSOCIATE_RQ }
            if (netEvent != null) {
                val candidate = netEvent.title.substringAfter("с ", "").substringAfter("from ", "").substringBefore(" ").trim()
                if (candidate.contains(":") && !candidate.contains("[")) {
                    remoteSocket = candidate
                }
            }
        }

        remoteAe = if (role == "SCU") {
            calledAe.takeIf { it != "-" }
                ?: events.firstNotNullOfOrNull { it.technicalDetails["Called AE Title"] ?: it.technicalDetails["Called AE"] }
                ?: "-"
        } else {
            callingAe.takeIf { it != "-" }
                ?: events.firstNotNullOfOrNull { it.technicalDetails["Calling AE Title"] ?: it.technicalDetails["Calling AE"] }
                ?: "-"
        }

        // Определение основной операции
        val primaryOp = determinePrimaryOperation(events)

        // Извлечение списка файлов и их статусов
        val files = extractTransferredFiles(events)

        // Параметры ассоциации
        val assocParams = extractAssociationParameters(events)

        // Расчёт продолжительности
        val startTime = firstEvent.timestampFormatted
        val endTime = lastEvent.timestampFormatted
        val durationFormatted = calculateDuration(startTime, endTime)

        // Определение статуса и итогового текста сеанса
        val (status, title, subtitle) = evaluateSessionOutcome(events, role, primaryOp, files)

        // Сбор диагностических рекомендаций
        val recommendations = mutableListOf<DiagnosticRecommendation>()
        for (e in events) {
            val rec = DicomDiagnosticHandbook.analyzeEntry(e)
            if (rec != null && recommendations.none { it.technicalName == rec.technicalName }) {
                recommendations.add(rec)
            }
        }

        return DicomSessionSummary(
            sessionKey = sessionKey,
            sessionUid = firstEvent.sessionUid.ifBlank { sessionKey },
            associationId = associationId,
            direction = direction,
            role = role,
            primaryOperation = primaryOp,
            status = status,
            statusTitle = title,
            statusSubtitle = subtitle,
            remoteAeTitle = remoteAe,
            calledAeTitle = calledAe,
            callingAeTitle = callingAe,
            remoteHostAndPort = remoteSocket,
            filesCount = files.size,
            filesTransferred = files,
            durationFormatted = durationFormatted,
            startTimeFormatted = startTime,
            endTimeFormatted = endTime,
            associationParameters = assocParams,
            events = events,
            diagnosticRecommendations = recommendations
        )
    }

    private fun determineRole(events: List<DicomLogEntry>): String {
        for (e in events) {
            val r = e.technicalDetails["Роль"]
            if (r != null && r.isNotBlank()) return r
            if (e.associationId.startsWith("SCU", ignoreCase = true)) return "SCU"
            if (e.associationId.startsWith("SCP", ignoreCase = true) || e.associationId.startsWith("ASSOC", ignoreCase = true)) return "SCP"
        }
        val first = events.first()
        return when (first.direction) {
            DicomTrafficDirection.OUTBOUND -> "SCU"
            DicomTrafficDirection.INBOUND -> "SCP"
            DicomTrafficDirection.LOCAL -> "SYS"
        }
    }

    private fun determineDirection(events: List<DicomLogEntry>, role: String): DicomTrafficDirection {
        if (role == "SCU") return DicomTrafficDirection.OUTBOUND
        if (role == "SCP") return DicomTrafficDirection.INBOUND
        return events.first().direction
    }

    private fun determinePrimaryOperation(events: List<DicomLogEntry>): String {
        val hasCStore = events.any { it.category == DicomLogCategory.C_STORE_RQ || it.category == DicomLogCategory.C_STORE_RSP }
        if (hasCStore) return "C-STORE"
        val hasCEcho = events.any { it.category == DicomLogCategory.C_ECHO_RQ || it.category == DicomLogCategory.C_ECHO_RSP }
        if (hasCEcho) return "C-ECHO"
        val hasAssoc = events.any {
            it.category == DicomLogCategory.ASSOCIATE_RQ ||
                it.category == DicomLogCategory.ASSOCIATE_AC ||
                it.category == DicomLogCategory.ASSOCIATE_RJ
        }
        if (hasAssoc) return "ASSOCIATION"
        val hasTcp = events.any { it.category == DicomLogCategory.TCP_CONNECT || it.category == DicomLogCategory.TCP_DISCONNECT }
        if (hasTcp) return "TCP"
        return "SERVER"
    }

    private fun formatTransferredFileSize(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        if (trimmed.contains("МБ") || trimmed.contains("КБ") || trimmed.contains("MB") || trimmed.contains("KB")) {
            return trimmed.substringBefore("(").trim()
        }
        val bytes = trimmed.filter { it.isDigit() }.toLongOrNull() ?: return ""
        return when {
            bytes >= 1024 * 1024 -> {
                val mb = bytes / (1024.0 * 1024.0)
                String.format(Locale.forLanguageTag("ru"), "%.2f МБ", mb)
            }
            bytes >= 1024 -> {
                val kb = bytes / 1024.0
                String.format(Locale.forLanguageTag("ru"), "%.1f КБ", kb)
            }
            bytes > 0 -> "$bytes байт"
            else -> ""
        }
    }

    private fun extractTransferredFiles(events: List<DicomLogEntry>): List<TransferredFileItem> {
        val result = mutableListOf<TransferredFileItem>()
        val fileRqs = events.filter { it.category == DicomLogCategory.C_STORE_RQ }

        for (rq in fileRqs) {
            val sopInstanceUid = rq.technicalDetails["SOP Instance UID"]
                ?: rq.technicalDetails["Affected SOP Instance UID"]
                ?: ""
            val sopClassUid = rq.technicalDetails["SOP Class UID"]
                ?: rq.technicalDetails["Affected SOP Class UID"]
                ?: ""

            // Ищем парный C_STORE_RSP (или ошибку)
            val pairRsp = events.firstOrNull {
                it.category == DicomLogCategory.C_STORE_RSP &&
                    ((sopInstanceUid.isNotBlank() && it.technicalDetails["SOP Instance UID"] == sopInstanceUid) || fileRqs.size == 1)
            }

            val rawName = rq.technicalDetails["Имя файла"]
                ?: rq.technicalDetails["Файл"]
                ?: rq.technicalDetails["File"]
                ?: pairRsp?.technicalDetails?.get("Имя файла")
                ?: pairRsp?.technicalDetails?.get("Файл")
                ?: pairRsp?.technicalDetails?.get("Saved Destination")?.let { File(it).name }
                ?: if (rq.title.contains(":") && rq.title.contains("C-STORE-RQ")) {
                    rq.title.substringAfter(": ").substringBefore(")").trim()
                } else null
            val name = rawName?.trim()?.takeIf { it.isNotBlank() && it != "DICOM Object" } ?: "Имя файла неизвестно"

            val modality = rq.technicalDetails["Модальность"]
                ?: rq.technicalDetails["Modality (0008,0060)"]
                ?: rq.technicalDetails["Modality"]
                ?: ""

            val rawSize = rq.technicalDetails["Размер файла"]
                ?: rq.technicalDetails["Размер Pixel Data"]
                ?: pairRsp?.technicalDetails?.get("Payload Size")
                ?: pairRsp?.technicalDetails?.get("Размер файла")
                ?: ""
            val sizeFormatted = formatTransferredFileSize(rawSize)

            val statusText: String
            val isSuccess: Boolean
            val isError: Boolean
            val isWarning: Boolean

            if (pairRsp != null) {
                val statusCode = DicomDiagnosticHandbook.extractStatusCode(pairRsp) ?: (if (pairRsp.isError) 0xC000 else 0x0000)
                val hexStr = "0x" + statusCode.toString(16).uppercase(Locale.US).padStart(4, '0')
                statusText = when (statusCode) {
                    0x0000 -> "Успешно ($hexStr)"
                    in 0xB000..0xB007, 0x0001 -> "Предупреждение ($hexStr)"
                    else -> "Ошибка ($hexStr)"
                }
                isSuccess = statusCode == 0x0000
                isWarning = statusCode in 0xB000..0xB007 || statusCode == 0x0001
                isError = !isSuccess && !isWarning
            } else if (rq.isError) {
                // Ошибка зафиксирована на уровне C_STORE_RQ (например, отказ Presentation Context или таймаут)
                statusText = if (rq.title.contains("отклонен контекст")) {
                    "Отклонён контекст представления"
                } else if (rq.title.contains("таймаут") || rq.summaryLines.any { it.contains("таймаут") }) {
                    "C-STORE-RSP не получен (таймаут)"
                } else {
                    rq.title
                }
                isSuccess = false
                isWarning = false
                isError = true
            } else {
                // Если сессия еще активна или ответ не получен
                statusText = "C-STORE-RSP не получен"
                isSuccess = false
                isWarning = false
                isError = true
            }

            result.add(
                TransferredFileItem(
                    fileName = name,
                    modality = modality,
                    fileSizeFormatted = sizeFormatted,
                    sopInstanceUid = sopInstanceUid,
                    sopClassUid = sopClassUid,
                    statusText = statusText,
                    isSuccess = isSuccess,
                    isWarning = isWarning,
                    isError = isError
                )
            )
        }

        return result
    }

    private fun extractAssociationParameters(events: List<DicomLogEntry>): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        for (e in events) {
            val d = e.technicalDetails
            if (d.containsKey("Max PDU Length (RQ)")) params["Max PDU (RQ)"] = d["Max PDU Length (RQ)"]!!
            if (d.containsKey("Max PDU Length (AC)")) params["Max PDU (AC)"] = d["Max PDU Length (AC)"]!!
            if (d.containsKey("Implementation Class UID")) params["Impl Class UID"] = d["Implementation Class UID"]!!
            if (d.containsKey("Implementation Version")) params["Impl Version"] = d["Implementation Version"]!!
            if (d.containsKey("Accepted Transfer Syntax")) params["Transfer Syntax"] = d["Accepted Transfer Syntax"]!!
            if (d.containsKey("Abstract Syntax UID")) params["SOP Class"] = d["Abstract Syntax UID"]!!
        }
        return params
    }

    private fun evaluateSessionOutcome(
        events: List<DicomLogEntry>,
        role: String,
        primaryOp: String,
        files: List<TransferredFileItem>
    ): Triple<SessionOutcomeStatus, String, String> {
        val hasAssociateRj = events.any { it.category == DicomLogCategory.ASSOCIATE_RJ }
        if (hasAssociateRj) {
            return Triple(
                SessionOutcomeStatus.FAILURE,
                "Ассоциация отклонена (A-ASSOCIATE-RJ)",
                "PACS отклонил установление связи"
            )
        }

        val hasAbort = events.any { it.category == DicomLogCategory.ABORT }
        if (hasAbort) {
            return Triple(
                SessionOutcomeStatus.FAILURE,
                "Аварийный разрыв (A-ABORT)",
                "Связь прервана до штатного завершения"
            )
        }

        val hasNetworkError = events.any { it.category == DicomLogCategory.NETWORK_ERROR }
        if (hasNetworkError) {
            val errEvent = events.first { it.category == DicomLogCategory.NETWORK_ERROR }
            val title = if (errEvent.title.contains("refused", ignoreCase = true) || errEvent.title.contains("отказано", ignoreCase = true)) {
                "В соединении отказано (Connection refused)"
            } else if (errEvent.title.contains("timeout", ignoreCase = true) || errEvent.title.contains("таймаут", ignoreCase = true)) {
                "Таймаут сетевого подключения"
            } else {
                "Сетевая ошибка соединения"
            }
            return Triple(SessionOutcomeStatus.FAILURE, title, errEvent.summaryLines.firstOrNull() ?: errEvent.title)
        }

        // Проверка на незавершённый активный сеанс (Выполняется)
        val hasReleaseRp = events.any { it.category == DicomLogCategory.RELEASE_RP }
        val hasDisconnect = events.any { it.category == DicomLogCategory.TCP_DISCONNECT }
        val isTerminal = hasReleaseRp || hasDisconnect || hasAbort || hasAssociateRj

        if (!isTerminal && (primaryOp == "C-STORE" || primaryOp == "C-ECHO" || primaryOp == "ASSOCIATION")) {
            return Triple(
                SessionOutcomeStatus.IN_PROGRESS,
                "Выполняется...",
                "DICOM-соединение активно"
            )
        }

        // C-STORE операции
        if (primaryOp == "C-STORE") {
            if (files.isNotEmpty()) {
                val successCount = files.count { it.isSuccess }
                val errorCount = files.count { it.isError }
                val total = files.size

                return when {
                    successCount == total -> {
                        val title = if (role == "SCU") "Успешная отправка исследования" else "Успешный приём исследования"
                        val sub = if (total == 1) "1 файл успешно передан: Success (0x0000)" else "Все $total файлов успешно переданы"
                        Triple(SessionOutcomeStatus.SUCCESS, title, sub)
                    }

                    successCount > 0 && errorCount > 0 -> {
                        Triple(
                            SessionOutcomeStatus.WARNING,
                            "Частичная отправка файлов",
                            "Успешно: $successCount из $total, ошибок: $errorCount"
                        )
                    }

                    files.all { it.statusText.contains("не получен") } -> {
                        // Требование ТЗ: Не делать вывод, что PACS отклонил файл, если сервер не прислал ответ!
                        Triple(
                            SessionOutcomeStatus.FAILURE,
                            "Не получено подтверждение отправки",
                            "C-STORE-RSP не получен от PACS"
                        )
                    }

                    else -> {
                        val firstErr = files.firstOrNull { it.isError }?.statusText ?: "Ошибка C-STORE"
                        Triple(
                            SessionOutcomeStatus.FAILURE,
                            "Ошибка отправки исследования",
                            firstErr
                        )
                    }
                }
            }
        }

        // C-ECHO операции
        if (primaryOp == "C-ECHO") {
            val echoRsp = events.firstOrNull { it.category == DicomLogCategory.C_ECHO_RSP }
            if (echoRsp != null) {
                val code = DicomDiagnosticHandbook.extractStatusCode(echoRsp) ?: (if (echoRsp.isError) 0xC000 else 0x0000)
                return if (code == 0x0000) {
                    Triple(
                        SessionOutcomeStatus.SUCCESS,
                        "Успешная проверка связи (C-ECHO)",
                        "Эхо-ответ Success (0x0000) получен"
                    )
                } else {
                    Triple(
                        SessionOutcomeStatus.FAILURE,
                        "Ошибка C-ECHO",
                        "Эхо-ответ со статусом 0x${code.toString(16).uppercase(Locale.US)}"
                    )
                }
            } else if (events.any { it.isError }) {
                return Triple(
                    SessionOutcomeStatus.FAILURE,
                    "Ошибка C-ECHO",
                    "Ответ C-ECHO-RSP не получен"
                )
            }
        }

        // Чистое TCP соединение
        if (primaryOp == "TCP") {
            val isErr = events.any { it.isError }
            return if (isErr) {
                Triple(SessionOutcomeStatus.FAILURE, "Сбой TCP-соединения", "Разрыв сокета или отказ")
            } else {
                Triple(SessionOutcomeStatus.UNKNOWN, "TCP подключение закрыто", "DICOM-ассоциация не запрашивалась")
            }
        }

        // Системные события
        if (primaryOp == "SERVER") {
            val isErr = events.any { it.isError }
            val title = events.firstOrNull()?.title ?: "Событие службы"
            return if (isErr) {
                Triple(SessionOutcomeStatus.FAILURE, title, "Ошибка сервера")
            } else {
                Triple(SessionOutcomeStatus.SUCCESS, title, "Служба DICOM")
            }
        }

        // Если соединение завершилось без однозначного результата (требование ТЗ: не считать ошибкой)
        val hasErrors = events.any { it.isError }
        return if (hasErrors) {
            Triple(SessionOutcomeStatus.FAILURE, "Сеанс завершён с ошибкой", "Зафиксированы технические ошибки")
        } else {
            Triple(
                SessionOutcomeStatus.UNKNOWN,
                "Сеанс завершён (результат неизвестен)",
                "Операции передачи файлов или C-ECHO не выполнялись"
            )
        }
    }

    private fun parseTimeMillis(timeStr: String): Long = synchronized(timeFormat) {
        try {
            timeFormat.parse(timeStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun calculateDuration(startStr: String, endStr: String): String {
        val startMs = parseTimeMillis(startStr)
        val endMs = parseTimeMillis(endStr)
        if (startMs <= 0 || endMs <= 0 || endMs < startMs) return "—"
        val deltaMs = endMs - startMs
        return if (deltaMs < 1000) {
            "$deltaMs мс"
        } else {
            val sec = deltaMs / 1000.0
            String.format(Locale.US, "%.1f с", sec).replace('.', ',')
        }
    }
}

/**
 * Проверка соответствия сеанса фильтрам и поисковому запросу.
 */
fun DicomSessionSummary.matches(
    trafficFilter: DicomTrafficFilter,
    outcomeFilter: DicomOutcomeFilter,
    query: String
): Boolean {
    // 1. Фильтр направления
    val trafficMatch = when (trafficFilter) {
        DicomTrafficFilter.ALL -> true
        DicomTrafficFilter.SCP_IN -> role == "SCP" || direction == DicomTrafficDirection.INBOUND
        DicomTrafficFilter.SCU_OUT -> role == "SCU" || direction == DicomTrafficDirection.OUTBOUND
    }
    if (!trafficMatch) return false

    // 2. Фильтр результата
    val outcomeMatch = when (outcomeFilter) {
        DicomOutcomeFilter.ALL -> true
        DicomOutcomeFilter.SUCCESS -> status == SessionOutcomeStatus.SUCCESS
        DicomOutcomeFilter.ERRORS -> status == SessionOutcomeStatus.FAILURE
        DicomOutcomeFilter.WARNINGS -> status == SessionOutcomeStatus.WARNING
        DicomOutcomeFilter.IN_PROGRESS_OR_UNKNOWN ->
            status == SessionOutcomeStatus.IN_PROGRESS || status == SessionOutcomeStatus.UNKNOWN
    }
    if (!outcomeMatch) return false

    // 3. Поисковый запрос
    val q = query.trim().lowercase(Locale.US)
    if (q.isBlank()) return true

    if (associationId.lowercase(Locale.US).contains(q) ||
        sessionUid.lowercase(Locale.US).contains(q) ||
        remoteAeTitle.lowercase(Locale.US).contains(q) ||
        calledAeTitle.lowercase(Locale.US).contains(q) ||
        callingAeTitle.lowercase(Locale.US).contains(q) ||
        remoteHostAndPort.lowercase(Locale.US).contains(q) ||
        primaryOperation.lowercase(Locale.US).contains(q) ||
        statusTitle.lowercase(Locale.US).contains(q) ||
        statusSubtitle.lowercase(Locale.US).contains(q)
    ) {
        return true
    }

    if (filesTransferred.any { f ->
            f.fileName.lowercase(Locale.US).contains(q) ||
                f.sopInstanceUid.lowercase(Locale.US).contains(q) ||
                f.statusText.lowercase(Locale.US).contains(q)
        }
    ) {
        return true
    }

    if (diagnosticRecommendations.any { r ->
            r.technicalName.lowercase(Locale.US).contains(q) ||
                r.explanationRu.lowercase(Locale.US).contains(q) ||
                (r.dicomStatusCodeHex?.lowercase(Locale.US)?.contains(q) == true)
        }
    ) {
        return true
    }

    return events.any { e ->
        e.title.lowercase(Locale.US).contains(q) ||
            e.category.name.lowercase(Locale.US).contains(q) ||
            e.summaryLines.any { it.lowercase(Locale.US).contains(q) } ||
            e.technicalDetails.any { (k, v) ->
                k.lowercase(Locale.US).contains(q) || v.lowercase(Locale.US).contains(q)
            }
    }
}

/**
 * Проверка соответствия отдельной записи фильтрам и поисковому запросу.
 */
fun DicomLogEntry.matches(
    trafficFilter: DicomTrafficFilter,
    outcomeFilter: DicomOutcomeFilter,
    query: String
): Boolean {
    // 1. Фильтр направления
    val trafficMatch = when (trafficFilter) {
        DicomTrafficFilter.ALL -> true
        DicomTrafficFilter.SCP_IN -> direction == DicomTrafficDirection.INBOUND ||
            associationId.startsWith("SCP", ignoreCase = true) ||
            associationId.startsWith("ASSOC", ignoreCase = true)
        DicomTrafficFilter.SCU_OUT -> direction == DicomTrafficDirection.OUTBOUND ||
            associationId.startsWith("SCU", ignoreCase = true)
    }
    if (!trafficMatch) return false

    // 2. Фильтр результата
    val outcomeMatch = when (outcomeFilter) {
        DicomOutcomeFilter.ALL -> true
        DicomOutcomeFilter.SUCCESS -> !isError && (
            category == DicomLogCategory.C_ECHO_RSP ||
                category == DicomLogCategory.C_STORE_RSP ||
                category == DicomLogCategory.ASSOCIATE_AC ||
                category == DicomLogCategory.RELEASE_RP
            )
        DicomOutcomeFilter.ERRORS -> isError ||
            category == DicomLogCategory.ASSOCIATE_RJ ||
            category == DicomLogCategory.ABORT ||
            category == DicomLogCategory.NETWORK_ERROR
        DicomOutcomeFilter.WARNINGS -> summaryLines.any {
            it.contains("warning", ignoreCase = true) || it.contains("предупреждение", ignoreCase = true)
        }
        DicomOutcomeFilter.IN_PROGRESS_OR_UNKNOWN -> !isError && (
            category == DicomLogCategory.TCP_CONNECT ||
                category == DicomLogCategory.ASSOCIATE_RQ ||
                category == DicomLogCategory.C_STORE_RQ ||
                category == DicomLogCategory.SERVER_LIFECYCLE
            )
    }
    if (!outcomeMatch) return false

    // 3. Поисковый запрос
    val q = query.trim().lowercase(Locale.US)
    if (q.isBlank()) return true

    if (associationId.lowercase(Locale.US).contains(q) ||
        sessionUid.lowercase(Locale.US).contains(q) ||
        title.lowercase(Locale.US).contains(q) ||
        category.name.lowercase(Locale.US).contains(q) ||
        direction.code.lowercase(Locale.US).contains(q)
    ) {
        return true
    }

    if (summaryLines.any { it.lowercase(Locale.US).contains(q) }) return true

    return technicalDetails.any { (k, v) ->
        k.lowercase(Locale.US).contains(q) || v.lowercase(Locale.US).contains(q)
    }
}

