package com.example.nova

import android.content.Context

object TrafficCamouflagePolicy {

    private const val CACHE_TTL_MS = 60_000L

    private enum class Category(val hosts: List<String>, val priority: Int) {
        MEDIA(
            hosts = listOf(
                "www.youtube.com",
                "i.ytimg.com",
                "www.googlevideo.com",
                "static.twitchcdn.net",
                "www.twitch.tv",
            ),
            priority = 5,
        ),
        BROWSER(
            hosts = listOf(
                "www.google.com",
                "www.cloudflare.com",
                "github.com",
                "www.microsoft.com",
                "www.wikipedia.org",
            ),
            priority = 4,
        ),
        MAPS(
            hosts = listOf(
                "m.2gis.ru",
                "yandex.ru",
                "maps.googleapis.com",
                "api-maps.yandex.ru",
            ),
            priority = 3,
        ),
        SHOPPING(
            hosts = listOf(
                "www.ozon.ru",
                "bank.wildberries.ru",
                "www.avito.ru",
                "market.yandex.ru",
            ),
            priority = 2,
        ),
        DEFAULT(
            hosts = listOf(
                "www.google.com",
                "www.cloudflare.com",
                "www.microsoft.com",
                "www.yandex.ru",
            ),
            priority = 1,
        ),
    }

    private val categoryHints = linkedMapOf(
        Category.MEDIA to listOf(
            "youtube",
            "ytmusic",
            "googlevideo",
            "ytimg",
            "twitch",
            "ttvnw",
        ),
        Category.BROWSER to listOf(
            "chrome",
            "firefox",
            "opera",
            "browser",
            "edge",
            "vivaldi",
            "brave",
            "duckduckgo",
            "samsung.internet",
        ),
        Category.MAPS to listOf(
            "2gis",
            "maps",
            "yandexmaps",
            "navigator",
            "waze",
        ),
        Category.SHOPPING to listOf(
            "ozon",
            "wildberries",
            "avito",
            "market",
            "megamarket",
            "aliexpress",
        ),
    )

    @Volatile
    private var cachedInterestingPackages: Set<String> = emptySet()

    @Volatile
    private var cacheUpdatedAtMs: Long = 0L

    private fun getInstalledInterestingPackages(context: Context): Set<String> {
        val nowMs = System.currentTimeMillis()
        val cached = cachedInterestingPackages
        if (cached.isNotEmpty() && nowMs - cacheUpdatedAtMs < CACHE_TTL_MS) {
            return cached
        }
        val refreshed = runCatching {
            context.packageManager
                .getInstalledApplications(0)
                .asSequence()
                .mapNotNull { it.packageName?.trim()?.lowercase() }
                .filter { packageName ->
                    categoryHints.values.any { hints -> hints.any(packageName::contains) }
                }
                .toSet()
        }.getOrElse { emptySet() }
        cachedInterestingPackages = refreshed
        cacheUpdatedAtMs = nowMs
        return refreshed
    }

    private fun packagesRoutedViaVpn(context: Context, clientData: ClientData): Set<String> {
        val interestingPackages = getInstalledInterestingPackages(context)
        if (interestingPackages.isEmpty()) return emptySet()

        val selectedApps = clientData.getSplitApps()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        return when (clientData.getSplitMode()) {
            1 -> interestingPackages.filterTo(linkedSetOf()) { it in selectedApps }
            2 -> interestingPackages.filterTo(linkedSetOf()) { it !in selectedApps }
            else -> interestingPackages
        }
    }

    private fun categoryForPackage(packageName: String): Category {
        val normalized = packageName.trim().lowercase()
        for ((category, hints) in categoryHints) {
            if (hints.any(normalized::contains)) return category
        }
        return Category.DEFAULT
    }

    fun pickCamouflageHost(
        context: Context,
        clientData: ClientData,
        seed: String,
    ): String {
        val packages = packagesRoutedViaVpn(context, clientData)
        if (packages.isEmpty()) {
            return Category.DEFAULT.hosts.first()
        }

        val category = packages
            .groupingBy(::categoryForPackage)
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Category, Int>> { it.value }
                    .thenByDescending { it.key.priority }
            )
            .firstOrNull()
            ?.key
            ?: Category.DEFAULT

        val hostPool = category.hosts.ifEmpty { Category.DEFAULT.hosts }
        val basis = buildString {
            append(seed.trim().lowercase())
            append('|')
            append(category.name)
            append('|')
            append(packages.sorted().joinToString(","))
        }
        val index = (((basis.hashCode().toLong() and Long.MAX_VALUE) % hostPool.size.toLong())).toInt()
        return hostPool[index]
    }
}
