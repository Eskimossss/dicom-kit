package ru.medeng.dicomtool.dicom.scp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.AssociationHandler
import org.dcm4che3.net.AssociationMonitor
import org.dcm4che3.net.Commands
import org.dcm4che3.net.Connection
import org.dcm4che3.net.ConnectionMonitor
import org.dcm4che3.net.Device
import org.dcm4che3.net.Dimse
import org.dcm4che3.net.PDVInputStream
import org.dcm4che3.net.Status
import org.dcm4che3.net.TransferCapability
import org.dcm4che3.net.pdu.AAbort
import org.dcm4che3.net.pdu.AAssociateAC
import org.dcm4che3.net.pdu.AAssociateRJ
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.BasicCEchoSCP
import org.dcm4che3.net.service.BasicCStoreSCP
import org.dcm4che3.net.service.DicomServiceException
import org.dcm4che3.net.service.DicomServiceRegistry
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.data.storage.DicomSaveResult
import ru.medeng.dicomtool.data.storage.DicomStudyRepository
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.domain.model.NetworkInterfaceInfo
import ru.medeng.dicomtool.domain.model.ScpReceiverState
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Настоящий DICOM Storage SCP сервер на базе dcm4che 5.33.1 (PS3.7 DIMSE, PS3.8 Upper Layer, PS3.10 Media Storage).
 *
 * Реализует:
 * - Настраиваемый AE Title (по умолчанию ANDROID_PACS) и TCP-порт (по умолчанию 11112).
 * - Строгую валидацию AE Title и порта.
 * - C-ECHO SCP (Verification SOP Class 1.2.840.10008.1.1).
 * - C-STORE SCP для стандартных несжатых Transfer Syntax:
 *   - Implicit VR Little Endian (1.2.840.10008.1.2)
 *   - Explicit VR Little Endian (1.2.840.10008.1.2.1)
 * - Поддержку стандартных Storage SOP Classes (CT, DX, CR, MG, US, MR, XA, RF, NM, PT, SC и др. + wildcard fallback).
 * - Потоковый приём больших файлов напрямую из PDVInputStream во временный файл (.part) без чтения Pixel Data в ОЗУ.
 * - Чтение только заголовков (readDatasetUntilPixelData) для определения StudyInstanceUID / SeriesInstanceUID / SOPInstanceUID.
 * - Атомарное перемещение временного файла в постоянное хранилище с защитой от потери данных при повторной отправке того же SOPInstanceUID.
 * - Отправку C-STORE-RSP (0x0000 Success) строго после успешного сохранения и синхронизации файла на диске.
 * - Подробное журналирование всех сетевых и протокольных событий в PersistentDicomTechnicalLogger.
 */
class DicomStorageScpServer(
    private val studyRepository: DicomStudyRepository,
    private val logger: PersistentDicomTechnicalLogger
) {

    companion object {
        val UNCOMPRESSED_TRANSFER_SYNTAXES = arrayOf(
            UID.ExplicitVRLittleEndian,
            UID.ImplicitVRLittleEndian
        )

        /**
         * Основные стандартные Storage SOP Classes для рентгенов (DX/CR), маммографов (MG), КТ (CT),
         * УЗИ (US), МРТ (MR), ангиографии (XA) и Secondary Capture (SC).
         */
        val SUPPORTED_STORAGE_SOP_CLASSES = arrayOf(
            UID.CTImageStorage,
            UID.EnhancedCTImageStorage,
            UID.DigitalXRayImageStorageForPresentation,
            UID.DigitalXRayImageStorageForProcessing,
            UID.ComputedRadiographyImageStorage,
            UID.DigitalMammographyXRayImageStorageForPresentation,
            UID.DigitalMammographyXRayImageStorageForProcessing,
            UID.BreastTomosynthesisImageStorage,
            UID.UltrasoundImageStorage,
            UID.UltrasoundMultiFrameImageStorage,
            UID.MRImageStorage,
            UID.EnhancedMRImageStorage,
            UID.XRayAngiographicImageStorage,
            UID.XRayRadiofluoroscopicImageStorage,
            UID.NuclearMedicineImageStorage,
            UID.PositronEmissionTomographyImageStorage,
            UID.SecondaryCaptureImageStorage,
            UID.MultiFrameSingleBitSecondaryCaptureImageStorage,
            UID.MultiFrameGrayscaleByteSecondaryCaptureImageStorage,
            UID.MultiFrameGrayscaleWordSecondaryCaptureImageStorage,
            UID.MultiFrameTrueColorSecondaryCaptureImageStorage
        )
    }

    private val lock = Any()
    private var device: Device? = null
    private var executor: ExecutorService? = null
    private var scheduledExecutor: ScheduledExecutorService? = null
    private var uptimeTimer: Timer? = null
    private var serverStartTimeMs: Long = 0L

    @Volatile private var accepting = false
    @Volatile private var stopRequested = false
    private var cleanupStarted = false
    private val connectionGate = Any()
    private val sockets = ConcurrentHashMap<Socket, DicomSessionManager.SessionIdentity>()
    private val storing = ConcurrentHashMap.newKeySet<Socket>()
    private val associations = ConcurrentHashMap<Socket, Association>()

    private val _state = MutableStateFlow(
        ScpReceiverState(
            status = ScpServerStatus.STOPPED,
            callingAeTitle = "ANDROID_PACS",
            tcpPort = 11112,
            networkInfo = detectLocalIpv4Interface(),
            receivedStudiesCount = 0,
            receivedImagesCount = 0,
            storagePath = studyRepository.storageRootDir.absolutePath
        )
    )
    val state: StateFlow<ScpReceiverState> = _state.asStateFlow()

    init {
        refreshCountersFromRepository()
    }

    fun detectLocalIpv4Interface(): NetworkInterfaceInfo {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // Сначала ищем активный Wi-Fi или Ethernet интерфейс
            val preferred = interfaces.sortedBy { nif ->
                when {
                    nif.name.startsWith("wlan", ignoreCase = true) -> 0
                    nif.name.startsWith("eth", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            for (nif in preferred) {
                if (!nif.isUp || nif.isLoopback) continue
                val ipv4 = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                if (ipv4 != null) {
                    val label = when {
                        nif.name.startsWith("wlan", ignoreCase = true) -> "${nif.name} (Wi-Fi)"
                        nif.name.startsWith("eth", ignoreCase = true) -> "${nif.name} (Ethernet)"
                        else -> nif.name
                    }
                    return NetworkInterfaceInfo(
                        interfaceName = label,
                        ipv4Address = ipv4.hostAddress ?: "127.0.0.1"
                    )
                }
            }
            NetworkInterfaceInfo("lo (Loopback)", "127.0.0.1")
        } catch (_: Exception) {
            NetworkInterfaceInfo("lo (Loopback)", "127.0.0.1")
        }
    }

    fun refreshNetworkInfo() {
        _state.update { it.copy(networkInfo = detectLocalIpv4Interface()) }
    }

    fun updateConfiguration(aeTitle: String, port: Int) {
        if (_state.value.status in listOf(ScpServerStatus.RUNNING, ScpServerStatus.STARTING, ScpServerStatus.STOPPING)) return
        val aeCheck = DicomValidationUtils.validateAeTitle(aeTitle)
        val portCheck = DicomValidationUtils.validateScpPort(port)
        val validationError = when {
            aeCheck is DicomValidationUtils.ValidationResult.Invalid -> aeCheck.messageRu
            portCheck is DicomValidationUtils.ValidationResult.Invalid -> portCheck.messageRu
            else -> null
        }
        _state.update {
            it.copy(
                callingAeTitle = aeTitle,
                tcpPort = port,
                validationError = validationError,
                errorMessage = null
            )
        }
    }

    fun refreshCountersFromRepository() {
        studyRepository.refreshFromDisk()
        val studiesList = studyRepository.studiesFlow.value
        val studiesCount = studiesList.size
        val imagesCount = studiesList.sumOf { it.instancesCount }
        val activeStoragePath = studyRepository.storageManager?.let { sm ->
            if (sm.getCurrentStorageType() == ru.medeng.dicomtool.data.storage.StorageType.SAF) {
                sm.getSavedSafTreeUri()?.let { sm.getReadablePathForSaf(it) }
            } else null
        } ?: studyRepository.storageRootDir.absolutePath

        _state.update {
            it.copy(
                receivedStudiesCount = studiesCount,
                receivedImagesCount = imagesCount,
                storagePath = activeStoragePath
            )
        }
    }

    /**
     * Запускает настоящий DICOM Storage SCP сервер на указанном AE Title и TCP-порту.
     */
    fun startServer(
        aeTitle: String = _state.value.callingAeTitle.trim(),
        port: Int = _state.value.tcpPort
    ): Result<Unit> = synchronized(lock) {
        if (_state.value.status == ScpServerStatus.STOPPING) {
            return Result.failure(IllegalStateException("SCP ещё освобождает ресурсы"))
        }
        if (_state.value.status == ScpServerStatus.RUNNING) {
            return Result.success(Unit)
        }

        val aeValidation = DicomValidationUtils.validateAeTitle(aeTitle)
        if (aeValidation is DicomValidationUtils.ValidationResult.Invalid) {
            _state.update {
                it.copy(
                    validationError = aeValidation.messageRu,
                    errorMessage = aeValidation.messageRu
                )
            }
            return Result.failure(IllegalArgumentException(aeValidation.messageRu))
        }

        val portValidation = DicomValidationUtils.validateScpPort(port)
        if (portValidation is DicomValidationUtils.ValidationResult.Invalid) {
            _state.update {
                it.copy(
                    validationError = portValidation.messageRu,
                    errorMessage = portValidation.messageRu
                )
            }
            return Result.failure(IllegalArgumentException(portValidation.messageRu))
        }

        stopRequested = false
        cleanupStarted = false
        _state.update {
            it.copy(
                status = ScpServerStatus.STARTING,
                callingAeTitle = aeTitle,
                tcpPort = port,
                networkInfo = detectLocalIpv4Interface(),
                validationError = null,
                errorMessage = null
            )
        }

        return try {
            val exec = Executors.newCachedThreadPool().also { executor = it }
            val sched = Executors.newSingleThreadScheduledExecutor().also { scheduledExecutor = it }

            val scpDevice = Device("dicomtool-scp-device")
            val conn = Connection("dicom-scp-conn", "0.0.0.0", port).apply {
                maxOpsInvoked = 0
                maxOpsPerformed = 0
                receivePDULength = 65536
                sendPDULength = 65536
                isPackPDV = true
                isTcpNoDelay = true
                acceptTimeout = 15000
                releaseTimeout = 10000
                responseTimeout = 30000
                retrieveTimeout = 60000
                idleTimeout = 60000
            }

            val ae = ApplicationEntity(aeTitle).apply {
                isAssociationAcceptor = true
                isAssociationInitiator = false
                addConnection(conn)

                // Verification SOP Class (C-ECHO)
                addTransferCapability(
                    TransferCapability(
                        "Verification-SCP",
                        UID.Verification,
                        TransferCapability.Role.SCP,
                        UID.ImplicitVRLittleEndian,
                        UID.ExplicitVRLittleEndian
                    )
                )

                // Явная регистрация всех стандартных Storage SOP Classes (CT, DX, CR, MG, US, MR, XA, SC и др.)
                for (sopUid in SUPPORTED_STORAGE_SOP_CLASSES) {
                    val name = runCatching { UID.nameOf(sopUid) }.getOrDefault(sopUid)
                    addTransferCapability(
                        TransferCapability(
                            name,
                            sopUid,
                            TransferCapability.Role.SCP,
                            *UNCOMPRESSED_TRANSFER_SYNTAXES
                        )
                    )
                }

                // Также добавляем wildcard "*" для любых дополнительных Storage SOP Classes с несжатыми Transfer Syntaxes
                addTransferCapability(
                    TransferCapability(
                        "Wildcard-Uncompressed-SCP",
                        "*",
                        TransferCapability.Role.SCP,
                        *UNCOMPRESSED_TRANSFER_SYNTAXES
                    )
                )
            }

            val serviceRegistry = DicomServiceRegistry().apply {
                addDicomService(createLoggingCEchoScp())
                addDicomService(createStreamingCStoreScp())
            }

            scpDevice.addConnection(conn)
            scpDevice.addApplicationEntity(ae)
            scpDevice.dimseRQHandler = serviceRegistry
            scpDevice.connectionMonitor = createConnectionMonitor()
            scpDevice.associationMonitor = createAssociationMonitor()
            scpDevice.associationHandler = createAssociationHandler()
            scpDevice.executor = exec
            scpDevice.scheduledExecutor = sched

            // Publish resources before bind so a partial start failure can release them.
            device = scpDevice
            executor = exec
            scheduledExecutor = sched
            synchronized(connectionGate) {
                if (stopRequested) throw IOException("SCP start cancelled")
                accepting = true
                scpDevice.bindConnections()
            }
            serverStartTimeMs = System.currentTimeMillis()
            startUptimeTicker()

            val netInfo = detectLocalIpv4Interface()
            refreshCountersFromRepository()
            synchronized(connectionGate) {
                if (stopRequested) throw IOException("SCP start cancelled")
                _state.update {
                it.copy(
                    status = ScpServerStatus.RUNNING,
                    callingAeTitle = aeTitle,
                    tcpPort = port,
                    networkInfo = netInfo,
                    uptimeFormatted = "00:00:00",
                    errorMessage = null
                )
                }
            }

            logger.recordTechnicalEvent(
                category = DicomLogCategory.SERVER_LIFECYCLE,
                direction = DicomTrafficDirection.LOCAL,
                associationId = "SCP",
                title = "DICOM Storage SCP started on ${netInfo.ipv4Address}:$port (AE: $aeTitle)",
                summaryLines = listOf(
                    "Listen Address: 0.0.0.0:$port (${netInfo.interfaceName})",
                    "Called AE Title: $aeTitle",
                    "Transfer Syntaxes: Implicit VR Little Endian, Explicit VR Little Endian"
                ),
                technicalDetails = mapOf(
                    "AE Title" to aeTitle,
                    "Bind Address" to "0.0.0.0:$port",
                    "Local IPv4" to netInfo.ipv4Address,
                    "Network Interface" to netInfo.interfaceName,
                    "Supported Transfer Syntaxes" to "1.2.840.10008.1.2 (Implicit VR LE), 1.2.840.10008.1.2.1 (Explicit VR LE)",
                    "Storage Path" to studyRepository.storageRootDir.absolutePath
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            stopServerInternal(logStop = false)
            val errMsg = _state.value.errorMessage?.takeIf { stopRequested }
                ?: "Не удалось запустить SCP на порту $port: ${e.message ?: e.javaClass.simpleName}"
            _state.update {
                it.copy(
                    status = if (it.status == ScpServerStatus.STOPPING) it.status else ScpServerStatus.ERROR,
                    errorMessage = errMsg
                )
            }
            logger.recordTechnicalEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.LOCAL,
                associationId = "SCP",
                title = "SCP start error on port $port",
                summaryLines = listOf(errMsg),
                technicalDetails = mapOf(
                    "Port" to port.toString(),
                    "AE Title" to aeTitle,
                    "Exception" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true
            )
            Result.failure(e)
        }
    }

    /**
     * Останавливает DICOM Storage SCP и освобождает TCP-порт и пулы потоков.
     */
    fun stopServer() = synchronized(lock) {
        stopServerInternal(logStop = true)
    }

    /** OS timeout / Service destruction: close networking without waiting for a blocked SAF writer. */
    fun requestNetworkStop() {
        synchronized(connectionGate) {
            stopRequested = true
            accepting = false
            runCatching { device?.unbindConnections() }
            sockets.keys.forEach { runCatching { it.close() } }
            _state.update { if (it.status in listOf(ScpServerStatus.STARTING, ScpServerStatus.RUNNING))
                it.copy(status = ScpServerStatus.STOPPING) else it }
        }
    }

    private fun stopServerInternal(logStop: Boolean) {
        if (device == null && executor == null) return
        if (cleanupStarted) {
            finishStopIfTerminated()
            return
        }
        cleanupStarted = true
        _state.update { it.copy(status = ScpServerStatus.STOPPING) }
        synchronized(connectionGate) { accepting = false; stopRequested = true }
        if (uptimeTimer == null) startUptimeTicker()
        try { device?.unbindConnections() } catch (e: Exception) {
            reportServiceError("Ошибка закрытия слушателя SCP: $e")
        }
        // Allow a bounded drain for in-flight storage. Release idle associations off this thread:
        // an encoder can be blocked on a peer that is no longer reading.
        device?.listOpenAssociations()?.forEach { assoc ->
            executor?.execute { runCatching { if (assoc.isReadyForDataTransfer && !storing.contains(assoc.socket)) assoc.release() } }
        }
        executor?.shutdown()
        executor?.awaitTermination(1200, TimeUnit.MILLISECONDS)
        synchronized(connectionGate) { sockets.keys.forEach { runCatching { it.close() } } }
        executor?.shutdownNow()
        scheduledExecutor?.shutdownNow()
        executor?.awaitTermination(300, TimeUnit.MILLISECONDS)
        finishStopIfTerminated()
        if (logStop) logger.recordTechnicalEvent(
            category = DicomLogCategory.SERVER_LIFECYCLE, direction = DicomTrafficDirection.LOCAL,
            associationId = "SCP", title = "SCP stop: listener closed; connections drained or forcibly closed",
            summaryLines = listOf("State: ${_state.value.status}"),
            technicalDetails = mapOf("State" to _state.value.status.name))
    }

    private fun finishStopIfTerminated() {
        if (_state.value.status != ScpServerStatus.STOPPING || executor?.isTerminated == false ||
            scheduledExecutor?.isTerminated == false) return
        updateConnections()
        sockets.clear()
        associations.clear()
        storing.clear()
        loggedRejectedAssocIds.clear()
        device = null
        executor = null
        scheduledExecutor = null
        uptimeTimer?.cancel()
        uptimeTimer = null
        _state.update { it.copy(status = ScpServerStatus.STOPPED, activeConnectionsCount = 0,
            connectedNodes = emptyList(), uptimeFormatted = "00:00:00") }
    }

    fun setServiceStopping(stopping: Boolean) {
        _state.update { it.copy(serviceStopping = stopping) }
    }

    fun reportServiceError(message: String) {
        logger.recordTechnicalEvent(category = DicomLogCategory.SERVER_LIFECYCLE,
            direction = DicomTrafficDirection.LOCAL, associationId = "SCP", title = "SCP service warning",
            summaryLines = listOf(message), technicalDetails = mapOf("Reason" to message), isError = true)
        _state.update { it.copy(errorMessage = message,
            status = if (it.status == ScpServerStatus.STOPPED) ScpServerStatus.ERROR else it.status) }
    }

    private fun identity(socket: Socket): DicomSessionManager.SessionIdentity =
        sockets.computeIfAbsent(socket) { DicomSessionManager.nextScpSession() }

    private fun updateConnections() {
        sockets.keys.filter { it.isClosed }.forEach { socket ->
            val session = sockets.remove(socket) ?: return@forEach
            val ex = associations.remove(socket)?.exception
            logger.recordTechnicalEvent(category = DicomLogCategory.TCP_DISCONNECT,
                direction = DicomTrafficDirection.LOCAL, associationId = session.displayLabel,
                title = "TCP socket locally closed; release PDU not inferred", summaryLines = emptyList(),
                technicalDetails = mapOf("Remote Socket" to "${socket.inetAddress?.hostAddress}:${socket.port}",
                    "Exception" to (ex?.toString() ?: "No exception observed")),
                isError = ex != null, sessionUid = session.sessionUid)
        }
        val nodes = associations.values.map { "${it.callingAET} (${it.socket.inetAddress.hostAddress})" }.distinct()
        _state.update { it.copy(activeConnectionsCount = sockets.size, connectedNodes = nodes) }
    }

    private fun createConnectionMonitor(): ConnectionMonitor = object : ConnectionMonitor {
        override fun onConnectionEstablished(conn: Connection, remoteConn: Connection, s: Socket) {
            // используется для исходящих соединений
        }

        override fun onConnectionFailed(conn: Connection, remoteConn: Connection, s: Socket, e: Throwable) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "TCP",
                title = "TCP connection failed from ${s.inetAddress?.hostAddress}:${s.port}",
                summaryLines = listOf("Error: ${e.message ?: e.javaClass.simpleName}"),
                technicalDetails = mapOf(
                    "Remote Address" to "${s.inetAddress?.hostAddress}:${s.port}",
                    "Exception" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true
            )
        }

        override fun onConnectionRejectedBlacklisted(conn: Connection, s: Socket) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "TCP",
                title = "TCP connection rejected (blacklisted): ${s.inetAddress?.hostAddress}:${s.port}",
                summaryLines = emptyList(),
                technicalDetails = mapOf(
                    "Remote Address" to "${s.inetAddress?.hostAddress}:${s.port}"
                ),
                isError = true
            )
        }

        override fun onConnectionRejected(conn: Connection, s: Socket, e: Throwable) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "TCP",
                title = "TCP connection rejected: ${s.inetAddress?.hostAddress}:${s.port}",
                summaryLines = listOf("Reason: ${e.message ?: e.javaClass.simpleName}"),
                technicalDetails = mapOf(
                    "Remote Address" to "${s.inetAddress?.hostAddress}:${s.port}",
                    "Exception" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true
            )
        }

        override fun onConnectionAccepted(conn: Connection, s: Socket) {
            synchronized(connectionGate) {
                if (!accepting) { s.close(); return }
                identity(s)
            }
            val currentActive = sockets.size
            _state.update { it.copy(activeConnectionsCount = currentActive) }
            val remoteIp = s.inetAddress?.hostAddress ?: "unknown"
            val remotePort = s.port
            val tcpSession = identity(s)
            logger.recordTechnicalEvent(
                category = DicomLogCategory.TCP_CONNECT,
                direction = DicomTrafficDirection.INBOUND,
                associationId = tcpSession.displayLabel,
                title = "TCP connected from $remoteIp:$remotePort",
                summaryLines = listOf("Local Listen Port: ${conn.port}"),
                technicalDetails = mapOf(
                    "Remote IP" to remoteIp,
                    "Remote Port" to remotePort.toString(),
                    "Local Port" to conn.port.toString(),
                    "Active Connections" to currentActive.toString()
                ),
                sessionUid = tcpSession.sessionUid
            )
        }
    }

    private val loggedRejectedAssocIds = ConcurrentHashMap.newKeySet<String>()

    private fun logAssociationRejection(asAssoc: Association, rq: AAssociateRQ?, rj: AAssociateRJ, localWritten: Boolean = false) {
        val assocId = formatAssocId(asAssoc)
        val sessionUid = formatSessionUid(asAssoc)
        if (!loggedRejectedAssocIds.add("$assocId:$localWritten")) {
            return
        }
        val remoteIp = asAssoc.socket?.inetAddress?.hostAddress ?: "unknown"
        val remotePort = asAssoc.socket?.port ?: 0

        if (rq != null && !localWritten) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "A-ASSOCIATE-RQ received",
                summaryLines = listOf(
                    "Calling AE: ${rq.callingAET}  ->  Called AE: ${rq.calledAET}",
                    "Remote: $remoteIp:$remotePort"
                ),
                technicalDetails = mapOf(
                    "Association ID" to assocId,
                    "Calling AE Title" to rq.callingAET,
                    "Called AE Title" to rq.calledAET,
                    "Remote Socket" to "$remoteIp:$remotePort"
                ),
                sessionUid = sessionUid
            )
        }

        logger.recordTechnicalEvent(
            category = if (localWritten) DicomLogCategory.ASSOCIATE_RJ else DicomLogCategory.SERVER_LIFECYCLE,
            direction = DicomTrafficDirection.OUTBOUND,
            associationId = assocId,
            title = if (localWritten) "A-ASSOCIATE-RJ local write completed; peer receipt unconfirmed" else "A-ASSOCIATE-RJ selected; local write not yet confirmed",
            summaryLines = listOf(
                "Calling AE: ${rq?.callingAET ?: asAssoc.callingAET} | Called AE: ${rq?.calledAET ?: asAssoc.calledAET}",
                "Reason: ${explainAssociateRjReason(rj)}"
            ),
            technicalDetails = mapOf(
                "Association ID" to assocId,
                "Remote Socket" to "$remoteIp:$remotePort",
                "Calling AE Title" to (rq?.callingAET ?: asAssoc.callingAET ?: "-"),
                "Called AE Title" to (rq?.calledAET ?: asAssoc.calledAET ?: "-"),
                "RJ Result" to rj.result.toString(),
                "RJ Source" to rj.source.toString(),
                "RJ Reason" to "${rj.reason} (${explainAssociateRjReason(rj)})"
            ),
            isError = true,
            sessionUid = sessionUid
        )
    }

    private fun createAssociationMonitor(): AssociationMonitor = object : AssociationMonitor {
        override fun onAssociationEstablished(asAssoc: Association) {
            associationIdentity(asAssoc)
            associations[asAssoc.socket] = asAssoc
        }

        override fun onAssociationAccepted(asAssoc: Association) {
            associationIdentity(asAssoc)
            associations[asAssoc.socket] = asAssoc
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val rq: AAssociateRQ? = asAssoc.aAssociateRQ
            val ac: AAssociateAC? = asAssoc.aAssociateAC
            val remoteIp = asAssoc.socket?.inetAddress?.hostAddress ?: "unknown"
            val remotePort = asAssoc.socket?.port ?: 0

            updateConnections()

            if (rq != null) {
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.ASSOCIATE_RQ,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    title = "A-ASSOCIATE-RQ received",
                    summaryLines = listOf(
                        "Calling AE: ${rq.callingAET}  ->  Called AE: ${rq.calledAET}",
                        "Remote: $remoteIp:$remotePort | Presentation Contexts: ${rq.numberOfPresentationContexts}"
                    ),
                    technicalDetails = mapOf(
                        "Association ID" to assocId,
                        "Remote Socket" to "$remoteIp:$remotePort",
                        "Calling AE Title" to rq.callingAET,
                        "Called AE Title" to rq.calledAET,
                        "Application Context" to rq.applicationContext,
                        "Max PDU Length (RQ)" to "${rq.maxPDULength} bytes",
                        "Implementation Class UID" to (rq.implClassUID ?: "-"),
                        "Implementation Version" to (rq.implVersionName ?: "-"),
                        "Presentation Context Count" to rq.numberOfPresentationContexts.toString()
                    ),
                    sessionUid = sessionUid
                )
            }

            if (ac != null) {
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.ASSOCIATE_AC,
                    direction = DicomTrafficDirection.OUTBOUND,
                    associationId = assocId,
                    title = "A-ASSOCIATE-AC local write completed; peer receipt unconfirmed",
                    summaryLines = listOf(
                        "Result: Accepted (${ac.callingAET} -> ${ac.calledAET})"
                    ),
                    technicalDetails = mapOf(
                        "Association ID" to assocId,
                        "Calling AE Title" to ac.callingAET,
                        "Called AE Title" to ac.calledAET,
                        "Max PDU Length (AC)" to "${ac.maxPDULength} bytes",
                        "Responder Implementation Version" to (ac.implVersionName ?: "-")
                    ),
                    sessionUid = sessionUid
                )

                // Детальное логирование каждого согласованного Presentation Context
                val rqPcs = rq?.presentationContexts.orEmpty().associateBy { it.pcid }
                for (acPc in ac.presentationContexts) {
                    val rqPc = rqPcs[acPc.pcid]
                    val abstractSyntaxUid = rqPc?.abstractSyntax ?: asAssoc.getAbstractSyntax(acPc.pcid) ?: "-"
                    val abstractSyntaxName = formatUidWithName(abstractSyntaxUid)
                    val proposedTs = rqPc?.transferSyntaxes?.joinToString(", ") { formatUidWithName(it) } ?: "-"
                    val acceptedTs = acPc.transferSyntax?.let { formatUidWithName(it) } ?: "-"
                    val resultLabel = formatPcResult(acPc.result)

                    logger.recordTechnicalEvent(
                        category = DicomLogCategory.PRESENTATION_CONTEXT,
                        direction = DicomTrafficDirection.OUTBOUND,
                        associationId = assocId,
                        title = if (acPc.isAccepted) {
                            "Presentation Context #${acPc.pcid} accepted"
                        } else {
                            "Presentation Context #${acPc.pcid} rejected ($resultLabel)"
                        },
                        summaryLines = listOf(
                            "PC ID: ${acPc.pcid} | Result: $resultLabel",
                            "SOP Class: $abstractSyntaxName",
                            "Transfer Syntax: $acceptedTs"
                        ),
                        technicalDetails = mapOf(
                            "Association ID" to assocId,
                            "Presentation Context ID" to acPc.pcid.toString(),
                            "Negotiation Result" to "${acPc.result} ($resultLabel)",
                            "Abstract Syntax UID" to abstractSyntaxName,
                            "Proposed Transfer Syntaxes" to proposedTs,
                            "Accepted Transfer Syntax" to acceptedTs
                        ),
                        isError = !acPc.isAccepted,
                        sessionUid = sessionUid
                    )
                }
            }
        }

        override fun onAssociationRejected(asAssoc: Association, rj: AAssociateRJ) {
            logAssociationRejection(asAssoc, asAssoc.aAssociateRQ, rj, localWritten = true)
        }

        override fun onAssociationFailed(asAssoc: Association, e: Throwable) {
            if (e is AAssociateRJ) {
                logAssociationRejection(asAssoc, asAssoc.aAssociateRQ, e)
                return
            }
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val isAbort = e is AAbort
            logger.recordTechnicalEvent(
                category = if (isAbort) DicomLogCategory.ABORT else DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = if (isAbort) "A-ABORT (${e.message})" else "Association error: ${e.javaClass.simpleName}",
                summaryLines = listOf(
                    "Calling AE: ${asAssoc.callingAET ?: "-"} | Detail: ${e.message ?: e.javaClass.simpleName}"
                ),
                technicalDetails = mapOf(
                    "Association ID" to assocId,
                    "Calling AE Title" to (asAssoc.callingAET ?: "-"),
                    "Called AE Title" to (asAssoc.calledAET ?: "-"),
                    "Exception" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true,
                sessionUid = sessionUid
            )
        }
    }

    private fun createAssociationHandler(): AssociationHandler = object : AssociationHandler() {
        override fun negotiate(asAssoc: Association, rq: AAssociateRQ): AAssociateAC {
            associationIdentity(asAssoc)
            associations[asAssoc.socket] = asAssoc
            if (!accepting) throw AAssociateRJ(AAssociateRJ.RESULT_REJECTED_TRANSIENT,
                AAssociateRJ.SOURCE_SERVICE_PROVIDER_ACSE, 1)
            return try {
                // Проверяем совпадение Called AE Title с настроенным AE Title приёмника
                val configuredAe = _state.value.callingAeTitle.trim()
                if (configuredAe.isNotEmpty() && !rq.calledAET.trim().equals(configuredAe, ignoreCase = false)) {
                    throw AAssociateRJ(
                        AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED
                    )
                }
                super.negotiate(asAssoc, rq)
            } catch (rj: AAssociateRJ) {
                logAssociationRejection(asAssoc, rq, rj)
                throw rj
            }
        }

        override fun onClose(asAssoc: Association) {
            super.onClose(asAssoc)
            val session = associationIdentity(asAssoc)
            val ex = asAssoc.exception
            logger.recordTechnicalEvent(
                category = when { ex is AAbort -> DicomLogCategory.ABORT; ex != null -> DicomLogCategory.NETWORK_ERROR; else -> DicomLogCategory.SERVER_LIFECYCLE },
                direction = DicomTrafficDirection.LOCAL, associationId = session.displayLabel,
                title = if (ex is AAbort) "dcm4che reported A-ABORT; wire direction not inferred" else "Association processing ended; release PDU not inferred",
                summaryLines = listOf(ex?.toString() ?: "No exception reported"),
                technicalDetails = mapOf("Exception" to (ex?.toString() ?: "none")),
                isError = ex != null, sessionUid = session.sessionUid)
            // Some decoder callbacks run before the delayed physical close; ticker prunes sockets.
            updateConnections()
        }
    }

    private fun createLoggingCEchoScp(): BasicCEchoSCP = object : BasicCEchoSCP(UID.Verification) {
        override fun onDimseRQ(
            asAssoc: Association,
            pc: PresentationContext,
            dimse: Dimse,
            cmd: Attributes,
            data: Attributes?
        ) {
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val msgId = cmd.getInt(Tag.MessageID, 0)
            val sopClassUid = cmd.getString(Tag.AffectedSOPClassUID, UID.Verification)

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_ECHO_RQ,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "C-ECHO-RQ received (Message ID: $msgId)",
                summaryLines = listOf(
                    "PC ID: ${pc.pcid} | Calling AE: ${asAssoc.callingAET}",
                    "SOP Class: ${formatUidWithName(sopClassUid)}"
                ),
                technicalDetails = mapOf(
                    "Association ID" to assocId,
                    "DIMSE Command" to "0030H (C-ECHO-RQ)",
                    "Message ID" to msgId.toString(),
                    "Presentation Context ID" to pc.pcid.toString(),
                    "Affected SOP Class UID" to formatUidWithName(sopClassUid),
                    "Transfer Syntax UID" to formatUidWithName(pc.transferSyntax ?: UID.ImplicitVRLittleEndian)
                ),
                sessionUid = sessionUid
            )

            if (dimse != Dimse.C_ECHO_RQ) throw DicomServiceException(Status.UnrecognizedOperation)
            writeResponse(asAssoc, pc, Commands.mkEchoRSP(cmd, Status.Success), DicomLogCategory.C_ECHO_RSP)
            return


        }
    }

    /**
     * Потоковый C-STORE SCP:
     * 1. Записывает File Meta Information (0002,xxxx) и весь поток данных PDVInputStream напрямую во временный файл `.part`
     *    блоками без загрузки Pixel Data в оперативную память.
     * 2. Выполняет fsync временного файла.
     * 3. Читает только заголовки (`readDatasetUntilPixelData`) из временного файла для получения StudyInstanceUID и SeriesInstanceUID.
     * 4. Проверяет, существует ли уже файл с тем же SOPInstanceUID:
     *    - Если файлы побайтово идентичны (повторная отправка того же снимка) — удаляет временный файл и возвращает `0x0000 (Success)`.
     *    - Если содержимое отличается при том же SOPInstanceUID — сохраняет оба файла без перезаписи оригинала (`<SOPInstanceUID>_dup_<timestamp>.dcm`), чтобы исключить потерю данных.
     * 5. Только после успешного сохранения файла формирует и отправляет `C-STORE-RSP` (`0x0000 Success`).
     */
    private fun createStreamingCStoreScp(): BasicCStoreSCP = object : BasicCStoreSCP("*") {

        override fun onDimseRQ(
            asAssoc: Association,
            pc: PresentationContext,
            dimse: Dimse,
            rq: Attributes,
            data: PDVInputStream
        ) {
            if (dimse != Dimse.C_STORE_RQ) {
                throw DicomServiceException(Status.UnrecognizedOperation)
            }
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val msgId = rq.getInt(Tag.MessageID, 0)
            val cuid = rq.getString(Tag.AffectedSOPClassUID, "")
            val iuid = rq.getString(Tag.AffectedSOPInstanceUID, "")
            val tsuid = pc.transferSyntax ?: UID.ImplicitVRLittleEndian

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_STORE_RQ,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "C-STORE-RQ receiving (Message ID: $msgId)",
                summaryLines = listOf(
                    "SOP Class: ${formatUidWithName(cuid)}",
                    "SOP Instance UID: $iuid"
                ),
                technicalDetails = mapOf(
                    "Association ID" to assocId,
                    "DIMSE Command" to "0001H (C-STORE-RQ)",
                    "Message ID" to msgId.toString(),
                    "Presentation Context ID" to pc.pcid.toString(),
                    "Affected SOP Class UID" to formatUidWithName(cuid),
                    "Affected SOP Instance UID" to iuid,
                    "Transfer Syntax UID" to formatUidWithName(tsuid),
                    "Calling AE Title" to (asAssoc.callingAET ?: "-"),
                    "Called AE Title" to (asAssoc.calledAET ?: "-")
                ),
                sessionUid = sessionUid
            )

            val rsp = Commands.mkCStoreRSP(rq, Status.Success)
            storing.add(asAssoc.socket)
            try {
                store(asAssoc, pc, rq, data, rsp)
            } catch (e: DicomServiceException) {
                rsp.setInt(Tag.Status, org.dcm4che3.data.VR.US, e.status)
                rsp.setString(Tag.ErrorComment, org.dcm4che3.data.VR.LO, e.message?.take(64))
            }
            try { writeResponse(asAssoc, pc, rsp, DicomLogCategory.C_STORE_RSP) }
            finally { storing.remove(asAssoc.socket) }
        }

        override fun store(
            asAssoc: Association,
            pc: PresentationContext,
            rq: Attributes,
            data: PDVInputStream,
            rsp: Attributes
        ) {
            val startNs = System.nanoTime()
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val msgId = rq.getInt(Tag.MessageID, 0)
            val cuid = rq.getString(Tag.AffectedSOPClassUID)?.takeIf { it.isNotBlank() }
                ?: throw DicomServiceException(Status.MissingAttributeValue, "Missing AffectedSOPClassUID")
            val iuid = rq.getString(Tag.AffectedSOPInstanceUID)?.takeIf { it.isNotBlank() }
                ?: throw DicomServiceException(Status.MissingAttributeValue, "Missing AffectedSOPInstanceUID")
            val tsuid = pc.transferSyntax ?: UID.ImplicitVRLittleEndian

            val tmpDir = File(studyRepository.storageRootDir, ".incoming_tmp").apply { mkdirs() }
            val tempFile = File(tmpDir, "${sanitizeFileName(iuid)}_${System.nanoTime()}.part")

            var payloadComplete = false
            try {
                // 1. Потоковая запись DICOM Part 10 (File Meta Information + PDV поток) без чтения Pixel Data в ОЗУ
                val fmi = asAssoc.createFileMetaInformation(iuid, cuid, tsuid)
                FileOutputStream(tempFile).use { fos ->
                    DicomOutputStream(BufferedOutputStream(fos, 65536), UID.ExplicitVRLittleEndian).use { dos ->
                        dos.writeFileMetaInformation(fmi)
                        data.copyTo(dos)
                        dos.flush()
                        fos.fd.sync()
                    }
                }

                payloadComplete = true

                // 2. Читаем только теги заголовка (до PixelData) для валидации и раскладки по Study/Series папкам
                val studyUid: String
                val seriesUid: String
                val datasetSopUid: String
                DicomInputStream(tempFile).use { dis ->
                    dis.includeBulkData = DicomInputStream.IncludeBulkData.NO
                    dis.readFileMetaInformation()
                    val headerAttrs = dis.readDatasetUntilPixelData()
                    studyUid = headerAttrs.getString(Tag.StudyInstanceUID)?.takeIf { it.isNotBlank() } ?: "UNKNOWN_STUDY"
                    seriesUid = headerAttrs.getString(Tag.SeriesInstanceUID)?.takeIf { it.isNotBlank() } ?: "UNKNOWN_SERIES"
                    datasetSopUid = headerAttrs.getString(Tag.SOPInstanceUID)?.takeIf { it.isNotBlank() } ?: iuid
                }

                // 3. Сохранение файла через репозиторий хранилища (Internal или SAF)
                val saveResult = studyRepository.saveIncomingInstance(
                    tempIncomingFile = tempFile,
                    studyInstanceUid = studyUid,
                    seriesInstanceUid = seriesUid,
                    sopInstanceUid = datasetSopUid
                )

                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0

                when (saveResult) {
                    is DicomSaveResult.Success -> {
                        refreshCountersFromRepository()

                        logger.recordTechnicalEvent(
                            category = DicomLogCategory.SERVER_LIFECYCLE,
                            direction = DicomTrafficDirection.OUTBOUND,
                            associationId = assocId,
                            title = "C-STORE storage verified (${formatByteCount(saveResult.savedSizeBytes)}); response pending",
                            summaryLines = listOf(
                                "Status: 0x0000 (Success) | Время: ${String.format(Locale.US, "%.3f s", elapsedSec)}",
                                "SOP Instance UID: $datasetSopUid"
                            ),
                            technicalDetails = mapOf(
                                "Association ID" to assocId,
                                "DIMSE Command" to "8001H (C-STORE-RSP)",
                                "Message ID Being Responded To" to msgId.toString(),
                                "DICOM Status" to "0x0000 (Success)",
                                "Study Instance UID" to studyUid,
                                "Series Instance UID" to seriesUid,
                                "SOP Class UID" to formatUidWithName(cuid),
                                "SOP Instance UID" to datasetSopUid,
                                "Transfer Syntax UID" to formatUidWithName(tsuid),
                                "Saved Destination" to saveResult.destinationDescription,
                                "Payload Size" to "${saveResult.savedSizeBytes} bytes (${formatByteCount(saveResult.savedSizeBytes)})",
                                "Duration" to String.format(Locale.US, "%.4f s", elapsedSec),
                                "Duplicate Policy" to saveResult.duplicateActionNote
                            ),
                            sessionUid = sessionUid
                        )
                    }

                    is DicomSaveResult.Failure -> {
                        val status = if (saveResult.isOutOfSpace) Status.OutOfResources else Status.ProcessingFailure
                        val statusHex = if (saveResult.isOutOfSpace) "0xA700 (OutOfResources)" else "0x0110 (ProcessingFailure)"
                        logger.recordTechnicalEvent(
                            category = DicomLogCategory.SERVER_LIFECYCLE,
                            direction = DicomTrafficDirection.OUTBOUND,
                            associationId = assocId,
                            title = "C-STORE storage failed ($statusHex); response pending",
                            summaryLines = listOf(
                                "SOP Instance UID: $datasetSopUid",
                                "Error: ${saveResult.errorReason}"
                            ),
                            technicalDetails = mapOf(
                                "Association ID" to assocId,
                                "Message ID" to msgId.toString(),
                                "DICOM Status" to statusHex,
                                "SOP Class UID" to formatUidWithName(cuid),
                                "SOP Instance UID" to datasetSopUid,
                                "Duration" to String.format(Locale.US, "%.4f s", elapsedSec),
                                "Reason" to saveResult.errorReason
                            ),
                            isError = true,
                            sessionUid = sessionUid
                        )
                        throw DicomServiceException(status, saveResult.errorReason)
                    }
                }
            } catch (e: Exception) {
                if (!payloadComplete && tempFile.exists()) {
                    tempFile.delete() // Incomplete network payload; complete failed saves remain recoverable.
                }
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.SERVER_LIFECYCLE,
                    direction = DicomTrafficDirection.OUTBOUND,
                    associationId = assocId,
                    title = "C-STORE processing failed; response pending",
                    summaryLines = listOf(
                        "SOP Instance UID: $iuid",
                        "Error: ${e.message ?: e.javaClass.simpleName}"
                    ),
                    technicalDetails = mapOf(
                        "Association ID" to assocId,
                        "Message ID" to msgId.toString(),
                        "DICOM Status" to String.format("0x%04X", if (e is DicomServiceException) e.status else Status.ProcessingFailure),
                        "SOP Class UID" to formatUidWithName(cuid),
                        "SOP Instance UID" to iuid,
                        "Duration" to String.format(Locale.US, "%.4f s", elapsedSec),
                        "Exception" to "${e.javaClass.name}: ${e.message}"
                    ),
                    isError = true,
                    sessionUid = sessionUid
                )
                if (e is DicomServiceException) throw e
                throw DicomServiceException(Status.ProcessingFailure, e)
            }
        }
    }

    private fun writeResponse(assoc: Association, pc: PresentationContext, rsp: Attributes,
                              category: DicomLogCategory) {
        val session = associationIdentity(assoc)
        val status = String.format("0x%04X", rsp.getInt(Tag.Status, Status.ProcessingFailure))
        logger.recordTechnicalEvent(category = DicomLogCategory.SERVER_LIFECYCLE, direction = DicomTrafficDirection.LOCAL,
            associationId = session.displayLabel, title = "${category.name} local write attempted",
            summaryLines = emptyList(), technicalDetails = mapOf("DICOM Status" to status, "Response Write" to "ATTEMPTED"),
            sessionUid = session.sessionUid)
        try {
            assoc.writeDimseRSP(pc, rsp) // dcm4che encoder writes and flushes before returning.
            logger.recordTechnicalEvent(category = category, direction = DicomTrafficDirection.OUTBOUND,
                associationId = session.displayLabel, title = "${category.name} local write completed; peer receipt unconfirmed",
                summaryLines = listOf("Status: $status"), technicalDetails = mapOf("DICOM Status" to status,
                    "Response Write" to "LOCAL_COMPLETED", "Message ID Being Responded To" to rsp.getInt(Tag.MessageIDBeingRespondedTo, 0).toString()),
                isError = rsp.getInt(Tag.Status, 0) != Status.Success, sessionUid = session.sessionUid)
        } catch (e: IOException) {
            logger.recordTechnicalEvent(category = DicomLogCategory.NETWORK_ERROR, direction = DicomTrafficDirection.LOCAL,
                associationId = session.displayLabel, title = "${category.name} local write failed; peer result unknown",
                summaryLines = listOf(e.toString()), technicalDetails = mapOf("DICOM Status" to status,
                    "Response Write" to "FAILED", "Exception" to e.toString()), isError = true, sessionUid = session.sessionUid)
            throw e
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(96).ifBlank { "UNNAMED" }

    private fun associationIdentity(assoc: Association): DicomSessionManager.SessionIdentity = synchronized(assoc) {
        (assoc.getProperty("stage55.identity") as? DicomSessionManager.SessionIdentity) ?: identity(assoc.socket).also {
            assoc.setProperty("stage55.identity", it)
        }
    }

    private fun formatAssocId(asAssoc: Association): String =
        associationIdentity(asAssoc).displayLabel

    private fun formatSessionUid(asAssoc: Association): String =
        associationIdentity(asAssoc).sessionUid

    private fun formatUidWithName(uid: String): String {
        val name = runCatching { UID.nameOf(uid) }.getOrNull()
        return if (!name.isNullOrBlank() && name != "?" && name != uid) {
            "$uid ($name)"
        } else {
            uid
        }
    }

    private fun formatPcResult(result: Int): String = when (result) {
        PresentationContext.ACCEPTANCE -> "0 - Acceptance"
        PresentationContext.USER_REJECTION -> "1 - User Rejection"
        PresentationContext.PROVIDER_REJECTION -> "2 - No Reason (Provider Rejection)"
        PresentationContext.ABSTRACT_SYNTAX_NOT_SUPPORTED -> "3 - Abstract Syntax Not Supported"
        PresentationContext.TRANSFER_SYNTAX_NOT_SUPPORTED -> "4 - Transfer Syntaxes Not Supported"
        else -> "$result - Unknown"
    }

    private fun explainAssociateRjReason(rj: AAssociateRJ): String {
        if (rj.source == AAssociateRJ.SOURCE_SERVICE_USER) {
            return when (rj.reason) {
                AAssociateRJ.REASON_NO_REASON_GIVEN -> "No reason given"
                AAssociateRJ.REASON_APP_CTX_NAME_NOT_SUPPORTED -> "Application Context Name not supported"
                AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED -> "Calling AE Title not recognized"
                AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED -> "Called AE Title not recognized (mismatch with SCP AE Title)"
                else -> "User rejection (${rj.reason})"
            }
        }
        return rj.message ?: "Rejected (${rj.reason})"
    }

    private fun formatByteCount(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun startUptimeTicker() {
        uptimeTimer?.cancel()
        val timer = Timer("ScpUptimeTicker", true)
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                synchronized(lock) {
                    finishStopIfTerminated()
                    if (_state.value.status == ScpServerStatus.RUNNING && device?.listConnections()?.none { it.isListening } == true) {
                        reportServiceError("Listening socket SCP закрыт; сервер останавливается")
                        stopServerInternal(logStop = true)
                    }
                }
                updateConnections()
                if (_state.value.status == ScpServerStatus.RUNNING) {
                    val elapsedSec = ((System.currentTimeMillis() - serverStartTimeMs) / 1000L).coerceAtLeast(0L)
                    val hours = elapsedSec / 3600
                    val minutes = (elapsedSec % 3600) / 60
                    val seconds = elapsedSec % 60
                    val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                    _state.update { it.copy(uptimeFormatted = formatted) }
                }
            }
        }, 100L, 100L)
        uptimeTimer = timer
    }
}
