package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.*
import java.io.File
import java.io.IOException
import org.dcm4che3.data.Tag
import ru.medeng.dicomtool.mwl.engine.MwlDatasetBuilder
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine

class Alpha5DataIntegrityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)

    @Test fun importingOneAppointmentRefreshesAllPatientCopiesAndSurvivesRestart() {
        val dir = temp.newFolder("target")
        val repo = LocalMwlRepository(dir, false)
        val (patient, study) = fixture()
        repo.addSyntheticBatch(listOf(patient to study))
        val other = repo.duplicateStudy(study.id)!!
        val incoming = LocalMwlRepository(temp.newFolder("incoming"), false)
        val updated = patient.copy(patientName = "UPDATED^SYNTHETIC", patientId = "UPDATED-ID")
        incoming.savePatient(updated); incoming.saveStudy(study)
        assertEquals(1, repo.importSyntheticJson(incoming.exportSyntheticJson()))
        val reopened = LocalMwlRepository(dir, false)
        assertEquals(setOf(study.id, other.id), reopened.getAllStudies().map { it.id }.toSet())
        reopened.getAllStudies().forEach {
            assertEquals(updated.patientName, it.patientName)
            assertEquals(updated.patientId, it.patientId)
            assertTrue(MwlMatchingEngine.matches(reopened.getPatientById(it.patientInternalId), it,
                MwlMatchingEngine.MatchingQuery(patientId = updated.patientId)))
            val response = MwlDatasetBuilder.buildMwlResponseDataset(reopened.getPatientById(it.patientInternalId), it)
            assertEquals(updated.patientName, response.getString(Tag.PatientName))
            assertEquals(updated.patientId, response.getString(Tag.PatientID))
        }
        assertEquals(other.copy(patientName = updated.patientName, patientId = updated.patientId), reopened.getStudyById(other.id))
        val exported = LocalMwlRepository(temp.newFolder("exported"), false)
        exported.importSyntheticJson(reopened.exportSyntheticJson())
        assertEquals(reopened.snapshot(), exported.snapshot())
    }

    @Test fun missingStudiesSectionCannotBeReplacedByAnEmptyDatabase() {
        val dir = temp.newFolder("broken")
        val file = File(dir, "mwl_snapshot.json")
        file.writeText("{\"version\":1,\"patients\":[]}")
        val original = file.readBytes()
        val repo = LocalMwlRepository(dir, true)
        assertThrows(IOException::class.java) { repo.clearAll() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun failedImportPreservesBothPatientsAndAllAppointmentsOnDiskAndInMemory() {
        val dir = temp.newFolder("atomic-import")
        var fail = false
        val repo = LocalMwlRepository(dir, false) { file, text ->
            AtomicMwlFile.write(file, text) { from, to ->
                if (fail) throw IOException("synthetic replacement failure")
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val (p, s) = fixture(); repo.addSyntheticBatch(listOf(p to s)); repo.duplicateStudy(s.id)
        val incoming = LocalMwlRepository(temp.newFolder("failed-incoming"), false)
        incoming.savePatient(p.copy(patientName = "UPDATED^SYNTHETIC", patientId = "UPDATED-ID")); incoming.saveStudy(s)
        val before = repo.snapshot(); val bytes = File(dir, "mwl_snapshot.json").readBytes()
        fail = true
        assertThrows(IOException::class.java) { repo.importSyntheticJson(incoming.exportSyntheticJson()) }
        assertEquals(before, repo.snapshot())
        assertArrayEquals(bytes, File(dir, "mwl_snapshot.json").readBytes())
        assertEquals(before, LocalMwlRepository(dir, false).snapshot())
        assertFalse(File(dir, "mwl_snapshot.json.tmp").exists())
        fail = false
        assertEquals(1, repo.importSyntheticJson(incoming.exportSyntheticJson()))
    }

    @Test fun damagedUnknownAndWronglyTypedSnapshotsArePreservedAndBlockWritesAndScpSnapshots() {
        val source = LocalMwlRepository(temp.newFolder("valid-source"), false)
        val (p, s) = fixture(); source.addSyntheticBatch(listOf(p to s))
        val input = source.exportSyntheticJson()
        val broken = listOf("{", "{}", "{\"patients\":[],\"studies\":[]}",
            "{\"version\":2,\"patients\":[],\"studies\":[]}",
            "{\"version\":\"1\",\"patients\":[],\"studies\":[]}",
            "{\"version\":1,\"studies\":[]}", "{\"version\":1,\"patients\":[],\"studies\":null}",
            "{\"version\":1,\"patients\":{},\"studies\":[]}",
            "{\"version\":1,\"patients\":\"[]\",\"studies\":[]}",
            "{\"version\":1,\"patients\":[1],\"studies\":[]}",
            "{\"version\":1,\"patients\":[{}],\"studies\":[]}")
        broken.forEachIndexed { index, json ->
            val dir = temp.newFolder("invalid-$index"); val file = File(dir, "mwl_snapshot.json").apply { writeText(json) }
            var writes = 0
            val repo = LocalMwlRepository(dir, true) { _, _ -> writes++ }
            assertNotNull("Missing diagnostic for $json", repo.readErrorFlow.value)
            assertThrows(IOException::class.java) { repo.clearAll() }
            assertThrows(IOException::class.java) { repo.savePatient(p) }
            assertThrows(IOException::class.java) { repo.addSyntheticBatch(listOf(p to s)) }
            assertThrows(IOException::class.java) { repo.importSyntheticJson(input) }
            assertThrows(IOException::class.java) { repo.exportSyntheticJson() }
            assertThrows(IOException::class.java) { repo.snapshot() }
            assertEquals(0, writes)
            assertEquals(json, file.readText())
        }
    }

    @Test fun reloadFailureKeepsLastReadableStateAndCanRecoverOnlyAfterValidFileIsRestored() {
        val dir = temp.newFolder("reload")
        val repo = LocalMwlRepository(dir, false); val (p, s) = fixture(); repo.addSyntheticBatch(listOf(p to s))
        val before = repo.snapshot(); val file = File(dir, "mwl_snapshot.json"); val bytes = file.readBytes()
        file.writeText("{\"version\":1,\"patients\":[],\"studies\":null}")
        repo.reload()
        assertEquals(before.patients, repo.getAllPatients()); assertEquals(before.studies, repo.getAllStudies())
        assertNotNull(repo.readErrorFlow.value)
        assertThrows(IOException::class.java) { repo.savePatient(p.copy(patientName = "CHANGED")) }
        assertTrue(file.readText().contains("null"))
        file.writeBytes(bytes); repo.reload()
        assertNull(repo.readErrorFlow.value); assertEquals(before, repo.snapshot())
    }

    @Test fun damagedSnapshotNeverFallsBackToOrDeletesLegacyFiles() {
        val dir = temp.newFolder("legacy-preserved")
        val snapshot = File(dir, "mwl_snapshot.json").apply { writeText("{\"version\":999}") }
        val legacy = File(dir, "mwl_patients.json").apply { writeText("[]") }
        val repo = LocalMwlRepository(dir, true)
        assertThrows(IOException::class.java) { repo.clearAll() }
        assertEquals("{\"version\":999}", snapshot.readText()); assertEquals("[]", legacy.readText())
    }

    @Test fun validEmptySnapshotDoesNotReseedOrRewriteAndFailedSaveRemainsRetryable() {
        val dir = temp.newFolder("empty"); val file = File(dir, "mwl_snapshot.json")
        val original = "{\"version\":1,\"patients\":[],\"studies\":[]}"
        file.writeText(original)
        var fail = true
        val repo = LocalMwlRepository(dir, true) { f, text -> if (fail) throw IOException("synthetic failure") else AtomicMwlFile.write(f, text) }
        assertNull(repo.readErrorFlow.value); assertTrue(repo.snapshot().patients.isEmpty())
        assertEquals(original, file.readText())
        val (p, _) = fixture()
        assertThrows(IOException::class.java) { repo.savePatient(p) }
        assertNull(repo.readErrorFlow.value); assertTrue(repo.getAllPatients().isEmpty()); assertEquals(original, file.readText())
        fail = false; repo.savePatient(p)
        assertEquals(p.id, LocalMwlRepository(dir, false).getAllPatients().single().id)
    }
}
