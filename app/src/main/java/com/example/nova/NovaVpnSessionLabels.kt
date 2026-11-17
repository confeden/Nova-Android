package com.example.nova

/** Session labels remain available on Android versions that hide the VPN owner UID. */
internal object NovaVpnSessionLabels {
    private val session = Regex(
        "(?<![A-Za-z0-9_-])(Nova(?:Opera|Vless|List|Dns|Tor)?VPN)(?:-stop)?(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )

    fun backendFor(label: String): String? = when (session.find(label)?.groupValues?.get(1)?.lowercase()) {
        "novavpn" -> NovaVpnService.BACKEND_WARP
        "novaoperavpn" -> NovaVpnService.BACKEND_OPERA
        "novavlessvpn" -> NovaVpnService.BACKEND_VLESS
        "novalistvpn" -> NovaVpnService.BACKEND_NOVA
        "novadnsvpn" -> NovaVpnService.BACKEND_DNS
        "novatorvpn" -> NovaVpnService.BACKEND_TOR
        else -> null
    }

    fun isNova(label: String): Boolean = backendFor(label) != null
}
