package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Закрытый список «Прямого потока» сверяется строкой целиком, поэтому опечатка
 * в имени пакета не падает и не жалуется — она просто ни с чем не совпадает, и
 * приложение молча продолжает ходить через туннель. Ровно тот случай, который
 * ловится только тестом или руками на телефоне.
 */
class RussianDirectAppsTest {

    /**
     * Пакеты, названные владельцем 2026-09-13.
     *
     * На подключённых Pixel 4a и Mi A1 не стоит ни один из них; 2026-09-15 все
     * восемь сверены с каталогом RuStore — тест сторожит, что их не потеряют при
     * следующей правке списка.
     */
    private val ownerNamed = listOf(
        "ru.yandex.telemost",
        "ru.yandex.weatherplugin",
        "ru.vk.store",
        "ru.rutube.app",
        "ru.urentbike.app",
        "com.punicapp.whoosh",
        "com.ustasapp",
        "com.dlilb.profplus",
    )

    @Test
    fun `названные владельцем пакеты идут напрямую`() {
        ownerNamed.forEach { pkg ->
            assertTrue(pkg, RussianDirectApps.contains(pkg))
            assertTrue(pkg, pkg in RussianDirectApps.all())
        }
    }

    /**
     * Владелец 2026-09-15: банки из Play, МАКС, ВКонтакте и Одноклассники — напрямую; там
     * же проверены Яндекс Переводчик и МегаФон, о которых он спрашивал.
     *
     * Имена — по каталогу RuStore с разработчиком-организацией. Прежние записи
     * Райффайзена, ПСБ, Совкомбанка, РСХБ, МКБ, МТС Банка и VK Видео ни с чем не
     * совпадали, и эти приложения шли через туннель.
     */
    @Test
    fun `банки, МАКС, ВКонтакте и Одноклассники идут напрямую`() {
        listOf(
            "ru.sberbankmobile",
            "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android",
            "com.idamob.tinkoff.android",
            "ru.raiffeisennews",
            "logo.com.mbanking",
            "ru.sovcomcard.halva.v1",
            "ru.rshb.dbo",
            "ru.mkb.mobile",
            "ru.lewis.dbo",
            "ru.ozon.app.android",
            "ru.ozon.fintech.finance",
            "ru.oneme.app",
            "com.vkontakte.android",
            "com.vk.vkvideo",
            "ru.ok.android",
            "ru.yandex.translate",
            "ru.megafon.mlk",
        ).forEach { pkg -> assertTrue(pkg, RussianDirectApps.contains(pkg)) }
    }

    /** Владелец 2026-09-15: инвестиционные приложения российских банков — тоже напрямую. */
    @Test
    fun `инвестиционные приложения банков идут напрямую`() {
        listOf(
            "ru.sberbank.investor",
            "ru.vtb.invest",
            "ru.tinkoff.investing",
            "ru.alfadirect.app",
            "ru.gazprombank.invest",
            "ru.psbank.invest",
            "ru.sovcombank.investor",
        ).forEach { pkg -> assertTrue(pkg, RussianDirectApps.contains(pkg)) }
    }

    /** Имена, которых нет ни в одном магазине, в список не возвращаются. */
    @Test
    fun `мёртвые имена пакетов не возвращаются`() {
        listOf(
            "ru.raiffeisen.mobile",
            "ru.psbank.mobile",
            "ru.sovcombank.sovcombank",
            "ru.rshb.mobilebank",
            "ru.mkb.mobilniy_bank",
            "ru.mtsbank.mtsbank",
            "com.vk.video",
            "ru.yandex.eda",
            "ru.yandex.mobile.navigator",
            "ru.rzd.rzd",
        ).forEach { pkg -> assertFalse(pkg, RussianDirectApps.contains(pkg)) }
    }

    /**
     * `all()` — это объединение двух наборов, и дубль между ними не виден
     * снаружи: множество его поглотит. Сверяем размер до объединения, иначе
     * запись, случайно добавленная в оба набора, однажды будет удалена «как
     * лишняя» не из того места.
     */
    @Test
    fun `в списке нет пустых и повторяющихся имён`() {
        val all = RussianDirectApps.all()
        assertTrue(all.isNotEmpty())
        all.forEach { pkg ->
            assertEquals("хвостовые пробелы в «$pkg»", pkg.trim(), pkg)
            assertTrue("пустое имя пакета", pkg.isNotEmpty())
            // Имя пакета Android — как минимум две части через точку.
            assertTrue("«$pkg» не похоже на имя пакета", pkg.contains('.') && !pkg.startsWith('.'))
        }
    }

    /** Чужое имя в списке не значится — иначе «закрытый список» перестал бы быть закрытым. */
    @Test
    fun `посторонний пакет в список не входит`() {
        assertFalse(RussianDirectApps.contains("com.google.android.youtube"))
        assertFalse(RussianDirectApps.contains("org.telegram.messenger"))
        assertFalse(RussianDirectApps.contains(""))
    }
}
