package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Разбор настоящей смешанной подписки. Файл в репозиторий не кладём: это чужие
 * рабочие узлы, они быстро устаревают, и хранить их у себя незачем. Скачайте
 * список в tools/probe/, чтобы прогнать проверку.
 */
class MixedSubscriptionProbe {

    @Test
    fun parseRealMixedSubscription() {
        val source = File("../tools/probe/black_list.txt")
        assumeTrue(source.exists())

        val kept = mutableListOf<VlessConfig>()
        val stats = source.bufferedReader().use { reader ->
            VlessSubscription.parseStreaming(reader) { kept += it }
        }
        val invalidReasons = kept.mapNotNull { VlessConfig.validate(it) }

        println(
            buildString {
                append("строк: ${stats.linesSeen}")
                append(", vless разобрано: ${stats.parsed}")
                append(", взято: ${stats.kept}")
                append(", дубликатов: ${stats.duplicates}")
                append(", битых: ${stats.invalid}")
                append(", другие протоколы: ${stats.skippedOtherProtocol}")
                append(" (${stats.describeSkippedProtocols()})")
                append(", не прошли валидацию: ${invalidReasons.size}")
            }
        )
        invalidReasons.groupingBy { it }.eachCount().forEach { (reason, count) ->
            println("  валидация: $reason — $count")
        }
        val transports = kept.groupingBy { it.network }.eachCount()
        println("  транспорты: $transports")
        println("  reality: ${kept.count { it.isReality }} из ${kept.size}")
    }
}
