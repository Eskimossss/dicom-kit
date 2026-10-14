package ru.medeng.dicomtool.dicom.scu

import android.content.Context
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Association
import org.dcm4che3.net.Connection
import org.dcm4che3.net.DataWriter
import org.dcm4che3.net.DataWriterAdapter
import org.dcm4che3.net.Device
import org.dcm4che3.net.DimseRSPHandler
import org.dcm4che3.net.NoPresentationContextException
import org.dcm4che3.net.PDVOutputStream
import org.dcm4che3.net.Priority
import org.dcm4che3.net.Status
import org.dcm4che3.net.pdu.AAbort
import org.dcm4che3.net.pdu.AAssociateAC
import org.dcm4che3.net.pdu.AAssociateRJ
import org.dcm4che3.net.pdu.AAssociateRQ
import org.dcm4che3.net.pdu.PresentationContext
import ru.medeng.dicomtool.data.logging.DicomSessionManager
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.dicom.DicomValidationUtils
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.domain.model.DicomLogCategory
import ru.medeng.dicomtool.domain.model.DicomTrafficDirection
import ru.medeng.dicomtool.domain.model.FileSendStatus
import ru.medeng.dicomtool.domain.model.ScuCandidateFile
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Категория сетевой или протокольной ошибки DICOM SCU (Этап 3).
 * Позволяет строго различать типы сбоев без подмены на универсальное «Соединение не удалось».
 */
enum class ScuErrorCategory(val codeLabel: String, val titleRu: String) {
    INVALID_PARAMETERS("INVALID_PARAMETERS", "Некорректные параметры подключения"),
    TCP_CONNECTION_REFUSED("TCP_CONNECTION_REFUSED", "TCP Connection Refused (соединение отклонено)"),
    TCP_TIMEOUT("TCP_TIMEOUT", "TCP Timeout (превышено время ожидания)"),
    DNS_RESOLUTION_ERROR("DNS_RESOLUTION_ERROR", "DNS Resolution Error (неизвестный хост)"),
    ASSOCIATE_RJ("A_ASSOCIATE_RJ", "A-ASSOCIATE-RJ (отказ в ассоциации)"),
    PRESENTATION_CONTEXT_REJECTED("PRESENTATION_CONTEXT_REJECTED", "Presentation Context Rejected (контекст отклонён)"),
    DIMSE_ERROR_STATUS("DIMSE_ERROR_STATUS", "DIMSE Error Status (ошибка выполнения команды)"),
    ASSOCIATION_ABORTED("A_ABORT", "A-ABORT (ассоциация аварийно прервана)"),
    CONNECTION_RESET("CONNECTION_RESET", "Connection Reset (разрыв соединения узлом)"),
    IO_PROTOCOL_ERROR("IO_PROTOCOL_ERROR", "Ошибка ввода-вывода DICOM-протокола")
}

/**
 * Классификация кода статуса DIMSE в ответе C-ECHO-RSP / C-STORE-RSP (DICOM PS3.7 Annex C).
 */
enum class ScuDimseStatusKind {
    SUCCESS,
    WARNING,
    FAILURE
}

/**
 * Детальный результат разбора статуса DIMSE.
 */
data class DimseStatusEvaluation(
    val statusInt: Int,
    val statusHex: String,
    val kind: ScuDimseStatusKind,
    val descriptionRu: String
)

/**
 * Детальная информация о классифицированной ошибке SCU.
 */
data class ClassifiedScuError(
    val category: ScuErrorCategory,
    val shortUserMessageRu: String,
    val technicalReasonRu: String,
    val logCategory: DicomLogCategory,
    val logDirection: DicomTrafficDirection,
    val technicalDetails: Map<String, String>
)

/**
 * Итоговый результат выполнения операции SCU (C-ECHO или C-STORE).
 */
sealed class ScuOperationResult {
    data class Success(
        val associationId: String,
        val operationName: String,
        val statusHex: String,
        val durationMs: Long,
        val transmittedBytes: Long = 0L,
        val shortTitleRu: String,
        val userMessageRu: String
    ) : ScuOperationResult()

    data class Warning(
        val associationId: String,
        val operationName: String,
        val statusHex: String,
        val durationMs: Long,
        val transmittedBytes: Long = 0L,
        val shortTitleRu: String,
        val userMessageRu: String
    ) : ScuOperationResult()

    data class Failure(
        val associationId: String,
        val operationName: String,
        val errorCategory: ScuErrorCategory,
        val statusHex: String? = null,
        val durationMs: Long,
        val shortTitleRu: String,
        val userMessageRu: String,
        val technicalReasonRu: String
    ) : ScuOperationResult()
}

/**
 * Итоговый результат пакетной отправки реальных DICOM-файлов (Этап 4.2).
 */
data class ScuBatchOperationResult(
    val associationId: String,
    val totalFiles: Int,
    val successCount: Int,
    val warningCount: Int,
    val failedCount: Int,
    val durationMs: Long,
    val summaryMessageRu: String,
    val isCancelled: Boolean,
    val skippedCount: Int = 0,
    val cancelledCount: Int = 0,
    val unconfirmedCount: Int = 0,
    val fileResults: List<ScuFileResult> = emptyList(),
    val closureWarnings: List<String> = emptyList()
)


/**
 * Полноценный клиент DICOM SCU (C-ECHO SCU + C-STORE SCU) на базе dcm4che 5.33.1 (PS3.7 / PS3.8).
 *
 * - Выполняет реальное TCP-подключение, Association Negotiation, согласование Presentation Context,
 *   отправку C-ECHO-RQ / C-STORE-RQ, приём и проверку C-ECHO-RSP / C-STORE-RSP и корректный A-RELEASE.
 * - Различает все типы сетевых и протокольных ошибок (Connection Refused, Timeout, DNS, A-ASSOCIATE-RJ,
 *   Presentation Context Rejected, DIMSE Warning/Failure Status, A-ABORT, Connection Reset).
 * - Записывает каждое реальное событие в технический журнал с направлением (OUT / IN), ролью SCU и ID ассоциации,
 *   не записывая Pixel Data и персональные данные пациента и не выдумывая ответы PACS, которые не были получены.
 */
class DicomScuClient(
    private val logger: PersistentDicomTechnicalLogger,
    private val tempCacheDir: File? = null
) {

    companion object {
        private val assocCounter = AtomicInteger(0)

        const val DEFAULT_CONNECT_TIMEOUT_MS = 5000
        const val DEFAULT_ACCEPT_TIMEOUT_MS = 5000
        const val DEFAULT_REQUEST_TIMEOUT_MS = 5000
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 10000
        const val DEFAULT_RELEASE_TIMEOUT_MS = 3000

        /**
         * Оценивает код статуса DIMSE согласно стандарту DICOM PS3.7 Annex C / PS3.4 Annex B.
         */
        fun evaluateDimseStatus(status: Int, errorComment: String? = null): DimseStatusEvaluation {
            val unsigned = status and 0xFFFF
            val hex = String.format(Locale.US, "0x%04X", unsigned)
            val commentSuffix = if (!errorComment.isNullOrBlank()) " ($errorComment)" else ""

            return when {
                unsigned == Status.Success -> {
                    DimseStatusEvaluation(
                        statusInt = unsigned,
                        statusHex = hex,
                        kind = ScuDimseStatusKind.SUCCESS,
                        descriptionRu = "Success ($hex) — операция успешно подтверждена принимающей системой$commentSuffix"
                    )
                }

                // B000..BFFF — Warning statuses (например, B000 Coercion of Data Elements, B006 Elements Discarded, B007 Data Set does not match SOP Class)
                unsigned in 0xB000..0xBFFF -> {
                    val warningDesc = when (unsigned) {
                        0xB000 -> "Предупреждение ($hex): Coercion of Data Elements (принудительное изменение элементов)"
                        0xB006 -> "Предупреждение ($hex): Elements Discarded (часть необязательных элементов отброшена)"
                        0xB007 -> "Предупреждение ($hex): Data Set does not match SOP Class"
                        else -> "Предупреждение DIMSE ($hex): объект принят с предупреждением"
                    }
                    DimseStatusEvaluation(
                        statusInt = unsigned,
                        statusHex = hex,
                        kind = ScuDimseStatusKind.WARNING,
                        descriptionRu = "$warningDesc$commentSuffix"
                    )
                }

                else -> {
                    val failureDesc = when (unsigned) {
                        0x0110 -> "Ошибка обработки ($hex): Processing Failure"
                        0x0112 -> "Ошибка ($hex): No Such Object Instance"
                        0x0122 -> "Отказ ($hex): Refused — SOP Class Not Supported"
                        0x0124 -> "Отказ ($hex): Refused — Not Authorized"
                        0x0210 -> "Ошибка ($hex): Duplicate Invocation"
                        0x0211 -> "Ошибка ($hex): Unrecognized Operation"
                        0x0212 -> "Ошибка ($hex): Mistyped Argument"
                        in 0xA700..0xA7FF -> "Отказ ($hex): Refused — Out of Resources (недостаточно ресурсов на PACS)"
                        in 0xA900..0xA9FF -> "Ошибка ($hex): Error — Data Set does not match SOP Class (набор данных не соответствует SOP Class)"
                        in 0xC000..0xCFFF -> "Ошибка ($hex): Error — Cannot Understand (принимающая система не смогла разобрать набор данных)"
                        else -> "Ошибка статуса DIMSE ($hex)"
                    }
                    DimseStatusEvaluation(
                        statusInt = unsigned,
                        statusHex = hex,
                        kind = ScuDimseStatusKind.FAILURE,
                        descriptionRu = "$failureDesc$commentSuffix"
                    )
                }
            }
        }

        /**
         * Строгая классификация исключений сетевого и DICOM Upper Layer уровней.
         * Никогда не подменяет конкретную ошибку универсальным сообщением «Соединение не удалось».
         */
        fun classifyException(
            throwable: Throwable,
            remoteHost: String,
            remotePort: Int,
            callingAe: String,
            calledAe: String
        ): ClassifiedScuError {
            val chain = generateSequence(throwable) { it.cause }.toList()
            val endpoint = "$remoteHost:$remotePort"

            // 1. A-ASSOCIATE-RJ от удалённого PACS
            val rj = chain.filterIsInstance<AAssociateRJ>().firstOrNull()
            if (rj != null) {
                val resultDesc = when (rj.result) {
                    AAssociateRJ.RESULT_REJECTED_PERMANENT -> "1 (rejected-permanent)"
                    AAssociateRJ.RESULT_REJECTED_TRANSIENT -> "2 (rejected-transient)"
                    else -> rj.result.toString()
                }
                val sourceDesc = when (rj.source) {
                    AAssociateRJ.SOURCE_SERVICE_USER -> "1 (DICOM UL service-user)"
                    AAssociateRJ.SOURCE_SERVICE_PROVIDER_ACSE -> "2 (DICOM UL service-provider ACSE)"
                    AAssociateRJ.SOURCE_SERVICE_PROVIDER_PRES -> "3 (DICOM UL presentation)"
                    else -> rj.source.toString()
                }
                val reasonDesc = formatAssociateRjReason(rj.source, rj.reason)
                return ClassifiedScuError(
                    category = ScuErrorCategory.ASSOCIATE_RJ,
                    shortUserMessageRu = "A-ASSOCIATE-RJ: PACS ($endpoint) отклонил ассоциацию [$callingAe → $calledAe]. Причина: $reasonDesc.",
                    technicalReasonRu = "Получен PDU A-ASSOCIATE-RJ от $endpoint: result=$resultDesc, source=$sourceDesc, reason=$reasonDesc.",
                    logCategory = DicomLogCategory.ASSOCIATE_RJ,
                    logDirection = DicomTrafficDirection.INBOUND,
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Calling AE" to callingAe,
                        "Called AE" to calledAe,
                        "PDU" to "A-ASSOCIATE-RJ (03H)",
                        "Result" to resultDesc,
                        "Source" to sourceDesc,
                        "Reason" to reasonDesc
                    )
                )
            }

            // 2. Presentation Context Rejected / NoPresentationContextException
            val noPc = chain.filterIsInstance<NoPresentationContextException>().firstOrNull()
            if (noPc != null) {
                return ClassifiedScuError(
                    category = ScuErrorCategory.PRESENTATION_CONTEXT_REJECTED,
                    shortUserMessageRu = "Presentation Context Rejected: PACS ($endpoint) не принял предложенный SOP Class или Transfer Syntax (${noPc.message}).",
                    technicalReasonRu = "NoPresentationContextException: ${noPc.message}",
                    logCategory = DicomLogCategory.PRESENTATION_CONTEXT,
                    logDirection = DicomTrafficDirection.INBOUND,
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Calling AE" to callingAe,
                        "Called AE" to calledAe,
                        "Категория ошибки" to ScuErrorCategory.PRESENTATION_CONTEXT_REJECTED.codeLabel,
                        "Причина" to (noPc.message ?: "Контекст представления отклонён")
                    )
                )
            }

            // 3. A-ABORT (получен или отправлен при разрыве ассоциации)
            val abort = chain.filterIsInstance<AAbort>().firstOrNull()
            if (abort != null) {
                val sourceStr = when (abort.source) {
                    AAbort.UL_SERIVE_USER -> "0 (DICOM UL service-user)"
                    AAbort.UL_SERIVE_PROVIDER -> "2 (DICOM UL service-provider)"
                    else -> abort.source.toString()
                }
                val reasonStr = formatAbortReason(abort.reason)
                return ClassifiedScuError(
                    category = ScuErrorCategory.ASSOCIATION_ABORTED,
                    shortUserMessageRu = "A-ABORT: ассоциация с PACS ($endpoint) аварийно прервана (source=$sourceStr, reason=$reasonStr).",
                    technicalReasonRu = "Получен/вызван PDU A-ABORT: source=$sourceStr, reason=$reasonStr (${abort.message}).",
                    logCategory = DicomLogCategory.ABORT,
                    logDirection = DicomTrafficDirection.INBOUND,
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Calling AE" to callingAe,
                        "Called AE" to calledAe,
                        "PDU" to "A-ABORT (07H)",
                        "Source" to sourceStr,
                        "Reason" to reasonStr
                    )
                )
            }

            // 4. Ошибка DNS (UnknownHostException)
            val unknownHost = chain.filterIsInstance<UnknownHostException>().firstOrNull()
            if (unknownHost != null) {
                return ClassifiedScuError(
                    category = ScuErrorCategory.DNS_RESOLUTION_ERROR,
                    shortUserMessageRu = "DNS Resolution Error: не удалось разрешить имя хоста '$remoteHost'. Проверьте адрес PACS или настройки DNS.",
                    technicalReasonRu = "UnknownHostException для '$remoteHost': ${unknownHost.message ?: "имя узла не найдено"}",
                    logCategory = DicomLogCategory.NETWORK_ERROR,
                    logDirection = DicomTrafficDirection.OUTBOUND,
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Хост" to remoteHost,
                        "TCP Порт" to remotePort.toString(),
                        "Категория ошибки" to ScuErrorCategory.DNS_RESOLUTION_ERROR.codeLabel,
                        "Исключение" to "java.net.UnknownHostException: ${unknownHost.message ?: remoteHost}"
                    )
                )
            }

            // 5. TCP Timeout (SocketTimeoutException или ConnectException с ETIMEDOUT / timed out)
            val socketTimeout = chain.filterIsInstance<SocketTimeoutException>().firstOrNull()
            if (socketTimeout != null) {
                return ClassifiedScuError(
                    category = ScuErrorCategory.TCP_TIMEOUT,
                    shortUserMessageRu = "TCP Timeout: превышено время ожидания ответа от $endpoint. Узел недоступен или блокируется межсетевым экраном.",
                    technicalReasonRu = "SocketTimeoutException при обращении к $endpoint: ${socketTimeout.message ?: "timed out"}",
                    logCategory = DicomLogCategory.NETWORK_ERROR,
                    logDirection = DicomTrafficDirection.OUTBOUND,
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Calling AE" to callingAe,
                        "Called AE" to calledAe,
                        "Категория ошибки" to ScuErrorCategory.TCP_TIMEOUT.codeLabel,
                        "Исключение" to "java.net.SocketTimeoutException: ${socketTimeout.message ?: "timed out"}"
                    )
                )
            }

            // 6. ConnectException: различаем Connection Refused (ECONNREFUSED) и Timeout (ETIMEDOUT)
            val connectEx = chain.filterIsInstance<ConnectException>().firstOrNull()
            if (connectEx != null) {
                val msgLower = (connectEx.message ?: "").lowercase(Locale.ROOT)
                return if (msgLower.contains("timed out") || msgLower.contains("etimedout")) {
                    ClassifiedScuError(
                        category = ScuErrorCategory.TCP_TIMEOUT,
                        shortUserMessageRu = "TCP Timeout: узел $endpoint не ответил вовремя при установке TCP-соединения.",
                        technicalReasonRu = "ConnectException (Timeout) к $endpoint: ${connectEx.message}",
                        logCategory = DicomLogCategory.NETWORK_ERROR,
                        logDirection = DicomTrafficDirection.OUTBOUND,
                        technicalDetails = linkedMapOf(
                            "Роль" to "SCU",
                            "Удалённый узел" to endpoint,
                            "Категория ошибки" to ScuErrorCategory.TCP_TIMEOUT.codeLabel,
                            "Исключение" to "java.net.ConnectException: ${connectEx.message}"
                        )
                    )
                } else {
                    ClassifiedScuError(
                        category = ScuErrorCategory.TCP_CONNECTION_REFUSED,
                        shortUserMessageRu = "TCP Connection Refused: узел $endpoint отклонил подключение (DICOM-сервер не запущен на порту $remotePort или порт закрыт).",
                        technicalReasonRu = "ConnectException (Connection Refused) к $endpoint: ${connectEx.message}",
                        logCategory = DicomLogCategory.NETWORK_ERROR,
                        logDirection = DicomTrafficDirection.OUTBOUND,
                        technicalDetails = linkedMapOf(
                            "Роль" to "SCU",
                            "Удалённый узел" to endpoint,
                            "Calling AE" to callingAe,
                            "Called AE" to calledAe,
                            "Категория ошибки" to ScuErrorCategory.TCP_CONNECTION_REFUSED.codeLabel,
                            "Исключение" to "java.net.ConnectException: ${connectEx.message}"
                        )
                    )
                }
            }

            // 7. Connection Reset / Broken Pipe / EOFException (принудительный сброс установленного TCP-соединения удалённой стороной)
            val socketEx = chain.filterIsInstance<SocketException>().firstOrNull()
            val eofEx = chain.filterIsInstance<EOFException>().firstOrNull()
            if (socketEx != null || eofEx != null) {
                val msg = (socketEx?.message ?: eofEx?.message ?: "Connection reset by peer")
                val msgLower = msg.lowercase(Locale.ROOT)
                if (eofEx != null ||
                    msgLower.contains("reset") ||
                    msgLower.contains("econnreset") ||
                    msgLower.contains("broken pipe") ||
                    msgLower.contains("epipe") ||
                    msgLower.contains("abort") ||
                    msgLower.contains("closed")
                ) {
                    return ClassifiedScuError(
                        category = ScuErrorCategory.CONNECTION_RESET,
                        shortUserMessageRu = "Connection Reset: удалённый узел $endpoint принудительно разорвал TCP-соединение ($msg).",
                        technicalReasonRu = "${(socketEx ?: eofEx)!!.javaClass.simpleName}: $msg",
                        logCategory = DicomLogCategory.TCP_DISCONNECT,
                        logDirection = DicomTrafficDirection.INBOUND,
                        technicalDetails = linkedMapOf(
                            "Роль" to "SCU",
                            "Удалённый узел" to endpoint,
                            "Calling AE" to callingAe,
                            "Called AE" to calledAe,
                            "Категория ошибки" to ScuErrorCategory.CONNECTION_RESET.codeLabel,
                            "Исключение" to "${(socketEx ?: eofEx)!!.javaClass.name}: $msg"
                        )
                    )
                }
            }

            // 8. Прочие сетевые ошибки ввода-вывода
            val rootCause = chain.last()
            return ClassifiedScuError(
                category = ScuErrorCategory.IO_PROTOCOL_ERROR,
                shortUserMessageRu = "Ошибка обмена с $endpoint (${rootCause.javaClass.simpleName}): ${rootCause.message ?: "сбой ввода-вывода"}.",
                technicalReasonRu = "${rootCause.javaClass.name}: ${rootCause.message}",
                logCategory = DicomLogCategory.NETWORK_ERROR,
                logDirection = DicomTrafficDirection.OUTBOUND,
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Удалённый узел" to endpoint,
                    "Calling AE" to callingAe,
                    "Called AE" to calledAe,
                    "Категория ошибки" to ScuErrorCategory.IO_PROTOCOL_ERROR.codeLabel,
                    "Исключение" to "${rootCause.javaClass.name}: ${rootCause.message}"
                )
            )
        }

        private fun formatAssociateRjReason(source: Int, reason: Int): String {
            return when (source) {
                AAssociateRJ.SOURCE_SERVICE_USER -> when (reason) {
                    1 -> "1 - no-reason-given"
                    2 -> "2 - application-context-name-not-supported"
                    3 -> "3 - calling-AE-title-not-recognized"
                    7 -> "7 - called-AE-title-not-recognized"
                    else -> "$reason (service-user)"
                }
                AAssociateRJ.SOURCE_SERVICE_PROVIDER_ACSE -> when (reason) {
                    1 -> "1 - no-reason-given"
                    2 -> "2 - protocol-version-not-supported"
                    else -> "$reason (ACSE)"
                }
                AAssociateRJ.SOURCE_SERVICE_PROVIDER_PRES -> when (reason) {
                    1 -> "1 - temporary-congestion"
                    2 -> "2 - local-limit-exceeded"
                    else -> "$reason (Presentation)"
                }
                else -> reason.toString()
            }
        }

        private fun formatAbortReason(reason: Int): String {
            return when (reason) {
                0 -> "0 - reason-not-specified"
                1 -> "1 - unrecognized-PDU"
                2 -> "2 - unexpected-PDU"
                4 -> "4 - unrecognized-PDU-parameter"
                5 -> "5 - unexpected-PDU-parameter"
                6 -> "6 - invalid-PDU-parameter-value"
                else -> reason.toString()
            }
        }

        fun formatPresentationContextResult(result: Int): String {
            return when (result) {
                0 -> "0 - Acceptance (Принят)"
                1 -> "1 - User Rejection (Отклонён пользователем)"
                2 -> "2 - No Reason (Provider Rejection)"
                3 -> "3 - Abstract Syntax Not Supported (SOP Class не поддерживается)"
                4 -> "4 - Transfer Syntaxes Not Supported (Синтаксис передачи не поддерживается)"
                else -> "$result"
            }
        }
    }

    /**
     * Выполняет проверку параметров удалённого узла перед открытием TCP-соединения.
     */
    private fun validateEndpointParameters(
        remoteHost: String,
        remotePort: Int,
        callingAe: String,
        calledAe: String,
        operationName: String,
        assocId: String,
        sessionUid: String = ""
    ): ScuOperationResult.Failure? {
        val hostTrimmed = remoteHost.trim()
        if (hostTrimmed.isEmpty()) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Не указан IP-адрес или хост удалённого PACS.",
                sessionUid = sessionUid
            )
        }
        if (remotePort <= 0) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Не указан TCP-порт удалённого PACS (допустимый диапазон 1..65535).",
                sessionUid = sessionUid
            )
        }
        if (remotePort !in 1..65535) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Некорректный TCP-порт PACS: $remotePort (допустимый диапазон 1..65535).",
                sessionUid = sessionUid
            )
        }
        if (calledAe.isBlank()) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Не указан Called AE Title удалённого PACS (1..16 символов ASCII).",
                sessionUid = sessionUid
            )
        }
        val calledValid = DicomValidationUtils.validateAeTitle(calledAe)
        if (calledValid is DicomValidationUtils.ValidationResult.Invalid) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Called AE Title: ${calledValid.messageRu}",
                sessionUid = sessionUid
            )
        }
        if (callingAe.isBlank()) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Не указан Calling AE Title приложения (1..16 символов ASCII).",
                sessionUid = sessionUid
            )
        }
        val callingValid = DicomValidationUtils.validateAeTitle(callingAe)
        if (callingValid is DicomValidationUtils.ValidationResult.Invalid) {
            return recordValidationFailure(
                assocId = assocId,
                operationName = operationName,
                reason = "Calling AE Title: ${callingValid.messageRu}",
                sessionUid = sessionUid
            )
        }
        return null
    }

    private fun recordValidationFailure(
        assocId: String,
        operationName: String,
        reason: String,
        sessionUid: String = ""
    ): ScuOperationResult.Failure {
        logger.recordTechnicalEvent(
            category = DicomLogCategory.NETWORK_ERROR,
            direction = DicomTrafficDirection.LOCAL,
            associationId = assocId,
            title = "[SCU] Ошибка параметров $operationName",
            summaryLines = listOf(reason),
            technicalDetails = linkedMapOf(
                "Роль" to "SCU",
                "Операция" to operationName,
                "Категория ошибки" to ScuErrorCategory.INVALID_PARAMETERS.codeLabel,
                "Описание" to reason
            ),
            isError = true,
            sessionUid = sessionUid
        )
        return ScuOperationResult.Failure(
            associationId = assocId,
            operationName = operationName,
            errorCategory = ScuErrorCategory.INVALID_PARAMETERS,
            durationMs = 0L,
            shortTitleRu = "$operationName: ошибка параметров",
            userMessageRu = reason,
            technicalReasonRu = reason
        )
    }

    /**
     * Выполняет настоящий DICOM C-ECHO SCU (Verification SOP Class 1.2.840.10008.1.1).
     */
    fun performCEcho(
        remoteHost: String,
        remotePort: Int,
        callingAeTitle: String,
        calledAeTitle: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): ScuOperationResult {
        val session = DicomSessionManager.nextScuSession()
        val assocId = session.displayLabel
        val sessionUid = session.sessionUid
        val host = remoteHost.trim()
        val callingAe = callingAeTitle.trim()
        val calledAe = calledAeTitle.trim()
        val endpoint = "$host:$remotePort"

        validateEndpointParameters(host, remotePort, callingAe, calledAe, "C-ECHO", assocId, sessionUid)?.let {
            return it
        }

        val startNs = System.nanoTime()
        val executor = Executors.newSingleThreadExecutor()
        val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        var association: Association? = null

        try {
            val device = Device("dicomtool-scu")
            val localConn = Connection().apply {
                connectTimeout = connectTimeoutMs
                acceptTimeout = DEFAULT_ACCEPT_TIMEOUT_MS
                requestTimeout = DEFAULT_REQUEST_TIMEOUT_MS
                responseTimeout = DEFAULT_RESPONSE_TIMEOUT_MS
                sendTimeout = DEFAULT_RESPONSE_TIMEOUT_MS
                releaseTimeout = DEFAULT_RELEASE_TIMEOUT_MS
            }
            val localAe = ApplicationEntity(callingAe).apply {
                isAssociationInitiator = true
                isAssociationAcceptor = false
                addConnection(localConn)
            }
            device.addConnection(localConn)
            device.addApplicationEntity(localAe)
            device.executor = executor
            device.scheduledExecutor = scheduledExecutor

            val remoteConn = Connection().apply {
                hostname = host
                port = remotePort
            }

            // 1. Логируем попытку установки исходящего TCP-соединения
            logger.recordTechnicalEvent(
                category = DicomLogCategory.TCP_CONNECT,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Установка TCP-соединения с $endpoint",
                summaryLines = listOf("Calling AE: $callingAe → Called AE: $calledAe"),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Направление" to "OUT (Исходящее)",
                    "Remote Socket" to endpoint,
                    "Удалённый хост" to host,
                    "TCP Порт" to remotePort.toString(),
                    "Calling AE Title" to callingAe,
                    "Called AE Title" to calledAe,
                    "Таймаут подключения" to "${connectTimeoutMs} мс"
                ),
                sessionUid = sessionUid
            )

            // 2. Формируем A-ASSOCIATE-RQ с Verification SOP Class (1.2.840.10008.1.1)
            val rq = AAssociateRQ().apply {
                callingAET = callingAe
                calledAET = calledAe
                addPresentationContext(
                    PresentationContext(
                        1,
                        UID.Verification,
                        UID.ExplicitVRLittleEndian,
                        UID.ImplicitVRLittleEndian
                    )
                )
            }

            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Отправка A-ASSOCIATE-RQ ($callingAe → $calledAe)",
                summaryLines = listOf(
                    "Abstract Syntax: ${UID.Verification} (Verification SOP Class)",
                    "Transfer Syntaxes: Explicit VR Little Endian, Implicit VR Little Endian"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "PDU" to "A-ASSOCIATE-RQ (01H)",
                    "Remote Socket" to endpoint,
                    "Calling AE Title" to callingAe,
                    "Called AE Title" to calledAe,
                    "Presentation Context ID" to "1",
                    "Abstract Syntax UID" to "${UID.Verification} (Verification SOP Class)",
                    "Transfer Syntax UIDs" to "${UID.ExplicitVRLittleEndian}, ${UID.ImplicitVRLittleEndian}"
                ),
                sessionUid = sessionUid
            )

            // 3. Открываем TCP-соединение и выполняем DICOM Association Negotiation
            association = localAe.connect(localConn, remoteConn, rq)
            val ac: AAssociateAC = association.aAssociateAC

            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_AC,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "[SCU] Получен A-ASSOCIATE-AC от $endpoint",
                summaryLines = listOf(
                    "Ассоциация принята узлом ${ac.calledAET} (Impl: ${ac.implVersionName ?: "N/A"})"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "PDU" to "A-ASSOCIATE-AC (02H)",
                    "Remote Socket" to endpoint,
                    "Calling AE Title" to ac.callingAET,
                    "Called AE Title" to ac.calledAET,
                    "Max PDU Length" to ac.maxPDULength.toString(),
                    "Implementation Class UID" to (ac.implClassUID ?: ""),
                    "Implementation Version" to (ac.implVersionName ?: "")
                ),
                sessionUid = sessionUid
            )

            // 4. Проверяем согласованный Presentation Context #1
            val pcAc = ac.getPresentationContext(1)
            val pcResult = pcAc?.result ?: -1
            val acceptedTs = pcAc?.transferSyntax ?: ""
            val isPcAccepted = pcAc != null && pcAc.isAccepted

            logger.recordTechnicalEvent(
                category = DicomLogCategory.PRESENTATION_CONTEXT,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = if (isPcAccepted) {
                    "[SCU] Presentation Context #1 согласован (Verification)"
                } else {
                    "[SCU] Presentation Context #1 отклонён (${formatPresentationContextResult(pcResult)})"
                },
                summaryLines = listOf(
                    "Abstract Syntax: ${UID.Verification}",
                    "Result: ${formatPresentationContextResult(pcResult)}",
                    "Transfer Syntax: ${acceptedTs.ifEmpty { "не согласован" }}"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Presentation Context ID" to "1",
                    "Abstract Syntax UID" to UID.Verification,
                    "Transfer Syntax UID" to acceptedTs.ifEmpty { "N/A" },
                    "Result" to formatPresentationContextResult(pcResult)
                ),
                isError = !isPcAccepted,
                sessionUid = sessionUid
            )

            if (!isPcAccepted) {
                releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val reasonRu = "PACS ($endpoint) отклонил Presentation Context для Verification SOP Class (${UID.Verification}): ${formatPresentationContextResult(pcResult)}."
                return ScuOperationResult.Failure(
                    associationId = assocId,
                    operationName = "C-ECHO",
                    errorCategory = ScuErrorCategory.PRESENTATION_CONTEXT_REJECTED,
                    durationMs = durationMs,
                    shortTitleRu = "C-ECHO: контекст представления отклонён",
                    userMessageRu = reasonRu,
                    technicalReasonRu = reasonRu
                )
            }

            // 5. Отправляем C-ECHO-RQ и ожидаем C-ECHO-RSP
            val echoStartNs = System.nanoTime()

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_ECHO_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Отправка C-ECHO-RQ",
                summaryLines = listOf(
                    "SOP Class UID: ${UID.Verification} (Verification SOP Class)",
                    "Transfer Syntax UID: $acceptedTs"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "DIMSE Command" to "C-ECHO-RQ (0030H)",
                    "Affected SOP Class UID" to UID.Verification,
                    "Transfer Syntax UID" to acceptedTs
                ),
                sessionUid = sessionUid
            )

            val dimseRsp = association.cecho(UID.Verification)
            dimseRsp.next()
            val rspCmd: Attributes? = dimseRsp.command

            val echoDurationMs = ((System.nanoTime() - echoStartNs) / 1_000_000L).coerceAtLeast(1L)
            val totalDurationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

            if (rspCmd == null) {
                releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)
                val noRspMsg = "Превышено время ожидания C-ECHO-RSP от PACS ($endpoint). Ответ не получен."
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.NETWORK_ERROR,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    title = "[SCU] Отсутствует ответ C-ECHO-RSP от $endpoint (таймаут)",
                    summaryLines = listOf(noRspMsg),
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Длительность" to "${totalDurationMs} мс"
                    ),
                    isError = true,
                    sessionUid = sessionUid
                )
                return ScuOperationResult.Failure(
                    associationId = assocId,
                    operationName = "C-ECHO",
                    errorCategory = ScuErrorCategory.DIMSE_ERROR_STATUS,
                    durationMs = totalDurationMs,
                    shortTitleRu = "C-ECHO: ответ не получен",
                    userMessageRu = noRspMsg,
                    technicalReasonRu = noRspMsg
                )
            }

            val rawStatus = rspCmd.getInt(Tag.Status, -1)
            val errorComment = rspCmd.getString(Tag.ErrorComment, null)
            val respMsgId = rspCmd.getInt(Tag.MessageIDBeingRespondedTo, 1)
            val eval = evaluateDimseStatus(rawStatus, errorComment)

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_ECHO_RSP,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "[SCU] Получен C-ECHO-RSP (${eval.statusHex})",
                summaryLines = listOf(
                    eval.descriptionRu,
                    "Длительность C-ECHO: ${echoDurationMs} мс (общая: ${totalDurationMs} мс)"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "DIMSE Command" to "C-ECHO-RSP (8030H)",
                    "Message ID Being Responded To" to respMsgId.toString(),
                    "Affected SOP Class UID" to (rspCmd.getString(Tag.AffectedSOPClassUID) ?: UID.Verification),
                    "DIMSE Status" to eval.statusHex,
                    "Описание статуса" to eval.descriptionRu,
                    "Длительность C-ECHO" to "${echoDurationMs} мс",
                    "Общая длительность" to "${totalDurationMs} мс"
                ),
                isError = eval.kind == ScuDimseStatusKind.FAILURE,
                sessionUid = sessionUid
            )

            // 6. Корректно завершаем ассоциацию A-RELEASE-RQ / A-RELEASE-RP
            releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)

            return when (eval.kind) {
                ScuDimseStatusKind.SUCCESS -> ScuOperationResult.Success(
                    associationId = assocId,
                    operationName = "C-ECHO",
                    statusHex = eval.statusHex,
                    durationMs = totalDurationMs,
                    shortTitleRu = "C-ECHO успешно (${eval.statusHex})",
                    userMessageRu = "C-ECHO успешно выполнен: $endpoint [$callingAe → $calledAe], статус DIMSE ${eval.statusHex} (Success), время ${totalDurationMs} мс."
                )

                ScuDimseStatusKind.WARNING -> ScuOperationResult.Warning(
                    associationId = assocId,
                    operationName = "C-ECHO",
                    statusHex = eval.statusHex,
                    durationMs = totalDurationMs,
                    shortTitleRu = "C-ECHO с предупреждением (${eval.statusHex})",
                    userMessageRu = "C-ECHO завершён со статусом предупреждения ${eval.statusHex}: ${eval.descriptionRu} (${totalDurationMs} мс)."
                )

                ScuDimseStatusKind.FAILURE -> ScuOperationResult.Failure(
                    associationId = assocId,
                    operationName = "C-ECHO",
                    errorCategory = ScuErrorCategory.DIMSE_ERROR_STATUS,
                    statusHex = eval.statusHex,
                    durationMs = totalDurationMs,
                    shortTitleRu = "C-ECHO: ошибка DIMSE (${eval.statusHex})",
                    userMessageRu = "PACS ($endpoint) вернул ошибку в C-ECHO-RSP: ${eval.descriptionRu}.",
                    technicalReasonRu = eval.descriptionRu
                )
            }
        } catch (t: Throwable) {
            val totalDurationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val classified = classifyException(t, host, remotePort, callingAe, calledAe)
            logger.recordTechnicalEvent(
                category = classified.logCategory,
                direction = classified.logDirection,
                associationId = assocId,
                title = "[SCU] Сбой C-ECHO: ${classified.category.titleRu}",
                summaryLines = listOf(
                    classified.shortUserMessageRu,
                    "Длительность до сбоя: ${totalDurationMs} мс"
                ),
                technicalDetails = classified.technicalDetails + mapOf(
                    "Операция" to "C-ECHO SCU",
                    "Длительность" to "${totalDurationMs} мс"
                ),
                isError = true,
                sessionUid = sessionUid
            )
            abortQuietlyIfOpen(association)
            return ScuOperationResult.Failure(
                associationId = assocId,
                operationName = "C-ECHO",
                errorCategory = classified.category,
                durationMs = totalDurationMs,
                shortTitleRu = "C-ECHO: ${classified.category.titleRu}",
                userMessageRu = classified.shortUserMessageRu,
                technicalReasonRu = classified.technicalReasonRu
            )
        } finally {
            executor.shutdownNow()
            scheduledExecutor.shutdownNow()
        }
    }

    /**
     * Выполняет настоящий DICOM C-STORE SCU для подготовленного объекта Secondary Capture (1.2.840.10008.5.1.4.1.1.7).
     *
     * Передача считается успешной ТОЛЬКО при получении от принимающей системы подтверждающего C-STORE-RSP (0x0000 Success).
     * Временный файл создаётся только во внутреннем кэше `tempCacheDir` и удаляется после успешной отправки.
     */
    fun performCStore(
        remoteHost: String,
        remotePort: Int,
        callingAeTitle: String,
        calledAeTitle: String,
        preparedObject: PreparedSecondaryCaptureDicomObject,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): ScuOperationResult {
        val session = DicomSessionManager.nextScuSession()
        val assocId = session.displayLabel
        val sessionUid = session.sessionUid
        val host = remoteHost.trim()
        val callingAe = callingAeTitle.trim()
        val calledAe = calledAeTitle.trim()
        val endpoint = "$host:$remotePort"

        validateEndpointParameters(host, remotePort, callingAe, calledAe, "C-STORE", assocId, sessionUid)?.let {
            return it
        }

        val sopClassUid = preparedObject.uids.sopClassUid
        val sopInstanceUid = preparedObject.uids.sopInstanceUid
        val studyInstanceUid = preparedObject.uids.studyInstanceUid
        val seriesInstanceUid = preparedObject.uids.seriesInstanceUid

        // Записываем временный объект в приватный кэш для поддержки повторной попытки (не в список принятых исследований!)
        var tempCacheFile: File? = null
        val cacheDirLocal = tempCacheDir
        if (cacheDirLocal != null) {
            runCatching {
                tempCacheFile = DicomSecondaryCaptureGenerator.writeToTempCacheFile(preparedObject, cacheDirLocal)
            }
        }

        val startNs = System.nanoTime()
        val executor = Executors.newSingleThreadExecutor()
        val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
        var association: Association? = null

        try {
            val device = Device("dicomtool-scu")
            val localConn = Connection().apply {
                connectTimeout = connectTimeoutMs
                acceptTimeout = DEFAULT_ACCEPT_TIMEOUT_MS
                requestTimeout = DEFAULT_REQUEST_TIMEOUT_MS
                responseTimeout = DEFAULT_RESPONSE_TIMEOUT_MS
                sendTimeout = DEFAULT_RESPONSE_TIMEOUT_MS
                releaseTimeout = DEFAULT_RELEASE_TIMEOUT_MS
            }
            val localAe = ApplicationEntity(callingAe).apply {
                isAssociationInitiator = true
                isAssociationAcceptor = false
                addConnection(localConn)
            }
            device.addConnection(localConn)
            device.addApplicationEntity(localAe)
            device.executor = executor
            device.scheduledExecutor = scheduledExecutor

            val remoteConn = Connection().apply {
                hostname = host
                port = remotePort
            }

            // 1. Логируем попытку установки TCP-соединения
            logger.recordTechnicalEvent(
                category = DicomLogCategory.TCP_CONNECT,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Установка TCP-соединения с $endpoint (C-STORE)",
                summaryLines = listOf(
                    "Calling AE: $callingAe → Called AE: $calledAe",
                    "SOPInstanceUID: $sopInstanceUid"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Направление" to "OUT (Исходящее)",
                    "Remote Socket" to endpoint,
                    "Удалённый хост" to host,
                    "TCP Порт" to remotePort.toString(),
                    "Calling AE Title" to callingAe,
                    "Called AE Title" to calledAe,
                    "SOP Class UID" to sopClassUid,
                    "SOP Instance UID" to sopInstanceUid,
                    "Study Instance UID" to studyInstanceUid,
                    "Series Instance UID" to seriesInstanceUid
                ),
                sessionUid = sessionUid
            )

            // 2. Формируем A-ASSOCIATE-RQ с Secondary Capture Image Storage (1.2.840.10008.5.1.4.1.1.7)
            val rq = AAssociateRQ().apply {
                callingAET = callingAe
                calledAET = calledAe
                addPresentationContext(
                    PresentationContext(
                        1,
                        sopClassUid,
                        UID.ExplicitVRLittleEndian,
                        UID.ImplicitVRLittleEndian
                    )
                )
            }

            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Отправка A-ASSOCIATE-RQ ($callingAe → $calledAe)",
                summaryLines = listOf(
                    "Abstract Syntax: $sopClassUid (Secondary Capture Image Storage)",
                    "Transfer Syntaxes: Explicit VR Little Endian, Implicit VR Little Endian"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "PDU" to "A-ASSOCIATE-RQ (01H)",
                    "Remote Socket" to endpoint,
                    "Calling AE Title" to callingAe,
                    "Called AE Title" to calledAe,
                    "Presentation Context ID" to "1",
                    "Abstract Syntax UID" to "$sopClassUid (Secondary Capture Image Storage)",
                    "Transfer Syntax UIDs" to "${UID.ExplicitVRLittleEndian}, ${UID.ImplicitVRLittleEndian}"
                ),
                sessionUid = sessionUid
            )

            // 3. Открываем TCP-соединение и согласуем ассоциацию
            association = localAe.connect(localConn, remoteConn, rq)
            val ac: AAssociateAC = association.aAssociateAC

            logger.recordTechnicalEvent(
                category = DicomLogCategory.ASSOCIATE_AC,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "[SCU] Получен A-ASSOCIATE-AC от $endpoint",
                summaryLines = listOf(
                    "Ассоциация принята узлом ${ac.calledAET} (Max PDU: ${ac.maxPDULength})"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "PDU" to "A-ASSOCIATE-AC (02H)",
                    "Remote Socket" to endpoint,
                    "Calling AE Title" to ac.callingAET,
                    "Called AE Title" to ac.calledAET,
                    "Max PDU Length" to ac.maxPDULength.toString(),
                    "Implementation Class UID" to (ac.implClassUID ?: ""),
                    "Implementation Version" to (ac.implVersionName ?: "")
                ),
                sessionUid = sessionUid
            )

            // 4. Проверяем согласованный Presentation Context #1
            val pcAc = ac.getPresentationContext(1)
            val pcResult = pcAc?.result ?: -1
            val acceptedTs = pcAc?.transferSyntax ?: ""
            val isPcAccepted = pcAc != null && pcAc.isAccepted

            logger.recordTechnicalEvent(
                category = DicomLogCategory.PRESENTATION_CONTEXT,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = if (isPcAccepted) {
                    "[SCU] Presentation Context #1 согласован (Secondary Capture)"
                } else {
                    "[SCU] Presentation Context #1 отклонён (${formatPresentationContextResult(pcResult)})"
                },
                summaryLines = listOf(
                    "Abstract Syntax: $sopClassUid",
                    "Result: ${formatPresentationContextResult(pcResult)}",
                    "Transfer Syntax: ${acceptedTs.ifEmpty { "не согласован" }}"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Presentation Context ID" to "1",
                    "Abstract Syntax UID" to sopClassUid,
                    "Transfer Syntax UID" to acceptedTs.ifEmpty { "N/A" },
                    "Result" to formatPresentationContextResult(pcResult)
                ),
                isError = !isPcAccepted,
                sessionUid = sessionUid
            )

            if (!isPcAccepted) {
                releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                val reasonRu = "PACS ($endpoint) отклонил Presentation Context для Secondary Capture ($sopClassUid): ${formatPresentationContextResult(pcResult)}."
                return ScuOperationResult.Failure(
                    associationId = assocId,
                    operationName = "C-STORE",
                    errorCategory = ScuErrorCategory.PRESENTATION_CONTEXT_REJECTED,
                    durationMs = durationMs,
                    shortTitleRu = "C-STORE: контекст представления отклонён",
                    userMessageRu = reasonRu,
                    technicalReasonRu = reasonRu
                )
            }

            // 5. Отправляем C-STORE-RQ с реальным набором данных и Pixel Data (512 × 512, 262 144 байт пикселей)
            val messageId = association.nextMessageID()
            val cstoreStartNs = System.nanoTime()
            val transmittedBytesCounter = AtomicLong(0L)
            val rspCmdRef = AtomicReference<Attributes?>(null)

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_STORE_RQ,
                direction = DicomTrafficDirection.OUTBOUND,
                associationId = assocId,
                title = "[SCU] Отправка C-STORE-RQ (MsgID=$messageId)",
                summaryLines = listOf(
                    "SOPClassUID: $sopClassUid | Modality: ${preparedObject.modality}",
                    "SOPInstanceUID: $sopInstanceUid",
                    "Изображение: 512×512, 8-bit MONOCHROME2 (${preparedObject.pixelDataBytes.size} байт Pixel Data)"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "DIMSE Command" to "C-STORE-RQ (0001H)",
                    "Message ID" to messageId.toString(),
                    "Remote Socket" to endpoint,
                    "Calling AE Title" to callingAe,
                    "Called AE Title" to calledAe,
                    "Имя файла" to "Тестовое изображение",
                    "Модальность" to preparedObject.modality,
                    "Размер файла" to "${preparedObject.pixelDataBytes.size} байт",
                    "Affected SOP Class UID" to sopClassUid,
                    "Affected SOP Instance UID" to sopInstanceUid,
                    "Study Instance UID" to studyInstanceUid,
                    "Series Instance UID" to seriesInstanceUid,
                    "Transfer Syntax UID" to acceptedTs,
                    "Modality (0008,0060)" to preparedObject.modality,
                    "Разрешение" to "512 × 512 (8-bit MONOCHROME2)",
                    "Размер Pixel Data" to "${preparedObject.pixelDataBytes.size} байт"
                ),
                sessionUid = sessionUid
            )

            val countingWriter = object : DataWriter {
                private val delegate = DataWriterAdapter(preparedObject.dataset)
                override fun writeTo(out: PDVOutputStream, tsuid: String) {
                    delegate.writeTo(out, tsuid)
                    val estimatedBytes = tempCacheFile?.length() ?: (preparedObject.pixelDataBytes.size.toLong() + 768L)
                    transmittedBytesCounter.set(estimatedBytes)
                }
            }

            val rspHandler = object : DimseRSPHandler(messageId) {
                override fun onDimseRSP(asAssoc: Association?, cmd: Attributes?, data: Attributes?) {
                    super.onDimseRSP(asAssoc, cmd, data)
                    if (cmd != null) {
                        rspCmdRef.set(Attributes(cmd))
                    }
                }
            }

            association.cstore(
                sopClassUid,
                sopInstanceUid,
                Priority.NORMAL,
                countingWriter,
                acceptedTs,
                rspHandler
            )
            association.waitForOutstandingRSP()

            val cstoreDurationMs = ((System.nanoTime() - cstoreStartNs) / 1_000_000L).coerceAtLeast(1L)
            val totalDurationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val sentBytes = transmittedBytesCounter.get().coerceAtLeast(preparedObject.pixelDataBytes.size.toLong())
            val rspCmd = rspCmdRef.get()

            // Не считаем передачу успешной, если фактически не был получен C-STORE-RSP от PACS
            if (rspCmd == null) {
                releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)
                val noRspMsg = "Байты отправлены ($sentBytes байт), но подтверждающий ответ C-STORE-RSP от PACS ($endpoint) не был получен (таймаут ответа). Передача не подтверждена."
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.NETWORK_ERROR,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    title = "[SCU] Превышено время ожидания C-STORE-RSP от $endpoint",
                    summaryLines = listOf(noRspMsg),
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "Удалённый узел" to endpoint,
                        "Message ID" to messageId.toString(),
                        "SOP Class UID" to sopClassUid,
                        "SOP Instance UID" to sopInstanceUid,
                        "Отправлено байт" to "$sentBytes байт",
                        "Длительность" to "${totalDurationMs} мс"
                    ),
                    isError = true,
                    sessionUid = sessionUid
                )
                return ScuOperationResult.Failure(
                    associationId = assocId,
                    operationName = "C-STORE",
                    errorCategory = ScuErrorCategory.DIMSE_ERROR_STATUS,
                    durationMs = totalDurationMs,
                    shortTitleRu = "C-STORE: не получен C-STORE-RSP",
                    userMessageRu = noRspMsg,
                    technicalReasonRu = noRspMsg
                )
            }

            val rawStatus = rspCmd.getInt(Tag.Status, -1)
            val errorComment = rspCmd.getString(Tag.ErrorComment, null)
            val respMsgId = rspCmd.getInt(Tag.MessageIDBeingRespondedTo, messageId)
            val eval = evaluateDimseStatus(rawStatus, errorComment)

            logger.recordTechnicalEvent(
                category = DicomLogCategory.C_STORE_RSP,
                direction = DicomTrafficDirection.INBOUND,
                associationId = assocId,
                title = "[SCU] Получен C-STORE-RSP (${eval.statusHex})",
                summaryLines = listOf(
                    eval.descriptionRu,
                    "Передано: $sentBytes байт за ${cstoreDurationMs} мс (SOPInstanceUID: $sopInstanceUid)"
                ),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "DIMSE Command" to "C-STORE-RSP (8001H)",
                    "Message ID Being Responded To" to respMsgId.toString(),
                    "Affected SOP Class UID" to (rspCmd.getString(Tag.AffectedSOPClassUID) ?: sopClassUid),
                    "Affected SOP Instance UID" to (rspCmd.getString(Tag.AffectedSOPInstanceUID) ?: sopInstanceUid),
                    "Study Instance UID" to studyInstanceUid,
                    "Series Instance UID" to seriesInstanceUid,
                    "DIMSE Status" to eval.statusHex,
                    "Описание статуса" to eval.descriptionRu,
                    "Размер переданных данных" to "$sentBytes байт",
                    "Длительность передачи C-STORE" to "${cstoreDurationMs} мс",
                    "Общая длительность" to "${totalDurationMs} мс"
                ),
                isError = eval.kind == ScuDimseStatusKind.FAILURE,
                sessionUid = sessionUid
            )

            // 6. Корректно завершаем ассоциацию A-RELEASE-RQ / A-RELEASE-RP
            releaseQuietlyWithLogs(association, assocId, endpoint, sessionUid)

            val formattedDuration = String.format(Locale.forLanguageTag("ru"), "%.1f", totalDurationMs / 1000.0)
            val rawPatient = preparedObject.dataset.getString(Tag.PatientName) ?: ""
            val formattedPatient = DicomCharsetDecoder.formatDicomPersonName(rawPatient).ifBlank { rawPatient.trim() }
            val patientLine = if (formattedPatient.isNotBlank()) "Пациент: $formattedPatient\n" else ""

            return when (eval.kind) {
                ScuDimseStatusKind.SUCCESS -> {
                    // Очищаем временный кэш после успешного подтверждения C-STORE-RSP
                    tempCacheFile?.let { runCatching { it.delete() } }
                    val message = "${patientLine}1 файл · Успешно (${eval.statusHex}) · $formattedDuration с"
                    ScuOperationResult.Success(
                        associationId = assocId,
                        operationName = "C-STORE",
                        statusHex = eval.statusHex,
                        durationMs = totalDurationMs,
                        transmittedBytes = sentBytes,
                        shortTitleRu = "Исследование успешно отправлено",
                        userMessageRu = message
                    )
                }

                ScuDimseStatusKind.WARNING -> {
                    tempCacheFile?.let { runCatching { it.delete() } }
                    val message = "${patientLine}1 файл · Предупреждение (${eval.statusHex}) · $formattedDuration с"
                    ScuOperationResult.Warning(
                        associationId = assocId,
                        operationName = "C-STORE",
                        statusHex = eval.statusHex,
                        durationMs = totalDurationMs,
                        transmittedBytes = sentBytes,
                        shortTitleRu = "Исследование отправлено с предупреждением",
                        userMessageRu = message
                    )
                }

                ScuDimseStatusKind.FAILURE -> {
                    // При ошибке временный файл в приватном кэше остаётся для повторной попытки того же объекта
                    ScuOperationResult.Failure(
                        associationId = assocId,
                        operationName = "C-STORE",
                        errorCategory = ScuErrorCategory.DIMSE_ERROR_STATUS,
                        statusHex = eval.statusHex,
                        durationMs = totalDurationMs,
                        shortTitleRu = "C-STORE отклонён (${eval.statusHex})",
                        userMessageRu = "PACS ($endpoint) отклонил сохранение в C-STORE-RSP: ${eval.descriptionRu}. SOPInstanceUID сохранён для повторной попытки.",
                        technicalReasonRu = eval.descriptionRu
                    )
                }
            }
        } catch (t: Throwable) {
            val totalDurationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val classified = classifyException(t, host, remotePort, callingAe, calledAe)
            logger.recordTechnicalEvent(
                category = classified.logCategory,
                direction = classified.logDirection,
                associationId = assocId,
                title = "[SCU] Сбой C-STORE: ${classified.category.titleRu}",
                summaryLines = listOf(
                    classified.shortUserMessageRu,
                    "SOPInstanceUID: $sopInstanceUid (сохранён для повторной попытки)"
                ),
                technicalDetails = classified.technicalDetails + mapOf(
                    "Операция" to "C-STORE SCU",
                    "SOP Class UID" to sopClassUid,
                    "SOP Instance UID" to sopInstanceUid,
                    "Study Instance UID" to studyInstanceUid,
                    "Series Instance UID" to seriesInstanceUid,
                    "Длительность" to "${totalDurationMs} мс"
                ),
                isError = true,
                sessionUid = sessionUid
            )
            abortQuietlyIfOpen(association)
            return ScuOperationResult.Failure(
                associationId = assocId,
                operationName = "C-STORE",
                errorCategory = classified.category,
                durationMs = totalDurationMs,
                shortTitleRu = "C-STORE: ${classified.category.titleRu}",
                userMessageRu = classified.shortUserMessageRu,
                technicalReasonRu = classified.technicalReasonRu
            )
        } finally {
            executor.shutdownNow()
            scheduledExecutor.shutdownNow()
        }
    }

    /**
     * Выполняет пакетную отправку реальных DICOM-файлов через DICOM C-STORE SCU (Этап 4.2).
     *
     * - Сохраняет исходные Study, Series и SOP Instance UID без перегенерации.
     * - Не изменяет свойства пациента (Patient Name, Patient ID, Modality).
     * - Не транскодирует Transfer Syntax автоматически.
     * - Согласует Presentation Context для всех необходимых комбинаций (SOP Class, Transfer Syntax).
     * - При необходимости разбивает отправку на несколько ассоциаций, если количество контекстов превышает 128.
     * - Потоково передаёт файлы через DataWriter без загрузки массивов Pixel Data в оперативную память.
     * - Не прерывает очередь при ошибке отдельного файла: продолжает отправку остальных при сохранении связи.
     * - Отмена закрывает транспорт текущего запуска; подтверждённые RSP сохраняются, in-flight без RSP остаётся неизвестным.
     */
    fun performCStoreFileBatch(
        context: Context,
        remoteHost: String,
        remotePort: Int,
        callingAeTitle: String,
        calledAeTitle: String,
        files: List<ScuCandidateFile>,
        onFileProgress: (Int, ScuCandidateFile, FileSendStatus, String?) -> Unit,
        isCancelled: () -> Boolean,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        cancellation: ScuCancellation = ScuCancellation()
    ): ScuBatchOperationResult = ScuFileBatchSender(logger).send(
        remoteHost.trim(), remotePort, callingAeTitle.trim(), calledAeTitle.trim(), files,
        open = { file ->
            val uri = file.uri ?: throw IOException("Нет URI исходного файла")
            context.contentResolver.openInputStream(uri) ?: throw IOException("Источник недоступен")
        }, progress = onFileProgress, cancellation = cancellation, isCancelled = isCancelled,
        timeouts = ScuTimeouts(connect = connectTimeoutMs)
    )
    private fun releaseQuietlyWithLogs(association: Association?, assocId: String, endpoint: String, sessionUid: String = "") {
        if (association == null) return
        try {
            if (association.isReadyForDataTransfer) {
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.RELEASE_RQ,
                    direction = DicomTrafficDirection.OUTBOUND,
                    associationId = assocId,
                    title = "[SCU] Отправка A-RELEASE-RQ на $endpoint",
                    summaryLines = listOf("Запрос штатного завершения DICOM-ассоциации"),
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "PDU" to "A-RELEASE-RQ (05H)",
                        "Удалённый узел" to endpoint
                    ),
                    sessionUid = sessionUid
                )
                association.release()
                association.waitForSocketClose()
                logger.recordTechnicalEvent(
                    category = DicomLogCategory.RELEASE_RP,
                    direction = DicomTrafficDirection.INBOUND,
                    associationId = assocId,
                    title = "[SCU] Получен A-RELEASE-RP от $endpoint",
                    summaryLines = listOf("DICOM-ассоциация корректно закрыта"),
                    technicalDetails = linkedMapOf(
                        "Роль" to "SCU",
                        "PDU" to "A-RELEASE-RP (06H)",
                        "Удалённый узел" to endpoint
                    ),
                    sessionUid = sessionUid
                )
            }
        } catch (e: Throwable) {
            logger.recordTechnicalEvent(
                category = DicomLogCategory.ABORT,
                direction = DicomTrafficDirection.LOCAL,
                associationId = assocId,
                title = "[SCU] Прерывание при закрытии ассоциации с $endpoint",
                summaryLines = listOf("${e.javaClass.simpleName}: ${e.message ?: ""}"),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Удалённый узел" to endpoint,
                    "Детали" to "${e.javaClass.name}: ${e.message}"
                ),
                isError = true,
                sessionUid = sessionUid
            )
        } finally {
            runCatching { association.socket.close() }
            runCatching { association.waitForSocketClose() }
            if (association.socket.isClosed) logger.recordTechnicalEvent(
                category = DicomLogCategory.TCP_DISCONNECT,
                direction = DicomTrafficDirection.LOCAL,
                associationId = assocId,
                title = "[SCU] TCP-соединение с $endpoint закрыто",
                summaryLines = listOf("Сокет освобождён"),
                technicalDetails = linkedMapOf(
                    "Роль" to "SCU",
                    "Удалённый узел" to endpoint
                ),
                sessionUid = sessionUid
            )
        }
    }

    private fun abortQuietlyIfOpen(association: Association?) {
        if (association == null) return
        try {
            if (association.isReadyForDataTransfer) {
                association.abort()
            }
        } catch (_: Throwable) {
        }
    }
}
