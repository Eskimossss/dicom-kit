package ru.medeng.dicomtool.dicom

import android.content.Context
import ru.medeng.dicomtool.data.logging.PersistentDicomTechnicalLogger
import ru.medeng.dicomtool.data.storage.LocalDicomStudyRepository
import ru.medeng.dicomtool.dicom.scp.DicomStorageScpServer
import ru.medeng.dicomtool.dicom.scu.DicomScuClient
import ru.medeng.dicomtool.dicom.scu.DicomSecondaryCaptureGenerator
import java.io.File

/**
 * Единый локальный реестр сервисов приложения (SCP-сервер, SCU-клиент, локальное хранилище исследований и персистентный технический журнал).
 * Позволяет ForegroundService и ViewModel обращаться к одному экземпляру работающего DICOM SCP-сервера и SCU-клиента.
 */
object DicomRuntimeRegistry {

    @Volatile
    private var studyRepositoryInstance: LocalDicomStudyRepository? = null

    @Volatile
    private var technicalLoggerInstance: PersistentDicomTechnicalLogger? = null

    @Volatile
    private var scpServerInstance: DicomStorageScpServer? = null

    @Volatile
    private var scuClientInstance: DicomScuClient? = null

    @Synchronized
    fun getStudyRepository(context: Context): LocalDicomStudyRepository {
        return studyRepositoryInstance ?: run {
            val appCtx = context.applicationContext
            val dir = File(appCtx.filesDir, "received_dicom")
            val storageManager = ru.medeng.dicomtool.data.storage.DicomStorageManager(appCtx, dir)
            LocalDicomStudyRepository(
                storageRootDir = dir,
                context = appCtx,
                storageManager = storageManager,
                eagerLoad = false
            ).also { studyRepositoryInstance = it }
        }
    }

    @Synchronized
    fun getTechnicalLogger(context: Context): PersistentDicomTechnicalLogger {
        return technicalLoggerInstance ?: run {
            val logFile = File(context.applicationContext.filesDir, "logs/dicom_technical_log.tsv")
            PersistentDicomTechnicalLogger(logFile, deferHistoryLoad = true).also { technicalLoggerInstance = it }
        }
    }

    @Synchronized
    fun getScpServer(context: Context): DicomStorageScpServer {
        return scpServerInstance ?: run {
            val repo = getStudyRepository(context)
            val logger = getTechnicalLogger(context)
            DicomStorageScpServer(repo, logger).also {
                val prefs = context.applicationContext.getSharedPreferences("scp_configuration", Context.MODE_PRIVATE)
                it.updateConfiguration(prefs.getString("ae", "ANDROID_PACS") ?: "ANDROID_PACS", prefs.getInt("port", 11112))
                scpServerInstance = it
            }
        }
    }

    @Synchronized
    fun getScuClient(context: Context): DicomScuClient {
        return scuClientInstance ?: run {
            val logger = getTechnicalLogger(context)
            val scuCacheDir = File(context.applicationContext.cacheDir, "scu_temp")
            DicomScuClient(logger, scuCacheDir).also { scuClientInstance = it }
        }
    }

    private var archiveInstance: ru.medeng.dicomtool.data.storage.ArchiveOperations? = null

    @Synchronized
    fun getArchiveOperations(context: Context): ru.medeng.dicomtool.data.storage.ArchiveOperations =
        archiveInstance ?: ru.medeng.dicomtool.data.storage.ArchiveOperations(getStudyRepository(context), getScpServer(context))
            .also { archiveInstance = it }

    fun clearScuTempCache(context: Context) {
        val scuCacheDir = File(context.applicationContext.cacheDir, "scu_temp")
        DicomSecondaryCaptureGenerator.clearTempCache(scuCacheDir)
    }
}
