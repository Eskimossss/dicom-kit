package ru.medeng.dicomtool.mwl.scu

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.Device
import org.dcm4che3.net.DimseRSP
import org.dcm4che3.net.Priority
import org.dcm4che3.net.Status
import org.dcm4che3.net.TransferCapability
import org.dcm4che3.net.pdu.AAssociateAC
import org.dcm4che3.net.pdu.AAssociateRJ
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.MwlQueryResultItem
import ru.medeng.dicomtool.mwl.model.MwlScuFilter
import ru.medeng.dicomtool.mwl.model.MwlScuProfile
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MwlScuClient(
    private val logger: MwlTechnicalLogger
) {
    companion object {
        val SUPPORTED_TRANSFER_SYNTAXES = arrayOf(
            UID.ImplicitVRLittleEndian,
            UID.ExplicitVRLittleEndian
        )
    }

    private val isCancelled = AtomicBoolean(false)
    private val currentRspRef = AtomicReference<DimseRSP?>(null)
    private val currentAssocRef = AtomicReference<Association?>(null)

    fun cancelQuery() {
        isCancelled.set(true)
        val assoc = currentAssocRef.get()
        val rsp = currentRspRef.get()
        if (assoc != null && rsp != null) {
            runCatching { rsp.cancel(assoc) }
        }
    }

    fun queryWorklist(
        profile: MwlScuProfile,
        filter: MwlScuFilter,
        onProgress: (count: Int) -> Unit = {}
    ): Result<List<MwlQueryResultItem>> {
        isCancelled.set(false)
        val session = DicomSessionManager.nextMwlScuSession()
        val assocId = session.displayLabel
        val sessionUid = session.sessionUid

        val host = profile.host.trim().ifEmpty { "127.0.0.1" }
        val port = profile.port
        val calledAet = profile.calledAet.trim().ifEmpty { "MWL_SCP" }
        val callingAet = profile.callingAet.trim().ifEmpty { "DICOMKIT_SCU" }

        logger.recordEvent(
            category = DicomLogCategory.TCP_CONNECT,
            direction = DicomTrafficDirection.OUTBOUND,
            associationId = assocId,
            sessionUid = sessionUid,
            title = "MWL SCU: Подключение к $host:$port",
            summaryLines = listOf(
                "Calling AE: $callingAet  ->  Called AE: $calledAet",
                "Таймаут подключения: ${profile.connectionTimeoutMs} мс",
                "Таймаут ответа: ${profile.responseTimeoutMs} мс"
            ),
            technicalDetails = mapOf(
                "Host" to host,
                "Port" to port.toString(),
                "Called AET" to calledAet,
                "Calling AET" to callingAet
            )
        )

        var association: Association? = null
        var executor: java.util.concurrent.ExecutorService? = null
        var scheduled: ScheduledExecutorService? = null

        return try {
            val exec = Executors.newCachedThreadPool().also { executor = it }
            val sched = Executors.newSingleThreadScheduledExecutor().also { scheduled = it }

            val device = Device("dicomkit-mwl-scu")
            val localConn = Connection().apply {
                connectTimeout = profile.connectionTimeoutMs
                responseTimeout = profile.responseTimeoutMs
            }
            val remoteConn = Connection().apply {
                hostname = host
                this.port = port
                connectTimeout = profile.connectionTimeoutMs
                responseTimeout = profile.responseTimeoutMs
                acceptTimeout = profile.connectionTimeoutMs
            }

            val localAe = ApplicationEntity(callingAet).apply {
                isAssociationInitiator = true
                isAssociationAcceptor = false
                addConnection(localConn)

                addTransferCapability(
                    TransferCapability(
                        "MWL-Find-SCU",
                        UID.ModalityWorklistInformationModelFind,
                        TransferCapability.Role.SCU,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )

                addTransferCapability(
                    TransferCapability(
                        "Verification-SCU",
                        UID.Verification,
                        TransferCapability.Role.SCU,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )
            }

            device.addConnection(localConn)
            device.addApplicationEntity(localAe)
            device.executor = exec
            device.scheduledExecutor = sched

            val rq = AAssociateRQ().apply {
                this.calledAET = calledAet
                this.callingAET = callingAet
                addPresentationContext(
                    PresentationContext(
                        1,
                        UID.ModalityWorklistInformationModelFind,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )
                addPresentationContext(
                    PresentationContext(
                        3,
                        UID.Verification,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )
            }

            logger.recordEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "A-ASSOCIATE-RQ запрос согласования ассоциации MWL",
                summaryLines = listOf(
                    "Calling AE: $callingAet  ->  Called AE: $calledAet",
                    "SOP Class: Modality Worklist FIND (1.2.840.10008.5.1.4.31)"
                )
            )

            // Установка TCP соединения и согласование ассоциации
            val assoc = localAe.connect(localConn, remoteConn, rq)
            association = assoc
            currentAssocRef.set(assoc)

            val ac: AAssociateAC = assoc.aAssociateAC
            val rqMwlPc = rq.presentationContexts.firstOrNull { it.abstractSyntax == UID.ModalityWorklistInformationModelFind }
            val mwlPc = rqMwlPc?.let { ac.getPresentationContext(it.pcid) }

            if (mwlPc == null || !mwlPc.isAccepted) {
                logger.recordEvent(
                    category = DicomLogCategory.ASSOCIATE_RJ,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    sessionUid = sessionUid,
                    title = "Удалённый сервер отклонил Presentation Context для Modality Worklist",
                    summaryLines = listOf("SOP Class 1.2.840.10008.5.1.4.31 не был принят сервером"),
                    isError = true
                )
                assoc.release()
                return Result.failure(IllegalStateException("Сервер не принял Presentation Context для Modality Worklist"))
            }

            val negotiatedTs = mwlPc.transferSyntax ?: UID.ImplicitVRLittleEndian
            logger.recordEvent(
                category = DicomLogCategory.ASSOCIATE_AC,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Ассоциация MWL согласована успешно (A-ASSOCIATE-AC)",
                summaryLines = listOf(
                    "Согласованный Transfer Syntax: ${runCatching { UID.nameOf(negotiatedTs) }.getOrDefault(negotiatedTs)}",
                    "PC ID: ${mwlPc.pcid}"
                ),
                technicalDetails = mapOf(
                    "PC ID" to mwlPc.pcid.toString(),
                    "Transfer Syntax" to negotiatedTs,
                    "Remote AET" to assoc.calledAET
                )
            )

            // Построение C-FIND-RQ Identifier
            val queryDataset = MwlDatasetBuilder.buildMwlScuQueryDataset(
                patientName = filter.patientName,
                patientId = filter.patientId,
                accessionNumber = filter.accessionNumber,
                modality = filter.modality,
                scheduledStationAe = filter.scheduledStationAe,
                dateRange = filter.buildDateRangePattern(),
                requestedProcedureId = filter.requestedProcedureId,
                characterSet = "ISO_IR 192"
            )

            val filterSummary = mutableListOf<String>()
            val datePat = filter.buildDateRangePattern()
            if (datePat.isNotEmpty()) filterSummary.add("Дата: $datePat (${filter.dateOption.labelRu})")
            if (filter.modality.isNotEmpty()) filterSummary.add("Модальность: ${filter.modality}")
            if (filter.scheduledStationAe.isNotEmpty()) filterSummary.add("Station AE: ${filter.scheduledStationAe}")
            if (filter.patientName.isNotEmpty()) filterSummary.add("ФИО: ${filter.patientName}")
            if (filter.patientId.isNotEmpty()) filterSummary.add("Patient ID: ${filter.patientId}")
            if (filter.accessionNumber.isNotEmpty()) filterSummary.add("Accession: ${filter.accessionNumber}")
            if (filterSummary.isEmpty()) filterSummary.add("Без фильтрации (запрос всех записей)")

            logger.recordEvent(
                category = DicomLogCategory.C_ECHO_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Отправка C-FIND-RQ Modality Worklist",
                summaryLines = listOf("Параметры фильтра:") + filterSummary
            )

            val results = mutableListOf<MwlQueryResultItem>()
            val dimseRsp: DimseRSP = assoc.cfind(
                UID.ModalityWorklistInformationModelFind,
                Priority.NORMAL,
                queryDataset,
                negotiatedTs,
                0 // autoCancel = 0
            )
            currentRspRef.set(dimseRsp)

            var lastStatusHex = "0000H"
            var cancelled = false

            while (dimseRsp.next()) {
                if (isCancelled.get()) {
                    runCatching { dimseRsp.cancel(assoc) }
                    cancelled = true
                }

                val cmd = dimseRsp.command ?: continue
                val status = cmd.getInt(Tag.Status, 0)
                lastStatusHex = "%04XH".format(status)

                if (Status.isPending(status)) {
                    val ds = dimseRsp.dataset
                    if (ds != null) {
                        val item = parseMwlQueryResultItem(ds)
                        results.add(item)
                        onProgress(results.size)
                    }
                } else if (status == Status.Cancel) {
                    cancelled = true
                    break
                } else if (status == Status.Success) {
                    break
                } else {
                    // Ошибка со стороны SCP
                    val errDesc = "Ошибка выполнения ($lastStatusHex)"
                    logger.recordEvent(
                        category = DicomLogCategory.NETWORK_ERROR,
                        direction = DicomTrafficDirection.INBOUND,
                        associationId = assocId,
                        sessionUid = sessionUid,
                        title = "Сервер MWL вернул ошибку выполнения C-FIND",
                        summaryLines = listOf("Status: $lastStatusHex ($errDesc)"),
                        technicalDetails = mapOf("Status" to lastStatusHex, "Description" to errDesc),
                        isError = true
                    )
                    break
                }
            }

            if (cancelled) {
                logger.recordEvent(
                    category = DicomLogCategory.C_ECHO_RSP,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    sessionUid = sessionUid,
                    title = "C-FIND завершён по отмене пользователя (FE00H Cancel)",
                    summaryLines = listOf("Получено результатов до отмены: ${results.size}")
                )
            } else {
                logger.recordEvent(
                    category = DicomLogCategory.C_ECHO_RSP,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    sessionUid = sessionUid,
                    title = "C-FIND успешно завершён (0000H Success)",
                    summaryLines = listOf("Получено назначений Worklist: ${results.size}"),
                    technicalDetails = mapOf("Received Items" to results.size.toString(), "Status" to lastStatusHex)
                )
            }

            // Корректное завершение ассоциации
            runCatching { assoc.release() }
            logger.recordEvent(
                category = DicomLogCategory.RELEASE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Завершение ассоциации A-RELEASE",
                summaryLines = listOf("Соединение освобождено штатно")
            )

            Result.success(results)
        } catch (e: Exception) {
            val errorMsg = when (e) {
                is ConnectException -> "TCP Connection Refused: сервер $host:$port недоступен или отклонил соединение"
                is SocketTimeoutException -> "TCP Timeout: превышено время ожидания ответа от $host:$port"
                is UnknownHostException -> "DNS Error: не удалось определить адрес хоста '$host'"
                else -> "${e.javaClass.simpleName}: ${e.message}"
            }

            logger.recordEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Сбой выполнения запроса MWL SCU",
                summaryLines = listOf(errorMsg),
                technicalDetails = mapOf(
                    "Host" to host,
                    "Port" to port.toString(),
                    "Exception" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true
            )

            runCatching { association?.abort() }
            Result.failure(e)
        } finally {
            currentRspRef.set(null)
            currentAssocRef.set(null)
            executor?.shutdownNow()
            scheduled?.shutdownNow()
        }
    }

    private fun parseMwlQueryResultItem(ds: Attributes): MwlQueryResultItem {
        val cs = ds.getString(Tag.SpecificCharacterSet, "ISO_IR 192")
        val pName = ds.getString(Tag.PatientName, "")
        val pId = ds.getString(Tag.PatientID, "")
        val pBirth = ds.getString(Tag.PatientBirthDate, "")
        val pSex = ds.getString(Tag.PatientSex, "")
        val acc = ds.getString(Tag.AccessionNumber, "")
        val studyUid = ds.getString(Tag.StudyInstanceUID, "")
        val rpId = ds.getString(Tag.RequestedProcedureID, "")
        val rpDesc = ds.getString(Tag.RequestedProcedureDescription, "")
        val refPhys = ds.getString(Tag.ReferringPhysicianName, "")

        var spsId = ""
        var modality = ds.getString(Tag.Modality, "") // fallback из корня
        var spsDate = ""
        var spsTime = ""
        var stationAe = ""
        var stationName = ""
        var spsDesc = ""
        var perfPhys = ""
        var spsLoc = ""

        val spsSeq = ds.getSequence(Tag.ScheduledProcedureStepSequence)
        if (spsSeq != null && !spsSeq.isEmpty) {
            val item = spsSeq[0]
            val mod = item.getString(Tag.Modality, "")
            if (mod.isNotEmpty()) modality = mod
            spsId = item.getString(Tag.ScheduledProcedureStepID, "")
            spsDate = item.getString(Tag.ScheduledProcedureStepStartDate, "")
            spsTime = item.getString(Tag.ScheduledProcedureStepStartTime, "")
            stationAe = item.getString(Tag.ScheduledStationAETitle, "")
            stationName = item.getString(Tag.ScheduledStationName, "")
            spsDesc = item.getString(Tag.ScheduledProcedureStepDescription, "")
            perfPhys = item.getString(Tag.ScheduledPerformingPhysicianName, "")
            spsLoc = item.getString(Tag.ScheduledProcedureStepLocation, "")
        }

        return MwlQueryResultItem(
            id = UUID.randomUUID().toString(),
            patientName = pName,
            patientId = pId,
            patientBirthDate = pBirth,
            patientSex = pSex,
            accessionNumber = acc,
            studyInstanceUid = studyUid,
            requestedProcedureId = rpId,
            requestedProcedureDescription = rpDesc,
            referringPhysicianName = refPhys,
            spsId = spsId,
            modality = modality,
            spsStartDate = spsDate,
            spsStartTime = spsTime,
            scheduledStationAeTitle = stationAe,
            scheduledStationName = stationName,
            spsDescription = spsDesc,
            scheduledPerformingPhysician = perfPhys,
            spsLocation = spsLoc,
            specificCharacterSet = cs,
            rawAttributes = ds
        )
    }
}
