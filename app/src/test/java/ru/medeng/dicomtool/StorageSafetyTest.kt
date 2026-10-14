package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.data.storage.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StorageSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private val study = "1.2.3"
    private val series = "1.2.3.4"
    private val sop = "1.2.3.4.5"
    private fun dicom(pixel: Int = 1, instanceUid: String = sop): ByteArray {
        val ds = Attributes().apply {
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, instanceUid)
            setString(Tag.StudyInstanceUID, VR.UI, study)
            setString(Tag.SeriesInstanceUID, VR.UI, series)
            setString(Tag.PatientName, VR.PN, "SYNTHETIC^STORAGE^TEST")
            setInt(Tag.Rows, VR.US, 2); setInt(Tag.Columns, VR.US, 2)
            setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8)
            setInt(Tag.HighBit, VR.US, 7); setInt(Tag.SamplesPerPixel, VR.US, 1)
            setInt(Tag.PixelRepresentation, VR.US, 0)
            setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            setBytes(Tag.PixelData, VR.OB, ByteArray(4) { pixel.toByte() })
        }
        val bytes = ByteArrayOutputStream()
        DicomOutputStream(bytes, UID.ExplicitVRLittleEndian).use { it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds) }
        return bytes.toByteArray()
    }
    private fun engine(access: StorageAccess, root: File = temp.root) =
        VerifiedDicomStore(access, DocumentOwnership.forRoot(root), StorageCoordinator.forRoot(root))
    private fun source(bytes: ByteArray, remove: () -> Boolean = { true }) = StorageSource({ ByteArrayInputStream(bytes) }, remove)

    @Test fun sameLengthDifferentPixelDataPreservedInSafAlgorithm() {
        val access = MemoryAccess()
        val store = engine(access)
        val a = dicom(1); val b = dicom(2)
        assertEquals(a.size, b.size)
        assertTrue(store.save(source(a), study, series, sop) is DicomSaveResult.Success)
        assertTrue(store.save(source(b), study, series, sop) is DicomSaveResult.Success)
        val saved = access.files().filter { it.name.endsWith(".dcm") }
        assertEquals(2, saved.size)
        assertTrue(saved.any { access.read(it).readBytes().contentEquals(a) })
        assertTrue(saved.any { access.read(it).readBytes().contentEquals(b) })
    }
    @Test fun trueDuplicateComparedByBytesAndNotWrittenAgain() {
        val access = MemoryAccess(); val store = engine(access)
        repeat(2) { assertTrue(store.save(source(dicom()), study, series, sop) is DicomSaveResult.Success) }
        assertEquals(1, access.files().size)
    }
    @Test fun retainedSourceCanBeRetriedWithoutExtraCopiesOrDataLoss() {
        val access = MemoryAccess(); val store = engine(access)
        val original = dicom(2)
        val retained = source(original) { false }
        val first = store.save(retained, study, series, sop) as DicomSaveResult.Success
        repeat(3) {
            val retry = store.save(retained, study, series, sop) as DicomSaveResult.Success
            assertEquals(first.destinationDescription, retry.destinationDescription)
            assertTrue(retry.sourceRetained)
            assertTrue(retry.duplicateActionNote.contains(RETAINED_SOURCE_WARNING))
            assertArrayEquals(original, retained.open().use { stream -> stream.readBytes() })
            assertEquals(1, access.files().size)
        }
        val completed = store.save(source(original), study, series, sop) as DicomSaveResult.Success
        assertFalse(completed.sourceRetained)
        assertEquals(first.destinationDescription, completed.destinationDescription)
        assertArrayEquals(original, access.read(access.files().single()).readBytes())
    }

    @Test fun truncatedDestinationNeverReturnsSuccessOrDeletesSource() {
        val access = MemoryAccess(truncateFinal = true)
        var removed = false
        assertTrue(engine(access).save(source(dicom()) { removed = true; true }, study, series, sop) is DicomSaveResult.Failure)
        assertFalse(removed); assertTrue(access.files().isEmpty())
    }
    @Test fun copyExceptionPreservesSourceAndExistingOriginal() {
        val access = MemoryAccess(); val store = engine(access)
        val original = store.save(source(dicom()), study, series, sop) as DicomSaveResult.Success
        access.failWrite = true
        var removed = false
        assertTrue(store.save(source(dicom(2)) { removed = true; true }, study, series, sop) is DicomSaveResult.Failure)
        assertFalse(removed)
        assertArrayEquals(dicom(), access.read(access.files().single { it.id == original.destinationDescription }).readBytes())
    }
    @Test fun delayedReadVisibilityIsVerifiedByReadingAgain() {
        val access = MemoryAccess(delayedReads = true)
        assertTrue(engine(access).save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
    }
    @Test fun internalMigrationCollisionDoesNotOverwriteOriginal() {
        val dest = temp.newFolder("internal")
        val store = engine(FileStorageAccess(dest))
        val a = temp.newFile("a.dcm").apply { writeBytes(dicom(1)) }
        val b = temp.newFile("b.dcm").apply { writeBytes(dicom(2)) }
        val first = store.save(a.storageSource(), study, series, sop) as DicomSaveResult.Success
        assertTrue(store.save(b.storageSource(), study, series, sop) is DicomSaveResult.Success)
        assertArrayEquals(dicom(1), File(first.destinationDescription).readBytes())
        assertEquals(2, dest.walkTopDown().count { it.extension == "dcm" })
    }
    @Test fun interruptedAfterCommitCanResumeWithoutLosingOrDuplicatingData() {
        val access = MemoryAccess(); val store = engine(access)
        val interrupted = source(dicom()) { throw IOException("simulated interruption before source deletion") }
        assertTrue(store.save(interrupted, study, series, sop) is DicomSaveResult.Failure)
        assertEquals(1, access.files().size) // Already verified/committed destination must not be rolled back.
        assertTrue(store.save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
        assertEquals(1, access.files().size)
    }
    @Test fun interruptedThreadCannotDeleteSource() {
        var removed = false
        Thread.currentThread().interrupt()
        try {
            assertTrue(engine(MemoryAccess()).save(source(dicom()) { removed = true; true }, study, series, sop) is DicomSaveResult.Failure)
            assertFalse(removed)
        } finally { Thread.interrupted() }
    }
    @Test fun unsafeUidsAndPathsRejectedWithoutChangingSource() {
        val access = MemoryAccess()
        for (uid in listOf("..", ".", "", "1/../../x", "1\\2", "1..2", "01.2", "1.02", "1.2 ", "1.")) {
            var removed = false
            assertTrue(engine(access).save(source(dicom()) { removed = true; true }, uid, series, sop) is DicomSaveResult.Failure)
            assertFalse(removed)
        }
        assertTrue(access.files().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { StoragePaths.contained(temp.root, File(temp.root, "../outside.dcm")) }
    }
    @Test fun parallelSameUidDifferentObjectsAllSurvive() {
        val access = MemoryAccess(); val store = engine(access)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { pixel -> executor.submit<DicomSaveResult> {
                start.await(); store.save(source(dicom(pixel)), study, series, sop)
            } }
            start.countDown()
            futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS) is DicomSaveResult.Success) }
            assertEquals(8, access.files().size)
            for (pixel in 1..8) assertTrue(access.files().any { access.read(it).readBytes().contentEquals(dicom(pixel)) })
        } finally { executor.shutdownNow() }
    }
    @Test fun independentSopsCanCopyAtTheSameTime() {
        val access = MemoryAccess()
        val both = CountDownLatch(2)
        access.onStageWrite = { both.countDown(); check(both.await(3, TimeUnit.SECONDS)) { "Independent UID blocked" } }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { n -> executor.submit<DicomSaveResult> { engine(access).save(source(dicom(instanceUid = "$sop.$n")), study, series, "$sop.$n") } }
            jobs.forEach { assertTrue(it.get(10, TimeUnit.SECONDS) is DicomSaveResult.Success) }
        } finally { executor.shutdownNow() }
    }
    @Test fun cleanupKeepsUnknownKeepTxtAndLegacyDicomAndNeverDeletesDirectories() {
        val access = MemoryAccess(); val store = engine(access)
        assertTrue(store.save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
        val folder = access.directory(access.root, "Other")
        val keep = access.create(folder, "keep.txt").also { access.write(it).use { out -> out.write("keep".toByteArray()) } }
        val legacy = access.create(folder, "legacy.dcm").also { access.write(it).use { out -> out.write(dicom()) } }
        assertEquals(1, store.clearOwned())
        assertEquals("keep", access.read(keep).reader().readText())
        assertArrayEquals(dicom(), access.read(legacy).readBytes())
        assertEquals(0, access.deletedDirectories)
    }
    @Test fun changedOwnedDocumentIsNotDeleted() {
        val access = MemoryAccess(); val store = engine(access)
        val saved = store.save(source(dicom()), study, series, sop) as DicomSaveResult.Success
        val doc = access.files().single { it.id == saved.destinationDescription }
        access.write(doc).use { it.write(dicom(2)) }
        assertEquals(0, store.clearOwned()); assertEquals(1, access.files().size)
    }
    @Test fun identicalUnknownDocumentIsNotClaimedForCleanup() {
        val access = MemoryAccess()
        val parent = access.directory(access.directory(access.root, study), series)
        val original = access.create(parent, "$sop.dcm").also { access.write(it).use { out -> out.write(dicom()) } }
        val store = engine(access)
        assertTrue(store.save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
        assertEquals(0, store.clearOwned()); assertArrayEquals(dicom(), access.read(original).readBytes())
    }
    @Test fun ledgerPersistenceFailureNeverDeletesIncomingSource() {
        val root = temp.newFolder("blocked-ledger")
        File(root, ".storage_ownership.tsv").mkdir()
        var removed = false
        assertTrue(engine(MemoryAccess(), root).save(source(dicom()) { removed = true; true }, study, series, sop) is DicomSaveResult.Failure)
        assertFalse(removed)
    }
    @Test fun ownershipReloadFromDiskAllowsOnlyRecordedDocumentsToBeCleaned() {
        val access = MemoryAccess()
        assertTrue(engine(access).save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
        val reloaded = DocumentOwnership(File(temp.root, ".storage_ownership.tsv"))
        val afterRestart = VerifiedDicomStore(access, reloaded, StorageCoordinator.forRoot(temp.root))
        assertEquals(1, afterRestart.clearOwned())
    }
    @Test fun corruptOwnershipLedgerFailsClosed() {
        val access = MemoryAccess()
        assertTrue(engine(access).save(source(dicom()), study, series, sop) is DicomSaveResult.Success)
        File(temp.root, ".storage_ownership.tsv").writeText("synthetic torn record")
        val afterRestart = VerifiedDicomStore(access, DocumentOwnership(File(temp.root, ".storage_ownership.tsv")), StorageCoordinator.forRoot(temp.root))
        assertEquals(0, afterRestart.clearOwned()); assertEquals(1, access.files().size)
    }
    @Test fun privateRepositoryUsesSafeSaveAndCleanupKeepsNonDicom() {
        val root = temp.newFolder("repository")
        val repo = LocalDicomStudyRepository(root)
        val a = temp.newFile("repo-a.dcm").apply { writeBytes(dicom()) }
        val b = temp.newFile("repo-b.dcm").apply { writeBytes(dicom(2)) }
        assertTrue(repo.saveIncomingInstance(a, study, series, sop) is DicomSaveResult.Success)
        assertTrue(repo.saveIncomingInstance(b, study, series, sop) is DicomSaveResult.Success)
        val keep = File(root, "keep.txt").apply { writeText("keep") }
        val incoming = File(root, ".incoming_tmp").apply { mkdirs() }
        val retained = File(incoming, "failed.part").apply { writeBytes(dicom(3)) }
        val original = retained.readBytes()
        assertEquals(2, repo.clearAllStudies()); assertEquals("keep", keep.readText())
        assertArrayEquals(original, retained.readBytes())
        val pending = RetainedIncoming.inspect(root)
        assertEquals(1, pending.filesCount)
        assertEquals(original.size.toLong(), pending.totalBytes)
        assertTrue(pending.warningMessage!!.contains("Очистка исследований их не удаляет"))
    }

    private class MemoryAccess(val truncateFinal: Boolean = false, val delayedReads: Boolean = false) : StorageAccess {
        override val scope = "memory:${UUID.randomUUID()}"
        override val root = StorageNode("root", "root", true)
        private val nodes = linkedMapOf(root.id to root)
        private val parents = mutableMapOf<String, String>()
        private val bytes = mutableMapOf<String, ByteArray>()
        private val delayed = mutableSetOf<String>()
        var failWrite = false
        var onStageWrite: (() -> Unit)? = null
        var deletedDirectories = 0
        @Synchronized override fun children(parent: StorageNode) = nodes.values.filter { parents[it.id] == parent.id }
        @Synchronized override fun directory(parent: StorageNode, name: String) = add(parent, name, true)
        @Synchronized override fun create(parent: StorageNode, name: String) = add(parent, name, false)
        private fun add(parent: StorageNode, name: String, directory: Boolean): StorageNode {
            check(children(parent).none { it.name == name })
            return StorageNode(UUID.randomUUID().toString(), name, directory).also { nodes[it.id] = it; parents[it.id] = parent.id }
        }
        @Synchronized override fun read(node: StorageNode): InputStream {
            if (delayed.remove(node.id)) return ByteArrayInputStream(byteArrayOf())
            return ByteArrayInputStream(bytes[node.id] ?: throw IOException("Missing document"))
        }
        override fun write(node: StorageNode): OutputStream {
            if (failWrite) throw IOException("ENOSPC synthetic failure")
            if (node.name.endsWith(".part")) onStageWrite?.invoke()
            return object : ByteArrayOutputStream() {
                override fun close() { synchronized(this@MemoryAccess) {
                    val content = toByteArray()
                    bytes[node.id] = if (truncateFinal && node.name.endsWith(".dcm")) content.copyOf(content.size / 2) else content
                    if (delayedReads) delayed.add(node.id)
                } }
            }
        }
        @Synchronized override fun deleteFile(node: StorageNode): Boolean {
            if (node.directory) { deletedDirectories++; error("Directory deletion forbidden") }
            bytes.remove(node.id); parents.remove(node.id)
            return nodes.remove(node.id) != null
        }
        @Synchronized fun files() = nodes.values.filter { !it.directory }
    }
}
