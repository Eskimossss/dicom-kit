package ru.medeng.dicomtool.dicom.scu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Process-local leases stay held until the owning coroutine and its network cleanup complete. */
object ScuOperationGate {
    data class Lease(val owner: String, val operation: String, val id: String = UUID.randomUUID().toString())
    private val current = MutableStateFlow<Lease?>(null)
    val active = current.asStateFlow()
    @Synchronized fun acquire(owner: String, operation: String): Lease? {
        if (current.value != null) return null
        return Lease(owner, operation).also { current.value = it }
    }
    @Synchronized fun release(lease: Lease) {
        if (current.value === lease) current.value = null
    }
}
