package ru.medeng.dicomtool.dicom.tags

import org.dcm4che3.data.ElementDictionary
import org.dcm4che3.data.Tag
import org.dcm4che3.util.TagUtils

/**
 * Описание названия тега на английском и русском языках.
 */
data class DicomTagNames(
    val nameEn: String,
    val nameRu: String?,
    val isPrivate: Boolean
)

/**
 * Словарь DICOM-тегов (PS3.6 / PS3.10) с поддержкой:
 * - Стандартного словаря dcm4che3 (keywords).
 * - Преобразования ключевых слов в удобочитаемые английские наименования.
 * - Локального технического русскоязычного справочника стандартных тегов.
 * - Распознавания частных тегов производителей (Private Tags) и создателей (Private Creators).
 * - Расширяемой архитектуры для добавления новых тегов.
 */
object DicomTagDictionary {

    private val standardDict = ElementDictionary.getStandardElementDictionary()

    /**
     * Справочник русских переводов ключевых и частых стандартных DICOM-тегов.
     * Соответствует стандартам DICOM PS3.6, ГОСТ Р ИСО 12052 и отечественной медицинской терминологии.
     */
    private val RUSSIAN_TRANSLATIONS: Map<Int, String> = mapOf(
        // Группа 0000 — Командные элементы DIMSE
        Tag.CommandGroupLength to "Длина группы командных элементов",
        Tag.AffectedSOPClassUID to "UID затрагиваемого класса SOP",
        Tag.RequestedSOPClassUID to "UID запрашиваемого класса SOP",
        Tag.CommandField to "Код команды DIMSE",
        Tag.MessageID to "Идентификатор сообщения",
        Tag.MessageIDBeingRespondedTo to "Идентификатор отвечаемого сообщения",
        Tag.MoveDestination to "AE-получатель перемещения (Move)",
        Tag.Priority to "Приоритет",
        Tag.DataSetType to "Тип набора данных (DataSetType)",
        Tag.Status to "Статус выполнения команды",

        // Группа 0002 — File Meta Information (FMI)
        Tag.FileMetaInformationGroupLength to "Длина группы метаинформации файла",
        Tag.FileMetaInformationVersion to "Версия метаинформации файла",
        Tag.MediaStorageSOPClassUID to "UID класса SOP на носителе",
        Tag.MediaStorageSOPInstanceUID to "UID экземпляра SOP на носителе",
        Tag.TransferSyntaxUID to "UID синтаксиса передачи",
        Tag.ImplementationClassUID to "UID класса реализации",
        Tag.ImplementationVersionName to "Имя версии реализации",
        Tag.SourceApplicationEntityTitle to "AE Title источника",
        Tag.SendingApplicationEntityTitle to "AE Title отправителя",
        Tag.ReceivingApplicationEntityTitle to "AE Title получателя",
        Tag.PrivateInformationCreatorUID to "UID создателя частной информации",
        Tag.PrivateInformation to "Частная информация файла",

        // Группа 0008 — Идентификация исследования и оборудования
        Tag.SpecificCharacterSet to "Набор символов (кодировка)",
        Tag.LanguageCodeSequence to "Последовательность кода языка",
        Tag.ImageType to "Тип изображения",
        Tag.InstanceCreationDate to "Дата создания экземпляра",
        Tag.InstanceCreationTime to "Время создания экземпляра",
        Tag.InstanceCreatorUID to "UID создателя экземпляра",
        Tag.InstanceCoercionDateTime to "Дата и время модификации экземпляра",
        Tag.SOPClassUID to "UID класса SOP",
        Tag.SOPInstanceUID to "UID экземпляра SOP",
        Tag.StudyDate to "Дата исследования",
        Tag.SeriesDate to "Дата серии",
        Tag.AcquisitionDate to "Дата сбора данных",
        Tag.ContentDate to "Дата содержимого",
        Tag.StudyTime to "Время исследования",
        Tag.SeriesTime to "Время серии",
        Tag.AcquisitionTime to "Время сбора данных",
        Tag.ContentTime to "Время содержимого",
        Tag.AccessionNumber to "Номер заявки (Accession Number)",
        Tag.QueryRetrieveLevel to "Уровень запроса Query/Retrieve",
        Tag.Modality to "Модальность",
        Tag.ModalitiesInStudy to "Модальности в исследовании",
        Tag.ConversionType to "Тип преобразования",
        Tag.PresentationIntentType to "Назначение представления снимка",
        Tag.Manufacturer to "Производитель оборудования",
        Tag.InstitutionName to "Наименование учреждения",
        Tag.InstitutionAddress to "Адрес учреждения",
        Tag.ReferringPhysicianName to "ФИО направляющего врача",
        Tag.ReferringPhysicianAddress to "Адрес направляющего врача",
        Tag.ReferringPhysicianTelephoneNumbers to "Телефон направляющего врача",
        Tag.CodeValue to "Значение кода",
        Tag.CodingSchemeDesignator to "Классификатор схемы кодирования",
        Tag.CodingSchemeVersion to "Версия схемы кодирования",
        Tag.CodeMeaning to "Значение (расшифровка) кода",
        Tag.TimezoneOffsetFromUTC to "Смещение часового пояса от UTC",
        Tag.StationName to "Имя рабочей станции / аппарата",
        Tag.StudyDescription to "Описание исследования",
        Tag.ProcedureCodeSequence to "Последовательность кода процедуры",
        Tag.SeriesDescription to "Описание серии",
        Tag.InstitutionalDepartmentName to "Наименование отделения учреждения",
        Tag.PhysiciansOfRecord to "ФИО лечащих врачей",
        Tag.PerformingPhysicianName to "ФИО проводящего врача",
        Tag.NameOfPhysiciansReadingStudy to "ФИО врача, описывающего исследование",
        Tag.OperatorsName to "ФИО оператора / лаборанта",
        Tag.AdmittingDiagnosesDescription to "Описание направительного диагноза",
        Tag.ManufacturerModelName to "Модель аппарата",
        Tag.ReferencedStudySequence to "Последовательность ссылок на исследования",
        Tag.ReferencedSeriesSequence to "Последовательность ссылок на серии",
        Tag.ReferencedPatientSequence to "Последовательность ссылок на пациента",
        Tag.ReferencedVisitSequence to "Последовательность ссылок на посещение",
        Tag.ReferencedOverlaySequence to "Последовательность ссылок на оверлеи",
        Tag.ReferencedImageSequence to "Последовательность ссылок на изображения",
        Tag.ReferencedCurveSequence to "Последовательность ссылок на кривые",
        Tag.ReferencedInstanceSequence to "Последовательность ссылок на экземпляры",
        Tag.ReferencedSOPClassUID to "UID класса SOP ссылки",
        Tag.ReferencedSOPInstanceUID to "UID экземпляра SOP ссылки",
        Tag.DerivationDescription to "Описание преобразования изображения",
        Tag.SourceImageSequence to "Последовательность исходных изображений",

        // Группа 0010 — Данные пациента
        Tag.PatientName to "ФИО пациента",
        Tag.PatientID to "Идентификатор пациента",
        Tag.IssuerOfPatientID to "Организация, выдавшая ID пациента",
        Tag.PatientBirthDate to "Дата рождения пациента",
        Tag.PatientBirthTime to "Время рождения пациента",
        Tag.PatientSex to "Пол пациента",
        Tag.PatientInsurancePlanCodeSequence to "Код страхового полиса пациента",
        Tag.PatientPrimaryLanguageCodeSequence to "Основной язык пациента",
        Tag.OtherPatientIDs to "Другие идентификаторы пациента",
        Tag.OtherPatientNames to "Другие имена пациента",
        Tag.PatientBirthName to "Имя пациента при рождении",
        Tag.PatientAge to "Возраст пациента",
        Tag.PatientSize to "Рост пациента",
        Tag.PatientWeight to "Вес пациента",
        Tag.PatientAddress to "Адрес пациента",
        Tag.PatientMotherBirthName to "Девичья фамилия матери пациента",
        Tag.MilitaryRank to "Воинское звание",
        Tag.MedicalRecordLocator to "Номер медицинской карты",
        Tag.MedicalAlerts to "Медицинские предупреждения (аллергии и др.)",
        Tag.Allergies to "Аллергические реакции",
        Tag.CountryOfResidence to "Страна проживания",
        Tag.RegionOfResidence to "Регион проживания",
        Tag.PatientTelephoneNumbers to "Номера телефонов пациента",
        Tag.EthnicGroup to "Этническая принадлежность",
        Tag.Occupation to "Профессия",
        Tag.SmokingStatus to "Статус курения",
        Tag.AdditionalPatientHistory to "Анамнез пациента",
        Tag.PregnancyStatus to "Статус беременности",
        Tag.LastMenstrualDate to "Дата последней менструации",
        Tag.PatientSexNeutered to "Стерилизация животного",
        Tag.SpecialNeeds to "Особые потребности",
        Tag.PatientState to "Состояние пациента",
        Tag.PatientComments to "Комментарии к пациенту",

        // Группа 0018 — Параметры сбора данных и аппаратуры
        Tag.ContrastBolusAgent to "Контрастное вещество",
        Tag.ContrastBolusRoute to "Путь введения контраста",
        Tag.ContrastBolusVolume to "Объём контрастного вещества",
        Tag.ContrastBolusStartTime to "Время начала введения контраста",
        Tag.ContrastBolusStopTime to "Время окончания введения контраста",
        Tag.ContrastBolusTotalDose to "Общая доза контрастного вещества",
        Tag.ContrastFlowRate to "Скорость введения контраста",
        Tag.BodyPartExamined to "Исследуемая анатомическая область",
        Tag.ScanningSequence to "Последовательность сканирования",
        Tag.SequenceVariant to "Вариант импульсной последовательности",
        Tag.ScanOptions to "Параметры сканирования",
        Tag.MRAcquisitionType to "Тип сбора МРТ данных (2D/3D)",
        Tag.SequenceName to "Имя импульсной последовательности",
        Tag.AngioFlag to "Признак ангиографии",
        Tag.SliceThickness to "Толщина среза",
        Tag.RepetitionTime to "Время повторения (TR)",
        Tag.EchoTime to "Время эха (TE)",
        Tag.InversionTime to "Время инверсии (TI)",
        Tag.NumberOfAverages to "Количество усреднений (NEX)",
        Tag.ImagingFrequency to "Частота томографа (МГц)",
        Tag.ImagedNucleus to "Исследуемое ядро",
        Tag.EchoNumbers to "Номера эхо-сигналов",
        Tag.MagneticFieldStrength to "Индукция магнитного поля (Тл)",
        Tag.SpacingBetweenSlices to "Расстояние между срезами",
        Tag.NumberOfPhaseEncodingSteps to "Число шагов фазового кодирования",
        Tag.EchoTrainLength to "Длина эхо-трейна",
        Tag.PercentSampling to "Процент выборки",
        Tag.PercentPhaseFieldOfView to "Процент фазового поля обзора",
        Tag.PixelBandwidth to "Полоса пропускания на пиксель",
        Tag.DeviceSerialNumber to "Серийный номер устройства",
        Tag.DeviceUID to "UID устройства",
        Tag.PlateID to "Идентификатор кассеты/пластины",
        Tag.SoftwareVersions to "Версии программного обеспечения",
        Tag.ProtocolName to "Название протокола сканирования",
        Tag.ReconstructionDiameter to "Диаметр реконструкции (FOV)",
        Tag.DistanceSourceToDetector to "Расстояние фокус-детектор (SID)",
        Tag.DistanceSourceToPatient to "Расстояние фокус-пациент (SOD)",
        Tag.GantryDetectorTilt to "Угол наклона гентри",
        Tag.TableHeight to "Высота стола",
        Tag.RotationDirection to "Направление вращения",
        Tag.ExposureTime to "Время экспозиции (мс)",
        Tag.XRayTubeCurrent to "Ток трубки (мА)",
        Tag.Exposure to "Экспозиция (мАс)",
        Tag.ExposureInuAs to "Экспозиция (мкАс)",
        Tag.KVP to "Анодное напряжение (кВ)",
        Tag.FilterType to "Тип фильтра",
        Tag.GeneratorPower to "Мощность генератора (кВт)",
        Tag.CollimatorGridName to "Тип отсеивающей решётки",
        Tag.FocalSpots to "Размер фокусного пятна",
        Tag.AnodeTargetMaterial to "Материал анода",
        Tag.BodyPartThickness to "Толщина анатомической области (мм)",
        Tag.CompressionForce to "Сила компрессии (Н)",
        Tag.ViewPosition to "Проекция снимка (AP/PA/LL/CC/MLO)",
        Tag.PatientPosition to "Положение пациента (HFS/FFS/HFP и др.)",
        Tag.DetectorType to "Тип детектора",
        Tag.DetectorConfiguration to "Конфигурация детектора",
        Tag.DateOfLastCalibration to "Дата последней калибровки",
        Tag.TimeOfLastCalibration to "Время последней калибровки",

        // Группа 0020 — Взаимосвязи, геометрия и ориентация
        Tag.StudyInstanceUID to "UID исследования",
        Tag.SeriesInstanceUID to "UID серии",
        Tag.StudyID to "Номер исследования",
        Tag.SeriesNumber to "Номер серии",
        Tag.AcquisitionNumber to "Номер сбора данных",
        Tag.InstanceNumber to "Номер снимка (экземпляра)",
        Tag.PatientOrientation to "Анатомическая ориентация пациента",
        Tag.ImagePositionPatient to "Координаты положения снимка (пациент)",
        Tag.ImageOrientationPatient to "Направляющие косинусы ориентации (пациент)",
        Tag.FrameOfReferenceUID to "UID системы пространственных координат",
        Tag.TemporalPositionIdentifier to "Временной идентификатор позиции",
        Tag.NumberOfTemporalPositions to "Количество временных позиций",
        Tag.PositionReferenceIndicator to "Анатомический ориентир системы координат",
        Tag.SliceLocation to "Координата среза (Z)",
        Tag.ImageComments to "Комментарии к изображению",

        // Группа 0028 — Представление изображения и пикселей
        Tag.SamplesPerPixel to "Количество цветовых каналов на пиксель",
        Tag.PhotometricInterpretation to "Фотометрическая интерпретация",
        Tag.PlanarConfiguration to "Планарная конфигурация каналов",
        Tag.NumberOfFrames to "Количество кадров",
        Tag.FrameIncrementPointer to "Указатель инкремента кадров",
        Tag.Rows to "Строки (высота изображения в пикселях)",
        Tag.Columns to "Столбцы (ширина изображения в пикселях)",
        Tag.Planes to "Количество плоскостей",
        Tag.PixelSpacing to "Физический размер пикселя (мм)",
        Tag.PixelAspectRatio to "Соотношение сторон пикселя",
        Tag.BitsAllocated to "Выделено бит на пиксель (Bits Allocated)",
        Tag.BitsStored to "Задействовано бит на пиксель (Bits Stored)",
        Tag.HighBit to "Старший значащий бит (High Bit)",
        Tag.PixelRepresentation to "Представление пикселя (0 = Unsigned, 1 = Signed)",
        Tag.SmallestImagePixelValue to "Минимальное значение пикселя в снимке",
        Tag.LargestImagePixelValue to "Максимальное значение пикселя в снимке",
        Tag.PixelPaddingValue to "Значение пикселей подложки (Padding)",
        Tag.PixelPaddingRangeLimit to "Предел диапазона пикселей подложки",
        Tag.WindowCenter to "Центр окна яркости (Window Level / Center)",
        Tag.WindowWidth to "Ширина окна яркости (Window Width)",
        Tag.RescaleIntercept to "Смещение шкалы Хаунсфилда (Rescale Intercept)",
        Tag.RescaleSlope to "Наклон шкалы Хаунсфилда (Rescale Slope)",
        Tag.RescaleType to "Тип шкалы преобразования (HU, OD, US)",
        Tag.WindowCenterWidthExplanation to "Описание предустановки окна яркости",
        Tag.VOILUTSequence to "Последовательность таблиц преобразования VOI LUT",
        Tag.LUTDescriptor to "Дескриптор таблицы LUT",
        Tag.LUTExplanation to "Описание таблицы LUT",
        Tag.LUTData to "Данные таблицы LUT",
        Tag.LossyImageCompression to "Признак сжатия с потерями (00/01)",
        Tag.LossyImageCompressionRatio to "Коэффициент сжатия с потерями",
        Tag.LossyImageCompressionMethod to "Метод сжатия с потерями",

        // Группа 0040 — Worklist, расписание и протоколы
        Tag.ScheduledStationAETitle to "Запланированный AE Title станции",
        Tag.ScheduledProcedureStepStartDate to "Дата начала запланированного этапа",
        Tag.ScheduledProcedureStepStartTime to "Время начала запланированного этапа",
        Tag.ScheduledProcedureStepEndDate to "Дата завершения запланированного этапа",
        Tag.ScheduledProcedureStepEndTime to "Время завершения запланированного этапа",
        Tag.ScheduledPerformingPhysicianName to "ФИО назначенного врача",
        Tag.ScheduledProcedureStepDescription to "Описание запланированного этапа процедуры",
        Tag.ScheduledProcedureStepID to "Идентификатор этапа процедуры",
        Tag.RequestedProcedureID to "Идентификатор запрашиваемой процедуры",
        Tag.RequestedProcedureDescription to "Описание запрашиваемой процедуры",
        Tag.RequestedProcedurePriority to "Приоритет запрашиваемой процедуры",
        Tag.PatientTransportArrangements to "Условия транспортировки пациента",

        // Группа 7FE0 — Данные изображения
        Tag.PixelData to "Массив данных изображения (Pixel Data)",
        Tag.FloatPixelData to "Массив данных с плавающей точкой (Float Pixel Data)",
        Tag.DoubleFloatPixelData to "Массив данных двойной точности (Double Float Pixel Data)"
    )

    /**
     * Преобразует ключевое слово dcm4che (CamelCase) в читаемое английское название.
     * Например:
     * "PatientName" -> "Patient Name"
     * "StudyInstanceUID" -> "Study Instance UID"
     * "SOPClassUID" -> "SOP Class UID"
     * "SpecificCharacterSet" -> "Specific Character Set"
     */
    fun formatEnglishName(keyword: String): String {
        if (keyword.isBlank() || keyword == "PrivateTag") return "Private Tag"
        if (keyword == "Item") return "Sequence Item"

        // Обработка частых аббревиатур (UID, SOP, FMI, CT, MR, VR, KVP, VOI, LUT)
        val normalized = keyword
            .replace("UID", " UID")
            .replace("SOP", " SOP ")
            .replace("FMI", " FMI ")
            .replace("KVP", " KVP")
            .replace("VOI", " VOI ")
            .replace("LUT", " LUT ")
            .replace("AE", " AE ")

        val sb = StringBuilder()
        for (i in normalized.indices) {
            val c = normalized[i]
            if (i > 0 && c.isUpperCase() && normalized[i - 1].isLowerCase()) {
                sb.append(' ')
            }
            sb.append(c)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Разрешает английское и русское наименование тега.
     */
    fun resolveTagNames(tag: Int, creator: String? = null): DicomTagNames {
        if (TagUtils.isPrivateCreator(tag)) {
            return DicomTagNames(
                nameEn = "Private Creator Data Element",
                nameRu = "Идентификатор частного создателя",
                isPrivate = true
            )
        }

        if (TagUtils.isPrivateTag(tag) || TagUtils.isPrivateGroup(tag)) {
            // Попытка разрешить по словарю создателя через dcm4che
            if (!creator.isNullOrBlank()) {
                val privDict = runCatching { ElementDictionary.getElementDictionary(creator) }.getOrNull()
                val privKeyword = privDict?.keywordOf(tag)
                if (!privKeyword.isNullOrBlank() && privKeyword != "PrivateTag") {
                    return DicomTagNames(
                        nameEn = formatEnglishName(privKeyword),
                        nameRu = "Частный тег ($creator)",
                        isPrivate = true
                    )
                }
            }

            return DicomTagNames(
                nameEn = "Private Tag",
                nameRu = "Частный тег производителя",
                isPrivate = true
            )
        }

        // Проверяем групповую длину (Group Length, EEEE=0000)
        if (TagUtils.isGroupLength(tag)) {
            val groupHex = "%04X".format(TagUtils.groupNumber(tag))
            return DicomTagNames(
                nameEn = "Group $groupHex Length",
                nameRu = "Длина группы $groupHex",
                isPrivate = false
            )
        }

        val rawKeyword = standardDict.keywordOf(tag)
        val russianTranslation = RUSSIAN_TRANSLATIONS[tag]

        if (!rawKeyword.isNullOrBlank()) {
            val englishName = formatEnglishName(rawKeyword)
            return DicomTagNames(
                nameEn = englishName,
                nameRu = russianTranslation,
                isPrivate = false
            )
        }

        return DicomTagNames(
            nameEn = "Unknown Tag",
            nameRu = "Неизвестный тег",
            isPrivate = false
        )
    }

    /**
     * Возвращает русское наименование тега, если оно известно.
     */
    fun getRussianName(tag: Int): String? = RUSSIAN_TRANSLATIONS[tag]
}
