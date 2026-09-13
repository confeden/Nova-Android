package com.example.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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
            NovaAppearance.Texture.MICA -> mica(sheet)
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
            NovaAppearance.Texture.GLOW -> glow(sheet)
            NovaAppearance.Texture.LIGHTNING -> lightning(sheet)
            NovaAppearance.Texture.SNOW -> snow(sheet)
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
 * Слюда: смятый слоистый сланец, по которому рассыпаны блёстки.
 *
 * Прежний вариант набирал слюду россыпью нарисованных линз. На плитке 256 точек
 * это читалось, на целом экране — нет: линзы оказались мелким конфетти без
 * общего строя, и владелец назвал фактуру недостаточно красивой.
 *
 * Теперь фактура изображает то, чем слюда и является в природе: тонко
 * расслоенную породу. Слои — дробная часть поля, которое само согнуто двумя
 * суммами октав, поэтому пласт идёт складкой, а не по линейке; ламина —
 * подчёркнутая граница между слоями. Блёстки берутся клеточным полем: каждая
 * чешуйка получает собственную ориентацию из хеша своей ячейки, и к свету
 * повёрнута лишь часть из них — именно поэтому слюда искрится, а не светится
 * целиком.
 *
 * Поле сжато по горизонтали и растянуто по вертикали: пласт лежит.
 */
private fun mica(s: Sheet) {
    val seed = s.random.nextInt()
    s.fill {
        val cell = Cellular()
        return@fill { u: Float, v: Float ->
            val fold = fbm(u * 0.9f, v * 0.34f, 5, seed)
            val fold2 = fbm(u * 2.1f + 3.1f, v * 0.7f + 7.7f, 4, seed + 13)
            val layer = v * 9.5f + 2.6f * fold + 0.85f * fold2

            val t = layer - floor(layer)
            // Ламина: яркая узкая граница пласта плюс мягкое тело под ней.
            val edge = smoothstep(0.88f, 1f, t) + smoothstep(0.12f, 0f, t)
            val bed = 0.35f + 0.65f * smoothstep(0f, 0.6f, t)

            val body = fbm01(u * 3.2f, v * 1.4f, 5, seed + 29)

            // Чешуйки: сетка вытянута поперёк слоёв — слюда колется вдоль них.
            cell.at(u * 26f, v * 17f, seed + 101)
            val plate = smoothstep(0.36f, 0.04f, cell.f1)
            val facing = cell.tone()
            val glint = if (facing > 0.70f) plate * (facing - 0.70f) * 3.3f else 0f

            0.035f + 0.13f * body * bed + 0.24f * edge * (0.35f + 0.65f * body) + 0.70f * glint
        }
    }
}

/**
 * Дерево: годовые кольца как линии уровня искривлённого радиального поля.
 *
 * Окружность рисунка не даёт. Древесиной кольцо делают три вещи сразу, и все три
 * здесь есть: искривление радиуса суммой октав на двух масштабах (кольцо гнётся
 * и целиком, и в мелочах), узкая поздняя древесина вместо синуса (граница
 * годового слоя — тонкая тёмная полоса, а не половина периода) и сучок — тот же
 * ствол в миниатюре, который расталкивает вокруг себя кольца.
 *
 * Сверх того две подробности, ради которых доска и выглядит доской: сердцевинные
 * лучи — тонкие штрихи поперёк колец — и сосуды, мелкие поры, которые у
 * кольцесосудистых пород сидят только в ранней древесине. Их отсутствие и делало
 * прежний вариант похожим на мишень.
 */
private fun wood(s: Sheet) {
    val seed = s.random.nextInt()
    // Сердцевина вынесена за край: доска пилена не через центр ствола.
    val pithX = -0.35f - s.random.nextFloat() * 0.25f
    val pithY = s.h * (0.35f + s.random.nextFloat() * 0.3f)
    val knotX = s.w * (0.3f + s.random.nextFloat() * 0.45f)
    val knotY = s.h * (0.2f + s.random.nextFloat() * 0.6f)
    val knotR = 0.075f + s.random.nextFloat() * 0.035f

    s.fill {
        val cell = Cellular()
        return@fill { u: Float, v: Float ->
            val warp = 0.052f * fbm(u * 2.1f, v * 2.1f, 5, seed) +
                0.016f * fbm(u * 7.3f, v * 7.3f, 4, seed + 31)

            // Кольца ствола: поле сплющено по x — доска пилена вдоль, и дуги
            // приходят на неё пологими.
            val dx = (u - pithX) * 0.42f
            val dy = (v - pithY) * 1.25f
            val core = hypot(dx, dy)

            // Сучок и мягкая шапка его влияния.
            val kx = u - knotX
            val ky = (v - knotY) * 1.3f
            val kr = hypot(kx, ky)
            val mask = clamp01(1f - smoothstep(knotR * 0.9f, knotR * 3.4f, kr))

            // Рядом с сучком радиус искусственно больше: кольца обходят его, и
            // это читается как «кафедральный» рисунок доски.
            val trunkPhase = (core + warp + mask * 0.16f) * 12.5f
            val knotPhase = (kr + warp * 0.4f) * 44f
            val phase = trunkPhase + (knotPhase - trunkPhase) * mask

            val ring = 0.5f + 0.5f * sin(TAU * phase)
            val r2 = ring * ring
            val late = r2 * r2 * r2                       // узкая поздняя древесина
            val early = 1f - late

            // Сердцевинные лучи: штрихи вдоль радиуса, то есть поперёк колец.
            val ang = atan2(dy, dx)
            val rays = clamp01(fbm01(ang * 26f, core * 2.6f, 3, seed + 57) * 1.6f - 0.75f)

            // Сосуды ранней древесины.
            cell.at(u * 46f, v * 21f, seed + 83)
            val pore = smoothstep(0.26f, 0.06f, cell.f1) * (if (cell.tone() > 0.45f) 1f else 0f)

            val fibre = fbm01(u * 9f, v * 44f, 3, seed + 97)
            0.05f + 0.30f * late + 0.10f * early * fibre + 0.11f * rays * early +
                0.16f * pore * early
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
 * Трава: поле отдельных травинок.
 *
 * Здесь стояла рекурсивная вайя папоротника. Фрактально — да, травой — нет:
 * владелец так и сказал, «должна быть именно как трава». У травы самоподобия в
 * форме листа нет вовсе, она берёт другим: травинка — простая дуга, а поле
 * набирается тем, что травинок много, они разной высоты и разного наклона, и
 * плотность их меняется пятнами.
 *
 * Поэтому травинка здесь — залитая фигура из двух дуг, сходящихся в остриё:
 * лист сужается к кончику и валится под собственным весом тем сильнее, чем он
 * длиннее. Ровная линия постоянной ширины читалась бы щёткой.
 *
 * Три яруса по высоте рисуются от дальнего к ближнему и разной яркостью — это
 * даёт глубину, без которой поле выглядит плоской штриховкой. Дальний ярус
 * гуще и тусклее, ближний реже и ярче, как и бывает на просвет.
 *
 * Плотность решает поле октав: где оно провисло — проплешина, где поднялось —
 * куртина. Порог «расти или нет» здесь допустим ровно потому, что травинок
 * тысяча: пустое место читается проплешиной, а не забытым куском экрана.
 */
private fun grass(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random
    val tiers = 3

    s.fill {
        return@fill { u: Float, v: Float ->
            // Подстилка: земля и прель, по которым растёт куртина.
            val soil = fbm01(u * 3.6f, v * 3.6f, 5, seed)
            val litter = fbm01(u * 17f, v * 26f, 3, seed + 41)
            0.02f + 0.07f * soil * soil + 0.04f * litter * soil
        }
    }

    val blades = Array(tiers) { Path() }

    // Травинка: две дуги от основания к общему острию. Управляющая точка обеих
    // вынесена по ходу листа, поэтому у основания он идёт прямо, а валится ближе
    // к кончику — так гнётся лист, нагруженный собственным весом.
    fun blade(x: Float, y: Float, height: Float, lean: Float, tier: Int) {
        val bend = lean * (0.55f + random.nextFloat() * 0.5f)
        val midX = x + sin(lean * 0.45f) * height * 0.5f
        val midY = y - cos(lean * 0.45f) * height * 0.55f
        val tipX = x + sin(lean + bend) * height
        val tipY = y - cos(lean + bend) * height * 0.92f
        val half = height * 0.055f + 0.0016f
        val path = blades[tier]
        path.moveTo(x - half, y)
        path.quadTo(midX - half * 0.55f, midY, tipX, tipY)
        path.quadTo(midX + half * 0.55f, midY, x + half, y)
        path.close()
    }

    // Сетка со сдвигом, а не случайные броски: при тысяче травинок случайные
    // броски оставляют заметные прорехи и сгустки, которых в дернине не бывает.
    val step = 0.028f
    var row = 0
    while (row * step < s.h + step) {
        var col = 0
        while (col * step < s.w + step) {
            val bx = (col + random.nextFloat()) * step
            val by = (row + random.nextFloat()) * step
            col++
            val density = fbm01(bx * 3.1f, by * 3.1f, 4, seed + 7)
            if (density < 0.36f) continue
            val tier = random.nextInt(tiers)
            // Ближний ярус выше и его меньше: два броска из трёх уходят в дальний.
            val scale = when (tier) {
                0 -> 0.055f + random.nextFloat() * 0.035f
                1 -> 0.085f + random.nextFloat() * 0.055f
                else -> 0.13f + random.nextFloat() * 0.085f
            }
            if (tier == 2 && random.nextFloat() > 0.45f) continue
            blade(
                bx, by,
                scale * (0.7f + density * 0.6f),
                (random.nextFloat() - 0.5f) * 1.15f,
                tier,
            )
        }
        row++
    }

    s.drawing { canvas ->
        val paint = s.paint
        paint.style = Paint.Style.FILL
        for (tier in 0 until tiers) {
            paint.alpha = 34 + tier * 30
            canvas.drawPath(blades[tier], paint)
        }
        paint.alpha = 255
    }
}

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
 * Звёздное небо: туманность плюс мультипликативный каскад звёзд.
 *
 * Прежний вариант был одними звёздами, и на весь экран их не хватало: небо
 * выглядело россыпью точек по пустоте. Здесь сначала строится туманность —
 * искривлённое поле с нитями (гребневой мультифрактал) и пустотами (облако,
 * поджатое порогом), — и уже по ней раскладываются звёзды.
 *
 * Каскад остался прежним и остаётся правильным: небо неоднородно не «в среднем»,
 * а на всех масштабах сразу — скопления сидят в сверхскоплениях, между ними
 * пустоты крупнее самих скоплений. Квадрат делится на четыре, каждая четверть
 * получает свой множитель плотности, множители перемножаются вниз по уровням.
 * Множитель подобран так, что его среднее ровно 1: сколько ветвей потеряло,
 * столько же другие приобрели, и общее число звёзд не зависит от глубины.
 *
 * У самых ярких звёзд рисуются лучи. Это не украшение, а то, по чему глаз
 * отличает звезду от точки: луч даёт диафрагма, и он есть на любом снимке неба.
 */
private fun starfield(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random

    s.fill {
        return@fill { u: Float, v: Float ->
            val qx = fbm(u * 1.3f, v * 1.3f, 5, seed)
            val qy = fbm(u * 1.3f + 4.7f, v * 1.3f + 8.9f, 5, seed + 13)
            val wx = u + 0.45f * qx
            val wy = v + 0.45f * qy

            val cloud = fbm01(wx * 1.7f, wy * 1.7f, 6, seed + 41)
            val clear = smoothstep(0.38f, 0.72f, cloud)
            val filament = ridged(wx * 2.6f, wy * 2.6f, 6, seed + 29)
            val dust = fbm01(u * 9f, v * 9f, 4, seed + 59)

            0.012f + 0.10f * clear * cloud + 0.17f * filament * filament * clear +
                0.03f * dust * clear
        }
    }

    // Звезда — четыре числа: x, y, радиус, альфа. Список считается целиком до
    // отрисовки: холст трогается один раз.
    val stars = ArrayList<FloatArray>(1024)

    fun cascade(x: Float, y: Float, size: Float, depth: Int, weight: Float) {
        if (weight < 0.06f) return
        if (x > s.w || y > s.h || x + size < 0f || y + size < 0f) return
        if (depth == 0) {
            val exact = weight * 0.5f
            var count = exact.toInt()
            // Вероятностное округление: без него дробная часть веса терялась бы
            // на каждом листе, и слабые ветви не давали бы вообще ничего.
            if (random.nextFloat() < exact - count) count++
            repeat(if (count > 10) 10 else count) {
                // Произведение двух равномерных: ярких звёзд мало, тусклых много —
                // как в настоящей функции светимости.
                val q = random.nextFloat() * random.nextFloat()
                val lift = if (weight > 8f) 8f else weight
                stars += floatArrayOf(
                    x + random.nextFloat() * size,
                    y + random.nextFloat() * size,
                    0.0007f + q * (0.0022f + lift * 0.00022f),
                    (44f + q * 196f + lift * 12f).coerceAtMost(240f),
                )
            }
            return
        }
        val half = size * 0.5f
        for (quadrant in 0 until 4) {
            val m = 0.15f + 2.55f * random.nextFloat().let { it * it }
            cascade(
                x + (quadrant and 1) * half,
                y + (quadrant shr 1) * half,
                half,
                depth - 1,
                weight * m,
            )
        }
    }

    val root = if (s.w > s.h) s.w else s.h
    cascade(0f, 0f, root, 6, 1.7f)

    // Смещение общим полем, а не каждой звезде своё: соседи по листу получают
    // почти одинаковый сдвиг, поэтому скопление остаётся скоплением, а
    // прямоугольная решётка деления исчезает.
    for (star in stars) {
        val x0 = star[0]
        val y0 = star[1]
        star[0] = x0 + fbm(x0 * 1.1f, y0 * 1.1f, 3, seed + 211) * 0.09f
        star[1] = y0 + fbm(x0 * 1.1f + 3.7f, y0 * 1.1f, 3, seed + 223) * 0.09f
    }

    val halo = android.graphics.RadialGradient(
        0f, 0f, 1f,
        Color.argb(255, 255, 255, 255),
        Color.argb(0, 255, 255, 255),
        android.graphics.Shader.TileMode.CLAMP,
    )

    s.drawing { canvas ->
        val paint = s.paint
        // Ореолы первым слоем: они шире звезды, и ядро должно лечь поверх.
        paint.shader = halo
        for (star in stars) {
            if (star[2] < 0.0019f) continue
            paint.alpha = (star[3] * 0.16f).toInt()
            canvas.save()
            canvas.translate(star[0], star[1])
            val k = star[2] * 6.5f
            canvas.scale(k, k)
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        paint.shader = null

        // Лучи у самых ярких.
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (star in stars) {
            if (star[3] < 190f || star[2] < 0.0024f) continue
            val reach = star[2] * 7f
            paint.strokeWidth = star[2] * 0.5f
            paint.alpha = (star[3] * 0.5f).toInt()
            canvas.drawLine(star[0] - reach, star[1], star[0] + reach, star[1], paint)
            canvas.drawLine(star[0], star[1] - reach, star[0], star[1] + reach, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT

        for (star in stars) {
            paint.alpha = star[3].toInt()
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
 * Брызги: удар капли о поверхность.
 *
 * Прежний вариант («Кровь») пускал потёки вниз, и владелец прочёл их ровно так,
 * как они выглядели, — «сопли или застывший воск». Потёк и брызги — разные
 * явления: потёк течёт под своим весом, брызги разлетаются от удара, и рисунок
 * у них радиальный, а не отвесный.
 *
 * Отсюда устройство каждого пятна, и все три части обязательны — без любой из
 * них выходит клякса, а не брызги:
 *
 * 1. **Ядро** — неровное пятно в точке удара, набранное несколькими кружками;
 * 2. **Лучи** — цепочки, убегающие от ядра и сужающиеся к концу, с набухшей
 *    каплей на конце: жидкость уходит нитью, нить рвётся, и её головка
 *    собирается в шарик;
 * 3. **Спутники** — отдельные капли дальше лучей, оторвавшиеся совсем.
 *
 * Как и у прежней фактуры, ничего не рисуется кистью: всё набивается в поле
 * толщины кружками с носителем `(1 − d²/R²)²`, а поверхность — линия уровня
 * суммы. Только сумма даёт слияние (луч, вышедший из ядра, — одно тело с ним) и
 * валик по кромке, которого у обведённого контура не бывает.
 *
 * Отличие от потёка в шейдере — резкость. У брызг плёнка тонкая, край
 * оканчивается валиком почти сразу, поэтому полоса перехода узкая, кромка ярче,
 * а тело темнее: света возвращает край, а не заливка.
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

    fun burst(cx: Float, cy: Float, radius: Float) {
        // Ядро: несколько кружков вразброс — у пятна от удара край рваный, а не
        // круглый.
        repeat(6) {
            val a = random.nextFloat() * TAU
            val d = radius * random.nextFloat() * 0.55f
            splat(cx + cos(a) * d, cy + sin(a) * d, radius * (0.55f + random.nextFloat() * 0.5f), 1f)
        }

        val arms = 5 + random.nextInt(6)
        repeat(arms) {
            val angle = random.nextFloat() * TAU
            val reach = radius * (1.7f + random.nextFloat() * 3.6f)
            val curve = (random.nextFloat() - 0.5f) * 0.5f
            val steps = 14
            var endX = cx
            var endY = cy
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val a = angle + curve * t * t
                val d = reach * t
                endX = cx + cos(a) * d
                endY = cy + sin(a) * d
                // Нить сужается к концу почти до нуля: ровная ширина читается
                // как лапа, а не как летящая капля.
                splat(endX, endY, radius * (0.5f * (1f - t) + 0.06f), 1f)
            }
            // Головка оторвавшейся нити собирается в шарик.
            splat(endX, endY, radius * (0.22f + random.nextFloat() * 0.22f), 1.3f)
        }

        // Спутники: капли, улетевшие дальше лучей.
        repeat(arms * 3) {
            val angle = random.nextFloat() * TAU
            val d = radius * (1.9f + random.nextFloat() * 5.2f)
            splat(
                cx + cos(angle) * d,
                cy + sin(angle) * d,
                radius * (0.06f + random.nextFloat() * 0.20f),
                1.15f,
            )
        }
    }

    // Пятна по сетке со сдвигом: случайные броски на такой площади сбиваются в
    // кучу и оставляют пустые углы.
    val cellSize = 0.47f
    var row = 0
    while (row * cellSize < s.h + cellSize) {
        var col = 0
        while (col * cellSize < s.w + cellSize) {
            burst(
                (col + 0.15f + random.nextFloat() * 0.7f) * cellSize,
                (row + 0.15f + random.nextFloat() * 0.7f) * cellSize,
                0.016f + random.nextFloat() * 0.020f,
            )
            col++
        }
        row++
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
 * Сияние: свет как поле, а не как набор кругов.
 *
 * Основа — гребневой мультифрактал по искривлённому домену. Сама сумма октав —
 * это дымка; свет даёт не она, а её гребни: крупная жила ветвится нитями вдвое
 * тоньше, те — ещё вдвое, и так до предела октав. Это и есть искомая рекурсия:
 * узор одинаково устроен и вблизи, и издали.
 *
 * Очаги света входят в ту же формулу слагаемыми, а не ложатся друг на друга
 * готовыми кругами: пересечение двух очагов ярче каждого из них. Очаги идут
 * тремя масштабами по тому же закону, что октавы, — каждый следующий вдвое
 * мельче и вдвое многочисленнее.
 *
 * Насыщение мягкое (`x/(1+x)`), а не обрезкой: у самых ярких мест остаётся
 * градиент, а не плоское белое пятно.
 */
private fun glow(s: Sheet) {
    val seed = s.random.nextInt()
    val random = s.random

    val pools = ArrayList<FloatArray>(28)
    var count = 2
    var radius = 0.55f
    var force = 1f
    repeat(3) {
        repeat(count) {
            pools += floatArrayOf(
                s.pointX(),
                s.pointY(),
                radius * (0.7f + random.nextFloat() * 0.6f),
                force * (0.6f + random.nextFloat() * 0.5f),
            )
        }
        count *= 2
        radius *= 0.5f
        force *= 0.75f
    }
    // Массив, а не список: лямбда ниже вызывается на каждую точку, и обход
    // списка заводил бы итератор миллион раз.
    val lamps = pools.toTypedArray()

    s.fill {
        return@fill { u: Float, v: Float ->
            var pool = 0f
            for (p in lamps) {
                val dx = u - p[0]
                if (dx > p[2] || dx < -p[2]) continue
                val dy = v - p[1]
                if (dy > p[2] || dy < -p[2]) continue
                val q = (dx * dx + dy * dy) / (p[2] * p[2])
                if (q >= 1f) continue
                val t = 1f - q
                // Купол без exp: он здесь в горячем цикле, а квадрат даёт такой
                // же гладкий край и втрое дешевле.
                pool += t * t * p[3]
            }

            val qx = fbm(u * 1.5f, v * 1.5f, 4, seed)
            val qy = fbm(u * 1.5f + 6.3f, v * 1.5f + 2.9f, 4, seed + 19)
            val wx = u + 0.30f * qx
            val wy = v + 0.30f * qy

            val vein = ridged(wx * 3.0f, wy * 3.0f, 6, seed + 37)
            val haze = fbm01(u * 0.9f, v * 0.9f, 2, seed + 53)

            val light = (0.14f + pool) *
                (vein * 0.80f + vein * vein * vein * 0.9f) *
                (0.35f + 0.8f * haze)
            0.92f * light / (1f + light)
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
