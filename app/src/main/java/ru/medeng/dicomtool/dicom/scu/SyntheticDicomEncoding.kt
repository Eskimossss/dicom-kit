package ru.medeng.dicomtool.dicom.scu

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import ru.medeng.dicomtool.domain.model.ScuSendConfig
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Applies exclusively to NEW synthetic objects. Never used by the original-file SCU path. */
enum class SyntheticDicomEncoding(val label: String, val charsetName: String, val dicomTerm: String?) {
    UTF8("UTF-8 (ISO_IR 192)", "UTF-8", "ISO_IR 192"),
    ISO8859_5("ISO-8859-5 (ISO_IR 144)", "ISO-8859-5", "ISO_IR 144"),
    ASCII("ASCII (без тега, ISO IR 6)", "US-ASCII", null),
    UNDECLARED("Без объявления — экспериментальный UTF-8", "UTF-8", null),
    WINDOWS1251("Windows-1251 — нестандартный", "windows-1251", null);

    fun encode(value: String, field: String): ByteArray {
        require(value.none { it.isISOControl() }) { "$field: управляющие символы недопустимы в текстовых метаданных. Измените текст; автоматическая замена не выполняется." }
        try {
            val buffer = Charset.forName(charsetName).newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) {
            // No entered value / patient text is copied into diagnostic messages.
            throw IllegalArgumentException("$field: символы несовместимы с $label. Измените текст или выберите UTF-8. Замена символов не выполняется.")
        }
    }

    fun validationError(config: ScuSendConfig): String? {
        if (config.patientName.trim().isEmpty()) {
            return "Укажите ФИО пациента (обязательный атрибут для формирования DICOM)"
        }
        if (config.patientId.trim().isEmpty()) {
            return "Укажите ID пациента (обязательный атрибут для формирования DICOM)"
        }
        val dateValidation = ru.medeng.dicomtool.dicom.date.DicomDateUtils.validateBirthDateDigits(config.patientBirthDateDigits)
        if (dateValidation is ru.medeng.dicomtool.dicom.date.DateValidationResult.Incomplete) {
            return "Дата рождения заполнена не полностью (введено ${dateValidation.digitsCount} из 8 цифр). Заполните все 8 цифр ДД.ММ.ГГГГ или очистите поле."
        }
        if (dateValidation is ru.medeng.dicomtool.dicom.date.DateValidationResult.Invalid) {
            return dateValidation.messageRu
        }
        return try {
            textFields(config).forEach { encode(it.value, it.label) }
            null
        } catch (invalid: IllegalArgumentException) {
            invalid.message
        }
    }

    fun warning(config: ScuSendConfig): String? = when (this) {
        WINDOWS1251 -> "Нестандартный тест: текстовые байты CP1251 без (0008,0005). Это не ISO_IR 144; оборудование может прочитать текст неверно. Только синтетические объекты."
        UNDECLARED -> if (textFields(config).any { field -> field.value.any { it.code > 127 } })
            "Эксперимент: UTF-8 с не-ASCII символами без (0008,0005) нарушает объявление кодировки DICOM. Приёмник может прочитать текст неверно."
            else "Эксперимент: тег (0008,0005) не записывается; в текущих метаданных только ASCII."
        else -> null
    }

    fun writeTextAttributes(dataset: Attributes, config: ScuSendConfig) {
        dataset.remove(Tag.SpecificCharacterSet)
        dicomTerm?.let { dataset.setString(Tag.SpecificCharacterSet, VR.CS, it) }
        // Explicit bytes prevent any implicit replacement or library re-encoding, including CP1251.
        textFields(config).forEach { field -> dataset.setBytes(field.tag, field.vr, encode(field.value, field.label)) }
    }

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: UTF8
        private data class TextField(val tag: Int, val vr: VR, val value: String, val label: String)
        private fun textFields(config: ScuSendConfig) = listOf(
            TextField(Tag.PatientName, VR.PN, config.patientName.trim(), "ФИО пациента"),
            TextField(Tag.PatientID, VR.LO, config.patientId.trim(), "ID пациента"),
            TextField(Tag.StudyDescription, VR.LO, config.studyDescription.trim(), "Описание исследования"),
            TextField(Tag.SeriesDescription, VR.LO, config.seriesDescription.trim(), "Описание серии"))
    }
}
