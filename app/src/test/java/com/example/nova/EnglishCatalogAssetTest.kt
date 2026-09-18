package com.example.nova

import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Английский каталог `assets/i18n/en.json`.
 *
 * Каталог правят руками и скриптом (`tools/i18n/merge_catalog.py`), а ошибка в нём
 * тихая: перевод, потерявший `{0}`, на экране съест номер попытки или адрес узла,
 * и заметит это только человек, читающий английский интерфейс. Поэтому те же
 * проверки, что делает скрипт, повторены здесь — сборка не пройдёт с битой записью.
 */
class EnglishCatalogAssetTest {

    private fun entries(): Map<String, String> {
        val candidates = listOf(File("src/main/assets/i18n/en.json"), File("app/src/main/assets/i18n/en.json"))
        val file = candidates.firstOrNull { it.isFile } ?: error("не найден en.json; искали: $candidates")
        val json = JSONObject(file.readText(Charsets.UTF_8))
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private val placeholder = Regex("""\{\d+\}""")
    private val russianDomains = Regex("""\.?\b(рф|РФ)\b""")

    @Test
    fun `каждая запись сохраняет места подстановки, поля и переводы строк`() {
        val broken = entries().filter { (source, translation) ->
            placeholder.findAll(source).map { it.value }.sorted().toList() !=
                placeholder.findAll(translation).map { it.value }.sorted().toList() ||
                source.takeWhile { it.isWhitespace() } != translation.takeWhile { it.isWhitespace() } ||
                source.takeLastWhile { it.isWhitespace() } != translation.takeLastWhile { it.isWhitespace() } ||
                source.count { it == '\n' } != translation.count { it == '\n' }
        }
        assertTrue("битые записи: ${broken.keys.take(5)}", broken.isEmpty())
    }

    @Test
    fun `в переводах нет русского текста`() {
        val russian = entries().filterValues { translation ->
            NovaTranslationCatalog.hasCyrillic(russianDomains.replace(translation, ""))
        }
        assertTrue("русский в переводе: ${russian.values.take(5)}", russian.isEmpty())
    }

    @Test
    fun `главный экран переводится целиком`() {
        val catalog = NovaTranslationCatalog.fromEntries(entries(), Locale.ENGLISH)
        listOf(
            "НЕ ПОДКЛЮЧЕНО",
            "ПОДКЛЮЧИТЬ",
            "ОТКЛЮЧИТЬ",
            "⚙️ Настройки",
            "след. профиль",
            "Регион:",
            "Отключите DoT",
            "Язык интерфейса",
        ).forEach { source ->
            val out = catalog.translate(source)
            assertFalse("не переведено: $source -> $out", NovaTranslationCatalog.hasCyrillic(out))
        }
    }

    @Test
    fun `собранные кодом строки переводятся без остатка русского`() {
        val catalog = NovaTranslationCatalog.fromEntries(entries(), Locale.ENGLISH)
        // Строка уведомления из NovaVpnService.buildNotificationDetails и formatSpeed.
        val notification = catalog.translate("WARP  ·  NL  ·  ↓ 1.4 МБ/с  ↑ 20 КБ/с")
        assertFalse(notification, NovaTranslationCatalog.hasCyrillic(notification))
        assertTrue(notification, notification.startsWith("WARP  ·  NL  ·  ↓ 1.4 "))
        // Абзац экрана «MTU туннеля»: собран через append из трёх фраз каталога.
        val mtu = catalog.translate(
            "MASQUE — размер считается из пакета QUIC, настройка на него не влияет.\n" +
                "Opera, VLESS и TOR — туннель держит tun2proxy со своим 1420 Б." +
                "\n\nСейчас выбран OPERA, поэтому регулировка скрыта: она не изменила бы ничего. " +
                "Выберите AUTO, WARP или PROTON, чтобы настроить MTU."
        )
        assertFalse(mtu, NovaTranslationCatalog.hasCyrillic(mtu))
        assertEquals(mtu, 3, mtu.count { it == '\n' })
        // Счётчик «Конфигурации» в настройках: buildString из кусков с полями.
        val configs = catalog.translate("Встроенных: 50 • импортировано: 3 • вручную: 1")
        assertFalse(configs, NovaTranslationCatalog.hasCyrillic(configs))
        // Сводки DNS и обхода по доменам: рамка без русского, русские куски внутри.
        val dns = catalog.translate("DNS: dns.dns-ai.ru + ещё 6, автовыбор пути")
        assertFalse(dns, NovaTranslationCatalog.hasCyrillic(dns))
        val zones = catalog.translate("зоны: .ru, .su, кириллические · доменов: 0")
        assertFalse(zones, NovaTranslationCatalog.hasCyrillic(zones.replace(".рф", "")))
        // Кнопка «Удалить все импортированные (N)» из меню импорта.
        val menu = catalog.translate("Удалить все импортированные (12)")
        assertFalse(menu, NovaTranslationCatalog.hasCyrillic(menu))
        assertTrue(menu, menu.contains("12"))
    }

    @Test
    fun `названия языков в каталог не попадают`() {
        // Список языков показывает их на самих этих языках — человек, включивший
        // чужой язык, обязан узнать свой.
        val keys = entries().keys
        NovaLanguage.Language.values().forEach { language ->
            assertFalse(language.nativeName, language.nativeName in keys)
        }
        assertEquals("RU", NovaLanguage.Language.RU.badge)
        assertEquals("EN", NovaLanguage.Language.EN.badge)
    }
}
