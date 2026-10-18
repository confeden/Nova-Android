package com.example.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Узоры поверхности: один сплошной фрактальный рисунок во весь экран.
 *
 * ## Что здесь изменилось и почему
 *
 * Раньше узор строился на плитке 256×256 и размножался `BitmapShader`'ом. Всё
 * устройство тех узоров подчинялось одному требованию — бесшовности: частоты
 * шума обязаны были быть целыми, примитивы рисовались по девять раз со
 * смещениями, расстояния считались по тору. Плата за это видна глазом: на экране
 * шириной 1080 точек плитка укладывается четырежды, и рисунок читается как
 * мозаика из одинаковых квадратов, а не как поверхность. Владелец так и сообщил:
 * «просто повторение заполнения, а квадраты маленькие».
 *
 * Теперь узор строится сразу во весь экран и повторять его не нужно. Отсюда три
 * следствия, и каждое — выигрыш:
 *
 * 1. **Периодичность больше не ограничивает.** Частоты стали дробными, решётки
 *    октав намеренно расходятся (`LACUNARITY` 2.03, а не 2), домен искривляется
 *    без оглядки на край плитки. Именно эти три запрета и делали прежний рисунок
 *    «мозаичным».
 * 2. **Шум стал градиентным, а не значенческим.** У значенческого шума экстремумы
 *    сидят в узлах решётки, и на больших площадях проступает клетка. Градиентный
 *    (Перлина) держит в узлах нули, а значение набирает между ними — клетки нет.
 * 3. **Деталь появилась на всех масштабах сразу.** Крупная форма занимает пол-экрана,
 *    мелкая — единицы точек, и между ними непрерывная лестница октав. Это и есть
 *    «фрактально, а не мозаично».
 *
 * ## Единицы
 *
 * Узор описывается не в точках, а в **единицах узора**: короткая сторона экрана
 * равна 1.0. Поэтому «частота 2.4» значит «две с половиной крупных формы поперёк
 * экрана» на любом устройстве, и рисунок не мельчает на планшете. Всё, что
 * рисуется кистью, тоже задаётся в этих единицах — холст масштабируется один раз
 * (см. [Sheet.drawing]).
 *
 * ## Чем платится
 *
 * Построением, и только им: готовый рисунок кладётся в кэш
 * ([NovaAppearanceDrawable]) и дальше стоит одну заливку растром в кадр. Само
 * построение раскладывается по всем ядрам полосами строк ([fill]) — иначе
 * миллион точек с восемью октавами занял бы секунды вместо долей.
 */
internal object NovaTexturePatterns {

    /**
     * Строит узор размером `width`×`height`.
     *
     * Рисунок белый, а вся картинка — в альфе: цвет ему даёт акцент темы через
     * `PorterDuff.SRC_IN`, поэтому одна и та же поверхность годится всем
     * двенадцати темам.
     *
     * Зерно случайности прибито к виду фактуры и к [variant]: один и тот же
     * «Базальт» обязан выглядеть одинаково до и после перезапуска, иначе кэш был
     * бы виден как подмена рисунка на ровном месте. `variant` — это счётчик
     * пересевов: повторное касание уже выбранной фактуры увеличивает его, и узор
     * строится заново, оставаясь той же фактурой.
     */
    fun render(
        texture: NovaAppearance.Texture,
        width: Int,
        height: Int,
        variant: Int,
    ): Bitmap? {
        if (texture == NovaAppearance.Texture.NONE) return null
        if (width < 8 || height < 8) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val seed = 0x4E4F5641L + texture.ordinal * 7919L + variant * 6700417L
        val sheet = Sheet(bitmap, Random(seed))
        when (texture) {
            NovaAppearance.Texture.NONE -> Unit
            NovaAppearance.Texture.BASALT -> basalt(sheet)
            NovaAppearance.Texture.MARBLE -> marble(sheet)
            NovaAppearance.Texture.WOOD -> wood(sheet)
            NovaAppearance.Texture.SAND -> sand(sheet)
            NovaAppearance.Texture.GRASS -> grass(sheet)
            NovaAppearance.Texture.CRACKS -> cracks(sheet)
            NovaAppearance.Texture.WATER -> water(sheet)
            NovaAppearance.Texture.SMOKE -> smoke(sheet)
            NovaAppearance.Texture.STARFIELD -> starfield(sheet)
            NovaAppearance.Texture.SLIME -> slime(sheet)
            NovaAppearance.Texture.SHARDS -> shards(sheet)
            NovaAppearance.Texture.SPLATTER -> splatter(sheet)
            NovaAppearance.Texture.LIGHTNING -> lightning(sheet)
            NovaAppearance.Texture.SNOW -> snow(sheet)
            NovaAppearance.Texture.FRACTAL -> fractal(sheet)
            NovaAppearance.Texture.MATRIX -> matrix(sheet)
        }
        return bitmap
    }
}

// =====================================================================
// Лист: размеры, единицы, кисть
// =====================================================================

/**
 * Лист, на котором строится узор.
 *
 * Держит и растр, и единицы: `w`/`h` — размеры листа в единицах узора (короткая
 * сторона всегда 1.0), `span` — сколько точек в одной единице.
 */
private class Sheet(val bitmap: Bitmap, val random: Random) {

    val width: Int = bitmap.width
    val height: Int = bitmap.height

    /** Точек в одной единице узора. */
    val span: Float = min(width, height).toFloat()

    /** Ширина листа в единицах узора. */
    val w: Float = width / span

    /** Высота листа в единицах узора. */
    val h: Float = height / span

    private var canvasOrNull: Canvas? = null

    val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /**
     * Рисование кистью — в единицах узора.
     *
     * Холст масштабируется один раз, поэтому толщины штрихов и радиусы задаются
     * теми же числами, что и координаты полей, и не зависят от плотности экрана.
     */
    fun drawing(block: (Canvas) -> Unit) {
        val canvas = canvasOrNull ?: Canvas(bitmap).also { canvasOrNull = it }
        canvas.save()
        canvas.scale(span, span)
        block(canvas)
        canvas.restore()
    }

    /** Случайная точка листа в единицах узора. */
    fun pointX(): Float = random.nextFloat() * w

    fun pointY(): Float = random.nextFloat() * h
}

/**
 * Заливает лист попиксельно, разложив полосы строк по ядрам.
 *
 * Принимает не саму функцию, а её изготовителя: каждый поток берёт свой
 * экземпляр и волен держать в нём рабочие буферы — клеточному полю, например,
 * нужны три ответа сразу ([Cellular]), и общий буфер на все потоки был бы гонкой.
 *
 * Пишет через `setPixels`, то есть **замещает** содержимое листа. Поэтому заливка
 * всегда идёт первой, а кисть — после неё.
 */
private fun Sheet.fill(shaderFor: () -> (Float, Float) -> Float) {
    val pixels = IntArray(width * height)
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val bands = min(cores, height)
    if (bands <= 1) {
        fillRows(pixels, 0, height, shaderFor())
    } else {
        val step = (height + bands - 1) / bands
        val threads = ArrayList<Thread>(bands)
        var start = 0
        while (start < height) {
            val from = start
            val to = min(height, start + step)
            threads += Thread({ fillRows(pixels, from, to, shaderFor()) }, "NovaTexture-$from")
            start = to
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}

private fun Sheet.fillRows(
    pixels: IntArray,
    from: Int,
    to: Int,
    shade: (Float, Float) -> Float,
) {
    val inv = 1f / span
    var index = from * width
    for (y in from until to) {
        val v = y * inv
        for (x in 0 until width) {
            val a = (shade(x * inv, v) * 255f).toInt()
            pixels[index++] = (if (a < 0) 0 else if (a > 255) 255 else a) shl 24 or 0x00FFFFFF
        }
    }
}

// =====================================================================
// Основание: решётка, шум, октавы
// =====================================================================

private const val TAU = 6.2831855f

/**
 * Лакунарность — во сколько раз частота растёт от октавы к октаве.
 *
 * Ровно 2 брать нельзя: при удвоении узлы каждой следующей решётки садятся на
 * узлы предыдущей, и совпавшие нули выстраиваются в прямоугольную сетку, которую
 * видно на больших площадях. Иррациональный шаг 2.03 разводит решётки, и сумма
 * октав остаётся суммой октав, а не клеткой.
 */
private const val LACUNARITY = 2.03f

/**
 * 256 единичных векторов — градиенты решётки.
 *
 * Таблица, а не `cos`/`sin` на месте: градиент берётся четыре раза на каждое
 * обращение к шуму, а обращений к шуму — десятки на точку. Тригонометрия в этом
 * цикле стоила бы больше, чем весь остальной узор.
 */
private val GRAD: FloatArray = FloatArray(512).also { table ->
    for (i in 0 until 256) {
        val angle = i * (TAU / 256f)
        table[i * 2] = cos(angle)
        table[i * 2 + 1] = sin(angle)
    }
}

/**
 * Целочисленный хеш узла решётки.
 *
 * Хеш, а не таблица перестановок: узел берётся по своим настоящим координатам,
 * без остатка от деления, поэтому решётка бесконечна и узор нигде не повторяется.
 * Именно повторение и было главной претензией к прежней плитке.
 */
private fun hash2(i: Int, j: Int, seed: Int): Int {
    var h = i * 0x27D4EB2D + j * -0x61C88647 + seed * 0x165667B1
    h = h xor (h ushr 15)
    h *= -0x3361D2AF
    h = h xor (h ushr 13)
    h *= -0x7A143595
    h = h xor (h ushr 16)
    return h
}

private fun gradDot(i: Int, j: Int, seed: Int, dx: Float, dy: Float): Float {
    val k = ((hash2(i, j, seed) ushr 9) and 255) shl 1
    return GRAD[k] * dx + GRAD[k + 1] * dy
}

/**
 * Градиентный шум Перлина, −1..1.
 *
 * Отличие от прежнего значенческого шума не в качестве «вообще», а ровно в том,
 * что было видно: у значенческого экстремум сидит в узле решётки, и на площади в
 * миллион точек узлы выстраиваются в клетку. Здесь в узлах нули, а значение
 * набирается между ними по случайному направлению — клетке взяться неоткуда.
 *
 * Сглаживание квинтическое (6t⁵−15t⁴+10t³): у него на границе ячейки нулевые и
 * первая, и вторая производные, поэтому в освещении (а его считают по градиенту
 * поля — см. [sand]) не проступают полосы на швах ячеек.
 */
private fun perlin(x: Float, y: Float, seed: Int): Float {
    val xi = floor(x).toInt()
    val yi = floor(y).toInt()
    val fx = x - xi
    val fy = y - yi
    val u = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
    val v = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
    val n00 = gradDot(xi, yi, seed, fx, fy)
    val n10 = gradDot(xi + 1, yi, seed, fx - 1f, fy)
    val n01 = gradDot(xi, yi + 1, seed, fx, fy - 1f)
    val n11 = gradDot(xi + 1, yi + 1, seed, fx - 1f, fy - 1f)
    val a = n00 + (n10 - n00) * u
    val b = n01 + (n11 - n01) * u
    val value = (a + (b - a) * v) * 1.4f
    return if (value < -1f) -1f else if (value > 1f) 1f else value
}

/**
 * Сумма октав, −1..1: частота растёт в [LACUNARITY] раз, вклад падает вдвое.
 *
 * Сдвиг координат на каждой октаве (`+17.31`, `−9.47`) — не украшение: без него
 * все октавы центрированы в одной точке, и там, где сошлись их нули, появляется
 * заметное пятно правильной формы.
 */
private fun fbm(x: Float, y: Float, octaves: Int, seed: Int): Float {
    var sum = 0f
    var amp = 1f
    var norm = 0f
    var fx = x
    var fy = y
    var s = seed
    repeat(if (octaves < 1) 1 else if (octaves > 10) 10 else octaves) {
        sum += amp * perlin(fx, fy, s)
        norm += amp
        amp *= 0.5f
        fx = fx * LACUNARITY + 17.31f
        fy = fy * LACUNARITY - 9.47f
        s += 131
    }
    return sum / norm
}

/** То же, но 0..1 — так удобнее почти везде. */
private fun fbm01(x: Float, y: Float, octaves: Int, seed: Int): Float =
    fbm(x, y, octaves, seed) * 0.5f + 0.5f

/**
 * Гребневой мультифрактал, 0..1.
 *
 * `1 − |шум|` даёт острый гребень в каждом нуле шума, квадрат заостряет его ещё
 * сильнее. Множитель `prev` — та самая «мультифрактальность»: вклад мелкой
 * октавы гасится там, где крупная провисла. Поэтому деталь набирается **на
 * хребтах** и не набирается в долинах — в природе так и есть, и именно это
 * отличает горный хребет от равномерно шершавого поля.
 */
private fun ridged(x: Float, y: Float, octaves: Int, seed: Int): Float {
    var sum = 0f
    var amp = 1f
    var norm = 0f
    var prev = 1f
    var fx = x
    var fy = y
    var s = seed
    repeat(if (octaves < 1) 1 else if (octaves > 10) 10 else octaves) {
        var n = 1f - abs(perlin(fx, fy, s))
        n *= n
        sum += n * amp * prev
        prev = n
        norm += amp
        amp *= 0.5f
        fx = fx * LACUNARITY + 11.7f
        fy = fy * LACUNARITY + 23.1f
        s += 197
    }
    val value = sum / norm
    return if (value < 0f) 0f else if (value > 1f) 1f else value
}

/**
 * Турбулентность: та же сумма октав, но взятая по модулю.
 *
 * Модуль даёт излом в каждом нуле шума. Поле из изломов — это не «шум погрубее»,
 * а другое устройство: линии уровня у него рвутся и ветвятся, и именно из него
 * получаются прожилки мрамора и нити дыма.
 */
private fun turbulence(x: Float, y: Float, octaves: Int, seed: Int): Float {
    var sum = 0f
    var amp = 1f
    var norm = 0f
    var fx = x
    var fy = y
    var s = seed
    repeat(if (octaves < 1) 1 else if (octaves > 10) 10 else octaves) {
        sum += amp * abs(perlin(fx, fy, s))
        norm += amp
        amp *= 0.5f
        fx = fx * LACUNARITY + 5.13f
        fy = fy * LACUNARITY + 31.7f
        s += 251
    }
    return sum / norm
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 == edge0) return if (x < edge0) 0f else 1f
    var t = (x - edge0) / (edge1 - edge0)
    if (t < 0f) t = 0f else if (t > 1f) t = 1f
    return t * t * (3f - 2f * t)
}

private fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

/**
 * Клеточное поле: расстояния до ближайшего и второго узла плюс хеш ячейки.
 *
 * Три ответа сразу, поэтому не функция, а объект: возвращать `FloatArray` на
 * каждую из миллиона точек значило бы миллион коротких аллокаций. Экземпляр
 * свой у каждого потока — его выдаёт изготовитель в [fill].
 *
 * `id` — хеш выигравшего узла: по нему ячейка получает собственную яркость, и
 * соседние столбы базальта или чешуйки слюды ловят свет по-разному.
 */
private class Cellular {
    var f1 = 0f
    var f2 = 0f
    var id = 0

    fun at(x: Float, y: Float, seed: Int) {
        val gx = floor(x).toInt()
        val gy = floor(y).toInt()
        var d1 = Float.MAX_VALUE
        var d2 = Float.MAX_VALUE
        var best = 0
        for (j in gy - 1..gy + 1) {
            for (i in gx - 1..gx + 1) {
                val h = hash2(i, j, seed)
                val px = i + (((h ushr 8) and 255) / 255f)
                val py = j + (((h ushr 16) and 255) / 255f)
                val dx = x - px
                val dy = y - py
                val d = dx * dx + dy * dy
                if (d < d1) {
                    d2 = d1
                    d1 = d
                    best = h
                } else if (d < d2) {
                    d2 = d
                }
            }
        }
        f1 = sqrt(d1)
        f2 = sqrt(d2)
        id = best
    }

    /** Собственная яркость ячейки, 0..1. */
    fun tone(): Float = ((id ushr 3) and 1023) / 1023f
}

/**
 * Рельеф в буфере: высота считается один раз, освещение — по её градиенту.
 *
 * Свету нужны соседние отсчёты. Пересчитывать ради каждого соседа всю сумму
 * октав заново — это втрое больше работы на ровном месте, поэтому высота
 * складывается в массив, а второй проход только читает.
 */
private fun Sheet.relief(heightOf: (Float, Float) -> Float): FloatArray {
    val field = FloatArray(width * height)
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val bands = min(cores, height)
    val step = (height + bands - 1) / bands
    val inv = 1f / span
    val threads = ArrayList<Thread>(bands)
    var start = 0
    while (start < height) {
        val from = start
        val to = min(height, start + step)
        threads += Thread({
            var index = from * width
            for (y in from until to) {
                val v = y * inv
                for (x in 0 until width) {
                    field[index++] = heightOf(x * inv, v)
                }
            }
        }, "NovaRelief-$from")
        start = to
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    return field
}

// =====================================================================
// Узоры
// =====================================================================

/**
 * Базальт: столбчатая отдельность.
 *
 * Колонна — не нарисованный шестиугольник, а ячейка Вороного: область, которая
 * ближе к своему узлу, чем к любому чужому, а шов идёт там, где два ближайших
 * узла равноудалены (`f2 − f1` ≈ 0). Фрактальность даёт сумма трёх таких полей:
 * крупная колоннада, поверх неё та же картина вдвое мельче и вдвое слабее. В
 * породе это и видно — энтаблемент дробит массив тем же законом, каким его
 * раскалывает основная колоннада.
 *
 * Домен искривляется дважды. Первое искривление гнёт колоннаду целиком, второе,
 * мелкое, рвёт сами швы. Без второго стенки остаются отрезками, и рисунок
 * читается как чертёж сот.
 *
 * Яркость столба берётся из хеша его ячейки, а не из шума по координате: у
 * настоящего скола плоскость грани постоянна по всей колонне, а не плывёт вдоль
 * неё.
 */
private fun basalt(s: Sheet) {
    val seed = s.random.nextInt()
    s.fill {
        val cell = Cellular()
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.7f, v * 1.7f, 4, seed + 401)
            val qy = fbm(u * 1.7f + 4.2f, v * 1.7f + 1.3f, 4, seed + 419)
            val wx = u + 0.085f * qx + 0.026f * fbm(u * 6.3f, v * 6.3f, 3, seed + 433)
            val wy = v + 0.085f * qy + 0.026f * fbm(u * 6.3f + 2.7f, v * 6.3f, 3, seed + 449)

            var seam = 0f
            var weight = 1f
            var freq = 2.6f
            var tone = 0.5f
            for (level in 0 until 3) {
                cell.at(wx * freq, wy * freq, seed + level * 71)
                // Шов задан в долях шага ячейки, поэтому мелкие столбы сами собой
                // получают тонкие швы, а не такие же, как у крупных.
                seam += weight * (1f - smoothstep(0f, 0.17f, cell.f2 - cell.f1))
                if (level == 0) tone = cell.tone()
                weight *= 0.5f
                freq *= 2.07f
            }

            val lit = clamp01(1f - seam)
            val grain = fbm01(u * 22f, v * 22f, 3, seed + 91)
            // Крупное затемнение: массив освещён неровно, часть колоннады в тени.
            val shade = 0.55f + 0.45f * fbm01(u * 0.9f, v * 0.9f, 3, seed + 113)
            lit * shade * (0.09f + 0.21f * tone + 0.13f * grain)
        }
    }
}

/**
 * Мрамор: прожилка — линия уровня синуса, сорванного турбулентностью.
 *
 * «Дорогой» её делает не синус, а двухступенчатое искривление домена: сначала
 * координата смещается суммой октав, потом — второй суммой, взятой уже от
 * смещённой. Результат самоподобен по построению: крупный изгиб прожилки несёт
 * такой же изгиб вдвое мельче, тот — ещё вдвое, и так до предела октав. Одна
 * ступень искривления даёт волну, две — камень.
 *
 * Прожилок три поколения: каждое вдвое чаще по волновому числу, вдвое слабее
 * сорвано турбулентностью и вдвое тусклее. Восьмая степень синуса оставляет от
 * широкой волны тонкую жилу, а всё между жилами уводит почти в ноль.
 */
private fun marble(s: Sheet) {
    val seed = s.random.nextInt()
    val dirX = 1.3f + s.random.nextFloat() * 0.8f
    val dirY = 1.9f + s.random.nextFloat() * 1.0f
    s.fill {
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.2f, v * 1.2f, 5, seed)
            val qy = fbm(u * 1.2f + 5.2f, v * 1.2f + 1.3f, 5, seed + 11)
            val rx = fbm(u * 2.3f + 1.6f * qx + 1.7f, v * 2.3f + 1.6f * qy + 9.2f, 4, seed + 23)
            val ry = fbm(u * 2.3f + 1.6f * qx + 8.3f, v * 2.3f + 1.6f * qy + 2.8f, 4, seed + 37)
            val wx = u + 0.42f * qx + 0.17f * rx
            val wy = v + 0.42f * qy + 0.17f * ry

            val turb = turbulence(wx * 3.1f, wy * 3.1f, 6, seed + 53)

            var vein = 0f
            var amp = 1f
            var wave = 1.0f
            var push = 2.4f
            for (gen in 0 until 3) {
                val phase = (wx * dirX + wy * dirY) * wave * TAU + turb * push
                val si = 0.5f + 0.5f * sin(phase)
                val s2 = si * si
                val s4 = s2 * s2
                vein += amp * s4 * s4
                amp *= 0.5f
                wave *= 2.1f
                push *= 0.6f
            }

            // Облачная основа камня и кальцитовая крупа в ней.
            val body = fbm01(wx * 2.2f, wy * 2.2f, 5, seed + 71)
            val grit = fbm01(u * 34f, v * 34f, 2, seed + 89)
            0.04f + 0.15f * body * body + 0.05f * grit * body + 0.52f * clamp01(vein)
        }
    }
}

/**
 * Дерево: продольный распил доски, а не мишень из колец.
 *
 * Прежний вариант строил кольца вокруг близкой сердцевины и сыпал по ним клеточные
 * поры, поэтому читался пузырчатым: круглые пятна пор и сучка на фоне дуг. У
 * настоящей доски рисунок другой — длинные линии, текущие через весь лист, и
 * держится он на четырёх вещах, все они здесь есть:
 *
 * 1. **Сердцевина далеко за краем.** Кольца приходят на лист пологими дугами
 *    («кафедральный» рисунок), а не замкнутыми кругами. Поле сплющено вдоль
 *    волокна, поэтому дуги почти горизонтальны.
 * 2. **Профиль годового слоя — пила.** Ранняя древесина плавно темнеет к поздней и
 *    обрывается резкой границей; синус давал бы равномерную рябь. Ширина слоя
 *    плывёт, потому что к фазе добавлена сумма октав.
 * 3. **Волокна.** Тонкие нити вдоль доски: шум, растянутый по волокну в десятки раз.
 *    Поры — не клетки, а короткие штрихи той же ориентации, и сидят они только в
 *    ранней древесине.
 * 4. **Сучки.** Вокруг сучка линии не рвутся, а обтекают его: координата поперёк
 *    волокна растягивается вблизи сучка, а внутри него кольца идут уже вокруг
 *    самого сучка, часто и вытянуто вдоль волокна.
 */
private fun wood(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random
    val pithX = -1.8f - random.nextFloat() * 1.6f
    val pithY = s.h * (0.15f + random.nextFloat() * 0.7f)
    val tilt = (random.nextFloat() - 0.5f) * 0.3f
    val knots = Array(2 + random.nextInt(2)) {
        floatArrayOf(
            s.w * (0.1f + random.nextFloat() * 0.8f),
            s.h * (0.1f + random.nextFloat() * 0.8f),
            0.022f + random.nextFloat() * 0.03f,
        )
    }

    s.fill {
        return@fill { u: Float, v: Float ->
            // Длинные волны всего полотна плюс мелкая рябь поперёк волокна.
            val wave = 0.05f * fbm(u * 0.8f + 3f, v * 0.5f, 4, seed) +
                0.012f * fbm(u * 2.4f, v * 9f, 3, seed + 31)
            var vv = v + wave + tilt * u

            var knotMask = 0f
            var knotPhase = 0f
            var knotCore = 0f
            for (k in knots) {
                val kx = (u - k[0]) * 0.5f
                val ky = vv - k[1]
                val kr = hypot(kx, ky)
                val reach = k[2] * 3.6f
                // Линии обтекают сучок: поперечная координата растягивается.
                if (kr < reach * 2f) {
                    val q = 1f - kr / (reach * 2f)
                    vv += ky * q * q * 0.9f
                }
                val m = 1f - smoothstep(k[2] * 0.8f, k[2] * 2.4f, kr)
                if (m > knotMask) {
                    knotMask = m
                    knotPhase = kr * 46f + 1.3f * wave * 20f
                }
                val c = smoothstep(k[2] * 1.1f, k[2] * 0.35f, kr)
                if (c > knotCore) knotCore = c
            }

            // Кольца ствола: поле сплющено вдоль волокна, сердцевина далеко.
            val dx = (u - pithX) * 0.10f
            val dy = vv - pithY
            val trunk = hypot(dx, dy)
            val mainPhase = trunk * 17f + 1.4f * fbm(u * 0.6f, vv * 0.9f, 3, seed + 7)
            val phase = mainPhase + (knotPhase - mainPhase) * knotMask

            // Пила: плавный подъём к поздней древесине и резкий обрыв.
            val t = phase - floor(phase)
            val ring = t * t * t * (1f - smoothstep(0.94f, 1f, t))
            val contrast = 0.55f + 0.45f * fbm01(u * 1.1f, vv * 0.7f, 2, seed + 13)

            // Волокна: шум, вытянутый вдоль доски.
            val streak = smoothstep(0.35f, 0.75f, fbm01(u * 2.3f, vv * 150f, 2, seed + 97))

            // Поры: короткие штрихи вдоль волокна, только в ранней древесине.
            val porous = smoothstep(0.35f, 0.6f, fbm01(u * 1.5f, v * 1.5f, 2, seed + 41))
            val dash = smoothstep(0.38f, 0.6f, perlin(u * 38f, vv * 210f, seed + 83)) * porous

            0.05f + 0.27f * ring * contrast + 0.09f * streak * (0.45f + 0.55f * (1f - t)) +
                0.13f * dash * (1f - t) + 0.20f * knotCore * (0.5f + 0.5f * ring)
        }
    }
}

/**
 * Песок: дюны гребневым мультифракталом, видимость им даёт свет.
 *
 * Обычная сумма октав даёт холмы — симметричные и круглые. У дюны наветренный
 * склон пологий, подветренный обрывается гребнем, и гребень острый; это ровно
 * то, что получается из `1 − |шум|` с усилением мелких октав на хребтах, то
 * есть из гребневого мультифрактала ([ridged]).
 *
 * Рябь — второй масштаб той же поверхности: её фронты гнутся полем дюн, поэтому
 * ветвятся, сливаются и обрываются, как в натуре. Зерно — третий.
 *
 * Яркость считается **по наклону**, а не по высоте: нормаль берётся из градиента
 * буфера высот, свет падает сверху-слева. Ровно поэтому песок читается объёмным,
 * а не пятнистым. Нормаль нормируется, поэтому пересветить картинку нельзя ни
 * при каком рельефе — освещение здесь косинус, а не множитель.
 */
private fun sand(s: Sheet) {
    val seed = s.random.nextInt()
    val rippleX = 6.1f + s.random.nextFloat() * 2f
    val rippleY = 2.4f + s.random.nextFloat() * 1.6f

    val field = s.relief { u, v ->
        val qx = fbm(u * 1.1f, v * 1.1f, 4, seed + 5)
        val qy = fbm(u * 1.1f + 3.3f, v * 1.1f + 8.1f, 4, seed + 9)
        val dune = ridged(u * 1.9f + 0.42f * qx, v * 1.9f + 0.42f * qy, 7, seed)
        val ripple = sin((u * rippleX + v * rippleY) * TAU + dune * 13f)
        val grit = fbm(u * 90f, v * 90f, 2, seed + 61)
        dune + ripple * 0.020f + grit * 0.0025f
    }

    val width = s.width
    val heightPx = s.height
    val span = s.span
    s.fill {
        return@fill { u: Float, v: Float ->
            var ix = (u * span).toInt()
            var iy = (v * span).toInt()
            if (ix < 1) ix = 1 else if (ix > width - 2) ix = width - 2
            if (iy < 1) iy = 1 else if (iy > heightPx - 2) iy = heightPx - 2
            val row = iy * width
            // Центральная разность, приведённая к единицам узора: шаг между
            // отсчётами равен 2/span, поэтому множитель span/2.
            val du = (field[row + ix + 1] - field[row + ix - 1]) * span * 0.5f
            val dv = (field[row + width + ix] - field[row - width + ix]) * span * 0.5f

            // Нормаль к рельефу: 0.09 — насколько высоко поднят песок
            // относительно кадра. Больше — резче тени.
            val nx = -du * 0.09f
            val ny = -dv * 0.09f
            val inv = 1f / sqrt(nx * nx + ny * ny + 1f)
            val lambert = clamp01((nx * -0.48f + ny * -0.60f + 0.64f) * inv)

            val lit = lambert * lambert * sqrt(lambert)
            0.05f + 0.34f * lit + 0.09f * field[row + ix]
        }
    }
}

/**
 * Луг: несколько сотен цельных растений вместо десятков тысяч травинок.
 *
 * Прежняя трава клала на полотно десятки тысяч тонких травинок, и каждая шла
 * отдельным контуром: дорого и читается как шум. Здесь растений немного, зато
 * каждое крупное и узнаваемое:
 *
 * - **куртинки** — пучки из четырёх-шести широких листьев, веером из одного основания;
 * - **клевер** — три овальных листочка на черешке;
 * - **папоротник** — стебель с парами перистых листочков;
 * - **цветы** — ромашка (восемь лепестков и сердцевина), мак (четыре округлых
 *   лепестка), лютик (пять мелких), василёк (шесть узких лепестков на тонкой ножке).
 *
 * Растения одного яруса сливаются в один [Path], поэтому кадр получает несколько
 * вызовов `drawPath`, а не десятки тысяч. Ярусы различаются прозрачностью: дальние
 * тусклее, ближние ярче. Фактура окрашивается акцентом темы, поэтому виды различаются
 * формой, а не оттенком.
 */
private fun grass(s: Sheet) {
    val random = s.random
    val seed = random.nextInt()
    // Число растений считается от площади листа; единица — лист телефона примерно 1 x 2.2.
    val area = s.w * s.h / 2.2f

    // Подстилка: земля и прель; у нижнего края она сгущается, скрывая основания.
    s.fill {
        return@fill { u: Float, v: Float ->
            val soil = fbm01(u * 3.6f, v * 3.6f, 3, seed)
            val root = smoothstep(s.h - 0.26f, s.h + 0.02f, v)
            0.03f + 0.07f * soil * soil + root * 0.16f
        }
    }

    val far = Path()
    val mid = Path()
    val near = Path()
    val stems = Path()
    val flowers = Path()
    val leaf = Path()
    val matrix = Matrix()

    // Овал в точке (cx, cy) с полуосями rx, ry, повёрнутый на угол `angle` (радианы).
    fun oval(dst: Path, cx: Float, cy: Float, rx: Float, ry: Float, angle: Float) {
        leaf.reset()
        leaf.addOval(-rx, -ry, rx, ry, Path.Direction.CW)
        matrix.reset()
        matrix.setRotate(Math.toDegrees(angle.toDouble()).toFloat(), 0f, 0f)
        matrix.postTranslate(cx, cy)
        dst.addPath(leaf, matrix)
    }

    // Лист, уходящий от основания (x, y); `angle` — отклонение от вертикали.
    // Нормаль всегда берётся одним поворотом, поэтому все листья в одном Path
    // имеют одинаковую ориентацию и не гасят друг друга при заливке.
    fun leafBlade(dst: Path, x: Float, y: Float, len: Float, halfWidth: Float, angle: Float, bend: Float) {
        val dx = sin(angle)
        val dy = -cos(angle)
        val nx = -dy
        val ny = dx
        val tipX = x + dx * len + nx * bend
        val tipY = y + dy * len + ny * bend
        val midX = x + dx * len * 0.5f + nx * bend * 0.35f
        val midY = y + dy * len * 0.5f + ny * bend * 0.35f
        val bulge = halfWidth * 1.1f
        dst.moveTo(x - nx * halfWidth, y - ny * halfWidth)
        dst.quadTo(midX - nx * bulge, midY - ny * bulge, tipX, tipY)
        dst.quadTo(midX + nx * bulge, midY + ny * bulge, x + nx * halfWidth, y + ny * halfWidth)
        dst.close()
    }

    fun wind(x: Float, y: Float): Float = 0.35f * fbm(x * 1.4f, y * 1.4f, 2, seed + 5)

    // Куртинка: 4-6 широких листьев, веером из одной точки.
    fun tuft(dst: Path, x: Float, y: Float, len: Float, halfWidth: Float) {
        val lean = wind(x, y)
        val blades = 4 + random.nextInt(3)
        for (i in 0 until blades) {
            val spread = (i.toFloat() / (blades - 1) - 0.5f) * 0.9f
            val angle = lean + spread + (random.nextFloat() - 0.5f) * 0.2f
            val l = len * (0.7f + 0.3f * random.nextFloat()) * (if (abs(spread) < 0.2f) 1f else 0.85f)
            leafBlade(
                dst,
                x + (random.nextFloat() - 0.5f) * halfWidth * 2f,
                y,
                l,
                halfWidth * (0.8f + 0.4f * random.nextFloat()),
                angle,
                (random.nextFloat() - 0.5f) * len * 0.4f,
            )
        }
    }

    // Клевер: три листочка на черешке.
    fun clover(x: Float, y: Float, size: Float) {
        val lean = (random.nextFloat() - 0.5f) * 0.6f + wind(x, y)
        val tipX = x + sin(lean) * size * 1.4f
        val tipY = y - cos(lean) * size * 1.4f
        stems.moveTo(x, y)
        stems.lineTo(tipX, tipY)
        for (i in 0 until 3) {
            val a = lean + (i - 1) * 2.1f
            oval(
                mid,
                tipX + sin(a) * size * 0.45f,
                tipY - cos(a) * size * 0.45f,
                size * 0.42f,
                size * 0.36f,
                a - HALF_PI,
            )
        }
    }

    // Папоротник: изогнутый стебель с парами перистых листочков, короче кверху.
    fun fern(x: Float, y: Float, len: Float) {
        val lean = (random.nextFloat() - 0.5f) * 0.5f + wind(x, y)
        val tipX = x + sin(lean) * len
        val tipY = y - cos(lean) * len
        val ctrlX = x + sin(lean * 0.4f) * len * 0.5f
        val ctrlY = y - len * 0.5f
        stems.moveTo(x, y)
        stems.quadTo(ctrlX, ctrlY, tipX, tipY)
        val pairs = 5 + random.nextInt(3)
        for (j in 1..pairs) {
            val t = j.toFloat() / (pairs + 1)
            val px = x + (tipX - x) * t
            val py = y + (tipY - y) * t
            val size = len * (0.3f - 0.14f * t)
            for (side in intArrayOf(-1, 1)) {
                val a = lean + side * 0.95f
                oval(
                    mid,
                    px + sin(a) * size * 0.5f,
                    py - cos(a) * size * 0.5f,
                    size * 0.5f,
                    size * 0.16f,
                    a - HALF_PI,
                )
            }
        }
    }

    // Цветок на ножке: вырастает из земли и кончается головкой.
    fun stalked(x: Float, y: Float, stemLen: Float): Float {
        val headY = y - stemLen
        stems.moveTo(x, y)
        stems.lineTo(x, headY)
        return headY
    }

    fun daisy(x: Float, y: Float, r: Float) {
        for (i in 0 until 8) {
            val a = i * TAU / 8f
            oval(flowers, x + cos(a) * r, y + sin(a) * r, r * 0.55f, r * 0.22f, a)
        }
        oval(flowers, x, y, r * 0.3f, r * 0.3f, 0f)
    }

    fun poppy(x: Float, y: Float, r: Float) {
        val turn = random.nextFloat() * TAU
        for (i in 0 until 4) {
            val a = turn + i * HALF_PI
            oval(flowers, x + cos(a) * r * 0.5f, y + sin(a) * r * 0.5f, r * 0.7f, r * 0.62f, a)
        }
    }

    fun buttercup(x: Float, y: Float, r: Float) {
        val turn = random.nextFloat() * TAU
        for (i in 0 until 5) {
            val a = turn + i * TAU / 5f
            oval(flowers, x + cos(a) * r * 0.6f, y + sin(a) * r * 0.6f, r * 0.5f, r * 0.36f, a)
        }
    }

    fun cornflower(x: Float, y: Float, r: Float) {
        val turn = random.nextFloat() * TAU
        for (i in 0 until 6) {
            val a = turn + i * TAU / 6f
            oval(flowers, x + cos(a) * r * 0.7f, y + sin(a) * r * 0.7f, r * 0.6f, r * 0.2f, a)
        }
        oval(flowers, x, y, r * 0.22f, r * 0.22f, 0f)
    }

    // Дальний ярус: мелкие и тусклые, растут выше.
    repeat((220f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.22f + 0.5f * random.nextFloat())
        tuft(far, x, y, 0.05f + 0.035f * random.nextFloat(), 0.006f)
    }

    // Средний ярус: куртинки, клевер и папоротник, ниже.
    repeat((130f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.45f + 0.47f * random.nextFloat())
        tuft(mid, x, y, 0.09f + 0.05f * random.nextFloat(), 0.008f)
    }
    repeat((26f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.5f + 0.4f * random.nextFloat())
        clover(x, y, 0.035f + 0.02f * random.nextFloat())
    }
    repeat((22f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.55f + 0.4f * random.nextFloat())
        fern(x, y, 0.12f + 0.06f * random.nextFloat())
    }

    // Ближний ярус: крупные и яркие, основания у нижней кромки.
    repeat((70f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.78f + 0.27f * random.nextFloat())
        tuft(near, x, y, 0.16f + 0.08f * random.nextFloat(), 0.011f)
    }

    // Цветы: ножка в стебли, головка — в отдельный Path.
    repeat((34f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.4f + 0.5f * random.nextFloat())
        daisy(x, stalked(x, y, 0.05f + 0.04f * random.nextFloat()), 0.02f)
    }
    repeat((12f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.4f + 0.5f * random.nextFloat())
        poppy(x, stalked(x, y, 0.04f + 0.04f * random.nextFloat()), 0.025f)
    }
    repeat((18f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.4f + 0.5f * random.nextFloat())
        buttercup(x, stalked(x, y, 0.03f + 0.03f * random.nextFloat()), 0.016f)
    }
    repeat((14f * area).toInt()) {
        val x = random.nextFloat() * s.w
        val y = s.h * (0.4f + 0.5f * random.nextFloat())
        cornflower(x, stalked(x, y, 0.06f + 0.04f * random.nextFloat()), 0.018f)
    }

    s.drawing { canvas ->
        val paint = s.paint
        paint.style = Paint.Style.FILL
        paint.alpha = FAR_ALPHA
        canvas.drawPath(far, paint)
        paint.alpha = MID_ALPHA
        canvas.drawPath(mid, paint)
        paint.alpha = NEAR_ALPHA
        canvas.drawPath(near, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 0.0032f
        paint.alpha = STEM_ALPHA
        canvas.drawPath(stems, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = FLOWER_ALPHA
        canvas.drawPath(flowers, paint)

        paint.alpha = 255
        paint.strokeCap = Paint.Cap.BUTT
    }
}

private const val FAR_ALPHA = 70
private const val MID_ALPHA = 130
private const val NEAR_ALPHA = 205
private const val STEM_ALPHA = 170
private const val FLOWER_ALPHA = 240
private const val HALF_PI = 1.5707964f

/**
 * Трещины: кракелюр состарившегося покрытия.
 *
 * Здесь была броуновская ломаная — десяток длинных разломов через весь экран. На
 * просвет это читалось разрядом молнии, а не растрескавшейся поверхностью, и
 * владелец сказал именно так: трещины должны быть мелкие и частые, эффект
 * «состаривание».
 *
 * Состарившееся покрытие трескается не длинными разломами, а сеткой: слой
 * стягивается, и разрыв идёт там, где ближе всего до соседнего центра
 * напряжения. Это буквально граница ячеек Вороного — там, где до двух
 * ближайших узлов одинаково (`f2 − f1` ≈ 0). Три поколения таких сеток, каждая
 * вдвое мельче, дают то, что и видно на старой эмали: крупные плиты, разбитые
 * мелкой сеткой, а та — ещё мельче.
 *
 * Поколения складываются **максимумом**, а не суммой: шов должен остаться
 * тонким и резким, а сумма трёх швов размазала бы его в серую кашу.
 *
 * Вторая половина фактуры — сам износ, и она из той же семьи, что была у
 * «Ржавчины» (её удалили по просьбе владельца, но приём стоил того, чтобы
 * остаться): порог, за которым покрытие сдало, сам является суммой октав, и
 * поэтому облезает оно пятнами с рваными краями, а не ровным слоем. Трещины
 * гуще там, где покрытие уже сдало — так и растрескивается краска.
 */
private fun cracks(s: Sheet) {
    val seed = s.random.nextInt()
    s.fill {
        val cell = Cellular()
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.8f, v * 1.8f, 4, seed + 3)
            val qy = fbm(u * 1.8f + 5.3f, v * 1.8f + 1.1f, 4, seed + 7)
            val wx = u + 0.045f * qx
            val wy = v + 0.045f * qy

            // Где покрытие держится, а где сдало. Порог сам фрактален, поэтому
            // изрезанность кромки меняется от места к месту.
            val wear = fbm01(wx * 2.2f, wy * 2.2f, 5, seed + 11)
            val gate = 0.40f + fbm01(wx * 0.9f, wy * 0.9f, 2, seed + 23) * 0.24f
            val bare = clamp01((wear - gate) / 0.12f)

            // Сетка трещин: три поколения, каждое вдвое мельче и чуть слабее.
            // Смещение по x на каждом поколении разводит решётки, иначе мелкая
            // сетка садится узлами на крупную и обе выстраиваются в клетку.
            var net = 0f
            var weight = 1f
            var freq = 7.5f
            for (level in 0 until 3) {
                cell.at(wx * freq + level * 13.7f, wy * freq, seed + level * 71)
                val seam = weight * (1f - smoothstep(0f, 0.085f, cell.f2 - cell.f1))
                if (seam > net) net = seam
                weight *= 0.74f
                freq *= 2.15f
            }
            // Волосяные трещины по всей поверхности — четвёртый масштаб, уже
            // ниже размера ячейки.
            cell.at(wx * 34f, wy * 34f, seed + 211)
            val hair = (1f - smoothstep(0f, 0.05f, cell.f2 - cell.f1)) * 0.34f
            if (hair > net) net = hair

            val crack = net * (0.42f + 0.58f * bare)
            val grain = fbm01(u * 44f, v * 44f, 3, seed + 41)
            val patina = fbm01(wx * 6.5f, wy * 6.5f, 4, seed + 97)

            0.02f + 0.10f * wear * wear + 0.09f * bare * (0.4f + 0.6f * patina) +
                0.05f * grain * bare + 0.46f * crack
        }
    }
}

/**
 * Вода: каустика — сеть света, сфокусированного волной.
 *
 * Прежний вариант рисовал бегущую рябь: полосы, изогнутые суммой октав. На целом
 * экране это читалось как ткань, а не как вода.
 *
 * Свет на дне под волной собирается в сеть ветвящихся нитей, и сеть — это
 * линии уровня гребневого мультифрактала ([ridged]), а не полосы. Здесь их две,
 * разного масштаба, и они **перемножаются**: ярко только там, где нити обеих
 * сошлись. Так фокусировка и работает — две складки поверхности должны сойтись
 * над одной точкой дна. Произведение же даёт длинные тёмные промежутки, которых
 * не бывает у суммы, и именно они читаются как глубина.
 *
 * Домен предварительно смещён крупной зыбью, поэтому вся сеть вместе с ней
 * дышит, а не лежит по линейке.
 */
private fun water(s: Sheet) {
    val seed = s.random.nextInt()
    s.fill {
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.4f, v * 1.4f, 4, seed)
            val qy = fbm(u * 1.4f + 7.1f, v * 1.4f + 2.3f, 4, seed + 17)
            val wx = u + 0.20f * qx
            val wy = v + 0.20f * qy

            val c1 = clamp01(ridged(wx * 4.2f, wy * 4.2f, 5, seed + 31) * 1.25f)
            val c2 = clamp01(ridged(wx * 7.9f + 3.3f, wy * 7.9f - 1.7f, 4, seed + 53) * 1.15f)
            val caustic = c1 * c2
            val sharp = caustic * caustic

            val swell = 0.35f + 0.65f * fbm01(u * 1.1f, v * 1.1f, 3, seed + 71)
            0.035f + 0.10f * swell + 0.26f * caustic * swell + 0.55f * sharp * sharp * swell
        }
    }
}

/**
 * Звёздное небо: Млечный Путь, галактики и звёзды всех масштабов.
 *
 * Прежнее небо было туманностью и горстью точек: на весь экран звёзд не хватало,
 * и читалось оно россыпью одиночек по пустоте. Теперь небо слоистое, и каждый
 * слой отвечает за свой масштаб:
 *
 * 1. **Фон** — туманность по искривлённому домену, а поперёк листа проходит полоса
 *    Млечного Пути: облако, прожилки гребневого мультифрактала и тёмные
 *    пылевые рукава, вычитаемые из неё. Из-за рукавов полоса пёстрая по тону, а не
 *    ровное светлое пятно.
 * 2. **Галактики** — три крупные (спиральные и эллиптические, под своим углом
 *    и наклоном, с яркими ядрами), несколько средних и россыпь мелких размытых
 *    пятнышек. Спираль набрана точками вдоль логарифмической спирали с разбросом, а
 *    не нарисована линией: у настоящих рукавов края рваные.
 * 3. **Звёзды** — четыре класса. Пыль из тысяч слабых точек, мелкие, средние с
 *    ореолом и несколько ярких с дифракционными лучами. Плотность следит за
 *    полосой Млечного Пути: в ней звёзд втрое больше, чем вне её.
 *
 * Всё белое с альфой: цвет даёт акцент темы. Богатство тона достигается разбросом
 * яркости и размеров, а не цветом.
 */
private fun starfield(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random

    // Полоса Млечного Пути под случайным углом через середину листа.
    val bandAngle = 0.75f + random.nextFloat() * 0.7f
    val ca = cos(bandAngle)
    val sa = sin(bandAngle)
    val bandOffset = -(s.w * 0.5f + (random.nextFloat() - 0.5f) * 0.3f) * sa +
        (s.h * 0.5f + (random.nextFloat() - 0.5f) * 0.3f) * ca

    fun band(u: Float, v: Float): Float {
        val along = u * ca + v * sa
        val across = -u * sa + v * ca - bandOffset
        val bend = 0.16f * fbm(along * 1.2f, 0.7f, 3, seed + 3)
        return 1f - smoothstep(0.03f, 0.36f, abs(across + bend))
    }

    s.fill {
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.3f, v * 1.3f, 4, seed)
            val qy = fbm(u * 1.3f + 4.7f, v * 1.3f + 8.9f, 4, seed + 13)
            val wx = u + 0.4f * qx
            val wy = v + 0.4f * qy

            val cloud = fbm01(wx * 1.7f, wy * 1.7f, 5, seed + 41)
            val clear = smoothstep(0.38f, 0.72f, cloud)
            val filament = ridged(wx * 2.6f, wy * 2.6f, 5, seed + 29)
            val b = band(u, v)
            val grain = fbm01(wx * 9f, wy * 9f, 4, seed + 59)
            // Пылевые рукава: гребни вычитаются из полосы.
            val lane = smoothstep(0.55f, 0.85f, ridged(wx * 4.2f + 1.7f, wy * 4.2f, 4, seed + 71))
            val milky = b * (0.08f + 0.22f * grain * cloud + 0.10f * filament * filament) *
                (1f - 0.8f * lane * b)

            0.010f + 0.07f * clear * cloud + 0.12f * filament * filament * clear + milky
        }
    }

    val halo = android.graphics.RadialGradient(
        0f, 0f, 1f,
        Color.argb(255, 255, 255, 255),
        Color.argb(0, 255, 255, 255),
        android.graphics.Shader.TileMode.CLAMP,
    )
    // Профили галактик: спиральный диск, эллиптическая и ядро.
    val white = Color.argb(255, 255, 255, 255)
    val diskShader = android.graphics.RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(55, 255, 255, 255), Color.argb(22, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.25f, 0.6f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    val bulgeShader = android.graphics.RadialGradient(
        0f, 0f, 1f,
        intArrayOf(white, Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.35f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    val ellipticalShader = android.graphics.RadialGradient(
        0f, 0f, 1f,
        intArrayOf(white, Color.argb(200, 255, 255, 255), Color.argb(100, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.12f, 0.35f, 0.7f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )

    // Звёзды заранее: холст трогается один раз. Звезда — x, y, радиус, альфа.
    fun rnd(lo: Float, hi: Float) = lo + (hi - lo) * random.nextFloat()
    fun accept(x: Float, y: Float): Boolean = random.nextFloat() < 0.25f + 0.75f * band(x, y)

    val area = s.w * s.h
    val dust = Array(3) { FloatArray(2 * (14000 * area).toInt() + 8) }
    val dustCount = IntArray(3)
    repeat((14000 * area).toInt()) {
        val x = s.pointX()
        val y = s.pointY()
        if (!accept(x, y)) return@repeat
        val c = random.nextInt(3)
        dust[c][dustCount[c] * 2] = x
        dust[c][dustCount[c] * 2 + 1] = y
        dustCount[c]++
    }

    val small = ArrayList<FloatArray>(2048)
    repeat((3500 * area).toInt()) {
        val x = s.pointX()
        val y = s.pointY()
        if (!accept(x, y)) return@repeat
        val q = random.nextFloat()
        small += floatArrayOf(x, y, 0.0009f + q * q * 0.0012f, 90f + q * 110f)
    }
    val medium = ArrayList<FloatArray>(256)
    repeat((420 * area).toInt()) {
        val x = s.pointX()
        val y = s.pointY()
        if (!accept(x, y)) return@repeat
        val q = random.nextFloat()
        medium += floatArrayOf(x, y, 0.0016f + q * q * 0.002f, 150f + q * 80f)
    }
    val bright = ArrayList<FloatArray>(32)
    repeat(10 + random.nextInt(8)) {
        bright += floatArrayOf(s.pointX(), s.pointY(), rnd(0.003f, 0.0055f), 255f)
    }

    s.drawing { canvas ->
        val paint = s.paint

        // Галактика в собственной системе: единичный круг, повёрнутый и сплюснутый.
        fun galaxy(cx: Float, cy: Float, r: Float, rot: Float, ratio: Float, spiral: Boolean, force: Float) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rot)
            canvas.scale(r, r * ratio)
            paint.style = Paint.Style.FILL
            if (spiral) {
                paint.shader = diskShader
                paint.alpha = (255 * force).toInt()
                canvas.drawCircle(0f, 0f, 1f, paint)
                paint.shader = null

                val tight = rnd(0.17f, 0.28f)
                val turn = ln(11f) / tight
                val flip = if (random.nextBoolean()) 1f else -1f
                for (arm in 0 until 2) {
                    val off = arm * 3.1416f
                    // Мягкая дымка вдоль рукава.
                    paint.shader = halo
                    repeat(70) {
                        val th = random.nextFloat() * turn
                        val rr = 0.09f * exp(tight * th)
                        val a = flip * th + off
                        val rad = rnd(0.05f, 0.12f)
                        paint.alpha = (rnd(12f, 22f) * force).toInt()
                        canvas.save()
                        canvas.translate(cos(a) * rr, sin(a) * rr)
                        canvas.scale(rad, rad)
                        canvas.drawCircle(0f, 0f, 1f, paint)
                        canvas.restore()
                    }
                    paint.shader = null
                    // Звёзды рукава с разбросом поперёк.
                    repeat(450) {
                        val th = random.nextFloat() * turn
                        val rr = 0.09f * exp(tight * th)
                        val a = flip * th + off
                        val spread = (random.nextFloat() + random.nextFloat() - 1f) * 0.07f * (0.4f + rr)
                        val x = cos(a) * rr + spread * cos(a + 1.5708f)
                        val y = sin(a) * rr + spread * sin(a + 1.5708f)
                        val q = random.nextFloat()
                        paint.alpha = ((35f + 90f * random.nextFloat()) * (1f - 0.5f * rr) * force).toInt()
                        canvas.drawCircle(x, y, 0.006f + q * q * q * 0.02f, paint)
                    }
                    // Яркие узлы звёздообразования.
                    repeat(18) {
                        val th = random.nextFloat() * turn
                        val rr = 0.09f * exp(tight * th)
                        val a = flip * th + off
                        paint.alpha = (rnd(120f, 210f) * force).toInt()
                        canvas.drawCircle(cos(a) * rr, sin(a) * rr, rnd(0.008f, 0.016f), paint)
                    }
                }
                paint.shader = bulgeShader
                paint.alpha = (255 * force).toInt()
                canvas.save()
                canvas.scale(0.28f, 0.28f)
                canvas.drawCircle(0f, 0f, 1f, paint)
                canvas.restore()
                paint.shader = null
                paint.alpha = 255
                canvas.drawCircle(0f, 0f, 0.03f, paint)

                // Пылевая полоса у почти ребром повёрнутых: вычитается из свечения.
                if (ratio < 0.45f) {
                    val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                    dustPaint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                    dustPaint.style = Paint.Style.STROKE
                    dustPaint.strokeWidth = 0.05f
                    dustPaint.alpha = 130
                    canvas.drawLine(-0.85f, 0f, 0.85f, 0f, dustPaint)
                }
            } else {
                paint.shader = ellipticalShader
                paint.alpha = (255 * force).toInt()
                canvas.drawCircle(0f, 0f, 1f, paint)
                paint.shader = null
            }
            canvas.restore()
            paint.alpha = 255
        }

        // Три крупные галактики подальше друг от друга.
        val placed = ArrayList<FloatArray>(4)
        var tries = 0
        while (placed.size < 3 && tries < 60) {
            tries++
            val x = s.w * rnd(0.12f, 0.88f)
            val y = s.h * rnd(0.08f, 0.92f)
            if (placed.any { hypot(it[0] - x, it[1] - y) < 0.3f }) continue
            placed += floatArrayOf(x, y)
            galaxy(
                x, y, rnd(0.07f, 0.13f), rnd(0f, 180f), rnd(0.22f, 0.9f),
                random.nextFloat() < 0.7f, rnd(0.75f, 1f),
            )
        }
        // Средние.
        repeat(4) {
            galaxy(
                s.pointX(), s.pointY(), rnd(0.025f, 0.05f), rnd(0f, 180f), rnd(0.3f, 0.85f),
                random.nextFloat() < 0.5f, rnd(0.5f, 0.85f),
            )
        }
        // Мелкие размытые пятнышки далёких галактик.
        repeat(14) {
            galaxy(
                s.pointX(), s.pointY(), rnd(0.008f, 0.02f), rnd(0f, 180f), rnd(0.3f, 0.9f),
                false, rnd(0.25f, 0.6f),
            )
        }

        // Ореолы средних и ярких звёзд.
        paint.shader = halo
        for (star in medium) {
            paint.alpha = (star[3] * 0.18f).toInt()
            canvas.save()
            canvas.translate(star[0], star[1])
            val k = star[2] * 6f
            canvas.scale(k, k)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        for (star in bright) {
            paint.alpha = 130
            canvas.save()
            canvas.translate(star[0], star[1])
            val k = star[2] * 9f
            canvas.scale(k, k)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        paint.shader = null

        // Дифракционные лучи ярких: четыре длинных и четыре короткие по диагоналям.
        val needle = Path()
        paint.style = Paint.Style.FILL
        for (star in bright) {
            val len = rnd(0.035f, 0.085f)
            val tilt = rnd(-12f, 12f)
            for (pass in 0 until 2) {
                val reach = if (pass == 0) len else len * 0.45f
                val base = reach * 0.014f
                paint.alpha = if (pass == 0) 190 else 110
                for (arm in 0 until 4) {
                    canvas.save()
                    canvas.translate(star[0], star[1])
                    canvas.rotate(tilt + arm * 90f + pass * 45f)
                    needle.reset()
                    needle.moveTo(0f, -base)
                    needle.lineTo(reach, 0f)
                    needle.lineTo(0f, base)
                    needle.close()
                    canvas.drawPath(needle, paint)
                    canvas.restore()
                }
            }
        }

        // Пыль: три класса яркости точками.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 0.0011f
        val dustAlpha = intArrayOf(40, 70, 105)
        for (c in 0 until 3) {
            paint.alpha = dustAlpha[c]
            canvas.drawPoints(dust[c], 0, dustCount[c] * 2, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        for (star in small) {
            paint.alpha = star[3].toInt()
            canvas.drawCircle(star[0], star[1], star[2], paint)
        }
        for (star in medium) {
            paint.alpha = star[3].toInt()
            canvas.drawCircle(star[0], star[1], star[2], paint)
        }
        for (star in bright) {
            paint.alpha = 255
            canvas.drawCircle(star[0], star[1], star[2], paint)
        }
        paint.alpha = 255
    }
}

/**
 * Слизь: метаболы, расставленные рекурсивным дроблением.
 *
 * Капля сажает две дочерние себе на край, те — свои, и так до третьего колена.
 * Поэтому у комка нет собственного размера: каждый выступ его границы сам
 * оказывается каплей с выступами. Россыпь независимых кружков такого края не
 * даст никогда — она даст кружки.
 *
 * Капли не рисуются по одной, а складываются в скалярное поле с конечным
 * носителем, `w·(1 − d²/R²)²`; поверхность — линия уровня суммы. Оттого соседние
 * капли не пересекаются кромками, а стягиваются перемычкой — это и читается как
 * слизь. Носитель шире самой капли (1.8 r), поэтому перемычка тянется и к той,
 * до которой кромкой не достать.
 *
 * Глянец — из градиента того же поля: он копится в том же цикле и почти ничего
 * не стоит. Мениск (яркий кант по линии уровня) плюс блик на склоне, повёрнутом
 * к свету; блик достаётся каждому поколению капель, и мелкие блестят наравне с
 * крупными — без этого поверхность читается матовой.
 */
private fun slime(s: Sheet) {
    val random = s.random
    val depth = 3
    val reach = 1.8f
    val cell = 0.55f
    val cols = (s.w / cell).toInt() + 1
    val rows = (s.h / cell).toInt() + 1
    val capacity = cols * rows * ((1 shl (depth + 1)) - 1)

    val blobX = FloatArray(capacity)
    val blobY = FloatArray(capacity)
    val blobR = FloatArray(capacity)
    val blobSupport = FloatArray(capacity)
    var count = 0

    fun spawn(cx: Float, cy: Float, r: Float, left: Int) {
        if (count >= capacity) return
        val support = r * reach
        blobX[count] = cx
        blobY[count] = cy
        blobR[count] = r
        blobSupport[count] = support * support
        count++
        if (left == 0) return
        repeat(2) {
            // Ребёнок садится чуть дальше края родителя: вплотную они сливаются в
            // картофелину, а на таком выносе между ними остаётся перемычка.
            val angle = random.nextFloat() * TAU
            val step = r * 1.45f * (0.75f + random.nextFloat() * 0.5f)
            spawn(
                cx + cos(angle) * step,
                cy + sin(angle) * step,
                r * (0.55f + random.nextFloat() * 0.17f),
                left - 1,
            )
        }
    }

    for (gy in 0 until rows) {
        for (gx in 0 until cols) {
            spawn(
                (gx + 0.15f + random.nextFloat() * 0.7f) * cell,
                (gy + 0.15f + random.nextFloat() * 0.7f) * cell,
                0.085f + random.nextFloat() * 0.055f,
                depth,
            )
        }
    }

    val total = count
    // Уровень поля на «своём» радиусе капли: (1 − 1/reach²)².
    val edge = 1f - 1f / (reach * reach)
    val level = edge * edge
    val sunX = 0.6f
    val sunY = 0.8f

    s.fill {
        return@fill { u: Float, v: Float ->
            var f = 0f
            var gx = 0f
            var gy = 0f
            for (i in 0 until total) {
                val support = blobSupport[i]
                // Отсев по одной координате до умножений: от данной точки почти
                // все капли далеко, и платить за них полную формулу незачем.
                val dy = v - blobY[i]
                if (dy * dy >= support) continue
                val dx = u - blobX[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= support) continue
                val t = 1f - d2 / support
                f += t * t
                val g = -4f * t / support
                gx += g * dx
                gy += g * dy
            }
            val df = f - level
            val body = clamp01(df * 16f)
            val meniscus = 1f / (1f + 420f * df * df)
            val slope = hypot(gx, gy)
            val facing = if (slope > 1e-6f) {
                val c = (gx * sunX + gy * sunY) / slope
                if (c < 0f) 0f else c
            } else {
                0f
            }
            val lit = facing * facing
            val gloss = lit * lit * body
            0.12f * body + 0.26f * meniscus + 0.40f * gloss
        }
    }
}

/**
 * Осколки: рекурсивное дробление многоугольника от точки удара.
 *
 * Многоугольник режется хордой от точки на одном ребре до точки на другом, и к
 * обеим половинам применяется то же правило: излом порождает изломы. Россыпь
 * независимых треугольников так не выглядит — у неё нет общих рёбер, и рисунок
 * читается как конфетти, а не как разбитое стекло.
 *
 * Новое здесь — точка удара. Порог, ниже которого грань дробить перестают,
 * зависит от расстояния до неё: у самого удара стекло в пыль, поодаль — крупные
 * пластины. Это настоящий закон разрушения, и он же делает картинку читаемой:
 * глаз сразу находит центр. Оттуда же расходятся радиальные разломы.
 */
private fun shards(s: Sheet) {
    val random = s.random
    val tierAlpha = intArrayOf(12, 21, 32, 46)
    val facets = Array(tierAlpha.size) { Path() }
    val edges = Path()
    val radials = Path()

    val impactX = s.w * (0.25f + random.nextFloat() * 0.5f)
    val impactY = s.h * (0.2f + random.nextFloat() * 0.6f)

    fun emit(poly: FloatArray) {
        val n = poly.size / 2
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += poly[i * 2]
            cy += poly[i * 2 + 1]
        }
        cx /= n
        cy /= n
        // Ступень яркости своя у каждой грани: соседи, отличающиеся альфой,
        // читаются как отдельные плоскости, повёрнутые к свету по-разному.
        val face = facets[random.nextInt(tierAlpha.size)]
        for (i in 0 until n) {
            // Грань поджимается к своему центру: зазор между соседями и есть
            // линия разлома — сквозь щель виден фон.
            val px = cx + (poly[i * 2] - cx) * 0.93f
            val py = cy + (poly[i * 2 + 1] - cy) * 0.93f
            if (i == 0) {
                face.moveTo(px, py)
                edges.moveTo(px, py)
            } else {
                face.lineTo(px, py)
                edges.lineTo(px, py)
            }
        }
        face.close()
        edges.close()
    }

    fun split(poly: FloatArray, depth: Int) {
        val n = poly.size / 2
        var cross = 0f
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            cross += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
            cx += poly[i * 2]
            cy += poly[i * 2 + 1]
        }
        cx /= n
        cy /= n
        val area = abs(cross) * 0.5f
        // Чем дальше от удара, тем крупнее осколок, которому позволено уцелеть.
        val reach = hypot(cx - impactX, cy - impactY)
        val floorArea = 0.00018f + reach * reach * 0.008f
        if (depth == 0 || area < floorArea) {
            emit(poly)
            return
        }
        val a = random.nextInt(n)
        // Сдвиг до второго ребра в диапазоне 1..n−2: обе половины остаются
        // многоугольниками, вырожденных «половин» из двух точек не бывает.
        val off = 1 + random.nextInt(n - 2)
        val b = (a + off) % n
        val ta = 0.22f + random.nextFloat() * 0.56f
        val tb = 0.22f + random.nextFloat() * 0.56f
        val an = (a + 1) % n
        val bn = (b + 1) % n
        val ax = poly[a * 2] + (poly[an * 2] - poly[a * 2]) * ta
        val ay = poly[a * 2 + 1] + (poly[an * 2 + 1] - poly[a * 2 + 1]) * ta
        val bx = poly[b * 2] + (poly[bn * 2] - poly[b * 2]) * tb
        val by = poly[b * 2 + 1] + (poly[bn * 2 + 1] - poly[b * 2 + 1]) * tb

        val left = FloatArray((2 + off) * 2)
        left[0] = ax
        left[1] = ay
        for (k in 1..off) {
            val vtx = (a + k) % n
            left[k * 2] = poly[vtx * 2]
            left[k * 2 + 1] = poly[vtx * 2 + 1]
        }
        left[(off + 1) * 2] = bx
        left[(off + 1) * 2 + 1] = by

        val rest = n - off
        val right = FloatArray((2 + rest) * 2)
        right[0] = bx
        right[1] = by
        for (k in 1..rest) {
            val vtx = (b + k) % n
            right[k * 2] = poly[vtx * 2]
            right[k * 2 + 1] = poly[vtx * 2 + 1]
        }
        right[(rest + 1) * 2] = ax
        right[(rest + 1) * 2 + 1] = ay

        split(left, depth - 1)
        split(right, depth - 1)
    }

    // Пластины покрывают лист с запасом: рисунок идёт от края до края.
    val plate = 0.62f
    var py = -plate * 0.5f
    while (py < s.h + plate * 0.5f) {
        var px = -plate * 0.5f
        while (px < s.w + plate * 0.5f) {
            val quad = FloatArray(8)
            val cx = px + plate * (0.3f + random.nextFloat() * 0.4f)
            val cy = py + plate * (0.3f + random.nextFloat() * 0.4f)
            for (i in 0 until 4) {
                val ang = i * 1.5708f + (random.nextFloat() - 0.5f) * 0.8f
                val rr = plate * (0.55f + random.nextFloat() * 0.4f)
                quad[i * 2] = cx + cos(ang) * rr
                quad[i * 2 + 1] = cy + sin(ang) * rr
            }
            split(quad, 7)
            px += plate
        }
        py += plate
    }

    // Радиальные разломы от удара: по ним стекло и разошлось.
    repeat(13) {
        val angle = random.nextFloat() * TAU
        val len = 0.35f + random.nextFloat() * 0.75f
        var x = impactX
        var y = impactY
        var a = angle
        radials.moveTo(x, y)
        val steps = 7
        for (i in 0 until steps) {
            a += (random.nextFloat() - 0.5f) * 0.30f
            x += cos(a) * len / steps
            y += sin(a) * len / steps
            radials.lineTo(x, y)
        }
    }

    s.drawing { canvas ->
        val paint = s.paint
        paint.style = Paint.Style.FILL
        for (tier in tierAlpha.indices) {
            paint.alpha = tierAlpha[tier]
            canvas.drawPath(facets[tier], paint)
        }
        // Рёбра поверх заливки и ярче неё — блик на сколе.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.0016f
        paint.alpha = 96
        canvas.drawPath(edges, paint)
        paint.strokeWidth = 0.0035f
        paint.alpha = 150
        canvas.drawPath(radials, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }
}

/**
 * Брызги: разные удары, а не одинаковые застывшие капли.
 *
 * Прежний вариант размножал одно и то же пятно: ядро, пучок лучей, спутники.
 * Глаз быстро видел повтор. Теперь пятно выбирается из пяти видов, у каждого свой
 * характер, и размер тоже гуляет:
 *
 * 1. **Клякса с нитями** — неровное ядро из лопастей, вокруг нити разной длины,
 *    изогнутые, с бусинами, развилками и каплями на концах;
 * 2. **Косой удар** — вытянутая голова и узкий хвост в одну сторону, за хвостом
 *    цепочка отрывающихся капель, мельчающая и редеющая;
 * 3. **Корона** — неровное кольцо с зубцами, у каждого шарик на конце, часть шариков
 *    оторвалась; в центре иногда остаётся столбик-капля;
 * 4. **Мазок** — длинный изогнутый след переменной ширины с пальцами по бокам;
 * 5. **Мелочь** — россыпь капель разного размера по всему листу: чем мельче, тем
 *    больше.
 *
 * Техника прежняя и по-прежнему нужна: всё набирается кружками в поле толщины с
 * носителем `(1 − d²/R²)²`, поверхность — линия уровня суммы. Только сумма даёт
 * слияние нити с ядром и валик по кромке, которого у обведённого контура нет.
 * Цепочка кружков по отрезку ([chain]) даёт вытянутые тела с плавным сужением.
 */
private fun splatter(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random
    val width = s.width
    val heightPx = s.height
    val span = s.span
    val field = FloatArray(width * heightPx)

    fun splat(cx: Float, cy: Float, r: Float, weight: Float) {
        val pr = r * span
        if (pr < 0.6f) return
        val px = cx * span
        val py = cy * span
        var x0 = (px - pr).toInt()
        var x1 = (px + pr).toInt() + 1
        var y0 = (py - pr).toInt()
        var y1 = (py + pr).toInt() + 1
        if (x0 < 0) x0 = 0
        if (y0 < 0) y0 = 0
        if (x1 > width - 1) x1 = width - 1
        if (y1 > heightPx - 1) y1 = heightPx - 1
        val r2 = pr * pr
        for (y in y0..y1) {
            val dy = y - py
            if (r2 - dy * dy <= 0f) continue
            val row = y * width
            for (x in x0..x1) {
                val dx = x - px
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val t = 1f - d2 / r2
                field[row + x] += t * t * weight
            }
        }
    }

    fun rnd(lo: Float, hi: Float) = lo + (hi - lo) * random.nextFloat()

    // Цепочка кружков по отрезку с линейным сужением: вытянутое тело.
    fun chain(x0: Float, y0: Float, x1: Float, y1: Float, r0: Float, r1: Float, weight: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        val rMin = if (r0 < r1) r0 else r1
        val n = (len / ((if (rMin < 0.0008f) 0.0008f else rMin) * 0.5f)).toInt().coerceIn(2, 70)
        for (i in 0..n) {
            val t = i.toFloat() / n
            splat(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r0 + (r1 - r0) * t, weight)
        }
    }

    // Спутники: оторвавшиеся капли; чем дальше, тем мельче.
    fun satellites(cx: Float, cy: Float, r: Float, count: Int) {
        repeat(count) {
            val a = random.nextFloat() * TAU
            val q = random.nextFloat()
            val d = r * (1.5f + 11f * q * q)
            val size = r * (0.05f + 0.28f * (1f - q) * random.nextFloat() * random.nextFloat())
            splat(cx + cos(a) * d, cy + sin(a) * d, size, 1.15f)
        }
    }

    // Нить: изогнутая цепочка от (cx, cy) под углом, сужается, с бусинами и шариком.
    fun filament(cx: Float, cy: Float, angle: Float, reach: Float, width0: Float, curve: Float, beads: Boolean) {
        val steps = 12
        var px = cx
        var py = cy
        var prevW = width0
        var endA = angle
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val a = angle + curve * t * t
            endA = a
            val x = cx + cos(a) * reach * t + cos(a + 1.5708f) * sin(t * 9f) * reach * 0.015f
            val y = cy + sin(a) * reach * t + sin(a + 1.5708f) * sin(t * 9f) * reach * 0.015f
            val w = width0 * (1f - t) + width0 * 0.1f
            chain(px, py, x, y, prevW, w, 1f)
            if (beads && i in 4..9 && random.nextFloat() < 0.22f) {
                splat(x, y, width0 * rnd(0.35f, 0.6f), 1.2f)
            }
            px = x
            py = y
            prevW = w
        }
        splat(px, py, width0 * rnd(0.25f, 0.6f), 1.3f)
        // Иногда головка отрывается совсем.
        if (random.nextFloat() < 0.4f) {
            splat(px + cos(endA) * width0 * 1.8f, py + sin(endA) * width0 * 1.8f, width0 * rnd(0.12f, 0.3f), 1.3f)
        }
    }

    fun blob(cx: Float, cy: Float, r: Float) {
        repeat(4 + random.nextInt(4)) {
            val a = random.nextFloat() * TAU
            val d = r * random.nextFloat() * 0.6f
            splat(cx + cos(a) * d, cy + sin(a) * d, r * rnd(0.45f, 0.95f), 1f)
        }
        val arms = 4 + random.nextInt(8)
        repeat(arms) {
            val angle = random.nextFloat() * TAU
            val reach = r * rnd(1.3f, 5.2f)
            val width0 = r * rnd(0.25f, 0.55f)
            val curve = (random.nextFloat() - 0.5f) * 1.1f
            filament(cx, cy, angle, reach, width0, curve, true)
            // Развилка: ещё одна нить из середины.
            if (reach > r * 3f && random.nextFloat() < 0.5f) {
                val t = 0.55f
                val a = angle + curve * t * t
                val side = if (random.nextBoolean()) 0.7f else -0.7f
                filament(
                    cx + cos(a) * reach * t, cy + sin(a) * reach * t,
                    a + side, reach * rnd(0.25f, 0.45f), width0 * 0.5f, side * 0.3f, false,
                )
            }
        }
        satellites(cx, cy, r, arms * 2)
    }

    fun streak(cx: Float, cy: Float, r: Float) {
        val dir = random.nextFloat() * TAU
        val dx = cos(dir)
        val dy = sin(dir)
        val side = dir + 1.5708f
        chain(cx, cy, cx + dx * r * 1.8f, cy + dy * r * 1.8f, r * 0.9f, r * 0.65f, 1f)
        val tail = r * rnd(4f, 9f)
        val bow = r * rnd(-0.8f, 0.8f)
        var px = cx + dx * r * 1.8f
        var py = cy + dy * r * 1.8f
        repeat(5) { i ->
            val t = (i + 1) / 5f
            val x = cx + dx * tail * t + cos(side) * bow * sin(t * 3.1416f)
            val y = cy + dy * tail * t + sin(side) * bow * sin(t * 3.1416f)
            chain(px, py, x, y, r * (0.6f - 0.5f * i / 5f), r * (0.6f - 0.5f * t), 1f)
            px = x
            py = y
        }
        // Боковые шипы у головы.
        repeat(3 + random.nextInt(4)) {
            val a = dir + rnd(-1.2f, 1.2f) + (if (random.nextFloat() < 0.3f) 3.1416f else 0f)
            filament(cx, cy, a, r * rnd(1f, 2.8f), r * rnd(0.2f, 0.4f), rnd(-0.3f, 0.3f), false)
        }
        // Цепочка капель за хвостом: мельчает и редеет.
        val drops = 6 + random.nextInt(8)
        var d = tail
        for (i in 0 until drops) {
            d += r * rnd(0.6f, 2.0f) * (1f + i * 0.15f)
            val lat = r * rnd(-0.5f, 0.5f)
            var size = r * (0.4f * (1f - i.toFloat() / drops)) * rnd(0.5f, 1.2f)
            if (size < r * 0.04f) size = r * 0.04f
            val x = cx + dx * d + cos(side) * lat
            val y = cy + dy * d + sin(side) * lat
            chain(x, y, x + dx * size * 1.6f, y + dy * size * 1.6f, size, size * 0.6f, 1.1f)
        }
    }

    fun crown(cx: Float, cy: Float, r: Float) {
        val ring = r * rnd(1.4f, 2.1f)
        val n = 18 + random.nextInt(8)
        for (i in 0 until n) {
            if (random.nextFloat() < 0.1f) continue
            val a = i * TAU / n
            val rr = ring * rnd(0.88f, 1.12f)
            splat(cx + cos(a) * rr, cy + sin(a) * rr, r * rnd(0.28f, 0.42f), 1f)
        }
        // Зубцы неровно по кольцу, на концах шарики; часть оторвана.
        val spikes = 9 + random.nextInt(8)
        repeat(spikes) {
            val a = random.nextFloat() * TAU
            val len = r * rnd(0.7f, 2.6f)
            val x0 = cx + cos(a) * ring
            val y0 = cy + sin(a) * ring
            val x1 = cx + cos(a) * (ring + len)
            val y1 = cy + sin(a) * (ring + len)
            chain(x0, y0, x1, y1, r * 0.3f, r * 0.12f, 1f)
            val ball = r * rnd(0.18f, 0.34f)
            if (random.nextFloat() < 0.35f) {
                splat(cx + cos(a) * (ring + len * 1.4f), cy + sin(a) * (ring + len * 1.4f), ball, 1.3f)
            } else {
                splat(x1, y1, ball, 1.3f)
            }
        }
        if (random.nextFloat() < 0.55f) splat(cx, cy, r * rnd(0.3f, 0.6f), 1.2f)
        satellites(cx, cy, ring, 10 + random.nextInt(8))
    }

    fun smear(cx: Float, cy: Float, r: Float) {
        var heading = random.nextFloat() * TAU
        val turn = rnd(-0.12f, 0.12f)
        val length = r * rnd(6f, 14f)
        val n = 16
        var px = cx
        var py = cy
        var prevW = r * 0.25f
        for (i in 1..n) {
            val t = i.toFloat() / n
            heading += turn + rnd(-0.05f, 0.05f)
            val x = px + cos(heading) * length / n
            val y = py + sin(heading) * length / n
            val bulge = Math.pow(sin(t * 3.1416f).toDouble(), 0.6).toFloat()
            val w = r * (0.25f + 0.6f * bulge) * rnd(0.85f, 1.15f)
            chain(px, py, x, y, prevW, w, 1f)
            // Пальцы по бокам.
            if (i % 4 == 0 && random.nextFloat() < 0.7f) {
                val a = heading + (if (random.nextBoolean()) 1f else -1f) * rnd(0.6f, 1.2f)
                filament(x, y, a, r * rnd(0.8f, 2.2f), w * 0.5f, rnd(-0.4f, 0.4f), false)
            }
            px = x
            py = y
            prevW = w
        }
        splat(px, py, r * rnd(0.15f, 0.3f), 1.3f)
        satellites(px, py, r, 6)
    }

    // Пятна по сетке со сдвигом: случайные броски на такой площади сбиваются в
    // кучу и оставляют пустые углы.
    val cellSize = 0.28f
    var row = 0
    while (row * cellSize < s.h + cellSize) {
        var col = 0
        while (col * cellSize < s.w + cellSize) {
            val count = if (random.nextFloat() < 0.35f) 2 else 1
            repeat(count) {
                val cx = (col + 0.1f + random.nextFloat() * 0.8f) * cellSize
                val cy = (row + 0.1f + random.nextFloat() * 0.8f) * cellSize
                val q = random.nextFloat()
                val r = 0.011f + 0.03f * q * sqrt(q)
                when (random.nextFloat()) {
                    in 0f..0.30f -> blob(cx, cy, r)
                    in 0.30f..0.55f -> streak(cx, cy, r)
                    in 0.55f..0.75f -> crown(cx, cy, r * 0.8f)
                    else -> smear(cx, cy, r)
                }
            }
            col++
        }
        row++
    }

    // Мелочь по всему листу, гуще там, где поле шума повыше.
    repeat((380 * s.w * s.h).toInt()) {
        val x = s.pointX()
        val y = s.pointY()
        if (fbm01(x * 3.5f, y * 3.5f, 3, seed + 3) < 0.4f * random.nextFloat() + 0.2f) return@repeat
        val q = random.nextFloat()
        splat(x, y, 0.0018f + 0.009f * q * q * q, 1.15f)
    }

    s.fill {
        return@fill shade@{ u: Float, v: Float ->
            var ix = (u * span).toInt()
            var iy = (v * span).toInt()
            if (ix < 1) ix = 1 else if (ix > width - 2) ix = width - 2
            if (iy < 1) iy = 1 else if (iy > heightPx - 2) iy = heightPx - 2
            val row2 = iy * width
            val f = field[row2 + ix]

            // Сухой ореол вокруг пятен: мельчайшая пыль, которую отдельными
            // кружками набивать незачем.
            val mist = fbm01(u * 5.5f, v * 5.5f, 5, seed)
            val haze = clamp01((mist - 0.55f) * 3.2f)

            // Полоса перехода узкая: плёнка тонкая, край обрывается валиком.
            val body = smoothstep(0.5f, 0.95f, f)
            if (body <= 0f) return@shade 0.018f + 0.07f * haze * haze

            val gx = (field[row2 + ix + 1] - field[row2 + ix - 1]) * 0.5f
            val gy = (field[row2 + width + ix] - field[row2 - width + ix]) * 0.5f
            val slope = hypot(gx, gy)

            val rim = clamp01(slope * 7f) * body
            val facing = if (slope > 1e-6f) {
                val c = (gx * 0.62f + gy * 0.78f) / slope
                if (c < 0f) 0f else c
            } else {
                0f
            }
            val gloss = facing * facing * facing * body
            val depth = 1f / (1f + f * 0.7f)

            0.018f + 0.07f * haze * haze + 0.14f * body * depth + 0.40f * rim + 0.50f * gloss
        }
    }
}

/**
 * Дым: клубы, а не облако шума.
 *
 * Дым отличает от тумана одно — он **закручен**. Завиток даёт не сам шум, а
 * искривление домена, взятое дважды: координата смещается суммой октав, затем
 * смещается ещё раз суммой, посчитанной уже от смещённой координаты. Тогда
 * крупный завиток несёт на себе завиток вдвое мельче, тот — ещё вдвое, и клуб
 * получается самоподобным, как в натуре. Одна ступень даёт мятое облако, две —
 * дым.
 *
 * Домен сжат по вертикали (`v * 0.75f` против `u * 1.3f`): струя вытянута вверх,
 * потому что дым поднимается, а не растекается. Второе искривление к тому же
 * смещено вверх — завитки заваливаются по ходу подъёма.
 *
 * Плотность падает кверху: дым редеет, разбавляясь. Без этого выходит ровная
 * муть во весь экран, то есть туман, а не струя.
 *
 * Нити по краю клуба — гребневой мультифрактал, и берутся они **с малым весом**:
 * у дыма резких линий нет, но и совсем гладким он не бывает.
 */
private fun smoke(s: Sheet) {
    val seed = s.random.nextInt()
    val sheetHeight = s.h
    s.fill {
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.3f, v * 0.75f, 5, seed)
            val qy = fbm(u * 1.3f + 3.7f, v * 0.75f + 9.1f, 5, seed + 13)
            val rx = fbm(u * 2.6f + 1.6f * qx + 1.9f, v * 1.5f + 1.6f * qy - 0.7f, 4, seed + 29)
            val ry = fbm(u * 2.6f + 1.6f * qx + 7.3f, v * 1.5f + 1.6f * qy + 2.1f, 4, seed + 37)
            val wx = u + 0.62f * qx + 0.30f * rx
            val wy = v + 0.62f * qy + 0.30f * ry

            val body = fbm01(wx * 1.8f, wy * 1.15f, 6, seed + 53)
            val wisp = ridged(wx * 3.6f, wy * 2.3f, 5, seed + 71)

            // Клуб, а не шум: порог оставляет яркое ядро и мягкий край. Без него
            // поле держится около середины, и под вуалью 56/255 от дыма не
            // остаётся ничего — ровная муть в одну восьмую силы.
            val puff = smoothstep(0.28f, 0.88f, body)
            // Кверху редеет. Треть плотности наверху остаётся: совсем в ноль
            // уводить нельзя, иначе верх экрана пустеет.
            val rise = 0.35f + 0.65f * (v / sheetHeight)
            0.02f + 0.62f * puff * rise + 0.16f * wisp * wisp * puff
        }
    }
}

/**
 * Молния: разряд, а не нарисованная ломаная.
 *
 * Канал разряда строится тем же дроблением со смещением середины, что и
 * трещины, и по той же причине: ступенчатый лидер идёт именно так — пробивает
 * очередное колено, отклоняется, пробивает следующее, и отношение отклонения к
 * длине колена одно и то же на всех масштабах. Отсюда и вид: увеличенный кусок
 * молнии неотличим от целой молнии.
 *
 * Ветви уходят вниз и в стороны и гаснут по поколениям — у настоящего разряда
 * до земли доходит один канал, остальные обрываются.
 *
 * Каждое поколение рисуется трижды: широко и очень тускло (корона), средне
 * (свечение) и узко, почти в полную яркость (сам канал). Именно эта тройка и
 * читается как свет, а не как проведённая линия — одна линия любой толщины
 * выглядит нарисованной.
 */
private fun lightning(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random
    val generations = 4
    val strands = Array(generations) { Path() }

    fun channel(x0: Float, y0: Float, x1: Float, y1: Float, depth: Int, gen: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (depth == 0 || len < 0.005f) {
            strands[gen].moveTo(x0, y0)
            strands[gen].lineTo(x1, y1)
            return
        }
        val shift = (random.nextFloat() - 0.5f) * len * 0.34f
        val mx = (x0 + x1) * 0.5f - dy / len * shift
        val my = (y0 + y1) * 0.5f + dx / len * shift
        channel(x0, y0, mx, my, depth - 1, gen)
        channel(mx, my, x1, y1, depth - 1, gen)
        // Ветвь уходит вперёд и вбок: назад, к облаку, разряд не идёт.
        if (gen + 1 < generations && depth >= 3 && random.nextFloat() < 0.5f) {
            val turn = (0.35f + random.nextFloat() * 0.55f) * (if (random.nextBoolean()) 1f else -1f)
            val c = cos(turn)
            val sn = sin(turn)
            val bx = (dx * c - dy * sn) / len
            val by = (dx * sn + dy * c) / len
            val branchReach = len * (0.5f + random.nextFloat() * 0.6f)
            channel(mx, my, mx + bx * branchReach, my + by * branchReach, depth - 1, gen + 1)
        }
    }

    s.fill {
        return@fill { u: Float, v: Float ->
            // Грозовая муть: разряд должен быть в чём-то, иначе он висит в пустоте.
            val qx = fbm(u * 1.2f, v * 1.2f, 4, seed)
            val cloud = fbm01(u * 1.9f + 0.4f * qx, v * 1.9f, 6, seed + 23)
            val churn = ridged(u * 3.4f, v * 3.4f, 5, seed + 41)
            0.015f + 0.10f * cloud * cloud + 0.05f * churn * cloud
        }
    }

    // Разряды идут сверху вниз, каждый со своей точкой входа.
    repeat(3) { index ->
        val startX = s.w * (0.15f + random.nextFloat() * 0.7f)
        val endX = startX + (random.nextFloat() - 0.5f) * s.w * 0.8f
        val startY = -0.05f - random.nextFloat() * 0.1f
        val endY = s.h * (0.75f + random.nextFloat() * 0.35f)
        channel(startX, startY, endX, endY, 7, 0)
    }

    s.drawing { canvas ->
        val paint = s.paint
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        // Корона — по всем поколениям сразу, чтобы свечение одного канала не
        // легло поверх ядра соседнего.
        for (gen in generations - 1 downTo 0) {
            paint.strokeWidth = 0.030f / (gen + 1f)
            paint.alpha = 16 - gen * 3
            canvas.drawPath(strands[gen], paint)
        }
        for (gen in generations - 1 downTo 0) {
            paint.strokeWidth = 0.010f / (gen + 1f)
            paint.alpha = 54 - gen * 10
            canvas.drawPath(strands[gen], paint)
        }
        for (gen in generations - 1 downTo 0) {
            paint.strokeWidth = 0.0026f / (gen + 1f) + 0.0008f
            paint.alpha = when (gen) {
                0 -> 245
                1 -> 190
                2 -> 140
                else -> 95
            }
            canvas.drawPath(strands[gen], paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.alpha = 255
    }
}

/**
 * Снег: дендритные кристаллы, а не нарисованные звёздочки.
 *
 * Снежинка — едва ли не самый наглядный фрактал, какой встречается сам по себе,
 * и держится он на двух правилах решётки льда. Первое: луч ветвится строго под
 * 60°, потому что таков угол между осями кристалла. Второе: каждая боковая ветвь
 * растёт по тому же правилу, что и луч, из которого вышла, — поэтому увеличенный
 * отросток неотличим от целого луча.
 *
 * Отсюда и построение. Луч строится рекурсией: отрезок, на нём несколько
 * станций, из каждой — пара отростков под ±60°, и к ним то же правило. Ветви
 * укорачиваются к вершине луча, поэтому кристалл получается каплевидным, а не
 * ёлочкой.
 *
 * Шесть лучей — это **один** луч, повёрнутый шесть раз холстом. Так и надо: у
 * настоящей снежинки лучи одинаковы, потому что росли в одних условиях, а шесть
 * независимо случайных лучей дали бы кляксу. При этом кристаллы отличаются друг
 * от друга: геометрия строится своя на каждый.
 *
 * Размеры идут тремя ярусами — несколько крупных, вдвое больше средних, вдвое
 * больше мелких. Тот же закон, что у октав, только для снежинок: без него снег
 * лежит в одной плоскости и выглядит наклейкой.
 */
private fun snow(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random
    val levels = 3

    s.fill {
        return@fill { u: Float, v: Float ->
            // Морозная дымка: кристаллы обязаны на чём-то лежать.
            val frost = fbm01(u * 2.8f, v * 2.8f, 5, seed)
            val needles = ridged(u * 8.5f, v * 8.5f, 4, seed + 31)
            0.02f + 0.09f * frost * frost + 0.06f * needles * needles * frost
        }
    }

    // Отросток: отрезок плюс пары ветвей под ±60° из нескольких точек на нём.
    fun spur(
        paths: Array<Path>,
        ox: Float,
        oy: Float,
        dx: Float,
        dy: Float,
        len: Float,
        level: Int,
    ) {
        if (level >= levels || len < 0.0035f) return
        paths[level].moveTo(ox, oy)
        paths[level].lineTo(ox + dx * len, oy + dy * len)
        val stations = 2 + random.nextInt(3)
        for (i in 1..stations) {
            val t = i.toFloat() / (stations + 1)
            val px = ox + dx * len * t
            val py = oy + dy * len * t
            // Ветви короче к вершине — кристалл выходит каплевидным, а не ёлочкой.
            val sub = len * (0.62f - 0.34f * t) * (0.75f + random.nextFloat() * 0.4f)
            for (side in 0 until 2) {
                val sn = if (side == 0) 0.8660254f else -0.8660254f
                spur(paths, px, py, dx * 0.5f - dy * sn, dx * sn + dy * 0.5f, sub, level + 1)
            }
        }
    }

    class Flake(
        val x: Float,
        val y: Float,
        val radius: Float,
        val turn: Float,
        val arm: Array<Path>,
        val core: Path,
        val alpha: Int,
    )

    val flakes = ArrayList<Flake>()

    fun build(x: Float, y: Float, radius: Float, alpha: Int) {
        val arm = Array(levels) { Path() }
        spur(arm, 0f, 0f, 0f, -1f, radius, 0)
        // Шестиугольная пластинка в середине: с неё дендрит и начинает расти.
        val core = Path()
        val plate = radius * 0.11f
        for (i in 0 until 6) {
            val a = i * (TAU / 6f)
            val px = cos(a) * plate
            val py = sin(a) * plate
            if (i == 0) core.moveTo(px, py) else core.lineTo(px, py)
        }
        core.close()
        flakes += Flake(x, y, radius, random.nextFloat() * 60f, arm, core, alpha)
    }

    // Три яруса: крупных мало, мелких много — тот же закон, что у октав.
    var count = 3
    var size = 0.16f
    var ink = 155
    repeat(3) {
        repeat(count) {
            build(
                s.pointX(),
                s.pointY(),
                size * (0.75f + random.nextFloat() * 0.5f),
                ink,
            )
        }
        count *= 3
        size *= 0.45f
        ink = (ink * 0.68f).toInt()
    }

    s.drawing { canvas ->
        val paint = s.paint
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        for (flake in flakes) {
            canvas.save()
            canvas.translate(flake.x, flake.y)
            canvas.rotate(flake.turn)
            paint.style = Paint.Style.FILL
            paint.alpha = flake.alpha
            canvas.drawPath(flake.core, paint)
            paint.style = Paint.Style.STROKE
            repeat(6) {
                for (level in 0 until levels) {
                    paint.strokeWidth = (0.0016f + flake.radius * 0.020f) / (level + 1f)
                    paint.alpha = (flake.alpha * (1f - level * 0.22f)).toInt().coerceIn(0, 255)
                    canvas.drawPath(flake.arm[level], paint)
                }
                canvas.rotate(60f)
            }
            canvas.restore()
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.alpha = 255
    }
}

/** Раздаёт строки `0 until rows` по потокам: `work(from, to)` на каждую полосу. */
private fun parallelRows(rows: Int, work: (Int, Int) -> Unit) {
    val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val bands = min(cores, rows)
    if (bands <= 1) {
        work(0, rows)
        return
    }
    val step = (rows + bands - 1) / bands
    val threads = ArrayList<Thread>(bands)
    var start = 0
    while (start < rows) {
        val from = start
        val to = min(rows, start + step)
        threads += Thread({ work(from, to) }, "NovaTexture-rows-$from")
        start = to
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
}

/**
 * Фрактал: погружение в множество Мандельброта.
 *
 * Берётся не обзорный вид, а окрестность граничной точки, которую знают как
 * богатую: долина морских коньков, усик на вещественной оси, точка Фейгенбаума,
 * спирали. Вокруг такой точки множество самоподобно: каждая завитушка несёт
 * внутри миниатюрную копию целого, и на любом увеличении картина продолжается, —
 * отсюда ощущение, что падаешь вглубь.
 *
 * Вид зависит от зерна: точка берётся из списка, масштаб — от 1e-3 до глубины,
 * которую выдерживает точка (список хранит её предел: у приблизительных координат
 * глубже не нырнуть), угол поворота случаен, плюс смещение от центра. Затем
 * проверяется, что кадр не пуст и не залит нутром множества, иначе берётся другой.
 *
 * Считается в `Double` (глубина до 1e-7 не умещается во `Float`) на уменьшенной
 * сетке с длинной стороной 380, потом растягивается билинейно. Число итераций
 * ограничено и растёт с глубиной. Плавный счётчик итераций `ν = n + 1 − log₂ ln|z|`
 * не даёт ступенек; по нему идут полосы альфы. Нутро множества кодируется
 * отрицательным числом и несёт в себе фазу последней точки орбиты — слабый узор
 * вместо чёрной заливки. При билинейной выборке в среднем участвуют только вышедшие
 * из множества соседи, иначе по границе легла бы серая каёмка.
 */
private fun fractal(s: Sheet) {
    val random = s.random

    // Центры: re, im, предельный десятичный порядок масштаба.
    val centres = arrayOf(
        doubleArrayOf(-0.743643887037151, 0.131825904205330, 7.0),
        doubleArrayOf(-1.401155189092, 0.0, 6.5),
        doubleArrayOf(0.25, 0.0, 6.0),
        doubleArrayOf(-0.77568377, 0.13646737, 4.5),
        doubleArrayOf(-1.25066, 0.02012, 4.0),
        doubleArrayOf(-0.1011, 0.9563, 3.8),
        doubleArrayOf(-0.16070135, 1.0375665, 4.5),
    )

    val longSide = 380
    val gh: Int
    val gw: Int
    if (s.height >= s.width) {
        gh = longSide
        gw = max(2, (longSide * s.width / s.height.toFloat()).toInt())
    } else {
        gw = longSide
        gh = max(2, (longSide * s.height / s.width.toFloat()).toInt())
    }

    // Плавный счётчик итераций; нутро — число меньше нуля.
    fun orbit(cr: Double, ci: Double, maxIter: Int): Float {
        val q = (cr - 0.25) * (cr - 0.25) + ci * ci
        if (q * (q + (cr - 0.25)) <= 0.25 * ci * ci) return -1.5f
        if ((cr + 1.0) * (cr + 1.0) + ci * ci <= 0.0625) return -1.5f
        var zr = 0.0
        var zi = 0.0
        var n = 0
        while (n < maxIter) {
            val zr2 = zr * zr
            val zi2 = zi * zi
            if (zr2 + zi2 > 1e4) {
                val modLog = ln(sqrt(zr2 + zi2))
                return (n + 1 - ln(modLog) / ln(2.0)).toFloat()
            }
            zi = 2.0 * zr * zi + ci
            zr = zr2 - zi2 + cr
            n++
        }
        val phase = (atan2(zi, zr) / 6.283185307179586 + 0.5).toFloat()
        return -(1f + phase)
    }

    var centreRe = 0.0
    var centreIm = 0.0
    var viewShort = 1.0
    var rotation = 0.0
    var maxIter = 300

    for (attempt in 0 until 8) {
        val c = centres[random.nextInt(centres.size)]
        val depth = 3.0 + random.nextDouble() * (c[2] - 3.0).coerceAtLeast(0.0)
        val scale = Math.pow(10.0, -depth)
        centreRe = c[0] + (random.nextDouble() - 0.5) * scale * 0.8
        centreIm = c[1] + (random.nextDouble() - 0.5) * scale * 0.8
        viewShort = scale
        rotation = random.nextDouble() * 6.283185307179586
        maxIter = (180 + 50 * depth).toInt().coerceAtMost(420)

        // Проба: не пустой ли кадр и не залит ли нутром.
        var inside = 0
        var rich = 0
        val probes = 300
        for (i in 0 until probes) {
            val px = (random.nextDouble() - 0.5) * viewShort * s.w
            val py = (random.nextDouble() - 0.5) * viewShort * s.h
            val cr = centreRe + px * cos(rotation) - py * sin(rotation)
            val ci = centreIm + px * sin(rotation) + py * cos(rotation)
            val nu = orbit(cr, ci, maxIter)
            if (nu < 0f) inside++ else if (nu > 12f) rich++
        }
        if (inside < probes * 0.55 && rich >= probes * 0.22) break
    }

    val grid = FloatArray(gw * gh)
    val cRe = centreRe
    val cIm = centreIm
    val cosR = cos(rotation)
    val sinR = sin(rotation)
    val viewW = viewShort * s.w
    val viewH = viewShort * s.h
    val iterLimit = maxIter
    parallelRows(gh) { from, to ->
        for (gy in from until to) {
            val py = (gy / (gh - 1.0) - 0.5) * viewH
            for (gx in 0 until gw) {
                val px = (gx / (gw - 1.0) - 0.5) * viewW
                grid[gy * gw + gx] = orbit(
                    cRe + px * cosR - py * sinR,
                    cIm + px * sinR + py * cosR,
                    iterLimit,
                )
            }
        }
    }

    val span = s.span
    val kx = (gw - 1).toFloat() / (s.width - 1)
    val ky = (gh - 1).toFloat() / (s.height - 1)
    val centreU = s.w * 0.5f
    val centreV = s.h * 0.5f
    s.fill {
        return@fill shade@{ u: Float, v: Float ->
            var gx = u * span * kx
            var gy = v * span * ky
            if (gx < 0f) gx = 0f else if (gx > gw - 1.001f) gx = gw - 1.001f
            if (gy < 0f) gy = 0f else if (gy > gh - 1.001f) gy = gh - 1.001f
            val ix = gx.toInt()
            val iy = gy.toInt()
            val fx = gx - ix
            val fy = gy - iy
            val a = grid[iy * gw + ix]
            val b = grid[iy * gw + ix + 1]
            val c = grid[(iy + 1) * gw + ix]
            val d = grid[(iy + 1) * gw + ix + 1]
            val nearest = if (fy < 0.5f) (if (fx < 0.5f) a else b) else (if (fx < 0.5f) c else d)

            // Нутро: слабый узор по фазе последней точки орбиты.
            if (nearest < 0f) {
                val phase = -(nearest + 1f)
                return@shade 0.025f + 0.07f * (0.5f + 0.5f * sin(TAU * phase * 3f))
            }

            var sum = 0f
            var weight = 0f
            val wa = (1f - fx) * (1f - fy)
            val wb = fx * (1f - fy)
            val wc = (1f - fx) * fy
            val wd = fx * fy
            if (a >= 0f) { sum += a * wa; weight += wa }
            if (b >= 0f) { sum += b * wb; weight += wb }
            if (c >= 0f) { sum += c * wc; weight += wc }
            if (d >= 0f) { sum += d * wd; weight += wd }
            val nu = if (weight > 1e-4f) sum / weight else nearest

            // Близость к границе: чем дольше орбита держалась, тем ярче.
            val prox = smoothstep(1.5f, 90f, nu)
            val wide = 0.5f + 0.5f * sin(TAU * nu * 0.055f)
            val fine = nu * 0.22f - floor(nu * 0.22f)
            val line = smoothstep(0f, 0.18f, fine) * smoothstep(0.5f, 0.18f, fine)
            val dx = u - centreU
            val dy = v - centreV
            val vignette = 1f - 0.3f * smoothstep(0.2f, 0.8f, hypot(dx, dy))

            (0.05f + (0.12f + 0.55f * prox) * (0.25f + 0.75f * wide) + 0.12f * line * prox) * vignette
        }
    }
}

/**
 * Матрица: цифровой дождь.
 *
 * Колонки глифов с яркой головой внизу и шлейфом, гаснущим кверху: колонка
 * «вылезает» снизу и уходит вверх. В каждой колонке снизу вверх идёт несколько
 * струй со случайными длинами и разрывами; часть колонок тусклая, часть яркая,
 * часть пуста. Под дождём слабой сеткой лежат погашенные глифы — без неё между
 * струями экран пустел бы.
 *
 * Глифы — полуширинная катакана и цифры, рисуются кистью с моноширинным
 * шрифтом. На устройстве без катаканы в шрифтах она рисовалась бы «тофу»
 * (прямоугольниками), поэтому каждый знак проверяется через [Paint.hasGlyph], и
 * при нехватке катаканы остаются цифры и латинские символы.
 *
 * Кисть работает в пикселях: холст листа масштабируется в единицы узора, а
 * текст мелкого размера в масштабированном холсте теряет метрики — масштаб
 * здесь снимается обратно.
 */
private fun matrix(s: Sheet) {
    val random = s.random
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    val glyphs = ArrayList<String>()
    val kana = (0xFF66..0xFF9D).map { it.toChar().toString() }.filter {
        try {
            textPaint.hasGlyph(it)
        } catch (_: Throwable) {
            false
        }
    }
    if (kana.size >= 24) glyphs.addAll(kana)
    for (ch in "0123456789") glyphs.add(ch.toString())
    for (ch in "ZTSXEH:.=*+<>|") glyphs.add(ch.toString())

    val cell = 0.0185f
    val cols = max(1, (s.w / cell).toInt())
    val cw = s.w / cols
    val ch = cw * 1.2f
    val rows = (s.h / ch).toInt() + 1
    val cellGlyph = Array(cols * rows) { glyphs[random.nextInt(glyphs.size)] }

    s.drawing { canvas ->
        val k = s.span
        canvas.scale(1f / k, 1f / k)
        textPaint.textSize = ch * 0.9f * k
        val baseline = ch * 0.82f

        fun glyph(col: Int, row: Int, alpha: Int, bold: Boolean) {
            if (alpha < 6) return
            textPaint.alpha = if (alpha > 255) 255 else alpha
            textPaint.isFakeBoldText = bold
            canvas.drawText(
                cellGlyph[row * cols + col],
                (col + 0.5f) * cw * k,
                (row * ch + baseline) * k,
                textPaint,
            )
        }

        // Погашенная сетка: слабая, чтобы струи читались поверх.
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                glyph(col, row, 8 + random.nextInt(8), false)
            }
        }

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (col in 0 until cols) {
            if (random.nextFloat() < 0.12f) continue
            val kind = random.nextFloat()
            val power = when {
                kind < 0.18f -> 0.22f + random.nextFloat() * 0.18f
                kind < 0.78f -> 0.6f + random.nextFloat() * 0.25f
                else -> 0.9f + random.nextFloat() * 0.1f
            }
            var head = rows - 1 + random.nextInt(8) - random.nextInt(max(1, (rows * 0.55f).toInt()))
            while (head > 1) {
                val len = 5 + random.nextInt(rows / 2 + 1)
                for (n in 0..len) {
                    val row = head - n
                    if (row < 0) break
                    if (row >= rows) continue
                    val t = 1f - n.toFloat() / len
                    var a = 255f * power * Math.pow(t.toDouble(), 1.6).toFloat() *
                        (0.55f + 0.45f * random.nextFloat())
                    if (n > 0 && random.nextFloat() < 0.05f) a *= 1.6f
                    if (n == 0) {
                        halo.alpha = (50 * power).toInt()
                        canvas.drawCircle(
                            (col + 0.5f) * cw * k, (row * ch + ch * 0.5f) * k, cw * 0.9f * k, halo,
                        )
                        glyph(col, row, 255, true)
                    } else {
                        glyph(col, row, a.toInt(), false)
                    }
                }
                head -= len + 2 + random.nextInt(max(1, rows / 3))
            }
        }
    }
}
