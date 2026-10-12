package com.example.nova

import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Отдельный замер IPv4 выхода.
 *
 * ## Зачем он нужен
 *
 * Основной источник [ExitAddress.URL_ANY] отвечает тем семейством, которое выбрал
 * стек, а стек при живом IPv6 всегда выбирает IPv6. Поэтому поле IPv4 на экране
 * было прочерком на всех транспортах, кроме Tor: только там адрес источника
 * заранее резолвился в A-запись, и соединение шло по IPv4 (Redmi Note 9S,
 * Proton NO, 2026-10-12: `—` в поле IPv4, IPv6 и флаг RU при живом туннеле).
 *
 * ## Как он устроен
 *
 * - **Через сеть** (TUN-транспорты): тот же источник [ExitAddress.URL_ANY], но
 *   резолвер клиента отдаёт только A-записи — сервер видит IPv4 выхода. Источник
 *   владельца (D20) не меняется.
 * - **Через прокси** (Opera, VLESS): имя резолвит сам прокси, семейство там не
 *   выбрать. Поэтому trace по литералу `1.1.1.1` по HTTPS: литерал IPv4 другого
 *   семейства не даёт, а вырезы маршрутов на прокси-путь не действуют — запрос
 *   уходит в прокси явно. По HTTP литерал больше не годится: Cloudflare с
 *   октября 2026 отвечает на него `301` на HTTPS.
 *
 * Адрес и страна берутся из **одного** ответа (I10). Если IPv4-ответ есть, страна
 * на бейдже — его: IPv4 туннель несёт всегда, а IPv6 на части транспортов идёт
 * мимо него (P41), и страна IPv6-ответа тогда — страна провайдера.
 */
object ExitAddressV4 {

    private const val TIMEOUT_MS = 3_500L

    /** Только A-записи: соединение обязано идти по IPv4. */
    private class Ipv4OnlyDns(private val network: Network?) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = network?.getAllByName(hostname)?.toList()
                ?: InetAddress.getAllByName(hostname).toList()
            val v4 = all.filterIsInstance<Inet4Address>()
            if (v4.isEmpty()) throw UnknownHostException("$hostname: A-записей нет")
            return v4
        }
    }

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** IPv4 выхода через сеть [network] (null — сеть по умолчанию процесса). */
    fun overNetwork(network: Network?): ExitAddress.Observation? {
        return try {
            val builder = baseClient.newBuilder().dns(Ipv4OnlyDns(network))
            if (network != null) builder.socketFactory(network.socketFactory)
            val request = Request.Builder()
                .url(ExitAddress.URL_ANY)
                .header("User-Agent", "NovaAndroid/1.12")
                .header("Accept", "text/plain,*/*")
                .build()
            builder.build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                ExitAddress.parse(response.body?.string())?.takeIf { ExitAddress.isIpv4(it.ip) }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** IPv4 выхода через HTTP- или SOCKS-прокси. */
    fun viaProxy(proxy: Proxy): ExitAddress.Observation? {
        for (url in CloudflareTrace.IPV4_URLS) {
            val body = try {
                val connection = URL(url).openConnection(proxy) as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = TIMEOUT_MS.toInt()
                connection.readTimeout = TIMEOUT_MS.toInt()
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "NovaAndroid/1.12")
                connection.setRequestProperty("Accept", "text/plain,*/*")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                text
            } catch (_: Exception) {
                null
            }
            val parsed = ExitAddress.parseTrace(body)?.takeIf { ExitAddress.isIpv4(it.ip) }
            if (parsed != null) return parsed
        }
        return null
    }

    /** Итог двух замеров: поля экрана. */
    data class Merged(val ipv4: String, val ipv6: String, val country: String)

    /**
     * Сводит основной ответ и IPv4-ответ.
     *
     * Страна берётся у IPv4-ответа, когда он есть, — вместе с его адресом (I10);
     * иначе у основного. IPv6 берётся только из основного ответа.
     */
    fun merge(primary: ExitAddress.Observation?, v4: ExitAddress.Observation?): Merged? {
        val primaryIsV4 = primary != null && ExitAddress.isIpv4(primary.ip)
        val ipv4 = v4?.ip ?: primary?.ip?.takeIf { primaryIsV4 }.orEmpty()
        val ipv6 = primary?.ip?.takeIf { !primaryIsV4 }.orEmpty()
        val country = when {
            v4 != null && v4.country.isNotBlank() -> v4.country
            else -> primary?.country.orEmpty()
        }
        if (ipv4.isBlank() && ipv6.isBlank() && country.isBlank()) return null
        return Merged(ipv4 = ipv4, ipv6 = ipv6, country = country)
    }
}
