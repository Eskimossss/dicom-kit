package ru.medeng.dicomtool.data.storage

import java.io.File
import java.nio.file.Files

internal const val RETAINED_SOURCE_WARNING = "Копия проверена, но исходный файл не удалось удалить; он сохранён. Повторите перенос после восстановления доступа: одинаковая копия не будет создана повторно."

data class RetainedIncoming(val filesCount: Int = 0, val totalBytes: Long = 0) {
    val warningMessage: String? get() = if (filesCount == 0) null else
        "Во внутренней папке .incoming_tmp сохранены входящие файлы: $filesCount шт., $totalBytes байт. Они могут содержать исследования, не сохранённые после ошибки. Очистка исследований их не удаляет. Требуется проверка и восстановление данных."

    companion object {
        fun inspect(root: File): RetainedIncoming {
            val directory = File(root, ".incoming_tmp")
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return RetainedIncoming()
            StoragePaths.contained(root, directory)
            val files = directory.listFiles().orEmpty().filter {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && it.extension == "part" &&
                    runCatching { StoragePaths.contained(root, it) }.isSuccess
            }
            return RetainedIncoming(files.size, files.sumOf { it.length() })
        }
    }
}
