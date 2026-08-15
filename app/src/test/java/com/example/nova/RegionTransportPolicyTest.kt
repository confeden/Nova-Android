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

}
