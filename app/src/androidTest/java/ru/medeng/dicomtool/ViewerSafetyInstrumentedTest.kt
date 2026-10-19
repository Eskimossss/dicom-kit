package ru.medeng.dicomtool

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
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
        // A clock substring can start with 0 and accidentally generate an invalid UI component.
        val suffix = System.nanoTime().toString().takeLast(12).toLong().toString()
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
    @Test fun multiframeAndInstanceNavigationRemainSeparateInUnifiedWorkspace() {
        val data = bytes(dataset("SYNTHETIC^NAV"))
        val decoded = (DicomPixelDataDecoder.decodeFrameFromSource("synthetic-nav", streamOpener = { ByteArrayInputStream(data) }) as DicomPixelDecodeResult.Success).frame
        val bitmap = DicomPixelDataDecoder.renderFrameToBitmap(decoded, 256.0, 128.0, false)
        val frame = mutableIntStateOf(1); val instance = mutableIntStateOf(1); val narrow = mutableStateOf(false)
        compose.runOnUiThread { compose.activity.setContent {
            Box(Modifier.width(if (narrow.value) 360.dp else 1280.dp).fillMaxHeight()) {
            ru.medeng.dicomtool.ui.components.DicomViewerWorkspace(
                sourceKey = "synthetic-nav", metadata = emptyList(), tags = emptyList(), query = "",
                renderState = DicomViewerRenderState.Ready(bitmap, decoded.descriptor, frame.intValue, 2, instance.intValue, 3, "synthetic-nav"),
                zoom = 1f, pan = androidx.compose.ui.geometry.Offset.Zero, inverted = false,
                ww = 256, wl = 128, frame = frame.intValue, frames = 2, instance = instance.intValue, instances = 3, fullScreen = false,
                onQuery = {}, onZoom = {}, onPan = {}, onFit = {}, onResetView = {}, onResetWindow = {}, onInvert = {},
                onAdjustWindow = { _, _ -> }, onWindow = { _, _ -> }, onFrame = { frame.intValue += it },
                onInstance = { instance.intValue += it }, onFullScreen = {})
            }
        } }
        compose.onNodeWithContentDescription("Следующий кадр").performClick()
        compose.runOnIdle { assertEquals(2, frame.intValue); assertEquals(1, instance.intValue) }
        compose.onNodeWithContentDescription("Следующий снимок").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, frame.intValue); assertEquals(2, instance.intValue) }
        compose.onNodeWithText("Авто Мин/Макс", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Enhance MAM", substring = true).assertDoesNotExist()
        compose.onNodeWithText("DICOM-теги").performClick()
        compose.onNodeWithText("Просмотр и WW/WL").performClick()
        // A narrow phone still exposes both levels inside the viewport.
        compose.runOnIdle { narrow.value = true }
        compose.onNodeWithContentDescription("Предыдущий кадр").assertExists()
        compose.onNodeWithContentDescription("Предыдущий снимок").performScrollTo().assertExists()
    }
    @Test fun liveSliderRendersWhileFingerIsDownAndReusesDecodedLargeFrame() {
        val ds = dataset("SYNTHETIC^LIVE").apply {
            setInt(Tag.Rows, VR.US, 3000); setInt(Tag.Columns, VR.US, 4000)
            setInt(Tag.BitsAllocated, VR.US, 16); setInt(Tag.BitsStored, VR.US, 16); setInt(Tag.HighBit, VR.US, 15)
            setBytes(Tag.PixelData, VR.OW, ByteArray(3000 * 4000 * 2) { index -> if (index % 2 == 0) 0x10 else 0x27 })
        }
        val (uri, root) = source(ds); val model = vm()
        fun hash() = context.contentResolver.openInputStream(uri)!!.use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest()
        }
        val original = hash()
        compose.onNodeWithContentDescription("Теги и просмотр", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        val before = (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap
        val opens = context.contentResolver.call(uri, "stage6-read-count", root, null)!!.getInt("count")
        val slider = compose.onNodeWithTag("viewer-ww-slider").performScrollTo()
        slider.performTouchInput { down(center.copy(x = width * 0.2f)) }
        var changedDuringGesture = false
        for (i in 1..12) {
            slider.performTouchInput { moveTo(center.copy(x = width * (0.2f + i * 0.05f))) }
            Thread.sleep(40)
            if ((model.viewerRenderState.value as? DicomViewerRenderState.Ready)?.bitmap !== before) changedDuringGesture = true
        }
        assertTrue("No intermediate bitmap published while slider is held", changedDuringGesture)
        slider.performTouchInput { up() }
        val ww = model.viewerWindowWidth.value; val wl = model.viewerWindowCenter.value
        val expected = DicomPixelDataDecoder.applyWindowFormula(10000.0, wl.toDouble(), ww.toDouble())
        await { ready(model, uri) && (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(0, 0) and 255 == expected }
        assertEquals(opens, context.contentResolver.call(uri, "stage6-read-count", root, null)!!.getInt("count"))
        assertArrayEquals(original, hash())
        android.util.Log.i("STAGE6", "large_ww_live=true; decoded_frame_reused=true; size=4000x3000")
    }
    @Test fun resetWindowPreservesInversionZoomPanAndCurrentFrame() {
        val ds = dataset("SYNTHETIC^RESET").apply { setInt(Tag.NumberOfFrames, VR.IS, 2)
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(1,2,3,4,5,6,7,8)) }
        val uri = source(ds).first; val model = vm()
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        compose.runOnIdle { model.stepViewerFrame(1) }
        await { ready(model, uri) && (model.viewerRenderState.value as DicomViewerRenderState.Ready).currentFrame == 2 }
        compose.runOnIdle { model.adjustViewerZoom(2f); model.updateViewerPan(androidx.compose.ui.geometry.Offset(3f,4f)); model.toggleViewerInvert(); model.setWindowWidthLevel(100, 50) }
        val pan = model.viewerPan.value
        compose.runOnIdle { model.resetWindowToDicomDefault() }
        await { ready(model, uri) && model.viewerWindowWidth.value == 256 }
        assertTrue(model.viewerInverted.value); assertEquals(2f, model.viewerZoom.value, 0f)
        assertEquals(pan, model.viewerPan.value); assertEquals(2, model.viewerCurrentFrame.value)
    }
    @Test fun fullScreenWindowButtonsChangeBrightnessAndContrastWithoutEditingSource() {
        val uri = source(dataset("SYNTHETIC^WINDOW").apply {
            setInt(Tag.BitsAllocated, VR.US, 16); setInt(Tag.BitsStored, VR.US, 16); setInt(Tag.HighBit, VR.US, 15)
            setBytes(Tag.PixelData, VR.OW, java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putShort(0).putShort(10000).putShort(20000).putShort(30000).array())
        }).first
        val original = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val model = vm()
        compose.onNodeWithText("Теги и просмотр").performClick()
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        compose.onNodeWithText("Просмотр и WW/WL").performClick()
        compose.runOnIdle { model.setWindowWidthLevel(20000, 10000); model.setViewerFullScreen(true) }
        await { ready(model, uri) && model.viewerWindowWidth.value == 20000 }
        val before = (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(2, 0)
        compose.onNodeWithText("WL+").performScrollTo().performClick()
        val expected = DicomPixelDataDecoder.applyWindowFormula(20000.0, 11000.0, 20000.0)
        try {
            await { ready(model, uri) && model.viewerWindowCenter.value == 11000 &&
                (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(2, 0) and 255 == expected }
        } catch (failure: AssertionError) {
            val state = model.viewerRenderState.value
            val pixels = (state as? DicomViewerRenderState.Ready)?.bitmap?.let { bmp -> (0 until 4).map { bmp.getPixel(it, 0) and 255 } }
            throw AssertionError("WW=${model.viewerWindowWidth.value}; WL=${model.viewerWindowCenter.value}; invert=${model.viewerInverted.value}; pixels=$pixels; expected=$expected; state=${state.javaClass.simpleName}", failure)
        }
        assertNotEquals(before, (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(2, 0))
        compose.onNodeWithText("WW-").performScrollTo().performClick()
        await { model.viewerWindowWidth.value == 19000 }
        compose.onNodeWithText("WL-").performScrollTo().performClick()
        await { ready(model, uri) && model.viewerWindowCenter.value == 10050 }
        compose.waitForIdle()
        // Dispatch through the Activity's Back handler; the navigation test separately injects real system Back keys.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        await { !model.isViewerFullScreen.value }
        assertFalse(compose.activity.isFinishing)
        assertArrayEquals(original, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }
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
        await { !model.isMigrating.value } // Storage changes now finish on the archive worker.
        val temp = File(context.cacheDir, "stage54-${UUID.randomUUID()}.dcm").apply { writeBytes(bytes(ds)) }
        val saved = repo.saveIncomingInstance(temp, ds.getString(Tag.StudyInstanceUID), ds.getString(Tag.SeriesInstanceUID), ds.getString(Tag.SOPInstanceUID))
        assertTrue(saved.toString(), saved is ru.medeng.dicomtool.data.storage.DicomSaveResult.Success)
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
    @Test fun receivedSafObjectUsesResolverForPixelsAndLeavesOriginalUntouched() {
        val model = vm()
        val ds = dataset("SYNTHETIC^RECEIVED^SAF", 210)
        val (seed, _) = source(dataset("SYNTHETIC^SEED"))
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(seed.authority,
            android.provider.DocumentsContract.getTreeDocumentId(seed))
        await { !model.isMigrating.value }
        compose.runOnIdle { model.setCustomSafStorage(tree) }
        await { !model.isMigrating.value }
        assertEquals(ru.medeng.dicomtool.data.storage.StorageType.SAF, model.storageLocationInfo.value.type)
        val original = bytes(ds)
        val incoming = File(context.cacheDir, "stage56-saf-${UUID.randomUUID()}").apply { writeBytes(original) }
        val repo = DicomRuntimeRegistry.getStudyRepository(context)
        try {
            val result = repo.saveIncomingInstance(incoming, ds.getString(Tag.StudyInstanceUID),
                ds.getString(Tag.SeriesInstanceUID), ds.getString(Tag.SOPInstanceUID))
            assertTrue(result.toString(), result is ru.medeng.dicomtool.data.storage.DicomSaveResult.Success)
            val path = (result as ru.medeng.dicomtool.data.storage.DicomSaveResult.Success).destinationDescription
            assertTrue(path.startsWith("content://"))
            compose.runOnIdle { model.selectStudy(ds.getString(Tag.StudyInstanceUID)) }
            await { model.viewerRenderState.value.let { it is DicomViewerRenderState.Ready && it.filePath == path } }
            assertEquals(210, (model.viewerRenderState.value as DicomViewerRenderState.Ready).bitmap.getPixel(0, 0) and 255)
            assertArrayEquals(original, context.contentResolver.openInputStream(Uri.parse(path))!!.use { it.readBytes() })
        } finally {
            compose.runOnIdle { model.resetToInternalStorage() }
            await { !model.isMigrating.value }
        }
    }
    @Test fun externalFullscreenUsesSharedViewportAndReturnsToSameObject() {
        val model = vm(); val uri = source(dataset("SYNTHETIC^FULLSCREEN")).first
        compose.runOnIdle { model.loadExternalDicomFile(uri) }; await { ready(model, uri) }
        compose.onNodeWithContentDescription("Теги и просмотр", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Просмотр и WW/WL", useUnmergedTree = true).performClick()
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
        compose.onNodeWithText("DICOM-теги").performClick()
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
        compose.runOnIdle { model.resetWindowToDicomDefault() }; await { pixel() == 127 }; assertTrue(model.viewerInverted.value)
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
