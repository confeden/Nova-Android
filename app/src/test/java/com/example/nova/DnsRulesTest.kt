package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правила DNS: что пользователь может ввести и во что это превращается для ядра.
 *
 * Проверяется здесь ровно то, что ломается молча. Значение с запятой или `|`
 * внутри разваливает список апстримов, который уезжает в ядро одной строкой, и
 * разбирается там во что-то другое — не там, где его вводили.
 */
class DnsRulesTest {

    @Test
    fun `a plain rule accepts only literal addresses`() {
        assertEquals("1.1.1.1", DnsRule.normalizeValue(DnsRule.Kind.PLAIN, " 1.1.1.1 "))
        assertEquals(
            "2606:4700:4700::1111",
            DnsRule.normalizeValue(DnsRule.Kind.PLAIN, "2606:4700:4700::1111"),
        )
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.PLAIN, "dns.google"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.PLAIN, "999.1.1.1"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.PLAIN, ""))
    }

    @Test
    fun `separators are refused in every kind`() {
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.PLAIN, "1.1.1.1,8.8.8.8"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.PLAIN, "1.1.1.1|via=tunnel"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.DOH, "https://a/dns-query|1.1.1.1"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.DOT, "tls://a,b"))
    }

    @Test
    fun `doh needs an https url with a host`() {
        assertEquals(
            "https://dns.dns-ai.ru/dns-query",
            DnsRule.normalizeValue(DnsRule.Kind.DOH, "https://dns.dns-ai.ru/dns-query"),
        )
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.DOH, "http://dns.dns-ai.ru/dns-query"))
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.DOH, "dns.dns-ai.ru"))
    }

    @Test
    fun `dot gets its scheme back when the user omits it`() {
        assertEquals("tls://dns.dns-ai.ru", DnsRule.normalizeValue(DnsRule.Kind.DOT, "dns.dns-ai.ru"))
        assertEquals(
            "tls://dns.dns-ai.ru:853",
            DnsRule.normalizeValue(DnsRule.Kind.DOT, "tls://dns.dns-ai.ru:853"),
        )
        assertNull(DnsRule.normalizeValue(DnsRule.Kind.DOT, "tls://"))
    }

    @Test
    fun `an upstream carries its bootstrap and its path`() {
        val rule = DnsRule(
            id = "x",
            kind = DnsRule.Kind.DOH,
            value = "https://dns.dns-ai.ru/dns-query",
            bootstrap = listOf("192.144.59.14", "186.246.49.127"),
        )
        assertEquals(
            "https://dns.dns-ai.ru/dns-query|192.144.59.14|186.246.49.127",
            rule.toUpstream(DnsRouteMode.DIRECT),
        )
        assertEquals(
            "https://dns.dns-ai.ru/dns-query|192.144.59.14|186.246.49.127|via=tunnel",
            rule.toUpstream(DnsRouteMode.TUNNEL),
        )
        assertEquals(
            "https://dns.dns-ai.ru/dns-query|192.144.59.14|186.246.49.127|via=auto",
            rule.toUpstream(DnsRouteMode.AUTO),
        )
    }

    /**
     * Открытый резолвер не участвует в гонке путей: подделанный ответ приходит
     * раньше настоящего, и «быстрейший» выбрал бы подделку. Ядро проверяет это
     * ещё раз у себя, но писать `via=auto` для него мы не должны и здесь.
     */
    @Test
    fun `a plain upstream never asks for the path race`() {
        val rule = DnsRule(id = "x", kind = DnsRule.Kind.PLAIN, value = "8.8.8.8")
        assertEquals("8.8.8.8", rule.toUpstream(DnsRouteMode.AUTO))
        assertEquals("8.8.8.8|via=tunnel", rule.toUpstream(DnsRouteMode.TUNNEL))
    }

    @Test
    fun `a disabled rule leaves the chain`() {
        val set = DnsRuleSet(
            rules = listOf(
                DnsRule(id = "a", kind = DnsRule.Kind.PLAIN, value = "1.1.1.1", enabled = false),
                DnsRule(id = "b", kind = DnsRule.Kind.PLAIN, value = "8.8.8.8"),
            ),
            routeMode = DnsRouteMode.DIRECT,
        )
        assertEquals(listOf("8.8.8.8"), set.upstreams())
    }

    /**
     * Вырез маршрута сильнее метки сокета: адрес с `excludeRoute` не попадает в
     * TUN, помечен сокет или нет. Поэтому в режиме «через VPN» выводить наружу
     * нельзя ничего — иначе настройка не действует, а выглядит действующей.
     */
    @Test
    fun `the tunnel path carves out nothing`() {
        val rules = listOf(
            DnsRule(
                id = "a",
                kind = DnsRule.Kind.DOH,
                value = "https://dns.dns-ai.ru/dns-query",
                bootstrap = listOf("192.144.59.14"),
            ),
            DnsRule(id = "b", kind = DnsRule.Kind.PLAIN, value = "1.1.1.1"),
        )
        assertEquals(
            listOf("192.144.59.14", "1.1.1.1"),
            DnsRuleSet(rules, DnsRouteMode.DIRECT).directBypassAddresses(),
        )
        assertEquals(
            emptyList<String>(),
            DnsRuleSet(rules, DnsRouteMode.TUNNEL).directBypassAddresses(),
        )
    }

    @Test
    fun `the whole list can be switched off without losing it`() {
        val set = DnsRuleSet(
            rules = listOf(DnsRule(id = "a", kind = DnsRule.Kind.PLAIN, value = "1.1.1.1")),
            routeMode = DnsRouteMode.DIRECT,
            enabled = false,
        )
        assertEquals(emptyList<String>(), set.upstreams())
        assertEquals(emptyList<String>(), set.directBypassAddresses())
        assertEquals(1, set.rules.size)
    }

    /**
     * Умолчания: наш резолвер первым, все шифрованные, провайдерский последним.
     *
     * Порядок назвал владелец, и он же и есть каскад: ядро берёт следующий
     * апстрим ровно тогда, когда предыдущий не ответил.
     */
    @Test
    fun `the defaults start with the owner and end with the provider`() {
        val defaults = DnsRulesStore.defaults()
        assertEquals(DnsRule.Kind.AUTO, defaults.first().kind)
        assertEquals(PriorityDns.HOST, defaults.first().value)
        assertEquals(PriorityDns.KNOWN_ADDRESSES, defaults.first().bootstrap)
        assertEquals(DnsRule.Kind.PROVIDER, defaults.last().kind)
        assertEquals(DnsRulesStore.PROVIDER_RULE_ID, defaults.last().id)
        // Провайдерский — единственный незашифрованный во всём списке.
        assertEquals(1, defaults.count { !it.encrypted })
        assertEquals(
            listOf("owner", "comss", "geohide", "xbox", "cloudflare", "google", "provider"),
            defaults.map { it.id },
        )
    }

    /**
     * Провайдерская ступень обязана пережить чтение файла.
     *
     * Её значение — метка, а не адрес: адрес приходит из свойств сети при
     * подключении. Пока `normalizeValue` требовал от неё настоящий адрес,
     * `fromJson` выбрасывал правило целиком, и вместе с ним исчезал выключатель
     * пользователя.
     */
    @Test
    fun `the provider rule survives a round trip`() {
        val rule = DnsRulesStore.defaults().last()
        val restored = DnsRule.fromJson(rule.toJson())
        assertEquals(rule, restored)
        assertEquals(false, restored?.encrypted)
        // Адреса для выреза маршрута у неё нет: их подставляет служба.
        assertEquals(emptyList<String>(), rule.directAddresses())
    }

    /** Наш резолвер раскрывается в тот транспорт, который назвала служба. */
    @Test
    fun `the owner rule can be pointed at either transport`() {
        val rule = DnsRulesStore.defaults().first()
        assertEquals(
            "${PriorityDns.DOH_URL}|192.144.59.14|186.246.49.127",
            rule.toUpstream(DnsRouteMode.DIRECT, PriorityDns.DOH_URL),
        )
        assertEquals(
            "${PriorityDns.DOT_URL}|192.144.59.14|186.246.49.127",
            rule.toUpstream(DnsRouteMode.DIRECT, PriorityDns.DOT_URL),
        )
    }
}
