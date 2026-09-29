package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор проверяется на **настоящих** ссылках, выданных ботом `@rostunnelbot`,
 * а не на выдуманных: формат чужой, и придуманный образец подтвердил бы только
 * то, что разбор согласен сам с собой.
 */
class DnsProfileImportTest {

    private val slipnet = "slipnet://Mjh8ZG5zdHRfc3NofPCfh7fwn4e6INCg0L7RgdGB0LjRjyAvINGC0LPQujogQHJvc3R1bm5lbHxzLmRuc3R0LnJ1fDE4NS4yMi4yMzQuMjI5OjUzOjAsNS42MS44LjIwOjUzOjAsMTAuMjIwLjEzOC4xOjUzOjAsNzcuODguOC43OjUzOjAsODQuNTMuMjAxLjIwMjo1MzowLDc3Ljg4LjguMzo1MzowLDE4NS4yMi4yMzUuMTM3OjUzOjAsNzcuODguOC4yOjUzOjAsOTUuMTY3LjE2LjIzNzo1MzowLDE4NS42OC4xMDMuMjQ5OjUzOjB8MHw1MDAwfGJicnwxMDgwfDEyNy4wLjAuMXwwfDQ5MjY2ZTk3ODNhZWQ0NmI5MGM1YTM1OGRlZjk1NDhiNmY5YzZkNmM5NjM0ODE2YjVjOWZlNzQ1MmVjODkwMzl8fHwxfGRuc3R0fGVleGxzZG44YjNkeDF4dmhlZnlkMXd0enwyMnwwfDEyNy4wLjAuMXwwfHx1ZHB8cGFzc3dvcmR8fHx8MHw0NDN8fHwwfHwwfDB8fDB8fDB8MTAwfDEwODB8MHx0eHR8MTAxfDAuMHwwfDB8MHwwfDB8MHx8fDgwODB8fDB8L3wxfHx8cm91bmRyb2JpbnwzfHx0bHN8d3N8L3x8NDQzfDF8c25pX3NwbGl0fDMwMHx8MHwwfDB8OHx8MHw="

    private val stormdns = "stormdns://eyJzY2hlbWEiOiJ3aGl0ZWRucy5wcm9maWxlIiwidmVyc2lvbiI6MSwiaW1wb3J0X3R5cGUiOiJzdG9ybWRucyIsInByb2ZpbGUiOnsibmFtZSI6IvCfh6nwn4eqINCT0LXRgNC80LDQvdC40Y8gfCDRgtCz0Lo6IEByb3N0dW5uZWwiLCJzZXJ2ZXIiOnsiZG9tYWluIjoiZGUxLmlyYW4ucXp6LmlvIiwiZG9tYWlucyI6WyJkZTEuaXJhbi5xenouaW8iXSwiZW5jcnlwdGlvbl9rZXkiOiJlNjEyMWE4Zi1UZWxlZ3JhbUBTT1NJcmFuQ29ubmVjdCIsImVuY3J5cHRpb25fbWV0aG9kIjoxfX19"

    private val cottendns = "cottendns://eyJzY2hlbWEiOiJ3aGl0ZWRucy5wcm9maWxlIiwidmVyc2lvbiI6MSwiaW1wb3J0X3R5cGUiOiJjb3R0ZW5kbnMiLCJwcm9maWxlIjp7Im5hbWUiOiLwn4ep8J-HqiBAUnRwVmFkaW0gfCDRgtCz0Lo6IEByb3N0dW5uZWwiLCJzZXJ2ZXIiOnsiZG9tYWluIjoidnBuLmRlLnBydHcucnUiLCJkb21haW5zIjpbInZwbi5kZS5wcnR3LnJ1Il0sImVuY3J5cHRpb25fa2V5IjoiYzY4MGFlNGU2ZGRmZTUyNzMxMDI3OGYyMWQxNjI5OTEiLCJlbmNyeXB0aW9uX21ldGhvZCI6MX19fQ"

    private fun imported(raw: String): DnsProfileImport.Profile {
        val result = DnsProfileImport.parse(raw)
        assertTrue("ожидали профиль, получили $result", result is DnsProfileImport.Result.Imported)
        return (result as DnsProfileImport.Result.Imported).profile
    }

    @Test
    fun `slipnet is recognised as dnstt and every field lands where it belongs`() {
        val p = imported(slipnet)
        assertEquals(DnsProfileImport.Engine.DNSTT, p.engine)
        assertEquals("s.dnstt.ru", p.domain)
        assertEquals("49266e9783aed46b90c5a358def9548b6f9c6d6c9634816b5c9fe7452ec89039", p.key)
        assertTrue(DnsProfileImport.isNoiseKey(p.key))
        assertEquals("txt", p.recordType)
        assertEquals(101, p.maxQnameLen)
        assertTrue("имя из ссылки должно доехать", p.name.contains("rostunnel"))
    }

    /** Флаг в записи `адрес:порт:флаг` нам не нужен, а порт нужен. */
    @Test
    fun `slipnet resolvers keep their port and drop the trailing flag`() {
        val p = imported(slipnet)
        assertEquals(10, p.resolvers.size)
        assertEquals("185.22.234.229:53", p.resolvers.first())
        assertTrue(p.resolvers.contains("77.88.8.7:53"))
        assertTrue("флаг :0 обязан исчезнуть", p.resolvers.none { it.count { c -> c == ':' } > 1 })
    }

    /**
     * У этого профиля за туннелем SSH, а не SOCKS. Промолчать об этом — значит
     * дать человеку выбрать точку входа, которая никогда не подключится.
     */
    @Test
    fun `slipnet with an ssh far end is imported but marked unsupported, with the reason`() {
        val p = imported(slipnet)
        assertNotNull(p.innerSsh)
        assertEquals(22, p.innerSsh!!.port)
        assertFalse(p.supported)
        val reason = DnsProfileImport.unsupportedReason(p)
        assertNotNull(reason)
        assertTrue("причина обязана называть SSH", reason!!.contains("SSH"))
    }

    @Test
    fun `stormdns is recognised as the cotten family with its shared key`() {
        val p = imported(stormdns)
        assertEquals(DnsProfileImport.Engine.COTTEN, p.engine)
        assertEquals("stormdns", p.flavour)
        assertEquals("de1.iran.qzz.io", p.domain)
        assertEquals("e6121a8f-Telegram@SOSIranConnect", p.key)
        assertEquals(1, p.encryptionMethod)
        // Движок семейства встроен (libstormdns.so), поэтому профиль рабочий.
        assertTrue(p.supported)
        assertNull(DnsProfileImport.unsupportedReason(p))
    }

    @Test
    fun `cottendns is recognised and its key is not mistaken for a dnstt key`() {
        val p = imported(cottendns)
        assertEquals(DnsProfileImport.Engine.COTTEN, p.engine)
        assertEquals("cottendns", p.flavour)
        assertEquals("vpn.de.prtw.ru", p.domain)
        assertEquals("c680ae4e6ddfe527310278f21d162991", p.key)
        // Ровно 32 шестнадцатеричных знака — вдвое короче ключа dnstt. Если бы
        // разбор судил «похоже на hex», он принял бы это за ключ Noise.
        assertFalse(DnsProfileImport.isNoiseKey(p.key))
        assertEquals(1, p.encryptionMethod)
    }

    /** Схему можно и не писать: люди вставляют и голый base64. */
    @Test
    fun `a bare base64 body is recognised without a scheme`() {
        val p = imported(stormdns.substringAfter("://"))
        assertEquals("de1.iran.qzz.io", p.domain)
    }

    @Test
    fun `a plain list of resolvers is recognised as a list`() {
        val text = """
            94.25.113.230
            95.167.150.28
            91.240.86.14
            85.95.168.122
        """.trimIndent()
        val result = DnsProfileImport.parse(text)
        assertTrue(result is DnsProfileImport.Result.Resolvers)
        assertEquals(4, (result as DnsProfileImport.Result.Resolvers).addresses.size)
    }

    @Test
    fun `a list with explicit ports survives, duplicates do not`() {
        val result = DnsProfileImport.parse("77.88.8.8:53, 77.88.8.8:53, 5.61.8.20:53")
        assertTrue(result is DnsProfileImport.Result.Resolvers)
        assertEquals(
            listOf("77.88.8.8:53", "5.61.8.20:53"),
            (result as DnsProfileImport.Result.Resolvers).addresses,
        )
    }

    /**
     * Текст, где адрес попался случайно, списком считаться не должен: иначе из
     * пересланного сообщения получился бы список из одного адреса.
     */
    @Test
    fun `prose containing an address is not a resolver list`() {
        val result = DnsProfileImport.parse("используй 8.8.8.8 если что")
        assertTrue(result is DnsProfileImport.Result.Failure)
    }

    @Test
    fun `an unknown scheme is refused by name, not guessed`() {
        val result = DnsProfileImport.parse("vmess://something")
        assertTrue(result is DnsProfileImport.Result.Failure)
        assertTrue((result as DnsProfileImport.Result.Failure).reason.contains("vmess"))
    }

    @Test
    fun `empty input fails with a reason instead of an empty profile`() {
        val result = DnsProfileImport.parse("   ")
        assertTrue(result is DnsProfileImport.Result.Failure)
        assertTrue((result as DnsProfileImport.Result.Failure).reason.isNotBlank())
    }

    @Test
    fun `base64 decodes in both alphabets and without padding`() {
        // "hello" в обычном алфавите, без добивки и с ней.
        assertEquals("hello", DnsProfileImport.decodeBase64("aGVsbG8"))
        assertEquals("hello", DnsProfileImport.decodeBase64("aGVsbG8="))
        // URL-safe: '-' и '_' вместо '+' и '/'.
        assertEquals("~~~?", DnsProfileImport.decodeBase64("fn5-Pw"))
        assertNull(DnsProfileImport.decodeBase64("!!!!"))
        assertNull(DnsProfileImport.decodeBase64(""))
        // Косая черта на конце — данные, а не оформление: «Pz8/» это «???».
        // Пока она снималась всегда, у каждой шестьдесят четвёртой ссылки молча
        // терялся последний байт.
        assertEquals("???", DnsProfileImport.decodeBase64("Pz8/"))
    }

    /** Текст длиннее потолка отбивается причиной, а не разворачивается в память. */
    @Test
    fun `an oversized paste is refused with a reason`() {
        val huge = "8.8.8.8\n".repeat(DnsProfileImport.MAX_INPUT_CHARS / 4)
        val result = DnsProfileImport.parse(huge)
        assertTrue(result is DnsProfileImport.Result.Failure)
        assertTrue((result as DnsProfileImport.Result.Failure).reason.contains("Слишком длинный"))
    }

    @Test
    fun `resolver shapes are judged, not guessed`() {
        assertTrue(DnsProfileImport.looksLikeResolver("8.8.8.8"))
        assertTrue(DnsProfileImport.looksLikeResolver("8.8.8.8:53"))
        assertTrue(DnsProfileImport.looksLikeResolver("[2001:4860:4860::8888]:53"))
        assertTrue(DnsProfileImport.looksLikeResolver("https://dns.google/dns-query"))
        assertFalse(DnsProfileImport.looksLikeResolver("8.8.8.8:99999"))
        assertFalse(DnsProfileImport.looksLikeResolver("привет"))
        assertFalse(DnsProfileImport.looksLikeResolver("999.1.1.1"))
    }

    /**
     * Подборка приходит файлом, а в файле рядом с адресом пишут, чей он.
     * Комментарий не должен обесценивать весь файл.
     */
    @Test
    fun `comments in a list file do not disqualify it`() {
        val text = """
            # Подборка из канала, 2026
            94.25.113.230   # МегаФон
            95.167.150.28
            // выключено: 91.240.86.14
            77.88.8.8:53
        """.trimIndent()
        val result = DnsProfileImport.parse(text)
        assertTrue("ожидали список, получили $result", result is DnsProfileImport.Result.Resolvers)
        assertEquals(
            listOf("94.25.113.230", "95.167.150.28", "77.88.8.8:53"),
            (result as DnsProfileImport.Result.Resolvers).addresses,
        )
    }

    /** Комментарий не имеет права съесть `https://` резолвера DoH. */
    @Test
    fun `a doh address survives comment stripping`() {
        val result = DnsProfileImport.parse("https://dns.google/dns-query\n8.8.8.8")
        assertTrue(result is DnsProfileImport.Result.Resolvers)
        assertEquals(
            listOf("https://dns.google/dns-query", "8.8.8.8"),
            (result as DnsProfileImport.Result.Resolvers).addresses,
        )
    }

    /** Файл из одних комментариев — не список, а именно неудача с причиной. */
    @Test
    fun `a file of comments alone is not a list`() {
        val result = DnsProfileImport.parse("# только заголовок\n# и ещё строка")
        assertTrue(result is DnsProfileImport.Result.Failure)
    }

}
