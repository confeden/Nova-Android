package com.example.nova

import java.util.Locale

/**
 * Решения раздачи, зависящие от режима релея (`passthrough`). Чистые функции —
 * чтобы их можно было проверить юнит-тестом без Android.
 *
 * В режиме релея своего туннеля у Nova нет: выпускать трафик клиентов можно
 * всегда (он идёт по системной сети, то есть в чужой VPN, если тот включён), а
 * Opera как апстрим не используется — её локальный прокси без туннеля не поднят.
 */
object GatewayPassthroughPolicy {
    fun egressAllowed(connected: Boolean, allowDirect: Boolean, passthrough: Boolean): Boolean =
        passthrough || connected || allowDirect

    fun tunnelBlocksTraffic(connected: Boolean, allowDirect: Boolean, passthrough: Boolean): Boolean =
        !egressAllowed(connected, allowDirect, passthrough)

    fun useOperaUpstream(backendLabel: String, passthrough: Boolean): Boolean =
        !passthrough &&
            backendLabel.trim().uppercase(Locale.US).startsWith(NovaVpnService.BACKEND_OPERA)
}
