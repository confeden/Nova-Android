package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessSubscriptionTest {

    private fun link(uuidTail: String, host: String, remark: String = "") =
        "vless://11111111-2222-3333-4444-$uuidTail@$host:443" +
            "?security=reality&sni=a.example&pbk=key123&type=tcp" +
            if (remark.isBlank()) "" else "#$remark"

    private fun read(body: String) = body.reader().buffered()

    @Test
    fun parsesPlainLinkList() {
        val body = """
            #profile-title: base64:TXlWUE4=
            #profile-update-interval: 6

            ${link("555555555555", "a.example", "RU-1")}
            ${link("555555555556", "b.example", "NL-2")}
            not-a-link
        """.trimIndent()

        val configs = VlessSubscription.parse(read(body))
        assertEquals(2, configs.size)
        assertEquals("a.example", configs[0].host)
        assertEquals("NL-2", configs[1].remark)

        val meta = VlessSubscription.parseInlineMetadata(body.lines().take(5))
        assertEquals("MyVPN", meta.title)
        assertEquals(6, meta.updateIntervalHours)
    }

    @Test
    fun parsesBase64WrappedBody() {
        val plain = link("555555555555", "a.example", "RU-1") + "\n" +
            link("555555555556", "b.example", "NL-2")
        val encoded = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())

        val configs = VlessSubscription.parse(read(encoded))
        assertEquals(2, configs.size)
        assertEquals("b.example", configs[1].host)
    }

    @Test
    fun parsesBase64WithoutPaddingAndUrlSafeAlphabet() {
        val plain = link("555555555555", "a.example")
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.toByteArray())

        val configs = VlessSubscription.parse(read(encoded))
        assertEquals(1, configs.size)
        assertEquals("a.example", configs[0].host)
    }

    @Test
    fun readsSubscriptionUserInfoHeaders() {
        val meta = VlessSubscription.parseMetadata(
            mapOf(
                "Subscription-Userinfo" to
                    "upload=455727941; download=6174315083; total=1073741824000; expire=1671815872",
                "profile-update-interval" to "24",
                "profile-title" to "Plain Title",
            )
        )
        assertEquals(455727941L, meta.uploadBytes)
        assertEquals(6174315083L, meta.downloadBytes)
        assertEquals(1073741824000L, meta.totalBytes)
        assertEquals(1671815872L, meta.expireAtSeconds)
        assertEquals(24, meta.updateIntervalHours)
        assertEquals("Plain Title", meta.title)
    }

    @Test
    fun treatsMissingUserInfoFieldsAsUnknown() {
        val meta = VlessSubscription.parseMetadata(
            mapOf("subscription-userinfo" to "upload=1; download=2; total=3; expire=")
        )
        assertEquals(1L, meta.uploadBytes)
        assertEquals(-1L, meta.expireAtSeconds)
    }

    @Test
    fun diffMatchesOnIdentityNotName() {
        val before = VlessSubscription.parse(
            read(link("555555555555", "a.example", "Old name") + "\n" + link("555555555556", "b.example"))
        )
        val after = VlessSubscription.parse(
            read(link("555555555555", "a.example", "New name") + "\n" + link("555555555557", "c.example"))
        )

        val diff = VlessSubscription.diff(before, after)
        assertEquals(1, diff.added.size)
        assertEquals("c.example", diff.added[0].host)
        assertEquals(1, diff.removed.size)
        assertEquals("b.example", diff.removed[0].host)
        assertEquals(1, diff.kept.size)
        assertEquals(1, diff.renamed.size)
        assertEquals("Old name", diff.renamed[0].first.remark)
        assertEquals("New name", diff.renamed[0].second.remark)
        assertFalse(diff.isEmpty)
    }

    @Test
    fun unchangedSubscriptionProducesEmptyDiff() {
        val body = link("555555555555", "a.example", "RU-1")
        val diff = VlessSubscription.diff(
            VlessSubscription.parse(read(body)),
            VlessSubscription.parse(read(body)),
        )
        assertTrue(diff.isEmpty)
        assertEquals(1, diff.kept.size)
    }

    @Test
    fun honoursParseLimitOnHugeLists() {
        val body = (1..500).joinToString("\n") { index ->
            link("5555555555%02d".format(index % 100), "h$index.example")
        }
        val configs = VlessSubscription.parse(read(body), limit = 100)
        assertEquals(100, configs.size)
    }

    /**
     * Смешанная подписка: рядом с vless лежат другие протоколы. Структура повторяет
     * реальный список BLACK_VLESS_RUS_mobile.txt — комментарии с метаданными,
     * vless, hysteria2 и vmess вперемешку.
     */
    private val mixedBody = """
        # profile-title: base64:0KfQtdGA0L3Ri9C1
        # profile-update-interval: 12
        # Количество: 5
        ${link("000000000001", "a.example")}
        hysteria2://pass@1.2.3.4:443?sni=a.example#узел-1
        ${link("000000000002", "b.example")}
        vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzfQ==
        hysteria2://pass@5.6.7.8:8443#узел-2
        ${link("000000000003", "c.example")}
        совсем не ссылка
        ss://YWVzLTI1Ni1nY206cGFzcw==@9.9.9.9:8388#шадоу
    """.trimIndent()

    @Test
    fun keepsOnlyVlessFromMixedSubscription() {
        val kept = mutableListOf<VlessConfig>()
        val stats = VlessSubscription.parseStreaming(read(mixedBody)) { kept += it }

        assertEquals(3, stats.kept)
        assertEquals(3, kept.size)
        assertTrue(kept.all { it.host.endsWith(".example") })
    }

    @Test
    fun countsForeignProtocolsSeparatelyFromBrokenLines() {
        val stats = VlessSubscription.parseStreaming(read(mixedBody)) {}

        // hysteria2 дважды, vmess и ss по разу — это пропуски, а не ошибки.
        assertEquals(2, stats.skippedByProtocol["hysteria2"])
        assertEquals(1, stats.skippedByProtocol["vmess"])
        assertEquals(1, stats.skippedByProtocol["ss"])
        assertEquals(4, stats.skippedOtherProtocol)
        // Битой считается только строка без схемы.
        assertEquals(1, stats.invalid)
    }

    @Test
    fun describesSkippedProtocolsForReport() {
        val stats = VlessSubscription.parseStreaming(read(mixedBody)) {}
        assertEquals("hysteria2 — 2, vmess — 1, ss — 1", stats.describeSkippedProtocols())
    }

    private fun identityOf(uri: String) = requireNotNull(VlessConfig.parse(uri)).identity

    @Test
    fun syncKeepsSurvivorsInPlaceAndAppendsNewOnes() {
        // Порядок выживших — это результат перебора: наверху то, что подключалось.
        // Пересортировка по порядку подписки вернула бы мёртвые записи наверх.
        val a = link("000000000001", "a.example")
        val b = link("000000000002", "b.example")
        val c = link("000000000003", "c.example")
        val plan = VlessSubscription.planSync(
            existingLinks = listOf(b, a),
            freshLinks = listOf(a, b, c),
            previousIdentities = listOf(identityOf(a), identityOf(b)),
            limit = 400,
        )
        assertEquals(listOf(b, a, c), plan.links)
        assertEquals(1, plan.added)
        assertEquals(0, plan.removed)
    }

    @Test
    fun syncDropsOnlyWhatDisappearedFromSubscription() {
        val a = link("000000000001", "a.example")
        val b = link("000000000002", "b.example")
        val manual = link("000000000009", "manual.example")
        val plan = VlessSubscription.planSync(
            existingLinks = listOf(a, b, manual),
            freshLinks = listOf(a),
            // В прошлой загрузке подписки были только a и b: manual добавлен руками.
            previousIdentities = listOf(identityOf(a), identityOf(b)),
            limit = 400,
        )
        assertEquals(listOf(a, manual), plan.links)
        assertEquals(1, plan.removed)
        assertEquals(0, plan.added)
    }

    @Test
    fun firstImportNeverRemovesAnything() {
        // Состава прошлой загрузки нет, и уже сохранённый профиль не должен выглядеть
        // как пропавший из подписки.
        val saved = link("000000000009", "manual.example")
        val fresh = link("000000000001", "a.example")
        val plan = VlessSubscription.planSync(
            existingLinks = listOf(saved),
            freshLinks = listOf(fresh),
            previousIdentities = emptyList(),
            limit = 400,
        )
        assertEquals(listOf(saved, fresh), plan.links)
        assertEquals(0, plan.removed)
        assertEquals(1, plan.added)
    }

    @Test
    fun syncRespectsProfileLimit() {
        val existing = (1..3).map { link("00000000000$it", "$it.example") }
        val fresh = existing + (4..6).map { link("00000000000$it", "$it.example") }
        val plan = VlessSubscription.planSync(
            existingLinks = existing,
            freshLinks = fresh,
            previousIdentities = existing.map(::identityOf),
            limit = 4,
        )
        assertEquals(4, plan.links.size)
        assertEquals(1, plan.added)
    }

    @Test
    fun syncWithoutChangesReportsNothingToWrite() {
        val a = link("000000000001", "a.example")
        val plan = VlessSubscription.planSync(
            existingLinks = listOf(a),
            freshLinks = listOf(a),
            previousIdentities = listOf(identityOf(a)),
            limit = 400,
        )
        assertFalse(plan.changed)
        assertEquals(listOf(a), plan.links)
    }

    @Test
    fun renamedNodeSurvivesSyncWithoutBeingRecreated() {
        // Провайдеры перенумеровывают узлы в имени при каждой публикации. По имени это
        // выглядело бы как удаление и добавление, и вся статистика профиля обнулялась бы.
        val before = link("000000000001", "a.example", remark = "узел 1")
        val after = link("000000000001", "a.example", remark = "узел 47")
        val plan = VlessSubscription.planSync(
            existingLinks = listOf(before),
            freshLinks = listOf(after),
            previousIdentities = listOf(identityOf(before)),
            limit = 400,
        )
        assertFalse(plan.changed)
        assertEquals(listOf(before), plan.links)
    }

    @Test
    fun unknownFutureProtocolIsSkippedNotCountedAsError() {
        // Появится новый протокол — он попадёт в «пропущено», а не в «ошибки».
        val body = """
            ${link("000000000001", "a.example")}
            quicmagic://token@1.2.3.4:443#будущее
        """.trimIndent()
        val stats = VlessSubscription.parseStreaming(read(body)) {}
        assertEquals(1, stats.kept)
        assertEquals(0, stats.invalid)
        assertEquals(1, stats.skippedByProtocol["quicmagic"])
    }
}
