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
import ru.medeng.dicomtool.mwl.model.MwlProcedureSuggestions
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

    @Test
    fun testCascadeDeletePatientWithZeroOneAndMultipleStudiesAndRollback() {
        val (_, template) = SyntheticMwlGenerator.createSingleSyntheticPatient(MwlModalityTemplate.CHEST_XRAY)
        // Пациент 1: без назначений
        val pZero = repository.savePatient(MwlPatient(patientName = "НОЛЬ^НАЗНАЧЕНИЙ", patientId = "PAT-ZERO"))
        // Пациент 2: с 1 назначением
        val pOne = repository.savePatient(MwlPatient(patientName = "ОДНО^НАЗНАЧЕНИЕ", patientId = "PAT-ONE"))
        val sOne = repository.saveStudy(template.copy(
            id = java.util.UUID.randomUUID().toString(),
            patientInternalId = pOne.id, patientName = pOne.patientName, patientId = pOne.patientId,
            accessionNumber = "ACC-ONE", studyInstanceUid = MwlValidation.generateStudyInstanceUid()
        ))
        // Пациент 3: с несколькими назначениями
        val pMulti = repository.savePatient(MwlPatient(patientName = "МНОГО^НАЗНАЧЕНИЙ", patientId = "PAT-MULTI"))
        val sMulti1 = repository.saveStudy(template.copy(
            id = java.util.UUID.randomUUID().toString(),
            patientInternalId = pMulti.id, patientName = pMulti.patientName, patientId = pMulti.patientId,
            accessionNumber = "ACC-M1", modality = "CT", studyInstanceUid = MwlValidation.generateStudyInstanceUid()
        ))
        val sMulti2 = repository.saveStudy(template.copy(
            id = java.util.UUID.randomUUID().toString(),
            patientInternalId = pMulti.id, patientName = pMulti.patientName, patientId = pMulti.patientId,
            accessionNumber = "ACC-M2", modality = "MR", studyInstanceUid = MwlValidation.generateStudyInstanceUid()
        ))

        assertEquals(3, repository.getAllPatients().size)
        assertEquals(3, repository.getAllStudies().size)

        // 1. Удаление пациента без назначений: удаляется только он, остальные не затронуты
        assertTrue(repository.deletePatient(pZero.id, cascadeStudies = true))
        assertNull(repository.getPatientById(pZero.id))
        assertEquals(2, repository.getAllPatients().size)
        assertEquals(3, repository.getAllStudies().size)

        // 2. Удаление пациента с 1 назначением: удаляется пациент и его исследование, pMulti не затронут
        assertTrue(repository.deletePatient(pOne.id, cascadeStudies = true))
        assertNull(repository.getPatientById(pOne.id))
        assertNull(repository.getStudyById(sOne.id))
        assertEquals(1, repository.getAllPatients().size)
        assertEquals(2, repository.getAllStudies().size)
        assertNotNull(repository.getPatientById(pMulti.id))
        assertNotNull(repository.getStudyById(sMulti1.id))
        assertNotNull(repository.getStudyById(sMulti2.id))

        // 3. Тест отката при сбое транзакции (I/O error rollback)
        var shouldFail = true
        val failingRepo = LocalMwlRepository(
            rootDir = tempDir,
            autoSeedIfEmpty = false,
            snapshotWriter = { file, text ->
                if (shouldFail) {
                    throw java.io.IOException("Симуляция ошибки записи snapshot при транзакции")
                } else {
                    ru.medeng.dicomtool.mwl.repo.AtomicMwlFile.write(file, text)
                }
            }
        )
        // Исходное состояние до сбоя
        val beforePatients = failingRepo.getAllPatients()
        val beforeStudies = failingRepo.getAllStudies()
        assertEquals(1, beforePatients.size)
        assertEquals(2, beforeStudies.size)

        // Попытка удаления при сбое
        try {
            failingRepo.deletePatient(pMulti.id, cascadeStudies = true)
            org.junit.Assert.fail("Ожидалось исключение IOException при сбое транзакции записи")
        } catch (_: java.io.IOException) {
            // Ожидаемо
        }

        // Проверяем атомарный откат: данные в памяти и снимок не изменились
        assertEquals(beforePatients, failingRepo.getAllPatients())
        assertEquals(beforeStudies, failingRepo.getAllStudies())
        assertNotNull(failingRepo.getPatientById(pMulti.id))
        assertNotNull(failingRepo.getStudyById(sMulti1.id))
        assertNotNull(failingRepo.getStudyById(sMulti2.id))

        // 4. Успешное удаление пациента с несколькими назначениями при восстановлении
        shouldFail = false
        assertTrue(failingRepo.deletePatient(pMulti.id, cascadeStudies = true))
        assertNull(failingRepo.getPatientById(pMulti.id))
        assertNull(failingRepo.getStudyById(sMulti1.id))
        assertNull(failingRepo.getStudyById(sMulti2.id))
        assertEquals(0, failingRepo.getAllPatients().size)
        assertEquals(0, failingRepo.getAllStudies().size)
    }

    @Test
    fun testFreshInstallCleanStateAndUpgradePreservation() {
        val freshDir = java.io.File(tempDir, "fresh_install_dir")
        // 1. Первая установка с дефолтным параметром autoSeedIfEmpty: база пуста
        val freshRepo = LocalMwlRepository(freshDir)
        assertTrue(freshRepo.getAllPatients().isEmpty())
        assertTrue(freshRepo.getAllStudies().isEmpty())
        assertEquals(0, freshRepo.patientsFlow.value.size)
        assertEquals(0, freshRepo.studiesFlow.value.size)

        // 2. Ручное создание тестовых данных (кнопка «Тестовые»)
        val batch = SyntheticMwlGenerator.createMultipleSyntheticPatients(count = 3)
        freshRepo.addSyntheticBatch(batch)
        assertEquals(3, freshRepo.getAllPatients().size)
        assertEquals(3, freshRepo.getAllStudies().size)

        // 3. Обновление приложения (переоткрытие репозитория с существующими файлами)
        val upgradedRepo = LocalMwlRepository(freshDir)
        assertEquals(3, upgradedRepo.getAllPatients().size)
        assertEquals(3, upgradedRepo.getAllStudies().size)
        assertEquals(freshRepo.getAllPatients().map { it.id }, upgradedRepo.getAllPatients().map { it.id })
    }

    @Test
    fun testGeneratedIdentifiersFormat() {
        val acc = MwlValidation.generateAccessionNumber()
        assertTrue("Accession Number should start with ACC-", acc.startsWith("ACC-"))
        assertTrue("Accession Number length <= 16", acc.length <= 16)
        assertTrue(MwlValidation.validateAccessionNumber(acc) is MwlValidation.Result.Valid)

        val rp = MwlValidation.generateRequestedProcedureId()
        assertTrue("RP ID should start with RP-", rp.startsWith("RP-"))
        assertTrue("RP ID length <= 16", rp.length <= 16)
        assertTrue(MwlValidation.validateShortString(rp, "requestedProcedureId") is MwlValidation.Result.Valid)

        val sps = MwlValidation.generateSpsId()
        assertTrue("SPS ID should start with SPS-", sps.startsWith("SPS-"))
        assertTrue("SPS ID length <= 16", sps.length <= 16)
        assertTrue(MwlValidation.validateShortString(sps, "spsId") is MwlValidation.Result.Valid)

        val uid = MwlValidation.generateStudyInstanceUid()
        assertTrue("UID length <= 64", uid.length <= 64)
        assertTrue(MwlValidation.validateStudyInstanceUid(uid) is MwlValidation.Result.Valid)
    }

    @Test
    fun testMwlProcedureSuggestions() {
        val crList = MwlProcedureSuggestions.forModality("CR")
        assertTrue("CR suggestions non-empty", crList.isNotEmpty())
        assertTrue("Contains chest x-ray", crList.any { it.contains("грудной клетки") })

        val ctList = MwlProcedureSuggestions.forModality("CT")
        assertTrue("CT suggestions non-empty", ctList.isNotEmpty())
        assertTrue("Contains CT head or chest", ctList.any { it.startsWith("КТ") })

        val mgList = MwlProcedureSuggestions.forModality("MG")
        assertTrue("MG suggestions non-empty", mgList.isNotEmpty())
        assertTrue("Contains mammography", mgList.any { it.startsWith("Маммография") })

        val unknownList = MwlProcedureSuggestions.forModality("UNKNOWN_XYZ")
        assertTrue("Unknown fallback non-empty", unknownList.isNotEmpty())

        val nullList = MwlProcedureSuggestions.forModality(null)
        assertTrue("Null fallback non-empty", nullList.isNotEmpty())

        // Verify all suggestions are <= 64 characters (DICOM LO)
        listOf("CR", "DX", "MG", "CT", "MR", "US", "XA", "RF", "NM", "PT", "ECG", "ES", null).forEach { mod ->
            MwlProcedureSuggestions.forModality(mod).forEach { suggestion ->
                assertTrue("Suggestion '$suggestion' <= 64 chars", suggestion.length <= 64)
            }
        }
    }

    @Test
    fun testMwlCleanDraftAndAutoGenerateUid() {
        // Проверяем, что генераторы идентификаторов создают валидные значения для модели исследования
        val patient = repository.savePatient(MwlPatient(patientName = "ПЕТРОВ^ПЕТР", patientId = "PAT-123"))

        // Создаем исследование с пустыми ID и UID, как в форме создания назначения
        val cleanDraft = MwlScheduledStudy(
            patientInternalId = patient.id,
            patientName = patient.patientName,
            patientId = patient.patientId,
            accessionNumber = "",
            studyInstanceUid = "",
            requestedProcedureId = "",
            requestedProcedureDescription = "Рентгенография органов грудной клетки",
            spsId = "",
            modality = "CR",
            spsStartDate = MwlValidation.currentDateFormatted(),
            spsStartTime = MwlValidation.currentTimeFormatted(),
            scheduledStationAeTitle = "CR_STATION",
            scheduledStationName = "Room 101"
        )

        // Дозаполнение при сохранении (аналог логики saveStudy)
        val fallbackNum = (100000..999999).random()
        val studyToSave = cleanDraft.copy(
            studyInstanceUid = cleanDraft.studyInstanceUid.ifBlank { MwlValidation.generateStudyInstanceUid() },
            accessionNumber = cleanDraft.accessionNumber.ifBlank { "ACC-$fallbackNum" },
            requestedProcedureId = cleanDraft.requestedProcedureId.ifBlank { "RP-$fallbackNum" },
            spsId = cleanDraft.spsId.ifBlank { "SPS-$fallbackNum" }
        )

        val validation = MwlValidation.validateStudy(studyToSave)
        assertTrue("Validation should be Valid: $validation", validation is MwlValidation.Result.Valid)

        repository.saveStudy(studyToSave)
        val savedStudies = repository.getAllStudies()
        assertEquals(1, savedStudies.size)
        val stored = savedStudies.first()
        assertTrue(stored.accessionNumber.startsWith("ACC-"))
        assertTrue(stored.requestedProcedureId.startsWith("RP-"))
        assertTrue(stored.spsId.startsWith("SPS-"))
        assertTrue(stored.studyInstanceUid.isNotBlank())
        assertTrue(MwlValidation.validateStudyInstanceUid(stored.studyInstanceUid) is MwlValidation.Result.Valid)
        assertEquals("CR", stored.modality)
        assertEquals("Рентгенография органов грудной клетки", stored.requestedProcedureDescription)
    }
}
