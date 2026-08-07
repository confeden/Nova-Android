package com.example.nova

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlessXrayConfigTest {

    private val links = mapOf(
        "reality-vision-raw" to
            "vless://11111111-2222-3333-4444-555555555555@89.208.231.191:8443" +
                "?encryption=none&flow=xtls-rprx-vision&fp=qq" +
                "&pbk=4CH3o5zOMcFNMbnwXnkAg0FFepmsc0QzhahXkUzb1ik" +
                "&security=reality&sid=d8c6b58bcbb0c323&sni=max.ru&type=raw#raw",
        "reality-xhttp" to
            "vless://11111111-2222-3333-4444-555555555555@212.233.123.185:8443" +
                "?encryption=none&fp=firefox&host=rus07.example.org&mode=auto" +
                "&path=%2Fapi%2FuploadFile%2F&pbk=N20oGhwfXOP-H9nuvWlJG_UNHwxWWhrTAV-KsjgZFDA" +
                "&security=reality&sid=23697e&sni=rus07.example.org&type=xhttp#xhttp",
        "reality-grpc" to
            "vless://11111111-2222-3333-4444-555555555555@176.98.176.110:443" +
                "?encryption=none&fp=chrome&mode=gun" +
                "&pbk=gWQI82-139LOX4McshbOZOGRElcpw_TXwvjVstOGswY" +
                "&security=reality&sid=d2f7a1c9e3b04685&sni=stepik.org&type=grpc&extra=null#grpc",
        "tls-ws" to
            "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443" +
                "?type=ws&security=tls&sni=cdn.example.org&host=cdn.example.org" +
                "&path=%2Fvless&alpn=h2%2Chttp%2F1.1&fp=chrome#ws",
        "plain-ws" to
            "vless://11111111-2222-3333-4444-555555555555@130.17.2.137:2200" +
                "?encryption=none&path=%2Fv1&security=none&type=ws#plain",
    )

    @Test
    fun buildsRealityOutbound() {
        val config = requireNotNull(VlessConfig.parse(links.getValue("reality-vision-raw")))
        val json = JSONObject(VlessXrayConfig.build(config, socksPort = 10808))

        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        val user = outbound.getJSONObject("settings").getJSONArray("vnext")
            .getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("11111111-2222-3333-4444-555555555555", user.getString("id"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))

        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("raw", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        val reality = stream.getJSONObject("realitySettings")
        assertEquals("max.ru", reality.getString("serverName"))
        // fp=qq умеет только TLS 1.2 — REALITY с ним не поднимется.
        assertEquals("chrome", reality.getString("fingerprint"))
        assertEquals("d8c6b58bcbb0c323", reality.getString("shortId"))

        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("socks", inbound.getString("protocol"))
        assertEquals(10808, inbound.getInt("port"))
        assertTrue(inbound.getJSONObject("settings").getBoolean("udp"))
    }

    @Test
    fun mapsTransportsToTheirSettingsBlocks() {
        val xhttp = JSONObject(
            VlessXrayConfig.build(
                requireNotNull(VlessConfig.parse(links.getValue("reality-xhttp"))),
                socksPort = 10808,
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("xhttp", xhttp.getString("network"))
        assertEquals("auto", xhttp.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("/api/uploadFile/", xhttp.getJSONObject("xhttpSettings").getString("path"))

        val grpc = JSONObject(
            VlessXrayConfig.build(
                requireNotNull(VlessConfig.parse(links.getValue("reality-grpc"))),
                socksPort = 10808,
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("grpc", grpc.getString("network"))
        // mode=gun у gRPC не означает multiMode.
        assertTrue(!grpc.getJSONObject("grpcSettings").optBoolean("multiMode", false))

        val ws = JSONObject(
            VlessXrayConfig.build(
                requireNotNull(VlessConfig.parse(links.getValue("tls-ws"))),
                socksPort = 10808,
            )
        ).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        assertEquals("ws", ws.getString("network"))
        assertEquals("/vless", ws.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.org", ws.getJSONObject("wsSettings").getString("host"))
        assertEquals(2, ws.getJSONObject("tlsSettings").getJSONArray("alpn").length())
    }

    @Test
    fun keepsUdpAssociateEnabledOnSocksInbound() {
        // UDP через инбаунд принимается. Узлы его часто не пропускают, но отключать
        // приём на своей стороне незачем: с рабочим узлом это единственный путь для
        // всего, что не TCP.
        val settings = JSONObject(
            VlessXrayConfig.build(
                requireNotNull(VlessConfig.parse(links.getValue("tls-ws"))),
                socksPort = 10808,
            )
        ).getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertTrue(settings.getBoolean("udp"))
    }

    /** Складывает конфигурации на диск, чтобы прогнать их настоящим Xray. */
    @Test
    fun writeConfigsForCoreValidation() {
        val outDir = File("../tools/probe/xray-configs")
        outDir.mkdirs()
        links.forEach { (name, link) ->
            val config = requireNotNull(VlessConfig.parse(link)) { "не разобралась ссылка $name" }
            File(outDir, "$name.json").writeText(VlessXrayConfig.build(config, socksPort = 10808))
        }
        println("конфигураций записано: ${outDir.listFiles()?.size}")
    }
}
