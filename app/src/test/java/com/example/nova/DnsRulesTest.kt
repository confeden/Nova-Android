package com.example.nova

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `the transport survives a round trip`() {
        val rule = DnsRule(
            id = "x",
            kind = DnsRule.Kind.DOH,
            value = "https://dns.google/dns-query",
            bootstrap = listOf("8.8.8.8"),
            transport = DnsRule.Transport.ANY,
        )
        assertEquals(rule, DnsRule.fromJson(rule.toJson()))
        assertEquals("any", rule.toJson().optString("transport"))
    }

    /**
     * Отсутствие поля — это файл прошлой версии, а не выбор «любой» (I3).
     *
     * Прочитать его как «любой» значило бы при обновлении молча превратить пять
     * работающих DoH-резолверов в пары с замером — то есть добавить каждой
     * попытке подключения цену, которой человек не просил.
     */
    @Test
    fun `an absent transport is derived from the kind`() {
        fun restored(kind: String, value: String): DnsRule? = DnsRule.fromJson(
            JSONObject().put("id", "x").put("kind", kind).put("value", value)
        )
        assertEquals(
            DnsRule.Transport.DOH,
            restored("doh", "https://dns.comss.one/dns-query")?.transport,
        )
        assertEquals(DnsRule.Transport.DOT, restored("dot", "tls://dns.quad9.net")?.transport)
        // У нашей строки транспорт всегда «любой»: ради автовыбора она и заведена.
        assertEquals(DnsRule.Transport.ANY, restored("auto", PriorityDns.HOST)?.transport)
        assertEquals(DnsRule.Transport.DOH, restored("provider", "provider")?.transport)
    }

    /** Встроенные резолверы спрашиваются тем транспортом, который отвечает быстрее. */
    @Test
    fun `the shipped resolvers ask on whichever transport answers first`() {
        assertEquals(
            listOf("owner", "comss", "geohide", "xbox", "cloudflare", "google"),
            DnsRulesStore.defaults()
                .filter { it.transport == DnsRule.Transport.ANY }
                .map { it.id },
        )
    }

    /**
     * Миграция вправе трогать только то, что клали мы (I1).
     *
     * Строка, которую человек добавил или поправил, пришла из диалога с явно
     * выбранным DoH или DoT, и переписать этот выбор молча нельзя.
     */
    @Test
    fun `the transport upgrade touches only untouched shipped rules`() {
        val shipped = DnsRulesStore.defaults().first { it.id == "comss" }
            .copy(transport = DnsRule.Transport.DOH)
        assertEquals(
            DnsRule.Transport.ANY,
            DnsRulesStore.upgradedToAnyTransport(shipped).transport,
        )
        // Адрес поправлен — строка больше не наша, выбор остаётся за человеком.
        val edited = shipped.copy(value = "https://dns.comss.one/resolve")
        assertEquals(
            DnsRule.Transport.DOT,
            DnsRulesStore.upgradedToAnyTransport(
                edited.copy(transport = DnsRule.Transport.DOT)
            ).transport,
        )
        // Чужое правило не трогаем вовсе.
        val mine = DnsRule(id = "mine", kind = DnsRule.Kind.DOT, value = "tls://dns.quad9.net")
        assertEquals(mine, DnsRulesStore.upgradedToAnyTransport(mine))
        // Провайдерская ступень остаётся собой: транспорта у неё нет.
        val provider = DnsRulesStore.defaults().last()
        assertEquals(provider, DnsRulesStore.upgradedToAnyTransport(provider))
    }

    /**
     * Транспорт не расширяет вырез маршрута (I8, D10, G96).
     *
     * Вырез — это `excludeRoute` на весь адрес: попади туда bootstrap чужого
     * резолвера, любое приложение с зашитым `8.8.8.8` слало бы открытый запрос
     * мимо туннеля. Выбор транспорта к этому списку отношения не имеет и иметь
     * не должен.
     */
    @Test
    fun `the carve-out does not depend on the transport`() {
        val rules = listOf(
            DnsRule(
                id = "owner",
                kind = DnsRule.Kind.AUTO,
                value = PriorityDns.HOST,
                bootstrap = listOf("192.144.59.14"),
            ),
            DnsRule(
                id = "google",
                kind = DnsRule.Kind.DOH,
                value = "https://dns.google/dns-query",
                bootstrap = listOf("8.8.8.8"),
            ),
        )
        val expected = listOf("192.144.59.14", "8.8.8.8")
        for (transport in DnsRule.Transport.values()) {
            assertEquals(
                expected,
                DnsRuleSet(
                    rules.map { it.copy(transport = transport) },
                    DnsRouteMode.DIRECT,
                ).directBypassAddresses(),
            )
        }
    }

    /**
     * Открытого транспорта нет и быть не может.
     *
     * Адрес открытого правила уезжает в вырез маршрута, то есть его запросы
     * покидают туннель в открытую. Поэтому у выбора ровно три значения, и ни
     * одна ошибка экрана не должна уметь изготовить четвёртое.
     */
    @Test
    fun `create cannot manufacture a plaintext transport`() {
        assertEquals(3, DnsRule.Transport.values().size)
        assertTrue(DnsRule.Transport.values().none { it.storageValue() == "plain" })
        // Вид, у которого транспорта нет, чужой выбор не принимает.
        assertEquals(
            DnsRule.Transport.DOH,
            DnsRule.create(
                DnsRule.Kind.PLAIN,
                "1.1.1.1",
                transport = DnsRule.Transport.ANY,
            )?.transport,
        )
        // Новое правило заводится «любым»: спрашивается то, что отвечает быстрее.
        assertEquals(
            DnsRule.Transport.ANY,
            DnsRule.create(DnsRule.Kind.DOH, "https://dns.google/dns-query")?.transport,
        )
        assertEquals(
            DnsRule.Transport.DOT,
            DnsRule.create(
                DnsRule.Kind.DOT,
                "dns.quad9.net",
                transport = DnsRule.Transport.DOT,
            )?.transport,
        )
    }
}
