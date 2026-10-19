package ru.medeng.dicomtool

import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import ru.medeng.dicomtool.data.storage.*
import java.io.*

class Stage6ArchiveDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun valid(file: File, nonstandardCharset: Boolean = false) {
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.999.6.3")
            setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.999.6")
            setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.999.6.2")
            if (nonstandardCharset) setBytes(Tag.PatientName, VR.PN, "СИНТЕТИЧЕСКИЙ^ТЕСТ".toByteArray(charset("windows-1251")))
            else setString(Tag.PatientName, VR.PN, "SYNTHETIC^ONLY")
            setInt(Tag.Rows, VR.US, 1); setInt(Tag.Columns, VR.US, 2); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7)
            setInt(Tag.PixelRepresentation, VR.US, 0); setBytes(Tag.PixelData, VR.OB, byteArrayOf(1,2))
        }
        DicomOutputStream(file).use { it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds) }
    }
    @Test fun problemsArePerSourceNotBasenameAndDisappearAfterDeletionOrSuccessfulRead() {
        val root = temp.newFolder("archive")
        val a = File(root, "a/bad.dcm").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3)) }
        val b = File(root, "b/bad.dcm").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4,5,6)) }
        val repo = LocalDicomStudyRepository(root)
        repeat(3) { repo.refreshFromDisk(); assertEquals(2, repo.indexState.value.readProblems.size) }
        assertArrayEquals(byteArrayOf(1,2,3), a.readBytes())
        assertTrue(repo.indexState.value.readProblems.all { it.stage == DicomReadStage.FILE_META })
        assertTrue(a.delete()); repo.refreshFromDisk(); assertEquals(1, repo.indexState.value.readProblems.size)
        valid(b); val bytes = b.readBytes(); repo.refreshFromDisk()
        assertTrue(repo.indexState.value.readProblems.isEmpty()); assertEquals(1, repo.getTotalSavedFilesCount())
        assertArrayEquals(bytes, b.readBytes()); assertNull(repo.indexState.value.warning)
    }
    @Test fun charsetDiagnosticIsNotAHeaderReadFailure() {
        val root = temp.newFolder("charset")
        val file = File(root, "synthetic.dcm"); valid(file, true); val bytes = file.readBytes()
        val repo = LocalDicomStudyRepository(root)
        assertTrue(repo.indexState.value.readProblems.isEmpty())
        assertNotNull(repo.studiesFlow.value.single().encodingWarning)
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun diagnosticsNeverShowRawExceptionText() {
        val p = DicomReadProblem.from("synthetic", "synthetic.dcm", DicomReadStage.HEADER, IOException("SECRET-SYNTHETIC-STACK"))
        assertFalse(p.reason.contains("SECRET")); assertEquals(DicomReadStage.HEADER, p.stage)
        assertEquals("Нет разрешения на чтение", DicomReadProblem.from("x", "x", DicomReadStage.OPEN, SecurityException("SECRET")).reason)
    }
}
