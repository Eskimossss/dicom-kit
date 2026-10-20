package ru.medeng.dicomtool.mwl.scp

/** Renewable bounded CPU lease. Platform handles remain owned by the service. */
internal class MwlLockLease(
    private val acquireWake: (Long) -> Unit,
    private val releaseWake: () -> Unit,
    private val acquireWifi: () -> Unit,
    private val releaseWifi: () -> Unit
) : AutoCloseable {
    companion object { const val TIMEOUT_MS = 120000L; const val RENEW_MS = 60000L }
    private var active = false
    @Synchronized fun start() {
        if (active) return
        active = true
        try { acquireWake(TIMEOUT_MS); acquireWifi() }
        catch (e: Exception) { close(); throw e }
    }
    @Synchronized fun renew() { if (active) acquireWake(TIMEOUT_MS) }
    @Synchronized override fun close() {
        if (!active) return
        active = false
        try { releaseWifi() } finally { releaseWake() }
    }
}
