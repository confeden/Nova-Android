package com.example.nova

/**
 * Минимальный кодировщик QR без зависимостей: байтовый режим (UTF-8), уровень коррекции M,
 * версия подбирается автоматически (1..40), маска выбирается по сумме штрафов.
 * Чистая JVM-логика, без Android-классов, поэтому проверяется обычным юнит-тестом.
 */
object QrCode {
    // Байт коррекции на блок и число блоков для уровня M, индекс = версия - 1.
    private val ECC_PER_BLOCK = intArrayOf(
        10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
        26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val NUM_BLOCKS = intArrayOf(
        1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
        17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )

    /** Матрица модулей [строка][столбец], true = тёмный, без тихой зоны. */
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (true) {
            if (version > 40) throw IllegalArgumentException("Text too long for a QR code")
            val ccBits = if (version < 10) 8 else 16
            if (data.size < (1 shl ccBits) && 4 + ccBits + 8 * data.size <= dataCapacity(version) * 8) break
            version++
        }
        val ccBits = if (version < 10) 8 else 16

        // Битовый поток: режим 0100, длина, данные, терминатор, добивка до байта и байты EC/11.
        val bits = ArrayList<Boolean>()
        fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
        put(4, 4)
        put(data.size, ccBits)
        for (b in data) put(b.toInt() and 0xFF, 8)
        val capBits = dataCapacity(version) * 8
        put(0, minOf(4, capBits - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0xEC
        while (bits.size < capBits) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val codewords = ByteArray(bits.size / 8)
        for (i in bits.indices) if (bits[i]) codewords[i / 8] = (codewords[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()

        val all = addEccAndInterleave(codewords, version)
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunc = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, isFunc, version)
        drawCodewords(modules, isFunc, all)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, isFunc, mask)
            drawFormat(modules, isFunc, mask)
            val p = penalty(modules)
            if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
            applyMask(modules, isFunc, mask) // XOR обратимо
        }
        applyMask(modules, isFunc, bestMask)
        drawFormat(modules, isFunc, bestMask)
        return modules
    }

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCapacity(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver - 1] * NUM_BLOCKS[ver - 1]

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsRemainder(data: ByteArray, from: Int, len: Int, degree: Int): IntArray {
        val div = IntArray(degree)
        div[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                div[j] = gfMul(div[j], root)
                if (j + 1 < degree) div[j] = div[j] xor div[j + 1]
            }
            root = gfMul(root, 2)
        }
        val rem = IntArray(degree)
        for (k in from until from + len) {
            val factor = (data[k].toInt() and 0xFF) xor rem[0]
            for (i in 0 until degree - 1) rem[i] = rem[i + 1]
            rem[degree - 1] = 0
            for (i in 0 until degree) rem[i] = rem[i] xor gfMul(div[i], factor)
        }
        return rem
    }

    private fun addEccAndInterleave(data: ByteArray, ver: Int): ByteArray {
        val numBlocks = NUM_BLOCKS[ver - 1]
        val eccLen = ECC_PER_BLOCK[ver - 1]
        val rawCw = rawModules(ver) / 8
        val numShort = numBlocks - rawCw % numBlocks
        val shortLen = rawCw / numBlocks
        val out = ByteArray(rawCw)
        val blocks = ArrayList<Pair<Int, Int>>() // (начало, длина данных)
        val eccs = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val dLen = shortLen - eccLen + if (i < numShort) 0 else 1
            blocks.add(k to dLen)
            eccs.add(rsRemainder(data, k, dLen, eccLen))
            k += dLen
        }
        var o = 0
        val maxData = shortLen - eccLen + 1
        for (j in 0 until maxData) for (i in 0 until numBlocks) {
            val (start, len) = blocks[i]
            if (j < len) out[o++] = data[start + j]
        }
        for (j in 0 until eccLen) for (i in 0 until numBlocks) out[o++] = eccs[i][j].toByte()
        return out
    }

    private fun setFunc(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark
        f[y][x] = true
    }

    private fun drawFunctionPatterns(m: Array<BooleanArray>, f: Array<BooleanArray>, ver: Int) {
        val size = m.size
        for (i in 0 until size) {
            setFunc(m, f, 6, i, i % 2 == 0)
            setFunc(m, f, i, 6, i % 2 == 0)
        }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val d = maxOf(Math.abs(dx), Math.abs(dy))
                    setFunc(m, f, x, y, d != 2 && d != 4)
                }
            }
        }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
        // Выравнивающие узоры
        val pos = alignmentPositions(ver, size)
        for (i in pos.indices) for (j in pos.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) {
                setFunc(m, f, pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }
        drawFormat(m, f, 0) // резервирует область; значение перепишется
        if (ver >= 7) {
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunc(m, f, a, b, bit)
                setFunc(m, f, b, a, bit)
            }
        }
    }

    private fun alignmentPositions(ver: Int, size: Int): IntArray {
        if (ver == 1) return IntArray(0)
        val n = ver / 7 + 2
        val step = if (ver == 32) 26 else (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2
        val r = IntArray(n)
        r[0] = 6
        var p = size - 7
        for (i in n - 1 downTo 1) { r[i] = p; p -= step }
        return r
    }

    private fun drawFormat(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        val size = m.size
        val data = mask // уровень M кодируется как 00
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) setFunc(m, f, 8, i, bit(i))
        setFunc(m, f, 8, 7, bit(6))
        setFunc(m, f, 8, 8, bit(7))
        setFunc(m, f, 7, 8, bit(8))
        for (i in 9..14) setFunc(m, f, 14 - i, 8, bit(i))
        for (i in 0..7) setFunc(m, f, size - 1 - i, 8, bit(i))
        for (i in 8..14) setFunc(m, f, 8, size - 15 + i, bit(i))
        setFunc(m, f, 8, size - 8, true) // всегда тёмный модуль
    }

    private fun drawCodewords(m: Array<BooleanArray>, f: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!f[y][x] && i < data.size * 8) {
                    m[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        for (y in m.indices) for (x in m.indices) {
            val inv = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (inv && !f[y][x]) m[y][x] = !m[y][x]
        }
    }

    private val FINDER_A = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
    private val FINDER_B = FINDER_A.reversedArray()

    private fun penalty(m: Array<BooleanArray>): Int {
        val n = m.size
        var p = 0
        var dark = 0
        for (y in 0 until n) for (x in 0 until n) if (m[y][x]) dark++
        // Правила 1 и 3 по строкам и столбцам
        for (t in 0..1) for (a in 0 until n) {
            fun at(b: Int) = if (t == 0) m[a][b] else m[b][a]
            var run = 1
            for (b in 1 until n) {
                if (at(b) == at(b - 1)) {
                    run++
                    if (run == 5) p += 3 else if (run > 5) p++
                } else run = 1
            }
            for (b in 0..n - 11) {
                var ma = true
                var mb = true
                for (k in 0 until 11) {
                    val v = at(b + k)
                    if (v != FINDER_A[k]) ma = false
                    if (v != FINDER_B[k]) mb = false
                }
                if (ma) p += 40
                if (mb) p += 40
            }
        }
        // Правило 2: блоки 2x2 одного цвета
        for (y in 0 until n - 1) for (x in 0 until n - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
        }
        // Правило 4: баланс тёмных и светлых
        val total = n * n
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        return p + k * 10
    }
}
