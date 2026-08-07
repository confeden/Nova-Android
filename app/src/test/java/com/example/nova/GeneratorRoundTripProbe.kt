package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Прогоняет настоящие ссылки через полный путь Nova: разбор → проверка → генерация
 * конфигурации Xray. Результат складывается рядом, чтобы его можно было запустить на
 * устройстве и убедиться, что сгенерированное действительно соединяется.
 *
 * Файл со ссылками в репозиторий не кладём — это чужие рабочие узлы.
 */
class GeneratorRoundTripProbe {

    @Test
    fun buildConfigsFromRealLinks() {
        val source = File("../tools/probe/reality_links.txt")
        assumeTrue(source.exists())

        val outDir = File("../tools/probe/novagen")
        outDir.deleteRecursively()
        outDir.mkdirs()

        var port = 11900
        var written = 0
        val index = StringBuilder()
        val problems = StringBuilder()
        source.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val config = VlessConfig.parse(line)
            if (config == null) {
                problems.append("не разобрано: ${line.take(60)}\n")
                return@forEach
            }
            val invalid = VlessConfig.validate(config)
            if (invalid != null) {
                problems.append("не прошло проверку (${config.host}): $invalid\n")
                return@forEach
            }
            File(outDir, "g%02d.json".format(written))
                .writeText(VlessXrayConfig.build(config, socksPort = port))
            index.append("g%02d\t%d\t%s:%d\t%s\t%s\n".format(
                written, port, config.host, config.port, config.network, config.sni))
            written++
            port++
        }
        File(outDir, "index.tsv").writeText(index.toString())
        println("сгенерировано конфигураций Nova: $written")
        if (problems.isNotEmpty()) println("проблемы:\n$problems")
    }
}
