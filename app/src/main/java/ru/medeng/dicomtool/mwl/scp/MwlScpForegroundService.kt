package ru.medeng.dicomtool.mwl.scp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.medeng.dicomtool.MainActivity
import ru.medeng.dicomtool.domain.model.ScpServerStatus
import ru.medeng.dicomtool.mwl.MwlRuntimeRegistry
import java.util.concurrent.Executors

class MwlScpForegroundService : Service() {

    companion object {
        const val ACTION_START_MWL_SCP = "ru.medeng.dicomtool.mwl.action.START_MWL_SCP"
        const val ACTION_STOP_MWL_SCP = "ru.medeng.dicomtool.mwl.action.STOP_MWL_SCP"
        const val EXTRA_AE_TITLE = "extra_ae_title"
        const val EXTRA_TCP_PORT = "extra_tcp_port"

        private const val NOTIFICATION_CHANNEL_ID = "dicom_mwl_scp_channel"
        private const val NOTIFICATION_ID = 11114

        fun startMwlScpService(context: Context, aeTitle: String, port: Int) {
            val intent = Intent(context, MwlScpForegroundService::class.java).apply {
                action = ACTION_START_MWL_SCP
                putExtra(EXTRA_AE_TITLE, aeTitle)
                putExtra(EXTRA_TCP_PORT, port)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                MwlRuntimeRegistry.getMwlScpServer(context).stopServer()
            }
        }

        fun stopMwlScpService(context: Context) {
            val intent = Intent(context, MwlScpForegroundService::class.java).apply {
                action = ACTION_STOP_MWL_SCP
            }
            try {
                context.startService(intent)
            } catch (_: RuntimeException) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lease: MwlLockLease? = null
    private var networkManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = updateNetwork()
    }
    private fun updateNetwork() {
        main.post { if (!destroyed && !stopping) server.refreshNetworkInfo() }
    }
    private val renewLocks = object : Runnable {
        override fun run() {
            if (destroyed || stopping) return
            try { lease?.renew() } catch (_: RuntimeException) { requestStop(); return }
            main.postDelayed(this, MwlLockLease.RENEW_MS)
        }
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var stopping = false
    @Volatile private var destroyed = false
    private var foreground = false
    private var observer: Job? = null

    private val server by lazy { MwlRuntimeRegistry.getMwlScpServer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback); networkManager = connectivity }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MWL_SCP -> {
                if (stopping || server.state.value.status == ScpServerStatus.RUNNING) return START_NOT_STICKY
                val ae = intent.getStringExtra(EXTRA_AE_TITLE) ?: "MWL_SCP"
                val port = intent.getIntExtra(EXTRA_TCP_PORT, 11112)

                try {
                    val notification = buildForegroundNotification(ae, port, "Запуск сервера MWL SCP…")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    foreground = true
                } catch (e: RuntimeException) {
                    requestStop()
                    return START_NOT_STICKY
                }

                if (observer == null) {
                    observer = scope.launch {
                        server.state.collect { state ->
                            if (!stopping && state.status == ScpServerStatus.RUNNING) {
                                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                                manager.notify(
                                    NOTIFICATION_ID,
                                    buildForegroundNotification(state.aeTitle, state.tcpPort, "MWL SCP активен (запросов: ${state.totalRequestsCount})")
                                )
                            }
                        }
                    }
                }

                worker.execute {
                    if (stopping || destroyed) return@execute
                    val result = server.startServer(ae, port)
                    main.post {
                        if (result.isFailure) {
                            requestStop()
                        } else if (!stopping && !destroyed) {
                            acquireLocks()
                        }
                    }
                }
            }
            ACTION_STOP_MWL_SCP -> requestStop()
            else -> requestStop()
        }
        return START_NOT_STICKY
    }

    private fun requestStop() {
        if (stopping) return
        stopping = true
        worker.execute {
            server.stopServer()
            main.post {
                releaseLocks()
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        requestStop()
        releaseLocks()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        requestStop()
        networkManager?.let { runCatching { it.unregisterNetworkCallback(networkCallback) } }
        networkManager = null
        scope.cancel()
        releaseLocks()
        foreground = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        worker.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (lease != null) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DICOMTool:MwlScpWakeLock").apply { setReferenceCounted(false) }
            // API 26-33: HIGH_PERF supports screen-off. API 34+ remaps it to LOW_LATENCY;
            // Android provides no equivalent background Wi-Fi guarantee. See acceptance limits.
            val wifi = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DICOMTool:MwlScpWifiLock")?.apply { setReferenceCounted(false) }
            wakeLock = wake; wifiLock = wifi
            val owned = MwlLockLease({ wake.acquire(it) }, { if (wake.isHeld) wake.release() }, { wifi?.acquire(); Unit }, { if (wifi?.isHeld == true) wifi.release() })
            lease = owned
            owned.start()
            main.postDelayed(renewLocks, MwlLockLease.RENEW_MS)
        } catch (_: RuntimeException) { releaseLocks(); requestStop() }
    }
    private fun releaseLocks() {
        main.removeCallbacks(renewLocks)
        runCatching { lease?.close() }
        lease = null; wakeLock = null; wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Служба DICOM MWL SCP",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновая работа сервера Modality Worklist (MWL SCP)"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(aeTitle: String, port: Int, statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MwlScpForegroundService::class.java).apply {
            action = ACTION_STOP_MWL_SCP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("DICOM Modality Worklist (MWL SCP)")
            .setContentText("AE: $aeTitle · Порт: $port · $statusText")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
