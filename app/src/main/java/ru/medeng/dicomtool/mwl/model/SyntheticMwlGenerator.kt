package ru.medeng.dicomtool.mwl.model

import java.util.UUID

enum class MwlModalityTemplate(
    val titleRu: String,
    val modality: String,
    val procedureDescription: String,
    val spsDescription: String,
    val defaultStationAe: String
) {
    CHEST_XRAY(
        titleRu = "Рентген грудной клетки (CR/DX)",
        modality = "CR",
        procedureDescription = "Рентгенография органов грудной клетки в двух проекциях",
        spsDescription = "Chest X-Ray PA/LAT",
        defaultStationAe = "CR_CHEST_01"
    ),
    MAMMOGRAPHY(
        titleRu = "Маммография (MG)",
        modality = "MG",
        procedureDescription = "Цифровая маммография обеих молочных желез (CC/MLO)",
        spsDescription = "Mammography Screening Bilateral",
        defaultStationAe = "MG_STATION_01"
    ),
    FLUOROGRAPHY(
        titleRu = "Флюорография (DX/CR)",
        modality = "DX",
        procedureDescription = "Профилактическая флюорография легких в прямой проекции",
        spsDescription = "Chest Fluorography Screening",
        defaultStationAe = "DX_FLUORO_01"
    ),
    COMPUTED_TOMOGRAPHY(
        titleRu = "Компьютерная томография (CT)",
        modality = "CT",
        procedureDescription = "КТ органов брюшной полости с контрастным усилением",
        spsDescription = "CT Abdomen with Contrast",
        defaultStationAe = "CT_SCANNER_01"
    ),
    ULTRASOUND(
        titleRu = "Ультразвуковое исследование (US)",
        modality = "US",
        procedureDescription = "УЗИ органов брюшной полости и забрюшинного пространства",
        spsDescription = "US Abdominal General",
        defaultStationAe = "US_STATION_01"
    )
}

object SyntheticMwlGenerator {

    private val SYNTH_SURNAMES = listOf("ТЕСТОВ", "ИВАНОВ-ТЕСТ", "СМИРНОВ-ТЕСТ", "СИДОРОВ-ТЕСТ", "КУЗНЕЦОВ-ТЕСТ")
    private val SYNTH_FIRSTNAMES = listOf("СЕРВИС", "ТЕСТЕР", "ИНЖЕНЕР", "ДЕМО", "АППАРАТ")
    private val SYNTH_PATRONYMICS = listOf("ТЕСТОВИЧ", "СЕРВИСОВИЧ", "ДИКОМОВИЧ", "ИНЖЕНЕРОВИЧ")

    /**
     * Создает одного синтетического пациента с одним назначенным исследованием по выбранному шаблону.
     */
    fun createSingleSyntheticPatient(
        template: MwlModalityTemplate,
        index: Int = 1,
        stationAeOverride: String? = null
    ): Pair<MwlPatient, MwlScheduledStudy> {
        val surname = SYNTH_SURNAMES[(index - 1) % SYNTH_SURNAMES.size]
        val firstname = SYNTH_FIRSTNAMES[(index - 1) % SYNTH_FIRSTNAMES.size]
        val patronymic = SYNTH_PATRONYMICS[(index - 1) % SYNTH_PATRONYMICS.size]
        val patientName = "$surname^$firstname^$patronymic"

        val patientId = "SYNTH-PAT-%04d".format(index)
        val birthDate = "198%01d0615".format(index % 10)
        val sex = if (index % 2 == 0) "F" else "M"

        val patient = MwlPatient(
            id = UUID.randomUUID().toString(),
            patientName = patientName,
            patientId = patientId,
            patientBirthDate = birthDate,
            patientSex = sex,
            isSynthetic = true
        )

        val today = MwlValidation.currentDateFormatted()
        val study = MwlScheduledStudy(
            id = UUID.randomUUID().toString(),
            patientInternalId = patient.id,
            patientName = patient.patientName,
            patientId = patient.patientId,
            accessionNumber = "ACC-%04d".format(index),
            studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
            requestedProcedureId = "RP-%04d".format(index),
            requestedProcedureDescription = template.procedureDescription,
            referringPhysicianName = "ДОКТОР^ТЕСТОВЫЙ^СЕРВИС",
            requestingPhysician = "НАПРАВИТЕЛЬ^ТЕСТОВЫЙ^С",
            spsId = "SPS-%04d".format(index),
            modality = template.modality,
            spsStartDate = today,
            spsStartTime = "%02d0000".format(8 + (index % 10)),
            scheduledStationAeTitle = stationAeOverride?.ifBlank { null } ?: template.defaultStationAe,
            scheduledStationName = "Кабинет ${100 + index}",
            spsDescription = template.spsDescription,
            scheduledPerformingPhysician = "ЛАБОРАНТ^ТЕСТОВЫЙ^И",
            spsLocation = "Диагностика",
            isSynthetic = true
        )

        return Pair(patient, study)
    }

    /**
     * Создает нескольких (count) пациентов с исследованиями разных модальностей.
     */
    fun createMultipleSyntheticPatients(
        count: Int = 5,
        startIndex: Int = 1,
        stationAeOverride: String? = null
    ): List<Pair<MwlPatient, MwlScheduledStudy>> {
        val templates = MwlModalityTemplate.entries
        return (0 until count).map { offset ->
            val idx = startIndex + offset
            val template = templates[offset % templates.size]
            createSingleSyntheticPatient(template, idx, stationAeOverride)
        }
    }

    /**
     * Создает одного пациента с несколькими назначенными исследованиями (например, КТ и рентген в один день).
     */
    fun createPatientWithMultipleStudies(
        patientIndex: Int = 10,
        templates: List<MwlModalityTemplate> = listOf(MwlModalityTemplate.CHEST_XRAY, MwlModalityTemplate.COMPUTED_TOMOGRAPHY),
        stationAeOverride: String? = null
    ): Pair<MwlPatient, List<MwlScheduledStudy>> {
        val patientId = "SYNTH-PAT-%04d".format(patientIndex)
        val patient = MwlPatient(
            id = UUID.randomUUID().toString(),
            patientName = "ТЕСТОВ-МНОГО^ПАЦИЕНТ^ИССЛЕДОВАНИЙ",
            patientId = patientId,
            patientBirthDate = "19750510",
            patientSex = "M",
            isSynthetic = true
        )

        val today = MwlValidation.currentDateFormatted()
        val studies = templates.mapIndexed { idx, template ->
            val num = patientIndex * 10 + (idx + 1)
            MwlScheduledStudy(
                id = UUID.randomUUID().toString(),
                patientInternalId = patient.id,
                patientName = patient.patientName,
                patientId = patient.patientId,
                accessionNumber = "ACC-%04d".format(num),
                studyInstanceUid = MwlValidation.generateStudyInstanceUid(),
                requestedProcedureId = "RP-%04d".format(num),
                requestedProcedureDescription = template.procedureDescription,
                referringPhysicianName = "ТЕРАПЕВТ^СЕРВИС^Т",
                spsId = "SPS-%04d".format(num),
                modality = template.modality,
                spsStartDate = today,
                spsStartTime = "%02d3000".format(9 + idx * 2),
                scheduledStationAeTitle = stationAeOverride?.ifBlank { null } ?: template.defaultStationAe,
                scheduledStationName = "Кабинет ${100 + idx + 1}",
                spsDescription = template.spsDescription,
                scheduledPerformingPhysician = "ИНЖЕНЕР^ОПЕРАТОР^С",
                spsLocation = "Диагностика",
                isSynthetic = true
            )
        }

        return Pair(patient, studies)
    }
}
