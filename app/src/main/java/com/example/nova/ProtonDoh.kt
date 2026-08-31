package com.example.nova

import android.util.Base64
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Резолверы, проверенные **нашей же формой запроса** (`?name=&type=TXT` плюс
     * `Accept: application/dns-json`).
     *
     * Прежний список из трёх был списком из одного: `dns11.quad9.net` отвечает на
     * эту форму отказом — `400` на телефоне, `505` с настольной машины, — то есть
     * JSON-API у него по этому адресу нет вовсе. Оставались `dns.google` и
     * `1.1.1.1`, и когда первый однажды не ответил, а до второго не открылось TCP,
     * весь выпуск профилей падал с «api.protonvpn.ch: timeout» на чистой установке.
     *
     * Теперь шесть входов у двух операторов, и половина — литеральные адреса:
     * они работают, даже когда DNS подменён или не отвечает. Все шесть проверены
     * живьём из российской сети, каждый вернул шесть записей TXT.
     */
    private val DOH_ENDPOINTS = listOf(
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query",
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/resolve",
        "https://1.0.0.1/dns-query",
        "https://8.8.4.4/resolve",
    )

    /**
     * Сроки короче прежних (8/12/20 с) намеренно: резолверов теперь шесть, и обход
     * всего списка на прежних сроках занимал бы до двух минут — пользователь успел
     * бы решить, что приложение зависло. Один DNS-запрос, который не ответил за
     * восемь секунд, не ответит и за двадцать.
     */
    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
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
     *
     * Сроки короче прежних 12/20/30 по той же причине, что и у прямых хостов
     * ([ProtonApi]), плюс новая: узлы теперь опрашиваются не по очереди, а разом
     * ([ProtonRace]), поэтому `callTimeout` — это не срок одной попытки, а срок
     * **всего** обходного шага, который пользователь ждёт на экране. Пятнадцати
     * секунд узлу Proton на 8–21 КБ хватает с запасом: когда он работает, он
     * отвечает за секунды, а когда российский транзит рвёт ответ — не отвечает
     * никогда.
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
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
     *
     * Все шесть резолверов опрашиваются разом: запрос TXT весит десятки байт, а
     * перебор по очереди стоил бы до 72 с на сроках `plainClient` — и это перед
     * тем, как обход вообще начнётся. Ответы у них одинаковые, так что выбирать
     * между ними нечего: годится первый пришедший.
     */
    fun resolveAlternativeHosts(host: String): List<String> {
        val name = "d" + base32(host.toByteArray()) + DOH_SUFFIX
        val attempts = DOH_ENDPOINTS.map { endpoint ->
            endpoint to plainClient.newCall(
                Request.Builder()
                    .url("$endpoint?name=$name&type=TXT")
                    .header("Accept", "application/dns-json")
                    .build()
            )
        }
        val abandoned = AtomicBoolean(false)
        val hosts = ProtonRace.firstSuccess(
            attempts = attempts.map { (endpoint, call) -> { readAnswers(endpoint, call, name, abandoned) } },
            cancelAll = {
                abandoned.set(true)
                attempts.forEach { runCatching { it.second.cancel() } }
            },
        )
        if (hosts == null) {
            LogManager.log("Proton DoH: запасных узлов для $host не нашлось ни на одном резолвере — обхода нет")
            return emptyList()
        }
        LogManager.log("Proton DoH: для $host выданы запасные узлы ${hosts.joinToString(",")}")
        return hosts
    }

    /** @return непустой список запасных хостов, либо null — «этот резолвер не помог». */
    private fun readAnswers(
        endpoint: String,
        call: Call,
        name: String,
        abandoned: AtomicBoolean,
    ): List<String>? {
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    LogManager.log("Proton DoH: $endpoint ответил HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    LogManager.log("Proton DoH: $endpoint вернул пустое тело")
                    return@use null
                }
                val json = JSONObject(body)
                val array = json.optJSONArray("Answer")
                if (array == null) {
                    LogManager.log(
                        "Proton DoH: $endpoint не дал секции Answer для $name (Status=${json.optInt("Status", -1)})"
                    )
                    return@use null
                }
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.optString("data")
                        ?.trim()
                        ?.trim('"')
                        ?.trimEnd('.')
                        ?.takeIf { it.isNotBlank() }
                }
                    .sortedBy { it.firstOrNull()?.isDigit() == true }
                    .takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            // Признак «оборвали мы сами» — свой флаг, а не `call.isCanceled()`:
            // истёкший `callTimeout` OkHttp отменяет сам, и запрос, честно не
            // ответивший за отведённый срок, не оставлял бы в журнале ни строки
            // (G8: пустой журнал — не подтверждение).
            if (!abandoned.get()) LogManager.log("Proton DoH: $endpoint не ответил — ${e.message}")
            null
        }
    }
}
