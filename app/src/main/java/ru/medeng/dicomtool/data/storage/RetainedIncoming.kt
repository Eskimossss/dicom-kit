package ru.medeng.dicomtool.data.storage

import java.io.File
import java.nio.file.Files

internal const val RETAINED_SOURCE_WARNING = "Копия проверена, но исходный файл не удалось удалить; он сохранён. Повторите перенос после восстановления доступа: одинаковая копия не будет создана повторно."

data class RetainedIncoming(val filesCount: Int = 0, val totalBytes: Long = 0) {
    val warningMessage: String? get() = if (filesCount == 0) null else
        "Остались входящие данные после прерывания или ошибки: $filesCount шт., $totalBytes байт. Это могут быть единственные копии. Очистка исследований их не удаляет. Можно повторить проверенное сохранение; непроверенные файлы останутся на месте."

    companion object {
        fun inspect(root: File): RetainedIncoming {
            val directory = File(root, ".incoming_tmp")
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) return RetainedIncoming()
            StoragePaths.contained(root, directory)
            val files = IncomingPayloadTracker.inactiveFiles(directory).filter {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && it.extension == "part" &&
                    runCatching { StoragePaths.contained(root, it) }.isSuccess
            }
            return RetainedIncoming(files.size, files.sumOf { it.length() })
        }
    }
}
