package ru.medeng.dicomtool.data.logging

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Менеджер уникальных идентификаторов сеансов DICOM (SCU и SCP).
 *
 * Решает проблему коллизий сессий после перезапуска приложения:
 * - Генерирует глобально уникальный `runId` для текущего процесса приложения (метка времени base36 + случайный суффикс).
 * - Предоставляет короткие, привычные человеку обозначения для отображения в интерфейсе (`SCU-1`, `SCP-1`).
 * - Связывает их с глобально уникальным внутренним `sessionUid` (например, `SCU-R-lh7y2q-a1b2-1`), который не повторяется
 *   между запусками и позволяет безошибочно различать сеансы в накопленной истории журнала.
 */
object DicomSessionManager {

    /**
     * Уникальный идентификатор запуска текущего процесса приложения.
     * Не полагается на секундную точность: использует System.currentTimeMillis() в base36 и полный UUID.
     */
    val appRunId: String = generateAppRunId()

    private val scuCounter = AtomicInteger(0)
    private val scpCounter = AtomicInteger(0)

    private fun generateAppRunId(): String {
        val timePart = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val randPart = UUID.randomUUID().toString().replace("-", "").take(32)
        return "R$timePart-$randPart"
    }

    /**
     * Идентификатор сеанса:
     * @property displayLabel Короткое имя для UI (например, "SCU-1", "SCP-2").
     * @property sessionUid Глобально уникальный ID сеанса (например, "SCU-Rlh7y2q-a1b2-1").
     */
    data class SessionIdentity(
        val displayLabel: String,
        val sessionUid: String
    )

    /**
     * Генерирует идентификатор для нового сеанса исходящего обмена (SCU).
     */
    fun nextScuSession(): SessionIdentity {
        val num = scuCounter.incrementAndGet()
        return SessionIdentity(
            displayLabel = "SCU-$num",
            sessionUid = "SCU-$appRunId-$num"
        )
    }

    /**
     * Генерирует идентификатор для сеанса входящего обмена (SCP).
     * @param serialNo Внутренний серийный номер ассоциации dcm4che (если доступен).
     */
    @Suppress("UNUSED_PARAMETER") // Legacy callers may supply serialNo; it is never an identity.
    fun nextScpSession(serialNo: Int? = null): SessionIdentity {
        val num = scpCounter.incrementAndGet()
        return SessionIdentity(
            displayLabel = "SCP-$num",
            sessionUid = "SCP-$appRunId-$num"
        )
    }

    /**
     * Генерирует идентификатор сеанса для TCP-события входящего подключения (до согласования ассоциации).
     */
    @Suppress("UNUSED_PARAMETER") // Source ports may be reused or match on different hosts.
    fun scpTcpSession(remotePort: Int): SessionIdentity {
        return nextScpSession()
    }

    /**
     * Сброс локальных счетчиков сессий для изоляции тестовых прогонов.
     */
    internal fun resetForTesting() {
        scuCounter.set(0)
        scpCounter.set(0)
    }
}
