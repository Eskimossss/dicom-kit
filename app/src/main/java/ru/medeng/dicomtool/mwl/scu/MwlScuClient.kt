package ru.medeng.dicomtool.mwl.scu

import org.dcm4che3.data.*
import org.dcm4che3.net.*
import org.dcm4che3.net.pdu.*
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.domain.model.*
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.*
import java.io.IOException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class MwlScuClient(private val logger: MwlTechnicalLogger) {
    companion object {
        val SUPPORTED_TRANSFER_SYNTAXES = arrayOf(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian)
    }
    private val active = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val cancelSent = AtomicBoolean(false)
    private val currentRsp = AtomicReference<DimseRSP?>()
    private val currentAssociation = AtomicReference<Association?>()
    private val currentSocket = AtomicReference<Socket?>()
    private val currentExecutor = AtomicReference<java.util.concurrent.ExecutorService?>()
    private val cancelDeadline = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)

    fun cancelQuery() {
        if (!active.get()) return
        cancelled.set(true)
        val rsp = currentRsp.get()
        val association = currentAssociation.get()
        if (rsp != null && association != null) {
            // DIMSE writes may block: never write on the UI thread.
            runCatching { currentExecutor.get()?.execute { sendCancel(rsp, association) } }
        } else runCatching { currentSocket.get()?.close() }
    }
    private fun sendCancel(rsp: DimseRSP, association: Association) {
        if (cancelSent.compareAndSet(false, true)) runCatching { rsp.cancel(association) }
    }

    fun queryWorklist(profile: MwlScuProfile, filter: MwlScuFilter, onProgress: (Int) -> Unit = {}): Result<MwlQueryOutcome> {
        if (!active.compareAndSet(false, true)) return Result.failure(IllegalStateException("MWL query already running"))
        cancelled.set(false); cancelSent.set(false); cancelDeadline.set(Long.MAX_VALUE)
        val session = DicomSessionManager.nextMwlScuSession()
        val results = mutableListOf<MwlQueryResultItem>()
        val executor = Executors.newCachedThreadPool()
        val scheduled = Executors.newSingleThreadScheduledExecutor()
        currentExecutor.set(executor)
        var association: Association? = null
        fun log(title: String, category: DicomLogCategory, status: Int? = null, error: Boolean = false) {
            logger.recordEvent(category = category, direction = DicomTrafficDirection.OUTBOUND,
                associationId = session.displayLabel, sessionUid = session.sessionUid, title = title,
                technicalDetails = buildMap { put("Received Items", results.size.toString()); status?.let { put("Status", "%04X".format(it)) } }, isError = error)
        }
        return try {
            require(profile.connectionTimeoutMs in 1..120000 && profile.responseTimeoutMs in 1..120000) { "MWL timeouts must be 1..120000 ms" }
            require(profile.port in 1..65535 && profile.host.isNotBlank()) { "Invalid MWL endpoint" }
            MwlValidation.requireValid(MwlValidation.validateAeTitle(profile.calledAet))
            MwlValidation.requireValid(MwlValidation.validateAeTitle(profile.callingAet))
            val device = Device("dicomkit-mwl-scu")
            val local = Connection().apply {
                connectTimeout = profile.connectionTimeoutMs
                acceptTimeout = profile.connectionTimeoutMs
                responseTimeout = profile.responseTimeoutMs
                retrieveTimeout = profile.responseTimeoutMs
                releaseTimeout = profile.connectionTimeoutMs
                socketCloseDelay = 0
            }
            val remote = Connection().apply { hostname = profile.host.trim(); port = profile.port }
            val ae = ApplicationEntity(profile.callingAet.trim()).apply { isAssociationInitiator = true; addConnection(local) }
            device.addConnection(local); device.addApplicationEntity(ae)
            device.executor = executor; device.scheduledExecutor = scheduled
            device.connectionMonitor = object : ConnectionMonitor {
                override fun onConnectionEstablished(conn: Connection, remoteConn: Connection, s: Socket) {
                    currentSocket.set(s)
                    if (cancelled.get()) s.close()
                }
                override fun onConnectionFailed(conn: Connection, remoteConn: Connection, s: Socket, e: Throwable) { s.close() }
                override fun onConnectionAccepted(conn: Connection, s: Socket) {}
                override fun onConnectionRejected(conn: Connection, s: Socket, e: Throwable) {}
                override fun onConnectionRejectedBlacklisted(conn: Connection, s: Socket) {}
            }
            scheduled.scheduleWithFixedDelay({
                if (cancelled.get()) {
                    val deadline = cancelDeadline.get()
                    if (deadline == Long.MAX_VALUE) cancelDeadline.compareAndSet(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(profile.responseTimeoutMs.toLong()))
                    else if (System.nanoTime() >= deadline) runCatching { currentSocket.get()?.close() }
                }
            }, 50, 50, TimeUnit.MILLISECONDS)
            val rq = AAssociateRQ().apply {
                callingAET = profile.callingAet.trim(); calledAET = profile.calledAet.trim()
                addPresentationContext(PresentationContext(1, UID.ModalityWorklistInformationModelFind, *SUPPORTED_TRANSFER_SYNTAXES))
                addPresentationContext(PresentationContext(3, UID.Verification, *SUPPORTED_TRANSFER_SYNTAXES))
            }
            log("A-ASSOCIATE-RQ MWL", DicomLogCategory.ASSOCIATE_RQ)
            val assoc = ae.connect(local, remote, rq)
            association = assoc; currentAssociation.set(assoc)
            if (cancelled.get()) throw java.util.concurrent.CancellationException("Cancelled during association")
            val pc = assoc.aAssociateAC.getPresentationContext(1)
            require(pc != null && pc.isAccepted) { "MWL Presentation Context rejected" }
            log("A-ASSOCIATE-AC MWL", DicomLogCategory.ASSOCIATE_AC)
            val keys = MwlDatasetBuilder.buildMwlScuQueryDataset(patientName = filter.patientName, patientId = filter.patientId,
                accessionNumber = filter.accessionNumber, modality = filter.modality, scheduledStationAe = filter.scheduledStationAe,
                dateRange = filter.buildDateRangePattern(), requestedProcedureId = filter.requestedProcedureId)
            log("C-FIND-RQ MWL", DicomLogCategory.C_ECHO_RQ)
            val rsp = assoc.cfind(UID.ModalityWorklistInformationModelFind, Priority.NORMAL, keys, pc.transferSyntax, 0)
            currentRsp.set(rsp)
            if (cancelled.get()) sendCancel(rsp, assoc)
            var finalStatus: Int? = null
            while (rsp.next()) {
                val cmd = rsp.command ?: throw IOException("Missing DIMSE command")
                if (!cmd.containsValue(Tag.Status)) throw IOException("Missing DIMSE Status")
                val status = cmd.getInt(Tag.Status, -1)
                if (Status.isPending(status)) {
                    val ds = rsp.dataset ?: throw IOException("Pending response has no Identifier")
                    results.add(parseMwlQueryResultItem(ds)); onProgress(results.size)
                    if (cancelled.get()) sendCancel(rsp, assoc)
                } else { finalStatus = status; break }
            }
            val status = finalStatus ?: throw IOException("C-FIND ended without final Status")
            log("C-FIND final %04X".format(status), DicomLogCategory.C_ECHO_RSP, status, status != Status.Success && status != Status.Cancel)
            runCatching { assoc.release(); assoc.waitForSocketClose() }.onFailure {
                runCatching { assoc.abort() }
                log("A-RELEASE failed after confirmed DIMSE final", DicomLogCategory.NETWORK_ERROR, error = true)
            }
            if (status != Status.Success && status != Status.Cancel) Result.failure(MwlQueryException(status, results.toList()))
            else Result.success(MwlQueryOutcome(results.toList(), status, cancelled.get()))
        } catch (e: Exception) {
            runCatching { association?.abort() }
            if (cancelled.get()) {
                log("MWL cancelled locally; remote Cancel not confirmed", DicomLogCategory.ABORT)
                Result.success(MwlQueryOutcome(results.toList(), null, true))
            } else {
                log("MWL query failed: ${e.javaClass.simpleName}", DicomLogCategory.NETWORK_ERROR, error = true)
                Result.failure(if (e is MwlQueryException) e else MwlQueryException(null, results.toList(), e))
            }
        } finally {
            currentRsp.set(null); currentAssociation.set(null)
            runCatching { currentSocket.getAndSet(null)?.close() }
            currentExecutor.set(null); scheduled.shutdownNow(); executor.shutdownNow(); active.set(false)
        }
    }

    private fun parseMwlQueryResultItem(ds: Attributes): MwlQueryResultItem {
        val cs = ds.getString(Tag.SpecificCharacterSet, "ISO_IR 6")
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
            rawAttributes = Attributes(ds)
        )
    }
}
