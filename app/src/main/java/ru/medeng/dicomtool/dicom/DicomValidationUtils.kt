package ru.medeng.dicomtool.dicom

/**
 * Утилита валидации параметров DICOM AE Title и TCP-порта согласно стандарту DICOM PS3.5 (VR AE) и сетевым ограничениям Android.
 */
object DicomValidationUtils {

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val messageRu: String) : ValidationResult()
    }

    /**
     * Проверка AE Title (DICOM PS3.5 Section 6.2 - AE VR):
     * - от 1 до 16 символов
     * - только видимые ASCII символы (0x20..0x7E), кроме обратной косой черты '\' и управляющих символов
     * - не может состоять только из пробелов
     */
    fun validateAeTitle(aeTitle: String): ValidationResult {
        val trimmed = aeTitle.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Invalid("AE Title не может быть пустым (1..16 символов ASCII).")
        }
        if (aeTitle.length > 16) {
            return ValidationResult.Invalid("AE Title превышает максимальную длину 16 символов (${aeTitle.length}/16).")
        }
        for (ch in aeTitle) {
            if (ch.code < 0x20 || ch.code > 0x7E || ch == '\\') {
                return ValidationResult.Invalid("Недопустимый символ '$ch' в AE Title. Разрешены только печатные латинские символы, цифры и знаки (без '\\').")
            }
        }
        return ValidationResult.Valid
    }

    /**
     * Проверка TCP-порта для непривилегированного Android-приложения:
     * - Порты 1..1023 на Android требуют root-прав (EACCES: Permission denied).
     * - Рабочий диапазон для локального SCP на Android: 1024..65535 (по умолчанию 11112).
     */
    fun validateScpPort(port: Int): ValidationResult {
        if (port < 1024) {
            return ValidationResult.Invalid("Порт $port является системным (< 1024) и недоступен без root на Android. Укажите порт в диапазоне 1024..65535 (например, 11112).")
        }
        if (port > 65535) {
            return ValidationResult.Invalid("Порт $port выходит за пределы допустимого диапазона TCP (1024..65535).")
        }
        return ValidationResult.Valid
    }

    /**
     * Очищает имя файла или папки от недопустимых символов для файловых систем.
     */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
    }
}
