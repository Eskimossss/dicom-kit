package ru.medeng.dicomtool.mwl.engine

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Sequence
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy

/**
 * Построитель стандартных DICOM MWL Datasets (PS3.3 / PS3.4 Annex K).
 *
 * Формирует корректную структуру вложенных последовательностей (Scheduled Procedure Step Sequence),
 * атрибутов с правильными VR и Specific Character Set (0008,0005).
 */
object MwlDatasetBuilder {

    /**
     * Создаёт Attributes ответа C-FIND-RSP для одного найденного назначения.
     *
     * @param patient Пациент (может быть null, если исследование хранится автономно)
     * @param study Назначенное исследование
     * @param requestAttributes Атрибуты из C-FIND-RQ (для определения запрошенных Return Keys)
     * @param characterSet Кодировка ответа (по умолчанию "ISO_IR 192" - UTF-8, либо "ISO_IR 144", "ISO_IR 100", "ISO_IR 6")
     */
    fun buildMwlResponseDataset(
        patient: MwlPatient?,
        study: MwlScheduledStudy,
        requestAttributes: Attributes? = null,
        characterSet: String = "ISO_IR 192"
    ): Attributes {
        ru.medeng.dicomtool.mwl.model.MwlValidation.requireValid(ru.medeng.dicomtool.mwl.model.MwlValidation.validateStudy(study))
        patient?.let { ru.medeng.dicomtool.mwl.model.MwlValidation.requireValid(ru.medeng.dicomtool.mwl.model.MwlValidation.validatePatient(it)) }
        val ds = Attributes()

        // 1. Specific Character Set (0008,0005)
        if (characterSet != "ISO_IR 6") ds.setString(Tag.SpecificCharacterSet, VR.CS, characterSet)

        // 2. Patient Module (0010,xxxx)
        val pName = patient?.patientName ?: study.patientName
        val pId = patient?.patientId ?: study.patientId
        val pBirth = patient?.patientBirthDate ?: ""
        val pSex = patient?.patientSex ?: "O"
        val pWeight = patient?.patientWeight ?: ""
        val pAlerts = patient?.medicalAlerts ?: ""

        ds.setString(Tag.PatientName, VR.PN, pName)
        ds.setString(Tag.PatientID, VR.LO, pId)
        ds.setString(Tag.PatientBirthDate, VR.DA, pBirth)
        ds.setString(Tag.PatientSex, VR.CS, pSex)
        if (pWeight.isNotEmpty()) ds.setString(Tag.PatientWeight, VR.DS, pWeight)
        if (pAlerts.isNotEmpty()) ds.setString(Tag.MedicalAlerts, VR.LO, pAlerts)

        // 3. Requested Procedure Module
        ds.setString(Tag.AccessionNumber, VR.SH, study.accessionNumber)
        ds.setString(Tag.StudyInstanceUID, VR.UI, study.studyInstanceUid)
        ds.setString(Tag.RequestedProcedureID, VR.SH, study.requestedProcedureId)
        ds.setString(Tag.RequestedProcedureDescription, VR.LO, study.requestedProcedureDescription)
        if (study.referringPhysicianName.isNotEmpty()) {
            ds.setString(Tag.ReferringPhysicianName, VR.PN, study.referringPhysicianName)
        }
        if (study.requestingPhysician.isNotEmpty()) {
            ds.setString(Tag.RequestingPhysician, VR.PN, study.requestingPhysician)
        }
        if (study.admissionId.isNotEmpty()) {
            ds.setString(Tag.AdmissionID, VR.LO, study.admissionId)
        }

        // Толерантность к старым аппаратам: если модальность была запрошена на корневом уровне
        if (requestAttributes != null && requestAttributes.contains(Tag.Modality)) {
            ds.setString(Tag.Modality, VR.CS, study.modality)
        }

        // 4. Scheduled Procedure Step Sequence (0040,0100)
        // Внимание: по стандарту DICOM это вложенная Sequence с элементами Item!
        val spsSeq: Sequence = ds.newSequence(Tag.ScheduledProcedureStepSequence, 1)
        val spsItem = Attributes()

        spsItem.setString(Tag.ScheduledStationAETitle, VR.AE, study.scheduledStationAeTitle)
        spsItem.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, study.spsStartDate)
        spsItem.setString(Tag.ScheduledProcedureStepStartTime, VR.TM, study.spsStartTime)
        spsItem.setString(Tag.Modality, VR.CS, study.modality)
        spsItem.setString(Tag.ScheduledProcedureStepID, VR.SH, study.spsId)
        spsItem.setString(Tag.ScheduledProcedureStepDescription, VR.LO, study.spsDescription.ifEmpty { study.requestedProcedureDescription })
        spsItem.setString(Tag.ScheduledStationName, VR.SH, study.scheduledStationName)
        spsItem.setString(Tag.ScheduledPerformingPhysicianName, VR.PN, study.scheduledPerformingPhysician)
        spsItem.setString(Tag.ScheduledProcedureStepLocation, VR.SH, study.spsLocation)
        spsItem.setString(Tag.ScheduledProcedureStepStatus, VR.CS, study.spsStatus.ifEmpty { "SCHEDULED" })

        // Если в запросе были пустые атрибуты внутри SPS (Return Keys), гарантируем их присутствие
        val reqSpsSeq = requestAttributes?.getSequence(Tag.ScheduledProcedureStepSequence)
        if (reqSpsSeq != null && !reqSpsSeq.isEmpty) {
            val reqItem = reqSpsSeq[0]
            val tags = reqItem.tags()
            for (t in tags) {
                if (!spsItem.contains(t)) {
                    val vr = reqItem.getVR(t) ?: VR.LO
                    spsItem.setNull(t, vr)
                }
            }
        }

        spsSeq.add(spsItem)

        // 5. Обработка запрошенных корневых Return Keys: если в запросе были теги с нулевой длиной,
        // возвращаем их пустыми, если у нас нет значения
        if (requestAttributes != null) {
            val reqTags = requestAttributes.tags()
            for (t in reqTags) {
                if (t != Tag.ScheduledProcedureStepSequence && t != Tag.SpecificCharacterSet && !ds.contains(t)) {
                    val vr = requestAttributes.getVR(t) ?: VR.LO
                    ds.setNull(t, vr)
                }
            }
        }

        requireLosslessEncoding(ds)
        return ds
    }

    fun hasUnsupportedReturnKeys(request: Attributes): Boolean {
        val root = setOf(Tag.SpecificCharacterSet, Tag.PatientName, Tag.PatientID, Tag.PatientBirthDate, Tag.PatientSex,
            Tag.PatientWeight, Tag.MedicalAlerts, Tag.AccessionNumber, Tag.StudyInstanceUID, Tag.RequestedProcedureID,
            Tag.RequestedProcedureDescription, Tag.ReferringPhysicianName, Tag.RequestingPhysician, Tag.AdmissionID,
            Tag.Modality, Tag.ScheduledProcedureStepSequence)
        val sps = setOf(Tag.ScheduledStationAETitle, Tag.ScheduledProcedureStepStartDate, Tag.ScheduledProcedureStepStartTime,
            Tag.Modality, Tag.ScheduledProcedureStepID, Tag.ScheduledProcedureStepDescription, Tag.ScheduledStationName,
            Tag.ScheduledPerformingPhysicianName, Tag.ScheduledProcedureStepLocation, Tag.ScheduledProcedureStepStatus)
        return request.tags().any { it !in root } || request.getSequence(Tag.ScheduledProcedureStepSequence)?.any { item -> item.tags().any { it !in sps } } == true
    }

    fun requireLosslessEncoding(ds: Attributes) {
        require((ds.getString(Tag.SpecificCharacterSet) ?: "ISO_IR 6") in setOf("ISO_IR 6", "ISO_IR 100", "ISO_IR 144", "ISO_IR 192", "ISO 2022 IR 100", "ISO 2022 IR 144", "GB18030")) { "Unsupported response character set" }
        val buffer = java.io.ByteArrayOutputStream()
        org.dcm4che3.io.DicomOutputStream(buffer, org.dcm4che3.data.UID.ExplicitVRLittleEndian).use { it.writeDataset(null, ds) }
        val decoded = org.dcm4che3.io.DicomInputStream(java.io.ByteArrayInputStream(buffer.toByteArray()), org.dcm4che3.data.UID.ExplicitVRLittleEndian).use { it.readDataset() }
        fun check(original: Attributes, restored: Attributes) {
            for (tag in original.tags()) {
                if (original.getVR(tag) == VR.SQ) {
                    val items = original.getSequence(tag) ?: continue
                    val readItems = restored.getSequence(tag)
                    require(readItems != null && items.size == readItems.size) { "Sequence encoding mismatch" }
                    items.indices.forEach { check(items[it], readItems[it]) }
                } else if (original.getVR(tag)?.isStringType == true) {
                    require(original.getStrings(tag).contentEquals(restored.getStrings(tag))) { "Response character set cannot represent DICOM text (tag %08X)".format(tag) }
                }
            }
        }
        check(ds, decoded)
    }

    /**
     * Создает C-FIND-RQ Identifier для клиента MWL SCU с набором фильтров и запрошенных Return Keys.
     */
    fun buildMwlScuQueryDataset(
        patientName: String = "",
        patientId: String = "",
        accessionNumber: String = "",
        modality: String = "",
        scheduledStationAe: String = "",
        dateRange: String = "",
        timeRange: String = "",
        requestedProcedureId: String = "",
        characterSet: String = "ISO_IR 192"
    ): Attributes {
        val ds = Attributes()
        if (characterSet != "ISO_IR 6") ds.setString(Tag.SpecificCharacterSet, VR.CS, characterSet)

        // Return keys & matching keys на корневом уровне
        ds.setString(Tag.PatientName, VR.PN, patientName.trim())
        ds.setString(Tag.PatientID, VR.LO, patientId.trim())
        ds.setString(Tag.AccessionNumber, VR.SH, accessionNumber.trim())
        ds.setString(Tag.PatientBirthDate, VR.DA, "")
        ds.setString(Tag.PatientSex, VR.CS, "")
        ds.setString(Tag.PatientWeight, VR.DS, "")
        ds.setString(Tag.MedicalAlerts, VR.LO, "")
        ds.setString(Tag.StudyInstanceUID, VR.UI, "")
        ds.setString(Tag.RequestedProcedureDescription, VR.LO, "")
        ds.setString(Tag.RequestedProcedureID, VR.SH, requestedProcedureId.trim())
        ds.setString(Tag.ReferringPhysicianName, VR.PN, "")
        ds.setString(Tag.RequestingPhysician, VR.PN, "")
        ds.setString(Tag.AdmissionID, VR.LO, "")

        // Scheduled Procedure Step Sequence (0040,0100)
        val spsSeq = ds.newSequence(Tag.ScheduledProcedureStepSequence, 1)
        val spsItem = Attributes()

        spsItem.setString(Tag.ScheduledStationAETitle, VR.AE, scheduledStationAe.trim())
        spsItem.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, dateRange.trim())
        spsItem.setString(Tag.ScheduledProcedureStepStartTime, VR.TM, timeRange.trim())
        spsItem.setString(Tag.Modality, VR.CS, modality.trim())
        spsItem.setString(Tag.ScheduledProcedureStepID, VR.SH, "")
        spsItem.setString(Tag.ScheduledProcedureStepDescription, VR.LO, "")
        spsItem.setString(Tag.ScheduledStationName, VR.SH, "")
        spsItem.setString(Tag.ScheduledPerformingPhysicianName, VR.PN, "")
        spsItem.setString(Tag.ScheduledProcedureStepLocation, VR.SH, "")
        spsItem.setString(Tag.ScheduledProcedureStepStatus, VR.CS, "")

        spsSeq.add(spsItem)

        return ds
    }
}
