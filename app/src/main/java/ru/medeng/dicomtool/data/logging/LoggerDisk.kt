package ru.medeng.dicomtool.data.logging

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/** Filesystem boundary for deterministic failure/slow-disk tests. Called only by disk writer. */
interface LoggerDisk {
    fun append(file: File, bytes: ByteArray)
    fun move(from: File, to: File)
    fun delete(file: File)
    fun clear(file: File)
}

open class FileLoggerDisk : LoggerDisk {
    override fun append(file: File, bytes: ByteArray) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Log directory unavailable")
        // An interrupted append may have left a partial record. Preserve it and separate the next record.
        val delimiter = file.isFile && file.length() > 0 && RandomAccessFile(file, "r").use {
            it.seek(it.length() - 1); it.read() != 10
        }
        FileOutputStream(file, true).use { output ->
            if (delimiter) output.write(10)
            output.write(bytes); output.fd.sync()
        }
    }
    override fun move(from: File, to: File) { Files.move(from.toPath(), to.toPath()) }
    override fun delete(file: File) { Files.delete(file.toPath()) }
    override fun clear(file: File) { FileOutputStream(file, false).use { it.fd.sync() } }
}
