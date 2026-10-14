package ru.medeng.dicomtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.medeng.dicomtool.data.storage.StorageLocationInfo
import ru.medeng.dicomtool.data.storage.StorageType
import ru.medeng.dicomtool.dicom.tags.DicomVrDictionary

class DicomVrDictionaryAndStorageTest {

    @Test
    fun testAll33StandardDicomVrsPresent() {
        // Проверка наличия всех 33 стандартных типов данных DICOM PS3.5
        val expectedCodes = listOf(
            "AE", "AS", "AT", "CS", "DA", "DS", "DT", "FD", "FL", "IS",
            "LO", "LT", "OB", "OD", "OF", "OL", "OV", "OW", "PN", "SH",
            "SL", "SQ", "SS", "ST", "SV", "UC", "UI", "UL", "UN", "UR",
            "US", "UT", "UV"
        )

        assertEquals("Количество VR в справочнике должно быть ровно 33", 33, DicomVrDictionary.allVrs.size)

        for (code in expectedCodes) {
            val def = DicomVrDictionary.find(code)
            assertNotNull("VR $code должен присутствовать в справочнике", def)
            assertEquals(code, def?.code)
            assertTrue("Название на английском не должно быть пустым для $code", def?.nameEn?.isNotBlank() == true)
            assertTrue("Описание на русском не должно быть пустым для $code", def?.descriptionRu?.isNotBlank() == true)
            assertTrue("Краткая сводка не должна быть пустой для $code", def?.shortSummaryRu?.isNotBlank() == true)
        }
    }

    @Test
    fun testCaseInsensitiveVrLookup() {
        val uiLower = DicomVrDictionary.find("ui")
        val uiUpper = DicomVrDictionary.find("UI")
        assertNotNull(uiLower)
        assertEquals("UI", uiLower?.code)
        assertEquals(uiUpper, uiLower)
        assertEquals("Unique Identifier (UID)", uiLower?.nameEn)
        assertTrue(uiLower?.shortSummaryRu?.contains("Уникальный идентификатор") == true)

        val sq = DicomVrDictionary.find("sq")
        assertNotNull(sq)
        assertEquals("Sequence of Items", sq?.nameEn)
        assertTrue(sq?.shortSummaryRu?.contains("Последовательность") == true)

        val pn = DicomVrDictionary.find("pn")
        assertNotNull(pn)
        assertEquals("Person Name", pn?.nameEn)
        assertTrue(pn?.shortSummaryRu?.contains("Имя человека") == true)
    }

    @Test
    fun testShortDescriptionFallback() {
        val descKnown = DicomVrDictionary.getShortDescription("SH")
        assertTrue("Должна содержать краткое описание для SH", descKnown.contains("Короткая текстовая строка"))

        val descUnknown = DicomVrDictionary.getShortDescription("XX")
        assertTrue("Должен быть фоллбэк для неизвестного VR", descUnknown.contains("XX"))
    }

    @Test
    fun testStorageLocationInfoModel() {
        val internalInfo = StorageLocationInfo(
            type = StorageType.INTERNAL,
            displayPath = "/data/user/0/ru.medeng.dicomtool/files/received_dicom",
            folderName = "received_dicom",
            readableLocation = "Внутреннее хранилище приложения",
            isAccessible = true,
            canWrite = true
        )

        assertEquals(StorageType.INTERNAL, internalInfo.type)
        assertEquals("received_dicom", internalInfo.folderName)
        assertEquals("Внутреннее хранилище приложения", internalInfo.readableLocation)

        val safInfo = StorageLocationInfo(
            type = StorageType.SAF,
            displayPath = "DicomImages (Внутренний накопитель / DicomImages)",
            folderName = "DicomImages",
            readableLocation = "Внутренний накопитель / DicomImages",
            safTreeUriString = "content://com.android.externalstorage.documents/tree/primary%3ADicomImages",
            isAccessible = true,
            canWrite = true
        )

        assertEquals(StorageType.SAF, safInfo.type)
        assertEquals("DicomImages", safInfo.folderName)
        assertEquals("Внутренний накопитель / DicomImages", safInfo.readableLocation)
        assertTrue(safInfo.displayPath.contains("DicomImages"))
    }
}
