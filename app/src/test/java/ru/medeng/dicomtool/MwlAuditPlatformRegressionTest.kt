package ru.medeng.dicomtool

import org.junit.Assert.*
import org.junit.Test
import ru.medeng.dicomtool.mwl.scp.MwlLockLease

class MwlAuditPlatformRegressionTest {
    @Test fun audit14_cpuLeaseRenewsPastFourHoursAndStopReleasesOnce() {
        var time = 0L; var expiry = 0L; var wifi = 0; var releases = 0
        val lease = MwlLockLease({ expiry = time + it }, { expiry = 0; releases++ }, { wifi++ }, { wifi-- })
        lease.start(); lease.start()
        repeat(300) { time += MwlLockLease.RENEW_MS; assertTrue(expiry > time); lease.renew() }
        assertTrue(time > 4 * 60 * 60 * 1000L)
        assertEquals(1, wifi)
        lease.close(); lease.close(); lease.renew()
        assertEquals(0, wifi); assertEquals(0L, expiry); assertEquals(1, releases)
    }
    @Test fun audit14_partialAcquisitionFailureReleasesCpuLease() {
        var held = false
        val lease = MwlLockLease({ held = true }, { held = false }, { throw IllegalStateException("wifi unavailable") }, {})
        assertThrows(IllegalStateException::class.java) { lease.start() }
        assertFalse(held)
        lease.close(); lease.renew(); assertFalse(held)
    }
}
