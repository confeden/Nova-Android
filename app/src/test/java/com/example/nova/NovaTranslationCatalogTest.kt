package com.example.nova

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Сопоставление каталога перевода. Ожидаемые строки написаны руками, а не взяты
 * из вывода самого сопоставления (I28).
 */
class NovaTranslationCatalogTest {

    private fun catalog(vararg pairs: Pair<String, String>) =
        NovaTranslationCatalog.fromEntries(mapOf(*pairs), Locale.ENGLISH)

    @Test
    fun `точная строка переводится`() {
        val c = catalog("Настройки" to "Settings")
        assertEquals("Settings", c.translate("Настройки"))
    }

    @Test
    fun `непереведённый текст возвращается тем же объектом`() {
        val c = catalog("Настройки" to "Settings")
        val text = "Совсем другая фраза"
        assertSame(text, c.translate(text))
        val latin = "WARP: NL"
        assertSame(latin, c.translate(latin))
    }

    @Test
    fun `шаблон подставляет значения и переставляет места`() {
        val c = catalog(
            "Попытка {0} из {1}" to "Attempt {0} of {1}",
            "Найден рабочий порт {0} для {1}" to "Port {0} works for {1}",
            "{0} на {1}" to "{1}: {0}",
        )
        assertEquals("Attempt 3 of 12", c.translate("Попытка 3 из 12"))
        assertEquals("Port 443 works for 162.159.192.1", c.translate("Найден рабочий порт 443 для 162.159.192.1"))
        assertEquals("NL: WARP", c.translate("WARP на NL"))
    }

    @Test
    fun `подставленное значение тоже переводится`() {
        val c = catalog(
            "Статус: {0}" to "Status: {0}",
            "подключено" to "connected",
        )
        assertEquals("Status: connected", c.translate("Статус: подключено"))
    }

    @Test
    fun `конкретный шаблон побеждает общий`() {
        val c = catalog(
            "{0} мс" to "{0} ms",
            "Пинг: {0} мс" to "Ping: {0} ms",
        )
        assertEquals("Ping: 45 ms", c.translate("Пинг: 45 мс"))
        assertEquals("45 ms", c.translate("45 мс"))
    }

    @Test
    fun `многострочный текст переводится построчно`() {
        val c = catalog(
            "Профилей: {0}" to "Profiles: {0}",
            "Подписка обновлена" to "Subscription updated",
        )
        assertEquals("Subscription updated\nProfiles: 12", c.translate("Подписка обновлена\nПрофилей: 12"))
    }

    @Test
    fun `склейка через разделитель переводится по частям`() {
        val c = catalog(
            "{0} МБ/с" to "{0} MB/s",
            "{0} КБ/с" to "{0} KB/s",
        )
        // Строка уведомления: транспорт, страна и скорость через «  ·  », скорость — через два пробела.
        assertEquals(
            "WARP  ·  NL  ·  ↓ 1.4 MB/s  ↑ 20 KB/s",
            c.translate("WARP  ·  NL  ·  ↓ 1.4 МБ/с  ↑ 20 КБ/с"),
        )
    }

    @Test
    fun `разделитель внутри известной фразы не рвёт её`() {
        val c = catalog(
            "Тёмный индиго и холодная мята — развитие прежнего вида" to
                "Dark indigo and cool mint — evolution of the previous look",
        )
        // Имя темы и описание склеены тем же тире, что стоит внутри описания.
        assertEquals(
            "Aurora mint — Dark indigo and cool mint — evolution of the previous look",
            c.translate("Aurora mint — Тёмный индиго и холодная мята — развитие прежнего вида"),
        )
    }

    @Test
    fun `куски, собранные append, узнаются внутри текста`() {
        val c = catalog(
            "Встроенных: " to "Built-in: ",
            " • импортировано: " to " • imported: ",
        )
        assertEquals("Built-in: 50 • imported: 3", c.translate("Встроенных: 50 • импортировано: 3"))
    }

    @Test
    fun `вставка с местом подстановки узнаётся внутри чужой строки`() {
        val c = catalog(
            " + ещё {0}" to " + {0} more",
            "автовыбор пути" to "auto route",
        )
        // Сводка DNS: "DNS: $head$tail, $routeWord" — рамка без русского в каталог не попадает.
        assertEquals(
            "DNS: dns.dns-ai.ru + 6 more, auto route",
            c.translate("DNS: dns.dns-ai.ru + ещё 6, автовыбор пути"),
        )
    }

    @Test
    fun `перечисление через запятую переводится по элементам`() {
        val c = catalog(
            "зоны: {0}" to "zones: {0}",
            "кириллические" to "Cyrillic",
        )
        assertEquals("zones: .ru, .su, Cyrillic", c.translate("зоны: .ru, .su, кириллические"))
    }

    @Test
    fun `точка, дописанная после фразы, сохраняется`() {
        val c = catalog(
            "Режим сети: " to "Network mode: ",
            "не определён — российские идут первыми" to "undetermined — Russian names first",
        )
        assertEquals(
            "Network mode: undetermined — Russian names first.",
            c.translate("Режим сети: не определён — российские идут первыми."),
        )
    }

    @Test
    fun `одна русская буква с полями не считается куском`() {
        val c = catalog(" с " to " with ")
        val text = "Связь с узлом"
        assertSame(text, c.translate(text))
    }

    @Test
    fun `поля вокруг фразы сохраняются`() {
        val c = catalog("Отключите DoT" to "Turn off DoT")
        assertEquals("  Turn off DoT\n", c.translate("  Отключите DoT\n"))
    }

    @Test
    fun `фраза, поднятая в верхний регистр кодом, находится`() {
        val c = catalog("Без мостов" to "No bridges")
        assertEquals("NO BRIDGES", c.translate("БЕЗ МОСТОВ"))
    }

    @Test
    fun `перевод, потерявший место подстановки, не принимается`() {
        val c = catalog("Попытка {0} из {1}" to "Attempt {0}")
        val text = "Попытка 3 из 12"
        assertSame(text, c.translate(text))
    }

    @Test
    fun `пустой перевод не принимается`() {
        val c = catalog("Настройки" to "")
        val text = "Настройки"
        assertSame(text, c.translate(text))
    }

    @Test
    fun `слишком длинный текст не разбирается`() {
        val c = catalog("{0} мс" to "{0} ms")
        val text = "а".repeat(13_000) + " мс"
        assertSame(text, c.translate(text))
    }
}
