package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Тесты паузы после «ключ погашен» ([OutdatedVerdict]) и разбора ответа релея на
 * `CONNECT` ([RelayConnectHead]).
 *
 * Что здесь закрепляется и почему (P53). Погашенный клиент стучался в релей около
 * 40 раз в час с одного адреса. Свойства, каждое из которых легко потерять правкой:
 * отказы одного залпа паузу не удлиняют (иначе одна попытка подключения загоняет
 * её под потолок), пауза растёт только после своего конца и упирается в потолок,
 * вердикт привязан к ключу (иначе он переживает обновление и запрещает релей
 * новой версии), а часы, переведённые назад, не растягивают паузу навсегда.
 */
class NovaRelayVerdictTest {

    private val key = "nova-android-158"
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun firstRefusalPausesForHalfAnHour() {
        val verdict = OutdatedVerdict.next(null, key, now = 1_000L)
        assertEquals(1, verdict.strikes)
        assertEquals(1_000L + 30 * minute, verdict.pausedUntil)
        assertEquals(verdict.pausedUntil, verdict.pauseEndFor(key, now = 1_000L))
    }

    @Test
    fun refusalsOfOneBurstDoNotExtendThePause() {
        val first = OutdatedVerdict.next(null, key, now = 0L)
        val second = OutdatedVerdict.next(first, key, now = 5_000L)
        assertSame(first, second)
    }

    @Test
    fun refusalAfterThePauseDoublesIt() {
        val first = OutdatedVerdict.next(null, key, now = 0L)
        assertEquals(0L, first.pauseEndFor(key, now = first.pausedUntil))
        val second = OutdatedVerdict.next(first, key, now = first.pausedUntil)
        assertEquals(2, second.strikes)
        assertEquals(first.pausedUntil + hour, second.pausedUntil)
    }

    @Test
    fun pauseGrowsToTwelveHoursAndStaysThere() {
        val pauses = mutableListOf<Long>()
        var verdict = OutdatedVerdict.next(null, key, now = 0L)
        pauses += verdict.pausedUntil
        repeat(7) {
            val start = verdict.pausedUntil
            verdict = OutdatedVerdict.next(verdict, key, now = start)
            pauses += verdict.pausedUntil - start
        }
        assertEquals(
            listOf(30 * minute, hour, 2 * hour, 4 * hour, 8 * hour, 12 * hour, 12 * hour, 12 * hour),
            pauses,
        )
        assertEquals(8, verdict.strikes)
    }

    @Test
    fun verdictOfAnotherKeyDoesNotPauseThisBuild() {
        val old = OutdatedVerdict.next(null, "nova-android-157", now = 0L)
        assertEquals(0L, old.pauseEndFor(key, now = 1_000L))
        val fresh = OutdatedVerdict.next(old, key, now = 1_000L)
        assertEquals(1, fresh.strikes)
        assertEquals(key, fresh.keyId)
    }

    @Test
    fun clockMovedBackDoesNotStretchThePause() {
        val verdict = OutdatedVerdict(key, strikes = 3, pausedUntil = 100 * hour)
        assertEquals(0L, verdict.pauseEndFor(key, now = 0L))
        val next = OutdatedVerdict.next(verdict, key, now = 0L)
        assertEquals(4, next.strikes)
        assertEquals(4 * hour, next.pausedUntil)
    }

    @Test
    fun stateWithoutPauseFromAnOlderBuildAllowsOneCheck() {
        // Файл, записанный до паузы, несёт только `outdated` и `key_id`.
        val legacy = OutdatedVerdict(key, strikes = 0, pausedUntil = 0L)
        assertEquals(0L, legacy.pauseEndFor(key, now = 5 * hour))
        assertEquals(1, OutdatedVerdict.next(legacy, key, now = 5 * hour).strikes)
    }

    /** Байты сняты с живого релея 2026-09-13: `CONNECT` с погашенным ключом 155 и с текущим 158. */
    @Test
    fun parsesLiveRelayAnswers() {
        val refused = RelayConnectHead.parse(
            (
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"nova\"\r\n" +
                    "X-Nova-Relay-Reason: outdated-client\r\n" +
                    "X-Nova-Relay-Platform: android\r\n" +
                    "X-Nova-Relay-Current: 158\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray()
        )
        assertEquals(RelayConnectHead(407, "outdated-client", "158"), refused)
        val accepted = RelayConnectHead.parse("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        assertEquals(RelayConnectHead(200, "", ""), accepted)
    }

    @Test
    fun parsesOutdatedRefusalWithAnyHeaderCase() {
        val head = RelayConnectHead.parse(
            (
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "x-nova-relay-reason: outdated-client\r\n" +
                    "X-Nova-Relay-Platform: android\r\n" +
                    "X-Nova-Relay-Current:  158 \r\n\r\n"
                ).toByteArray()
        )
        assertNotNull(head)
        assertEquals(407, head!!.code)
        assertEquals("outdated-client", head.reason)
        assertEquals("158", head.current)
    }

    @Test
    fun parsesSuccessfulConnectFollowedByTls() {
        val bytes = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray() + byteArrayOf(0x16, 0x03, 0x01)
        val head = RelayConnectHead.parse(bytes)
        assertEquals(200, head?.code)
        assertEquals("", head?.reason)
    }

    @Test
    fun incompleteOrForeignHeadIsNotAVerdict() {
        assertNull(RelayConnectHead.parse("HTTP/1.1 407 Proxy Authentication Required\r\n".toByteArray()))
        assertNull(RelayConnectHead.parse(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x2a, 0x0d, 0x0a, 0x0d, 0x0a)))
        assertNull(RelayConnectHead.parse("SSH-2.0-OpenSSH\r\n\r\n".toByteArray()))
    }
}
