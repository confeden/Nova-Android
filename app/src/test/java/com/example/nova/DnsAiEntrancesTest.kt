package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Входы DNS-AI: личная ссылка, цепочка «прямо, потом туннель», секрет в журнале.
 */
class DnsAiEntrancesTest {

    private val token = "abcdefg234567"

    @Test
    fun `a personal link is accepted in every form the user may paste`() {
        val expected = DnsAiEntrances.PersonalLink("ru.dns-ai.ru", token)
        assertEquals(expected, DnsAiEntrances.parsePersonalLink(token))
        assertEquals(expected, DnsAiEntrances.parsePersonalLink("ru.dns-ai.ru/$token"))
        assertEquals(expected, DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token"))
        assertEquals(expected, DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token/"))
        assertEquals(expected, DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token{?dns}"))
        assertEquals(expected, DnsAiEntrances.parsePersonalLink("  https://RU.dns-ai.ru/$token?dns=AAAB  "))
        assertEquals("https://ru.dns-ai.ru/$token", expected.url)
    }

    @Test
    fun `any dns-ai subdomain can carry a personal link`() {
        val link = DnsAiEntrances.parsePersonalLink("https://spb.dns-ai.ru/Ab12Cd34Ef")
        assertEquals(DnsAiEntrances.PersonalLink("spb.dns-ai.ru", "Ab12Cd34Ef"), link)
    }

    @Test
    fun `foreign hosts and server addresses are not personal links`() {
        assertNull(DnsAiEntrances.parsePersonalLink("https://example.com/$token"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://dns-ai.ru/$token"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://evildns-ai.ru/$token"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://dns.dns-ai.ru/dns-query"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://cf.dns-ai.ru/dns-query"))
        assertNull(DnsAiEntrances.parsePersonalLink("http://ru.dns-ai.ru/$token"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token/extra"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/short"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token|via=tunnel"))
        assertNull(DnsAiEntrances.parsePersonalLink("https://ru.dns-ai.ru/$token,1.1.1.1"))
        assertNull(DnsAiEntrances.parsePersonalLink(""))
        assertNull(DnsAiEntrances.parsePersonalLink("dns.google"))
    }

    @Test
    fun `a rule accepts a personal link and keeps one canonical form`() {
        assertEquals(
            "https://ru.dns-ai.ru/$token",
            DnsRule.normalizeValue(DnsRule.Kind.DOH, "ru.dns-ai.ru/$token{?dns}"),
        )
        assertEquals("https://ru.dns-ai.ru/$token", DnsRule.normalizeValue(DnsRule.Kind.DOH, token))
        // Обычный DoH не затронут.
        assertEquals(
            "https://dns.dns-ai.ru/dns-query",
            DnsRule.normalizeValue(DnsRule.Kind.DOH, "https://dns.dns-ai.ru/dns-query"),
        )
    }

    @Test
    fun `a personal link goes to the core as a GET and is DoH only`() {
        val rule = DnsRule.create(DnsRule.Kind.DOH, "ru.dns-ai.ru/$token", emptyList(), DnsRule.Transport.ANY)
        assertNotNull(rule)
        rule!!
        assertEquals(DnsRule.Transport.DOH, rule.transport)
        val upstream = rule.toUpstream(DnsRouteMode.AUTO)
        assertTrue(upstream, upstream.startsWith("https://ru.dns-ai.ru/$token"))
        assertTrue(upstream, upstream.split('|').contains("m=get"))
    }

    /** Ссылка из Витрины приходит шаблоном `...{?dns}`: до ядра она доходит без скобок и всегда по GET. */
    @Test
    fun `a link pasted with the dns template always reaches the core as a GET`() {
        val pasted = "https://ru.dns-ai.ru/$token{?dns}"
        val value = DnsRule.normalizeValue(DnsRule.Kind.DOH, pasted)
        assertEquals("https://ru.dns-ai.ru/$token", value)
        for (transport in DnsRule.Transport.values()) {
            val rule = DnsRule.create(DnsRule.Kind.DOH, pasted, emptyList(), transport)!!
            assertEquals(DnsRule.Transport.DOH, rule.transport)
            for (mode in DnsRouteMode.values()) {
                val upstream = rule.toUpstream(mode, rule.value)
                assertFalse(upstream, upstream.contains("{") || upstream.contains("}") || upstream.contains("?"))
                assertTrue(upstream, upstream.split('|').contains("m=get"))
            }
        }
        // И после цепочки «прямо, потом туннель» оба экземпляра остаются GET.
        val chain = DnsAiEntrances.routeChain(
            listOf(DnsRule.create(DnsRule.Kind.DOH, pasted)!!.toUpstream(DnsRouteMode.AUTO))
        )
        assertEquals(2, chain.size)
        assertTrue(chain.all { it.split('|').contains("m=get") })
    }

    @Test
    fun `the vitrina button points at the group message`() {
        assertEquals("https://t.me/nova_txt/322474/375424", DnsAiEntrances.VITRINA_WEB_LINK)
        assertTrue(DnsAiEntrances.VITRINA_APP_LINK.startsWith("tg://resolve?domain=nova_txt"))
        assertTrue(DnsAiEntrances.VITRINA_APP_LINK.contains("post=375424"))
        assertTrue(DnsAiEntrances.VITRINA_APP_LINK.contains("thread=322474"))
    }

    @Test
    fun `an ordinary rule never gets the GET token`() {
        val rule = DnsRule.create(DnsRule.Kind.DOH, "https://dns.comss.one/dns-query")!!
        assertFalse(rule.toUpstream(DnsRouteMode.AUTO).contains("m=get"))
        assertEquals(DnsRule.Transport.ANY, rule.transport)
    }

    @Test
    fun `the cf entrance is a DoH only default right after the owner`() {
        val defaults = DnsRulesStore.defaults()
        assertEquals(listOf("owner", "cf"), defaults.take(2).map { it.id })
        val cf = defaults[1]
        assertEquals(DnsAiEntrances.CF_URL, cf.value)
        assertEquals(DnsRule.Transport.DOH, cf.transport)
        // Адреса CDN плавают: зашитого bootstrap у входа нет.
        assertTrue(cf.bootstrap.isEmpty())
        assertTrue(DnsAiEntrances.isDohOnly(cf.value))
        assertFalse(DnsAiEntrances.isDohOnly("https://dns.dns-ai.ru/dns-query"))
    }

    @Test
    fun `migration adds cf once and never twice`() {
        val old = DnsRulesStore.defaults().filterNot { it.id == "cf" }
        val migrated = DnsRulesStore.withCfEntrance(old)
        assertEquals(listOf("owner", "cf"), migrated.take(2).map { it.id })
        assertEquals(old.size + 1, migrated.size)
        assertEquals(migrated, DnsRulesStore.withCfEntrance(migrated))
    }

    @Test
    fun `migration respects a cf rule the user made himself`() {
        val own = DnsRule.create(DnsRule.Kind.DOH, "https://cf.dns-ai.ru/dns-query")!!
        val old = listOf(own) + DnsRulesStore.defaults().filterNot { it.id == "cf" }
        assertEquals(old, DnsRulesStore.withCfEntrance(old))
    }

    @Test
    fun `entrances go direct first and the same entrances through the tunnel after them`() {
        val dns = "https://dns.dns-ai.ru/dns-query|194.87.196.13|via=auto"
        val dot = "tls://dns.dns-ai.ru|194.87.196.13|via=auto"
        val cf = "https://cf.dns-ai.ru/dns-query|104.21.1.1|via=auto"
        val ru = "https://ru.dns-ai.ru/$token|188.72.112.1|m=get|via=auto"
        val comss = "https://dns.comss.one/dns-query|83.220.169.155|via=auto"
        val chain = DnsAiEntrances.routeChain(listOf(dns, dot, cf, ru, comss))
        assertEquals(
            listOf(
                "https://dns.dns-ai.ru/dns-query|194.87.196.13|via=obfsonly",
                "tls://dns.dns-ai.ru|194.87.196.13|via=obfsonly",
                "https://cf.dns-ai.ru/dns-query|104.21.1.1|via=obfsonly",
                "https://ru.dns-ai.ru/$token|188.72.112.1|m=get|via=obfsonly",
                "https://dns.dns-ai.ru/dns-query|194.87.196.13|via=tunnel",
                "tls://dns.dns-ai.ru|194.87.196.13|via=tunnel",
                "https://cf.dns-ai.ru/dns-query|104.21.1.1|via=tunnel",
                "https://ru.dns-ai.ru/$token|188.72.112.1|m=get|via=tunnel",
                comss,
            ),
            chain,
        )
    }

    @Test
    fun `foreign resolvers keep their place and route`() {
        val comss = "https://dns.comss.one/dns-query|83.220.169.155|via=auto"
        val google = "https://dns.google/dns-query|8.8.8.8|via=auto"
        assertEquals(listOf(comss, google), DnsAiEntrances.routeChain(listOf(comss, google)))
        assertEquals(emptyList<String>(), DnsAiEntrances.routeChain(emptyList()))
    }

    @Test
    fun `an entrance the user pinned to the tunnel is not tried directly`() {
        val pinned = "https://cf.dns-ai.ru/dns-query|via=tunnel"
        val dns = "https://dns.dns-ai.ru/dns-query|via=auto"
        val chain = DnsAiEntrances.routeChain(listOf(dns, pinned))
        assertEquals(
            listOf(
                "https://dns.dns-ai.ru/dns-query|via=obfsonly",
                pinned,
                "https://dns.dns-ai.ru/dns-query|via=tunnel",
            ),
            chain,
        )
    }

    @Test
    fun `the secret token never reaches the log or the screen`() {
        val link = "https://ru.dns-ai.ru/$token"
        assertFalse(DnsAiEntrances.logValue(link).contains(token))
        assertEquals("https://ru.dns-ai.ru/…", DnsAiEntrances.logValue(link))
        val upstream = "$link|188.72.112.1|m=get|via=obfsonly"
        val logged = DnsAiEntrances.logUpstream(upstream)
        assertFalse(logged, logged.contains(token))
        assertTrue(logged, logged.endsWith("|188.72.112.1|m=get|via=obfsonly"))
        val shown = DnsAiEntrances.displayValue(link)
        assertFalse(shown, shown.contains(token))
        assertTrue(shown, shown.startsWith("https://ru.dns-ai.ru/ab"))
        // Обычные адреса без изменений.
        assertEquals("https://cf.dns-ai.ru/dns-query", DnsAiEntrances.logValue("https://cf.dns-ai.ru/dns-query"))
        assertEquals("https://dns.comss.one/dns-query|1.1.1.1", DnsAiEntrances.logUpstream("https://dns.comss.one/dns-query|1.1.1.1"))
    }

    @Test
    fun `a malformed dns-ai link is explained without quoting it`() {
        val bad = "https://ru.dns-ai.ru/zz"
        val message = DnsAiEntrances.linkError(bad)
        assertNotNull(message)
        assertFalse(message!!.contains("zz"))
        // Ни ссылка DNS-AI, ни обычный адрес сервера — не наше дело.
        assertNull(DnsAiEntrances.linkError("https://dns.comss.one/dns-query"))
        assertNull(DnsAiEntrances.linkError("https://dns.dns-ai.ru/dns-query"))
        assertNull(DnsAiEntrances.linkError("https://ru.dns-ai.ru/$token"))
    }

    private fun answer(rcode: Int = 0, id0: Int = 0x4e, id1: Int = 0x56, flags: Int = 0x81): ByteArray =
        byteArrayOf(id0.toByte(), id1.toByte(), flags.toByte(), rcode.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)

    @Test
    fun `a good answer to the probe is accepted and a wrong one says why`() {
        assertEquals(DnsAiEntrances.LinkState.ACCEPTED, DnsAiEntrances.classifyResponse(200, answer()).state)
        val notFound = DnsAiEntrances.classifyResponse(404, ByteArray(0))
        assertEquals(DnsAiEntrances.LinkState.REJECTED, notFound.state)
        assertTrue(notFound.describe().contains("404"))
        // HTTP 200 without a real DNS answer must not be accepted.
        assertEquals(DnsAiEntrances.LinkState.UNVERIFIED, DnsAiEntrances.classifyResponse(200, ByteArray(3)).state)
        assertEquals(DnsAiEntrances.LinkState.UNVERIFIED, DnsAiEntrances.classifyResponse(200, answer(id0 = 1)).state)
        assertEquals(DnsAiEntrances.LinkState.UNVERIFIED, DnsAiEntrances.classifyResponse(200, answer(flags = 0x01)).state)
        val servfail = DnsAiEntrances.classifyResponse(200, answer(rcode = 2))
        assertEquals(DnsAiEntrances.LinkState.UNVERIFIED, servfail.state)
        assertTrue(servfail.describe().contains("RCODE 2"))
        assertTrue(DnsAiEntrances.classifyResponse(502, ByteArray(0)).describe().contains("HTTP 502"))
    }

    @Test
    fun `the built-in cloudflare entrance shows its short name`() {
        assertEquals("cf.dns-ai.ru", DnsAiEntrances.shortLabel(DnsAiEntrances.CF_URL))
        assertNull(DnsAiEntrances.shortLabel("https://dns.comss.one/dns-query"))
        assertNull(DnsAiEntrances.shortLabel("https://ru.dns-ai.ru/$token"))
    }
}
