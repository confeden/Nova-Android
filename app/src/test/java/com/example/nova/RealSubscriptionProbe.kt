package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Разовая проверка парсера на реальной подписке.
 * Файл не хранится в репозитории (в нём настоящие UUID) — тест пропускается,
 * если его нет.
 */
class RealSubscriptionProbe {

    @Test
    fun parseRealList() {
        val file = File("../tools/probe/vless_universal.txt")
        assumeTrue(file.exists())

        val (configs, metadata) = VlessSubscription.parseWithMetadata(file.bufferedReader())
        val totalLines = file.readLines().count { it.trim().startsWith("vless://") }

        val byNetwork = configs.groupingBy { it.network }.eachCount()
        val bySecurity = configs.groupingBy { it.security }.eachCount()
        val invalid = configs.mapNotNull { config ->
            VlessConfig.validate(config)?.let { config.displayName to it }
        }
        val roundTripFailures = configs.count { config ->
            VlessConfig.parse(config.toUri())?.identity != config.identity
        }
        val uniqueIdentities = configs.map { it.identity }.toSet().size

        println("=== реальная подписка ===")
        println("строк vless:// в файле: $totalLines, разобрано: ${configs.size}")
        println("транспорты: $byNetwork")
        println("security: $bySecurity")
        println("уникальных identity: $uniqueIdentities")
        println("не прошли validate(): ${invalid.size}")
        invalid.take(5).forEach { (name, reason) -> println("   $name -> $reason") }
        println("сломались на round-trip: $roundTripFailures")
        println("метаданные: $metadata")
        println("примеры имён: " + configs.take(3).joinToString(" | ") { it.displayName })
    }
}
