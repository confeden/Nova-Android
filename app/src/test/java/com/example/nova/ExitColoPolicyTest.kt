package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Правило обхода узла Cloudflare.
 *
 * Тест существует потому, что сама постановка была неверна: просили «перебирать,
 * пока страна выхода не станет зарубежной», а страну Cloudflare выбирает по
 * адресу клиента, и из России она останется российской при любой точке входа.
 * Здесь пинуется то, что построено вместо этого, — обход по **узлу**, с потолком
 * переключений, чтобы настройка не превратилась в вечное переподключение.
 */
class ExitColoPolicyTest {

    @Test
    fun `disabled policy never switches`() {
        assertEquals(
            ExitColoPolicy.Verdict.KEEP,
            ExitColoPolicy.evaluate(enabled = false, observedColo = "DME"),
        )
    }

    @Test
    fun `an avoided node switches`() {
        assertEquals(
            ExitColoPolicy.Verdict.SWITCH,
            ExitColoPolicy.evaluate(enabled = true, observedColo = "DME"),
        )
    }

    @Test
    fun `case and spacing do not matter`() {
        for (value in listOf("dme", " DME ", "Dme")) {
            assertEquals(
                value,
                ExitColoPolicy.Verdict.SWITCH,
                ExitColoPolicy.evaluate(enabled = true, observedColo = value),
            )
        }
    }

    @Test
    fun `a wanted node is kept`() {
        for (value in listOf("FRA", "ARN", "AMS", "LED")) {
            assertEquals(
                value,
                ExitColoPolicy.Verdict.KEEP,
                ExitColoPolicy.evaluate(enabled = true, observedColo = value),
            )
        }
    }

    /**
     * Неизвестный узел — не улика. На сети, где `/cdn-cgi/trace` не отвечает,
     * переключение по пустому значению стало бы бесконечным.
     */
    @Test
    fun `an unknown node is not a reason to switch`() {
        for (value in listOf(null, "", "   ")) {
            assertEquals(
                ExitColoPolicy.Verdict.KEEP,
                ExitColoPolicy.evaluate(enabled = true, observedColo = value),
            )
        }
    }

    /**
     * Потолок обязателен: если нежелательный узел — единственный достижимый, без
     * него настройка превращается в «интернет не работает».
     */
    @Test
    fun `the switch budget stops an endless loop`() {
        val budget = ExitColoPolicy.DEFAULT_SWITCH_BUDGET
        for (n in 0 until budget) {
            assertEquals(
                "switch $n",
                ExitColoPolicy.Verdict.SWITCH,
                ExitColoPolicy.evaluate(enabled = true, observedColo = "DME", switchesSoFar = n),
            )
        }
        assertEquals(
            ExitColoPolicy.Verdict.KEEP,
            ExitColoPolicy.evaluate(enabled = true, observedColo = "DME", switchesSoFar = budget),
        )
        assertEquals(
            ExitColoPolicy.Verdict.KEEP,
            ExitColoPolicy.evaluate(enabled = true, observedColo = "DME", switchesSoFar = budget + 3),
        )
    }

    @Test
    fun `the avoided set is configurable`() {
        assertEquals(
            ExitColoPolicy.Verdict.SWITCH,
            ExitColoPolicy.evaluate(enabled = true, observedColo = "LED", avoided = setOf("led")),
        )
        assertEquals(
            ExitColoPolicy.Verdict.KEEP,
            ExitColoPolicy.evaluate(enabled = true, observedColo = "DME", avoided = setOf("LED")),
        )
    }
}
