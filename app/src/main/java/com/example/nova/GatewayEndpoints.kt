package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Тип адреса, на котором Nova готова принимать клиентов.
 *
 * [downstream] означает, что устройство подключено к самому телефону — через точку
 * доступа, USB, Bluetooth или Wi-Fi Direct. Такие клиенты находятся в сети, которой
 * управляет владелец телефона, поэтому для них допустимы послабления, недопустимые
 * в чужой Wi-Fi сети.
 */
enum class GatewayKind(val title: String, val downstream: Boolean) {
    WIFI_AP("Точка доступа Wi-Fi", true),
    USB("USB-модем", true),
    BLUETOOTH("Bluetooth-модем", true),
    WIFI_DIRECT("Wi-Fi Direct", true),
    ETHERNET_TETHER("Раздача по кабелю", true),
    OTHER_TETHER("Раздача", true),
    LAN("Общая сеть Wi-Fi", false),
}

data class GatewayEndpoint(
    val host: String,
    val interfaceName: String,
    val kind: GatewayKind,
) {
    val downstream: Boolean get() = kind.downstream
}

/**
 * Находит адреса, на которых имеет смысл слушать входящие подключения.
 *
 * Интерфейс раздачи нельзя получить через [ConnectivityManager]: точка доступа и
 * USB-модем не представлены объектом `Network`, у них нет ни capabilities, ни
 * LinkProperties. Поэтому идём от обратного — перечисляем все интерфейсы ядра и
 * вычитаем те, которые система уже показала как обычные сети (Wi-Fi, сотовая связь,
 * VPN). Всё, что осталось с рабочим IPv4-адресом, и есть раздача.
 *
 * Ни root, ни дополнительных разрешений это не требует.
 */
object GatewayEndpoints {

    /**
     * Интерфейсы, которые не могут быть шлюзом ни при каких условиях: служебные,
     * туннельные и модемные. Особо важен `v4-` / `clat` — это 464XLAT, он поднимает
     * IPv4-адрес из 192.0.0.0/29 поверх сотового IPv6 и легко сходит за раздачу.
     */
    private val NEVER_GATEWAY = listOf(
        "lo", "dummy", "sit", "ip6tnl", "ip_vti", "ip6_vti", "tun", "tap",
        "ppp", "v4-", "clat", "rmnet", "ccmni", "wwan", "seth", "epdg", "ims",
        "aware_data", "nan", "ifb", "gre",
    )

    fun discover(context: Context): List<GatewayEndpoint> {
        val known = collectKnownInterfaces(context)
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val found = mutableListOf<GatewayEndpoint>()
        for (iface in interfaces) {
            val name = runCatching { iface.name }.getOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (isNeverGateway(name)) continue
            if (!isUp(iface)) continue
            val host = firstUsableIpv4(iface) ?: continue
            // Сотовая связь, Ethernet-аплинк и наш собственный tun — это выход в
            // интернет, а не сторона, с которой приходят клиенты.
            if (name in known.upstream) continue
            val kind = if (name in known.lan) GatewayKind.LAN else classifyDownstream(name)
            found += GatewayEndpoint(host = host, interfaceName = name, kind = kind)
        }
        // Раздача важнее общей сети: её показываем первой и на неё ориентируем портал.
        return found.sortedWith(compareBy({ if (it.downstream) 0 else 1 }, { it.interfaceName }))
    }

    /**
     * Построчный разбор того, что видит приложение и почему интерфейс отвергнут.
     * Пишется в лог при каждой смене набора адресов: имена интерфейсов задаёт
     * вендор, и без этого разбирать жалобу «раздача не появилась» невозможно.
     */
    fun diagnose(context: Context): String {
        val known = collectKnownInterfaces(context)
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrElse { return "перечислить интерфейсы не удалось: ${it.message}" }
        if (interfaces.isEmpty()) return "интерфейсы не перечислились (пустой список)"
        return interfaces.joinToString("; ") { iface ->
            val name = runCatching { iface.name }.getOrNull().orEmpty().ifEmpty { "?" }
            val ipv4 = firstUsableIpv4(iface)
            val verdict = when {
                isNeverGateway(name) -> "служебный"
                !isUp(iface) -> "выключен"
                ipv4 == null -> "без IPv4"
                name in known.upstream -> "аплинк"
                name in known.lan -> "общая сеть"
                else -> "раздача:${classifyDownstream(name)}"
            }
            "$name=${ipv4 ?: "-"}($verdict)"
        }
    }

    private class KnownInterfaces(
        val lan: Set<String>,
        val upstream: Set<String>,
    )

    /**
     * Аплинк — это сеть, у которой есть NET_CAPABILITY_INTERNET.
     *
     * Проверять просто «известна ли сеть ConnectivityManager» нельзя: система
     * регистрирует и сторону раздачи тоже. На Redmi Pad 2 Pro USB-модем виден как
     * обычная сеть с интерфейсом rndis0, и наивная проверка записывала его в
     * аплинки — то есть ровно тот интерфейс, ради которого всё и затевалось,
     * молча выпадал. Отличает их именно отсутствие выхода в интернет: сторона
     * раздачи никуда не ведёт, она только принимает клиентов.
     */
    private fun collectKnownInterfaces(context: Context): KnownInterfaces {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return KnownInterfaces(emptySet(), emptySet())
        val lan = mutableSetOf<String>()
        val upstream = mutableSetOf<String>()
        val networks = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
        for (network in networks) {
            val name = runCatching { manager.getLinkProperties(network)?.interfaceName }
                .getOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val isWifiStation = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (isWifiStation) lan += name else upstream += name
        }
        return KnownInterfaces(lan = lan, upstream = upstream)
    }

    internal fun isNeverGateway(name: String): Boolean {
        val normalized = name.lowercase(Locale.US)
        return NEVER_GATEWAY.any(normalized::startsWith)
    }

    private fun isUp(iface: NetworkInterface): Boolean {
        return runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
    }

    private fun firstUsableIpv4(iface: NetworkInterface): String? {
        val addresses = runCatching { iface.inetAddresses?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return addresses
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address ->
                !address.isLoopbackAddress &&
                    !address.isAnyLocalAddress &&
                    !address.isLinkLocalAddress &&
                    !isClatAddress(address)
            }
            ?.hostAddress
            ?.takeIf { it.isNotBlank() }
    }

    /** 192.0.0.0/29 — служебный диапазон 464XLAT, наружу он не смотрит. */
    internal fun isClatAddress(address: Inet4Address): Boolean {
        val raw = address.address ?: return false
        if (raw.size != 4) return false
        return raw[0].toInt() and 0xFF == 192 &&
            raw[1].toInt() and 0xFF == 0 &&
            raw[2].toInt() and 0xFF == 0 &&
            (raw[3].toInt() and 0xFF) < 8
    }

    internal fun classifyDownstream(name: String): GatewayKind {
        val normalized = name.lowercase(Locale.US)
        return when {
            normalized.startsWith("rndis") ||
                normalized.startsWith("usb") ||
                normalized.startsWith("ncm") -> GatewayKind.USB
            normalized.startsWith("bt-pan") || normalized.startsWith("bnep") -> GatewayKind.BLUETOOTH
            normalized.startsWith("p2p") -> GatewayKind.WIFI_DIRECT
            normalized.startsWith("ap") ||
                normalized.startsWith("softap") ||
                normalized.startsWith("swlan") ||
                normalized.startsWith("wlan") ||
                normalized.startsWith("wigig") -> GatewayKind.WIFI_AP
            normalized.startsWith("eth") -> GatewayKind.ETHERNET_TETHER
            else -> GatewayKind.OTHER_TETHER
        }
    }
}
