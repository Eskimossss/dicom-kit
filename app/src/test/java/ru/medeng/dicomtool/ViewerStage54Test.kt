package ru.medeng.dicomtool

import org.dcm4che3.data.*
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.dicom.pixel.*
import ru.medeng.dicomtool.dicom.tags.*
import ru.medeng.dicomtool.domain.model.*
import java.io.*
import java.nio.charset.Charset
import java.util.concurrent.*

class ViewerStage54Test {
    private fun attrs(frames: Int = 1, photo: String = "MONOCHROME2", columns: Int = 4) = Attributes().apply {
        setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
        setString(Tag.SOPInstanceUID, VR.UI, "1.2.840.999.5401")
        setString(Tag.PatientName, VR.PN, "SYNTHETIC^VIEWER")
        setInt(Tag.Rows, VR.US, 1); setInt(Tag.Columns, VR.US, columns)
        setInt(Tag.NumberOfFrames, VR.IS, frames)
        setInt(Tag.SamplesPerPixel, VR.US, if (photo == "RGB") 3 else 1)
        setString(Tag.PhotometricInterpretation, VR.CS, photo)
        setInt(Tag.BitsAllocated, VR.US, 8); setInt(Tag.BitsStored, VR.US, 8)
        setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 0)
        setInt(Tag.PlanarConfiguration, VR.US, 0)
        setDouble(Tag.WindowCenter, VR.DS, 128.0); setDouble(Tag.WindowWidth, VR.DS, 256.0)
        setBytes(Tag.PixelData, VR.OB, ByteArray(columns * frames * if (photo == "RGB") 3 else 1) { (it * 60).toByte() })
    }
    private fun bytes(ds: Attributes): ByteArray = ByteArrayOutputStream().also { out ->
        DicomOutputStream(out, UID.ExplicitVRLittleEndian).use { it.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds) }
    }.toByteArray()
    private fun decode(ds: Attributes, frame: Int = 0): DicomPixelDecodeResult = bytes(ds).let { data ->
        DicomPixelDataDecoder.decodeFrameFromSource("synthetic", streamOpener = { ByteArrayInputStream(data) }, frameIndex = frame)
    }
    private fun frame(ds: Attributes, index: Int = 0) = (decode(ds, index) as DicomPixelDecodeResult.Success).frame
    private fun macro(parent: Attributes, tag: Int, vararg values: Pair<Int, Double>) {
        parent.newSequence(tag, 1).add(Attributes().apply { values.forEach { (t, v) -> setDouble(t, VR.DS, v) } })
    }
    private fun enhanced() = attrs(2).apply {
        setString(Tag.SOPClassUID, VR.UI, UID.EnhancedCTImageStorage)
        newSequence(Tag.PerFrameFunctionalGroupsSequence, 2).apply {
            add(Attributes().apply {
                macro(this, Tag.PixelValueTransformationSequence, Tag.RescaleSlope to 2.0, Tag.RescaleIntercept to -100.0)
                macro(this, Tag.FrameVOILUTSequence, Tag.WindowCenter to 20.0, Tag.WindowWidth to 50.0)
            })
            add(Attributes().apply {
                macro(this, Tag.PixelValueTransformationSequence, Tag.RescaleSlope to 3.0, Tag.RescaleIntercept to 7.0)
                macro(this, Tag.FrameVOILUTSequence, Tag.WindowCenter to 100.0, Tag.WindowWidth to 200.0)
            })
        }
    }
    @Test fun extensionlessSyntheticFileKeepsCyrillicAndPixelsWithoutChangingSource() {
        val ds = attrs().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.PatientName, VR.PN, "СИНТЕТИЧЕСКИЙ^БЕЗРАСШИРЕНИЯ")
        }
        val file = java.nio.file.Files.createTempDirectory("stage56-extensionless").resolve("synthetic").toFile()
        try {
            val original = bytes(ds); file.writeBytes(original)
            val metadata = DicomFileReader.readFromStream(file.inputStream(), fileName = file.name, fileSizeBytes = file.length())
            assertEquals("СИНТЕТИЧЕСКИЙ^БЕЗРАСШИРЕНИЯ", metadata.summary.patientName)
            assertTrue(DicomPixelDataDecoder.decodeFrameFromSource(file.path, streamOpener = { file.inputStream() }) is DicomPixelDecodeResult.Success)
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete(); file.parentFile?.delete() }
    }
    @Test fun wrongDeclaredTransferSyntaxDoesNotBecomeVerifiedPixelImageOrMutateBytes() {
        val ds = attrs()
        val malformed = ByteArrayOutputStream().also { out ->
            DicomOutputStream(out, UID.ExplicitVRLittleEndian).use {
                it.writeFileMetaInformation(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian))
                it.switchTransferSyntax(UID.ImplicitVRLittleEndian)
                ds.writeTo(it)
            }
        }.toByteArray()
        val before = malformed.copyOf()
        val result = DicomPixelDataDecoder.decodeFrameFromSource("synthetic-wrong-ts", streamOpener = { ByteArrayInputStream(malformed) })
        assertFalse(result.toString(), result is DicomPixelDecodeResult.Success)
        assertArrayEquals(before, malformed)
    }
    @Test fun enhancedTransformsAndVoiAreResolvedForEachFrame() {
        val ds = enhanced(); val a = frame(ds); val b = frame(ds, 1)
        assertEquals(2.0, a.descriptor.rescaleSlope, 0.0); assertEquals(-100.0, a.descriptor.rescaleIntercept, 0.0)
        assertEquals(3.0, b.descriptor.rescaleSlope, 0.0); assertEquals(7.0, b.descriptor.rescaleIntercept, 0.0)
        assertEquals(50.0, a.descriptor.defaultWindowWidth, 0.0); assertEquals(100.0, b.descriptor.defaultWindowCenter, 0.0)
        assertEquals(1, b.frameIndex)
        val rendered = DicomPixelDataDecoder.renderFrameToArgbPixels(a, 50.0, 20.0, false)
        assertEquals(0, rendered[0] and 255); assertEquals(130, rendered[1] and 255)
    }
    @Test fun sharedModalityAndPerFrameVoiAreIndependentMacros() {
        val ds = enhanced()
        ds.getSequence(Tag.PerFrameFunctionalGroupsSequence).forEach { it.remove(Tag.PixelValueTransformationSequence) }
        ds.newSequence(Tag.SharedFunctionalGroupsSequence, 1).add(Attributes().apply {
            macro(this, Tag.PixelValueTransformationSequence, Tag.RescaleSlope to -2.0, Tag.RescaleIntercept to 100.0)
        })
        assertEquals(-2.0, frame(ds, 1).descriptor.rescaleSlope, 0.0)
        assertEquals(200.0, frame(ds, 1).descriptor.defaultWindowWidth, 0.0)
    }
    @Test fun contradictorySharedAndPerFrameMacroIsRejected() {
        val ds = enhanced(); ds.newSequence(Tag.SharedFunctionalGroupsSequence, 1).add(Attributes().apply {
            macro(this, Tag.PixelValueTransformationSequence, Tag.RescaleSlope to 1.0, Tag.RescaleIntercept to 0.0)
        })
        assertTrue(decode(ds) is DicomPixelDecodeResult.DecodeError)
    }
    @Test fun incompleteFunctionalGroupsDoNotSilentlyUseTopLevelWindow() {
        val ds = enhanced(); ds.getSequence(Tag.PerFrameFunctionalGroupsSequence)[1].getSequence(Tag.FrameVOILUTSequence)[0].remove(Tag.WindowWidth)
        assertTrue(decode(ds, 1) is DicomPixelDecodeResult.DecodeError)
    }
    @Test fun absentEnhancedMacroDoesNotInheritAnUnrelatedTopLevelTransform() {
        val ds = enhanced(); ds.getSequence(Tag.PerFrameFunctionalGroupsSequence)[1].remove(Tag.FrameVOILUTSequence)
        val d = frame(ds, 1).descriptor
        assertEquals(588.0, d.defaultWindowWidth, 0.0); assertEquals(433.0, d.defaultWindowCenter, 0.0)
    }
    @Test fun enhancedWithoutGroupsAndInvalidLutAreExplicitLimitations() {
        val ds = attrs().apply { setString(Tag.SOPClassUID, VR.UI, UID.EnhancedCTImageStorage) }
        assertTrue(decode(ds) is DicomPixelDecodeResult.DecodeError)
        val lut = attrs().apply { newSequence(Tag.VOILUTSequence, 1).add(Attributes().apply { setInt(Tag.LUTDescriptor, VR.US, 4, 0, 16) }) }
        assertTrue(decode(lut) is DicomPixelDecodeResult.DecodeError)
    }
    @Test fun incorrectFunctionalGroupCountIsRejected() {
        val ds = enhanced(); ds.getSequence(Tag.PerFrameFunctionalGroupsSequence).removeAt(1)
        assertTrue(decode(ds) is DicomPixelDecodeResult.DecodeError)
    }
    @Test fun unsupportedRealWorldMappingIsExplicit() {
        val ds = enhanced(); ds.getSequence(Tag.PerFrameFunctionalGroupsSequence)[0].newSequence(Tag.RealWorldValueMappingSequence, 1).add(Attributes())
        assertTrue((decode(ds) as DicomPixelDecodeResult.DecodeError).reasonRu.contains("Real World"))
    }
    @Test fun invalidPixelMetadataAndOutOfRangeFrameAreRejected() {
        assertTrue(decode(attrs(), 1) is DicomPixelDecodeResult.DecodeError)
        for ((tag, value) in listOf(Tag.NumberOfFrames to 0, Tag.BitsStored to 9, Tag.HighBit to 8, Tag.PixelRepresentation to 2)) {
            val ds = attrs(); ds.setInt(tag, if (tag == Tag.NumberOfFrames) VR.IS else VR.US, value)
            assertTrue("tag $tag", decode(ds) is DicomPixelDecodeResult.DecodeError)
        }
    }
    @Test fun missingMandatoryPixelFieldsCannotBeGuessedAsUnsignedMonochrome() {
        for (tag in listOf(Tag.SamplesPerPixel, Tag.PhotometricInterpretation, Tag.BitsAllocated, Tag.BitsStored, Tag.HighBit, Tag.PixelRepresentation)) {
            val ds = attrs().apply { remove(tag) }
            assertTrue((decode(ds) as DicomPixelDecodeResult.DecodeError).reasonRu.contains("Image Pixel"))
        }
    }
    @Test fun missingRgbPlanarConfigurationCannotBeGuessedAsChunky() {
        val ds = attrs(photo = "RGB").apply { remove(Tag.PlanarConfiguration) }
        assertTrue((decode(ds) as DicomPixelDecodeResult.DecodeError).reasonRu.contains("Planar Configuration"))
    }
    @Test fun signedShiftedHighBitAndMonochromePolarityRemainCorrect() {
        val ds = attrs().apply { setInt(Tag.BitsStored, VR.US, 6); setInt(Tag.HighBit, VR.US, 7); setInt(Tag.PixelRepresentation, VR.US, 1)
            setBytes(Tag.PixelData, VR.OB, byteArrayOf(0x80.toByte(), 0xFC.toByte(), 0, 0x7C)) }
        val f = frame(ds); assertEquals(-32.0, f.descriptor.minModalityValue, 0.0); assertEquals(31.0, f.descriptor.maxModalityValue, 0.0)
        val mono2 = DicomPixelDataDecoder.renderFrameToArgbPixels(f, 64.0, 0.0, false)
        ds.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME1")
        val mono1 = DicomPixelDataDecoder.renderFrameToArgbPixels(frame(ds), 64.0, 0.0, false)
        assertEquals(255, (mono1[0] and 255) + (mono2[0] and 255))
        assertArrayEquals(mono2, DicomPixelDataDecoder.renderFrameToArgbPixels(frame(ds), 64.0, 0.0, true))
    }
    @Test fun largeRgbKeepsColorAndIgnoresMonochromeWindow() {
        val ds = attrs(photo = "RGB", columns = 5001); val f = frame(ds)
        val a = DicomPixelDataDecoder.renderFrameToArgbPixels(f, 1.0, -200.0, false)
        val b = DicomPixelDataDecoder.renderFrameToArgbPixels(f, 5000.0, 1000.0, false)
        assertArrayEquals(a, b)
        val inv = DicomPixelDataDecoder.renderFrameToArgbPixels(f, 1.0, 0.0, true)
        a.indices.forEach { assertEquals(a[it] xor 0x00FFFFFF, inv[it]) }
        assertTrue(f.descriptor.windowPresets.isEmpty())
    }
    @Test fun planarAndChunkyRgbHaveSameColorsEvenWithShortReads() {
        val chunky = attrs(photo = "RGB", columns = 2).apply { setBytes(Tag.PixelData, VR.OB, byteArrayOf(1,2,3,4,5,6)) }
        val planar = attrs(photo = "RGB", columns = 2).apply { setInt(Tag.PlanarConfiguration, VR.US, 1); setBytes(Tag.PixelData, VR.OB, byteArrayOf(1,4,2,5,3,6)) }
        val data = bytes(chunky)
        val decoded = DicomPixelDataDecoder.decodeFrameFromSource("short", streamOpener = {
            object : ByteArrayInputStream(data) { override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1)) }
        }) as DicomPixelDecodeResult.Success
        assertArrayEquals(decoded.frame.rgb8Buffer, frame(planar).rgb8Buffer)
    }
    @Test fun bitmapSizeIsBoundedAndKeepsAspectRatio() {
        val size = DicomPixelDataDecoder.renderSize(5000, 3000)
        assertTrue(size.width <= 4096); assertTrue(size.width * size.height <= DicomPixelDataDecoder.MAX_BITMAP_PIXELS)
        assertEquals(5.0 / 3, size.width.toDouble()/size.height, 0.002)
    }
    @Test fun hugeFrameIsRejectedBeforePixelBufferAllocation() {
        val ds = attrs().apply { setInt(Tag.Rows, VR.US, 65535); setInt(Tag.Columns, VR.US, 65535) }
        assertTrue((decode(ds) as DicomPixelDecodeResult.DecodeError).reasonRu.contains("предел памяти"))
    }
    @Test fun cancellationDoesNotReturnDecodeErrorOrFillCache() {
        val data = bytes(attrs()); val cache = SingleFrameDecodeCache()
        try {
            DicomPixelDataDecoder.decodeFrameFromSource("cancel", streamOpener = { ByteArrayInputStream(data) }, cache = cache,
                checkCancelled = { throw CancellationException("synthetic") }); fail()
        } catch (_: CancellationException) { }
        assertNull(cache.get("cancel", 0))
    }
    @Test fun invalidatedCacheCannotBeFilledByOldDecode() {
        val cache = SingleFrameDecodeCache(); val ticket = cache.ticket(); val f = frame(attrs())
        cache.clear(); cache.putIfCurrent(f, ticket); assertNull(cache.get(f.filePath, 0))
    }
    @Test fun lateAResultCannotReplaceBAndSourcesAreDistinct() {
        val gate = LatestViewerRequest(); val a = gate.next(); val b = gate.next()
        assertFalse(gate.isCurrent(a)); assertTrue(gate.isCurrent(b))
        assertNotEquals(ViewerSource.External("same"), ViewerSource.Received("same"))
        try { gate.check(a); fail() } catch (_: CancellationException) { }
    }
    @Test fun declaredIso144IsNotOverriddenByByteHeuristic() {
        val text = "ЮЯ^ЮЛИЯ=ТЕСТ" // valid high ISO-8859-5 bytes previously classified as CP1251
        val result = DicomCharsetDecoder.decodeBytesWithDiagnostics(text.toByteArray(Charset.forName("ISO-8859-5")), VR.PN, listOf("ISO_IR 144"))
        assertEquals(text, result.value); assertNull(result.diagnosticWarning)
    }
    @Test fun brokenUtf8AndMissingDeclarationRemainMarkedAsGuesses() {
        val raw = "СИНТЕТИЧЕСКИЙ^ТЕСТ".toByteArray(Charset.forName("windows-1251"))
        for (terms in listOf(emptyList(), listOf("ISO_IR 192"))) assertNotNull(DicomCharsetDecoder.decodeBytesWithDiagnostics(raw, VR.PN, terms).diagnosticWarning)
    }
    @Test fun nestedCharsetInheritancePrivateTagsAndPostPixelTagsAreVisible() {
        val ds = attrs().apply { setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 144")
            newSequence(Tag.RequestAttributesSequence, 1).add(Attributes().apply { setBytes(Tag.PatientName, VR.PN, "ЮЯ^ЮЛИЯ".toByteArray(Charset.forName("ISO-8859-5")))
                setString(0x00110010, VR.LO, "SYNTHETIC"); setBytes(0x00111001, VR.UN, byteArrayOf(1,2,3,4)) })
            setString(0xFFFCFFFC.toInt(), VR.UT, "СИНТЕТИЧЕСКИЙ AFTER_PIXEL") }
        val data = bytes(ds); val before = data.clone(); val result = DicomFileReader.readFromStream(ByteArrayInputStream(data))
        assertTrue(result.tags.any { it.value == "СИНТЕТИЧЕСКИЙ AFTER_PIXEL" })
        val item = result.tags.single { it.tagInt == Tag.RequestAttributesSequence }.sequenceItems.single()
        assertTrue(item.tags.any { it.value == "ЮЯ^ЮЛИЯ" }); assertTrue(item.tags.any { it.isPrivate && it.isBinary })
        assertArrayEquals(before, data)
    }
    @Test fun metadataLimitsAreEnforcedBeforeDeepRecursionOrHugeAllocation() {
        val deep = attrs(); var cur = deep
        repeat(30) { val next = Attributes(); cur.newSequence(Tag.RequestAttributesSequence, 1).add(next); cur = next }
        try { DicomFileReader.readFromStream(ByteArrayInputStream(bytes(deep))); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Ограничение")) }
        val huge = attrs().apply { setBytes(Tag.StudyDescription, VR.LO, ByteArray((BoundedDicomRead.MAX_VALUE_BYTES + 2).toInt()) { 65 }) }
        try { DicomFileReader.readFromStream(ByteArrayInputStream(bytes(huge))); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Ограничение")) }
    }
    @Test fun elementCountAndAggregateMetadataBudgetAreBounded() {
        val many = attrs().apply { repeat(BoundedDicomRead.MAX_ELEMENTS + 1) { setString(0x77770001 + it, VR.LO, "XX") } }
        try { DicomFileReader.readFromStream(ByteArrayInputStream(bytes(many))); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Ограничение")) }
        val big = attrs().apply { val payload = ByteArray(BoundedDicomRead.MAX_VALUE_BYTES.toInt()) { 65 }
            repeat(9) { setBytes(0x77771000 + it, VR.UN, payload) } }
        try { DicomFileReader.readFromStream(ByteArrayInputStream(bytes(big))); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("Метаданные")) }
    }
    @Test fun nestedLocalCharsetOverridesOnlyItsOwnItemAndPnKeepsRepresentations() {
        val ds = attrs().apply { setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 144")
            newSequence(Tag.RequestAttributesSequence, 2).apply {
                add(Attributes().apply { setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192"); setBytes(Tag.PatientName, VR.PN, "ЮЯ^ЮЛИЯ=ТЕСТ".toByteArray(Charsets.UTF_8)) })
                add(Attributes().apply { setBytes(Tag.PatientName, VR.PN, "ЮЯ^СЕСТРА".toByteArray(Charset.forName("ISO-8859-5"))) })
            } }
        val items = DicomFileReader.readFromStream(ByteArrayInputStream(bytes(ds))).tags.single { it.tagInt == Tag.RequestAttributesSequence }.sequenceItems
        assertEquals("ЮЯ^ЮЛИЯ=ТЕСТ", items[0].tags.single { it.tagInt == Tag.PatientName }.value)
        assertEquals("ЮЯ^СЕСТРА", items[1].tags.single { it.tagInt == Tag.PatientName }.value)
    }
    @Test fun recursiveSearchKeepsPrivateValueAndMatchingSequenceContents() {
        val ds = attrs().apply { newSequence(Tag.RequestAttributesSequence, 1).add(Attributes().apply {
            setString(0x00110010, VR.LO, "SYNTHETIC"); setString(0x00111001, VR.LO, "NESTED TEST VALUE") }) }
        val tags = DicomFileReader.readFromStream(ByteArrayInputStream(bytes(ds))).tags
        val found = ru.medeng.dicomtool.ui.screens.tags.filterTagsHierarchy(tags, "NESTED TEST", false)
        assertEquals("NESTED TEST VALUE", found.single().sequenceItems.single().tags.single().value)
        val sequence = tags.single { it.tagInt == Tag.RequestAttributesSequence }
        val byName = ru.medeng.dicomtool.ui.screens.tags.filterTagsHierarchy(listOf(sequence), sequence.name, false)
        assertEquals(sequence.sequenceItems, byName.single().sequenceItems)
    }
    @Test fun truncatedPixelsNeverBecomeSuccessfulImage() {
        val data = bytes(attrs()).dropLast(2).toByteArray()
        val result = DicomPixelDataDecoder.decodeFrameFromSource("truncated", streamOpener = { ByteArrayInputStream(data) })
        assertTrue(result is DicomPixelDecodeResult.DecodeError)
    }
    @Test fun encapsulatedDataUnderNativeSyntaxIsRejectedInsteadOfRenderingItemHeader() {
        val ds = attrs().apply { remove(Tag.PixelData); newFragments(Tag.PixelData, VR.OB, 2).apply {
            add(byteArrayOf()); add(byteArrayOf(1,2,3,4)) } }
        assertTrue(decode(ds) is DicomPixelDecodeResult.UnsupportedFormat)
    }
    @Test fun undefinedPixelLengthIsReportedAsUnknownEncapsulationNotFourGigabytes() {
        val ds = attrs().apply { remove(Tag.PixelData); newFragments(Tag.PixelData, VR.OB, 2).apply {
            add(byteArrayOf()); add(byteArrayOf(1,2,3,4)) } }
        val pixel = DicomFileReader.readFromStream(ByteArrayInputStream(bytes(ds))).tags.single { it.tagInt == Tag.PixelData }
        assertTrue(pixel.value.contains("Encapsulated")); assertFalse(pixel.value.contains("4294967295"))
        assertEquals(0L, pixel.binaryByteCount)
    }
    @Test fun repeatedOpeningAndWindowChangesRetainOnlyOneFrameAndOriginalBytes() {
        val data = bytes(enhanced()); val original = data.clone(); val cache = SingleFrameDecodeCache()
        repeat(40) { n ->
            val f = (DicomPixelDataDecoder.decodeFrameFromSource("repeat", streamOpener = { ByteArrayInputStream(data) }, frameIndex = n % 2, cache = cache) as DicomPixelDecodeResult.Success).frame
            repeat(10) { DicomPixelDataDecoder.renderFrameToArgbPixels(f, 50.0 + it, 20.0, false) }
            assertNull(cache.get("repeat", 1 - n % 2)); assertSame(f, cache.get("repeat", n % 2))
        }
        cache.clear(); assertNull(cache.get("repeat", 0)); assertArrayEquals(original, data)
    }
}
