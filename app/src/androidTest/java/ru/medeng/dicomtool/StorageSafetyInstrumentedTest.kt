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
    private fun dicom(pixel: Int = 1, studyUid: String = study, patientName: String = "SYNTHETIC^STAGE51"): File = File(context.cacheDir, "synthetic-${UUID.randomUUID()}.dcm").apply {
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, sop); setString(Tag.StudyInstanceUID, VR.UI, studyUid); setString(Tag.SeriesInstanceUID, VR.UI, series)
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.PatientName, VR.PN, patientName)
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
    @Test fun actualCStoreResponsesAndCountsFollowDuplicatePolicy() {
        val (manager, _) = setup(); val previous = manager.receptionOptions.value
        val repo = LocalDicomStudyRepository(manager.internalStorageDir, context, manager)
        val logger = ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger(File(context.cacheDir, "duplicates-${UUID.randomUUID()}.tsv"))
        val server = ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer(repo, logger)
        val executor = Executors.newCachedThreadPool(); val scheduler = Executors.newSingleThreadScheduledExecutor()
        var association: org.dcm4che3.net.Association? = null
        try {
            val port = java.net.ServerSocket(0).use { it.localPort }
            assertTrue(server.startServer("TEST_RECEIVER", port).isSuccess)
            val device = org.dcm4che3.net.Device("synthetic-policy-scu")
            val local = org.dcm4che3.net.Connection().apply { connectTimeout = 5000; responseTimeout = 10000; releaseTimeout = 1000 }
            val ae = org.dcm4che3.net.ApplicationEntity("SYNTHETIC")
            ae.addConnection(local); device.addConnection(local); device.addApplicationEntity(ae)
            device.executor = executor; device.scheduledExecutor = scheduler
            val rq = org.dcm4che3.net.pdu.AAssociateRQ().apply {
                callingAET = "SYNTHETIC"; calledAET = "TEST_RECEIVER"
                addPresentationContext(org.dcm4che3.net.pdu.PresentationContext(1, UID.SecondaryCaptureImageStorage, UID.ExplicitVRLittleEndian))
            }
            val a = ae.connect(local, org.dcm4che3.net.Connection("remote", "127.0.0.1", port), rq)
            association = a
            fun send(pixel: Int = 1): Int {
                val ds = org.dcm4che3.io.DicomInputStream(dicom(pixel)).use { it.readFileMetaInformation(); it.readDataset() }
                val rsp = a.cstore(UID.SecondaryCaptureImageStorage, sop, 0, org.dcm4che3.net.DataWriterAdapter(ds), UID.ExplicitVRLittleEndian)
                assertTrue(rsp.next()); return rsp.command.getInt(Tag.Status, -1)
            }
            manager.setReceptionOptions(ReceptionStorageOptions())
            repeat(3) { assertEquals(0, send()) }; assertEquals(3, repo.getTotalSavedFilesCount())
            manager.setReceptionOptions(ReceptionStorageOptions(IncomingDuplicatePolicy.DEDUPLICATE))
            assertEquals(0, send()); assertEquals(3, repo.getTotalSavedFilesCount())
            manager.setReceptionOptions(ReceptionStorageOptions(IncomingDuplicatePolicy.REJECT_IDENTICAL))
            assertEquals(org.dcm4che3.net.Status.ProcessingFailure, send())
            assertEquals(3, repo.getTotalSavedFilesCount())
            assertEquals(0, send(2)); assertEquals(4, repo.getTotalSavedFilesCount())
            val dispositions = logger.logsFlow.value.mapNotNull { it.technicalDetails["Storage Disposition"] }.toSet()
            assertTrue(dispositions.containsAll(setOf("NEW_COPY", "REPEAT_COPY", "DUPLICATE_CONFIRMED", "CONTENT_COLLISION")))
            assertTrue(File(manager.internalStorageDir, ".incoming_tmp").listFiles().orEmpty().isEmpty())
            a.release()
        } finally {
            runCatching { association?.socket?.close() }; server.stopServer()
            executor.shutdownNow(); scheduler.shutdownNow(); logger.shutdown()
            manager.setReceptionOptions(previous); manager.resetToInternalStorage()
        }
    }
    @Test fun receiverPoliciesAndPatientFolderNamesWorkWithSafWithoutRenamingOldStudies() {
        val (manager, uri) = setup()
        val previous = manager.receptionOptions.value
        val repo = LocalDicomStudyRepository(manager.internalStorageDir, context, manager)
        val original = dicom().readBytes()
        fun receive(pixel: Int = 1) = repo.saveIncomingInstance(dicom(pixel), study, series, sop)
        try {
            manager.setReceptionOptions(ReceptionStorageOptions())
            repeat(3) { success(receive()) }
            assertEquals(3, repo.getTotalSavedFilesCount())
            assertEquals(3, manager.ownedSafDocuments(uri).size)
            manager.ownedSafDocuments(uri).forEach { assertArrayEquals(original, bytes(it.id)) }
            manager.setReceptionOptions(ReceptionStorageOptions(IncomingDuplicatePolicy.DEDUPLICATE, true))
            assertEquals(StorageDisposition.DUPLICATE_CONFIRMED, success(receive()).disposition)
            assertEquals(3, repo.getTotalSavedFilesCount())
            // Enabling names does not rename this existing UID directory.
            val root = DocumentFile.fromTreeUri(context, uri)!!
            assertEquals(listOf(study), root.listFiles().filter { it.isDirectory }.map { it.name })
            manager.setReceptionOptions(ReceptionStorageOptions(IncomingDuplicatePolicy.REJECT_IDENTICAL, true))
            assertTrue((receive() as DicomSaveResult.Failure).duplicateRejected)
            success(receive(2)); assertEquals(4, repo.getTotalSavedFilesCount())
            val newStudy = "1.2.840.98765.1"
            val label = StudyFolderNames.create("СИНТЕТИЧЕСКИЙ^ПАЦИЕНТ/../", newStudy)
            success(repo.saveIncomingInstance(dicom(studyUid = newStudy, patientName = "СИНТЕТИЧЕСКИЙ^ПАЦИЕНТ/../"), newStudy, series, sop))
            assertTrue(root.listFiles().any { it.name == label && it.isDirectory })
            assertEquals(manager.receptionOptions.value, DicomStorageManager(context, manager.internalStorageDir).receptionOptions.value)
        } finally { manager.setReceptionOptions(previous); manager.resetToInternalStorage() }
    }
    @Test fun explicitIncomingRecoveryKeepsTruncationAndActiveTransferAndNeverMultipliesCopies() {
        val (manager, _) = setup(); manager.resetToInternalStorage()
        val previous = manager.receptionOptions.value
        manager.setReceptionOptions(ReceptionStorageOptions())
        val repo = LocalDicomStudyRepository(manager.internalStorageDir, context, manager)
        val logger = ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger(File(context.cacheDir, "recovery-${UUID.randomUUID()}.tsv"))
        val server = ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer(repo, logger)
        val operations = ArchiveOperations(repo, server)
        fun idle() {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (operations.busy.value && System.nanoTime() < until) Thread.sleep(20)
            assertFalse(operations.message.value, operations.busy.value)
        }
        val original = dicom().readBytes()
        val folder = File(manager.internalStorageDir, ".incoming_tmp").apply { mkdirs() }
        val full = File(folder, "synthetic-complete.part").apply { writeBytes(original) }
        val brokenBytes = original.copyOf(original.size - 2)
        val broken = File(folder, "synthetic-truncated.part").apply { writeBytes(brokenBytes) }
        val active = IncomingPayloadTracker.create(folder, "scp_synthetic_").apply { writeBytes(original) }
        try {
            idle(); operations.recoverIncoming(); idle()
            assertFalse(operations.message.value, full.exists())
            assertEquals(1, repo.getTotalSavedFilesCount())
            assertArrayEquals(brokenBytes, broken.readBytes()); assertArrayEquals(original, active.readBytes())
            full.writeBytes(original); operations.recoverIncoming(); idle()
            assertFalse(full.exists()); assertEquals(1, repo.getTotalSavedFilesCount())
            assertEquals(1, RetainedIncoming.inspect(manager.internalStorageDir).filesCount)
            repo.clearAllStudies(); assertArrayEquals(brokenBytes, broken.readBytes())
        } finally {
            IncomingPayloadTracker.release(active); operations.closeForTesting(); logger.shutdown()
            manager.setReceptionOptions(previous)
        }
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
