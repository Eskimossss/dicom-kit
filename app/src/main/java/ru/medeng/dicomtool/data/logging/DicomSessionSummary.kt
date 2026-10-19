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
    val isError: Boolean = false,
    val scuOutcome: String? = null,
    val transferSyntaxUid: String = "",
    val storageResult: String? = null,
    val responseWrite: String? = null,
    val dimseStatus: Int? = null
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
    val diagnosticRecommendations: List<DiagnosticRecommendation>,
    val patients: List<DicomLogPatient> = emptyList()
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
    // Patterns are immutable and thread safe; do not recompile them for every event/session.
    private val legacySessionLabel = Regex("(?:SCU|SCP|ASSOC)-[0-9]+")
    private val processSessionIdentity = Regex("^(?:SCU|SCP)-(R[0-9a-z]+-[0-9a-f]{6,32})-[0-9]+.*$")

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
            if (entry.sessionUid.isNotBlank() && (entry.sessionUid == entry.associationId ||
                    legacySessionLabel.matches(entry.sessionUid))) {
                // A short display label in legacy TSV is not a globally unique session identity.
                key = "LEGACY-UNPROVEN-${entry.id}-${++legacyCounter}"
            } else if (entry.sessionUid.isNotBlank()) {
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

        // 2. TCP and DIMSE share an explicit identity; legacy endpoint strings cannot prove a match.
        val mergedBuckets = sessionBuckets // Only an explicit identity proves that events belong together.

        // 3. Формирование DicomSessionSummary для каждой корзины
        val summaries = mutableListOf<DicomSessionSummary>()
        for ((key, events) in mergedBuckets) {
            val summary = buildSessionSummary(key, events)
            summaries.add(summary)
        }

        // Возвращаем в обратном порядке (сначала самые свежие сеансы)
        return summaries.asReversed()
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
            diagnosticRecommendations = recommendations,
            patients = events.flatMap { it.patients }.distinctBy { it.identityKey }
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
        if (events.any { it.technicalDetails.containsKey("SCU File Result") || it.technicalDetails.containsKey("SCU Batch Complete") }) return "C-STORE"
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
        val outcomes = events.filter { it.technicalDetails.containsKey("SCU File Result") }
        if (outcomes.isNotEmpty()) return outcomes.map { entry ->
            val d = entry.technicalDetails
            val outcome = d.getValue("SCU File Result")
            TransferredFileItem(fileName = d["Имя файла"] ?: "Имя файла неизвестно",
                sopInstanceUid = d["SOP Instance UID"] ?: "", sopClassUid = d["SOP Class UID"] ?: "",
                statusText = d["Причина"] ?: outcome, isSuccess = outcome == "SUCCESS",
                isWarning = outcome == "WARNING" || outcome == "UNCONFIRMED",
                isError = outcome == "FAILED" || outcome == "SKIPPED", scuOutcome = outcome,
                transferSyntaxUid = d["Transfer Syntax UID"] ?: "")
        }
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
            val requestId = rq.technicalDetails["Message ID"]?.takeIf { it.isNotBlank() }
            val pairRsp = events.firstOrNull {
                if (it.category != DicomLogCategory.C_STORE_RSP) false
                else if (requestId != null) (it.technicalDetails["Message ID Being Responded To"]
                    ?: it.technicalDetails["Message ID Responded To"]) == requestId
                else {
                    val responseUid = it.technicalDetails["SOP Instance UID"] ?: it.technicalDetails["Affected SOP Instance UID"]
                    val uniqueUid = sopInstanceUid.isNotBlank() && fileRqs.count { request ->
                        (request.technicalDetails["SOP Instance UID"] ?: request.technicalDetails["Affected SOP Instance UID"]) == sopInstanceUid
                    } == 1
                    (uniqueUid && responseUid == sopInstanceUid) || fileRqs.size == 1
                }
            }

            fun matchesRequest(entry: DicomLogEntry): Boolean = requestId != null &&
                (entry.technicalDetails["Message ID Being Responded To"] ?: entry.technicalDetails["Message ID"]) == requestId
            val storage = events.lastOrNull { matchesRequest(it) && it.technicalDetails["Storage Result"] != null }
                ?.technicalDetails?.get("Storage Result")
            val write = events.lastOrNull { matchesRequest(it) && it.technicalDetails["Response Write"] in setOf("FAILED", "LOCAL_COMPLETED") }
                ?.technicalDetails?.get("Response Write")

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
                val statusCode = DicomDiagnosticHandbook.extractStatusCode(pairRsp)
                val hexStr = statusCode?.let { "0x" + it.toString(16).uppercase(Locale.US).padStart(4, '0') } ?: "неизвестно"
                statusText = when (statusCode) {
                    null -> "C-STORE-RSP не содержит достоверного статуса; результат неизвестен"
                    0x0000 -> "Успешно ($hexStr)"
                    in 0xB000..0xBFFF -> "Предупреждение ($hexStr)"
                    else -> "Ошибка ($hexStr)"
                }
                isSuccess = statusCode == 0x0000
                isWarning = statusCode != null && statusCode in 0xB000..0xBFFF
                isError = statusCode != null && !isSuccess && !isWarning
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
                statusText = if (rq.direction == DicomTrafficDirection.INBOUND) "Локальная запись C-STORE-RSP не подтверждена; результат SCU неизвестен" else "C-STORE-RSP не получен"
                isSuccess = false
                isWarning = false
                isError = rq.direction != DicomTrafficDirection.INBOUND
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
                    isError = isError,
                    scuOutcome = if (statusText.contains("не получен") || statusText.contains("не содержит") || statusText.contains("не подтверждена")) "UNCONFIRMED" else null,
                    storageResult = storage, responseWrite = write,
                    dimseStatus = pairRsp?.let { DicomDiagnosticHandbook.extractStatusCode(it) }
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
        val runId = processSessionIdentity
            .matchEntire(events.first().sessionUid)?.groupValues?.get(1)
        val terminal = events.any { it.category in setOf(DicomLogCategory.TCP_DISCONNECT, DicomLogCategory.ASSOCIATE_RJ, DicomLogCategory.ABORT) ||
            it.technicalDetails["SCU Batch Complete"] == "true" || it.technicalDetails["SCU Association Complete"] == "true" }
        if (runId != null && runId != DicomSessionManager.appRunId && !terminal)
            return Triple(SessionOutcomeStatus.UNKNOWN, "Незавершённый сеанс предыдущего процесса",
                "Сохранённые индивидуальные ответы доступны; исход незавершённых операций неизвестен. Проверьте удалённый узел перед повтором.")
        val single = events.singleOrNull()
        if (single != null && single.sessionUid == single.associationId &&
            single.category in listOf(DicomLogCategory.C_STORE_RSP, DicomLogCategory.C_ECHO_RSP)) {
            val code = DicomDiagnosticHandbook.extractStatusCode(single)
            val status = when (code) { null -> SessionOutcomeStatus.UNKNOWN; 0 -> SessionOutcomeStatus.SUCCESS
                in 0xB000..0xBFFF -> SessionOutcomeStatus.WARNING; else -> SessionOutcomeStatus.FAILURE }
            return Triple(status, "Зафиксирован ${single.category.name}: ${code?.let { "0x%04X".format(it) } ?: "статус неизвестен"}",
                "Старый журнал: связь с другими событиями не установлена по одной короткой метке")
        }
        if (role == "SCU" && events.any { it.technicalDetails.containsKey("SCU File Result") || it.technicalDetails.containsKey("SCU Batch Complete") }) {
            val complete = events.any { it.technicalDetails["SCU Association Complete"] == "true" || it.technicalDetails["SCU Batch Complete"] == "true" }
            if (!complete) return Triple(SessionOutcomeStatus.IN_PROGRESS, "Передача выполняется", "Ожидается завершение операции и cleanup")
            val cancelled = events.any { it.technicalDetails["SCU Cancellation"] == "true" }
            val unknown = files.count { it.scuOutcome == "UNCONFIRMED" }
            val successful = files.count { it.isSuccess || it.scuOutcome == "WARNING" }
            val failure = files.count { it.isError }
            val expected = events.lastOrNull { it.technicalDetails.containsKey("Expected File Outcomes") }
                ?.technicalDetails?.get("Expected File Outcomes")?.toIntOrNull()
            val subtitle = "Подтверждено: $successful, ошибок/пропущено: $failure, неизвестно: $unknown, всего: ${files.size}"
            return when {
                cancelled -> Triple(SessionOutcomeStatus.UNKNOWN, "Передача отменена", subtitle)
                expected == null || expected != files.size -> Triple(SessionOutcomeStatus.UNKNOWN,
                    "Полнота сеанса не подтверждена", "${subtitle}. Выборка может не содержать все события; ответы отдельных файлов сохранены.")
                unknown > 0 -> Triple(SessionOutcomeStatus.UNKNOWN, "Результат на PACS неизвестен", subtitle)
                events.any { it.technicalDetails["SCU Closure Warning"] == "true" } && successful == files.size ->
                    Triple(SessionOutcomeStatus.WARNING, "Передача подтверждена, ошибка завершения соединения", subtitle)
                files.isNotEmpty() && files.all { it.isSuccess } -> Triple(SessionOutcomeStatus.SUCCESS, "Успешная отправка файлов", subtitle)
                files.isNotEmpty() && successful == files.size -> Triple(SessionOutcomeStatus.WARNING, "Передача с предупреждениями", subtitle)
                successful > 0 -> Triple(SessionOutcomeStatus.WARNING, "Частичная отправка файлов", subtitle)
                else -> Triple(SessionOutcomeStatus.FAILURE, "Файлы не переданы", subtitle)
            }
        }
        val hasClosedTransport = events.any { it.category in listOf(DicomLogCategory.TCP_DISCONNECT, DicomLogCategory.ABORT, DicomLogCategory.RELEASE_RP) }
        if (role == "SCU" && primaryOp == "C-STORE" && hasClosedTransport && files.isNotEmpty()) {
            val unconfirmed = files.count { it.scuOutcome == "UNCONFIRMED" }
            if (unconfirmed > 0) return Triple(SessionOutcomeStatus.UNKNOWN, "Не получено подтверждение отправки",
                if (files.size == unconfirmed) "C-STORE-RSP не получен от PACS" else "Неизвестно: $unconfirmed из ${files.size}; остальные результаты сохранены")
            val closingError = events.any { it.category == DicomLogCategory.NETWORK_ERROR || it.category == DicomLogCategory.ABORT }
            if (closingError && files.all { it.isSuccess || it.isWarning }) return Triple(SessionOutcomeStatus.WARNING,
                "Передача подтверждена, ошибка завершения соединения", "Подтверждено: ${files.size}; состояние соединения см. в подробном журнале")
        }
        if (role == "SCP" && primaryOp == "C-STORE" && files.isNotEmpty()) {
            val closed = events.any { it.category in setOf(DicomLogCategory.TCP_DISCONNECT, DicomLogCategory.ABORT, DicomLogCategory.RELEASE_RP) }
            val saved = files.count { it.storageResult == "VERIFIED" }
            val failed = files.count { it.storageResult == "FAILED" || it.isError }
            val failedWrite = events.any { it.technicalDetails["Response Write"] == "FAILED" }
            val complete = files.all { it.responseWrite == "LOCAL_COMPLETED" || it.dimseStatus != null }
            val warning = files.any { it.isWarning }
            val connectionError = events.any { it.category in setOf(DicomLogCategory.ABORT, DicomLogCategory.NETWORK_ERROR, DicomLogCategory.TCP_DISCONNECT) && it.isError }
            val counts = "Сохранено: $saved; не сохранено/отказ: $failed; файлов: ${files.size}"
            if (failed > 0) return Triple(if (failed == files.size) SessionOutcomeStatus.FAILURE else SessionOutcomeStatus.WARNING,
                if (failed == files.size) "C-STORE: $failed файл(ов) не сохранены или отклонены" else "C-STORE: частичный успех",
                counts + if (failedWrite) "; ошибка передачи ответа" else "")
            if (failedWrite) return Triple(if (saved == files.size) SessionOutcomeStatus.WARNING else SessionOutcomeStatus.UNKNOWN,
                "Ошибка передачи ответа C-STORE", "$counts. Результат получения ответа на SCU неизвестен")
            if (closed && !complete) return Triple(SessionOutcomeStatus.UNKNOWN, "C-STORE: ответ не записан полностью", counts)
            if (complete) return Triple(when { !closed -> SessionOutcomeStatus.IN_PROGRESS; warning || connectionError -> SessionOutcomeStatus.WARNING; else -> SessionOutcomeStatus.SUCCESS },
                if (warning) "C-STORE: ответ с предупреждением" else if (saved == files.size) "C-STORE: ${files.size} файл(ов) успешно приняты" else "C-STORE: успешный статус ответа",
                if (connectionError) "$counts. Ошибка завершения соединения" else counts)
            return Triple(SessionOutcomeStatus.IN_PROGRESS, "C-STORE: приём выполняется", counts)
        }
        if (role == "SCP" && events.any { it.category == DicomLogCategory.C_ECHO_RQ }) {
            val response = events.lastOrNull { it.category == DicomLogCategory.C_ECHO_RSP }
            val failedWrite = events.any { it.technicalDetails["Response Write"] == "FAILED" }
            if (failedWrite) return Triple(SessionOutcomeStatus.UNKNOWN, "Ошибка передачи ответа C-ECHO", "Ответ удалённого узла не подтверждается отдельным прикладным сообщением")
            if (response != null) {
                val code = DicomDiagnosticHandbook.extractStatusCode(response)
                val closed = events.any { it.category in setOf(DicomLogCategory.TCP_DISCONNECT, DicomLogCategory.ABORT, DicomLogCategory.RELEASE_RP) }
                val transportError = events.any { it.category in setOf(DicomLogCategory.ABORT, DicomLogCategory.NETWORK_ERROR, DicomLogCategory.TCP_DISCONNECT) && it.isError }
                return Triple(when { code == null -> SessionOutcomeStatus.UNKNOWN; code != 0 && code !in 0xB000..0xBFFF -> SessionOutcomeStatus.FAILURE; code != 0 || transportError -> SessionOutcomeStatus.WARNING; !closed -> SessionOutcomeStatus.IN_PROGRESS; else -> SessionOutcomeStatus.SUCCESS },
                    if (code == 0) "C-ECHO: успешный ответ записан" else "C-ECHO: статус ответа требует проверки",
                    if (transportError) "Ошибка завершения соединения; сетевые события в подробном журнале" else "Локальная запись ответа выполнена")
            }
        }
        val hasAssociateRj = events.any { it.category == DicomLogCategory.ASSOCIATE_RJ }
        if (hasAssociateRj) {
            return Triple(
                SessionOutcomeStatus.FAILURE,
                "Ассоциация отклонена (A-ASSOCIATE-RJ)",
                if (role == "SCP") "Локальный SCP записал A-ASSOCIATE-RJ; получение удалённым узлом не подтверждено" else "PACS отклонил установление связи"
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
                val warningCount = files.count { it.isWarning }
                val total = files.size

                return when {
                    successCount == total -> {
                        val title = if (role == "SCU") "Успешная отправка исследования" else "Успешный приём исследования"
                        val sub = if (total == 1) "1 файл успешно передан: Success (0x0000)" else "Все $total файлов успешно переданы"
                        Triple(SessionOutcomeStatus.SUCCESS, title, sub)
                    }

                    successCount + warningCount == total -> Triple(SessionOutcomeStatus.WARNING,
                        "Передача с предупреждениями", "Подтверждено: $total, предупреждений: $warningCount")

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
                            SessionOutcomeStatus.UNKNOWN,
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

    if (patients.any { it.displayName.lowercase(Locale.US).contains(q) } ||
        associationId.lowercase(Locale.US).contains(q) ||
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
    if (patients.any { it.displayName.lowercase(Locale.US).contains(q) }) return true

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

