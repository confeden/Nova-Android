package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Замер разбора очень большой подписки. Файл в репозитории не хранится —
 * тест пропускается, если его нет.
 */
class HugeSubscriptionBenchmark {

    @Test
    fun parseHugeList() {
        val file = File("../tools/probe/goida_2.txt")
        assumeTrue(file.exists())

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()

        val configs = VlessSubscription.parse(file.bufferedReader(), limit = Int.MAX_VALUE)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val memAfter = runtime.totalMemory() - runtime.freeMemory()

        println("=== огромная подписка ===")
        println("размер файла: ${file.length() / 1024 / 1024} МБ")
        println("разобрано: ${configs.size}")
        println("время разбора: $elapsedMs мс")
        println("память под объекты: ${(memAfter - memBefore) / 1024 / 1024} МБ")

        val identityStarted = System.nanoTime()
        val unique = HashSet<String>(configs.size * 2)
        configs.forEach { unique.add(it.identity) }
        println("уникальных: ${unique.size}, время identity: ${(System.nanoTime() - identityStarted) / 1_000_000} мс")

        val diffStarted = System.nanoTime()
        VlessSubscription.diff(configs, configs)
        println("время diff (полный список сам с собой): ${(System.nanoTime() - diffStarted) / 1_000_000} мс")
    }

    @Test
    fun streamingImportStaysBounded() {
        val file = File("../tools/probe/goida_2.txt")
        assumeTrue(file.exists())

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()

        val kept = ArrayList<VlessConfig>(10_000)
        val stats = VlessSubscription.parseStreaming(
            file.bufferedReader(),
            limit = 10_000,
            stopWhenFull = true,
        ) { kept.add(it) }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        runtime.gc()
        val memAfter = runtime.totalMemory() - runtime.freeMemory()

        println("=== потоковый импорт, лимит 10000, ранняя остановка ===")
        println("строк прочитано: ${stats.linesSeen}, разобрано: ${stats.parsed}, битых: ${stats.invalid}")
        println("дубликатов: ${stats.duplicates}, взято: ${stats.kept}, остановлено досрочно: ${stats.stoppedEarly}")
        println("время: $elapsedMs мс, удержанная память: ${(memAfter - memBefore) / 1024 / 1024} МБ")
    }

    @Test
    fun reportUnparsedLines() {
        val file = File("../tools/probe/goida_2.txt")
        assumeTrue(file.exists())

        var total = 0
        val failures = ArrayList<String>()
        file.bufferedReader().forEachLine { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("vless://", ignoreCase = true)) return@forEachLine
            total++
            if (VlessConfig.parse(trimmed) == null && failures.size < 2000) {
                failures.add(trimmed)
            }
        }
        println("=== непрочитанные строки ===")
        println("всего vless://: $total, не разобрано (первые 2000): ${failures.size}")
        failures.take(6).forEach { line ->
            println("   " + line.take(150).replace(Regex("[0-9a-fA-F-]{20,}"), "<id>"))
        }
    }
}
