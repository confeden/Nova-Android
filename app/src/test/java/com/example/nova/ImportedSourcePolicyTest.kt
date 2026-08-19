package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефекты, ради которых написаны эти проверки:
 *
 * 1. Импорт подписки VLESS включал режим «только импортированные», но выбранный
 *    протокол оставался «AUTO». Экран схлопывал «AUTO» в единственную семью и писал
 *    «VLESS», а служба требовала ровно значения `vless` — фаза VLESS не запускалась,
 *    подключение гасло через полминуты со строкой «shortlist пуст».
 * 2. Провал VLESS уводил перебор на встроенные профили WARP: вход в фазу проверял
 *    одно условие, выход из неё — другое, более узкое.
 * 3. Оставшийся с прошлого раза регион (eu/masque/vless) отменял выбор протокола,
 *    хотя в режиме импортированных регион пользователю даже не показывается.
 */
class ImportedSourcePolicyTest {

    @Test
    fun `AUTO при единственной семье означает эту семью`() {
        assertEquals(
            "vless",
            ImportedSourcePolicy.resolveEffectiveProtocol("auto", listOf("vless")),
        )
        assertEquals(
            "awg",
            ImportedSourcePolicy.resolveEffectiveProtocol(null, listOf("awg")),
        )
    }

    @Test
    fun `AUTO при нескольких семьях остаётся AUTO`() {
        // Выбор здесь действительно есть, и делать его за пользователя нельзя.
        assertEquals(
            "auto",
            ImportedSourcePolicy.resolveEffectiveProtocol("auto", listOf("awg", "vless")),
        )
    }

    @Test
    fun `названный протокол сохраняется`() {
        assertEquals(
            "awg",
            ImportedSourcePolicy.resolveEffectiveProtocol("AWG", listOf("awg", "vless")),
        )
        assertEquals(
            "vless",
            ImportedSourcePolicy.resolveEffectiveProtocol(" vless ", listOf("awg", "vless")),
        )
    }

    @Test
    fun `протокол без единого профиля ведёт себя как AUTO`() {
        // Подписку заменили: сохранённый «AWG» при одних только профилях VLESS — не
        // выбор, а остаток прошлого набора, и решать надо по тому же правилу, что для
        // AUTO — единственная семья подставляется сама.
        //
        // Проверено на устройстве: пока здесь возвращался «auto» без схлопывания, фаза
        // VLESS не запускалась (ей нужен ровно `vless`), импортированных AWG не было,
        // shortlist получался пустым и служба гасла, объявив себя WARP, — снаружи это
        // и выглядело как «свои VLESS подменяются встроенными».
        assertEquals(
            "vless",
            ImportedSourcePolicy.resolveEffectiveProtocol("awg", listOf("vless")),
        )
        // А когда семей несколько, выбор действительно есть — за пользователя его не
        // делаем даже при устаревшем значении.
        assertEquals(
            "auto",
            ImportedSourcePolicy.resolveEffectiveProtocol("masque", listOf("awg", "vless")),
        )
    }

    @Test
    fun `без импортированных семей выбирать нечего`() {
        assertEquals(
            "auto",
            ImportedSourcePolicy.resolveEffectiveProtocol("vless", emptyList()),
        )
    }

    @Test
    fun `в режиме импортированных VLESS определяется протоколом, а не регионом`() {
        assertTrue(
            ImportedSourcePolicy.isVlessChosen(
                importedSourceActive = true,
                effectiveImportedProtocol = "vless",
                regionPreference = "eu",
            )
        )
        // Устаревший регион «vless» не должен перебивать выбранный AWG: иначе
        // перебираются профили VLESS вместо тех, что попросили.
        assertFalse(
            ImportedSourcePolicy.isVlessChosen(
                importedSourceActive = true,
                effectiveImportedProtocol = "awg",
                regionPreference = "vless",
            )
        )
    }

    @Test
    fun `вне режима импортированных VLESS определяется регионом`() {
        assertTrue(
            ImportedSourcePolicy.isVlessChosen(
                importedSourceActive = false,
                effectiveImportedProtocol = "auto",
                regionPreference = "vless",
            )
        )
        assertFalse(
            ImportedSourcePolicy.isVlessChosen(
                importedSourceActive = false,
                effectiveImportedProtocol = "vless",
                regionPreference = "auto",
            )
        )
    }

    @Test
    fun `условие входа в фазу и условие выхода из неё совпадают`() {
        // Ровно это расхождение и уводило провалившийся VLESS на встроенные WARP:
        // предикат один и тот же объект, поэтому разойтись они больше не могут.
        val cases = listOf(
            Triple(true, "vless", "auto"),
            Triple(true, "awg", "vless"),
            Triple(false, "auto", "vless"),
            Triple(false, "auto", "auto"),
        )
        cases.forEach { (imported, protocol, region) ->
            val entry = ImportedSourcePolicy.isVlessChosen(imported, protocol, region)
            val exit = ImportedSourcePolicy.isVlessChosen(imported, protocol, region)
            assertEquals("imported=$imported protocol=$protocol region=$region", entry, exit)
        }
    }
}
