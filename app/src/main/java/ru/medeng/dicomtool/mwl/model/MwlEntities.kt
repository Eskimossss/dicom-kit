package ru.medeng.dicomtool.mwl.model

/**
 * Модель пациента в локальной базе Worklist.
 * Соответствует Patient Identification и Patient Demographic модулям DICOM (PS3.3).
 */
data class MwlPatient(
    val id: String = java.util.UUID.randomUUID().toString(),
    val patientName: String, // DICOM PN, e.g. "Иванов^Иван^Иванович" or "TEST^PATIENT"
    val patientId: String,   // DICOM LO (0010,0020), max 64 chars
    val patientBirthDate: String = "", // DICOM DA (0010,0030), YYYYMMDD
    val patientSex: String = "O",      // DICOM CS (0010,0040), "M", "F", "O"
    val patientWeight: String = "",    // DICOM DS (0010,1030)
    val medicalAlerts: String = "",    // DICOM LO (0010,2000)
    val isSynthetic: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Назначенное исследование (Requested Procedure + Scheduled Procedure Step) для пациента.
 * Соответствует Requested Procedure и Scheduled Procedure Step модулям DICOM (PS3.3 / PS3.4 Annex K).
 */
data class MwlScheduledStudy(
    val id: String = java.util.UUID.randomUUID().toString(),
    val patientInternalId: String, // Ссылка на MwlPatient.id
    val patientName: String,       // Денормализованное имя для быстрого поиска и отображения
    val patientId: String,         // Денормализованный Patient ID
    val accessionNumber: String,   // DICOM SH (0008,0050), max 16 chars
    val studyInstanceUid: String,  // DICOM UI (0020,000D), max 64 chars
    val requestedProcedureId: String, // DICOM SH (0040,1001), max 16 chars
    val requestedProcedureDescription: String = "", // DICOM LO (0032,1060), max 64 chars
    val referringPhysicianName: String = "",        // DICOM PN (0008,0090)
    val requestingPhysician: String = "",           // DICOM PN (0032,1032)
    val admissionId: String = "",                   // DICOM LO (0038,0010)

    // Scheduled Procedure Step (SPS) атрибуты:
    val spsId: String,                    // DICOM SH (0040,0009), e.g. "SPS-001"
    val modality: String,                 // DICOM CS (0008,0060), e.g. "CR", "DX", "CT", "MG", "US", "MR"
    val spsStartDate: String,             // DICOM DA (0040,0002), YYYYMMDD
    val spsStartTime: String = "090000",  // DICOM TM (0040,0003), HHMMSS
    val scheduledStationAeTitle: String = "MWL_STATION", // DICOM AE (0040,0001), max 16 chars
    val scheduledStationName: String = "",// DICOM SH (0040,0010), max 16 chars
    val spsDescription: String = "",      // DICOM LO (0040,0007), max 64 chars
    val scheduledPerformingPhysician: String = "", // DICOM PN (0040,0006)
    val spsLocation: String = "",         // DICOM SH (0040,0011)
    val spsStatus: String = "SCHEDULED",  // DICOM CS (0040,0020)

    val isSynthetic: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
