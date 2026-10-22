package ru.medeng.dicomtool

import org.dcm4che3.data.*
import org.dcm4che3.io.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.medeng.dicomtool.mwl.engine.*
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import java.io.*

class Alpha5MwlEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun query(dates: String, times: String) = MwlMatchingEngine.extractMatchingQuery(
        MwlDatasetBuilder.buildMwlScuQueryDataset(dateRange = dates, timeRange = times))
    private fun matches(dates: String, times: String, date: String, time: String): Boolean {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        return MwlMatchingEngine.matches(p, s.copy(spsStartDate = date, spsStartTime = time), query(dates, times))
    }
    @Test fun overnightRangePassesIdentifierValidationAndIncludesBothBoundaries() {
        val dates = "20261021-20261022"; val times = "230000-010000"
        assertTrue(matches(dates, times, "20261022", "003000"))
        assertTrue(matches(dates, times, "20261021", "230000"))
        assertTrue(matches(dates, times, "20261022", "010000"))
        assertFalse(matches(dates, times, "20261021", "225959"))
        assertFalse(matches(dates, times, "20261022", "010001"))
    }
    @Test fun ordinaryCombinedRangeIsContinuousIncludingIntermediateDays() {
        assertTrue(matches("20261021-20261023", "090000-170000", "20261022", "003000"))
        assertFalse(matches("20261021-20261023", "090000-170000", "20261021", "085959"))
        assertFalse(matches("20261021-20261023", "090000-170000", "20261023", "170001"))
    }
    @Test fun openDateTimeBoundariesRemainUnboundedAndOpenTimesUseTheDayEdges() {
        assertTrue(matches("-20261022", "-010000", "20261020", "233000"))
        assertFalse(matches("-20261022", "-010000", "20261022", "010001"))
        assertTrue(matches("20261021-", "230000-", "20261022", "003000"))
        assertFalse(matches("20261021-", "230000-", "20261021", "225959"))
        assertTrue(matches("20261021-20261022", "230000-", "20261022", "235959.999999"))
        assertTrue(matches("20261021-20261022", "-010000", "20261021", "000000"))
    }
    @Test fun reversedStandaloneTimeAndReversedCombinedMomentAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { query("", "230000-010000") }
        assertThrows(IllegalArgumentException::class.java) { query("20261022-20261022", "230000-010000") }
        assertThrows(IllegalArgumentException::class.java) { query("20261022-20261021", "010000-230000") }
        assertTrue(matches("20261022", "0900-09", "20261022", "095959"))
    }
    @Test fun invalidValuesAndEmptyRangesAreNotReinterpretedAsOvernightQueries() {
        for ((dates, times) in listOf("20260230-20260301" to "230000-010000", "20261021-20261022" to "250000-010000",
            "20261021-20261022" to "230000-010000-020000", "-" to "230000-010000", "20261021-20261022" to "-")) {
            assertThrows("$dates/$times", IllegalArgumentException::class.java) { query(dates, times) }
        }
    }
    @Test fun fractionalAndPartialPrecisionBoundariesAreInclusive() {
        assertTrue(matches("20261021-20261022", "23-01", "20261022", "015959.999999"))
        assertFalse(matches("20261021-20261022", "230000.1-010000.2", "20261022", "010000.300000"))
        assertTrue(matches("20261021-20261022", "230000.1-010000.2", "20261022", "010000.200000"))
    }
    @Test fun invalidAccessionCannotBeSavedOrBuiltAndDoesNotChangeCommittedData() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val repo = LocalMwlRepository(temp.root, false); repo.addSyntheticBatch(listOf(p to s))
        val before = repo.snapshot(); val bytes = File(temp.root, "mwl_snapshot.json").readBytes()
        for (acc in listOf("ACC\\OTHER", "A\nB", "A\rB", "A\tB", "A\u001bB", "A\u007fB", "A\u0085B", "X".repeat(17))) {
            assertTrue(acc, MwlValidation.validateAccessionNumber(acc) is MwlValidation.Result.Invalid)
            assertThrows(IllegalArgumentException::class.java) { repo.saveStudy(s.copy(accessionNumber = acc)) }
            assertThrows(IllegalArgumentException::class.java) { MwlDatasetBuilder.buildMwlResponseDataset(p, s.copy(accessionNumber = acc)) }
            assertEquals(before, repo.snapshot()); assertArrayEquals(bytes, File(temp.root, "mwl_snapshot.json").readBytes())
        }
    }
    @Test fun validAccessionIdentifiersRemainExactSingleValuesOnTheWire() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        for (acc in listOf("", "ACC-001/2026", "1234567890123456", "ТЕСТ-001", "ACC WITH SPACE")) {
            assertTrue(MwlValidation.validateAccessionNumber(acc) is MwlValidation.Result.Valid)
            val ds = MwlDatasetBuilder.buildMwlResponseDataset(p, s.copy(accessionNumber = acc))
            val bytes = ByteArrayOutputStream()
            DicomOutputStream(bytes, UID.ExplicitVRLittleEndian).use { it.writeDataset(null, ds) }
            val decoded = DicomInputStream(ByteArrayInputStream(bytes.toByteArray()), UID.ExplicitVRLittleEndian).use { it.readDataset() }
            assertEquals(VR.SH, decoded.getVR(Tag.AccessionNumber))
            assertEquals(acc, decoded.getString(Tag.AccessionNumber, ""))
            if (acc.isNotEmpty()) assertEquals(1, decoded.getStrings(Tag.AccessionNumber).size)
        }
    }
}
