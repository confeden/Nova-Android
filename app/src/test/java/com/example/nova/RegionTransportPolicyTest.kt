package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: при выбранном EU подключение
 * показывало «1/50» — длину списка встроенных WARP-профилей, — потом «1/54» от
 * собственного плана запуска Opera, а спустя минуту после неудачи уезжало на
 * WARP «ненадолго, ради discovery Opera endpoints» и там оставалось.
 */
class RegionTransportPolicyTest {

    @Test
    fun `подмена протокола разрешена только в Авто`() {
        assertTrue(RegionTransportPolicy.allowsTransportSubstitution("auto"))
        assertTrue(RegionTransportPolicy.allowsTransportSubstitution("AUTO"))
        // Значения по умолчанию и пустая настройка — это тоже «Авто».
        assertTrue(RegionTransportPolicy.allowsTransportSubstitution(null))
        assertTrue(RegionTransportPolicy.allowsTransportSubstitution("  "))

        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("eu"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("us"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("ru"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("masque"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("vless"))
    }

    @Test
    fun `незнакомое значение считается выбором пользователя`() {
        // Ошибка в эту сторону стоит лишнего отказа, в обратную — молчаливой
        // подмены выбранного региона, а её пользователь и не заметит.
        assertTrue(RegionTransportPolicy.isExplicitChoice("de"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("de"))
    }

    @Test
    fun `при выбранном EU счётчик не берёт список встроенных WARP-профилей`() {
        // Первые доли секунды цикла: фаза ещё не назвала себя, метки транспорта нет.
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = "",
                backendLabel = NovaVpnService.BACKEND_WARP,
                regionPreference = "eu",
            )
        )
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = "",
                backendLabel = "",
                regionPreference = "us",
            )
        )
    }

    @Test
    fun `фаза Opera в Авто тоже не показывает счётчик WARP`() {
        // В «Авто» Opera идёт после неудачи WARP: регион здесь ничего не подсказывает,
        // подсказывают метка транспорта и бэкенд.
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = NovaVpnService.TRANSPORT_OPERA,
                backendLabel = "${NovaVpnService.BACKEND_OPERA}-EU",
                regionPreference = "auto",
            )
        )
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = "",
                backendLabel = "${NovaVpnService.BACKEND_OPERA}-US",
                regionPreference = "auto",
            )
        )
    }

    @Test
    fun `у MASQUE и VLESS перебор свой`() {
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = NovaVpnService.TRANSPORT_MASQUE,
                backendLabel = NovaVpnService.BACKEND_WARP,
                regionPreference = "auto",
            )
        )
        assertFalse(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = NovaVpnService.TRANSPORT_VLESS,
                backendLabel = NovaVpnService.BACKEND_VLESS,
                regionPreference = "vless",
            )
        )
    }

    @Test
    fun `перебор встроенных профилей WARP счётчик показывает как и раньше`() {
        assertTrue(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = NovaVpnService.TRANSPORT_WARP,
                backendLabel = NovaVpnService.BACKEND_WARP,
                regionPreference = "auto",
            )
        )
        assertTrue(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = NovaVpnService.TRANSPORT_WARP,
                backendLabel = NovaVpnService.BACKEND_WARP,
                regionPreference = "ru",
            )
        )
        // Пока фаза не назвала себя, в «Авто» и RU заглушка по списку уместна:
        // перебирать там действительно будут встроенные профили.
        assertTrue(
            RegionTransportPolicy.countsWarpProfileList(
                transportLabel = "",
                backendLabel = "",
                regionPreference = "auto",
            )
        )
    }
}
