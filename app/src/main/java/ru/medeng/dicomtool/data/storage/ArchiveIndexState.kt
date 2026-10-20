package ru.medeng.dicomtool.data.storage

import java.io.EOFException
import java.io.FileNotFoundException

/** No raw exceptions or stack traces in the UI. File identity is the source path/URI, not its basename. */
enum class DicomReadStage(val label: String) { OPEN("Открытие источника"), FILE_META("File Meta Information"), HEADER("Заголовок Data Set"), METADATA("Чтение атрибутов") }
data class DicomReadProblem(val source: String, val fileName: String, val stage: DicomReadStage, val reason: String) {
    companion object {
        fun from(source: String, name: String, stage: DicomReadStage, error: Exception) = DicomReadProblem(source, name, stage,
            when {
                error is SecurityException -> "Нет разрешения на чтение"
                error is FileNotFoundException -> "Источник удалён или недоступен"
                error is EOFException -> "Файл обрывается до конца заголовка"
                error.message?.let { it.startsWith("Ограничение") || it.startsWith("Метаданные DICOM превышают") } == true -> "Превышен безопасный предел разбора метаданных"
                stage == DicomReadStage.OPEN -> "Не удалось открыть источник; проверьте доступ к хранилищу"
                else -> "Повреждённый, неподдерживаемый или не-DICOM заголовок"
            })
    }
}
/** Charset warnings belong to successfully decoded objects and are not read failures. */
data class ArchiveIndexState(val isLoading: Boolean = false, val warning: String? = null, val indexedFiles: Int = 0,
    val readProblems: List<DicomReadProblem> = emptyList(), val noticeId: String = java.util.UUID.randomUUID().toString())
