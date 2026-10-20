package ru.medeng.dicomtool.dicom.scu

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A request closes only this operation's socket. Completion is reported by the worker after cleanup. */
class ScuCancellation {
    private val requested = AtomicBoolean(false)
    private val socket = AtomicReference<Socket?>(null)
    val isRequested: Boolean get() = requested.get()
    fun request() { requested.set(true); runCatching { socket.get()?.close() } }
    internal fun attach(value: Socket) {
        socket.set(value)
        if (requested.get()) runCatching { value.close() }
    }
    internal fun detach(value: Socket?) { socket.compareAndSet(value, null) }
}
