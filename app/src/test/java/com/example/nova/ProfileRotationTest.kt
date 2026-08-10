package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: мёртвый узел останавливал
 * подключение целиком. Приложение писало «переключитесь на следующий профиль» и
 * ждало, а кнопка переключения знала только про WARP. Здесь закреплены оба правила
 * перебора: начинать с активной записи и уводить неудачную вниз.
 */
class ProfileRotationTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `перебор начинается с активного профиля`() {
        assertEquals(2, ProfileRotation.startIndex(list, "c"))
        assertEquals(0, ProfileRotation.startIndex(list, "a"))
        assertEquals(3, ProfileRotation.startIndex(list, "d"))
    }

    @Test
    fun `неизвестная активная запись отправляет перебор в начало списка`() {
        assertEquals(0, ProfileRotation.startIndex(list, "нет такого"))
        assertEquals(0, ProfileRotation.startIndex(list, null))
        assertEquals(0, ProfileRotation.startIndex(emptyList(), "z"))
    }

    @Test
    fun `удачный профиль поднимается в начало списка`() {
        assertEquals(listOf("c", "a", "b", "d"), ProfileRotation.promote(list, "c"))
        assertEquals(listOf("d", "a", "b", "c"), ProfileRotation.promote(list, "d"))
    }

    @Test
    fun `поднимать нечего — возвращается тот же список`() {
        assertSame(list, ProfileRotation.promote(list, "a"))
        assertSame(list, ProfileRotation.promote(list, "нет такого"))
    }

    @Test
    fun `неудачный профиль уходит вниз, порядок остальных сохраняется`() {
        assertEquals(listOf("b", "c", "d", "a"), ProfileRotation.demote(list, "a"))
        assertEquals(listOf("a", "b", "d", "c"), ProfileRotation.demote(list, "c"))
    }

    @Test
    fun `двигать нечего — возвращается тот же список`() {
        // Вызывающая сторона по этому признаку решает, надо ли писать в хранилище.
        assertSame(list, ProfileRotation.demote(list, "d"))
        assertSame(list, ProfileRotation.demote(list, "нет такого"))
    }

    @Test
    fun `полный круг отказов возвращает список к исходному порядку`() {
        // Если мертвы все, наказывать некого: порядок обязан остаться прежним, иначе
        // после каждого неудачного цикла список молча перемешивался бы.
        var current = list
        for (link in list) {
            current = ProfileRotation.demote(current, link)
        }
        assertEquals(list, current)
    }

    @Test
    fun `после успеха рабочий профиль первый, а следующий — второй`() {
        // Ровно тот случай, на котором ловился баг: перебор отверг шесть записей и
        // подключился к седьмой. Наказание применяется пачкой в момент успеха, рабочий
        // профиль поднимается наверх — и «следующий» уводит на восьмой, а не обратно к
        // уже отвергнутым, со счётчиком «2/10» вместо «1/10».
        val profiles = (1..10).map { "P$it" }
        val rejected = profiles.take(6)
        var current = profiles
        for (link in rejected) {
            current = ProfileRotation.demote(current, link)
        }
        current = ProfileRotation.promote(current, "P7")

        assertEquals(1, current.indexOf("P7") + 1)
        assertEquals("P8", ProfileRotation.next(current, "P7"))
        assertEquals(2, current.indexOf("P8") + 1)
        assertEquals(rejected, current.takeLast(6))
    }

    @Test
    fun `перебор с середины списка тоже приводит рабочий профиль наверх`() {
        // Активная запись после прошлого сеанса стоит в середине, и перебор начинается
        // с неё. Без подъёма наверх рабочий узел оставался бы шестым, а над ним лежали
        // бы записи, которых перебор в этот раз не касался.
        val profiles = (1..10).map { "P$it" }
        val start = ProfileRotation.startIndex(profiles, "P6")
        assertEquals(5, start)

        var current = profiles
        for (link in listOf("P6", "P7")) {
            current = ProfileRotation.demote(current, link)
        }
        current = ProfileRotation.promote(current, "P8")

        assertEquals(1, current.indexOf("P8") + 1)
        assertEquals(listOf("P6", "P7"), current.takeLast(2))
    }

    @Test
    fun `наказание сразу после каждого отказа оставляет счётчик на единице`() {
        // Ради этого перебор и копит отказы: пока запись уезжала вниз немедленно,
        // следующая занимала её место, текущий профиль всегда был первым, и номер
        // в списке не двигался вовсе.
        var current = list
        for (step in 0 until 3) {
            assertEquals(0, current.indexOf(current.first()))
            current = ProfileRotation.demote(current, current.first())
        }
        assertEquals(listOf("d", "a", "b", "c"), current)
    }

    @Test
    fun `следующий профиль берётся по кругу`() {
        assertEquals("b", ProfileRotation.next(list, "a"))
        assertEquals("a", ProfileRotation.next(list, "d"))
        // Ничего не выбрано или выбрано отсутствующее — начинаем сначала.
        assertEquals("a", ProfileRotation.next(list, null))
        assertEquals("a", ProfileRotation.next(list, "нет такого"))
        assertNull(ProfileRotation.next(emptyList(), "a"))
    }

    /**
     * Дефект, ради которого написаны эти проверки: удаление профиля VLESS не удаляло
     * ничего. Карточка собиралась из отдельного хранилища, кнопка чистила хранилище
     * WARP, счётчик оставался прежним — и выглядело это как «удаление не работает».
     */
    @Test
    fun `удаление возвращает укороченный список`() {
        val removal = ProfileRotation.remove(list, active = "a") { it == "b" }
        assertEquals(listOf("a", "c", "d"), removal?.items)
        // Активной была не удалённая запись — она и остаётся активной.
        assertEquals("a", removal?.active)
    }

    @Test
    fun `удаление активного профиля переносит активность на первый оставшийся`() {
        val removal = ProfileRotation.remove(list, active = "a") { it == "a" }
        assertEquals(listOf("b", "c", "d"), removal?.items)
        assertEquals("b", removal?.active)
    }

    @Test
    fun `удалять нечего — вызывающая сторона узнаёт об этом`() {
        // Именно ради этого признака: молчаливое «удалили ноль» и читалось как
        // сработавшее удаление.
        assertNull(ProfileRotation.remove(list, active = "a") { it == "нет такого" })
        assertNull(ProfileRotation.remove(emptyList<String>(), active = null) { true })
    }

    /**
     * Дефект, ради которого написаны эти проверки: фоновая проверка адресов Opera
     * заменяла список целиком и стирала порядок, выстроенный после двадцати секунд
     * удержания. Проверенный адрес оказывался в середине, и следующий цикл начинал
     * не с него — то есть подъём удачного способа наверх молча отменялся.
     */
    @Test
    fun `свежий список не сбивает выстроенный порядок`() {
        val known = listOf("b", "a", "c")
        val fresh = listOf("a", "b", "c")
        // Порядок известных сохраняется целиком, а не подменяется порядком ответа.
        assertEquals(listOf("b", "a", "c"), ProfileRotation.mergeKeepingOrder(known, fresh))
    }

    @Test
    fun `новые записи дописываются в конец`() {
        val merged = ProfileRotation.mergeKeepingOrder(listOf("b", "a"), listOf("a", "b", "d", "e"))
        assertEquals(listOf("b", "a", "d", "e"), merged)
    }

    @Test
    fun `пропавшие из свежего списка записи отбрасываются`() {
        // Их больше не предлагают: держать их в очереди — платить за них попытками.
        assertEquals(listOf("a", "c"), ProfileRotation.mergeKeepingOrder(listOf("a", "b", "c"), listOf("c", "a")))
    }

    @Test
    fun `пустой свежий список ничего не меняет`() {
        // Неудачная проверка не должна опустошать накопленный список.
        val known = listOf("a", "b")
        assertEquals(known, ProfileRotation.mergeKeepingOrder(known, emptyList()))
    }

    @Test
    fun `удаление последнего профиля оставляет пустой список без активного`() {
        val removal = ProfileRotation.remove(listOf("a"), active = "a") { it == "a" }
        assertEquals(emptyList<String>(), removal?.items)
        assertNull(removal?.active)
    }
}
