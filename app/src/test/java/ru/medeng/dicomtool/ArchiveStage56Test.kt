package ru.medeng.dicomtool

import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.storage.*
import ru.medeng.dicomtool.dicom.scu.ScuOperationGate
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Only generated two-pixel Secondary Capture instances; never patient data. */
class ArchiveStage56Test {
    @get:Rule val temp = TemporaryFolder()
    private val study = "1.2.826.0.1.56"
    private val series = "$study.2"
    private fun synthetic(file: File, n: Int, pixel: Int = n, sop: String = "$series.$n"): File {
        requireNotNull(file.parentFile).mkdirs()
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, sop)
            setString(Tag.StudyInstanceUID, VR.UI, study)
            setString(Tag.SeriesInstanceUID, VR.UI, series)
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.PatientName, VR.PN, "СИНТЕТИЧЕСКИЙ^ТЕСТ")
            setInt(Tag.Rows, VR.US, 1); setInt(Tag.Columns, VR.US, 2)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8)
            setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
            setInt(Tag.SamplesPerPixel, VR.US, 1)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(pixel.toByte(), 42))
        }
        DicomOutputStream(file).use { it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds) }
        return file
    }
    @Test fun deferredConstructorDoesNotReadHeadersAndReceiveReadsOnlyNewHeader() {
        val root = temp.newFolder("archive")
        repeat(1000) { synthetic(File(root, "$study/$series/$it.dcm"), it) }
        val reads = AtomicInteger()
        val repo = LocalDicomStudyRepository(root, eagerLoad = false, onHeaderRead = { reads.incrementAndGet() })
        assertEquals(0, reads.get()); assertTrue(repo.indexState.value.isLoading)
        val begin = System.nanoTime()
        repo.refreshFromDisk()
        println("STAGE56 index1000_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin))
        assertEquals(1000, reads.get()); assertEquals(1000, repo.getTotalSavedFilesCount())
        assertFalse(repo.indexState.value.isLoading)
        val incoming = synthetic(File(temp.root, "incoming"), 1001)
        assertTrue(repo.saveIncomingInstance(incoming, study, series, "$series.1001") is DicomSaveResult.Success)
        assertEquals("C-STORE must not reread the 1000 archived headers", 1001, reads.get())
        assertEquals(1001, repo.getTotalSavedFilesCount())
        assertEquals(root.walkTopDown().filter { it.extension == "dcm" }.sumOf { it.length() }, repo.getTotalStorageBytes())
    }
    @Test fun parallelReceiveAndRefreshPublishAllVerifiedInstancesWithoutDeadlock() {
        val repo = LocalDicomStudyRepository(temp.newFolder("archive"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val work = (1..30).map { n ->
                val incoming = synthetic(File(temp.root, "incoming$n"), n)
                executor.submit { assertTrue(repo.saveIncomingInstance(incoming, study, series, "$series.$n") is DicomSaveResult.Success) }
            } + (1..5).map { executor.submit { repo.refreshFromDisk() } }
            work.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(30, repo.getTotalSavedFilesCount())
            val before = repo.studiesFlow.value
            repo.refreshFromDisk()
            assertEquals(before, repo.studiesFlow.value)
        } finally { executor.shutdownNow() }
    }
    @Test fun malformedHeaderWarningPreservesBytesAndClearRetainsUnknownDocument() {
        val root = temp.newFolder("archive")
        val bad = File(root, "bad.dcm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val keep = File(root, "keep.txt").apply { writeText("unrelated synthetic document") }
        val repo = LocalDicomStudyRepository(root)
        assertEquals(1, repo.indexState.value.readProblems.size)
        assertNull(repo.indexState.value.warning)
        assertArrayEquals(byteArrayOf(1, 2, 3), bad.readBytes())
        repo.clearAllStudies()
        assertTrue(keep.exists())
    }
    @Test fun oldScuCompletionCannotReleaseNewOperationAndOwnersDoNotOverlap() {
        val first = requireNotNull(ScuOperationGate.acquire("old-vm", "C-STORE"))
        try { assertNull(ScuOperationGate.acquire("new-vm", "C-ECHO")) }
        finally { ScuOperationGate.release(first) }
        val second = requireNotNull(ScuOperationGate.acquire("new-vm", "C-ECHO"))
        try {
            ScuOperationGate.release(first)
            assertSame(second, ScuOperationGate.active.value)
        } finally { ScuOperationGate.release(second) }
        assertNull(ScuOperationGate.active.value)
    }
}
