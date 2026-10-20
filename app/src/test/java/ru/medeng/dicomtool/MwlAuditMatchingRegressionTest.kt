package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.mwl.engine.MwlMatchingEngine
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.model.*
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.domain.model.*

class MwlAuditMatchingRegressionTest {
    @Test fun audit03_combinedDateTimeRangeIncludesIntermediateDay() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        val query = MwlMatchingEngine.MatchingQuery(spsStartDateRange = "20261019-20261021", spsStartTimeRange = "110000-180000")
        assertTrue(MwlMatchingEngine.matches(p, s.copy(spsStartDate = "20261020", spsStartTime = "090000"), query))
        assertFalse(MwlMatchingEngine.matches(p, s.copy(spsStartDate = "20261019", spsStartTime = "090000"), query))
        assertFalse(MwlMatchingEngine.matches(p, s.copy(spsStartDate = "20261021", spsStartTime = "190000"), query))
    }

    @Test fun audit08_patientIdIsCaseSensitiveAndPnComponentsKeepPositions() {
        val (p, s) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        assertFalse(MwlMatchingEngine.matches(p, s, MwlMatchingEngine.MatchingQuery(patientId = s.patientId.lowercase())))
        assertFalse(MwlMatchingEngine.matchStringWildcard("^IVAN", "IVAN^", true))
    }

}
