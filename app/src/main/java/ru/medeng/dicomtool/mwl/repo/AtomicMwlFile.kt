package ru.medeng.dicomtool.mwl.repo

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Never remove the committed file before its replacement. Unsupported atomic replacement fails closed. */
internal object AtomicMwlFile {
    fun write(file: File, text: String, replace: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); Unit
    }) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            replace(tmp.toPath(), file.toPath())
        } finally { if (tmp.isFile) tmp.delete() }
    }
}
