package ru.medeng.dicomtool.domain.model

import android.net.Uri

/**
 * Статус работы локального DICOM Storage SCP приёмника.
 */
enum class ScpServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Сетевой интерфейс устройства (IPv4).
 */
data class NetworkInterfaceInfo(
    val interfaceName: String,
    val ipv4Address: String
)

/**
 * Конфигурация и текущее состояние DICOM SCP приёмника.
 */
data class ScpReceiverState(
    val status: ScpServerStatus = ScpServerStatus.STOPPED,
    val callingAeTitle: String = "ANDROID_PACS",
    val tcpPort: Int = 11112,
    val networkInfo: NetworkInterfaceInfo = NetworkInterfaceInfo("wlan0 (Wi-Fi)", "127.0.0.1"),
    val receivedStudiesCount: Int = 0,
    val receivedImagesCount: Int = 0,
    val activeConnectionsCount: Int = 0,
    val uptimeFormatted: String = "00:00:00",
    val storagePath: String = "",
    val connectedNodes: List<String> = emptyList(),
    val validationError: String? = null,
    val errorMessage: String? = null
)

/**
 * Описание принятого DICOM экземпляра (Instance) внутри серии.
 */
data class DicomInstanceFileItem(
    val sopInstanceUid: String,
    val sopClassUid: String,
    val transferSyntaxUid: String,
    val instanceNumber: Int,
    val rows: Int,
    val columns: Int,
    val bitsAllocated: Int,
    val bitsStored: Int = bitsAllocated,
    val highBit: Int = bitsAllocated - 1,
    val pixelRepresentation: Int = 0,
    val samplesPerPixel: Int = 1,
    val photometricInterpretation: String = "MONOCHROME2",
    val planarConfiguration: Int = 0,
    val numberOfFrames: Int,
    val windowWidth: Int,
    val windowCenter: Int,
    val rescaleSlope: Double = 1.0,
    val rescaleIntercept: Double = 0.0,
    val specificCharacterSet: String = "",
    val encodingWarning: String? = null,
    val filePath: String,
    val fileSizeBytes: Long,
    val duplicateCopyCount: Int = 0
)

/**
 * Описание серии (Series) внутри исследования.
 */
data class DicomSeriesGroup(
    val seriesInstanceUid: String,
    val seriesNumber: Int,
    val modality: String,
    val seriesDescription: String,
    val instances: List<DicomInstanceFileItem>
)

/**
 * Описание реально принятого DICOM исследования (группировка по Study Instance UID и Series Instance UID).
 */
data class DicomStudyItem(
    val studyInstanceUid: String,
    val patientName: String,
    val patientId: String,
    val patientBirthDate: String,
    val patientSex: String,
    val modality: String,
    val studyDateFormatted: String,
    val studyDescription: String,
    val seriesDescription: String,
    val seriesCount: Int,
    val instancesCount: Int,
    val manufacturer: String,
    val institutionName: String,
    val rows: Int = 256,
    val columns: Int = 256,
    val bitsAllocated: Int = 16,
    val bitsStored: Int = 16,
    val highBit: Int = 15,
    val pixelRepresentation: Int = 0,
    val photometricInterpretation: String = "MONOCHROME2",
    val windowWidth: Int = 80,
    val windowCenter: Int = 40,
    val rescaleSlope: Double = 1.0,
    val rescaleIntercept: Double = 0.0,
    val specificCharacterSet: String = "",
    val encodingWarning: String? = null,
    val sopClassUid: String = "",
    val sopInstanceUid: String = "",
    val seriesInstanceUid: String = "",
    val totalBytes: Long = 0L,
    val seriesList: List<DicomSeriesGroup> = emptyList()
)

/**
 * Элемент вложенной последовательности DICOM (Item of SQ).
 */
data class DicomSequenceItem(
    val itemIndex: Int,
    val tags: List<DicomTagEntry> = emptyList()
)

/**
 * Варианты ручного переопределения интерпретации кодировки в браузере тегов (без изменения исходного файла).
 */
enum class DicomEncodingOverride(val labelRu: String, val charsetName: String?) {
    AUTO("Авто (по спецификации файла)", null),
    UTF_8("UTF-8 (ISO_IR 192)", "UTF-8"),
    WINDOWS_1251("Windows-1251 (CP1251)", "windows-1251"),
    ISO_8859_5("ISO-8859-5 (Кириллица)", "ISO-8859-5"),
    ISO_8859_1("ISO-8859-1 (Latin-1)", "ISO-8859-1"),
    KOI8_R("KOI8-R (Кириллица)", "KOI8-R")
}

/**
 * Краткая сводка выбранного через проводник (SAF) DICOM-файла.
 */
data class DicomFileSummary(
    val fileName: String,
    val fileSizeBytes: Long,
    val hasPreambleAndDicm: Boolean,
    val patientName: String,
    val patientId: String,
    val modality: String,
    val studyDateFormatted: String,
    val studyDescription: String,
    val seriesDescription: String,
    val rows: Int,
    val columns: Int,
    val numberOfFrames: Int = 1,
    val bitsAllocated: Int,
    val transferSyntaxUid: String,
    val transferSyntaxName: String,
    val sopClassUid: String,
    val sopClassName: String,
    val specificCharacterSet: String,
    val encodingWarning: String? = null
)

/**
 * Элемент DICOM тега (PS3.6 / PS3.10) с поддержкой русских названий, длинных значений и вложенных SQ.
 */
data class DicomTagEntry(
    val tagHex: String,      // Например "(0010,0010)"
    val vr: String,          // Например "PN", "UI", "SQ"
    val name: String,        // Стандартное название на английском (например "Patient's Name")
    val value: String,       // Полное значение без обрезания
    val nameRu: String? = null, // Русское название (например "ФИО пациента")
    val tagInt: Int = 0,     // Числовой код тега, например 0x00100010
    val isPrivate: Boolean = false,
    val isSequence: Boolean = false,
    val sequenceItems: List<DicomSequenceItem> = emptyList(),
    val isBinary: Boolean = false,
    val binaryByteCount: Long = 0L,
    val depth: Int = 0       // Глубина вложенности в SQ (0 для корневых тегов)
)

/**
 * Режим генератора тестового изображения для C-STORE SCU (Этап 3).
 */
enum class TestImageMode(val labelRu: String) {
    TEXT("Текст"),
    EMOJI("Смайлик"),
    FREEHAND("Рисунок")
}

/**
 * Варианты пола пациента для UI (отображаются на русском, в DICOM записываются как M, F, O или пустое значение).
 */
enum class PatientSexOption(val dicomCode: String, val labelRu: String) {
    MALE("M", "Мужской (M)"),
    FEMALE("F", "Женский (F)"),
    OTHER("O", "Другой (O)"),
    UNSPECIFIED("", "Не указан");

    companion object {
        fun fromDicomCode(code: String): PatientSexOption {
            val normalized = code.trim().uppercase()
            return entries.firstOrNull { it.dicomCode == normalized } ?: UNSPECIFIED
        }
    }
}

/**
 * Точка линии в нормализованных координатах холста (0.0..1.0), не зависящая от размера области предпросмотра на экране.
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

/**
 * Отдельный штрих в режиме «Рисунок» с собственной толщиной линии (в пикселях 512×512).
 */
data class FreehandStroke(
    val points: List<NormalizedPoint>,
    val strokeWidthPx: Float = 8f
)

/**
 * Режим отправки DICOM SCU (Этап 4.2).
 */
enum class ScuSendMode(val labelRu: String) {
    TEST_IMAGE("Тестовое изображение"),
    DICOM_FILES("DICOM-файлы")
}

/**
 * Статус отправки отдельного DICOM-файла в очереди.
 */
enum class FileSendStatus(val labelRu: String) {
    PENDING("Ожидает"),
    SENDING("Отправка..."),
    SUCCESS("Успешно"),
    WARNING("Предупреждение"),
    FAILED("Ошибка"),
    SKIPPED("Пропущен"),
    CANCELLED("Отменён до отправки"),
    UNCONFIRMED("Результат на PACS неизвестен")
}

/**
 * Кандидат на отправку — DICOM-файл, выбранный пользователем (файл, список файлов или каталог).
 */
data class ScuCandidateFile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri? = null,
    val fileName: String,
    val fileSizeBytes: Long,
    val isValidDicom: Boolean,
    val sopClassUid: String? = null,
    val sopClassName: String? = null,
    val sopInstanceUid: String? = null,
    val studyInstanceUid: String? = null,
    val seriesInstanceUid: String? = null,
    val transferSyntaxUid: String? = null,
    val transferSyntaxName: String? = null,
    val modality: String? = null,
    val patientId: String? = null,
    val patientName: String? = null,
    val validationError: String? = null,
    val sendStatus: FileSendStatus = FileSendStatus.PENDING,
    val sendErrorMessage: String? = null,
    val sentDurationMs: Long = 0L
)

/**
 * Текущее состояние фонового сканирования и пакетной отправки DICOM-файлов.
 */
data class ScuBatchProgressState(
    val isSending: Boolean = false,
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val totalFiles: Int = 0,
    val sentSuccessCount: Int = 0,
    val sentWarningCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val cancelledCount: Int = 0,
    val unconfirmedCount: Int = 0,
    val currentIndex: Int = 0,
    val currentFileName: String? = null,
    val summaryMessageRu: String? = null,
    val isCancelled: Boolean = false
)

/**
 * Конфигурация и данные для отправки DICOM SCU (C-ECHO / C-STORE).
 *
 * Сетевые параметры PACS по умолчанию пусты и сохраняются при самостоятельном вводе пользователем.
 */
data class ScuSendConfig(
    val sendMode: ScuSendMode = ScuSendMode.TEST_IMAGE,
    val remoteIp: String = "",
    val remotePort: Int = 0,
    val calledAeTitle: String = "",
    val callingAeTitle: String = "",
    val patientName: String = "ТЕСТ^ИНЖЕНЕР^СЕРВИС",
    val patientId: String = "TEST-001",
    val patientBirthDateDigits: String = "01011990",
    val patientBirthDateDisplay: String = "01.01.1990",
    val patientBirthDate: String = "19900101",
    val patientSex: String = "M",
    val modality: String = "OT",
    val studyDescription: String = "ТЕСТ SCU ANDROID",
    val seriesDescription: String = "SECONDARY CAPTURE 512X512",
    val imageMode: TestImageMode = TestImageMode.TEXT,
    val customText: String = "DICOM TEST\nANDROID SCU\nКИРИЛЛИЦА UTF-8",
    val customTextLine1: String = "DICOM TEST",
    val customTextLine2: String = "ANDROID SCU",
    val smileySeed: Long = 20261012L,
    val brushThicknessPx: Float = 8f,
    val sopClassUid: String = "1.2.840.10008.5.1.4.1.1.7", // Secondary Capture Image Storage
    val isOperationInProgress: Boolean = false,
    val activeOperationName: String? = null,
    val lastSentSopInstanceUid: String? = null,
    val lastStatusTitle: String? = null,
    val lastStatusMessage: String? = null,
    val isLastOperationSuccess: Boolean? = null,
    val isLastOperationWarning: Boolean = false
)


/**
 * Тип технического события в журнале DICOM (PS3.7 / PS3.8).
 */
enum class DicomLogCategory {
    TCP_CONNECT,
    TCP_DISCONNECT,
    ASSOCIATE_RQ,
    ASSOCIATE_AC,
    ASSOCIATE_RJ,
    PRESENTATION_CONTEXT,
    C_ECHO_RQ,
    C_ECHO_RSP,
    C_STORE_RQ,
    C_STORE_RSP,
    RELEASE_RQ,
    RELEASE_RP,
    ABORT,
    NETWORK_ERROR,
    SERVER_LIFECYCLE
}

/**
 * Направление DICOM-обмена в техническом журнале.
 */
enum class DicomTrafficDirection(val code: String) {
    INBOUND("IN"),
    OUTBOUND("OUT"),
    LOCAL("SYS")
}

/**
 * Запись подробного технического журнала DICOM обмена.
 * Сохраняется между перезапусками приложения. Не содержит Pixel Data и ФИО пациента.
 */
data class DicomLogEntry(
    val id: String,
    val timestampFormatted: String,
    val category: DicomLogCategory,
    val direction: DicomTrafficDirection = DicomTrafficDirection.INBOUND,
    val associationId: String = "-",
    val title: String,
    val summaryLines: List<String>,
    val technicalDetails: Map<String, String> = emptyMap(),
    val isError: Boolean = false,
    val sessionUid: String = ""
) {
    val effectiveSessionUid: String
        get() = sessionUid.ifBlank { associationId }
}

