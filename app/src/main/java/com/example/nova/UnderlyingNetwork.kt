package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Сеть под туннелем — та самая, по которой ходит сам туннель.
 *
 * ## Зачем
 *
 * Выпуск профилей Proton должен получаться **в любых условиях**: и при выключенном
 * VPN, и при включённом. Это разные пути, и заранее не известно, какой из них
 * сегодня открыт: с российской сети прямые хосты Proton не открывают TCP вовсе, а
 * из-под чужого выхода их иногда закрывает уже сам Proton. Поэтому запросы, не
 * получившие ответа обычным путём, повторяются по нижележащей сети — то есть мимо
 * собственного туннеля.
 *
 * ## Чем это отличается от `VpnService.protect`
 *
 * `protect()` — метод самой службы и доступен только в процессе `:vpn`. Выпуск же
 * идёт и из интерфейса, а привязка сокета к конкретной `Network` работает в любом
 * процессе приложения. Тот же приём уже применён к сокету MASQUE
 * (`bindMasqueSocketToUnderlyingNetwork`), и там он **измеримо** лучше `protect()`.
 *
 * Гарантии, что привязка уведёт трафик мимо туннеля, нет: без `allowBypass()` это
 * решает система. Поэтому заход стоит **последним** и трактуется как ещё одна
 * попытка, а не как обещание: хуже от неё не бывает, лучше — бывает.
 */
object UnderlyingNetwork {

    /**
     * Лучшая сеть без признака VPN.
     *
     * Выбор по очкам, а не «первая попавшаяся»: у телефона одновременно бывают и
     * Wi-Fi, и сотовая, и та, что уже отвалилась, но ещё числится. `VALIDATED`
     * важнее типа — сеть без выхода в интернет не поможет ни одному заходу.
     */
    fun select(context: Context): Network? {
        val cm = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return null

        fun score(network: Network): Int {
            val caps = cm.getNetworkCapabilities(network) ?: return Int.MIN_VALUE
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Int.MIN_VALUE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Int.MIN_VALUE
            var score = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 1_000
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 100
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 90
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 80
            return score
        }

        @Suppress("DEPRECATION")
        val best = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            .filter { score(it) > Int.MIN_VALUE }
            .maxByOrNull { score(it) }
        return best
    }
}
