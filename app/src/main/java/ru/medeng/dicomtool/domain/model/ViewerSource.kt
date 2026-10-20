package ru.medeng.dicomtool.domain.model

/** The active viewport has exactly one source; a remembered external picker is not precedence. */
sealed interface ViewerSource {
    data object None : ViewerSource
    data class Received(val studyInstanceUid: String) : ViewerSource
    data class External(val uri: String) : ViewerSource
}

/** Identity guards apply even when a provider ignores cancellation. */
class LatestViewerRequest {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = generation.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
    fun check(ticket: Long) {
        if (!isCurrent(ticket)) throw java.util.concurrent.CancellationException("Устаревшая загрузка Viewer")
    }
}
