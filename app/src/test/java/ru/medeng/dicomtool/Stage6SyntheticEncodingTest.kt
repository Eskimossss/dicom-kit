package ru.medeng.dicomtool

import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.io.DicomInputStream
import org.dcm4che3.io.DicomOutputStream
import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.dicom.scu.*
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class Stage6SyntheticEncodingTest {
    private val synthetic = ScuSendConfig(patientName = "ТЕСТ^СИНТЕТИКА^КОДИРОВКА", patientId = "SYNTHETIC-ENCODING",
        studyDescription = "СИНТЕТИЧЕСКОЕ ИССЛЕДОВАНИЕ", seriesDescription = "СИНТЕТИЧЕСКАЯ СЕРИЯ")
    private fun bytes(prepared: PreparedSecondaryCaptureDicomObject) = ByteArrayOutputStream().also { out ->
        DicomOutputStream(out, UID.ExplicitVRLittleEndian).use { it.writeDataset(prepared.fileMetaInformation, prepared.dataset) }
    }.toByteArray()

    /** Independent minimal explicit-VR LE reader: checks on-disk values, never uses dcm4che decoding. */
    private fun raw(bytes: ByteArray): Map<Int, ByteArray> {
        assertEquals("DICM", String(bytes, 128, 4, Charsets.US_ASCII))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); data.position(132)
        val values = linkedMapOf<Int, ByteArray>()
        val longVr = setOf("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "UC", "UR", "UT", "UN")
        while (data.remaining() >= 8) {
            val tag = ((data.short.toInt() and 65535) shl 16) or (data.short.toInt() and 65535)
            val vr = "${data.get().toInt().toChar()}${data.get().toInt().toChar()}"
            val length = if (vr in longVr) { data.short; data.int } else data.short.toInt() and 65535
            require(length >= 0 && length <= data.remaining()) { "Invalid synthetic element length" }
            values[tag] = ByteArray(length).also { data.get(it) }
        }
        assertEquals(0, data.remaining()); return values
    }
    private fun unpad(value: ByteArray) = value.dropLastWhile { it == 0.toByte() || it == 32.toByte() }.toByteArray()
    private fun fixture(encoding: SyntheticDicomEncoding, bytes: ByteArray) {
        System.getenv("STAGE6_ENCODING_FIXTURES")?.let { path ->
            File(path).apply { mkdirs() }.resolve("synthetic-${encoding.name}.dcm").writeBytes(bytes)
        }
    }

    @Test fun standardCyrillicEncodingsHaveExactBytesDeclaredTermAndReadablePersonName() {
        for (encoding in listOf(SyntheticDicomEncoding.UTF8, SyntheticDicomEncoding.ISO8859_5)) {
            val config = synthetic.copy(syntheticEncoding = encoding)
            val prepared = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)
            val encoded = bytes(prepared); val fields = raw(encoded)
            assertEquals(encoding.dicomTerm, String(unpad(fields.getValue(Tag.SpecificCharacterSet)), Charsets.US_ASCII))
            assertArrayEquals(config.patientName.toByteArray(Charset.forName(encoding.charsetName)), unpad(fields.getValue(Tag.PatientName)))
            assertArrayEquals(config.studyDescription.toByteArray(Charset.forName(encoding.charsetName)), unpad(fields.getValue(Tag.StudyDescription)))
            assertArrayEquals(config.seriesDescription.toByteArray(Charset.forName(encoding.charsetName)), unpad(fields.getValue(Tag.SeriesDescription)))
            DicomInputStream(ByteArrayInputStream(encoded)).use { input ->
                val ds = input.readDataset(-1, -1)
                assertEquals(config.patientName, ds.getString(Tag.PatientName))
                assertEquals(config.studyDescription, ds.getString(Tag.StudyDescription))
            }
            assertArrayEquals(prepared.pixelDataBytes, fields.getValue(Tag.PixelData)); fixture(encoding, encoded)
        }
    }
    @Test fun asciiIsStrictAndNeverSilentlyReplacesNonAscii() {
        val mode = SyntheticDicomEncoding.ASCII
        for (config in listOf(synthetic, synthetic.copy(patientName = "SYNTHETIC^ASCII"),
            synthetic.copy(patientName = "SYNTHETIC", studyDescription = "SYNTHETIC"))) {
            try { DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config.copy(syntheticEncoding = mode)); fail("Cyrillic must be rejected") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("несовместимы")) }
        }
        val config = synthetic.copy(syntheticEncoding = mode, patientName = "SYNTHETIC^ASCII", studyDescription = "SYNTHETIC STUDY", seriesDescription = "SYNTHETIC SERIES")
        val encoded = bytes(DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)); val fields = raw(encoded)
        assertFalse(fields.containsKey(Tag.SpecificCharacterSet))
        assertEquals(config.patientName, String(unpad(fields.getValue(Tag.PatientName)), Charsets.US_ASCII))
        fixture(mode, encoded)
    }
    @Test fun undeclaredModeWritesUtf8WithoutCharsetAndWarns() {
        val mode = SyntheticDicomEncoding.UNDECLARED; val config = synthetic.copy(syntheticEncoding = mode)
        val encoded = bytes(DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)); val fields = raw(encoded)
        assertFalse(fields.containsKey(Tag.SpecificCharacterSet))
        assertArrayEquals(config.patientName.toByteArray(Charsets.UTF_8), unpad(fields.getValue(Tag.PatientName)))
        assertTrue(mode.warning(config)!!.contains("не-ASCII")); fixture(mode, encoded)
    }
    @Test fun windows1251WritesActualCpBytesAndNeverMasqueradesAsIso144() {
        val mode = SyntheticDicomEncoding.WINDOWS1251; val config = synthetic.copy(syntheticEncoding = mode)
        val encoded = bytes(DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config)); val fields = raw(encoded)
        assertFalse(fields.containsKey(Tag.SpecificCharacterSet))
        assertArrayEquals(config.patientName.toByteArray(Charset.forName("windows-1251")), unpad(fields.getValue(Tag.PatientName)))
        assertFalse(fields.getValue(Tag.PatientName).contentEquals(config.patientName.toByteArray(Charset.forName("ISO-8859-5"))))
        assertTrue(mode.warning(config)!!.contains("Нестандартный")); fixture(mode, encoded)
    }
    @Test fun changingEncodingChangesSyntheticIdentityButNeverPixelDataOrPreviousObject() {
        val first = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(synthetic)
        val original = bytes(first)
        for (encoding in SyntheticDicomEncoding.entries.filter { it != SyntheticDicomEncoding.ASCII }) {
            val next = DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(synthetic.copy(syntheticEncoding = encoding), existingObject = first)
            assertArrayEquals(first.pixelDataBytes, next.pixelDataBytes)
            if (encoding != SyntheticDicomEncoding.UTF8) assertNotEquals(first.uids.sopInstanceUid, next.uids.sopInstanceUid)
            assertArrayEquals(original, bytes(first))
        }
    }
    @Test fun unencodableCharactersAndMalformedUnicodeAreRejectedWithoutPatientValueInError() {
        for (mode in listOf(SyntheticDicomEncoding.ISO8859_5, SyntheticDicomEncoding.WINDOWS1251)) {
            val problem = mode.validationError(synthetic.copy(patientName = "SYNTHETIC^\uD83D\uDE00"))
            assertNotNull(problem); assertFalse(problem!!.contains("SYNTHETIC")); assertFalse(problem.contains("\uD83D\uDE00"))
        }
        assertNotNull(SyntheticDicomEncoding.UTF8.validationError(synthetic.copy(patientName = "SYNTHETIC^\uD800")))
    }
    @Test fun newOrUnknownPreferencesUseUtf8AndPersonNameGroupsArePreserved() {
        assertEquals(SyntheticDicomEncoding.UTF8, ScuSendConfig().syntheticEncoding)
        assertEquals(SyntheticDicomEncoding.UTF8, SyntheticDicomEncoding.fromPreference("future-mode"))
        assertEquals(SyntheticDicomEncoding.UTF8, SyntheticDicomEncoding.fromPreference(null))
        val config = synthetic.copy(patientName = "SYNTHETIC^NAME^^Dr.=ТЕСТ^ИМЯ")
        val encoded = bytes(DicomSecondaryCaptureGenerator.prepareSecondaryCaptureObject(config))
        assertArrayEquals(config.patientName.toByteArray(Charsets.UTF_8), unpad(raw(encoded).getValue(Tag.PatientName)))
    }
}
