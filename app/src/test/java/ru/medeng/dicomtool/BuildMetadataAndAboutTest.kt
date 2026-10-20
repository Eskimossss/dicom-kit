package ru.medeng.dicomtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты проверки метаданных сборки и версионирования (Этап 7 - Версионирование).
 */
class BuildMetadataAndAboutTest {

    @Test
    fun testVersionNamingAndCodeConstraints() {
        // Проверка соответствия versionName схеме 1.5.0-alpha.1
        assertEquals("1.5.0-alpha.1", BuildConfig.VERSION_NAME)

        // Version code должен быть строго больше максимального ранее использованного (10301)
        assertTrue(
            "Version code (${BuildConfig.VERSION_CODE}) должен быть строго больше 10301",
            BuildConfig.VERSION_CODE > 10301
        )
        assertEquals(10501, BuildConfig.VERSION_CODE)
    }

    @Test
    fun testBuildMetadataFieldsPresent() {
        assertNotNull("GIT_BRANCH не должен быть null", BuildConfig.GIT_BRANCH)
        assertFalse("GIT_BRANCH не должен быть пустым", BuildConfig.GIT_BRANCH.isBlank())

        assertNotNull("GIT_COMMIT_SHA не должен быть null", BuildConfig.GIT_COMMIT_SHA)
        assertFalse("GIT_COMMIT_SHA не должен быть пустым", BuildConfig.GIT_COMMIT_SHA.isBlank())

        assertNotNull("BUILD_TIME не должен быть null", BuildConfig.BUILD_TIME)
        assertFalse("BUILD_TIME не должен быть пустым", BuildConfig.BUILD_TIME.isBlank())

        assertNotNull("BUILD_TYPE не должен быть null", BuildConfig.BUILD_TYPE)
        assertTrue("BUILD_TYPE должен быть debug в тестовой среде", BuildConfig.BUILD_TYPE.equals("debug", ignoreCase = true))
    }

    @Test
    fun testApplicationIdPreserved() {
        assertEquals("ru.medeng.dicomtool", BuildConfig.APPLICATION_ID)
    }
}
