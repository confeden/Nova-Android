package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProfileStoreTest {

    private fun own(id: String, domain: String = "t.example.com", key: String = "aa") = DnsProfile(
        id = id,
        name = id,
        engine = DnsProfileImport.Engine.DNSTT,
        domain = domain,
        key = key,
    )

    private fun listOfOwn(vararg ids: String) =
        DnsProfileStore.withBuiltIn(DnsProfileList(profiles = ids.map { own(it) }))

    /**
     * Правило владельца: свой профиль выключает встроенный. Смысл в том, что
     * встроенный — запасной выход на чужой сервер, и держать на нём человека,
     * у которого есть собственный, незачем.
     */
    @Test
    fun `the built-in profile switches off as soon as an imported one exists`() {
        val empty = DnsProfileStore.withBuiltIn(DnsProfileList())
        val builtIn = empty.profiles.single { it.builtIn }
        assertTrue("без своих встроенный выбираем", empty.selectable(builtIn))

        val withOwn = listOfOwn("a")
        assertFalse("со своим встроенный выключен", withOwn.selectable(withOwn.profiles.single { it.builtIn }))
        assertTrue(withOwn.selectable(withOwn.profiles.first { !it.builtIn }))
    }

    @Test
    fun `deleting every imported profile switches the built-in back on`() {
        val withOwn = listOfOwn("a", "b")
        val afterOne = withOwn.withProfileRemoved("a")
        assertFalse(afterOne.selectable(afterOne.profiles.single { it.builtIn }))
        val afterAll = afterOne.withProfileRemoved("b")
        assertTrue(afterAll.selectable(afterAll.profiles.single { it.builtIn }))
    }

    /** Встроенный — часть приложения, а не выбор человека: удалить его нечем. */
    @Test
    fun `the built-in profile cannot be deleted`() {
        val list = DnsProfileStore.withBuiltIn(DnsProfileList())
        val after = list.withProfileRemoved(DnsProfileList.BUILT_IN_PROFILE_ID)
        assertTrue(after.profiles.any { it.builtIn })
    }

    /** Порядок в списке и есть приоритет — потому профили и двигают. */
    @Test
    fun `moving a profile changes the order and stops at the ends`() {
        val list = listOfOwn("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), list.profiles.filterNot { it.builtIn }.map { it.id })

        val down = list.withProfileMoved("a", 1)
        assertEquals(listOf("b", "a", "c"), down.profiles.filterNot { it.builtIn }.map { it.id })

        val up = down.withProfileMoved("a", -1)
        assertEquals(listOf("a", "b", "c"), up.profiles.filterNot { it.builtIn }.map { it.id })

        // Выше первого и ниже последнего двигать некуда, и это не ошибка.
        assertEquals(up.profiles.map { it.id }, up.withProfileMoved("a", -1).profiles.map { it.id })
    }

    @Test
    fun `an imported profile lands above the built-in one`() {
        val list = DnsProfileStore.withBuiltIn(DnsProfileList()).withProfileAdded(own("a"))
        assertEquals("a", list.profiles.first().id)
        assertTrue(list.profiles.last().builtIn)
    }

    /** Та же ссылка, вставленная дважды, — это путаница в списке, а не запас. */
    @Test
    fun `re-importing the same endpoint replaces it instead of duplicating`() {
        val list = DnsProfileStore.withBuiltIn(DnsProfileList())
            .withProfileAdded(own("a", domain = "t.example.com", key = "kk"))
            .withProfileAdded(own("b", domain = "t.example.com", key = "kk"))
        assertEquals(1, list.profiles.count { !it.builtIn })
        assertEquals("b", list.profiles.first().id)
    }

    @Test
    fun `active picks the first selectable profile that is actually usable`() {
        val broken = DnsProfile(
            id = "ssh", name = "ssh", engine = DnsProfileImport.Engine.DNSTT,
            domain = "t.example.com", key = "aa", needsSsh = true,
        )
        val good = own("good")
        val list = DnsProfileStore.withBuiltIn(DnsProfileList(profiles = listOf(broken, good)))
        assertEquals("good", list.active()?.id)
    }

    /**
     * Ненастроенный встроенный (сервер не поднят) подключаться не должен и
     * обязан сказать почему. С 2026-09-29 сервер есть, и встроенный настроен —
     * поэтому состояние задаётся здесь явно, а не берётся из сборки.
     */
    @Test
    fun `an unconfigured built-in profile is never active and says why`() {
        val unconfigured = DnsProfileList.BUILT_IN_PROFILE.copy(domain = "", key = "", configured = false)
        val list = DnsProfileStore.withBuiltIn(DnsProfileList(), unconfigured)
        assertNull(list.active())
        val builtIn = list.profiles.single { it.builtIn }
        assertFalse(builtIn.usable)
        assertEquals("сервер ещё не настроен", builtIn.blockedReason)
    }

    /**
     * Профиль семейства CottenDNS подключается: движок лежит в APK отдельным
     * процессом. Проверено 2026-09-20 на живой точке выхода `de1.iran.qzz.io`.
     */
    @Test
    fun `a cotten profile with a known cipher is usable`() {
        val cotten = DnsProfile(
            id = "c", name = "c", engine = DnsProfileImport.Engine.COTTEN,
            domain = "vpn.de.prtw.ru", key = "c680", flavour = "cottendns",
            encryptionMethod = 1,
        )
        assertTrue(cotten.usable)
        assertEquals("", cotten.blockedReason)
    }

    /** Чужой шифр — отказ вслух, а не молчаливая попытка (I4). */
    @Test
    fun `a cotten profile with an unknown cipher is refused with a named reason`() {
        val cotten = DnsProfile(
            id = "c", name = "c", engine = DnsProfileImport.Engine.COTTEN,
            domain = "vpn.de.prtw.ru", key = "c680", flavour = "cottendns",
            encryptionMethod = 9,
        )
        assertFalse(cotten.usable)
        assertTrue(cotten.blockedReason.contains("9"))
    }

    @Test
    fun `built-in resolver lists are present and non-empty`() {
        assertEquals(3, DnsProfileList.BUILT_IN_LISTS.size)
        assertTrue(DnsProfileList.BUILT_IN_LISTS.all { it.addresses.isNotEmpty() && it.builtIn })
        val list = DnsProfileList(resolverListId = "ru-yandex")
        assertTrue(list.resolverAddresses().contains("77.88.8.8:53"))
    }

    @Test
    fun `an imported list is selected at once and replaces a same-named one`() {
        val first = DnsProfileList().withListAdded(DnsResolverList("x1", "Мой", listOf("1.1.1.1")))
        assertEquals("x1", first.resolverListId)
        val second = first.withListAdded(DnsResolverList("x2", "Мой", listOf("8.8.8.8")))
        assertEquals(1, second.importedLists.size)
        assertEquals("x2", second.resolverListId)
        assertEquals(listOf("8.8.8.8"), second.resolverAddresses())
    }

    /**
     * Две подборки из разных каналов — это две подборки. Раньше обе приходили
     * под именем «Свой список», и вторая молча затирала первую.
     */
    @Test
    fun `a second own list gets its own name instead of replacing the first`() {
        val one = DnsProfileList()
        val nameA = one.nextImportedListName("Свой список")
        assertEquals("Свой список", nameA)
        val withA = one.withListAdded(DnsResolverList("x1", nameA, listOf("1.1.1.1")))
        val nameB = withA.nextImportedListName("Свой список")
        assertEquals("Свой список 2", nameB)
        val withB = withA.withListAdded(DnsResolverList("x2", nameB, listOf("8.8.8.8")))
        assertEquals(2, withB.importedLists.size)
        assertEquals("x2", withB.resolverListId)
        assertEquals("Свой список 3", withB.nextImportedListName("Свой список"))
    }

    /** Повторная вставка того же самого выбирает уже лежащий список, а не двоит. */
    @Test
    fun `re-importing the same addresses selects the existing list`() {
        val withA = DnsProfileList()
            .withListAdded(DnsResolverList("x1", "Свой список", listOf("1.1.1.1", "8.8.8.8")))
        val again = withA
            .copy(resolverListId = "ru-mobile")
            .withListAdded(DnsResolverList("x2", "Свой список 2", listOf("1.1.1.1", "8.8.8.8")))
        assertEquals(1, again.importedLists.size)
        assertEquals("x1", again.resolverListId)
    }

    @Test
    fun `the list survives a write-read round trip, built-in excluded from the file`() {
        val source = DnsProfileStore.withBuiltIn(
            DnsProfileList(
                profiles = listOf(own("a"), own("b")),
                resolverListId = "ru-mobile",
                importedLists = listOf(DnsResolverList("x1", "Мой", listOf("9.9.9.9"))),
            )
        )
        val json = DnsProfileStore.toJson(source)
        assertFalse("встроенный в файл не пишется", json.contains(DnsProfileList.BUILT_IN_PROFILE_ID))
        val back = DnsProfileStore.withBuiltIn(DnsProfileStore.fromJson(json))
        assertEquals(listOf("a", "b"), back.profiles.filterNot { it.builtIn }.map { it.id })
        assertEquals("ru-mobile", back.resolverListId)
        assertEquals(1, back.importedLists.size)
        assertTrue(back.profiles.any { it.builtIn })
    }
}
