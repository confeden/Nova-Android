package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessConfigTest {

    private val realityLink =
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=xN9GH0lFVvxTPTTLuFxVUJKMhLxNCcpERRRBCjKrGXo&sid=6ba85179e30d4fc2" +
            "&spx=%2F&flow=xtls-rprx-vision#Node%20RU-1"

    @Test
    fun parsesRealityLink() {
        val config = VlessConfig.parse(realityLink)
        assertNotNull(config)
        requireNotNull(config)
        assertEquals("11111111-2222-3333-4444-555555555555", config.uuid)
        assertEquals("example.com", config.host)
        assertEquals(443, config.port)
        assertEquals("reality", config.security)
        assertEquals("www.microsoft.com", config.sni)
        assertEquals("chrome", config.fingerprint)
        assertEquals("xN9GH0lFVvxTPTTLuFxVUJKMhLxNCcpERRRBCjKrGXo", config.realityPublicKey)
        assertEquals("6ba85179e30d4fc2", config.realityShortId)
        assertEquals("/", config.realitySpiderX)
        assertEquals("xtls-rprx-vision", config.flow)
        assertEquals("Node RU-1", config.remark)
        assertTrue(config.isReality)
        assertNull(VlessConfig.validate(config))
    }

    @Test
    fun parsesXhttpAndGrpcTransports() {
        val xhttp = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:8443" +
                "?type=xhttp&security=tls&sni=cdn.example.org&mode=stream-up&path=%2Fdownload"
        )
        requireNotNull(xhttp)
        assertEquals("xhttp", xhttp.network)
        assertEquals("stream-up", xhttp.mode)
        assertEquals("/download", xhttp.path)

        val grpc = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:2053" +
                "?type=grpc&security=tls&serviceName=GunService&sni=a.b.c"
        )
        requireNotNull(grpc)
        assertEquals("grpc", grpc.network)
        assertEquals("GunService", grpc.serviceName)
        assertNull(VlessConfig.validate(grpc))
    }

    @Test
    fun treatsRawTransportAsTcp() {
        // Так выглядит 57% реальной подписки zieng2/wl: Xray переименовал tcp в raw.
        val raw = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@89.208.231.191:8443" +
                "?encryption=none&flow=xtls-rprx-vision&fp=qq&pbk=4CH3o5zOMcFNMbnwXnkAg0FFepmsc0QzhahXkUzb1ik" +
                "&security=reality&sid=d8c6b58bcbb0c323&sni=max.ru&type=raw#%F0%9F%87%B7%F0%9F%87%BA%20VK"
        )
        requireNotNull(raw)
        assertEquals("tcp", raw.network)
        assertEquals("xtls-rprx-vision", raw.flow)
        assertNull(VlessConfig.validate(raw))

        val tcp = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@89.208.231.191:8443" +
                "?encryption=none&flow=xtls-rprx-vision&fp=qq&pbk=4CH3o5zOMcFNMbnwXnkAg0FFepmsc0QzhahXkUzb1ik" +
                "&security=reality&sid=d8c6b58bcbb0c323&sni=max.ru&type=tcp#other"
        )
        requireNotNull(tcp)
        assertEquals(raw.identity, tcp.identity)

        assertEquals("xhttp", VlessConfig.normalizeNetwork("splithttp"))
        assertEquals("ws", VlessConfig.normalizeNetwork("websocket"))
    }

    @Test
    fun acceptsGrpcWithoutServiceNameAndNullExtra() {
        val config = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@176.98.176.110:443" +
                "?encryption=none&fp=chrome&mode=gun&pbk=gWQI82-139LOX4McshbOZOGRElcpw_TXwvjVstOGswY" +
                "&security=reality&sid=d2f7a1c9e3b04685&sni=stepik.org&type=grpc&extra=null"
        )
        requireNotNull(config)
        assertEquals("grpc", config.network)
        assertEquals("gun", config.mode)
        assertNull(VlessConfig.validate(config))
    }

    @Test
    fun replacesTls12OnlyFingerprintsForReality() {
        // fp=qq стоит у 111 профилей реальной подписки и ломает REALITY: TLS 1.2.
        val qq = VlessConfig.parse(realityLink.replace("fp=chrome", "fp=qq"))
        requireNotNull(qq)
        assertEquals("qq", qq.fingerprint)
        assertEquals("chrome", qq.effectiveFingerprint)

        val firefox = VlessConfig.parse(realityLink.replace("fp=chrome", "fp=firefox"))
        requireNotNull(firefox)
        assertEquals("firefox", firefox.effectiveFingerprint)

        val plainTls = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@h.example:443?security=tls&fp=qq"
        )
        requireNotNull(plainTls)
        assertEquals("qq", plainTls.effectiveFingerprint)
    }

    @Test
    fun rejectsRealityKeysOfWrongSize() {
        val shortKey = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@h.example:443" +
                "?security=reality&sni=h.example&pbk=tooshort"
        )
        requireNotNull(shortKey)
        assertTrue(VlessConfig.validate(shortKey).orEmpty().contains("pbk"))

        val oddShortId = VlessConfig.parse(
            realityLink.replace("sid=6ba85179e30d4fc2", "sid=6ba851790")
        )
        requireNotNull(oddShortId)
        assertTrue(VlessConfig.validate(oddShortId).orEmpty().contains("sid"))
    }

    @Test
    fun parsesIpv6LiteralHost() {
        val config = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443?security=tls&sni=x.y"
        )
        requireNotNull(config)
        assertEquals("2001:db8::1", config.host)
        assertEquals(443, config.port)
    }

    @Test
    fun keepsUnknownParamsForRoundTrip() {
        val config = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@h.example:443?security=tls&sni=h.example&xmux=4"
        )
        requireNotNull(config)
        assertEquals("4", config.extraParams["xmux"])
        val reparsed = VlessConfig.parse(config.toUri())
        requireNotNull(reparsed)
        assertEquals(config.identity, reparsed.identity)
        assertEquals("4", reparsed.extraParams["xmux"])
    }

    @Test
    fun identityIgnoresRemark() {
        val a = VlessConfig.parse("$realityLink")
        val b = VlessConfig.parse(realityLink.substringBefore('#') + "#Renamed%20node")
        requireNotNull(a)
        requireNotNull(b)
        assertEquals(a.identity, b.identity)
    }

    @Test
    fun rejectsBrokenLinks() {
        assertNull(VlessConfig.parse("vmess://whatever"))
        assertNull(VlessConfig.parse("vless://@host:443"))
        assertNull(VlessConfig.parse("vless://uuid@host:0"))
        assertNull(VlessConfig.parse("vless://uuid@host:70000"))
        assertNull(VlessConfig.parse("vless://uuid@host"))
    }

    @Test
    fun explainsWhyRealityLinkWillNotWork() {
        val noKey = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@h.example:443?security=reality&sni=h.example"
        )
        requireNotNull(noKey)
        assertEquals(
            "REALITY без публичного ключа (pbk) — подключение невозможно",
            VlessConfig.validate(noKey),
        )

        // 31 символ — единственная длина, которую Xray не принимает: под вывод
        // UUIDv5 подходит 1..30, под разбор готового UUID — 32..36.
        val tooLongId = VlessConfig.parse("vless://${"a".repeat(31)}@h.example:443?security=tls")
        requireNotNull(tooLongId)
        assertTrue(VlessConfig.validate(tooLongId).orEmpty().startsWith("Идентификатор"))

        val visionOnWs = VlessConfig.parse(
            "vless://11111111-2222-3333-4444-555555555555@h.example:443" +
                "?security=reality&sni=h.example&pbk=xN9GH0lFVvxTPTTLuFxVUJKMhLxNCcpERRRBCjKrGXo&type=ws&flow=xtls-rprx-vision"
        )
        requireNotNull(visionOnWs)
        assertTrue(VlessConfig.validate(visionOnWs).orEmpty().contains("flow="))
    }

    /**
     * Границы взяты из common/uuid/uuid.go вендоренного Xray: произвольная строка
     * длиной 1..30 превращается в UUIDv5, 32..36 разбирается как готовый UUID.
     * Требование канонического UUID молча теряло бы рабочие профили — в реальной
     * подписке такие встречаются.
     */
    @Test
    fun acceptsNonUuidUserIdsExactlyAsXrayDoes() {
        assertTrue(VlessConfig.isAcceptableUserId("shady-vpn-shady-vpn-shady_vpn1"))
        assertTrue(VlessConfig.isAcceptableUserId("a"))
        assertTrue(VlessConfig.isAcceptableUserId("a".repeat(30)))
        assertTrue(VlessConfig.isAcceptableUserId("11111111-2222-3333-4444-555555555555"))
        assertTrue(VlessConfig.isAcceptableUserId("0123456789abcdef0123456789abcdef"))

        assertFalse(VlessConfig.isAcceptableUserId(""))
        assertFalse(VlessConfig.isAcceptableUserId("a".repeat(31)))
        assertFalse(VlessConfig.isAcceptableUserId("a".repeat(37)))
        assertFalse(VlessConfig.isAcceptableUserId("zzzzzzzz-2222-3333-4444-555555555555"))
    }
}
