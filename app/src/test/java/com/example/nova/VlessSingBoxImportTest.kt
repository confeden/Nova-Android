package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Конфигурации VLESS раздают не только ссылками: их копируют целиком как
 * outbound sing-box. Пока Nova понимала один вид, вставка второго выглядела как
 * «ничего не импортировалось» — без единого следа о причине.
 *
 * Образец взят из живой раздачи (t.me/rjsxrd): WebSocket поверх TLS с uTLS и
 * Host-заголовком воркера Cloudflare.
 */
class VlessSingBoxImportTest {

    private val sample = """
        {
          "type": "vless",
          "tag": "🇷🇺 LTE 136 | тгк: @rostunnel t.me/rjsxrd",
          "server": "141.101.120.73",
          "server_port": 443,
          "uuid": "5d8eb33a-2166-4daf-88fe-4be5799c493b",
          "tls": {
            "enabled": true,
            "server_name": "noisy-term-e657.airdrop2014aa5168.workers.dev",
            "utls": { "enabled": true, "fingerprint": "chrome" }
          },
          "transport": {
            "type": "ws",
            "path": "/",
            "headers": { "Host": "noisy-term-e657.airdrop2014aa5168.workers.dev" }
          }
        }
    """.trimIndent()

    @Test
    fun `outbound sing-box разбирается полностью`() {
        val config = VlessConfig.parseSingBoxText(sample).single()
        assertEquals("5d8eb33a-2166-4daf-88fe-4be5799c493b", config.uuid)
        assertEquals("141.101.120.73", config.host)
        assertEquals(443, config.port)
        assertEquals("tls", config.security)
        assertEquals("noisy-term-e657.airdrop2014aa5168.workers.dev", config.sni)
        assertEquals("chrome", config.fingerprint)
        assertEquals("ws", config.network)
        assertEquals("/", config.path)
        assertEquals("noisy-term-e657.airdrop2014aa5168.workers.dev", config.hostHeader)
        assertTrue("имя узла берётся из tag", config.remark.contains("rostunnel"))
    }

    @Test
    fun `разобранный outbound превращается в равнозначную ссылку`() {
        val fromJson = VlessConfig.parseSingBoxText(sample).single()
        // Ссылка — внутренняя форма хранения, поэтому обратный разбор обязан дать
        // ту же самую конфигурацию: иначе профиль «терялся» бы при перезапуске.
        val roundTrip = VlessConfig.parse(fromJson.toUri())
        assertEquals(fromJson.identity, roundTrip?.identity)
    }

    @Test
    fun `reality разбирается вместе с ключами`() {
        val reality = """
            { "type": "vless", "server": "1.2.3.4", "server_port": 443,
              "uuid": "u", "flow": "xtls-rprx-vision",
              "tls": { "enabled": true, "server_name": "ya.ru",
                       "utls": { "enabled": true, "fingerprint": "safari" },
                       "reality": { "enabled": true, "public_key": "PBK", "short_id": "SID" } } }
        """.trimIndent()
        val config = VlessConfig.parseSingBoxText(reality).single()
        assertEquals("reality", config.security)
        assertEquals("PBK", config.realityPublicKey)
        assertEquals("SID", config.realityShortId)
        assertEquals("xtls-rprx-vision", config.flow)
        assertEquals("safari", config.fingerprint)
    }

    @Test
    fun `целый конфиг с outbounds разбирается, чужие типы пропускаются`() {
        val full = """
            { "outbounds": [
                { "type": "direct", "tag": "direct" },
                $sample,
                { "type": "block", "tag": "block" }
            ] }
        """.trimIndent()
        assertEquals(1, VlessConfig.parseSingBoxText(full).size)
    }

    @Test
    fun `массив outbound-ов разбирается`() {
        assertEquals(2, VlessConfig.parseSingBoxText("[$sample, $sample]").size)
    }

    @Test
    fun `текст без json не мешает разбору ссылок`() {
        assertTrue(VlessConfig.parseSingBoxText("vless://uuid@host:443#name").isEmpty())
        assertTrue(VlessConfig.parseSingBoxText("").isEmpty())
    }

    /**
     * Сверка с живыми данными.
     *
     * Пары взяты из настоящей раздачи `whoahaow/rjsxrd` (`bypass-1.txt`): там
     * встречаются ровно четыре сочетания транспорта и защиты — tcp/reality,
     * raw/reality, grpc/reality и ws/tls. Каждое записано и ссылкой, и
     * эквивалентным outbound-ом; разбор обязан дать одно и то же, иначе один и
     * тот же узел, принесённый в разных видах, стал бы двумя профилями.
     */
    @Test
    fun `outbound и равнозначная ссылка дают одну конфигурацию`() {
        data class Pair(val uri: String, val json: String)
        val pairs = listOf(
            Pair(
                "vless://d7ff1a6d-529e-4260-aa46-3c8a7fd681f4@139.100.227.176:443" +
                    "?type=tcp&security=reality&sni=ya.ru&flow=xtls-rprx-vision" +
                    "&pbk=Sm9bhIqBASg1A0frr4ogMq47_QfufVAkaY4bp5HhyE8&sid=5726b22c209c49&fp=safari",
                """{ "type":"vless","server":"139.100.227.176","server_port":443,
                     "uuid":"d7ff1a6d-529e-4260-aa46-3c8a7fd681f4","flow":"xtls-rprx-vision",
                     "tls":{"enabled":true,"server_name":"ya.ru",
                            "utls":{"enabled":true,"fingerprint":"safari"},
                            "reality":{"enabled":true,
                                       "public_key":"Sm9bhIqBASg1A0frr4ogMq47_QfufVAkaY4bp5HhyE8",
                                       "short_id":"5726b22c209c49"}},
                     "transport":{"type":"tcp"} }""",
            ),
            Pair(
                "vless://76186f1e-3062-4a2b-98c2-fab3c61a9511@91.224.86.107:9830" +
                    "?security=reality&pbk=bnRIb3Er1i-K6NGGByCO9UbGfOvu43ZoiK7ulPd1SzU&fp=qq" +
                    "&type=grpc&serviceName=grpc-tunnel&sni=dl.google.com",
                """{ "type":"vless","server":"91.224.86.107","server_port":9830,
                     "uuid":"76186f1e-3062-4a2b-98c2-fab3c61a9511",
                     "tls":{"enabled":true,"server_name":"dl.google.com",
                            "utls":{"enabled":true,"fingerprint":"qq"},
                            "reality":{"enabled":true,
                                       "public_key":"bnRIb3Er1i-K6NGGByCO9UbGfOvu43ZoiK7ulPd1SzU"}},
                     "transport":{"type":"grpc","service_name":"grpc-tunnel"} }""",
            ),
            Pair(
                "vless://f9613819-d2fe-4b3a-a2e6-40c184c6854c@194.87.143.165:8443" +
                    "?type=ws&path=%2F&security=tls&sni=wsru1.wba-pn.ru&fp=chrome",
                """{ "type":"vless","server":"194.87.143.165","server_port":8443,
                     "uuid":"f9613819-d2fe-4b3a-a2e6-40c184c6854c",
                     "tls":{"enabled":true,"server_name":"wsru1.wba-pn.ru",
                            "utls":{"enabled":true,"fingerprint":"chrome"}},
                     "transport":{"type":"ws","path":"/"} }""",
            ),
        )
        pairs.forEach { (uri, json) ->
            val fromUri = VlessConfig.parse(uri)
            val fromJson = VlessConfig.parseSingBoxText(json).singleOrNull()
            assertEquals("ссылка не разобралась: $uri", true, fromUri != null)
            assertEquals("outbound не разобрался: $uri", true, fromJson != null)
            assertEquals(fromUri!!.identity, fromJson!!.identity)
        }
    }

    @Test
    fun `битый outbound отбрасывается, а не превращается в пустой профиль`() {
        val broken = """{ "type": "vless", "server": "", "server_port": 0, "uuid": "" }"""
        assertTrue(VlessConfig.parseSingBoxText(broken).isEmpty())
    }
}
