package com.example.nova

import com.example.nova.ProfileIssuePolicy.Entry
import com.example.nova.ProfileIssuePolicy.Environment
import com.example.nova.ProfileIssuePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D45: личный набор выпускается один раз на версию приложения; фоновый выпуск идёт только
 * в свободное окно. Ожидаемые значения здесь — из решения владельца, а не из реализации
 * (I28): паузы 1/3/12/24 ч, потолок 4 попытки, порог занятости 40 КБ/с.
 */
class ProfileIssuePolicyTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    // -- правило версии ---------------------------------------------------------

    @Test
    fun `no set means issue`() {
        val d = ProfileIssuePolicy.decide(hasSet = false, entry = null, currentVersion = 167, nowMs = now)
        assertEquals(Verdict.GO, d.verdict)
        assertFalse(d.upgrade)
    }

    @Test
    fun `a set issued by the current version is never touched automatically`() {
        val entry = Entry(issuedVersion = 167, attemptVersion = 167, attempts = 0)
        val d = ProfileIssuePolicy.decide(true, entry, 167, now)
        assertEquals(Verdict.NOT_NEEDED, d.verdict)
        // ни при подключении, ни при переподключении, ни при смене сети — решение то же
        repeat(3) { assertEquals(Verdict.NOT_NEEDED, ProfileIssuePolicy.decide(true, entry, 167, now + it * hour).verdict) }
    }

    @Test
    fun `a set from a newer build is left alone after a downgrade`() {
        assertEquals(
            Verdict.NOT_NEEDED,
            ProfileIssuePolicy.decide(true, Entry(issuedVersion = 170), 167, now).verdict,
        )
    }

    @Test
    fun `a set from an older version is reissued once after the update`() {
        val d = ProfileIssuePolicy.decide(true, Entry(issuedVersion = 166), 167, now)
        assertEquals(Verdict.GO, d.verdict)
        assertTrue(d.upgrade)
    }

    @Test
    fun `a set without a stamp (built before D45) counts as older`() {
        val d = ProfileIssuePolicy.decide(true, null, 167, now)
        assertEquals(Verdict.GO, d.verdict)
        assertTrue(d.upgrade)
    }

    @Test
    fun `after the reissue succeeds the same version does not issue again`() {
        val afterSuccess = Entry(issuedVersion = 167, attemptVersion = 167, attempts = 0, lastAttemptAt = now)
        assertEquals(Verdict.NOT_NEEDED, ProfileIssuePolicy.decide(true, afterSuccess, 167, now + 1).verdict)
        // следующая версия — снова один раз
        assertEquals(Verdict.GO, ProfileIssuePolicy.decide(true, afterSuccess, 168, now + 1).verdict)
    }

    // -- паузы и потолок ----------------------------------------------------------

    @Test
    fun `backoff grows 1 3 12 24 hours and stays at a day`() {
        assertEquals(0L, ProfileIssuePolicy.backoffMs(0))
        assertEquals(1 * hour, ProfileIssuePolicy.backoffMs(1))
        assertEquals(3 * hour, ProfileIssuePolicy.backoffMs(2))
        assertEquals(12 * hour, ProfileIssuePolicy.backoffMs(3))
        assertEquals(24 * hour, ProfileIssuePolicy.backoffMs(4))
        assertEquals(24 * hour, ProfileIssuePolicy.backoffMs(9))
    }

    @Test
    fun `a failed attempt puts the next one on cooldown`() {
        val failed = Entry(issuedVersion = 166, attemptVersion = 167, attempts = 1, lastAttemptAt = now)
        val soon = ProfileIssuePolicy.decide(true, failed, 167, now + 30 * 60_000L)
        assertEquals(Verdict.COOLDOWN, soon.verdict)
        assertEquals(30 * 60_000L, soon.retryInMs)
        assertEquals(Verdict.GO, ProfileIssuePolicy.decide(true, failed, 167, now + hour).verdict)
    }

    @Test
    fun `attempts of an older version do not delay the new one`() {
        val old = Entry(issuedVersion = 165, attemptVersion = 166, attempts = 3, lastAttemptAt = now)
        assertEquals(Verdict.GO, ProfileIssuePolicy.decide(true, old, 167, now + 1).verdict)
    }

    @Test
    fun `upgrading a working set gives up after four automatic attempts`() {
        val spent = Entry(issuedVersion = 166, attemptVersion = 167, attempts = 4, lastAttemptAt = now - 100 * hour)
        assertEquals(Verdict.EXHAUSTED, ProfileIssuePolicy.decide(true, spent, 167, now).verdict)
    }

    @Test
    fun `with no set at all there is no cap but never faster than a day`() {
        val e = Entry(attemptVersion = 167, attempts = 9, lastAttemptAt = now)
        assertEquals(Verdict.COOLDOWN, ProfileIssuePolicy.decide(false, e, 167, now + 23 * hour).verdict)
        assertEquals(Verdict.GO, ProfileIssuePolicy.decide(false, e, 167, now + 24 * hour).verdict)
    }

    // -- ворота -------------------------------------------------------------------

    private fun gate(env: Environment, firstSet: Boolean = false) = ProfileIssuePolicy.evaluateGate(env, firstSet)

    @Test
    fun `a quiet unmetered link opens the gate`() {
        assertTrue(gate(Environment(bytesPerSecond = 1_000L)).allowed)
    }

    @Test
    fun `active traffic closes the gate`() {
        val busy = Environment(bytesPerSecond = ProfileIssuePolicy.BUSY_BYTES_PER_SECOND)
        assertFalse(gate(busy).allowed)
        assertTrue(gate(Environment(bytesPerSecond = ProfileIssuePolicy.BUSY_BYTES_PER_SECOND - 1)).allowed)
    }

    @Test
    fun `an unknown traffic reading does not block`() {
        assertTrue(gate(Environment(bytesPerSecond = -1L)).allowed)
    }

    @Test
    fun `roaming data saver and power saver close the gate`() {
        assertFalse(gate(Environment(cellular = true, roaming = true), firstSet = true).allowed)
        assertFalse(gate(Environment(dataSaver = true), firstSet = true).allowed)
        assertFalse(gate(Environment(powerSave = true), firstSet = true).allowed)
    }

    @Test
    fun `a low battery closes the gate unless charging`() {
        assertFalse(gate(Environment(batteryPercent = 19)).allowed)
        assertTrue(gate(Environment(batteryPercent = 19, charging = true)).allowed)
        assertTrue(gate(Environment(batteryPercent = 20)).allowed)
        assertTrue(gate(Environment(batteryPercent = -1)).allowed)
    }

    @Test
    fun `a weak link closes the gate`() {
        assertFalse(gate(Environment(downKbps = 800)).allowed)
        assertFalse(gate(Environment(upKbps = 100)).allowed)
        // 0 — система не знает; это не «слабо»
        assertTrue(gate(Environment(downKbps = 0, upKbps = 0)).allowed)
    }

    @Test
    fun `renewing a working set waits for an unmetered link but the first set may use cellular`() {
        val cell = Environment(cellular = true, metered = true)
        assertFalse(gate(cell, firstSet = false).allowed)
        assertTrue(gate(cell, firstSet = true).allowed)
        assertFalse(gate(Environment(metered = true), firstSet = false).allowed)
    }

    @Test
    fun `no network closes the gate`() {
        assertFalse(gate(Environment(hasNetwork = false), firstSet = true).allowed)
    }
}
