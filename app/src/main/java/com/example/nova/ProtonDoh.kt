package com.example.nova

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Альтернативная маршрутизация Proton — штатный обход блокировки их API.
 *
 * Проверено на устройстве (Pixel 4a, Wi-Fi, RU): `vpn-api.proton.me` отвечает на
 * ICMP за 108 мс, но TCP 443 к нему не открывается вовсе — то есть имя не в DNS-,
 * а в транспортной блокировке. Собственно генератор из-за этого падал на
 * «api.protonvpn.ch: timeout», хотя сеть была исправна.
 *
 * Обход — тот же, что в официальном приложении Proton: TXT-запись
 * `d<base32(хост)>.protonpro.xyz`, полученная через DNS-over-HTTPS, называет
 * запасные хосты (на момент проверки — `vpn.protonpro.xyz` и пять адресов AWS).
 * Запасной узел отдаёт **самоподписанный** сертификат `CN=*.demo-wathever.net`,
 * поэтому обычная проверка цепочки там не работает по замыслу: подлинность даёт
 * не имя и не цепочка, а закреплённый ключ. Отпечаток, снятый живьём
 * (`EU6TS9MO0L/…`), совпал с первым из опубликованных Proton
 * `ALTERNATIVE_API_SPKI_PINS` — то есть это их узел, а не перехват.
 */
object ProtonDoh {

    /** Опубликованные Proton отпечатки SPKI запасных узлов (SHA-256, base64). */
    private val ALTERNATIVE_SPKI_PINS = setOf(
        "EU6TS9MO0L/GsDHvVc9D5fChYLNy5JdGYpJw0ccgetM=",
        "iKPIHPnDNqdkvOnTClQ8zQAIKG0XavaPkcEo0LBAABA=",
        "MSlVrBCdL0hKyczvgYVSRNm88RicyY04Q2y5qrBt0xA=",
        "C2UxW0T1Ckl9s+8cXfjXxlEqwAfPM4HiW2y3UdtBeCw=",
    )

    private const val DOH_SUFFIX = ".protonpro.xyz"

    private val DOH_ENDPOINTS = listOf(
        "https://dns.google/resolve",
        "https://1.1.1.1/dns-query",
        "https://dns11.quad9.net/dns-query",
    )

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Клиент для запасных узлов.
     *
     * Проверка имени и цепочки отключена намеренно и заменена на закрепление
     * ключа: сертификат там самоподписанный и выписан на постороннее имя, так что
     * системное доверие отвергло бы честный узел, а закреплённый ключ отвергнет
     * любой чужой. Клиент используется **только** для хостов, полученных из
     * TXT-записи Proton, и ни для чего больше.
     */
    val pinnedClient: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("пустая цепочка сертификатов")
                val digest = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                val pin = Base64.encodeToString(digest, Base64.NO_WRAP)
                if (pin !in ALTERNATIVE_SPKI_PINS) {
                    throw CertificateException("запасной узел Proton предъявил незакреплённый ключ $pin")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** RFC 4648 base32 без выравнивания — ровно в том виде, в каком его ждёт `protonpro.xyz`. */
    private fun base32(input: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in input) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(alphabet[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /**
     * @return запасные хосты для [host], имена раньше адресов. Пустой список —
     *         «обхода нет», и вызывающий обязан сообщить об этом, а не молча
     *         продолжить с прямым хостом, который уже не ответил.
     */
    fun resolveAlternativeHosts(host: String): List<String> {
        val name = "d" + base32(host.toByteArray()) + DOH_SUFFIX
        for (endpoint in DOH_ENDPOINTS) {
            val url = "$endpoint?name=$name&type=TXT"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            val answers = try {
                plainClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        LogManager.log("Proton DoH: $endpoint ответил HTTP ${response.code}")
                        return@use emptyList()
                    }
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        LogManager.log("Proton DoH: $endpoint вернул пустое тело")
                        return@use emptyList<String>()
                    }
                    val json = JSONObject(body)
                    val array = json.optJSONArray("Answer")
                    if (array == null) {
                        LogManager.log(
                            "Proton DoH: $endpoint не дал секции Answer для $name (Status=${json.optInt("Status", -1)})"
                        )
                        return@use emptyList()
                    }
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.optString("data")
                            ?.trim()
                            ?.trim('"')
                            ?.trimEnd('.')
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            } catch (e: Exception) {
                LogManager.log("Proton DoH: $endpoint не ответил — ${e.message}")
                emptyList()
            }
            if (answers.isNotEmpty()) {
                val hosts = answers.sortedBy { it.firstOrNull()?.isDigit() == true }
                LogManager.log("Proton DoH: для $host выданы запасные узлы ${hosts.joinToString(",")}")
                return hosts
            }
        }
        LogManager.log("Proton DoH: запасных узлов для $host не нашлось ни на одном резолвере — обхода нет")
        return emptyList()
    }
}
