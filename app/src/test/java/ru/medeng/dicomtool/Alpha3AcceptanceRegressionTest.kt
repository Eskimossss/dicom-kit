package ru.medeng.dicomtool

import java.io.File
import org.dcm4che3.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.*
import ru.medeng.dicomtool.mwl.engine.*
import ru.medeng.dicomtool.data.logging.*
import ru.medeng.dicomtool.domain.model.*

/** Synthetic fixtures only. Covers the patient, station, date and per-object journal acceptance fixes. */
class Alpha3AcceptanceRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
    private fun repo() = LocalMwlRepository(temp.root, autoSeedIfEmpty = false)
    @Test fun patientWithoutAppointmentsSurvivesRestart() {
        val repo = repo(); val patient = fixture().first
        repo.savePatient(patient)
        val restored = repo()
        assertEquals(patient.id, restored.getAllPatients().single().id)
        assertTrue(restored.getAllStudies().isEmpty())
    }
    @Test fun selectingExistingPatientDoesNotDuplicateAndSupportsMultipleStudies() {
        val repo = repo(); val (patient, study) = fixture()
        repo.savePatient(patient); repo.saveStudy(study); repo.duplicateStudy(study.id)
        val restored = repo()
        assertEquals(1, restored.getAllPatients().size)
        assertEquals(2, restored.getStudiesForPatient(patient.id).size)
    }
    @Test fun explicitReassignmentUsesAuthoritativePatientAndPreservesOtherStudies() {
        val repo = repo(); val (first, study) = fixture()
        val second = first.copy(id = "second", patientName = "СИНТЕТИЧЕСКИЙ^ВТОРОЙ", patientId = "SYNTHETIC-2")
        repo.savePatient(first); repo.savePatient(second); repo.saveStudy(study)
        val other = repo.duplicateStudy(study.id)!!
        repo.saveStudy(study.copy(patientInternalId = second.id, patientName = "stale", patientId = "stale"))
        val restored = repo()
        assertEquals(second.patientName, restored.getStudyById(study.id)!!.patientName)
        assertEquals(second.patientId, restored.getStudyById(study.id)!!.patientId)
        assertEquals(first.id, restored.getStudyById(other.id)!!.patientInternalId)
        assertEquals(2, restored.getAllPatients().size)
    }
    @Test fun danglingReferenceRejectedWithoutChangingSnapshot() {
        val repo = repo(); val (p, s) = fixture(); repo.savePatient(p); repo.saveStudy(s)
        val file = File(temp.root, "mwl_snapshot.json"); val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { repo.saveStudy(s.copy(patientInternalId = "absent")) }
        assertArrayEquals(before, file.readBytes()); assertEquals(p.id, repo.getStudyById(s.id)!!.patientInternalId)
    }
    @Test fun patientEditUpdatesAllLinkedStudiesAndPersists() {
        val repo = repo(); val (p, s) = fixture(); repo.savePatient(p); repo.saveStudy(s); repo.duplicateStudy(s.id)
        val edited = p.copy(patientName = "СИНТЕТИЧЕСКИЙ^ИЗМЕНЁННЫЙ", patientId = "UPDATED-ID")
        repo.savePatient(edited)
        assertTrue(repo().getAllStudies().all { it.patientName == edited.patientName && it.patientId == edited.patientId })
    }
    private fun station() = MwlStationProfile(name = "Тестовый аппарат", modality = "CR", scheduledStationAeTitle = "SYNTHETIC_CR_A",
        scheduledStationName = "Кабинет 1", location = "Этаж 1")
    @Test fun stationSettingsPersistWithoutRewritingPatientDatabase() {
        val repo = repo(); repo.savePatient(fixture().first)
        val snapshot = File(temp.root, "mwl_snapshot.json").readBytes()
        val profiles = MwlStationProfileRepository(temp.root); val profile = station(); profiles.save(profile)
        assertEquals(profile, MwlStationProfileRepository(temp.root).profilesFlow.value.single())
        assertArrayEquals(snapshot, File(temp.root, "mwl_snapshot.json").readBytes())
    }
    @Test fun stationDiskFailureRetainsCommittedStateAndCorruptInputIsPreserved() {
        val profile = station(); MwlStationProfileRepository(temp.root).save(profile)
        val file = File(temp.root, "mwl_station_profiles.json"); val bytes = file.readBytes()
        val failing = MwlStationProfileRepository(temp.root) { _, _ -> throw java.io.IOException("synthetic failure") }
        assertThrows(java.io.IOException::class.java) { failing.save(profile.copy(name = "Changed")) }
        assertEquals(profile, failing.profilesFlow.value.single()); assertArrayEquals(bytes, file.readBytes())
        file.writeText("broken")
        val corrupt = MwlStationProfileRepository(temp.root)
        assertNotNull(corrupt.readError)
        assertThrows(IllegalStateException::class.java) { corrupt.save(profile) }
        assertEquals("broken", file.readText())
    }
    @Test fun fieldLimitsFollowVrRatherThanOneGenericLimit() {
        assertTrue(station().copy(scheduledStationAeTitle = "A".repeat(16), scheduledStationName = "Я".repeat(16), location = "Я".repeat(16)).validate() is MwlValidation.Result.Valid)
        for (p in listOf(station().copy(scheduledStationAeTitle = "A".repeat(17)), station().copy(scheduledStationAeTitle = "АППАРАТ"),
            station().copy(scheduledStationName = "Я".repeat(17)), station().copy(location = "a\\b"))) assertTrue(p.validate() is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validateStudy(fixture().second.copy(requestedProcedureDescription = "Я".repeat(64))) is MwlValidation.Result.Valid)
    }
    @Test fun profileFillsCorrectSpsTagsAndVrWithoutNetworkEndpoint() {
        val (patient, study) = fixture(); val profile = station()
        val ds = MwlDatasetBuilder.buildMwlResponseDataset(patient, profile.applyTo(study))
        val step = ds.getNestedDataset(Tag.ScheduledProcedureStepSequence)
        assertEquals(profile.scheduledStationAeTitle, step.getString(Tag.ScheduledStationAETitle)); assertEquals(VR.AE, step.getVR(Tag.ScheduledStationAETitle))
        assertEquals(profile.scheduledStationName, step.getString(Tag.ScheduledStationName)); assertEquals(VR.SH, step.getVR(Tag.ScheduledStationName))
        assertEquals(profile.location, step.getString(Tag.ScheduledProcedureStepLocation)); assertEquals(VR.SH, step.getVR(Tag.ScheduledProcedureStepLocation))
        assertEquals(patient.patientName, ds.getString(Tag.PatientName))
    }
    @Test fun stationsWithSameModalityRemainSeparatedByStandardMatchingKey() {
        val (patient, study) = fixture()
        val first = station().applyTo(study)
        val second = station().copy(scheduledStationAeTitle = "SYNTHETIC_CR_B").applyTo(study)
        val query = MwlMatchingEngine.extractMatchingQuery(MwlDatasetBuilder.buildMwlScuQueryDataset(
            modality = "CR", scheduledStationAe = first.scheduledStationAeTitle))
        assertTrue(MwlMatchingEngine.matches(patient, first, query))
        assertFalse(MwlMatchingEngine.matches(patient, second, query))
    }
    @Test fun calendarSelectionProducesStandardSingleAndRangeDaAndRejectsReverseRange() {
        val single = MwlScuFilter(MwlDateFilterOption.CUSTOM_DATE, specificDate = "20261021")
        assertEquals("20261021", single.buildDateRangePattern()); assertTrue(single.validateDateSelection() is MwlValidation.Result.Valid)
        val range = MwlScuFilter(MwlDateFilterOption.DATE_RANGE, startDate = "20261021", endDate = "20261023")
        assertEquals("20261021-20261023", range.buildDateRangePattern()); assertTrue(range.validateDateSelection() is MwlValidation.Result.Valid)
        assertTrue(range.copy(endDate = "20261020").validateDateSelection() is MwlValidation.Result.Invalid)
        assertTrue(range.copy(endDate = "").validateDateSelection() is MwlValidation.Result.Invalid)
        assertTrue(single.copy(specificDate = "20260230").validateDateSelection() is MwlValidation.Result.Invalid)
        assertEquals("", MwlScuFilter(MwlDateFilterOption.ANY).buildDateRangePattern())
        assertTrue(MwlMatchingEngine.matchDateRange(range.buildDateRangePattern(), "20261022"))
        val query = MwlDatasetBuilder.buildMwlScuQueryDataset(dateRange = range.buildDateRangePattern())
        assertEquals("20261021-20261023", query.getNestedDataset(Tag.ScheduledProcedureStepSequence).getString(Tag.ScheduledProcedureStepStartDate))
    }
    private fun event(id: String, category: DicomLogCategory, message: String, uid: String, patient: String? = null, extra: Map<String,String> = emptyMap()) =
        DicomLogEntry(id, "12:00:00.000", category, DicomTrafficDirection.INBOUND, "SCP-1", "synthetic", emptyList(),
            technicalDetails = mapOf("Message ID" to message, "SOP Instance UID" to uid) + extra,
            sessionUid = "synthetic-session", patients = listOfNotNull(patient?.let { DicomLogPatient.from(it, "SYNTHETIC-$message") }))
    @Test fun scpPatientNameIsPerObjectIncludingFailedStorageAndMissingMetadata() {
        val events = listOf(
            event("rq1", DicomLogCategory.C_STORE_RQ, "1", "1.2.1"),
            event("store1", DicomLogCategory.SERVER_LIFECYCLE, "1", "1.2.1", "СИНТЕТИЧЕСКИЙ^ПЕРВЫЙ", mapOf("Storage Result" to "SUCCESS")),
            event("rq2", DicomLogCategory.C_STORE_RQ, "2", "1.2.2"),
            event("store2", DicomLogCategory.SERVER_LIFECYCLE, "2", "1.2.2", "СИНТЕТИЧЕСКИЙ^ВТОРОЙ", mapOf("Storage Result" to "FAILED", "DICOM Status" to "0x0110")),
            event("rq3", DicomLogCategory.C_STORE_RQ, "3", "1.2.3"))
        val files = DicomSessionGrouper.groupEvents(events).single().filesTransferred
        assertEquals("СИНТЕТИЧЕСКИЙ^ПЕРВЫЙ", files[0].patientName)
        assertEquals("СИНТЕТИЧЕСКИЙ^ВТОРОЙ", files[1].patientName)
        assertEquals("Не указано", files[2].patientDisplayName)
    }
    @Test fun reusedMessageAndSopIdsDoNotBorrowPatientFromEarlierObject() {
        val events = listOf(event("r1", DicomLogCategory.C_STORE_RQ, "1", "1.2.1"),
            event("p1", DicomLogCategory.SERVER_LIFECYCLE, "1", "1.2.1", "SYNTHETIC^FIRST"),
            event("r2", DicomLogCategory.C_STORE_RQ, "1", "1.2.1"),
            event("p2", DicomLogCategory.SERVER_LIFECYCLE, "1", "1.2.1", "SYNTHETIC^SECOND"))
        assertEquals(listOf("SYNTHETIC^FIRST", "SYNTHETIC^SECOND"), DicomSessionGrouper.groupEvents(events).single().filesTransferred.map { it.patientName })
    }
    @Test fun legacyScuOutcomeUsesExactFileRequestAndNeverOtherObjectWithSameSopUid() {
        val request = event("request", DicomLogCategory.C_STORE_RQ, "1", "1.2.1", "SYNTHETIC^LEGACY",
            mapOf("File ID" to "file-a:0"))
        val outcome = event("outcome", DicomLogCategory.SERVER_LIFECYCLE, "1", "1.2.1", extra =
            mapOf("File ID" to "file-a:0", "SCU File Result" to "SUCCESS"))
        assertEquals("SYNTHETIC^LEGACY", DicomSessionGrouper.groupEvents(listOf(request, outcome)).single().filesTransferred.single().patientName)
        val differentFile = outcome.copy(technicalDetails = outcome.technicalDetails + ("File ID" to "file-b:1"))
        assertEquals("Не указано", DicomSessionGrouper.groupEvents(listOf(request, differentFile)).single().filesTransferred.single().patientDisplayName)
    }
    @Test fun scuSuccessAndErrorNamesPersistPrivatelyButDefaultExportMasksThem() {
        val logger = PersistentDicomTechnicalLogger(File(temp.root, "synthetic.tsv"))
        for ((index, status) in listOf("SUCCESS", "FAILED").withIndex()) logger.recordEvent(event("e$index", DicomLogCategory.SERVER_LIFECYCLE,
            index.toString(), "1.2.$index", "СИНТЕТИЧЕСКИЙ^ОБЪЕКТ$index", mapOf("SCU File Result" to status)))
        assertTrue(logger.flushPendingWrites()); logger.shutdown()
        val loaded = PersistentDicomTechnicalLogger(File(temp.root, "synthetic.tsv"))
        try {
            val entries = loaded.logsFlow.value
            val summary = DicomSessionGrouper.groupEvents(entries).single()
            assertEquals(2, summary.filesTransferred.size)
            assertTrue(summary.filesTransferred.all { it.patientName.startsWith("СИНТЕТИЧЕСКИЙ") })
            assertFalse(DicomLogExportUtils.formatSession(summary).contains("СИНТЕТИЧЕСКИЙ"))
            assertFalse(DicomLogExportUtils.formatFullExport(listOf(summary), entries, true, true).contains("СИНТЕТИЧЕСКИЙ"))
            assertTrue(DicomLogExportUtils.formatSession(summary, false).contains("Синтетический"))
        } finally { loaded.shutdown() }
    }
}
