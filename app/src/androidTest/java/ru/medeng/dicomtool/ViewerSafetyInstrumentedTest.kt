package ru.medeng.dicomtool

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry
import ru.medeng.dicomtool.dicom.pixel.*
import ru.medeng.dicomtool.domain.model.ViewerSource
import ru.medeng.dicomtool.storagefixtures.StorageGrantReceiverActivity
import ru.medeng.dicomtool.ui.viewmodel.*
import java.io.*
import java.util.UUID
import java.util.concurrent.*

class ViewerSafetyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun vm(): DicomToolViewModel {
        lateinit var model: DicomToolViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity).get(DicomToolViewModel::class.java) }
        return model
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("Timed out waiting for synthetic Viewer result", condition())
    }
    private fun dataset(name: String, value: Int = 150, columns: Int = 4, photo: String = "MONOCHROME2") = Attributes().apply {
        setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
        val suffix = System.nanoTime().toString().takeLast(12)
        setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.999.54.$suffix")
        setString(Tag.SeriesInstanceUID, VR.UI, "1.2.840.999.541.$suffix")
        setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.999.542.$suffix")
        setString(Tag.PatientName, VR.PN, name)
        setInt(Tag.Rows, VR.US, 1); setInt(Tag.Columns, VR.US, columns)
        setInt(Tag.SamplesPerPixel, VR.US, if (photo == "RGB") 3 else 1)
        setString(Tag.PhotometricInterpretation, VR.CS, photo); setInt(Tag.PlanarConfiguration, VR.US, 0)
        setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8); setInt(Tag.HighBit, VR.US, 7)
        setInt(Tag.PixelRepresentation, VR.US, 0)
        setDouble(Tag.WindowCenter, VR.DS, 128.0); setDouble(Tag.WindowWidth, VR.DS, 256.0)
        setBytes(Tag.PixelData, VR.OB, ByteArray(columns * if (photo == "RGB") 3 else 1) { (value + it).toByte() })
    }
    private fun bytes(ds: Attributes) = ByteArrayOutputStream().also { out -> DicomOutputStream(out, UID.ExplicitVRLittleEndian).use {
        it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds)
    } }.toByteArray()
    private fun source(ds: Attributes, delayed: Boolean = false): Pair<Uri, String> {
        check(context.packageName.endsWith(".storagesafetytest"))
        val rootId = (if (delayed) "stage54delay" else "normal") + "_${UUID.randomUUID()}"
        StorageGrantReceiverActivity.ready = CountDownLatch(1); StorageGrantReceiverActivity.granted = null; StorageGrantReceiverActivity.error = null
        instrumentation.startActivitySync(Intent(context, StorageGrantReceiverActivity::class.java).putExtra("root", rootId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(StorageGrantReceiverActivity.ready.await(15, TimeUnit.SECONDS)); assertNull(StorageGrantReceiverActivity.error)
        val root = DocumentFile.fromTreeUri(context, StorageGrantReceiverActivity.granted!!)!!; assertTrue(root.exists())
        val uri = root.createFile("application/dicom", "synthetic-viewer.dcm")!!.uri
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes(ds)) }
        return uri to rootId
    }
    private fun ready(model: DicomToolViewModel, uri: Uri) = model.viewerRenderState.value.let { it is DicomViewerRenderState.Ready && it.filePath == uri.toString() }
    @Test fun lateProviderAResultCannotReplaceBAndOldMetadataClearsImmediately() {
        val model = vm(); val (a, root) = source(dataset("SYNTHETIC^A"), true)
        val b = source(dataset("SYNTHETIC^B", 50)).first
        fun started() = context.contentResolver.call(a, "stage54-status", root, null)?.getBoolean("started") == true
        fun release() { context.contentResolver.call(a, "stage54-release", root, null) }
        try {
            compose.runOnIdle { model.loadExternalDicomFile(a) }
            await { started() }
            compose.runOnIdle { model.loadExternalDicomFile(b) }
            assertNull(model.externalFileSummary.value); assertTrue(model.externalFileTags.value.isEmpty())
            assertFalse(model.viewerRenderState.value is DicomViewerRenderState.Ready)
            release(); await { ready(model, b) }
            assertEquals("SYNTHETIC^B", model.externalFileSummary.value!!.patientName)
            assertEquals(50, (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(0, 0) and 255)
        } finally { release() }
    }
    @Test fun receivedAndExternalSourcesStayDistinctAcrossFullscreenAndRecreation() {
        val model = vm(); val external = source(dataset("SYNTHETIC^EXTERNAL", 50)).first
        compose.runOnIdle { model.loadExternalDicomFile(external) }; await { ready(model, external) }
        val ds = dataset("SYNTHETIC^RECEIVED", 200)
        val repo = DicomRuntimeRegistry.getStudyRepository(context)
        compose.runOnIdle { model.resetToInternalStorage() }
        val temp = File(context.cacheDir, "stage54-${UUID.randomUUID()}.dcm").apply { writeBytes(bytes(ds)) }
        repo.saveIncomingInstance(temp, ds.getString(Tag.StudyInstanceUID), ds.getString(Tag.SeriesInstanceUID), ds.getString(Tag.SOPInstanceUID))
        repo.refreshFromDisk(); await { model.studies.value.any { it.studyInstanceUid == ds.getString(Tag.StudyInstanceUID) } }
        compose.runOnIdle { model.selectStudy(ds.getString(Tag.StudyInstanceUID)) }
        await { model.viewerSource.value is ViewerSource.Received && model.viewerRenderState.value.let { it is DicomViewerRenderState.Ready && it.filePath != external.toString() } }
        val receivedPath = (model.viewerRenderState.value as DicomViewerRenderState.Ready).filePath
        assertEquals(200, (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(0, 0) and 255)
        compose.runOnIdle { model.setViewerFullScreen(true); model.adjustViewerZoom(2f) }
        compose.activityRule.scenario.recreate()
        val recreated = vm(); assertSame(model, recreated); assertTrue(recreated.isViewerFullScreen.value)
        assertEquals(2f, recreated.viewerZoom.value, 0f)
        assertEquals(receivedPath, (recreated.viewerRenderState.value as DicomViewerRenderState.Ready).filePath)
        compose.runOnIdle { recreated.setViewerFullScreen(false); recreated.ensureExternalViewer() }
        await { ready(recreated, external) }
        assertEquals("SYNTHETIC^EXTERNAL", recreated.externalFileSummary.value!!.patientName)
    }
    @Test fun externalFullscreenUsesSharedViewportAndReturnsToSameObject() {
        val model = vm(); val uri = source(dataset("SYNTHETIC^FULLSCREEN")).first
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        compose.onNodeWithContentDescription("Теги и просмотр", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Изображение", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("DICOM изображение").assertExists()
        compose.onNodeWithContentDescription("На весь экран").performClick()
        assertTrue(model.isViewerFullScreen.value); assertTrue(ready(model, uri))
        val activity = compose.activity
        val originalRequest = activity.requestedOrientation
        val orientation = activity.resources.configuration.orientation
        val target = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        try {
            compose.runOnIdle { activity.requestedOrientation = target }
            await { activity.resources.configuration.orientation != orientation }
            assertTrue(model.isViewerFullScreen.value); assertTrue(ready(model, uri))
            compose.onNodeWithContentDescription("DICOM изображение").assertExists()
        } finally {
            instrumentation.runOnMainSync { activity.requestedOrientation = originalRequest }
            await { activity.resources.configuration.orientation == orientation }
            instrumentation.waitForIdleSync()
        }
        compose.runOnIdle { model.setViewerFullScreen(false) }
        assertTrue(ready(model, uri)); compose.onNodeWithContentDescription("DICOM изображение").assertExists()
    }
    @Test fun largeRgbBitmapUsesSameColorAndInversionAtBothTextureSizes() {
        val ds = dataset("SYNTHETIC^RGB", 1, 5001, "RGB"); val data = bytes(ds)
        val frame = (DicomPixelDataDecoder.decodeFrameFromSource("rgb", streamOpener = { ByteArrayInputStream(data) }) as DicomPixelDecodeResult.Success).frame
        val a = DicomPixelDataDecoder.renderFrameToBitmap(frame, 1.0, -200.0, false)
        val b = DicomPixelDataDecoder.renderFrameToBitmap(frame, 255.0, 128.0, true)
        try { assertTrue(a.width <= 4096); assertEquals(a.width, b.width)
            for (x in 0 until a.width) assertEquals(a.getPixel(x, 0) xor 0x00FFFFFF, b.getPixel(x, 0))
        } finally { a.recycle(); b.recycle() }
    }
    @Test fun enhancedFrameNavigationResetsToFrameSpecificWindowAndPreservesOriginal() {
        val ds = dataset("SYNTHETIC^ENHANCED").apply {
            setString(Tag.SOPClassUID, VR.UI, UID.EnhancedCTImageStorage); setInt(Tag.NumberOfFrames, VR.IS, 2)
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(10,11,12,13,10,11,12,13))
            newSequence(Tag.PerFrameFunctionalGroupsSequence, 2).apply {
                repeat(2) { n -> add(Attributes().apply {
                    newSequence(Tag.PixelValueTransformationSequence, 1).add(Attributes().apply {
                        setDouble(Tag.RescaleSlope, VR.DS, if (n == 0) 1.0 else 2.0); setDouble(Tag.RescaleIntercept, VR.DS, if (n == 0) 0.0 else 100.0) })
                    newSequence(Tag.FrameVOILUTSequence, 1).add(Attributes().apply {
                        setDouble(Tag.WindowCenter, VR.DS, if (n == 0) 50.0 else 120.0); setDouble(Tag.WindowWidth, VR.DS, if (n == 0) 100.0 else 40.0) })
                }) }
            }
        }
        val uri = source(ds).first; val model = vm()
        val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        assertEquals(100, model.viewerWindowWidth.value)
        compose.runOnIdle { model.stepViewerFrame(1) }
        await { model.viewerRenderState.value.let { it is DicomViewerRenderState.Ready && it.currentFrame == 2 } }
        val ready = model.viewerRenderState.value as DicomViewerRenderState.Ready
        assertEquals(2.0, ready.descriptor.rescaleSlope, 0.0); assertEquals(40, model.viewerWindowWidth.value)
        assertEquals(131, ready.bitmap.getPixel(0, 0) and 255)
        compose.runOnIdle { repeat(25) { model.setWindowWidthLevel(100 + it, 120) } }
        await { ready(model, uri) && model.viewerWindowWidth.value == 124 &&
            (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(0, 0) and 255 == DicomPixelDataDecoder.applyWindowFormula(120.0, 120.0, 124.0) }
        assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }
    @Test fun tagSearchAndCopyKeepExactSyntheticPrivateValue() {
        val ds = dataset("SYNTHETIC^COPY").apply { setString(0x00110010, VR.LO, "SYNTHETIC"); setString(0x00111001, VR.LO, "PRIVATE SYNTHETIC VALUE") }
        val model = vm(); val uri = source(ds).first
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        compose.onNodeWithContentDescription("Теги и просмотр", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.updateExternalTagSearchQuery("PRIVATE SYNTHETIC") }
        compose.onNodeWithText("PRIVATE SYNTHETIC VALUE", substring = true).performClick()
        compose.onNodeWithText("Значение", useUnmergedTree = true).performClick()
        var copied = ""
        compose.runOnIdle { copied = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
        assertEquals("PRIVATE SYNTHETIC VALUE", copied)
    }
    @Test fun fractionalDicomWindowSurvivesInvertAndResetWithoutIntegerRounding() {
        val ds = dataset("SYNTHETIC^FRACTIONAL", 10).apply {
            setDouble(Tag.WindowCenter, VR.DS, 10.5); setDouble(Tag.WindowWidth, VR.DS, 2.5) }
        val uri = source(ds).first; val model = vm()
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        fun pixel() = (model.viewerRenderState.value as? DicomViewerRenderState.Ready)?.bitmap?.getPixel(0,0)?.and(255)
        assertEquals(128, pixel())
        compose.runOnIdle { model.toggleViewerInvert() }; await { pixel() == 127 }
        compose.runOnIdle { model.resetWindowToDicomDefault() }; await { pixel() == 128 }
    }
    @Test fun repeatedBitmapRenderingKeepsBudgetAndCancellationReleasesUnpublishedBitmap() {
        val ds = dataset("SYNTHETIC^MEMORY", 1, 5001, "RGB").apply {
            setInt(Tag.Rows, VR.US, 512); setBytes(Tag.PixelData, VR.OB, ByteArray(5001 * 512 * 3) { it.toByte() })
        }; val data = bytes(ds)
        val cache = SingleFrameDecodeCache()
        repeat(30) { n ->
            cache.clear() // Force real repeated opening of the 7.7 MB synthetic RGB object.
            val f = (DicomPixelDataDecoder.decodeFrameFromSource("repeat", streamOpener = { ByteArrayInputStream(data) }, cache = cache) as DicomPixelDecodeResult.Success).frame
            val bmp = DicomPixelDataDecoder.renderFrameToBitmap(f, 200.0 + n, 128.0, false)
            assertTrue(bmp.allocationByteCount <= DicomPixelDataDecoder.MAX_BITMAP_PIXELS * 4); bmp.recycle()
        }
        var checks = 0
        try { DicomPixelDataDecoder.renderFrameToBitmap(cache.get("repeat", 0)!!, 200.0, 128.0, false,
            checkCancelled = { if (++checks == 2) throw CancellationException("synthetic") }); fail()
        } catch (_: CancellationException) { }
        cache.clear(); assertNull(cache.get("repeat", 0))
    }
}
