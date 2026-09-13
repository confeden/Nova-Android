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
     * На подключённых Pixel 4a и Mi A1 не стоит ни один из них, так что
     * `pm list packages` их не подтвердил — тест сторожит хотя бы то, что их не
     * потеряют при следующей правке списка.
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
     * Банковским пакет делает не список «прямых», а [RussianDirectApps.isBanking]:
     * у банков своя ветка в `DirectAppsPolicy` — установленные из Play они
     * **не** выводятся из туннеля. Ошибочно попавший туда пакет сменил бы
     * поведение, а не просто добавил запись.
     */
    @Test
    fun `новые пакеты не считаются банковскими`() {
        ownerNamed.forEach { pkg -> assertFalse(pkg, RussianDirectApps.isBanking(pkg)) }
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
