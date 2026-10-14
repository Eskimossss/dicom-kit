package ru.medeng.dicomtool

import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.medeng.dicomtool.data.storage.*
import ru.medeng.dicomtool.storagefixtures.StorageGrantReceiverActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageSafetyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val study = "1.2.840.1"
    private val series = "1.2.840.1.2"
    private val sop = "1.2.840.1.2.3"
    private fun setup(mode: String = "normal"): Pair<DicomStorageManager, Uri> {
        check(context.packageName.endsWith(".storagesafetytest")) { "Run with -Pstage51Isolated=true; never test against regular app data" }
        StorageGrantReceiverActivity.ready = CountDownLatch(1)
        StorageGrantReceiverActivity.granted = null; StorageGrantReceiverActivity.error = null
        instrumentation.startActivitySync(Intent(context, StorageGrantReceiverActivity::class.java)
            .putExtra("root", "${mode}_${UUID.randomUUID()}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(StorageGrantReceiverActivity.ready.await(15, TimeUnit.SECONDS))
        assertNull(StorageGrantReceiverActivity.error)
        val uri = requireNotNull(StorageGrantReceiverActivity.granted)
        val manager = DicomStorageManager(context, File(context.filesDir, "storage-test-${UUID.randomUUID()}").apply { mkdirs() })
        assertTrue(manager.setCustomSafStorage(uri).isSuccess)
        assertTrue(manager.isSafStorageWritable(uri))
        return manager to uri
    }
    private fun dicom(pixel: Int = 1): File = File(context.cacheDir, "synthetic-${UUID.randomUUID()}.dcm").apply {
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, sop); setString(Tag.StudyInstanceUID, VR.UI, study); setString(Tag.SeriesInstanceUID, VR.UI, series)
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^STAGE51")
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setInt(Tag.Rows, VR.US, 2); setInt(Tag.Columns, VR.US, 2); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
            setBytes(Tag.PixelData, VR.OB, ByteArray(4) { pixel.toByte() })
        }
        DicomOutputStream(this).use { it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds) }
    }
    private fun bytes(id: String) = context.contentResolver.openInputStream(Uri.parse(id))!!.use { it.readBytes() }
    private fun success(result: DicomSaveResult): DicomSaveResult.Success {
        assertTrue(result.toString(), result is DicomSaveResult.Success)
        return result as DicomSaveResult.Success
    }
    @Test fun repeatMigrationAfterSourceDeleteDeniedUsesVerifiedCopy() {
        val (manager, uri) = setup()
        manager.resetToInternalStorage()
        val original = dicom().readBytes()
        val saved = success(manager.saveIncomingDicomFile(dicom(), study, series, sop))
        val source = File(saved.destinationDescription)
        val parent = requireNotNull(source.parentFile)
        android.system.Os.chmod(parent.path, 365) // 0555: readable, deletion denied to app UID.
        try {
            repeat(2) {
                val result = manager.migrateFiles(true)
                assertEquals(result.toString(), 1, result.retainedSourceCount)
                assertEquals(0, result.successfullyMigrated)
                assertTrue(result.errorDetails.single().contains("Копия проверена"))
                assertArrayEquals(original, source.readBytes())
                assertEquals(1, manager.ownedSafDocuments(uri).size)
                assertArrayEquals(original, bytes(manager.ownedSafDocuments(uri).single().id))
            }
        } finally { android.system.Os.chmod(parent.path, 448) } // 0700
        assertEquals(1, manager.migrateFiles(true).successfullyMigrated)
        assertFalse(source.exists())
        assertEquals(1, manager.ownedSafDocuments(uri).size)
    }

    @Test fun retainedIncomingSurvivesStudyCleanupAndIsReported() {
        val (manager, _) = setup()
        val root = manager.internalStorageDir
        val repo = LocalDicomStudyRepository(root, context)
        val incoming = File(root, ".incoming_tmp").apply { mkdirs() }
        val source = dicom()
        val original = source.readBytes()
        val retained = File(incoming, "synthetic-failed.part").apply { writeBytes(original) }
        repo.clearAllStudies()
        assertArrayEquals(original, retained.readBytes())
        val status = RetainedIncoming.inspect(root)
        assertEquals(1, status.filesCount)
        assertEquals(original.size.toLong(), status.totalBytes)
        assertNotNull(status.warningMessage)
    }

    @Test fun sameSizeDifferentPixelsAndTrueDuplicates() {
        val (manager, _) = setup()
        val a = dicom(1); val b = dicom(2); val aBytes = a.readBytes(); val bBytes = b.readBytes()
        assertEquals(a.length(), b.length())
        val first = success(manager.saveIncomingDicomFile(a, study, series, sop))
        val second = success(manager.saveIncomingDicomFile(b, study, series, sop))
        assertNotEquals(first.destinationDescription, second.destinationDescription)
        assertArrayEquals(aBytes, bytes(first.destinationDescription)); assertArrayEquals(bBytes, bytes(second.destinationDescription))
        val repeated = success(manager.saveIncomingDicomFile(dicom(2), study, series, sop))
        assertEquals(second.destinationDescription, repeated.destinationDescription)
    }
    @Test fun zeroProviderSizeDoesNotPreventReadbackVerification() {
        val (manager, _) = setup("delayed")
        val source = dicom(); val original = source.readBytes()
        val result = success(manager.saveIncomingDicomFile(source, study, series, sop))
        assertArrayEquals(original, bytes(result.destinationDescription)); assertFalse(source.exists())
    }
    @Test fun shortFinalWriteRetainsSourceAndReturnsFailure() {
        val (manager, _) = setup("short")
        val source = dicom(); val original = source.readBytes()
        assertTrue(manager.saveIncomingDicomFile(source, study, series, sop) is DicomSaveResult.Failure)
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun providerWriteExceptionRetainsSource() {
        val (manager, _) = setup("fail")
        val source = dicom()
        assertTrue(manager.saveIncomingDicomFile(source, study, series, sop) is DicomSaveResult.Failure)
        assertTrue(source.exists())
    }
    @Test fun migrationBothWaysPreservesCollidingOriginalAndCanResume() {
        val (manager, _) = setup()
        val original = dicom(1)
        val originalBytes = original.readBytes()
        manager.resetToInternalStorage()
        val first = manager.saveIncomingDicomFile(original, study, series, sop) as DicomSaveResult.Success
        val migrated = manager.migrateFiles(true)
        assertEquals(migrated.toString(), 1, migrated.successfullyMigrated)
        // Simulate a separately received differing object with identical UID in internal storage.
        val internal = manager.saveIncomingDicomFile(dicom(2), study, series, sop) as DicomSaveResult.Success
        val internalBytes = File(internal.destinationDescription).readBytes()
        val back = manager.migrateFiles(false)
        assertEquals(1, back.successfullyMigrated)
        assertArrayEquals(internalBytes, File(internal.destinationDescription).readBytes())
        assertEquals(2, manager.internalStorageDir.walkTopDown().count { it.extension == "dcm" })
        assertTrue(manager.internalStorageDir.walkTopDown().filter { it.extension == "dcm" }.any { it.readBytes().contentEquals(originalBytes) })
        assertEquals(0, manager.migrateFiles(false).successfullyMigrated)
        assertFalse(File(first.destinationDescription).exists() && File(first.destinationDescription).readBytes().contentEquals(originalBytes))
    }
    @Test fun cleanupKeepsKeepTxtUnknownDicomAndUnknownDirectories() {
        val (manager, tree) = setup()
        assertTrue(manager.saveIncomingDicomFile(dicom(), study, series, sop) is DicomSaveResult.Success)
        val root = DocumentFile.fromTreeUri(context, tree)!!
        val unknown = root.createDirectory("Other")!!.createDirectory("Docs")!!
        val keep = unknown.createFile("text/plain", "keep.txt")!!
        context.contentResolver.openOutputStream(keep.uri)!!.use { it.write("must survive".toByteArray()) }
        val legacy = unknown.createFile("application/dicom", "legacy.dcm")!!
        context.contentResolver.openOutputStream(legacy.uri)!!.use { it.write(dicom().readBytes()) }
        val repo = LocalDicomStudyRepository(manager.internalStorageDir, context, manager)
        assertEquals(1, repo.clearAllStudies())
        assertTrue(unknown.exists()); assertTrue(legacy.exists())
        assertEquals("must survive", String(bytes(keep.uri.toString())))
    }
    @Test fun concurrentSameUidDoesNotLoseDifferentObjects() {
        val (manager, _) = setup()
        val jobs = Executors.newFixedThreadPool(4)
        try {
            val files = (1..4).map { dicom(it) }
            val expected = files.map { it.readBytes() }
            val results = files.map { file -> jobs.submit<DicomSaveResult> { manager.saveIncomingDicomFile(file, study, series, sop) } }
                .map { success(it.get(30, TimeUnit.SECONDS)) }
            assertEquals(4, results.map { it.destinationDescription }.distinct().size)
            expected.forEach { data -> assertTrue(results.any { bytes(it.destinationDescription).contentEquals(data) }) }
        } finally { jobs.shutdownNow() }
    }
    @Test fun oppositeMigrationsCannotDeleteBothIdenticalCopies() {
        val (manager, tree) = setup()
        val payload = dicom().readBytes()
        success(manager.saveIncomingDicomFile(dicom(), study, series, sop))
        manager.resetToInternalStorage()
        success(manager.saveIncomingDicomFile(dicom(), study, series, sop))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val forward = executor.submit<MigrationResult> { manager.migrateFiles(true) }
            val backward = executor.submit<MigrationResult> { manager.migrateFiles(false) }
            forward.get(30, TimeUnit.SECONDS); backward.get(30, TimeUnit.SECONDS)
            val internalCopies = manager.internalStorageDir.walkTopDown().filter { it.isFile && it.extension == "dcm" }.map { it.readBytes() }.toList()
            val safCopies = manager.ownedSafDocuments(tree).map { bytes(it.id) }
            assertTrue((internalCopies + safCopies).any { it.contentEquals(payload) })
        } finally { executor.shutdownNow() }
    }
}
