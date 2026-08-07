package com.example.nova

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Разобранная ссылка `vless://uuid@host:port?params#remark`.
 *
 * Модель повторяет то, что реально встречается в подписках 2025-2026:
 * TCP/gRPC/WebSocket/HTTPUpgrade/XHTTP поверх TLS или REALITY, с XTLS Vision.
 * Неизвестные параметры сохраняются в [extraParams], чтобы ссылка пережила
 * импорт-экспорт без потерь, даже если Nova пока не умеет их применять.
 */
data class VlessConfig(
    val uuid: String,
    val host: String,
    val port: Int,
    val remark: String = "",

    // security: none | tls | reality
    val security: String = "none",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,

    // REALITY
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",

    // transport: tcp | grpc | ws | httpupgrade | xhttp | kcp | http
    val network: String = "tcp",
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val headerType: String = "",

    // flow: "" | xtls-rprx-vision | xtls-rprx-vision-udp443
    val flow: String = "",
    val encryption: String = "none",

    val extraParams: Map<String, String> = emptyMap(),
) {
    /**
     * Ключ, по которому конфигурация опознаётся между обновлениями подписки.
     * Намеренно не включает [remark]: провайдеры переименовывают узлы, и по
     * имени диффать нельзя — иначе каждое обновление выглядит как полная замена.
     */
    val identity: String
        get() {
            // Все параметры, а не выборка: в реальных подписках соседние строки
            // отличаются только fp или alpn, и по укороченному ключу разные узлы
            // схлопывались бы в один.
            val params = sortedMapOf<String, String>()
            fun put(key: String, value: String) {
                if (value.isNotBlank() && value != "null") params[key] = value
            }
            put("security", security)
            put("type", network)
            put("sni", sni)
            put("fp", fingerprint)
            put("alpn", alpn.joinToString(","))
            put("pbk", realityPublicKey)
            put("sid", realityShortId)
            put("spx", realitySpiderX)
            put("path", path)
            put("host", hostHeader)
            put("serviceName", serviceName)
            put("mode", mode)
            put("headerType", headerType)
            put("flow", flow)
            put("encryption", encryption)
            if (allowInsecure) put("allowInsecure", "1")
            extraParams.forEach { (key, value) -> put(key, value) }

            return buildString {
                append(uuid).append('|')
                append(host.lowercase(Locale.US)).append('|')
                append(port)
                params.forEach { (key, value) -> append('|').append(key).append('=').append(value) }
            }
        }

    val displayName: String
        get() = remark.ifBlank { "$host:$port" }

    val isReality: Boolean
        get() = security.equals("reality", ignoreCase = true)

    /**
     * Отпечаток uTLS, который можно реально использовать.
     *
     * REALITY требует TLS 1.3, а профили `android`, `qq` и `360` имитируют
     * клиенты, умеющие только TLS 1.2 — сервер отвечает отказом. В подписках
     * такие значения встречаются массово, поэтому они молча заменяются на
     * chrome, а исходное значение остаётся в [fingerprint].
     */
    val effectiveFingerprint: String
        get() = when {
            !isReality -> fingerprint
            fingerprint.lowercase(Locale.US) in TLS12_ONLY_FINGERPRINTS -> "chrome"
            fingerprint.isBlank() -> "chrome"
            else -> fingerprint
        }

    fun toUri(): String {
        val authorityHost = if (host.contains(':')) "[$host]" else host
        val params = LinkedHashMap<String, String>()
        params["type"] = network
        params["security"] = security
        if (encryption.isNotBlank()) params["encryption"] = encryption
        if (sni.isNotBlank()) params["sni"] = sni
        if (fingerprint.isNotBlank()) params["fp"] = fingerprint
        if (alpn.isNotEmpty()) params["alpn"] = alpn.joinToString(",")
        if (allowInsecure) params["allowInsecure"] = "1"
        if (realityPublicKey.isNotBlank()) params["pbk"] = realityPublicKey
        if (realityShortId.isNotBlank()) params["sid"] = realityShortId
        if (realitySpiderX.isNotBlank()) params["spx"] = realitySpiderX
        if (path.isNotBlank()) params["path"] = path
        if (hostHeader.isNotBlank()) params["host"] = hostHeader
        if (serviceName.isNotBlank()) params["serviceName"] = serviceName
        if (mode.isNotBlank()) params["mode"] = mode
        if (headerType.isNotBlank()) params["headerType"] = headerType
        if (flow.isNotBlank()) params["flow"] = flow
        params.putAll(extraParams)
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val base = "vless://$uuid@$authorityHost:$port" + if (query.isBlank()) "" else "?$query"
        return if (remark.isBlank()) base else "$base#${encode(remark)}"
    }

    companion object {
        private val KNOWN_PARAMS = setOf(
            "type", "security", "encryption", "sni", "peer", "fp", "alpn", "allowinsecure",
            "pbk", "sid", "spx", "path", "host", "servicename", "mode", "headertype", "flow",
            "seed", "quicsecurity", "key",
        )

        /**
         * Разбирает одну ссылку. Возвращает null, если это не рабочая
         * vless-ссылка: пустой UUID, отсутствующий хост или порт вне 1..65535.
         */
        fun parse(raw: String): VlessConfig? {
            // Пробелы и '|' встречаются в живых подписках и делают ссылку
            // формально некорректной — экранируем, а не отбрасываем.
            val trimmed = raw.trim().replace(" ", "%20").replace("|", "%7C")
            if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

            val withoutScheme = trimmed.substring("vless://".length)
            val fragmentIndex = withoutScheme.indexOf('#')
            val remark = if (fragmentIndex >= 0) {
                decode(withoutScheme.substring(fragmentIndex + 1))
            } else {
                ""
            }
            val beforeFragment = if (fragmentIndex >= 0) {
                withoutScheme.substring(0, fragmentIndex)
            } else {
                withoutScheme
            }

            val queryIndex = beforeFragment.indexOf('?')
            val authority = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
            val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""

            val atIndex = authority.lastIndexOf('@')
            if (atIndex <= 0) return null
            val uuid = decode(authority.substring(0, atIndex)).trim()
            if (uuid.isBlank()) return null

            // После host:port может идти путь (`@host:80/?type=ws`) — он не несёт
            // смысла для VLESS, реальный путь приходит параметром `path`.
            val hostPort = authority.substring(atIndex + 1).trim().substringBefore('/')
            val (host, port) = splitHostPort(hostPort) ?: return null
            if (host.isBlank() || port !in 1..65535) return null

            val params = parseQuery(query)
            fun param(vararg names: String): String {
                for (name in names) {
                    val value = params[name]
                    if (!value.isNullOrBlank()) return value
                }
                return ""
            }

            val security = param("security").ifBlank { "none" }.lowercase(Locale.US)
            val network = normalizeNetwork(param("type"))
            val alpnRaw = param("alpn")

            return VlessConfig(
                uuid = uuid,
                host = host,
                port = port,
                remark = remark,
                security = security,
                // "peer" — устаревшее имя SNI, всё ещё попадается в старых подписках.
                sni = param("sni", "peer"),
                alpn = alpnRaw.split(',').map { it.trim() }.filter { it.isNotBlank() },
                fingerprint = param("fp"),
                allowInsecure = param("allowinsecure").let { it == "1" || it.equals("true", true) },
                realityPublicKey = param("pbk"),
                realityShortId = param("sid"),
                realitySpiderX = param("spx"),
                network = network,
                path = param("path"),
                hostHeader = param("host"),
                serviceName = param("servicename"),
                mode = param("mode"),
                headerType = param("headertype"),
                flow = param("flow"),
                encryption = param("encryption").ifBlank { "none" },
                extraParams = params.filterKeys { it !in KNOWN_PARAMS },
            )
        }

        /**
         * Человекочитаемая причина, по которой ссылка не будет работать,
         * или null, если конфигурация выглядит корректной.
         */
        fun validate(config: VlessConfig): String? {
            if (!isAcceptableUserId(config.uuid)) {
                return "Идентификатор пользователя не подходит: ${config.uuid}"
            }
            if (config.isReality) {
                if (config.realityPublicKey.isBlank()) {
                    return "REALITY без публичного ключа (pbk) — подключение невозможно"
                }
                if (config.sni.isBlank()) {
                    return "REALITY без SNI — сервер не сможет подобрать сертификат"
                }
                if (config.realityShortId.isNotBlank() &&
                    (!config.realityShortId.matches(Regex("^[0-9a-fA-F]{1,16}$")) ||
                        config.realityShortId.length % 2 != 0)
                ) {
                    // sid — это hex-представление до 8 байт, значит длина чётная.
                    return "shortId (sid) должен быть hex чётной длины до 16 символов"
                }
                if (decodeBase64UrlLength(config.realityPublicKey) != 32) {
                    return "публичный ключ (pbk) должен быть 32-байтным X25519 в base64url"
                }
                if (config.flow.isNotBlank() && config.network != "tcp") {
                    return "flow=${config.flow} работает только с type=tcp, а указан ${config.network}"
                }
            }
            if (config.encryption != "none" && config.encryption.isNotBlank() &&
                !config.encryption.startsWith("mlkem768", ignoreCase = true)
            ) {
                return "неизвестное значение encryption=${config.encryption}"
            }
            return null
        }

        private val TLS12_ONLY_FINGERPRINTS = setOf("android", "qq", "360")

        private const val BASE64URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** Длина в байтах, которую даёт base64url-строка, или -1 при мусоре. */
        private fun decodeBase64UrlLength(value: String): Int {
            val symbols = value.trim().trimEnd('=')
            if (symbols.isEmpty()) return -1
            if (symbols.any { it !in BASE64URL_ALPHABET && it != '+' && it != '/' }) return -1
            val bits = symbols.length * 6
            return bits / 8
        }

        /**
         * Идентификатор пользователя не обязан быть UUID.
         *
         * Xray принимает произвольную строку и выводит из неё UUIDv5 через SHA-1
         * (`common/uuid/uuid.go`, ParseString): длина 32..36 разбирается как обычный
         * UUID, длина 1..30 превращается в производный, а 31 и больше 36 —
         * отвергается. Требовать канонический UUID означало бы молча терять рабочие
         * профили: в списке BLACK_VLESS_RUS_mobile.txt таких два, с логином
         * вида `shady-vpn-...` длиной ровно 30 символов.
         */
        internal fun isAcceptableUserId(value: String): Boolean {
            val length = value.length
            if (length in 32..36) return UUID_REGEX.matches(value) || HEX32_REGEX.matches(value)
            return length in 1..30
        }

        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        private val HEX32_REGEX = Regex("^[0-9a-fA-F]{32}$")

        /**
         * Xray переименовал транспорты, и подписки смешивают старые и новые имена:
         * `raw` — это `tcp`, `xhttp` — бывший `splithttp`. Без приведения к одному
         * виду один и тот же узел меняет [identity] при смене генератора ссылок.
         */
        fun normalizeNetwork(value: String): String = when (value.trim().lowercase(Locale.US)) {
            "", "tcp", "raw" -> "tcp"
            "xhttp", "splithttp" -> "xhttp"
            "ws", "websocket" -> "ws"
            "kcp", "mkcp" -> "kcp"
            "httpupgrade" -> "httpupgrade"
            "h2", "http" -> "http"
            else -> value.trim().lowercase(Locale.US)
        }

        private fun splitHostPort(value: String): Pair<String, Int>? {
            // IPv6-литерал в квадратных скобках: [2001:db8::1]:443
            if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close < 0) return null
                val host = value.substring(1, close)
                val rest = value.substring(close + 1)
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1).toIntOrNull() ?: return null
                return host to port
            }
            val colon = value.lastIndexOf(':')
            if (colon <= 0) return null
            val port = value.substring(colon + 1).toIntOrNull() ?: return null
            return value.substring(0, colon) to port
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isBlank()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isBlank()) continue
                val eq = pair.indexOf('=')
                val key = if (eq >= 0) pair.substring(0, eq) else pair
                val value = if (eq >= 0) pair.substring(eq + 1) else ""
                result[decode(key).lowercase(Locale.US)] = decode(value)
            }
            return result
        }

        private fun decode(value: String): String =
            // URLDecoder трактует '+' как пробел; в путях и SNI это ломает значение,
            // поэтому плюс экранируется до декодирования.
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(value)

        private fun encode(value: String): String =
            runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
                .getOrDefault(value)
    }
}
