package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy

class MwlCharsetEncodingTest {

    private val patientCyrillic = MwlPatient(
        patientName = "СИДОРОВ^АЛЕКСАНДР^ВЛАДИМИРОВИЧ",
        patientId = "PAT-CYR-01",
        patientBirthDate = "19780315",
        patientSex = "M"
    )

    private val studyCyrillic = MwlScheduledStudy(
        patientInternalId = patientCyrillic.id,
        patientName = patientCyrillic.patientName,
        patientId = patientCyrillic.patientId,
        accessionNumber = "ACC-CYR-001",
        studyInstanceUid = "2.25.55",
        requestedProcedureId = "RP-CYR",
        requestedProcedureDescription = "КТ головного мозга без контраста",
        spsId = "SPS-CYR-01",
        modality = "CT",
        spsStartDate = "20261020",
        spsStartTime = "113000",
        scheduledStationAeTitle = "CT_STATION_1",
        scheduledStationName = "Кабинет КТ №1",
        spsDescription = "CT Brain Non-Contrast"
    )

    @Test
    fun testMwlResponseDatasetUtf8() {
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(
            patient = patientCyrillic,
            study = studyCyrillic,
            characterSet = "ISO_IR 192"
        )

        assertEquals("ISO_IR 192", ds.getString(Tag.SpecificCharacterSet))
        assertEquals("СИДОРОВ^АЛЕКСАНДР^ВЛАДИМИРОВИЧ", ds.getString(Tag.PatientName))
        assertEquals("PAT-CYR-01", ds.getString(Tag.PatientID))
        assertEquals("19780315", ds.getString(Tag.PatientBirthDate))
        assertEquals("ACC-CYR-001", ds.getString(Tag.AccessionNumber))
        assertEquals("2.25.55", ds.getString(Tag.StudyInstanceUID))
        assertEquals("КТ головного мозга без контраста", ds.getString(Tag.RequestedProcedureDescription))

        // Проверка вложенной последовательности SPS
        val spsSeq = ds.getSequence(Tag.ScheduledProcedureStepSequence)
        assertNotNull(spsSeq)
        assertEquals(1, spsSeq.size)

        val spsItem = spsSeq[0]
        assertEquals("CT", spsItem.getString(Tag.Modality))
        assertEquals("20261020", spsItem.getString(Tag.ScheduledProcedureStepStartDate))
        assertEquals("113000", spsItem.getString(Tag.ScheduledProcedureStepStartTime))
        assertEquals("CT_STATION_1", spsItem.getString(Tag.ScheduledStationAETitle))
        assertEquals("Кабинет КТ №1", spsItem.getString(Tag.ScheduledStationName))
        assertEquals("SCHEDULED", spsItem.getString(Tag.ScheduledProcedureStepStatus))
    }

    @Test
    fun testMwlResponseDatasetIsoIr144Cyrillic() {
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(
            patient = patientCyrillic,
            study = studyCyrillic,
            characterSet = "ISO_IR 144"
        )

        assertEquals("ISO_IR 144", ds.getString(Tag.SpecificCharacterSet))
        assertEquals("СИДОРОВ^АЛЕКСАНДР^ВЛАДИМИРОВИЧ", ds.getString(Tag.PatientName))
        assertEquals("КТ головного мозга без контраста", ds.getString(Tag.RequestedProcedureDescription))
    }

    @Test
    fun testReturnKeysPreservationWhenRequestedByEquipment() {
        val rq = Attributes()
        rq.setString(Tag.PatientName, VR.PN, "")
        rq.setString(Tag.PatientID, VR.LO, "")
        rq.setString(Tag.PatientWeight, VR.DS, "") // Запрошенный return key
        rq.setString(Tag.AdmissionID, VR.LO, "")   // Запрошенный return key

        val ds = MwlDatasetBuilder.buildMwlResponseDataset(
            patient = patientCyrillic,
            study = studyCyrillic,
            requestAttributes = rq
        )

        assertTrue(ds.contains(Tag.PatientWeight))
        assertTrue(ds.contains(Tag.AdmissionID))
    }
}
