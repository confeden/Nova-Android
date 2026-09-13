package com.example.nova

import kotlin.random.Random

/**
 * Раскладка созвездия главного экрана — одна на два вида.
 *
 * Зачем это отдельный объект. Точки рисуют двое: [TronRingsView] подсвечивает их
 * волнами во время подключения, а [NovaNetworkBackgroundView] показывает и двигает
 * их после. Раскладку каждый строил свою — своё зерно, своё число точек, свой
 * разброс радиусов, — и снаружи это выглядело так: во время подключения волны
 * зажигают одно созвездие, а подключившись, человек видит на экране совершенно
 * другое. Одна раскладка на оба вида убирает этот скачок по построению.
 *
 * Каноническими взяты параметры фона, а не колец: именно фон остаётся на экране
 * после подключения, и подсветка обязана быть его предпросмотром, а не наоборот.
 *
 * Зерно фиксированное, поэтому раскладка одинакова и между запусками: узор экрана
 * — часть облика приложения, и «каждый раз новый» здесь читался бы как дребезг.
 */
object NovaConstellation {

    /** Узел: положение, размер, фаза пульсации и скорость дрейфа. */
    data class Point(
        val x: Float,
        val y: Float,
        val radius: Float,
        val phase: Float,
        val vx: Float,
        val vy: Float,
    )

    private const val SEED = 0x4E4F5641

    /**
     * Строит раскладку для поля `w`×`h`.
     *
     * Число точек зависит только от ориентации: в портрете их чуть меньше, потому
     * что связи считаются по расстоянию, а в узком поле соседи ближе и сетка
     * получается гуще при том же количестве.
     */
    fun build(w: Int, h: Int, density: Float): List<Point> {
        if (w <= 0 || h <= 0) return emptyList()
        val random = Random(SEED)
        val count = if (w < h) 30 else 38
        return List(count) {
            val speed = (0.09f + random.nextFloat() * 0.18f) * density
            val angle = random.nextFloat() * (Math.PI.toFloat() * 2f)
            Point(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                radius = (0.85f + random.nextFloat() * 1.55f) * density,
                phase = random.nextFloat() * Math.PI.toFloat() * 2f,
                vx = kotlin.math.cos(angle) * speed,
                vy = kotlin.math.sin(angle) * speed,
            )
        }
    }
}
