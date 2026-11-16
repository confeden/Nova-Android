package com.example.nova

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * Ворота фонового выпуска профилей: ждёт, пока телефону не мешает работа, и только тогда
 * пускает её дальше. Правило — [ProfileIssuePolicy.evaluateGate]; здесь только чтение
 * состояния телефона и ожидание.
 *
 * Выпуск — это регистрация, сканирование точек входа и замер узлов. Он нужен не срочно
 * (на это время работают встроенные семена), поэтому уступает всему: идущему трафику,
 * слабому каналу, роумингу, экономии заряда и трафика. Ожидание не считается неудачей:
 * сердцебиение службы через минуту-две заходит сюда снова.
 *
 * Явный запрос пользователя (кнопка в настройках, выбор Proton) через ворота **не**
 * ходит: он сам ждёт результата и сам выбрал момент.
 */
class ProfileIssueGate(context: Context) {

    private val appContext = context.applicationContext

    /** Состояние без замера трафика — дешёвая часть. */
    fun readStatic(): ProfileIssuePolicy.Environment {
        val cm = runCatching { appContext.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        val network = UnderlyingNetwork.select(appContext)
        val caps = if (cm != null && network != null) cm.getNetworkCapabilities(network) else null
        val power = runCatching { appContext.getSystemService(PowerManager::class.java) }.getOrNull()
        val battery: Intent? = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        return ProfileIssuePolicy.Environment(
            hasNetwork = caps != null,
            powerSave = power?.isPowerSaveMode == true,
            batteryPercent = percent,
            charging = plugged,
            dataSaver = cm != null &&
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            // «Не в роуминге» — свойство сотовой сети; у Wi-Fi признак бессмыслен.
            roaming = cellular && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
            cellular = cellular,
            metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            downKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
        )
    }

    /** Байт/с по всему устройству за [windowMs]; -1, если система счётчика не даёт. */
    fun sampleBytesPerSecond(windowMs: Long = SAMPLE_WINDOW_MS): Long {
        fun total(): Long {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            return if (rx < 0L || tx < 0L) -1L else rx + tx
        }
        val before = total()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            Thread.sleep(windowMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return -1L
        }
        val after = total()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (before < 0L || after < before || elapsed <= 0L) return -1L
        return (after - before) * 1000L / elapsed
    }

    /**
     * Разрешение на фоновый выпуск. Держится до [close]: пока оно у одного вида профилей,
     * остальные ждут своей очереди, а не идут параллельно.
     */
    class Permit internal constructor() : AutoCloseable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        override fun close() {
            if (released.compareAndSet(false, true)) SLOT.release()
        }
    }

    /**
     * Ждёт окно и **очередь**, и возвращает разрешение, либо null.
     *
     * Очередь нужна потому, что ворота открываются у всех трёх выпусков (Cloudflare AWG,
     * MASQUE, Proton) в одну и ту же секунду — после ожидания трафика. Замер на Pixel 4a
     * 2026-11-14 показал именно это: ключ MASQUE, регистрация Cloudflare со сканером и
     * весь прогон Proton пошли одновременно, хотя последовательность выпуска разводит
     * их на 30 с. Теперь одновременно идёт один.
     *
     * @param firstSet у пользователя личного набора ещё нет вовсе.
     * @param stillWanted ложь — туннель погас или пользователь остановил службу.
     * @return разрешение — можно начинать (закрыть в `finally`); null — не дождались,
     *         начинать не надо и неудачей это не считать.
     */
    fun awaitWindow(
        label: String,
        firstSet: Boolean,
        stillWanted: () -> Boolean,
        maxWaitMs: Long = MAX_WAIT_MS,
    ): Permit? {
        if (!awaitQuiet(label, firstSet, stillWanted, maxWaitMs)) return null
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        while (stillWanted()) {
            try {
                if (SLOT.tryAcquire(1, TimeUnit.SECONDS)) return Permit()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                LogManager.log("Выпуск профилей ($label): очередь не дошла за ${maxWaitMs / 60_000} мин — вернёмся позже.")
                return null
            }
        }
        return null
    }

    private fun awaitQuiet(
        label: String,
        firstSet: Boolean,
        stillWanted: () -> Boolean,
        maxWaitMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs
        var lastReason = ""
        var quietSamples = 0
        while (stillWanted()) {
            val static = readStatic()
            var verdict = ProfileIssuePolicy.evaluateGate(static, firstSet)
            if (verdict.allowed) {
                val bytes = sampleBytesPerSecond()
                verdict = ProfileIssuePolicy.evaluateGate(static.copy(bytesPerSecond = bytes), firstSet)
                if (verdict.allowed) {
                    // Две тихие выборки подряд: единичная пауза между двумя загрузками
                    // страницы не повод начинать.
                    quietSamples++
                    if (quietSamples >= QUIET_SAMPLES_NEEDED) {
                        if (lastReason.isNotEmpty()) {
                            LogManager.log("Выпуск профилей ($label): канал свободен — начинаем.")
                        }
                        return true
                    }
                    continue
                }
            }
            quietSamples = 0
            // В журнал — только когда причина сменилась: ожидание тянется минутами (I30).
            if (verdict.reason != lastReason) {
                lastReason = verdict.reason
                LogManager.log("Выпуск профилей ($label): откладываем — ${verdict.reason}.")
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                LogManager.log(
                    "Выпуск профилей ($label): окна не дождались за ${maxWaitMs / 60_000} мин " +
                        "(${verdict.reason}) — вернёмся на следующем сердцебиении."
                )
                return false
            }
            if (!sleepWhile(RECHECK_MS, stillWanted)) return false
        }
        return false
    }

    private fun sleepWhile(totalMs: Long, stillWanted: () -> Boolean): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (!stillWanted()) return false
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            slept += 500L
        }
        return stillWanted()
    }

    companion object {
        const val SAMPLE_WINDOW_MS = 3_000L
        const val RECHECK_MS = 20_000L
        const val MAX_WAIT_MS = 30L * 60_000L
        const val QUIET_SAMPLES_NEEDED = 2

        /** Один фоновый выпуск за раз во всём процессе `:vpn`. */
        private val SLOT = java.util.concurrent.Semaphore(1)
    }
}
