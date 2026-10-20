package ru.medeng.dicomtool.mwl

import android.content.Context
import ru.medeng.dicomtool.mwl.log.MwlTechnicalLogger
import ru.medeng.dicomtool.mwl.repo.LocalMwlRepository
import ru.medeng.dicomtool.mwl.repo.MwlRepository
import ru.medeng.dicomtool.mwl.repo.MwlScuProfileRepository
import ru.medeng.dicomtool.mwl.scp.MwlScpServer
import ru.medeng.dicomtool.mwl.scu.MwlScuClient
import java.io.File

object MwlRuntimeRegistry {

    @Volatile
    private var repositoryInstance: MwlRepository? = null

    @Volatile
    private var profileRepositoryInstance: MwlScuProfileRepository? = null

    @Volatile
    private var loggerInstance: MwlTechnicalLogger? = null

    @Volatile
    private var scpServerInstance: MwlScpServer? = null

    @Volatile
    private var scuClientInstance: MwlScuClient? = null

    @Synchronized
    fun getMwlRepository(context: Context): MwlRepository {
        return repositoryInstance ?: run {
            val dir = File(context.applicationContext.filesDir, "mwl_data")
            LocalMwlRepository(dir, autoSeedIfEmpty = true).also { repositoryInstance = it }
        }
    }

    @Synchronized
    fun getProfileRepository(context: Context): MwlScuProfileRepository {
        return profileRepositoryInstance ?: run {
            val dir = File(context.applicationContext.filesDir, "mwl_data")
            MwlScuProfileRepository(dir).also { profileRepositoryInstance = it }
        }
    }

    @Synchronized
    fun getMwlLogger(context: Context): MwlTechnicalLogger {
        return loggerInstance ?: run {
            val file = File(context.applicationContext.filesDir, "logs/mwl_technical_log.tsv")
            MwlTechnicalLogger(file).also { loggerInstance = it }
        }
    }

    @Synchronized
    fun getMwlScpServer(context: Context): MwlScpServer {
        return scpServerInstance ?: run {
            val repo = getMwlRepository(context)
            val logger = getMwlLogger(context)
            MwlScpServer(repo, logger).also {
                val prefs = context.applicationContext.getSharedPreferences("mwl_scp_config", Context.MODE_PRIVATE)
                val ae = prefs.getString("ae", "MWL_SCP") ?: "MWL_SCP"
                val port = prefs.getInt("port", 11112)
                val charset = prefs.getString("charset", "ISO_IR 192") ?: "ISO_IR 192"
                val whitelist = prefs.getBoolean("whitelist_mode", false)
                val aets = prefs.getStringSet("allowed_aets", emptySet())?.toList() ?: emptyList()
                val ips = prefs.getStringSet("allowed_ips", emptySet())?.toList() ?: emptyList()
                it.updateConfiguration(
                    aeTitle = ae,
                    port = port,
                    whitelistMode = whitelist,
                    allowedCallingAets = aets,
                    allowedIps = ips,
                    characterSet = charset
                )
                scpServerInstance = it
            }
        }
    }

    @Synchronized
    fun getMwlScuClient(context: Context): MwlScuClient {
        return scuClientInstance ?: run {
            val logger = getMwlLogger(context)
            MwlScuClient(logger).also { scuClientInstance = it }
        }
    }

    fun saveMwlScpConfig(
        context: Context,
        ae: String,
        port: Int,
        charset: String,
        whitelistMode: Boolean,
        allowedAets: List<String>,
        allowedIps: List<String>
    ) {
        val prefs = context.applicationContext.getSharedPreferences("mwl_scp_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("ae", ae)
            .putInt("port", port)
            .putString("charset", charset)
            .putBoolean("whitelist_mode", whitelistMode)
            .putStringSet("allowed_aets", allowedAets.toSet())
            .putStringSet("allowed_ips", allowedIps.toSet())
            .apply()

        scpServerInstance?.updateConfiguration(
            aeTitle = ae,
            port = port,
            characterSet = charset,
            whitelistMode = whitelistMode,
            allowedCallingAets = allowedAets,
            allowedIps = allowedIps
        )
    }
}
