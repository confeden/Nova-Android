package com.example.nova

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Готовит клиентскую конфигурацию для локального сервера REALITY.
 * Ссылка лежит рядом с сервером в tools/probe и в репозиторий не попадает.
 */
class LocalRealityConfigExport {

    @Test
    fun exportLocalRealityConfig() {
        val linkFile = File("../tools/probe/local-link.txt")
        assumeTrue(linkFile.exists())

        val config = requireNotNull(VlessConfig.parse(linkFile.readText().trim()))
        require(VlessConfig.validate(config) == null) { "ссылка не прошла проверку" }
        File("../tools/probe/local-client.json")
            .writeText(VlessXrayConfig.build(config, socksPort = 14500, logLevel = "info"))
        println("клиентская конфигурация записана, транспорт=${config.network}, flow=${config.flow}")
    }
}
