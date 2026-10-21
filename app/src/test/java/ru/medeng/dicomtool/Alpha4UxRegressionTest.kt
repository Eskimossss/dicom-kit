package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.dcm4che3.data.*
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.*
import ru.medeng.dicomtool.mwl.engine.MwlDatasetInspector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Lossless UI boundaries and round trips; no production patient data or storage. */
class Alpha4UxRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val historicalNames = listOf(
        "ИВАНОВ^ИВАН^ИВАНОВИЧ", "DOE^JOHN^^Dr.^III", "Иванов^John^Иванович^^Jr.",
        "Yamada^Tarou=山田^太郎=やまだ^たろう", "=山田^太郎", "Иванов Иван Иванович",
        "VAN DER WAALS^JOHANNES^^Prof.^", "DOE^JOHN^", " DOE^JOHN ", "^^MID^^SUFFIX"
    )
    @Test fun basicFieldsBuildPnWithoutGuessingSpaceBoundaries() {
        assertEquals("ИВАНОВ^ИВАН^ИВАНОВИЧ", MwlPatientNameInput.compose("ИВАНОВ", "ИВАН", "ИВАНОВИЧ"))
        assertEquals("DOE^JOHN", MwlPatientNameInput.compose("DOE", "JOHN", ""))
        assertEquals("^JOHN", MwlPatientNameInput.compose("", "JOHN", ""))
        assertEquals("VAN DER WAALS^JOHN PAUL", MwlPatientNameInput.compose("VAN DER WAALS", "JOHN PAUL", ""))
        assertNull(MwlPatientNameInput.simpleComponents("Иванов Иван Иванович"))
    }
    @Test fun additionalComponentsAndRepresentationGroupsRequireRawEditor() {
        listOf("DOE^JOHN^^Dr.^III", "DOE^JOHN=ドウ^ジョン", "DOE^JOHN^^^", "=山田^太郎").forEach {
            assertNull(it, MwlPatientNameInput.simpleComponents(it))
        }
        assertEquals(MwlPatientNameInput.Components("ИВАНОВ", "John", "Иванович"), MwlPatientNameInput.simpleComponents("ИВАНОВ^John^Иванович"))
    }
    @Test fun componentFieldsCannotInjectPnSeparatorsOrControls() {
        listOf("A^B", "A=B", "A\\B", "A\nB").forEach { invalid ->
            assertFalse(MwlPatientNameInput.validComponent(invalid))
            assertThrows(IllegalArgumentException::class.java) { MwlPatientNameInput.compose(invalid, "", "") }
        }
    }
    @Test fun historicalPnPatientIdAndExtraPatientFieldsSurviveSaveReload() {
        val repo = LocalMwlRepository(temp.root, false)
        historicalNames.forEachIndexed { index, raw ->
            val p = MwlPatient(id = "p-$index", patientName = raw, patientId = "SYNTH-PAT-%04d".format(index + 1), medicalAlerts = "SYNTHETIC", patientWeight = "72.5")
            repo.savePatient(p)
            val restored = LocalMwlRepository(temp.root, false).getPatientById(p.id)!!
            // Simulates opening the editor and changing only a non-name field.
            repo.savePatient(restored.copy(patientSex = "F"))
            val after = LocalMwlRepository(temp.root, false).getPatientById(p.id)!!
            assertEquals(raw, after.patientName); assertEquals(p.patientId, after.patientId)
            assertEquals(p.medicalAlerts, after.medicalAlerts); assertEquals(p.patientWeight, after.patientWeight)
        }
        assertEquals(historicalNames.size, repo.getAllPatients().size)
    }
    @Test fun pnAndIdRemainExactInLinkedAppointmentsAfterPatientEdit() {
        val repo = LocalMwlRepository(temp.root, false)
        val (original, study) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val p = original.copy(patientName = "TEST^PATIENT^^Prof.^III=ТЕСТ^ПАЦИЕНТ", patientId = "SYNTH-PAT-0001")
        repo.savePatient(p); repo.saveStudy(study); repo.duplicateStudy(study.id)
        repo.savePatient(p.copy(patientBirthDate = "19600229"))
        LocalMwlRepository(temp.root, false).getAllStudies().forEach {
            assertEquals(p.patientName, it.patientName); assertEquals(p.patientId, it.patientId)
        }
    }
    private fun roundTrip(raw: String, charset: String): Attributes {
        val attrs = Attributes().apply { setString(Tag.SpecificCharacterSet, VR.CS, charset); setString(Tag.PatientName, VR.PN, raw); setString(Tag.PatientID, VR.LO, "SYNTH-PAT-0001") }
        val bytes = ByteArrayOutputStream()
        DicomOutputStream(bytes, UID.ExplicitVRLittleEndian).use { it.writeDataset(null, attrs) }
        return DicomInputStream(ByteArrayInputStream(bytes.toByteArray()), UID.ExplicitVRLittleEndian).use { it.readDataset(-1, -1) }
    }
    @Test fun utf8RetainsAllGroupsAndMixedScripts() {
        // dcm4che removes insignificant PN padding on decoding; local storage above preserves even it.
        historicalNames.forEach { raw -> assertEquals(raw.trim(), roundTrip(raw, "ISO_IR 192").getString(Tag.PatientName)) }
    }
    @Test fun isoIr144RetainsCyrillicLatinAndAllFiveComponents() {
        val raw = "ИВАНОВ^John^ИВАНОВИЧ^Dr.^III=Иванов^Иван"
        val result = roundTrip(raw, "ISO_IR 144")
        assertEquals(raw, result.getString(Tag.PatientName)); assertEquals("SYNTH-PAT-0001", result.getString(Tag.PatientID))
    }
    @Test fun birthDateSupportsPastDecadesLeapDayEmptyAndRejectsFuture() {
        val today = LocalDate.of(2026, 10, 21)
        listOf("", "19000101", "19600229", "20000229", "20261021").forEach { assertTrue(it, MwlBirthDate.valid(it, today)) }
        listOf("20261022", "20260229", "19000229", "1980-01-01", "00000101", "1980011").forEach { assertFalse(it, MwlBirthDate.valid(it, today)) }
        assertEquals("29.02.1960", MwlBirthDate.display("19600229"))
    }
    @Test fun modalityChoicesUseStandardCsAndIncludeRequiredCodes() {
        assertTrue(MwlModalityChoices.standard.map { it.first }.containsAll(listOf("CR", "DX", "MG", "CT", "MR", "US", "RF", "XA", "OT")))
        assertEquals(MwlModalityChoices.standard.size, MwlModalityChoices.standard.map { it.first }.toSet().size)
        MwlModalityChoices.standard.forEach { (code, description) ->
            assertTrue(MwlValidation.validateModality(code) is MwlValidation.Result.Valid)
            assertTrue(description.any { it in 'А'..'я' })
        }
        assertTrue(MwlValidation.validateModality("CUSTOM_1") is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateModality("строка") is MwlValidation.Result.Invalid)
    }
    @Test fun profileSelectionAndManualAppointmentEditsNeverMutateSavedProfile() {
        val profile = MwlStationProfile(name = "SYNTHETIC", modality = "RF", scheduledStationAeTitle = "SYNTH_RF", scheduledStationName = "ROOM-1", location = "FLOOR-1")
        val repo = MwlStationProfileRepository(temp.root); repo.save(profile)
        val study = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY).second
        val draft = profile.applyTo(study).copy(modality = "XA", scheduledStationAeTitle = "OTHER", scheduledStationName = "ROOM-2", spsLocation = "FLOOR-2")
        assertEquals("XA", draft.modality)
        assertEquals(profile, MwlStationProfileRepository(temp.root).profilesFlow.value.single())
    }
    @Test fun inspectorRetainsSequenceItemsPrivateAndUnknownAttributes() {
        val attrs = Attributes().apply {
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^RESULT")
            newSequence(Tag.ScheduledProcedureStepSequence, 2).apply {
                repeat(2) { index -> add(Attributes().apply { setString(Tag.ScheduledStationAETitle, VR.AE, "SYNTH_$index"); setString(0x77770010, VR.LO, "SYNTHETIC_PRIVATE") }) }
            }
        }
        val before = Attributes(attrs)
        val lines = MwlDatasetInspector.lines(attrs).joinToString("\n")
        assertTrue(lines.contains("0040,0100")); assertTrue(lines.contains("SYNTH_0")); assertTrue(lines.contains("SYNTH_1"))
        assertTrue(lines.contains("7777,0010")); assertEquals(before, attrs)
    }
}
