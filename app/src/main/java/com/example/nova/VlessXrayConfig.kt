package com.example.nova

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сборка конфигурации Xray из разобранной ссылки [VlessConfig].
 *
 * Ядро поднимает SOCKS5 на localhost, а пакеты в него заворачивает уже
 * существующий tun2proxy — та же схема, что используется для Opera-прокси,
 * поэтому туннельный слой переиспользуется без изменений.
 *
 * Имена полей сверены с `tools/xray-core/infra/conf` версии v26.7.28.
 */
object VlessXrayConfig {

    /** Значения `extra` из ссылки, которые Xray принимает как настройки XHTTP. */
    private const val EXTRA_PARAM = "extra"

    fun build(
        config: VlessConfig,
        socksPort: Int,
        socksHost: String = "127.0.0.1",
        logLevel: String = "warning",
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", logLevel))
        root.put("inbounds", JSONArray().put(buildSocksInbound(socksHost, socksPort)))
        root.put("outbounds", JSONArray().put(buildVlessOutbound(config)))
        return root.toString()
    }

    private fun buildSocksInbound(host: String, port: Int): JSONObject {
        val settings = JSONObject()
            .put("auth", "noauth")
            .put("udp", true)
        // Определение протокола нужно, чтобы REALITY и TLS видели настоящий SNI
        // соединения, а не только адрес назначения.
        val sniffing = JSONObject()
            .put("enabled", true)
            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
            .put("routeOnly", false)
        return JSONObject()
            .put("tag", "socks-in")
            .put("listen", host)
            .put("port", port)
            .put("protocol", "socks")
            .put("settings", settings)
            .put("sniffing", sniffing)
    }

    private fun buildVlessOutbound(config: VlessConfig): JSONObject {
        val user = JSONObject()
            .put("id", config.uuid)
            .put("encryption", config.encryption.ifBlank { "none" })
            .put("level", 0)
        if (config.flow.isNotBlank()) {
            user.put("flow", config.flow)
        }

        val vnext = JSONObject()
            .put("address", config.host)
            .put("port", config.port)
            .put("users", JSONArray().put(user))

        return JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", buildStreamSettings(config))
    }

    private fun buildStreamSettings(config: VlessConfig): JSONObject {
        val stream = JSONObject()
        // Xray принимает и старое имя `tcp`, и новое `raw`; отдаём новое.
        val network = when (config.network) {
            "tcp" -> "raw"
            else -> config.network
        }
        stream.put("network", network)

        when (config.security) {
            "reality" -> {
                stream.put("security", "reality")
                stream.put("realitySettings", buildRealitySettings(config))
            }
            "tls" -> {
                stream.put("security", "tls")
                stream.put("tlsSettings", buildTlsSettings(config))
            }
            else -> stream.put("security", "none")
        }

        when (network) {
            "raw" -> {
                if (config.headerType.isNotBlank() && config.headerType != "none") {
                    stream.put(
                        "rawSettings",
                        JSONObject().put("header", JSONObject().put("type", config.headerType)),
                    )
                }
            }
            "ws" -> {
                val ws = JSONObject().put("path", config.path.ifBlank { "/" })
                if (config.hostHeader.isNotBlank()) ws.put("host", config.hostHeader)
                stream.put("wsSettings", ws)
            }
            "httpupgrade" -> {
                val upgrade = JSONObject().put("path", config.path.ifBlank { "/" })
                if (config.hostHeader.isNotBlank()) upgrade.put("host", config.hostHeader)
                stream.put("httpupgradeSettings", upgrade)
            }
            "grpc" -> {
                val grpc = JSONObject().put("serviceName", config.serviceName)
                // Для gRPC `mode` принимает значения gun и multi — это не режимы XHTTP.
                if (config.mode.equals("multi", ignoreCase = true)) {
                    grpc.put("multiMode", true)
                }
                if (config.hostHeader.isNotBlank()) grpc.put("authority", config.hostHeader)
                stream.put("grpcSettings", grpc)
            }
            "xhttp" -> stream.put("xhttpSettings", buildXhttpSettings(config))
            "kcp" -> {
                val kcp = JSONObject()
                if (config.headerType.isNotBlank()) {
                    kcp.put("header", JSONObject().put("type", config.headerType))
                }
                config.extraParams["seed"]?.let { kcp.put("seed", it) }
                stream.put("kcpSettings", kcp)
            }
        }
        return stream
    }

    private fun buildRealitySettings(config: VlessConfig): JSONObject {
        val reality = JSONObject()
            .put("serverName", config.sni)
            .put("publicKey", config.realityPublicKey)
            .put("fingerprint", config.effectiveFingerprint)
        if (config.realityShortId.isNotBlank()) reality.put("shortId", config.realityShortId)
        if (config.realitySpiderX.isNotBlank()) reality.put("spiderX", config.realitySpiderX)
        // Постквантовая проверка сертификата REALITY, если сервер её объявил.
        config.extraParams["mldsa65verify"]?.let { reality.put("mldsa65Verify", it) }
        return reality
    }

    private fun buildTlsSettings(config: VlessConfig): JSONObject {
        val tls = JSONObject()
            .put("serverName", config.sni.ifBlank { config.hostHeader.ifBlank { config.host } })
            .put("allowInsecure", config.allowInsecure)
        if (config.fingerprint.isNotBlank()) tls.put("fingerprint", config.fingerprint)
        if (config.alpn.isNotEmpty()) {
            tls.put("alpn", JSONArray().apply { config.alpn.forEach { put(it) } })
        }
        return tls
    }

    private fun buildXhttpSettings(config: VlessConfig): JSONObject {
        val xhttp = JSONObject()
            .put("path", config.path.ifBlank { "/" })
            .put("mode", config.mode.ifBlank { "auto" })
        if (config.hostHeader.isNotBlank()) xhttp.put("host", config.hostHeader)

        // `extra` приходит строкой с JSON внутри и содержит тонкие настройки
        // XHTTP (xmux, паддинг, размеры чанков). Разбирать их по полям не нужно —
        // Xray понимает их сам; достаточно не потерять и не сломаться на "null".
        val extra = config.extraParams[EXTRA_PARAM]
        if (!extra.isNullOrBlank() && extra != "null") {
            runCatching { JSONObject(extra) }.getOrNull()?.let { parsed ->
                parsed.keys().forEach { key -> xhttp.put(key, parsed.get(key)) }
            }
        }
        return xhttp
    }
}
