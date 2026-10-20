package ru.medeng.dicomtool

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy

class MwlMatchingEngineTest {

    private val samplePatient = MwlPatient(
        patientName = "ИВАНОВ^ИВАН^ИВАНОВИЧ",
        patientId = "PAT-00101",
        patientBirthDate = "19800512",
        patientSex = "M"
    )

    private val sampleStudy = MwlScheduledStudy(
        patientInternalId = samplePatient.id,
        patientName = samplePatient.patientName,
        patientId = samplePatient.patientId,
        accessionNumber = "ACC-2026-001",
        studyInstanceUid = "2.25.1",
        requestedProcedureId = "RP-550",
        requestedProcedureDescription = "Рентгенография лёгких",
        spsId = "SPS-01",
        modality = "CR",
        spsStartDate = "20261020",
        spsStartTime = "093000",
        scheduledStationAeTitle = "CR_STATION_1",
        scheduledStationName = "Кабинет 101"
    )

    @Test
    fun testSingleValueAndCaseInsensitiveMatching() {
        val q1 = MwlMatchingEngine.MatchingQuery(patientName = "ИВАНОВ^ИВАН^ИВАНОВИЧ")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q1))

        // Регистронезависимое совпадение
        val q2 = MwlMatchingEngine.MatchingQuery(patientName = "иванов^иван^иванович")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q2))

        // Совпадение по фамилии (первому компоненту PN)
        val q3 = MwlMatchingEngine.MatchingQuery(patientName = "ИВАНОВ")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q3))

        // Несовпадающее имя
        val q4 = MwlMatchingEngine.MatchingQuery(patientName = "ПЕТРОВ")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q4))
    }

    @Test
    fun testWildcardMatching() {
        // Звёздочка в конце
        val q1 = MwlMatchingEngine.MatchingQuery(patientName = "ИВАН*")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q1))

        // Звёздочка с обеих сторон
        val q2 = MwlMatchingEngine.MatchingQuery(patientId = "*00101*")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q2)) // MWL Patient ID uses Single Value Matching.

        // Вопросительный знак
        val q3 = MwlMatchingEngine.MatchingQuery(accessionNumber = "ACC-2026-???")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q3))

        // Несовпадающий wildcard
        val q4 = MwlMatchingEngine.MatchingQuery(accessionNumber = "ACC-2025-*")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q4))
    }

    @Test
    fun testDateRangeMatching() {
        // Точное совпадение
        assertTrue(MwlMatchingEngine.matchDateRange("20261020", "20261020"))
        assertFalse(MwlMatchingEngine.matchDateRange("20261021", "20261020"))

        // Диапазон date1-date2
        assertTrue(MwlMatchingEngine.matchDateRange("20261001-20261031", "20261020"))
        assertFalse(MwlMatchingEngine.matchDateRange("20261001-20261019", "20261020"))
        assertFalse(MwlMatchingEngine.matchDateRange("20261021-20261031", "20261020"))

        // От даты: date1-
        assertTrue(MwlMatchingEngine.matchDateRange("20261020-", "20261020"))
        assertTrue(MwlMatchingEngine.matchDateRange("20261001-", "20261020"))
        assertFalse(MwlMatchingEngine.matchDateRange("20261021-", "20261020"))

        // До даты: -date2
        assertTrue(MwlMatchingEngine.matchDateRange("-20261020", "20261020"))
        assertTrue(MwlMatchingEngine.matchDateRange("-20261031", "20261020"))
        assertFalse(MwlMatchingEngine.matchDateRange("-20261019", "20261020"))
    }

    @Test
    fun testTimeRangeMatching() {
        // Точное и префиксное совпадение
        assertTrue(MwlMatchingEngine.matchTimeRange("093000", "093000"))
        assertTrue(MwlMatchingEngine.matchTimeRange("0930", "093000"))

        // Диапазон времени
        assertTrue(MwlMatchingEngine.matchTimeRange("090000-100000", "093000"))
        assertFalse(MwlMatchingEngine.matchTimeRange("100000-110000", "093000"))
    }

    @Test
    fun testSpsSequenceAndRootModalityMatching() {
        // 1. Запрос с Modality в SPS последовательности
        val q1 = MwlMatchingEngine.MatchingQuery(spsModality = "CR", spsScheduledStationAeTitle = "CR_STATION_1")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q1))

        // Несовпадающая модальность
        val q2 = MwlMatchingEngine.MatchingQuery(spsModality = "CT")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q2))

        // Несовпадающий AE Title станции
        val q3 = MwlMatchingEngine.MatchingQuery(spsScheduledStationAeTitle = "CT_STATION_2")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q3))

        // 2. Толерантность: аппарат передал Modality на корневом уровне
        val q4 = MwlMatchingEngine.MatchingQuery(rootModality = "CR")
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, q4))

        val q5 = MwlMatchingEngine.MatchingQuery(rootModality = "DX")
        assertFalse(MwlMatchingEngine.matches(samplePatient, sampleStudy, q5))
    }

    @Test
    fun testUniversalMatchingEmptyQuery() {
        val emptyQuery = MwlMatchingEngine.MatchingQuery()
        assertTrue(MwlMatchingEngine.matches(samplePatient, sampleStudy, emptyQuery))
    }

    @Test
    fun testExtractMatchingQueryFromDicomAttributes() {
        val rq = Attributes()
        rq.setString(Tag.PatientName, VR.PN, "SMITH*")
        rq.setString(Tag.AccessionNumber, VR.SH, "ACC-01")

        val spsSeq = rq.newSequence(Tag.ScheduledProcedureStepSequence, 1)
        val spsItem = Attributes()
        spsItem.setString(Tag.Modality, VR.CS, "MG")
        spsItem.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, "20261020-20261021")
        spsItem.setString(Tag.ScheduledStationAETitle, VR.AE, "MG_STATION")
        spsSeq.add(spsItem)

        val extracted = MwlMatchingEngine.extractMatchingQuery(rq)
        assertEquals("SMITH*", extracted.patientName)
        assertEquals("ACC-01", extracted.accessionNumber)
        assertEquals("MG", extracted.spsModality)
        assertEquals("20261020-20261021", extracted.spsStartDateRange)
        assertEquals("MG_STATION", extracted.spsScheduledStationAeTitle)
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
