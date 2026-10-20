package ru.medeng.dicomtool.data.storage

import java.io.File

/** Active transfers are not abandoned payloads. The lock closes the create/inspect race. */
object IncomingPayloadTracker {
    private val active = HashSet<String>()
    @Synchronized fun create(directory: File, prefix: String): File =
        File.createTempFile(prefix, ".part", directory).also { active.add(it.absolutePath) }
    @Synchronized fun release(file: File) { active.remove(file.absolutePath) }
    @Synchronized fun inactiveFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter { it.absolutePath !in active }
}
