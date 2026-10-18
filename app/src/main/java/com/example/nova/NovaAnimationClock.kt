package com.example.nova

/**
 * Общие часы анимаций фона: монотонное время, которое идёт только тогда, когда главный
 * экран реально показывает анимацию.
 *
 * ## Почему часы, а не время с начала показа
 *
 * Раньше анимация отсчитывала время от момента присоединения вида. Уход в фон или
 * пересоздание активности сбрасывали её в ноль. Здесь время накапливается, пока часы
 * запущены, и стоит, пока их остановили ([resume] / [suspend]). Главный экран
 * останавливает их в `onPause` и запускает в `onResume`, поэтому открытые поверх
 * настройки и прочие экраны паузу не считают.
 *
 * Часы живут в процессе, а не в виде: пересоздание активности (поворот, смена языка)
 * не сбрасывает их. Состояние анимации тоже выводится из этого времени, см.
 * [NovaMatrixRain], поэтому ничего не нужно переносить при пересоздании.
 */
internal object NovaAnimationClock {

    private val clock = AnimationClock { System.nanoTime() }

    /** Время анимаций в секундах; продвигается только пока часы запущены. */
    fun now(): Double = clock.now()

    /** Время без продвижения: для кадров, которые рисуются статично (экраны настроек). */
    fun peek(): Double = clock.peek()

    fun isRunning(): Boolean = clock.isRunning()

    /** Запускает часы: вызывается, когда главный экран снова на переднем плане. */
    fun resume() = clock.resume()

    /** Останавливает часы: вызывается, когда главный экран ушёл с переднего плана. */
    fun suspend() = clock.suspend()
}

/**
 * Сами часы с подменяемым источником времени, чтобы паузу проверяли юнит-тесты.
 *
 * Разрыв между вызовами длиннее [PAUSE_GAP_NANOS] — тоже пауза (вид спрятан без
 * явной остановки). Такой промежуток не добавляется вовсе.
 */
internal class AnimationClock(private val nanoTime: () -> Long) {

    private val lock = Any()
    private var running = false
    private var lastNanos = Long.MIN_VALUE
    private var seconds = 0.0

    fun now(): Double = synchronized(lock) {
        if (running) advanceLocked()
        seconds
    }

    fun peek(): Double = synchronized(lock) { seconds }

    fun isRunning(): Boolean = synchronized(lock) { running }

    fun resume() = synchronized(lock) {
        running = true
        // Отсчёт идёт от момента запуска, а не от последнего кадра до паузы.
        lastNanos = nanoTime()
    }

    fun suspend() = synchronized(lock) {
        if (running) advanceLocked()
        running = false
        lastNanos = Long.MIN_VALUE
    }

    private fun advanceLocked() {
        val nowNanos = nanoTime()
        if (lastNanos != Long.MIN_VALUE) {
            val gap = nowNanos - lastNanos
            if (gap in 0 until PAUSE_GAP_NANOS) seconds += gap / 1e9
        }
        lastNanos = nowNanos
    }

    companion object {
        /** Разрыв между вызовами, больше которого считаем паузой. */
        const val PAUSE_GAP_NANOS = 250_000_000L
    }
}

/**
 * Состояние фоновых анимаций, которое должно пережить пересоздание вида.
 *
 * Вид может умереть вместе с активностью, пока процесс жив (система забирает
 * фоновую активность), и тогда его фаза и зерно обнулялись. Здесь держатся только
 * опорные точки во времени общих часов и зёрна, а сама фаза выводится из часов.
 */
internal object NovaAnimationMemory {

    private val lock = Any()
    private var tronStart: Double? = null
    private var networkLayout: String? = null
    private var networkOrigin = 0.0
    private val seeds = KeyedSeeds()

    /** Начало волн подключения: берётся существующее, новое заводится только без него. */
    fun tronStartOrBegin(now: Double): Double = synchronized(lock) {
        tronStart ?: now.also { tronStart = it }
    }

    /** Явный перезапуск волн: отсчёт снова от [now]. */
    fun tronRestart(now: Double): Double = synchronized(lock) {
        now.also { tronStart = it }
    }

    /** Подключения нет: следующая волна начнётся заново. */
    fun tronStop() = synchronized(lock) { tronStart = null }

    /**
     * Точка отсчёта движения узлов. Меняется только вместе с раскладкой (размером),
     * поэтому пересоздание вида с тем же размером продолжает движение.
     */
    fun networkOrigin(layout: String, now: Double): Double = synchronized(lock) {
        if (networkLayout != layout) {
            networkLayout = layout
            networkOrigin = now
        }
        networkOrigin
    }

    /** Зерно узора по ключу. [reuse] — взять сохранённое, если оно есть. */
    fun frostSeed(key: String, reuse: Boolean, make: () -> Long): Long = seeds.seed(key, reuse, make)
}

/** Зёрна по ключу на процесс. Класс отдельно, чтобы логику проверяли юнит-тесты. */
internal class KeyedSeeds {

    private val lock = Any()
    private val map = HashMap<String, Long>()

    fun seed(key: String, reuse: Boolean, make: () -> Long): Long = synchronized(lock) {
        if (reuse) {
            map[key]?.let { return it }
        }
        make().also { map[key] = it }
    }
}
