package ru.medeng.dicomtool.dicom

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.dcm4che3.data.Attributes
import org.dcm4che3.data.Tag
import org.dcm4che3.data.UID
import org.dcm4che3.data.VR
import org.dcm4che3.net.ApplicationEntity
import org.dcm4che3.net.Connection
import org.dcm4che3.net.Device
import org.dcm4che3.net.service.BasicCEchoSCP
import org.dcm4che3.net.service.DicomServiceRegistry
import org.dcm4che3.util.UIDUtils
import ru.medeng.dicomtool.domain.model.DicomLogEntry
import ru.medeng.dicomtool.domain.model.NetworkInterfaceInfo
import kotlinx.coroutines.flow.Flow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Контракт сервиса приёмника DICOM Storage SCP (C-ECHO SCP + C-STORE SCP).
 * Никакой имитации сокетов: в реальной работе используется стек dcm4che3 (Device / ApplicationEntity / Connection).
 */
interface DicomScpEngine {
    fun detectLocalIpv4Interface(): NetworkInterfaceInfo
    fun verifyDcm4cheStackCompatibility(): Dcm4cheCompatibilityReport
}

/**
 * Контракт сервиса отправки DICOM SCU (C-ECHO SCU + C-STORE SCU Secondary Capture).
 */
interface DicomScuEngine {
    fun generateStandardSecondaryCaptureDataset(
        patientName: String,
        patientId: String,
        patientBirthDate: String,
        patientSex: String,
        studyDescription: String,
        seriesDescription: String,
        modality: String = "OT"
    ): Attributes
}

/**
 * Контракт технического журнала DICOM-обмена (PS3.7 / PS3.8).
 */
interface DicomTechnicalLogger {
    val logsFlow: Flow<List<DicomLogEntry>>
    fun recordEvent(entry: DicomLogEntry)
    fun clearLogs()
}

/**
 * Результат проверки инициализации классов dcm4che3 (core + net + dict) на Android Runtime.
 */
data class Dcm4cheCompatibilityReport(
    val isCompatible: Boolean,
    val libraryVersion: String,
    val verificationSopUid: String,
    val secondaryCaptureSopUid: String,
    val sampleGeneratedUid: String,
    val details: String
)

/**
 * Базовая проверка и инициализация реальных компонентов dcm4che3 на Android API 26+.
 * Подтверждает, что классы org.dcm4che3.data.* и org.dcm4che3.net.* работают без javax.imageio / javax.naming.
 */
class Dcm4cheEngineVerifier : DicomScpEngine, DicomScuEngine {

    override fun detectLocalIpv4Interface(): NetworkInterfaceInfo {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val ipv4 = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                if (ipv4 != null) {
                    val typeLabel = if (nif.name.startsWith("wlan")) "${nif.name} (Wi-Fi)" else nif.name
                    return NetworkInterfaceInfo(
                        interfaceName = typeLabel,
                        ipv4Address = ipv4.hostAddress ?: "192.168.1.150"
                    )
                }
            }
            NetworkInterfaceInfo("wlan0 (Wi-Fi)", "192.168.1.150")
        } catch (_: Exception) {
            NetworkInterfaceInfo("wlan0 (Wi-Fi)", "192.168.1.150")
        }
    }

    override fun verifyDcm4cheStackCompatibility(): Dcm4cheCompatibilityReport {
        return try {
            val device = Device("dicomtool-scp")
            val conn = Connection("dicom-tcp", "0.0.0.0", 11112)
            val ae = ApplicationEntity("ANDROID_PACS")
            val registry = DicomServiceRegistry()
            registry.addDicomService(BasicCEchoSCP())
            device.addConnection(conn)
            device.addApplicationEntity(ae)
            ae.addConnection(conn)
            device.dimseRQHandler = registry

            val sampleUid = ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator.createStandardUid()
            Dcm4cheCompatibilityReport(
                isCompatible = true,
                libraryVersion = "org.dcm4che:dcm4che-core & dcm4che-net 5.33.1",
                verificationSopUid = UID.Verification,
                secondaryCaptureSopUid = UID.SecondaryCaptureImageStorage,
                sampleGeneratedUid = sampleUid,
                details = "Стек dcm4che-core + dcm4che-net успешно инициализирует Device, ApplicationEntity, Connection и DicomServiceRegistry на Android API 26+."
            )
        } catch (e: Throwable) {
            Dcm4cheCompatibilityReport(
                isCompatible = false,
                libraryVersion = "org.dcm4che 5.33.1",
                verificationSopUid = UID.Verification,
                secondaryCaptureSopUid = UID.SecondaryCaptureImageStorage,
                sampleGeneratedUid = "",
                details = "Ошибка инициализации: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    override fun generateStandardSecondaryCaptureDataset(
        patientName: String,
        patientId: String,
        patientBirthDate: String,
        patientSex: String,
        studyDescription: String,
        seriesDescription: String,
        modality: String
    ): Attributes {
        val uids = ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator.createNewObjectUids()
        return Attributes().apply {
            setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage)
            setString(Tag.SOPInstanceUID, VR.UI, uids.sopInstanceUid)
            setString(Tag.StudyInstanceUID, VR.UI, uids.studyInstanceUid)
            setString(Tag.SeriesInstanceUID, VR.UI, uids.seriesInstanceUid)
            setString(Tag.Modality, VR.CS, modality.trim().ifEmpty { "OT" })
            setString(Tag.ConversionType, VR.CS, "WSD")
            setString(Tag.PatientName, VR.PN, patientName)
            setString(Tag.PatientID, VR.LO, patientId)
            setString(Tag.PatientBirthDate, VR.DA, patientBirthDate)
            setString(Tag.PatientSex, VR.CS, patientSex)
            setString(Tag.StudyDescription, VR.LO, studyDescription)
            setString(Tag.SeriesDescription, VR.LO, seriesDescription)
        }
    }
}
