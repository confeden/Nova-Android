package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фоновый подбор `I1` обязан быть незаметным: шагать редко, только на покое и
 * только по одному профилю. Ошибка в любую сторону дорога — слишком часто значит
 * будить устройство зря, слишком редко значит никогда не закончить.
 *
 * Отдельно закреплена подстановка: это то место, где подбор либо доезжает до
 * рукопожатия, либо тихо теряется.
 */
class AwgI1AdaptationTest {

    private val pool = listOf("www.gosuslugi.ru", "zakupki.gov.ru", "vk.com")

    private fun inputs(
        enabled: Boolean = true,
        connected: Boolean = true,
        screenOffMs: Long = AwgI1Adaptation.IDLE_BEFORE_STEP_MS,
        sinceLastStepMs: Long = AwgI1Adaptation.STEP_COOLDOWN_MS,
        profiles: List<AwgI1Adaptation.Profile> = listOf(
            AwgI1Adaptation.Profile(id = "a", score = 5, quicI1 = true),
            AwgI1Adaptation.Profile(id = "b", score = 1, quicI1 = true),
        ),
        sniPool: List<String> = pool,
    ) = AwgI1Adaptation.Inputs(
        enabled = enabled,
        connected = connected,
        screenOffMs = screenOffMs,
        sinceLastStepMs = sinceLastStepMs,
        profiles = profiles,
        sniPool = sniPool,
    )

    @Test
    fun `выключенный подбор молчит`() {
        assertNull(AwgI1Adaptation.decide(inputs(enabled = false)))
    }

    @Test
    fun `без туннеля подбирать нечего`() {
        assertNull(AwgI1Adaptation.decide(inputs(connected = false)))
    }

    @Test
    fun `пока пользователь у экрана, шага не будет`() {
        val tooSoon = AwgI1Adaptation.IDLE_BEFORE_STEP_MS - 1
        assertNull(AwgI1Adaptation.decide(inputs(screenOffMs = tooSoon)))
    }

    @Test
    fun `между шагами выдерживается пауза`() {
        val tooSoon = AwgI1Adaptation.STEP_COOLDOWN_MS - 1
        assertNull(AwgI1Adaptation.decide(inputs(sinceLastStepMs = tooSoon)))
    }

    @Test
    fun `берётся самый слабый профиль`() {
        val step = AwgI1Adaptation.decide(inputs())
        assertEquals("b", step?.profileId)
    }

    @Test
    fun `среди равных берётся тот, кому пробовали меньше имён`() {
        val step = AwgI1Adaptation.decide(
            inputs(
                profiles = listOf(
                    AwgI1Adaptation.Profile(id = "a", score = 1, attempts = 3, quicI1 = true),
                    AwgI1Adaptation.Profile(id = "b", score = 1, attempts = 0, quicI1 = true),
                )
            )
        )
        // Иначе один неудачник забирал бы себе все шаги подряд.
        assertEquals("b", step?.profileId)
    }

    @Test
    fun `имена берутся сверху списка`() {
        val step = AwgI1Adaptation.decide(inputs())
        assertEquals("www.gosuslugi.ru", step?.sni)
    }

    @Test
    fun `уже выданное имя повторно не назначается`() {
        val step = AwgI1Adaptation.decide(
            inputs(
                profiles = listOf(
                    AwgI1Adaptation.Profile(id = "b", score = 1, adaptedSni = "www.gosuslugi.ru", attempts = 1, quicI1 = true),
                )
            )
        )
        assertTrue("повтор того же имени — потраченный впустую шаг", step?.sni != "www.gosuslugi.ru")
    }

    @Test
    fun `пустой список имён останавливает подбор`() {
        assertNull(AwgI1Adaptation.decide(inputs(sniPool = emptyList())))
    }

    @Test
    fun `подстановка заменяет собственное I1 профиля`() {
        val extras = listOf("Jc = 4", "I1 = <b 0xdead>", "H1 = 1")
        val out = AwgI1Adaptation.applyI1(extras, "<b 0xbeef>")
        assertEquals(listOf("Jc = 4", "I1 = <b 0xbeef>", "H1 = 1"), out)
    }

    @Test
    fun `подстановка добавляет I1 профилю без него`() {
        val out = AwgI1Adaptation.applyI1(listOf("Jc = 4"), "<b 0xbeef>")
        assertEquals(listOf("Jc = 4", "I1 = <b 0xbeef>"), out)
    }

    @Test
    fun `пустая подмена оставляет набор нетронутым`() {
        val extras = listOf("Jc = 4", "I1 = <b 0xdead>")
        assertEquals(extras, AwgI1Adaptation.applyI1(extras, "   "))
    }

    @Test
    fun `SIP-семя подбором не трогается`() {
        // Из пятидесяти встроенных семян 17 маскируются не под QUIC, а под
        // SIP-звонок: `I1` — «INVITE …», к нему `I2` — ответ «SIP/2.0 …».
        // Подобрать им можно только QUIC-пакет, и профиль остался бы с чужим
        // инициалом впереди и ответом SIP следом.
        val step = AwgI1Adaptation.decide(
            inputs(
                profiles = listOf(
                    AwgI1Adaptation.Profile(id = "sip", score = 0, quicI1 = false),
                    AwgI1Adaptation.Profile(id = "quic", score = 9, quicI1 = true),
                )
            )
        )
        assertEquals("quic", step?.profileId)
    }

    @Test
    fun `без единого QUIC-семени шага нет`() {
        val step = AwgI1Adaptation.decide(
            inputs(profiles = listOf(AwgI1Adaptation.Profile(id = "sip", score = 0, quicI1 = false)))
        )
        assertNull(step)
    }

    @Test
    fun `QUIC Initial отличается от SIP по заголовку`() {
        // Настоящие значения из `warp_verified_seeds.json`: 33 семени начинаются
        // с `0xce`/`0xc7` и версии `00000001`, 17 — с ASCII «INVITE ».
        assertTrue(AwgI1Adaptation.isQuicInitial("<b 0xce00000001085d59385b700253fd>"))
        assertTrue(AwgI1Adaptation.isQuicInitial("I1 = <b 0xc700000001085d59385b700253fd>"))
        assertFalse(AwgI1Adaptation.isQuicInitial("<b 0x494e5649544520737near>"))
        // Длинный заголовок есть, а версия чужая — не наш случай.
        assertFalse(AwgI1Adaptation.isQuicInitial("<b 0xce6b3343cf>"))
        assertFalse(AwgI1Adaptation.isQuicInitial(""))
        assertFalse(AwgI1Adaptation.isQuicInitial("<b 0xce00>"))
    }

    @Test
    fun `сроки проверяются отдельно от списка профилей`() {
        // Служба спрашивает это до сборки списка, поэтому пороги обязаны совпадать
        // с теми, по которым решает decide.
        assertTrue(
            AwgI1Adaptation.isStepDue(
                enabled = true,
                connected = true,
                screenOffMs = AwgI1Adaptation.IDLE_BEFORE_STEP_MS,
                sinceLastStepMs = AwgI1Adaptation.STEP_COOLDOWN_MS,
            )
        )
        assertFalse(
            AwgI1Adaptation.isStepDue(
                enabled = false,
                connected = true,
                screenOffMs = AwgI1Adaptation.IDLE_BEFORE_STEP_MS,
                sinceLastStepMs = AwgI1Adaptation.STEP_COOLDOWN_MS,
            )
        )
        assertFalse(
            AwgI1Adaptation.isStepDue(
                enabled = true,
                connected = false,
                screenOffMs = AwgI1Adaptation.IDLE_BEFORE_STEP_MS,
                sinceLastStepMs = AwgI1Adaptation.STEP_COOLDOWN_MS,
            )
        )
        assertFalse(
            AwgI1Adaptation.isStepDue(
                enabled = true,
                connected = true,
                screenOffMs = AwgI1Adaptation.IDLE_BEFORE_STEP_MS - 1,
                sinceLastStepMs = AwgI1Adaptation.STEP_COOLDOWN_MS,
            )
        )
        assertFalse(
            AwgI1Adaptation.isStepDue(
                enabled = true,
                connected = true,
                screenOffMs = AwgI1Adaptation.IDLE_BEFORE_STEP_MS,
                sinceLastStepMs = AwgI1Adaptation.STEP_COOLDOWN_MS - 1,
            )
        )
    }

    @Test
    fun `рабочий профиль ручная адаптация не трогает`() {
        // Терять нечего только там, где сейчас плохо: у рабочего профиля `I1` уже
        // подходит этой сети, и замена может только испортить.
        assertFalse(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 2, quicI1 = true))
        assertTrue(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 1, quicI1 = true))
        assertTrue(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 0, quicI1 = true))
    }

    @Test
    fun `SIP-семя не получает кандидата ни при каком качестве`() {
        assertFalse(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 0, quicI1 = false))
        assertFalse(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 1, quicI1 = false))
        assertFalse(AwgI1Adaptation.deservesManualI1Candidate(qualityTier = 2, quicI1 = false))
    }

    @Test
    fun `следующее имя не повторяет уже выданное`() {
        assertEquals("www.gosuslugi.ru", AwgI1Adaptation.nextSniForProfile(pool, "", 0))
        // Индекс упёрся в уже выданное имя — берём первое отличное.
        assertTrue(AwgI1Adaptation.nextSniForProfile(pool, "www.gosuslugi.ru", 0) != "www.gosuslugi.ru")
        assertEquals("zakupki.gov.ru", AwgI1Adaptation.nextSniForProfile(pool, "", 1))
        // Список кончился — идём по кругу, а не выходим за край.
        assertEquals("www.gosuslugi.ru", AwgI1Adaptation.nextSniForProfile(pool, "", pool.size))
        assertNull(AwgI1Adaptation.nextSniForProfile(emptyList(), "", 0))
        // Единственное имя, и оно уже выдано: предлагать нечего.
        assertNull(AwgI1Adaptation.nextSniForProfile(listOf("vk.com"), "vk.com", 0))
    }

    @Test
    fun `совпадение сдвигает выбор вперёд, а не в начало списка`() {
        // Раньше при совпадении выбор возвращался к первому отличному имени, то есть
        // к самому верху списка — а его этому профилю пробовали первым делом, и заход
        // всё равно уходил впустую. Сдвиг на одно вперёд продолжает перебор.
        val wide = listOf("a.ru", "b.ru", "c.ru", "d.ru")
        assertEquals("d.ru", AwgI1Adaptation.nextSniForProfile(wide, "c.ru", 2))
    }

    @Test
    fun `отрицательный счётчик не выводит выбор за границы списка`() {
        // Остаток от деления в Kotlin сохраняет знак: без нормализации индекс ушёл бы
        // в минус. Это защита от испорченного файла подбора, а не от нормального хода.
        assertTrue(AwgI1Adaptation.nextSniForProfile(pool, "", -3) in pool)
    }

    // Начала настоящих `I1` из `warp_verified_seeds.json`: у SIP-семени — ASCII
    // «INVITE sip:bob@biloxi.com SIP/2.» (пример из RFC 3261), у QUIC-семени — 0xce и
    // версия 00000001.
    private val sipSeedI1 = "<b 0x494e56495445207369703a626f624062696c6f78692e636f6d205349502f322e>"
    private val quicSeedI1 = "<b 0xce000000010897a297ecc34cd6dd000044d0ec2e>"
    private val quicC7SeedI1 = "<b 0xc7000000010809a1ed4edbbe7615000044d017a6>"

    @Test
    fun `на сотовой сети QUIC-прикрытие идёт раньше SIP и профиля без I1`() {
        // P62: на МегаФоне SIP-звонок не прошёл ни разу из 72, QUIC — 53 из 60.
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(true, "I1 = $quicSeedI1", "188.114.97.3"))
        assertEquals(1, AwgI1Adaptation.cellularMaskRank(true, "I1 = $sipSeedI1", "8.34.146.3"))
        // Без `I1` на МегаФоне тоже 0 из 24 — это не «нейтрально», а хвост.
        assertEquals(1, AwgI1Adaptation.cellularMaskRank(true, null, "8.47.69.6"))
    }

    @Test
    fun `на сотовой сети точка входа по имени уходит в хвост даже с QUIC`() {
        // Такие семена — `engage.cloudflareclient.com` на портах 2408 и 7103: имя на
        // МегаФоне однажды не разрешилось, и первая попытка съела 83 с.
        assertEquals(1, AwgI1Adaptation.cellularMaskRank(true, quicSeedI1, "engage.cloudflareclient.com"))
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(true, quicSeedI1, "[2606:4700:d0::a29f:c001]"))
    }

    @Test
    fun `второе семейство QUIC-семян тоже идёт вперёд, а случайный I1 — нет`() {
        // 17 из 33 QUIC-семян начинаются с 0xc7, а не с 0xce.
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(true, "I1 = $quicC7SeedI1", "8.39.125.9"))
        // Случайные байты вместо пакета — не QUIC, как бы длинны они ни были.
        assertEquals(1, AwgI1Adaptation.cellularMaskRank(true, "I1 = <r 1250>", "8.39.125.9"))
    }

    @Test
    fun `вне сотовой сети прикрытие порядок не меняет`() {
        // На домашнем Ростелекоме проходят оба прикрытия: очередь там решают замеры.
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(false, sipSeedI1, "8.34.146.3"))
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(false, quicSeedI1, "engage.cloudflareclient.com"))
        assertEquals(0, AwgI1Adaptation.cellularMaskRank(false, null, "8.47.69.6"))
    }

    @Test
    fun `адрес отличается от имени`() {
        assertTrue(AwgI1Adaptation.isIpLiteralHost("162.159.192.1"))
        assertTrue(AwgI1Adaptation.isIpLiteralHost("2606:4700:d0::a29f:c001"))
        assertFalse(AwgI1Adaptation.isIpLiteralHost("engage.cloudflareclient.com"))
        assertFalse(AwgI1Adaptation.isIpLiteralHost("162.159.192"))
        assertFalse(AwgI1Adaptation.isIpLiteralHost("162.159.192.256"))
    }

    @Test
    fun `общее прикрытие берётся QUIC, даже если первым идёт SIP-семя`() {
        // Первые пять семян — SIP; раньше запасным `I1` становилось первое по списку.
        assertEquals(quicSeedI1, AwgI1Adaptation.preferredSharedMask(listOf(sipSeedI1, " $quicSeedI1 ")))
        // Без единого QUIC — лучше SIP, чем ничего: без `I1` не прошло ни на одной из
        // двух измеренных сетей.
        assertEquals(sipSeedI1, AwgI1Adaptation.preferredSharedMask(listOf("", sipSeedI1)))
        assertEquals("", AwgI1Adaptation.preferredSharedMask(listOf("", "  ")))
    }
}
