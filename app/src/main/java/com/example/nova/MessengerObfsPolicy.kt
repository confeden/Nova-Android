package com.example.nova

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

object MessengerObfsPolicy {

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PREFS_NAME = "nova_messenger_obfs_cache"
    private const val KEY_PACKAGES = "installed_messenger_packages"
    private const val KEY_UPDATED_AT = "installed_messenger_packages_updated_at"

    private val MESSENGER_PACKAGE_HINTS = listOf(
        "telegram",
        "whatsapp",
        "ayugram",
        "nagram",
        "nekogram",
        "kotatogram",
        "plusmessenger",
        "bgram",
        "graphmessenger",
        "vidogram",
    )

    private val TELEGRAM_PACKAGE_HINTS = listOf(
        "telegram",
        "ayugram",
        "nagram",
        "nekogram",
        "kotatogram",
        "plusmessenger",
        "bgram",
        "graphmessenger",
        "vidogram",
    )

    private val WHATSAPP_PACKAGE_HINTS = listOf(
        "whatsapp",
    )

    // Для chat-aware stealth мы сознательно не используем telegram/whatsapp host,
    // чтобы fake-template уходил в обычные web/CDN паттерны и не выглядел как мессенджер.
    private val TELEGRAM_CAMOUFLAGE_HOSTS = listOf(
        "www.google.com",
        "www.cloudflare.com",
        "github.com",
        "www.microsoft.com",
        "m.2gis.ru",
        "www.wikipedia.org",
    )

    private val WHATSAPP_CAMOUFLAGE_HOSTS = listOf(
        "i.ytimg.com",
        "www.youtube.com",
        "www.ozon.ru",
        "www.avito.ru",
        "www.rbc.ru",
        "m.yandex.ru",
    )

    private val GENERIC_MESSENGER_CAMOUFLAGE_HOSTS = listOf(
        "www.google.com",
        "www.cloudflare.com",
        "www.microsoft.com",
        "m.2gis.ru",
        "www.ozon.ru",
        "www.avito.ru",
        "www.rbc.ru",
        "yandex.ru",
    )
    private val KNOWN_MESSENGER_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "org.telegram.messenger.web",
        "nu.gpu.nagram",
        "nu.gpu.nagramx",
        "org.nekox.messenger",
        "nekox.messenger",
        "com.ayugram",
        "com.ayugram.messenger",
        "uz.unnarsx.cherrygram",
        "org.telegram.BifToGram",
        "org.grapheneos.apps.messaging",
        "com.whatsapp",
        "com.whatsapp.w4b",
    )

    @Volatile
    private var cachedInstalledPackages: Set<String> = emptySet()

    @Volatile
    private var cacheUpdatedAtMs: Long = 0L
    private val refreshInFlight = AtomicBoolean(false)

    fun isMessengerPackage(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        if (normalized.isBlank()) return false
        return MESSENGER_PACKAGE_HINTS.any { hint -> normalized.contains(hint) }
    }

    fun getInstalledMessengerPackages(context: Context): Set<String> {
        val nowMs = System.currentTimeMillis()
        val cached = cachedInstalledPackages
        if (cached.isNotEmpty() && nowMs - cacheUpdatedAtMs < CACHE_TTL_MS) {
            return cached
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val persistedUpdatedAtMs = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (persisted.isNotEmpty()) {
            cachedInstalledPackages = persisted
            cacheUpdatedAtMs = persistedUpdatedAtMs.takeIf { it > 0L } ?: nowMs
            if (nowMs - cacheUpdatedAtMs >= CACHE_TTL_MS) {
                scheduleAsyncRefresh(context)
            }
            return persisted
        }

        val quickDetected = detectKnownMessengerPackages(context)
        if (quickDetected.isNotEmpty()) {
            persistInstalledMessengerPackages(context, quickDetected, nowMs)
            cachedInstalledPackages = quickDetected
            cacheUpdatedAtMs = nowMs
            scheduleAsyncRefresh(context)
            return quickDetected
        }

        val refreshed = scanInstalledMessengerPackages(context)
        persistInstalledMessengerPackages(context, refreshed, nowMs)
        cachedInstalledPackages = refreshed
        cacheUpdatedAtMs = nowMs
        return refreshed
    }

    private fun detectKnownMessengerPackages(context: Context): Set<String> {
        val packageManager = context.packageManager
        val detected = linkedSetOf<String>()
        for (packageName in KNOWN_MESSENGER_PACKAGES) {
            val installed = runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }.isSuccess
            if (installed) {
                detected += packageName
            }
        }
        return detected
    }

    private fun scanInstalledMessengerPackages(context: Context): Set<String> {
        return runCatching {
            context.packageManager
                .getInstalledApplications(0)
                .asSequence()
                .mapNotNull { it.packageName?.trim() }
                .filter { it.isNotBlank() && isMessengerPackage(it) }
                .toSet()
        }.getOrElse { emptySet() }
    }

    private fun persistInstalledMessengerPackages(
        context: Context,
        packages: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PACKAGES, packages.toSet())
            .putLong(KEY_UPDATED_AT, nowMs)
            .apply()
    }

    private fun scheduleAsyncRefresh(context: Context) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                val refreshed = scanInstalledMessengerPackages(appContext)
                val nowMs = System.currentTimeMillis()
                persistInstalledMessengerPackages(appContext, refreshed, nowMs)
                cachedInstalledPackages = refreshed
                cacheUpdatedAtMs = nowMs
            } catch (_: Exception) {
            } finally {
                refreshInFlight.set(false)
            }
        }.apply {
            name = "NovaMessengerPackageRefresh"
            isDaemon = true
            start()
        }
    }

    fun pickCamouflageHost(context: Context, seed: String): String {
        val installed = getInstalledMessengerPackages(context)
        if (installed.isEmpty()) {
            return GENERIC_MESSENGER_CAMOUFLAGE_HOSTS.first()
        }
        val hasTelegramFamily = installed.any { packageName ->
            TELEGRAM_PACKAGE_HINTS.any(packageName::contains)
        }
        val hasWhatsappFamily = installed.any { packageName ->
            WHATSAPP_PACKAGE_HINTS.any(packageName::contains)
        }
        val hostPool = when {
            hasTelegramFamily && !hasWhatsappFamily -> TELEGRAM_CAMOUFLAGE_HOSTS
            hasWhatsappFamily && !hasTelegramFamily -> WHATSAPP_CAMOUFLAGE_HOSTS
            else -> GENERIC_MESSENGER_CAMOUFLAGE_HOSTS
        }
        val basis = buildString {
            append(seed.trim().lowercase())
            append('|')
            append(installed.sorted().joinToString(","))
        }
        val index = (((basis.hashCode().toLong() and Long.MAX_VALUE) % hostPool.size.toLong())).toInt()
        return hostPool[index]
    }
}
