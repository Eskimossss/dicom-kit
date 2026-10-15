package ru.medeng.dicomtool.data.storage

enum class IncomingDuplicatePolicy(val label: String) {
    KEEP_EACH("Каждый приём — отдельная копия"),
    DEDUPLICATE("Подтверждать имеющуюся копию"),
    REJECT_IDENTICAL("Отклонять точные повторы")
}
data class ReceptionStorageOptions(
    val duplicates: IncomingDuplicatePolicy = IncomingDuplicatePolicy.KEEP_EACH,
    val patientFolderNames: Boolean = false
)
enum class StorageDisposition(val label: String) {
    NEW_COPY("Сохранён новый экземпляр"),
    DUPLICATE_CONFIRMED("Точный повтор: подтверждена имеющаяся копия, число файлов не изменилось"),
    REPEAT_COPY("Точный повтор сохранён отдельным файлом"),
    CONTENT_COLLISION("Совпадение UID, разные данные: сохранён отдельный файл"),
    UNKNOWN("Результат хранения уточняйте в подробностях")
}

/** The UID suffix prevents collisions between namesakes and separates studies. No old files are renamed. */
internal object StudyFolderNames {
    fun create(patientName: String, studyUid: String): String {
        StoragePaths.uid(studyUid)
        val clean = patientName.replace('^', ' ').map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }
            .joinToString("").trim().replace(Regex(" +"), " ").take(36).trim()
        return if (clean.isBlank()) studyUid else "${clean}__$studyUid"
    }
    fun checked(name: String, studyUid: String): String {
        require(belongs(name, studyUid) && name.toByteArray(Charsets.UTF_8).size <= 220 &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            name.all { it.isLetterOrDigit() || it in " ._-" }) { "Unsafe study folder name" }
        return name
    }
    fun belongs(name: String, studyUid: String) = name == studyUid || name.endsWith("__$studyUid")
}
