package ru.medeng.dicomtool.dicom.tags

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Fragments
import org.dcm4che3.data.Sequence
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import org.dcm4che3.io.DicomInputHandler
import org.dcm4che3.io.DicomInputStream
import java.io.IOException

/** Limits are checked before dcm4che allocates values or recurses. Never changes the source. */
object BoundedDicomRead {
    const val MAX_DEPTH = 16
    const val MAX_ELEMENTS = 20000
    const val MAX_VALUE_BYTES = 1024 * 1024L
    const val MAX_METADATA_BYTES = 8 * 1024 * 1024L
    fun install(dis: DicomInputStream, checkCancelled: () -> Unit = {}) {
        var elements = 0
        var bytes = 0L
        fun check(length: Long = 0, sequence: Boolean = false) {
            checkCancelled()
            if (++elements > MAX_ELEMENTS || dis.level() > MAX_DEPTH)
                throw IOException("Ограничение разбора DICOM: глубина > $MAX_DEPTH или элементов > $MAX_ELEMENTS")
            if (!sequence) {
                if (length > MAX_VALUE_BYTES) throw IOException("Ограничение значения тега: $length байт > $MAX_VALUE_BYTES")
                bytes += length
                val budget = minOf(MAX_METADATA_BYTES, Runtime.getRuntime().maxMemory() / 16)
                if (bytes > budget) throw IOException("Метаданные DICOM превышают $budget байт")
            }
        }
        dis.setDicomInputHandler(object : DicomInputHandler {
            override fun readValue(stream: DicomInputStream, attrs: Attributes) {
                if (stream.tag() == Tag.PixelData) { checkCancelled(); stream.readValue(stream, attrs); return }
                check(stream.unsignedLength(), stream.vr() == VR.SQ || stream.length() == -1)
                stream.readValue(stream, attrs)
            }
            override fun readValue(stream: DicomInputStream, seq: Sequence) {
                check(sequence = true); stream.readValue(stream, seq)
            }
            override fun readValue(stream: DicomInputStream, fragments: Fragments) {
                checkCancelled(); stream.readValue(stream, fragments)
            }
            override fun startDataset(stream: DicomInputStream) { checkCancelled() }
            override fun endDataset(stream: DicomInputStream) { checkCancelled() }
        })
    }
}
