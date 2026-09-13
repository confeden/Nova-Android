package com.example.nova

import android.graphics.Bitmap

/**
 * Построенные узоры поверхности — общий кэш на процесс.
 *
 * ## Почему он вынесен из [NovaAppearanceDrawable]
 *
 * Сначала кэш жил внутри самого слоя: узор нужен был только экранам настроек, и
 * заводить под него отдельное имя было незачем. Потом владелец попросил, чтобы
 * фон главного экрана в режиме «изображение» брал **ту же** фактуру, что и
 * настройки. Главный экран темы не применяет и слоя не создаёт — он собирает
 * растр сам ([NovaMainBackdrop]), — и кэш внутри слоя означал бы второй такой же
 * растр в памяти и второе построение на те же полсекунды.
 *
 * ## Что в ключе и чего в нём нет
 *
 * Ключ — (фактура, ширина, высота, пересев). Цвета в нём нет намеренно: растр
 * хранит только прозрачность, а цвет ему даёт `SRC_IN` уже на отрисовке. Поэтому
 * один и тот же узор годится всем двенадцати темам, смена темы кэш не сбрасывает,
 * и настройки с главным экраном делят один растр, даже если красят его по-разному.
 *
 * ## Почему предел — два растра
 *
 * Экран «Оформление» показывает выбор сразу, то есть пересоздаёт себя на каждое
 * касание чипа. Без предела человек, перебравший весь список, унёс бы в памяти
 * шестнадцать полноэкранных растров. Двух хватает: текущий и предыдущий — то
 * есть «посмотрел и вернулся» не стоит ничего.
 */
internal object NovaTextureCache {

    /**
     * Наибольшая сторона построенного узора в точках.
     *
     * 1440 — это примерно две трети высоты современного телефона. Растяжение в
     * полтора раза под вуалью в 22 % не видно, а вчетверо меньшая цена построения
     * и памяти — видна сразу.
     */
    private const val MAX_SIDE = 1440

    private const val CACHE_LIMIT = 2

    data class Key(
        val texture: NovaAppearance.Texture,
        val width: Int,
        val height: Int,
        /** Счётчик пересевов: другой вариант — другой узор той же фактуры. */
        val variant: Int,
    )

    /** Ключ под запрошенный размер, уже урезанный до [MAX_SIDE]. */
    fun keyFor(
        texture: NovaAppearance.Texture,
        width: Int,
        height: Int,
        variant: Int,
    ): Key {
        val longest = if (width > height) width else height
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        return Key(
            texture,
            (width * scale).toInt().coerceAtLeast(8),
            (height * scale).toInt().coerceAtLeast(8),
            variant,
        )
    }

    /**
     * Порядок доступа, а не вставки: «посмотрел новую фактуру и вернулся к
     * прежней» не должен стоить повторного построения.
     */
    private val cache = object : LinkedHashMap<Key, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean =
            size > CACHE_LIMIT
    }

    /**
     * Заказы, которые сейчас строятся, вместе с теми, кто их ждёт.
     *
     * Список, а не флаг: экран настроек и главный экран могут попросить один и тот
     * же узор раньше, чем он готов, и второму тоже надо ответить. По флагу второй
     * молча не получил бы ничего и остался бы без фактуры до пересоздания.
     */
    private val waiting = HashMap<Key, MutableList<(Bitmap?) -> Unit>>()

    /** Заказы, построение которых не удалось: повторять их незачем. */
    private val failed = HashSet<Key>()

    /**
     * Отдаёт готовый узор или заказывает построение в фоне.
     *
     * Синхронно строить нельзя: самый подробный узор — это доли секунды, то есть
     * десятки пропущенных кадров ровно в тот момент, когда человек нажал на
     * фактуру. Поэтому первый кадр идёт без узора, а готовый приезжает вызовом
     * `onReady` — **из фонового потока**, так что трогать виды в нём нельзя.
     */
    fun request(key: Key, onReady: (Bitmap?) -> Unit): Bitmap? {
        if (key.texture == NovaAppearance.Texture.NONE) return null
        synchronized(cache) {
            cache[key]?.let { return it }
            if (failed.contains(key)) return null
            val queue = waiting.getOrPut(key) { ArrayList(2) }
            queue.add(onReady)
            if (queue.size > 1) return null
        }
        Thread({
            val started = android.os.SystemClock.elapsedRealtime()
            val built = runCatching {
                NovaTexturePatterns.render(key.texture, key.width, key.height, key.variant)
            }.getOrNull()
            val callbacks: List<(Bitmap?) -> Unit>
            synchronized(cache) {
                if (built != null) cache[key] = built else failed.add(key)
                callbacks = waiting.remove(key).orEmpty()
            }
            LogManager.log(
                "Оформление: узор ${key.texture.title} ${key.width}×${key.height} " +
                    "построен за ${android.os.SystemClock.elapsedRealtime() - started} мс."
            )
            for (callback in callbacks) callback(built)
        }, "NovaTexture").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        return null
    }
}
