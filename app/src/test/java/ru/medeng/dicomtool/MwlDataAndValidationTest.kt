package ru.medeng.dicomtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.medeng.dicomtool.mwl.model.MwlModalityTemplate
import ru.medeng.dicomtool.mwl.model.MwlPatient
import ru.medeng.dicomtool.mwl.model.MwlScheduledStudy
import ru.medeng.dicomtool.mwl.model.MwlValidation
import ru.medeng.dicomtool.mwl.model.SyntheticMwlGenerator
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.mwl.repo.MwlJsonUtils
import java.io.File
import java.nio.file.Files

class MwlDataAndValidationTest {

    private lateinit var tempDir: File
    private lateinit var repository: LocalMwlRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mwl_test_repo").toFile()
        repository = LocalMwlRepository(tempDir, autoSeedIfEmpty = false)
    }

    @Test
    fun testValidationPatientName() {
        assertTrue(MwlValidation.validatePatientName("ИВАНОВ^ИВАН") is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validatePatientName("DOE^JOHN^A") is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validatePatientName("") is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validatePatientName("   ") is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validatePatientName("A".repeat(65)) is MwlValidation.Result.Invalid)
    }

    @Test
    fun testValidationPatientId() {
        assertTrue(MwlValidation.validatePatientId("PAT-001") is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validatePatientId("") is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validatePatientId("P".repeat(65)) is MwlValidation.Result.Invalid)
    }

    @Test
    fun testValidationDateAndRange() {
        assertTrue(MwlValidation.validateDate("20261020", required = true) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateDate("", required = false) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateDate("", required = true) is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validateDate("20261301", required = true) is MwlValidation.Result.Invalid) // месяц 13
        assertTrue(MwlValidation.validateDate("20261035", required = true) is MwlValidation.Result.Invalid) // день 35
        assertTrue(MwlValidation.validateDate("2026102", required = true) is MwlValidation.Result.Invalid) // 7 цифр
    }

    @Test
    fun testValidationTime() {
        assertTrue(MwlValidation.validateTime("120000", required = true) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateTime("0930", required = true) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateTime("", required = false) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateTime("250000", required = true) is MwlValidation.Result.Invalid) // час 25
        assertTrue(MwlValidation.validateTime("126500", required = true) is MwlValidation.Result.Invalid) // мин 65
    }

    @Test
    fun testValidationStudyInstanceUid() {
        val validUid = MwlValidation.generateStudyInstanceUid()
        assertTrue(MwlValidation.validateStudyInstanceUid(validUid) is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateStudyInstanceUid("1.2.840.10008.5.1.4.31") is MwlValidation.Result.Valid)
        assertTrue(MwlValidation.validateStudyInstanceUid("") is MwlValidation.Result.Invalid)
        assertTrue(MwlValidation.validateStudyInstanceUid("1.2.840..1") is MwlValidation.Result.Invalid) // две точки
        assertTrue(MwlValidation.validateStudyInstanceUid("1.2.0840.1") is MwlValidation.Result.Invalid) // ведущий ноль
        assertTrue(MwlValidation.validateStudyInstanceUid("1.2.840.ABC") is MwlValidation.Result.Invalid) // буквы
    }

    @Test
    fun testJsonUtilsEscapeAndUnescape() {
        val original = "Тест \"кавычки\" \\обратный слэш\\ \nперевод \tтаб"
        val escaped = MwlJsonUtils.escape(original)
        val unescaped = MwlJsonUtils.unescape(escaped)
        assertEquals(original, unescaped)
    }

    @Test
    fun testRepositoryCrudAndCascade() {
        val p = MwlPatient(
            patientName = "ТЕСТОВ^ПЕТР",
            patientId = "PAT-999",
            isSynthetic = true
        )
        repository.savePatient(p)
        assertEquals(1, repository.getAllPatients().size)

        val s1 = MwlScheduledStudy(
            patientInternalId = p.id,
            patientName = p.patientName,
            patientId = p.patientId,
            accessionNumber = "ACC-01",
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureDescription = "TEST PROCEDURE",
            requestedProcedureId = "RP-01",
            spsId = "SPS-01",
            modality = "CR",
            spsStartDate = "20261020"
        )
        repository.saveStudy(s1)
        assertEquals(1, repository.getAllStudies().size)

        // Дублирование исследования
        val duplicated = repository.duplicateStudy(s1.id)
        assertNotNull(duplicated)
        assertEquals(2, repository.getAllStudies().size)
        assertEquals(p.patientName, duplicated?.patientName)
        assertFalse(s1.studyInstanceUid == duplicated?.studyInstanceUid) // UID обязан быть новым

        // Каскадное удаление пациента
        repository.deletePatient(p.id, cascadeStudies = true)
        assertEquals(0, repository.getAllPatients().size)
        assertEquals(0, repository.getAllStudies().size)
    }

    @Test
    fun testSyntheticGenerator() {
        val (pSingle, sSingle) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        assertTrue(pSingle.isSynthetic)
        assertTrue(sSingle.isSynthetic)
        assertEquals("CR", sSingle.modality)
        assertTrue(pSingle.patientName.contains("ТЕСТ"))

        val multi = SyntheticMwlGenerator.createMultipleSyntheticPatients(count = 5)
        assertEquals(5, multi.size)
        val modalities = multi.map { it.second.modality }.toSet()
        assertTrue(modalities.contains("CR"))
        assertTrue(modalities.contains("MG"))
        assertTrue(modalities.contains("CT"))

        val (pMultiStudy, studies) = SyntheticMwlGenerator.createPatientWithMultipleStudies(
            templates = listOf(MwlModalityTemplate.CHEST_XRAY, MwlModalityTemplate.COMPUTED_TOMOGRAPHY)
        )
        assertEquals(2, studies.size)
        assertEquals(pMultiStudy.id, studies[0].patientInternalId)
        assertEquals(pMultiStudy.id, studies[1].patientInternalId)
    }
}
