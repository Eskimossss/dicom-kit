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
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import ru.medeng.dicomtool.domain.model.ScpServerStatus
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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: RuntimeException) {
                DicomRuntimeRegistry.getScpServer(context).reportServiceError(
                    "Android запретил запуск SCP: ${e.message}. Откройте приложение и повторите запуск вручную.")
            }
        }

        fun stopScpService(context: Context) {
            val intent = Intent(context, DicomScpForegroundService::class.java).apply {
                action = ACTION_STOP_SCP
            }
            try { context.startService(intent) } catch (e: RuntimeException) {
                DicomRuntimeRegistry.getScpServer(context).reportServiceError("Не удалось запросить остановку SCP: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var stopping = false
    @Volatile private var destroyed = false
    private var foreground = false
    private val sessionLimit = Runnable { requestStop("Истёк 4-часовой сеанс SCP. Запустите приём вручную при необходимости.") }
    private val server by lazy { DicomRuntimeRegistry.getScpServer(applicationContext) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCP -> {
                if (stopping || server.state.value.status == ScpServerStatus.RUNNING) return START_NOT_STICKY
                val ae = intent.getStringExtra(EXTRA_AE_TITLE) ?: "ANDROID_PACS"
                val port = intent.getIntExtra(EXTRA_TCP_PORT, 11112)
                try {
                    val notification = buildForegroundNotification(ae, port, "Запуск SCP…")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else startForeground(NOTIFICATION_ID, notification)
                    foreground = true
                } catch (e: RuntimeException) {
                    server.reportServiceError("Android запретил Foreground Service: ${e.message}. Вернитесь в приложение.")
                    requestStop(null)
                    return START_NOT_STICKY
                }
                if (observer == null) observer = scope.launch {
                    server.state.collect { state ->
                        if (!stopping && state.status == ScpServerStatus.RUNNING) {
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,
                                buildForegroundNotification(state.callingAeTitle, state.tcpPort, "SCP слушает порт; сеанс до 4 ч"))
                        }
                    }
                }
                worker.execute {
                    if (stopping || destroyed) return@execute
                    val result = server.startServer(ae, port)
                    main.post {
                        if (result.isFailure) requestStop(null)
                        else if (!stopping && !destroyed) {
                            try {
                                acquireLocks()
                                main.removeCallbacks(sessionLimit)
                                main.postDelayed(sessionLimit, 4 * 60 * 60 * 1000L)
                            } catch (e: RuntimeException) {
                                requestStop("Не удалось удержать блокировки SCP: ${e.message}")
                            }
                        }
                    }
                }
            }
            ACTION_STOP_SCP -> requestStop(null)
            else -> requestStop(null) // No automatic resurrection after process death.
        }
        return START_NOT_STICKY
    }

    private var observer: Job? = null

    private fun requestStop(reason: String?) {
        if (reason != null) server.reportServiceError(reason)
        if (stopping) return
        stopping = true
        server.setServiceStopping(true)
        main.removeCallbacks(sessionLimit)
        worker.execute {
            server.stopServer()
            main.post {
                releaseLocks()
                if (!destroyed && foreground && server.state.value.status == ScpServerStatus.STOPPING) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,
                    buildForegroundNotification(server.state.value.callingAeTitle, server.state.value.tcpPort, "SCP остановлен; ожидание завершения записи"))
            }
            // A SAF operation may ignore interruption. Keep STOPPING until it actually returns.
            while (server.state.value.status == ScpServerStatus.STOPPING) Thread.sleep(100)
            main.post {
                releaseLocks()
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        server.requestNetworkStop()
        requestStop("Android остановил dataSync SCP по лимиту времени. Проверьте результаты незавершённых передач; откройте приложение для ручного запуска.")
        releaseLocks()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // Android requires this promptly; network cleanup continues in the process registry.
    }

    override fun onDestroy() {
        destroyed = true
        server.requestNetworkStop()
        requestStop(null)
        main.removeCallbacks(sessionLimit)
        scope.cancel()
        releaseLocks()
        foreground = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        worker.shutdown() // queued cleanup is retained
        server.setServiceStopping(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DICOMTool:ScpWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L + 30_000L) // до 4 часов непрерывного приёма
                }
            }
        } catch (e: RuntimeException) { throw e }

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
        } catch (e: RuntimeException) { throw e }
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

    private fun buildForegroundNotification(aeTitle: String, port: Int, text: String): Notification {
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
            .setContentTitle("DICOM SCP ($aeTitle:$port)")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", pendingStop)
            .build()
    }
}
