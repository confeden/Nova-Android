package com.example.nova

/**
 * Признак «это та же самая подложная сеть».
 *
 * По нему служба решает, была ли смена сети, а смена сети рвёт туннель любого
 * протокола. Поэтому признак обязан быть устойчивым: всё, что меняется само по себе
 * на одной и той же сети, из него исключено.
 *
 * Вынесено из [NovaVpnService] отдельно, потому что правило чисто строковое и
 * проверяется обычными unit-тестами, а не роумингом по квартире с телефоном в руках.
 */
object NetworkIdentity {

    /**
     * Wi-Fi опознаётся по SSID, а не по BSSID.
     *
     * BSSID — это MAC конкретной точки доступа, и он меняется при каждом роуминге:
     * в mesh-сети, с репитером или просто при переходе между 2.4 и 5 ГГц одной точки.
     * Сеть при этом та же, маршрут наружу тот же, и туннель бы выжил — но подпись
     * менялась, служба видела «сеть сменилась» и рвала соединение на ровном месте.
     *
     * Шлюз по умолчанию идёт следующим: он одинаков для всех точек одной сети, то есть
     * переживает роуминг, в отличие от BSSID. Сам BSSID остаётся последней запасной
     * опорой — когда нет ни имени сети, ни шлюза, лучше опознавать хоть как-то.
     */
    fun signature(
        transportLabel: String,
        interfaceName: String,
        ssid: String,
        bssid: String,
        defaultGateway: String,
    ): String {
        val identity = ssid.trim()
            .ifBlank { defaultGateway.trim() }
            .ifBlank { bssid.trim() }
        return listOf(transportLabel.ifBlank { "other" }, interfaceName.trim(), identity)
            .joinToString("|")
    }

    /** Значения-заглушки, которые Android отдаёт вместо данных Wi-Fi без разрешений. */
    fun sanitizeWifiValue(value: String?): String {
        val trimmed = value?.trim()?.trim('"').orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "<unknown ssid>") return ""
        if (trimmed == "02:00:00:00:00:00") return ""
        return trimmed
    }
}
