package ru.medeng.dicomtool.dicom.scu

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ScuFileResult(
    val fileIndex: Int, val fileId: String, val sopInstanceUid: String?, val transferSyntaxUid: String?,
    val status: FileSendStatus, val message: String, val responseStatus: Int? = null,
    val responseErrorComment: String? = null, val messageId: Int? = null, val transmittedBytes: Long = 0,
    val associationId: String = "", val sessionUid: String = ""
)

internal data class ScuTimeouts(val connect: Int = 5000, val response: Int = 10000,
    val send: Int = 10000, val release: Int = 3000)

/** Production transport; InputStream boundary also allows real loopback tests without an Android mock. */
internal class ScuFileBatchSender(private val logger: PersistentDicomTechnicalLogger) {
    fun send(host: String, port: Int, calling: String, called: String, files: List<ScuCandidateFile>,
        open: (ScuCandidateFile) -> InputStream,
        progress: (Int, ScuCandidateFile, FileSendStatus, String?) -> Unit,
        cancellation: ScuCancellation, isCancelled: () -> Boolean,
        timeouts: ScuTimeouts = ScuTimeouts()): ScuBatchOperationResult {
        val snapshot = files.toList()
        val results = arrayOfNulls<ScuFileResult>(snapshot.size)
        val started = System.nanoTime()
        var session = DicomSessionManager.nextScuSession()
        var cancelled = false
        val closureWarnings = mutableListOf<String>()
        fun checkCancel(): Boolean {
            if (isCancelled()) cancellation.request()
            return cancellation.isRequested
        }
        fun event(category: DicomLogCategory, title: String, details: Map<String, String> = emptyMap(),
            direction: DicomTrafficDirection = DicomTrafficDirection.LOCAL, error: Boolean = false,
                  patients: List<ru.medeng.dicomtool.data.logging.DicomLogPatient> = emptyList()) {
            logger.recordTechnicalEvent(category = category, direction = direction,
                associationId = session.displayLabel, sessionUid = session.sessionUid, title = title,
                summaryLines = listOf(title), technicalDetails = mapOf("Роль" to "SCU",
                    "Remote Socket" to "$host:$port", "Calling AE Title" to calling,
                    "Called AE Title" to called) + details, isError = error, patients = patients)
        }
        fun finish(index: Int, status: FileSendStatus, message: String, rsp: Attributes? = null,
            messageId: Int? = null, bytes: Long = 0) {
            check(results[index] == null) { "Duplicate terminal result" }
            val file = snapshot[index]
            val result = ScuFileResult(index, file.id, file.sopInstanceUid, file.transferSyntaxUid,
                status, message, rsp?.getInt(Tag.Status, -1)?.takeIf { it >= 0 },
                rsp?.getString(Tag.ErrorComment), messageId, bytes, session.displayLabel, session.sessionUid)
            results[index] = result
            event(DicomLogCategory.SERVER_LIFECYCLE, "[SCU] Файл: ${status.labelRu}",
                mapOf("SCU File Result" to status.name, "File ID" to "${file.id}:$index",
                    "Имя файла" to file.fileName, "SOP Instance UID" to (file.sopInstanceUid ?: ""),
                    "SOP Class UID" to (file.sopClassUid ?: ""), "Transfer Syntax UID" to (file.transferSyntaxUid ?: ""),
                    "Message ID" to (messageId?.toString() ?: ""), "Причина" to message,
                    "Dataset Bytes" to bytes.toString(), "Status" to (result.responseStatus?.let { "0x%04X".format(it) } ?: "")),
                error = status == FileSendStatus.FAILED || status == FileSendStatus.UNCONFIRMED)
            runCatching { progress(index, file, status, message) }.onFailure {
                event(DicomLogCategory.SERVER_LIFECYCLE, "[SCU] Ошибка уведомления UI: ${it.message}", error = true)
            }
        }

        // Preflight reads metadata and encoded FMI independently; no command is sent for unsafe input.
        val eligible = mutableListOf<Int>()
        val validEndpoint = host.isNotBlank() && port in 1..65535 &&
            DicomValidationUtils.validateAeTitle(calling) == DicomValidationUtils.ValidationResult.Valid &&
            DicomValidationUtils.validateAeTitle(called) == DicomValidationUtils.ValidationResult.Valid
        snapshot.forEachIndexed { index, file ->
            if (checkCancel()) { cancelled = true; finish(index, FileSendStatus.CANCELLED, "Отменено до отправки") }
            else try {
                require(validEndpoint) { "Некорректные параметры PACS: host, TCP port или AE Title" }
                require(file.isValidDicom && !file.sopClassUid.isNullOrBlank() && !file.sopInstanceUid.isNullOrBlank()
                    && !file.transferSyntaxUid.isNullOrBlank()) { "Нет достоверных SOP UID / Transfer Syntax" }
                val inspected = open(file).use { DicomFileScanner.inspectInputStream(it, fileName = file.fileName, fileSize = file.fileSizeBytes) }
                require(inspected.isValidDicom && inspected.sopClassUid == file.sopClassUid &&
                    inspected.sopInstanceUid == file.sopInstanceUid && inspected.transferSyntaxUid == file.transferSyntaxUid) {
                    "Файл изменился после выбора или его Transfer Syntax не подтверждён" }
                OriginalDicomDataset.open(open(file), file.transferSyntaxUid).use { }
                eligible.add(index)
            } catch (e: Exception) {
                if (checkCancel()) { cancelled = true; finish(index, FileSendStatus.CANCELLED, "Отменено до отправки") }
                else finish(index, FileSendStatus.FAILED, "Безопасная передача невозможна: ${e.message}")
            }
        }
        val batches = mutableListOf<MutableList<Int>>()
        var batch = mutableListOf<Int>()
        val pairs = mutableSetOf<Pair<String?, String?>>()
        for (index in eligible) {
            val file = snapshot[index]
            val pair = file.sopClassUid to file.transferSyntaxUid
            if (pair !in pairs && pairs.size == 120) { batches.add(batch); batch = mutableListOf(); pairs.clear() }
            batch.add(index); pairs.add(pair)
        }
        if (batch.isNotEmpty()) batches.add(batch)

        for (indices in batches) {
            if (checkCancel()) { cancelled = true; break }
            // First association uses the preflight identity; subsequent ones retain short SCU labels.
            if (indices !== batches.first()) session = DicomSessionManager.nextScuSession()
            val executor = Executors.newSingleThreadExecutor()
            val scheduler = Executors.newSingleThreadScheduledExecutor()
            var association: Association? = null
            var socket: java.net.Socket? = null
            try {
                scheduler.scheduleWithFixedDelay({ if (isCancelled()) cancellation.request() },
                    0, 50, TimeUnit.MILLISECONDS)
                val connection = object : Connection() {
                    override fun connect(remote: Connection): java.net.Socket {
                        return super.connect(remote).also { socket = it; cancellation.attach(it) }
                    }
                }.apply {
                    connectTimeout = timeouts.connect; acceptTimeout = timeouts.connect
                    requestTimeout = timeouts.connect; responseTimeout = timeouts.response
                    storeTimeout = timeouts.response; sendTimeout = timeouts.send
                    releaseTimeout = timeouts.release; socketCloseDelay = 0
                }
                val device = Device("dicomtool-file-scu").apply {
                    this.executor = executor; scheduledExecutor = scheduler; addConnection(connection)
                }
                val ae = ApplicationEntity(calling).apply { isAssociationInitiator = true; addConnection(connection) }
                device.addApplicationEntity(ae)
                val contextPairs = indices.map { snapshot[it].sopClassUid!! to snapshot[it].transferSyntaxUid!! }.distinct()
                val pcIds = contextPairs.withIndex().associate { it.value to it.index * 2 + 1 }
                val rq = AAssociateRQ().apply {
                    callingAET = calling; calledAET = called
                    pcIds.forEach { (pair, id) -> addPresentationContext(PresentationContext(id, pair.first, pair.second)) }
                }
                event(DicomLogCategory.TCP_CONNECT, "[SCU] Попытка TCP подключения", direction = DicomTrafficDirection.OUTBOUND)
                event(DicomLogCategory.ASSOCIATE_RQ, "[SCU] Подготовлен A-ASSOCIATE-RQ")
                association = ae.connect(connection, Connection().apply { hostname = host; this.port = port }, rq)
                event(DicomLogCategory.ASSOCIATE_AC, "[SCU] Получен A-ASSOCIATE-AC", direction = DicomTrafficDirection.INBOUND)
                for (index in indices) {
                    if (checkCancel()) { cancelled = true; break }
                    val file = snapshot[index]
                    val pc = association.aAssociateAC.getPresentationContext(pcIds[file.sopClassUid to file.transferSyntaxUid]!!)
                    event(DicomLogCategory.PRESENTATION_CONTEXT, "[SCU] Presentation Context: ${pc?.result}",
                        mapOf("SOP Class UID" to file.sopClassUid!!, "Transfer Syntax UID" to (pc?.transferSyntax ?: "")),
                        DicomTrafficDirection.INBOUND, pc?.isAccepted != true)
                    if (pc?.isAccepted != true || pc.transferSyntax != file.transferSyntaxUid) {
                        finish(index, FileSendStatus.FAILED, "Presentation Context отклонён; C-STORE не отправлялся")
                        continue
                    }
                    val messageId = association.nextMessageID()
                    val response = AtomicReference<Attributes?>()
                    val bytes = AtomicLong()
                    var attempted = false
                    var inspectedResponse: Attributes? = null
                    fun recordResponse(): Boolean {
                        val rsp = response.get() ?: return false
                        if (inspectedResponse === rsp) return false
                        inspectedResponse = rsp
                        val valid = rsp.getInt(Tag.CommandField, -1) == 0x8001 &&
                            rsp.getInt(Tag.MessageIDBeingRespondedTo, -1) == messageId && rsp.contains(Tag.Status) &&
                            (rsp.getString(Tag.AffectedSOPInstanceUID)?.let { it == file.sopInstanceUid } ?: true) &&
                            (rsp.getString(Tag.AffectedSOPClassUID)?.let { it == file.sopClassUid } ?: true)
                        val details = mapOf("Message ID Being Responded To" to rsp.getInt(Tag.MessageIDBeingRespondedTo, -1).toString(),
                            "Affected SOP Instance UID" to (rsp.getString(Tag.AffectedSOPInstanceUID) ?: ""),
                            "Affected SOP Class UID" to (rsp.getString(Tag.AffectedSOPClassUID) ?: ""),
                            "Status" to if (rsp.contains(Tag.Status)) "0x%04X".format(rsp.getInt(Tag.Status, -1)) else "missing",
                            "Error Comment" to (rsp.getString(Tag.ErrorComment) ?: ""), "Response Valid" to valid.toString())
                        if (!valid) {
                            event(DicomLogCategory.C_STORE_RSP, "[SCU] Получен некорректный ответ; подтверждение не установлено",
                                details, DicomTrafficDirection.INBOUND, true)
                            return false
                        }
                        val evaluation = DicomScuClient.evaluateDimseStatus(rsp.getInt(Tag.Status, -1), rsp.getString(Tag.ErrorComment))
                        event(DicomLogCategory.C_STORE_RSP, "[SCU] Получен C-STORE-RSP ${evaluation.statusHex}", details,
                            DicomTrafficDirection.INBOUND, evaluation.kind == ScuDimseStatusKind.FAILURE)
                        finish(index, when (evaluation.kind) {
                            ScuDimseStatusKind.SUCCESS -> FileSendStatus.SUCCESS
                            ScuDimseStatusKind.WARNING -> FileSendStatus.WARNING
                            ScuDimseStatusKind.FAILURE -> FileSendStatus.FAILED
                        }, evaluation.descriptionRu, rsp, messageId, bytes.get())
                        return true
                    }
                    try {
                        // Revalidate the selected identity immediately before issuing a command.
                        val inspected = open(file).use { DicomFileScanner.inspectInputStream(it, fileName = file.fileName, fileSize = file.fileSizeBytes) }
                        require(inspected.isValidDicom && inspected.sopInstanceUid == file.sopInstanceUid &&
                            inspected.sopClassUid == file.sopClassUid && inspected.transferSyntaxUid == file.transferSyntaxUid) { "Источник изменился до отправки" }
                        OriginalDicomDataset.open(open(file), file.transferSyntaxUid!!).use { dataset ->
                            if (checkCancel()) throw IOException("Отменено до отправки")
                            progress(index, file, FileSendStatus.SENDING, null)
                            if (checkCancel()) throw IOException("Отменено до отправки C-STORE-RQ")
                            event(DicomLogCategory.C_STORE_RQ, "[SCU] Попытка C-STORE-RQ", mapOf(
                                "Message ID" to messageId.toString(), "File ID" to "${file.id}:$index", "Имя файла" to file.fileName,
                                "Affected SOP Class UID" to file.sopClassUid, "Affected SOP Instance UID" to requireNotNull(file.sopInstanceUid),
                                "Transfer Syntax UID" to file.transferSyntaxUid!!), DicomTrafficDirection.OUTBOUND,
                                patients = listOf(ru.medeng.dicomtool.data.logging.DicomLogPatient.from(file.patientName, file.patientId)))
                            attempted = true
                            association.cstore(file.sopClassUid, file.sopInstanceUid, Priority.NORMAL, object : DataWriter {
                                override fun writeTo(out: PDVOutputStream, tsuid: String) {
                                    require(tsuid == file.transferSyntaxUid) { "Negotiated Transfer Syntax mismatch" }
                                    OriginalDicomDataset.copy(dataset, out, ::checkCancel) { bytes.set(it) }
                                }
                            }, file.transferSyntaxUid, object : DimseRSPHandler(messageId) {
                                override fun onDimseRSP(a: Association?, cmd: Attributes?, data: Attributes?) {
                                    super.onDimseRSP(a, cmd, data)
                                    if (cmd != null) response.set(Attributes(cmd))
                                }
                            })
                            association.waitForOutstandingRSP()
                        }
                        if (!recordResponse()) throw IOException("C-STORE-RSP отсутствует или некорректен; результат на PACS неизвестен")
                    } catch (e: Exception) {
                        val stopping = checkCancel()
                        if (stopping) cancelled = true
                        if (results[index] == null && !recordResponse()) finish(index,
                            if (attempted) FileSendStatus.UNCONFIRMED else if (stopping) FileSendStatus.CANCELLED else FileSendStatus.FAILED,
                            if (attempted) "C-STORE-RSP не подтверждён; результат на PACS неизвестен: ${e.message}" else "Файл не отправлен: ${e.message}",
                            messageId = messageId.takeIf { attempted }, bytes = bytes.get())
                        if (attempted || stopping) { runCatching { socket?.close() }; break }
                    }
                }
                if (!checkCancel() && association.isReadyForDataTransfer) {
                    event(DicomLogCategory.RELEASE_RQ, "[SCU] Запрос A-RELEASE-RQ", direction = DicomTrafficDirection.OUTBOUND)
                    association.release()
                    association.waitForSocketClose() // dcm4che throws on EOF, abort or timeout instead of fabricating RP.
                    event(DicomLogCategory.RELEASE_RP, "[SCU] Получен A-RELEASE-RP", direction = DicomTrafficDirection.INBOUND)
                }
            } catch (e: Exception) {
                if (checkCancel()) cancelled = true
                if (!cancelled && indices.all { results[it] != null })
                    closureWarnings.add("${session.displayLabel}: завершение ассоциации не подтверждено: ${e.message}")
                val classified = DicomScuClient.classifyException(e, host, port, calling, called)
                event(classified.logCategory, "[SCU] ${if (cancelled) "Отмена: закрытие транспорта" else classified.shortUserMessageRu}",
                    classified.technicalDetails + if (!cancelled && indices.all { results[it] != null })
                        mapOf("SCU Closure Warning" to "true") else emptyMap(),
                    if (cancelled) DicomTrafficDirection.LOCAL else classified.logDirection, !cancelled)
            } finally {
                runCatching { socket?.close() }
                runCatching { association?.waitForSocketClose() }
                cancellation.detach(socket)
                executor.shutdownNow(); scheduler.shutdownNow()
                val decoderStopped = executor.awaitTermination(2, TimeUnit.SECONDS)
                val schedulerStopped = scheduler.awaitTermination(2, TimeUnit.SECONDS)
                if (!decoderStopped || !schedulerStopped) {
                    closureWarnings.add("${session.displayLabel}: остановка вспомогательных потоков не подтверждена; TCP-сокет закрыт")
                    event(DicomLogCategory.SERVER_LIFECYCLE, "[SCU] Остановка вспомогательных потоков не подтверждена",
                        mapOf("SCU Closure Warning" to "true"), error = true)
                }
                if (socket?.isClosed == true) event(DicomLogCategory.TCP_DISCONNECT, "[SCU] Локальный TCP-сокет закрыт")
                indices.filter { results[it] == null }.forEach {
                    finish(it, if (cancelled) FileSendStatus.CANCELLED else FileSendStatus.SKIPPED,
                        if (cancelled) "Отменено до отправки" else "Не отправлен: ассоциация недоступна")
                }
                event(DicomLogCategory.SERVER_LIFECYCLE, "[SCU] Ассоциация завершена после cleanup",
                    mapOf("SCU Association Complete" to "true", "SCU Cancellation" to cancelled.toString(),
                        "Expected File Outcomes" to results.filterNotNull().count { it.sessionUid == session.sessionUid }.toString()))
            }
        }
        snapshot.indices.filter { results[it] == null }.forEach { finish(it,
            if (checkCancel()) FileSendStatus.CANCELLED else FileSendStatus.SKIPPED, "Не отправлен") }
        cancelled = cancelled || checkCancel()
        val terminal = results.map { requireNotNull(it) }
        fun count(status: FileSendStatus) = terminal.count { it.status == status }
        val success = count(FileSendStatus.SUCCESS); val warning = count(FileSendStatus.WARNING)
        val failure = count(FileSendStatus.FAILED); val skipped = count(FileSendStatus.SKIPPED)
        val unknown = count(FileSendStatus.UNCONFIRMED); val cancelledCount = count(FileSendStatus.CANCELLED)
        val allConfirmed = terminal.isNotEmpty() && success + warning == terminal.size
        val title = when {
            cancelled -> "Операция отменена после освобождения ресурсов"
            unknown > 0 -> "Передача завершена с неизвестными результатами"
            allConfirmed && warning == 0 -> "Все файлы успешно отправлены"
            allConfirmed -> "Все файлы подтверждены, есть предупреждения"
            success + warning > 0 -> "Пакет завершён с частичным успехом"
            else -> "Ни один файл не подтверждён как переданный"
        }
        val summary = "$title. Успешно: $success, предупреждений: $warning, ошибок: $failure, пропущено: $skipped, отменено до отправки: $cancelledCount, неизвестно: $unknown; всего: ${terminal.size}." +
            if (closureWarnings.isEmpty()) "" else " Предупреждение завершения: ${closureWarnings.joinToString("; ")}."
        event(DicomLogCategory.SERVER_LIFECYCLE, "[SCU] Итог пакета: $summary", mapOf("SCU Batch Complete" to "true", "SCU Cancellation" to cancelled.toString(),
            "Expected File Outcomes" to terminal.count { it.sessionUid == session.sessionUid }.toString()))
        return ScuBatchOperationResult(session.displayLabel, terminal.size, success, warning, failure,
            (System.nanoTime() - started) / 1_000_000, summary, cancelled, skipped, cancelledCount, unknown, terminal, closureWarnings)
    }
}
