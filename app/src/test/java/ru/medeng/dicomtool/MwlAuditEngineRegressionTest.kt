package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import org.dcm4che3.data.*
import org.dcm4che3.io.*
import ru.medeng.dicomtool.mwl.engine.*
import ru.medeng.dicomtool.mwl.model.*
import java.io.*

class MwlAuditEngineRegressionTest {
    @Test fun audit04_wireRoundTripPreservesCyrillicIncludingNestedPn() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        for (charset in listOf("ISO_IR 144", "ISO_IR 192", "ISO 2022 IR 144", "GB18030")) {
            val dataset = MwlDatasetBuilder.buildMwlResponseDataset(p, s, characterSet = charset)
            val bytes = ByteArrayOutputStream()
            DicomOutputStream(bytes, UID.ExplicitVRLittleEndian).use { it.writeDataset(null, dataset) }
            val read = DicomInputStream(ByteArrayInputStream(bytes.toByteArray()), UID.ExplicitVRLittleEndian).use { it.readDataset() }
            assertEquals(charset, read.getString(Tag.SpecificCharacterSet))
            assertEquals(p.patientName, read.getString(Tag.PatientName))
            assertEquals(s.scheduledPerformingPhysician, read.getNestedDataset(Tag.ScheduledProcedureStepSequence).getString(Tag.ScheduledPerformingPhysicianName))
        }
    }
    @Test fun audit04_unrepresentableTextFailsInsteadOfReplacingCharacters() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        for (charset in listOf("ISO_IR 6", "ISO_IR 100", "ISO 2022 IR 100")) {
            assertThrows(IllegalArgumentException::class.java) { MwlDatasetBuilder.buildMwlResponseDataset(p, s, characterSet = charset) }
        }
        val latin = MwlDatasetBuilder.buildMwlScuQueryDataset(patientName = "MÜLLER^ANDRÉ", characterSet = "ISO_IR 100")
        MwlDatasetBuilder.requireLosslessEncoding(latin)
        MwlDatasetBuilder.requireLosslessEncoding(MwlDatasetBuilder.buildMwlScuQueryDataset(patientName = "SMITH^JOHN", characterSet = "ISO_IR 6"))
    }
    @Test fun audit10_invalidSpsAndRangesAreRejected() {
        val q = Attributes().apply { newSequence(Tag.ScheduledProcedureStepSequence, 2).apply { add(Attributes()); add(Attributes()) } }
        assertThrows(IllegalArgumentException::class.java) { MwlMatchingEngine.extractMatchingQuery(q) }
        q.remove(Tag.ScheduledProcedureStepSequence)
        q.setString(Tag.ScheduledProcedureStepSequence, VR.LO, "invalid")
        assertThrows(IllegalArgumentException::class.java) { MwlMatchingEngine.extractMatchingQuery(q) }
        for (range in listOf("20260230", "20261020-20261019", "20261020-20261021-20261022")) {
            val keys = MwlDatasetBuilder.buildMwlScuQueryDataset(dateRange = range)
            assertThrows(IllegalArgumentException::class.java) { MwlMatchingEngine.extractMatchingQuery(keys) }
        }
    }
    @Test fun audit11_requiredFieldsVrAndSyntheticTemplatesAreValidated() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        for (invalid in listOf(s.copy(spsStartTime = ""), s.copy(requestedProcedureDescription = "", spsDescription = ""), s.copy(spsId = ""), s.copy(spsLocation = "X".repeat(17)))) {
            assertTrue(MwlValidation.validateStudy(invalid) is MwlValidation.Result.Invalid)
            assertThrows(IllegalArgumentException::class.java) { MwlDatasetBuilder.buildMwlResponseDataset(p, invalid) }
        }
        assertTrue(MwlValidation.validateTime("123") is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validateTime("123456.123456") is MwlValidation.Result.Valid)
        for (template in MwlModalityTemplate.entries) {
            val (patient, study) = SyntheticMwlGenerator.createSingleSyntheticPatient(template)
            assertTrue(MwlValidation.validatePatient(patient) is MwlValidation.Result.Valid)
            assertTrue(MwlValidation.validateStudy(study) is MwlValidation.Result.Valid)
        }
    }
    @Test fun audit16_generatedUidsUseUuidRootAndRemainUnique() {
        val values = (1..1000).map { MwlValidation.generateStudyInstanceUid() }
        assertEquals(values.size, values.toSet().size)
        assertTrue(values.all { it.startsWith("2.25.") && it.length <= 64 && MwlValidation.validateStudyInstanceUid(it) is MwlValidation.Result.Valid })
    }
}
