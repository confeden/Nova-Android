package com.example.nova

object LocalDnsPolicy {

    const val LOCAL_PROXY_IPV4 = "10.255.255.53"
    const val LOCAL_PROXY_IPV6 = "fd53:53::53"

    private val MEDIA_ADBLOCK_PACKAGE_HINTS = listOf(
        "youtube",
        "twitch",
    )

    private val MEDIA_ADBLOCK_DOMAIN_SUFFIXES = listOf(
        "youtube.com",
        "youtu.be",
        "youtubei.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "ggpht.com",
        "twitch.tv",
        "ttvnw.net",
        "jtvnw.net",
        "twitchcdn.net",
        "ext-twitch.tv",
    )

    sealed class Decision {
        data object Disabled : Decision()
        data object WaitingForSelection : Decision()
        data class MediaOnlyDirect(val selectedApps: List<String>) : Decision()
        data class MixedSelectionRequiresProxy(
            val matchedApps: List<String>,
            val blockingApps: List<String>,
        ) : Decision()
    }

    fun resolve(splitMode: Int, selectedApps: Collection<String>): Decision {
        if (splitMode != 1) return Decision.Disabled
        val normalizedApps = selectedApps
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedApps.isEmpty()) return Decision.WaitingForSelection

        val matchedApps = normalizedApps.filter(::isMediaAdBlockPackage)
        val blockingApps = normalizedApps - matchedApps.toSet()
        return if (blockingApps.isEmpty()) {
            Decision.MediaOnlyDirect(normalizedApps)
        } else {
            Decision.MixedSelectionRequiresProxy(
                matchedApps = matchedApps,
                blockingApps = blockingApps,
            )
        }
    }

    fun isActive(decision: Decision): Boolean = decision is Decision.MediaOnlyDirect

    fun summary(decision: Decision): String {
        return when (decision) {
            Decision.Disabled -> ""
            Decision.WaitingForSelection ->
                "Выберите YouTube или Twitch, чтобы Nova использовала DNS AdGuard No Ads для этих приложений"
            is Decision.MediaOnlyDirect -> {
                val prettyApps = decision.selectedApps.map(::toDisplayName).distinct()
                val appSummary = when (prettyApps.size) {
                    0 -> "медиа-приложений"
                    1 -> prettyApps.first()
                    2 -> prettyApps.joinToString(" и ")
                    else -> "${prettyApps.size} приложений"
                }
                "Для $appSummary будет использован DNS AdGuard No Ads. " +
                    "Если он недоступен на текущей сети, Nova автоматически вернётся к обычному DNS."
            }
            is Decision.MixedSelectionRequiresProxy ->
                "Отдельный DNS сейчас работает, когда через VPN идут только YouTube или Twitch. " +
                    "Если вместе с ними выбраны и другие приложения, Nova использует обычный DNS."
        }
    }

    fun isMediaAdBlockPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isBlank()) return false
        return MEDIA_ADBLOCK_PACKAGE_HINTS.any { hint -> normalized.contains(hint) }
    }

    fun getMediaDomainSuffixes(): List<String> = MEDIA_ADBLOCK_DOMAIN_SUFFIXES

    fun toDisplayName(packageName: String): String {
        val normalized = packageName.trim().lowercase()
        return when {
            normalized.contains("youtube.music") || normalized.contains("ytmusic") -> "YouTube Music"
            normalized.contains("youtube") -> "YouTube"
            normalized.contains("twitch") -> "Twitch"
            else -> packageName.trim()
        }
    }
}
