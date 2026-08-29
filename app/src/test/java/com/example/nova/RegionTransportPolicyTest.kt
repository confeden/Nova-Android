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
    fun `Opera допустима только в Авто и там где она сама выбрана`() {
        assertTrue(RegionTransportPolicy.allowsOperaTransport("auto"))
        assertTrue(RegionTransportPolicy.allowsOperaTransport(null))
        assertTrue(RegionTransportPolicy.allowsOperaTransport("  "))
        // Opera и есть выбранный выход.
        assertTrue(RegionTransportPolicy.allowsOperaTransport("eu"))
        assertTrue(RegionTransportPolicy.allowsOperaTransport("US"))

        // Названный выход — другой транспорт. Раньше в запретах был только `ru`,
        // и выбранный MASQUE после неудачи уезжал на выход Opera.
        assertFalse(RegionTransportPolicy.allowsOperaTransport("ru"))
        assertFalse(RegionTransportPolicy.allowsOperaTransport("masque"))
        assertFalse(RegionTransportPolicy.allowsOperaTransport("proton"))
        assertFalse(RegionTransportPolicy.allowsOperaTransport("vless"))
        assertFalse(RegionTransportPolicy.allowsOperaTransport("de"))
    }

    @Test
    fun `незнакомое значение считается выбором пользователя`() {
        // Ошибка в эту сторону стоит лишнего отказа, в обратную — молчаливой
        // подмены выбранного региона, а её пользователь и не заметит.
        assertTrue(RegionTransportPolicy.isExplicitChoice("de"))
        assertFalse(RegionTransportPolicy.allowsTransportSubstitution("de"))
    }

    @Test
    fun `чужой транспорт из памяти при явном выборе не поднимается`() {
        // «Последняя стабильная WARP-стратегия» помнит и MASQUE: он живёт внутри
        // бэкенда WARP. Отсюда «выбран WARP — подключается MASQUE».
        assertFalse(RegionTransportPolicy.allowsRememberedStrategy("ru", "MASQUE-ZT"))
        assertFalse(RegionTransportPolicy.allowsRememberedStrategy("ru", "masque-consumer"))
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("ru", "warp-awg-exact"))

        // Симметрично: выбран MASQUE — не поднимаем запомненный WARP.
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("masque", "MASQUE-ZT"))
        assertFalse(RegionTransportPolicy.allowsRememberedStrategy("masque", "warp-awg-exact"))

        // В «Авто» выбор делает приложение, память годится любая.
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("auto", "MASQUE-ZT"))
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy(null, "MASQUE-ZT"))
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("auto", "warp-awg-exact"))

        // Пустая память ничего не запрещает.
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("ru", null))
        assertTrue(RegionTransportPolicy.allowsRememberedStrategy("ru", ""))
    }

    @Test
    fun `MASQUE допустим только в Авто и когда выбран сам`() {
        assertTrue(RegionTransportPolicy.allowsMasqueTransport("auto"))
        assertTrue(RegionTransportPolicy.allowsMasqueTransport(null))
        assertTrue(RegionTransportPolicy.allowsMasqueTransport("masque"))

        // MASQUE живёт внутри бэкенда WARP, поэтому «выбран WARP» его не удерживало.
        assertFalse(RegionTransportPolicy.allowsMasqueTransport("ru"))
        assertFalse(RegionTransportPolicy.allowsMasqueTransport("eu"))
        assertFalse(RegionTransportPolicy.allowsMasqueTransport("us"))
        assertFalse(RegionTransportPolicy.allowsMasqueTransport("proton"))
        assertFalse(RegionTransportPolicy.allowsMasqueTransport("vless"))
    }
}
