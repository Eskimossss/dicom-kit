package ru.medeng.dicomtool.mwl.scp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.AssociationHandler
import org.dcm4che3.net.AssociationMonitor
import org.dcm4che3.net.Commands
import org.dcm4che3.net.Connection
import org.dcm4che3.net.ConnectionMonitor
import org.dcm4che3.net.Device
import org.dcm4che3.net.Dimse
import org.dcm4che3.net.Status
import org.dcm4che3.net.TransferCapability
import org.dcm4che3.net.pdu.AAbort
import org.dcm4che3.net.pdu.AAssociateAC
import org.dcm4che3.net.pdu.AAssociateRJ
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import org.dcm4che3.net.service.BasicCEchoSCP
import org.dcm4che3.net.service.BasicCFindSCP
import org.dcm4che3.net.service.DicomServiceException
import org.dcm4che3.net.service.DicomServiceRegistry
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.domain.model.NetworkInterfaceInfo
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.repo.MwlRepository
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class MwlScpState(
    val status: ScpServerStatus = ScpServerStatus.STOPPED,
    val aeTitle: String = "MWL_SCP",
    val tcpPort: Int = 11112,
    val networkInfo: NetworkInterfaceInfo = NetworkInterfaceInfo("wlan0 (Wi-Fi)", "127.0.0.1"),
    val activeConnectionsCount: Int = 0,
    val totalRequestsCount: Int = 0,
    val connectedNodes: List<String> = emptyList(),
    val whitelistMode: Boolean = false,
    val allowedCallingAets: List<String> = emptyList(),
    val allowedIps: List<String> = emptyList(),
    val characterSet: String = "ISO_IR 192", // UTF-8 по умолчанию
    val uptimeFormatted: String = "00:00:00",
    val validationError: String? = null,
    val errorMessage: String? = null
)

class MwlScpServer(
    private val repository: MwlRepository,
    private val logger: MwlTechnicalLogger
) {
    companion object {
        val SUPPORTED_TRANSFER_SYNTAXES = arrayOf(
            UID.ImplicitVRLittleEndian,
            UID.ExplicitVRLittleEndian
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
    private val connectionGate = Any()

    private val sockets = ConcurrentHashMap<Socket, DicomSessionManager.SessionIdentity>()
    private val associations = ConcurrentHashMap<Socket, Association>()
    private val recentNodes = ConcurrentHashMap<String, Long>()
    private val totalRequests = AtomicInteger(0)

    private val _state = MutableStateFlow(
        MwlScpState(
            networkInfo = detectLocalIpv4Interface()
        )
    )
    val state: StateFlow<MwlScpState> = _state.asStateFlow()

    fun detectLocalIpv4Interface(): NetworkInterfaceInfo {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
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
                    return NetworkInterfaceInfo(label, ipv4.hostAddress ?: "127.0.0.1")
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

    fun updateConfiguration(
        aeTitle: String = _state.value.aeTitle,
        port: Int = _state.value.tcpPort,
        whitelistMode: Boolean = _state.value.whitelistMode,
        allowedCallingAets: List<String> = _state.value.allowedCallingAets,
        allowedIps: List<String> = _state.value.allowedIps,
        characterSet: String = _state.value.characterSet
    ) {
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
                aeTitle = aeTitle,
                tcpPort = port,
                whitelistMode = whitelistMode,
                allowedCallingAets = allowedCallingAets,
                allowedIps = allowedIps,
                characterSet = characterSet,
                validationError = validationError,
                errorMessage = null
            )
        }
    }

    fun startServer(
        aeTitle: String = _state.value.aeTitle.trim(),
        port: Int = _state.value.tcpPort
    ): Result<Unit> = synchronized(lock) {
        if (_state.value.status == ScpServerStatus.STOPPING) {
            return Result.failure(IllegalStateException("Сервер MWL SCP ещё освобождает ресурсы"))
        }
        if (_state.value.status == ScpServerStatus.RUNNING) {
            return Result.success(Unit)
        }

        val aeCheck = DicomValidationUtils.validateAeTitle(aeTitle)
        if (aeCheck is DicomValidationUtils.ValidationResult.Invalid) {
            _state.update { it.copy(validationError = aeCheck.messageRu, errorMessage = aeCheck.messageRu) }
            return Result.failure(IllegalArgumentException(aeCheck.messageRu))
        }

        val portCheck = DicomValidationUtils.validateScpPort(port)
        if (portCheck is DicomValidationUtils.ValidationResult.Invalid) {
            _state.update { it.copy(validationError = portCheck.messageRu, errorMessage = portCheck.messageRu) }
            return Result.failure(IllegalArgumentException(portCheck.messageRu))
        }

        stopRequested = false
        _state.update {
            it.copy(
                status = ScpServerStatus.STARTING,
                aeTitle = aeTitle,
                tcpPort = port,
                networkInfo = detectLocalIpv4Interface(),
                validationError = null,
                errorMessage = null
            )
        }

        return try {
            val exec = Executors.newCachedThreadPool().also { executor = it }
            val sched = Executors.newSingleThreadScheduledExecutor().also { scheduledExecutor = it }

            val scpDevice = Device("dicomkit-mwl-scp")
            val conn = Connection("mwl-conn", "0.0.0.0", port).apply {
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

                // C-ECHO Verification SOP Class
                addTransferCapability(
                    TransferCapability(
                        "Verification-SCP",
                        UID.Verification,
                        TransferCapability.Role.SCP,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )

                // Modality Worklist Information Model - FIND SOP Class (1.2.840.10008.5.1.4.31)
                addTransferCapability(
                    TransferCapability(
                        "MWL-Find-SCP",
                        UID.ModalityWorklistInformationModelFind,
                        TransferCapability.Role.SCP,
                        *SUPPORTED_TRANSFER_SYNTAXES
                    )
                )
            }

            val registry = DicomServiceRegistry().apply {
                addDicomService(createLoggingCEchoScp())
                addDicomService(createLoggingCFindScp())
            }

            scpDevice.addConnection(conn)
            scpDevice.addApplicationEntity(ae)
            scpDevice.dimseRQHandler = registry
            scpDevice.connectionMonitor = createConnectionMonitor()
            scpDevice.associationMonitor = createAssociationMonitor()
            scpDevice.associationHandler = createAssociationHandler()
            scpDevice.executor = exec
            scpDevice.scheduledExecutor = sched

            device = scpDevice
            executor = exec
            scheduledExecutor = sched

            synchronized(connectionGate) {
                if (stopRequested) throw IOException("Запуск MWL SCP прерван")
                recentNodes.clear()
                accepting = true
                scpDevice.bindConnections()
            }

            serverStartTimeMs = System.currentTimeMillis()
            startUptimeTicker()

            val netInfo = detectLocalIpv4Interface()
            _state.update {
                it.copy(
                    status = ScpServerStatus.RUNNING,
                    aeTitle = aeTitle,
                    tcpPort = port,
                    networkInfo = netInfo,
                    uptimeFormatted = "00:00:00",
                    errorMessage = null
                )
            }

            logger.recordEvent(
                category = DicomLogCategory.SERVER_LIFECYCLE,
                direction = DicomTrafficDirection.LOCAL,
                associationId = "MWL SCP",
                title = "Сервер Modality Worklist (MWL SCP) запущен на ${netInfo.ipv4Address}:$port (AE: $aeTitle)",
                summaryLines = listOf(
                    "Called AE Title: $aeTitle",
                    "TCP Port: $port (Bind: 0.0.0.0:$port)",
                    "Сетевой интерфейс: ${netInfo.interfaceName} (${netInfo.ipv4Address})",
                    "SOP Class: 1.2.840.10008.5.1.4.31 (Modality Worklist FIND)",
                    "Кодировка ответов: ${_state.value.characterSet}"
                ),
                technicalDetails = mapOf(
                    "AE Title" to aeTitle,
                    "Port" to port.toString(),
                    "Bind Address" to "0.0.0.0:$port",
                    "IPv4" to netInfo.ipv4Address,
                    "SOP Class UID" to UID.ModalityWorklistInformationModelFind,
                    "Whitelist Mode" to _state.value.whitelistMode.toString(),
                    "Allowed Calling AETs" to _state.value.allowedCallingAets.joinToString(", ").ifEmpty { "Все" },
                    "Response Character Set" to _state.value.characterSet
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            stopServerInternal(logStop = false)
            val msg = "Не удалось запустить MWL SCP на порту $port: ${e.message ?: e.javaClass.simpleName}"
            _state.update {
                it.copy(
                    status = ScpServerStatus.ERROR,
                    errorMessage = msg
                )
            }
            logger.recordEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.LOCAL,
                associationId = "MWL SCP",
                title = "Ошибка запуска MWL SCP на порту $port",
                summaryLines = listOf(msg),
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

    fun stopServer() = synchronized(lock) {
        stopServerInternal(logStop = true)
    }

    private fun stopServerInternal(logStop: Boolean) {
        if (device == null && executor == null) return
        _state.update { it.copy(status = ScpServerStatus.STOPPING) }
        synchronized(connectionGate) {
            accepting = false
            stopRequested = true
        }

        try { device?.unbindConnections() } catch (_: Exception) {}

        device?.listOpenAssociations()?.forEach { assoc ->
            executor?.execute { runCatching { if (assoc.isReadyForDataTransfer) assoc.release() } }
        }

        executor?.shutdown()
        try { executor?.awaitTermination(800, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        synchronized(connectionGate) {
            sockets.keys.forEach { runCatching { it.close() } }
        }
        executor?.shutdownNow()
        scheduledExecutor?.shutdownNow()

        sockets.clear()
        associations.clear()
        device = null
        executor = null
        scheduledExecutor = null
        uptimeTimer?.cancel()
        uptimeTimer = null

        _state.update {
            it.copy(
                status = ScpServerStatus.STOPPED,
                activeConnectionsCount = 0,
                connectedNodes = emptyList(),
                uptimeFormatted = "00:00:00"
            )
        }

        if (logStop) {
            logger.recordEvent(
                category = DicomLogCategory.SERVER_LIFECYCLE,
                direction = DicomTrafficDirection.LOCAL,
                associationId = "MWL SCP",
                title = "Сервер Modality Worklist (MWL SCP) остановлен",
                summaryLines = listOf("Сокет закрыт, ресурсы освобождены")
            )
        }
    }

    private fun identity(socket: Socket): DicomSessionManager.SessionIdentity =
        sockets.computeIfAbsent(socket) { DicomSessionManager.nextMwlScpSession() }

    private fun formatAssocId(asAssoc: Association): String {
        val s = asAssoc.socket ?: return "MWL SCP"
        return identity(s).displayLabel
    }

    private fun formatSessionUid(asAssoc: Association): String {
        val s = asAssoc.socket ?: return "MWL SCP"
        return identity(s).sessionUid
    }

    private fun rememberNode(association: Association) {
        val ae = association.callingAET ?: return
        val ip = association.socket?.inetAddress?.hostAddress ?: return
        recentNodes["$ae ($ip)"] = System.currentTimeMillis()
        if (recentNodes.size > 128) {
            recentNodes.entries.minByOrNull { it.value }?.let { recentNodes.remove(it.key, it.value) }
        }
    }

    private fun updateConnectionsState() {
        val nodes = recentNodes.entries.sortedByDescending { it.value }.map {
            "${it.key} · последнее обращение ${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(it.value))}"
        }
        _state.update {
            it.copy(
                activeConnectionsCount = sockets.size,
                connectedNodes = if (it.status == ScpServerStatus.STOPPED) emptyList() else nodes,
                totalRequestsCount = totalRequests.get()
            )
        }
    }

    private fun startUptimeTicker() {
        uptimeTimer?.cancel()
        uptimeTimer = Timer("MwlScpUptime", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    val uptimeSec = (System.currentTimeMillis() - serverStartTimeMs) / 1000
                    val hh = uptimeSec / 3600
                    val mm = (uptimeSec % 3600) / 60
                    val ss = uptimeSec % 60
                    val formatted = "%02d:%02d:%02d".format(hh, mm, ss)
                    _state.update { it.copy(uptimeFormatted = formatted) }
                }
            }, 1000L, 1000L)
        }
    }

    private fun createConnectionMonitor(): ConnectionMonitor = object : ConnectionMonitor {
        override fun onConnectionEstablished(conn: Connection, remoteConn: Connection, s: Socket) {}
        override fun onConnectionFailed(conn: Connection, remoteConn: Connection, s: Socket, e: Throwable) {
            logger.recordEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = "TCP",
                title = "Сбой входящего TCP-соединения от ${s.inetAddress?.hostAddress}:${s.port}",
                summaryLines = listOf("Ошибка: ${e.message ?: e.javaClass.simpleName}"),
                isError = true
            )
        }
        override fun onConnectionRejectedBlacklisted(conn: Connection, s: Socket) {}
        override fun onConnectionRejected(conn: Connection, s: Socket, e: Throwable) {}

        override fun onConnectionAccepted(conn: Connection, s: Socket) {
            synchronized(connectionGate) {
                if (!accepting) { s.close(); return }
                identity(s)
            }
            updateConnectionsState()
            val remoteIp = s.inetAddress?.hostAddress ?: "unknown"
            val remotePort = s.port
            val session = identity(s)
            logger.recordEvent(
                category = DicomLogCategory.TCP_CONNECT,
                direction = DicomTrafficDirection.INBOUND,
                associationId = session.displayLabel,
                sessionUid = session.sessionUid,
                title = "TCP подключение от $remoteIp:$remotePort",
                summaryLines = listOf("Локальный порт прослушивания: ${conn.port}"),
                technicalDetails = mapOf(
                    "Remote IP" to remoteIp,
                    "Remote Port" to remotePort.toString(),
                    "Local Port" to conn.port.toString()
                )
            )
        }
    }

    private fun createAssociationMonitor(): AssociationMonitor = object : AssociationMonitor {
        override fun onAssociationEstablished(asAssoc: Association) {
            asAssoc.socket?.let { associations[it] = asAssoc }
        }

        override fun onAssociationAccepted(asAssoc: Association) {
            asAssoc.socket?.let { associations[it] = asAssoc }
            rememberNode(asAssoc)
            updateConnectionsState()
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val rq = asAssoc.aAssociateRQ
            val remoteIp = asAssoc.socket?.inetAddress?.hostAddress ?: "unknown"
            val remotePort = asAssoc.socket?.port ?: 0

            val acceptedPcs = asAssoc.aAssociateAC.presentationContexts.filter { it.isAccepted }
            val pcSummaries = acceptedPcs.map { pc ->
                val sopName = runCatching { UID.nameOf(pc.abstractSyntax) }.getOrDefault(pc.abstractSyntax)
                val tsName = runCatching { UID.nameOf(pc.transferSyntax) }.getOrDefault(pc.transferSyntax)
                "PC #${pc.pcid}: $sopName ($tsName)"
            }

            logger.recordEvent(
                category = DicomLogCategory.ASSOCIATE_AC,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Ассоциация DICOM успешно согласована (A-ASSOCIATE-AC)",
                summaryLines = listOf(
                    "Calling AE: ${asAssoc.callingAET}  ->  Called AE: ${asAssoc.calledAET}",
                    "Удалённый узел: $remoteIp:$remotePort"
                ) + pcSummaries,
                technicalDetails = mapOf(
                    "Calling AE Title" to asAssoc.callingAET,
                    "Called AE Title" to asAssoc.calledAET,
                    "Remote Socket" to "$remoteIp:$remotePort",
                    "Accepted Presentation Contexts" to acceptedPcs.size.toString()
                )
            )
        }

        override fun onAssociationRejected(asAssoc: Association, rj: AAssociateRJ) {
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val remoteIp = asAssoc.socket?.inetAddress?.hostAddress ?: "unknown"
            val remotePort = asAssoc.socket?.port ?: 0

            logger.recordEvent(
                category = DicomLogCategory.ASSOCIATE_RJ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Ассоциация DICOM отклонена (A-ASSOCIATE-RJ)",
                summaryLines = listOf(
                    "Calling AE: ${asAssoc.callingAET} | Called AE: ${asAssoc.calledAET}",
                    "Причина: Результат=${rj.result}, Источник=${rj.source}, Код=${rj.reason}"
                ),
                technicalDetails = mapOf(
                    "Calling AE Title" to (asAssoc.callingAET ?: "-"),
                    "Called AE Title" to (asAssoc.calledAET ?: "-"),
                    "Remote Socket" to "$remoteIp:$remotePort",
                    "RJ Result" to rj.result.toString(),
                    "RJ Source" to rj.source.toString(),
                    "RJ Reason" to rj.reason.toString()
                ),
                isError = true
            )
        }

        override fun onAssociationFailed(asAssoc: Association, e: Throwable) {
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            logger.recordEvent(
                category = DicomLogCategory.NETWORK_ERROR,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Сбой ассоциации DICOM: ${e.message ?: e.javaClass.simpleName}",
                summaryLines = listOf("Ошибка ассоциации: ${e.message}"),
                isError = true
            )
        }
    }

    private fun createAssociationHandler(): AssociationHandler = object : AssociationHandler() {
        override fun onClose(asAssoc: Association) {
            super.onClose(asAssoc)
            val s = asAssoc.socket
            if (s != null) {
                associations.remove(s)
                val session = sockets.remove(s)
                if (session != null) {
                    val ex = asAssoc.exception
                    logger.recordEvent(
                        category = if (ex is AAbort) DicomLogCategory.ABORT else DicomLogCategory.RELEASE_RP,
                        direction = DicomTrafficDirection.LOCAL,
                        associationId = session.displayLabel,
                        sessionUid = session.sessionUid,
                        title = if (ex is AAbort) "Ассоциация аварийно прервана (A-ABORT)" else "Ассоциация DICOM завершена (A-RELEASE)",
                        summaryLines = listOf(ex?.message ?: "Нормальное закрытие сеанса"),
                        technicalDetails = mapOf(
                            "Exception" to (ex?.toString() ?: "None")
                        ),
                        isError = ex != null
                    )
                }
            }
            updateConnectionsState()
        }
        override fun negotiate(asAssoc: Association, rq: AAssociateRQ): AAssociateAC {
            val assocId = formatAssocId(asAssoc)
            val sessionUid = formatSessionUid(asAssoc)
            val remoteIp = asAssoc.socket?.inetAddress?.hostAddress ?: "unknown"
            val callingAet = rq.callingAET?.trim() ?: ""

            // 1. Проверка Whitelist Mode (ограничение по Calling AE Title и IP)
            if (_state.value.whitelistMode) {
                val allowedAets = _state.value.allowedCallingAets.map { it.trim() }
                val allowedIps = _state.value.allowedIps.map { it.trim() }

                if (allowedAets.isNotEmpty() && !allowedAets.contains(callingAet)) {
                    val rj = AAssociateRJ(
                        AAssociateRJ.RESULT_REJECTED_PERMANENT,
                        AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED
                    )
                    logger.recordEvent(
                        category = DicomLogCategory.ASSOCIATE_RJ,
                        direction = DicomTrafficDirection.OUTBOUND,
                        associationId = assocId,
                        sessionUid = sessionUid,
                        title = "Отказ в ассоциации: Calling AE Title '$callingAet' отсутствует в белом списке",
                        summaryLines = listOf(
                            "Calling AE: $callingAet (НЕ РАЗРЕШЁН)",
                            "Удалённый IP: $remoteIp"
                        ),
                        isError = true
                    )
                    throw rj
                }

                if (allowedIps.isNotEmpty() && !allowedIps.contains(remoteIp)) {
                    logger.recordEvent(
                        category = DicomLogCategory.ASSOCIATE_RJ,
                        direction = DicomTrafficDirection.OUTBOUND,
                        associationId = assocId,
                        sessionUid = sessionUid,
                        title = "Отказ в ассоциации: IP '$remoteIp' отсутствует в белом списке",
                        summaryLines = listOf("Удалённый IP: $remoteIp (НЕ РАЗРЕШЁН)"),
                        isError = true
                    )
                    throw AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER, 1)
                }
            }

            // 2. Логирование полученного A-ASSOCIATE-RQ
            logger.recordEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "Получен запрос ассоциации (A-ASSOCIATE-RQ)",
                summaryLines = listOf(
                    "Calling AE: ${rq.callingAET}  ->  Called AE: ${rq.calledAET}",
                    "Удалённый узел: $remoteIp"
                ),
                technicalDetails = mapOf(
                    "Calling AE Title" to (rq.callingAET ?: ""),
                    "Called AE Title" to (rq.calledAET ?: ""),
                    "Remote IP" to remoteIp,
                    "Presentation Contexts Count" to rq.numberOfPresentationContexts.toString()
                )
            )

            // 3. Вызываем стандартное согласование контекстов
            return super.negotiate(asAssoc, rq)
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
            logger.recordEvent(
                category = DicomLogCategory.C_ECHO_RQ,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "C-ECHO-RQ получен (Message ID: $msgId)",
                summaryLines = listOf("Проверка связи Verification SOP Class от ${asAssoc.callingAET}")
            )
            asAssoc.writeDimseRSP(pc, Commands.mkEchoRSP(cmd, Status.Success))
            logger.recordEvent(
                category = DicomLogCategory.C_ECHO_RSP,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                sessionUid = sessionUid,
                title = "C-ECHO-RSP отправлен (Status: 0000H Success)",
                summaryLines = listOf("Ответ на Message ID: $msgId")
            )
        }
    }

    private fun createLoggingCFindScp(): BasicCFindSCP = object : BasicCFindSCP(UID.ModalityWorklistInformationModelFind) {
        override fun onDimseRQ(asAssoc: Association, pc: PresentationContext, dimse: Dimse, cmd: Attributes, data: Attributes) {
            if (dimse != Dimse.C_FIND_RQ) throw DicomServiceException(Status.UnrecognizedOperation)
            val msgId = cmd.getInt(Tag.MessageID, -1)
            val sessionUid = formatSessionUid(asAssoc)
            val assocId = formatAssocId(asAssoc)
            val cancelled = AtomicBoolean(false)
            val responseGate = Any()
            // Registration happens before dispatch, so an immediate C-CANCEL cannot be lost.
            asAssoc.addCancelRQHandler(msgId) {
                synchronized(responseGate) { cancelled.set(true) }
                logger.recordEvent(category = DicomLogCategory.RELEASE_RQ, associationId = assocId, sessionUid = sessionUid,
                    title = "C-CANCEL-FIND received", technicalDetails = mapOf("Message ID" to msgId.toString()))
            }
            try {
                asAssoc.applicationEntity.device.execute {
                    var sent = 0
                    try {
                        totalRequests.incrementAndGet()
                        updateConnectionsState()
                        val query = try { MwlMatchingEngine.extractMatchingQuery(data) }
                            catch (e: IllegalArgumentException) { throw DicomServiceException(Status.IdentifierDoesNotMatchSOPClass, "Invalid MWL Identifier") }
                        logger.recordEvent(
                            category = DicomLogCategory.C_ECHO_RQ,
                            direction = DicomTrafficDirection.INBOUND,
                            associationId = assocId, sessionUid = sessionUid,
                            title = "C-FIND-RQ Modality Worklist",
                            summaryLines = listOfNotNull(query.patientName?.let { "Patient Name: $it" }, query.patientId?.let { "Patient ID: $it" }, query.accessionNumber?.let { "Accession: $it" }),
                            technicalDetails = mapOf("Message ID" to msgId.toString())
                        )
                        val snapshot = repository.snapshot()
                        val patients = snapshot.patients.associateBy { it.id }
                        for (study in snapshot.studies) {
                            if (cancelled.get() || !asAssoc.isReadyForDataTransfer || Thread.currentThread().isInterrupted) break
                            val patient = patients[study.patientInternalId]
                            if (!MwlMatchingEngine.matches(patient, study, query)) continue
                            val ds = MwlDatasetBuilder.buildMwlResponseDataset(patient, study, data, state.value.characterSet)
                            synchronized(responseGate) {
                                if (!cancelled.get() && asAssoc.isReadyForDataTransfer) {
                                    val pending = if (MwlDatasetBuilder.hasUnsupportedReturnKeys(data)) Status.PendingWarning else Status.Pending
                                    asAssoc.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, pending), ds)
                                    sent++
                                }
                            }
                        }
                        synchronized(responseGate) {
                            if (asAssoc.isReadyForDataTransfer && !Thread.currentThread().isInterrupted) {
                                val status = if (cancelled.get()) Status.Cancel else Status.Success
                                asAssoc.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, status))
                                logger.recordEvent(category = DicomLogCategory.C_ECHO_RSP, direction = DicomTrafficDirection.OUTBOUND,
                                    associationId = assocId, sessionUid = sessionUid, title = "C-FIND-RSP final %04X".format(status),
                                    technicalDetails = mapOf("Message ID" to msgId.toString(), "Status" to "%04X".format(status), "Matches Sent" to sent.toString()))
                            }
                        }
                    } catch (e: Exception) {
                        val status = if (e is DicomServiceException) e.status else Status.UnableToProcess
                        if (asAssoc.isReadyForDataTransfer) runCatching { asAssoc.writeDimseRSP(pc, Commands.mkCFindRSP(cmd, status)) }
                        logger.recordEvent(category = DicomLogCategory.NETWORK_ERROR, associationId = assocId, sessionUid = sessionUid,
                            title = "C-FIND failed", technicalDetails = mapOf("Message ID" to msgId.toString(), "Status" to "%04X".format(status)), isError = true)
                    } finally { asAssoc.removeCancelRQHandler(msgId) }
                }
            } catch (e: Exception) {
                asAssoc.removeCancelRQHandler(msgId)
                throw DicomServiceException(Status.UnableToProcess)
            }
        }
    }
}
