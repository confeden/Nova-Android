package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Селектор протокола/региона.
 *
 * Тест существует потому, что описание селектора было выписано четырьмя копиями,
 * и порядок в коде не совпадает с порядком в разметке. Такие списки расходятся
 * молча — это G49, — а теперь их пинует проверка.
 */
class ConnectionSelectorPolicyTest {

    @Test
    fun `order and labels stay in step`() {
        assertEquals(ConnectionSelectorPolicy.ORDER.size, ConnectionSelectorPolicy.LABELS.size)
        assertEquals(6, ConnectionSelectorPolicy.SIZE)
        // Порядок кода, а не разметки: masque третий. Разъедется — сломается и
        // главный экран, и настройки, каждый по-своему.
        assertEquals(
            listOf("auto", "ru", "masque", "opera", "proton", "tor"),
            ConnectionSelectorPolicy.ORDER,
        )
        assertEquals(
            listOf("AUTO", "WARP", "MASQUE", "OPERA", "PROTON", "TOR"),
            ConnectionSelectorPolicy.LABELS,
        )
    }

    /**
     * Три полосы — это данные, а не разметка.
     *
     * Разбиение смысловое: Cloudflare, зарубежные VPN, Tor. По ширине его не
     * восстановить, поэтому оно живёт в политике и проверяется здесь: сумма
     * обязана совпасть с числом кнопок, иначе часть их просто не нарисуется.
     */
    @Test
    fun `rows cover every button exactly once`() {
        assertEquals(ConnectionSelectorPolicy.SIZE, ConnectionSelectorPolicy.ROWS.sum())
        assertTrue(ConnectionSelectorPolicy.ROWS.all { it > 0 })
        assertEquals(3, ConnectionSelectorPolicy.ROWS.size)
    }

    @Test
    fun `every selectable chip maps to its own button`() {
        val seen = mutableSetOf<Int>()
        for (chip in ConnectionSelectorPolicy.ORDER) {
            val index = ConnectionSelectorPolicy.ORDER.indexOf(chip)
            assertEquals(chip, ConnectionSelectorPolicy.valueAt(index))
            assertTrue("duplicate index for $chip", seen.add(index))
        }
    }

    /**
     * Кнопка OPERA — одна на два значения службы.
     *
     * Словарь службы по-прежнему знает `eu` и `us`: смена подписи не должна была
     * тронуть семнадцать мест, которые эти значения разбирают.
     */
    @Test
    fun `opera is one chip over two stored values`() {
        val opera = ConnectionSelectorPolicy.ORDER.indexOf(ConnectionSelectorPolicy.CHIP_OPERA)
        assertEquals(opera, ConnectionSelectorPolicy.indexOf("eu"))
        assertEquals(opera, ConnectionSelectorPolicy.indexOf("us"))
        assertEquals("us", ConnectionSelectorPolicy.storedValueForChip("opera", "us"))
        assertEquals("eu", ConnectionSelectorPolicy.storedValueForChip("opera", "eu"))
        // Незнакомый подрегион — это EU, а не пустая строка: пустую службу не
        // понимает, и `normalizeKnown` превратил бы её в «Авто», а «Авто»
        // разрешает подмену транспорта.
        assertEquals("eu", ConnectionSelectorPolicy.storedValueForChip("opera", null))
        assertEquals("eu", ConnectionSelectorPolicy.storedValueForChip("opera", "atlantis"))
        // Остальные кнопки переводятся сами в себя.
        assertEquals("masque", ConnectionSelectorPolicy.storedValueForChip("masque", "us"))
    }

    /** Строка подрегионов есть только там, где подрегион существует. */
    @Test
    fun `sub regions exist only for opera and proton`() {
        assertEquals(
            listOf("eu" to "EU", "us" to "US"),
            ConnectionSelectorPolicy.subRegionsFor("opera", emptyList()),
        )
        // «AUTO» — своя кнопка, а не отметка на первой стране: пустое
        // предпочтение иначе нельзя было бы ни увидеть, ни выбрать обратно.
        assertEquals(
            listOf("" to "AUTO", "NL" to "NL", "PL" to "PL", "JP" to "JP"),
            ConnectionSelectorPolicy.subRegionsFor("proton", listOf("JP", "PL", "NL")),
        )
        // Профилей нет — обещать страны нечем, и «AUTO» в одиночку бессмысленна.
        assertTrue(ConnectionSelectorPolicy.subRegionsFor("proton", emptyList()).isEmpty())
        for (chip in listOf("auto", "ru", "masque", "tor")) {
            assertTrue(chip, ConnectionSelectorPolicy.subRegionsFor(chip, listOf("NL")).isEmpty())
        }
    }

    @Test
    fun `case and spacing do not move the button`() {
        assertEquals(ConnectionSelectorPolicy.indexOf("masque"), ConnectionSelectorPolicy.indexOf(" MASQUE "))
        assertEquals(ConnectionSelectorPolicy.indexOf("proton"), ConnectionSelectorPolicy.indexOf("Proton"))
    }

    /**
     * `vless` — известный регион, но кнопки у него нет: он выбирается как
     * семейство импортированных протоколов. «Авто» здесь честнее, чем показать
     * нажатой чужую кнопку.
     */
    @Test
    fun `a region without a button falls back to auto`() {
        for (value in listOf(null, "", "vless", "atlantis")) {
            assertEquals(0, ConnectionSelectorPolicy.indexOf(value))
        }
        assertEquals("auto", ConnectionSelectorPolicy.valueAt(-1))
        assertEquals("auto", ConnectionSelectorPolicy.valueAt(99))
    }

    /**
     * Идущий выпуск Proton сильнее сохранённого значения: предпочтение
     * записывается только по успеху, а перерисовок до него много.
     */
    @Test
    fun `a running proton run holds the button down`() {
        assertEquals(
            ConnectionSelectorPolicy.indexOf("proton"),
            ConnectionSelectorPolicy.selectedIndex(storedRegion = "ru", protonPreparationRequested = true),
        )
        assertEquals(
            ConnectionSelectorPolicy.indexOf("ru"),
            ConnectionSelectorPolicy.selectedIndex(storedRegion = "ru", protonPreparationRequested = false),
        )
    }

    @Test
    fun `registration locks every button and says why`() {
        val a = ConnectionSelectorPolicy.availability(
            operaSupported = true,
            deviceRegistrationInProgress = true,
            storedRegion = "ru",
        )
        assertEquals(ConnectionSelectorPolicy.SIZE, a.enabled.size)
        assertTrue(a.enabled.none { it })
        assertTrue(a.lockReason.isNotBlank())
        // Запрет временный: переписывать чужой выбор из-за него нельзя.
        assertNull(a.rewriteStoredTo)
    }

    @Test
    fun `without opera only the opera chip goes dark`() {
        val a = ConnectionSelectorPolicy.availability(
            operaSupported = false,
            deviceRegistrationInProgress = false,
            storedRegion = "ru",
        )
        for ((i, chip) in ConnectionSelectorPolicy.ORDER.withIndex()) {
            assertEquals(chip, chip != ConnectionSelectorPolicy.CHIP_OPERA, a.enabled[i])
        }
        // Пользователь стоял на достижимом — его выбор не трогаем.
        assertNull(a.rewriteStoredTo)
        assertTrue(a.lockReason.isBlank())
    }

    @Test
    fun `an unreachable stored choice is rewritten, a reachable one is not`() {
        for (region in listOf("eu", "us")) {
            assertEquals(
                region,
                "auto",
                ConnectionSelectorPolicy.availability(false, false, region).rewriteStoredTo,
            )
        }
        for (region in listOf("auto", "ru", "masque", "proton", "tor")) {
            assertNull(region, ConnectionSelectorPolicy.availability(false, false, region).rewriteStoredTo)
        }
        // С поддержкой Opera переписывать нечего вообще.
        assertNull(ConnectionSelectorPolicy.availability(true, false, "eu").rewriteStoredTo)
    }

    @Test
    fun `everything is enabled when nothing forbids it`() {
        val a = ConnectionSelectorPolicy.availability(true, false, "auto")
        assertTrue(a.enabled.all { it })
        assertFalse(a.lockReason.isNotBlank())
    }
}
