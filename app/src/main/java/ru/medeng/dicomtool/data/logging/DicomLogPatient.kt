package ru.medeng.dicomtool.data.logging

import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.VR
import ru.medeng.dicomtool.dicom.charset.DicomCharsetDecoder
import ru.medeng.dicomtool.dicom.charset.DicomPersonName
import java.security.MessageDigest

/** Private log metadata. Never include in the default diagnostic export. The key is not anonymization. */
data class DicomLogPatient(val name: String, val identityKey: String) {
    val displayName: String get() = DicomPersonName.display(name).ifBlank { "Не указано" }
    companion object {
        fun from(name: String?, id: String? = null, issuer: String = ""): DicomLogPatient {
            val pn = name.orEmpty().take(2048).filterNot { it.isISOControl() }
            val identity = if (id.isNullOrBlank()) "PN:$pn" else "ID:$issuer:$id"
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return DicomLogPatient(pn, key)
        }
        fun from(ds: Attributes): DicomLogPatient {
            // dcm4che getters can cache decoded strings. Never mutate the object used by a wire writer.
            val snapshot = Attributes(ds)
            return from(DicomCharsetDecoder.decodeStringAttribute(snapshot, Tag.PatientName, VR.PN, "").value,
                DicomCharsetDecoder.decodeStringAttribute(snapshot, Tag.PatientID, VR.LO, "").value,
                DicomCharsetDecoder.decodeStringAttribute(snapshot, Tag.IssuerOfPatientID, VR.LO, "").value)
        }
    }
}
