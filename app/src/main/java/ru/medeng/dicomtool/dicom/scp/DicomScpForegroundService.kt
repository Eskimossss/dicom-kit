package ru.medeng.dicomtool.dicom.scp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import ru.medeng.dicomtool.MainActivity
import ru.medeng.dicomtool.dicom.DicomRuntimeRegistry

/**
 * Android Foreground Service для поддержания работы DICOM Storage SCP при выключенном экране.
 *
 * - Показывает постоянное уведомление с текущим AE Title и TCP-портом.
 * - Удерживает PARTIAL_WAKE_LOCK и WifiLock на время работы сервера.
 * - Освобождает сокет и ресурсы при остановке сервиса.
 */
class DicomScpForegroundService : Service() {

    companion object {
        const val ACTION_START_SCP = "ru.medeng.dicomtool.action.START_SCP"
        const val ACTION_STOP_SCP = "ru.medeng.dicomtool.action.STOP_SCP"
        const val EXTRA_AE_TITLE = "extra_ae_title"
        const val EXTRA_TCP_PORT = "extra_tcp_port"

        private const val NOTIFICATION_CHANNEL_ID = "dicom_scp_channel"
        private const val NOTIFICATION_ID = 11112

        fun startScpService(context: Context, aeTitle: String, port: Int) {
            val intent = Intent(context, DicomScpForegroundService::class.java).apply {
                action = ACTION_START_SCP
                putExtra(EXTRA_AE_TITLE, aeTitle)
                putExtra(EXTRA_TCP_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopScpService(context: Context) {
            val intent = Intent(context, DicomScpForegroundService::class.java).apply {
                action = ACTION_STOP_SCP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCP -> {
                val aeTitle = intent.getStringExtra(EXTRA_AE_TITLE) ?: "ANDROID_PACS"
                val port = intent.getIntExtra(EXTRA_TCP_PORT, 11112)
                val notification = buildForegroundNotification(aeTitle, port)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                acquireLocks()
                val scpServer = DicomRuntimeRegistry.getScpServer(applicationContext)
                val result = scpServer.startServer(aeTitle, port)
                if (result.isFailure) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_STOP_SCP -> {
                val scpServer = DicomRuntimeRegistry.getScpServer(applicationContext)
                scpServer.stopServer()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val scpServer = DicomRuntimeRegistry.getScpServer(applicationContext)
        scpServer.stopServer()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DICOMTool:ScpWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // до 4 часов непрерывного приёма
                }
            }
        } catch (_: Exception) {
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "DICOMTool:ScpWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "DICOM Storage SCP Сервер",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление активного DICOM приёмника (C-ECHO / C-STORE SCP)"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(aeTitle: String, port: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DicomScpForegroundService::class.java).apply {
            action = ACTION_STOP_SCP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("DICOM SCP запущен ($aeTitle:$port)")
            .setContentText("Ожидание входящих соединений C-ECHO и C-STORE")
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", pendingStop)
            .build()
    }
}
