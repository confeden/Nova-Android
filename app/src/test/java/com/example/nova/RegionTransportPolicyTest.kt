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

/**
 * Словарь выходов пинуется тестом, потому что он уже разъезжался.
 *
 * `NovaVpnService` держал собственную копию без `masque` и `vless`, и служба
 * разжаловала явный выбор в «Авто» — а «Авто» разрешает подмену транспорта.
 * Снаружи это выглядело как «выбрал MASQUE, подключился голландский Opera».
 */
class RegionVocabularyTest {

    @org.junit.Test
    fun `every selectable region survives normalization`() {
        for (region in listOf("auto", "ru", "eu", "us", "masque", "vless", "proton")) {
            org.junit.Assert.assertEquals(region, RegionTransportPolicy.normalizeKnown(region))
            org.junit.Assert.assertEquals(region, RegionTransportPolicy.normalizeKnown(region.uppercase()))
            org.junit.Assert.assertEquals(region, RegionTransportPolicy.normalizeKnown("  $region  "))
        }
    }

    @org.junit.Test
    fun `an unknown or empty region falls back to auto`() {
        for (value in listOf(null, "", "   ", "atlantis", "EU2")) {
            org.junit.Assert.assertEquals("auto", RegionTransportPolicy.normalizeKnown(value))
        }
    }

    /**
     * Нормализация не должна отменять запрет подмены: явный выбор, прошедший
     * через `normalizeKnown`, обязан остаться явным.
     */
    @org.junit.Test
    fun `an explicit choice stays explicit after normalization`() {
        for (region in listOf("ru", "eu", "us", "masque", "vless", "proton")) {
            val normalized = RegionTransportPolicy.normalizeKnown(region)
            org.junit.Assert.assertTrue(region, RegionTransportPolicy.isExplicitChoice(normalized))
        }
        org.junit.Assert.assertFalse(RegionTransportPolicy.isExplicitChoice(RegionTransportPolicy.normalizeKnown("auto")))
    }

    /** MASQUE и VLESS не должны разрешать подмену на Opera. */
    @org.junit.Test
    fun `masque and vless never allow an opera substitution`() {
        for (region in listOf("masque", "vless", "proton", "ru")) {
            org.junit.Assert.assertFalse(
                region,
                RegionTransportPolicy.allowsOperaTransport(RegionTransportPolicy.normalizeKnown(region)),
            )
        }
        for (region in listOf("eu", "us", "auto")) {
            org.junit.Assert.assertTrue(
                region,
                RegionTransportPolicy.allowsOperaTransport(RegionTransportPolicy.normalizeKnown(region)),
            )
        }
    }
}
