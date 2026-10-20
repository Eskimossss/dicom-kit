package ru.medeng.dicomtool.dicom.scu

import org.dcm4che3.data.UID
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream

/** Opens the original encoded dataset. Never copy from DicomInputStream: it inflates Deflated. */
internal object OriginalDicomDataset {
    fun open(input: InputStream, expectedSyntax: String): InputStream {
        val stream = PushbackInputStream(BufferedInputStream(input, 65536), 132)
        try {
            val prefix = stream.readUpTo(132)
            if (prefix.size < 132 || String(prefix, 128, 4, Charsets.US_ASCII) != "DICM") {
                stream.unread(prefix)
                require(expectedSyntax in listOf(UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian,
                    UID.ExplicitVRBigEndian)) { "Raw DICOM: Transfer Syntax cannot be safely confirmed" }
                return stream
            }
            var metadataBytes = 0L
            var groupLength: Long? = null
            var syntax: String? = null
            while (true) {
                val tag = stream.readUpTo(4)
                require(tag.size == 4) { "Missing encoded dataset after File Meta Information" }
                if (u16(tag, 0) != 2) { stream.unread(tag); break }
                val vr = stream.readUpTo(2)
                require(vr.size == 2) { "Truncated FMI VR" }
                val vrName = String(vr, Charsets.US_ASCII)
                require(vrName in setOf("UL", "OB", "UI", "SH", "AE", "UT", "UR")) { "Unsupported FMI VR: $vrName" }
                val longLength = vrName in setOf("OB", "UT", "UR")
                val lengthBytes = stream.readUpTo(if (longLength) 6 else 2)
                require(lengthBytes.size == (if (longLength) 6 else 2)) { "Truncated FMI length" }
                if (longLength) require(lengthBytes[0] == 0.toByte() && lengthBytes[1] == 0.toByte()) { "Invalid FMI reserved bytes" }
                val length = if (longLength) u32(lengthBytes, 2) else u16(lengthBytes, 0).toLong()
                require(length in 0..1_048_576 && length % 2 == 0L && metadataBytes + length <= 1_048_576) { "Unsafe FMI length" }
                val value = stream.readUpTo(length.toInt())
                require(value.size.toLong() == length) { "Truncated FMI value" }
                val element = u16(tag, 2)
                if (element == 0) {
                    require(metadataBytes == 0L && vrName == "UL" && length == 4L) { "Invalid FMI group length element" }
                    groupLength = u32(value, 0)
                }
                if (element == 0x10) {
                    require(vrName == "UI" && syntax == null) { "Invalid or duplicate Transfer Syntax UID" }
                    syntax = String(value, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                }
                metadataBytes += 4 + 2 + lengthBytes.size + length
            }
            require(groupLength == null || groupLength == metadataBytes - 12) { "FMI group length does not match encoded boundary; original retained" }
            require(syntax == expectedSyntax) { "Transfer Syntax changed or does not match negotiated syntax: $syntax / $expectedSyntax" }
            return stream
        } catch (e: Exception) { stream.close(); throw IOException(e.message, e) }
    }

    fun copy(input: InputStream, out: OutputStream, cancelled: () -> Boolean, bytes: (Long) -> Unit) {
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            if (cancelled()) throw IOException("Передача отменяется; результат на PACS неизвестен")
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            out.write(buffer, 0, read)
            count += read
            bytes(count)
        }
        require(count > 0) { "Empty encoded dataset" }
    }

    private fun InputStream.readUpTo(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(buffer, offset, size - offset)
            if (count < 0) break
            if (count > 0) offset += count
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int) = (0..3).fold(0L) { n, i -> n or ((bytes[offset + i].toLong() and 255) shl (i * 8)) }
}
