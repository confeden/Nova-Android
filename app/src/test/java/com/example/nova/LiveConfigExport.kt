package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Готовит конфигурации Xray из настоящих ссылок для проверки живого
 * подключения. Требует локальный файл подписки, в репозитории его нет.
 *
 * Проверяется именно генератор: те же `VlessXrayConfig.build`, что уходят в
 * приложение, раскладываются по файлам, и живая проба идёт по ним. Ссылка,
 * разобранная без ошибок, ещё ничего не значит — у публичных списков ключи
 * ротируются постоянно.
 */
class LiveConfigExport {

    @Test
    fun exportLiveConfigs() {
        val source = sequenceOf(
            "../tools/probe/black_vless_rus.txt",
            "../tools/probe/goida_2.txt",
        ).map(::File).firstOrNull { it.exists() }
        assumeTrue(source != null)

        val limit = System.getProperty("nova.liveConfigLimit")?.toIntOrNull() ?: 12
        val realityOnly = System.getProperty("nova.liveConfigRealityOnly")?.toBoolean() ?: false

        val outDir = File("../tools/probe/live-configs")
        outDir.deleteRecursively()
        outDir.mkdirs()

        var written = 0
        var port = 11080
        val index = StringBuilder()
        source!!.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (written >= limit) break
                val config = VlessConfig.parse(line.trim()) ?: continue
                if (realityOnly && !config.isReality) continue
                if (VlessConfig.validate(config) != null) continue
                val name = "live-%02d-%s-%d".format(written, config.network, config.port)
                File(outDir, "$name.json").writeText(VlessXrayConfig.build(config, socksPort = port))
                index.append(
                    "$name\t$port\t${config.host}:${config.port}\t${config.network}\t${config.remark}\n"
                )
                written++
                port++
            }
        }
        File(outDir, "index.tsv").writeText(index.toString())
        println("конфигураций для живой проверки: $written")
    }
}
