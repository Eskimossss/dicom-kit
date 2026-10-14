package ru.medeng.dicomtool.data.logging

enum class LoggerWriteState(val labelRu: String) {
    ACTIVE("Запись работает"), DEGRADED("Сбои или потери"), WRITE_FAILED("Ошибка записи"),
    STOPPING("Остановка"), STOPPED("Остановлен")
}

data class LoggerHealth(
    val state: LoggerWriteState = LoggerWriteState.ACTIVE,
    val accepted: Long = 0, val persisted: Long = 0,
    val droppedNormal: Long = 0, val droppedCritical: Long = 0,
    val writeUnconfirmed: Long = 0, val criticalWriteUnconfirmed: Long = 0, val readFailures: Long = 0,
    val rejectedAfterStop: Long = 0, val rejectedCriticalAfterStop: Long = 0, val controlRejected: Long = 0,
    val controlTimeouts: Long = 0, val controlFailures: Long = 0, val queueDepth: Int = 0,
    val rotationPending: Boolean = false, val lastError: String? = null
) {
    val needsAttention: Boolean get() = droppedNormal + droppedCritical + writeUnconfirmed + readFailures +
        rejectedAfterStop + controlRejected + controlTimeouts + controlFailures > 0 || rotationPending || state == LoggerWriteState.WRITE_FAILED || lastError != null
    val warning: String get() = "Журнал: не сохранены / не подтверждены ${droppedNormal + droppedCritical + writeUnconfirmed + rejectedAfterStop}, " +
        "из них критических ${droppedCritical + criticalWriteUnconfirmed + rejectedCriticalAfterStop}. Ошибки чтения: $readFailures. ${state.labelRu}. Подробнее по нажатию."
}
