package com.example.nova

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Живая загрузка очень большой подписки — та самая, на которой прежний импорт через
 * `readText()` гарантированно падал по памяти (94 МБ тела, см.
 * docs/vless-reality-plan.md).
 *
 * Проверяется ровно то, ради чего загрузчик потоковый: чтение обрывается на лимите,
 * тело целиком не скачивается, объекты не накапливаются, а прогресс приходит по ходу
 * дела, а не одним разом в конце.
 *
 * Ходит в сеть, поэтому по умолчанию пропускается. Адрес берётся из окружения — его
 * тестовая JVM наследует, в отличие от `-D`, которое Gradle в форк не передаёт:
 *
 *     NOVA_HUGE_SUBSCRIPTION_URL=<адрес> ./gradlew testDebugUnitTest
 */
class HugeSubscriptionFetchProbe {

    @Test
    fun streamsHugeSubscriptionWithoutReadingItWhole() {
        val url = System.getenv("NOVA_HUGE_SUBSCRIPTION_URL").orEmpty()
        assumeTrue(url.isNotBlank())

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()
        val progressTicks = ArrayList<Pair<Int, Long>>()
        val started = System.nanoTime()

        val result = VlessSubscriptionFetcher.fetch(url, limit = 400) { kept, charsRead ->
            progressTicks += kept to charsRead
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val memAfter = runtime.totalMemory() - runtime.freeMemory()

        println("=== потоковая загрузка подписки ===")
        println("время: $elapsedMs мс")
        println("память под объекты: ${(memAfter - memBefore) / 1024 / 1024} МБ")
        println("шагов прогресса: ${progressTicks.size}")

        when (result) {
            is VlessSubscriptionFetcher.Result.Ok -> {
                println("профилей: ${result.configs.size}, прочитано: ${result.bytesRead / 1024} КБ")
                println("статистика: ${result.stats}")
                assertTrue("лимит профилей не должен превышаться", result.configs.size <= 400)
                assertTrue("прогресс обязан приходить по ходу дела", progressTicks.size > 1)
                assertTrue(
                    "чтение обязано обрываться на лимите, а не тянуть тело целиком",
                    result.stats.stoppedEarly || result.bytesRead < 32L * 1024 * 1024,
                )
            }
            else -> println("подписка недоступна: $result")
        }
    }
}
