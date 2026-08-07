package com.example.nova

import android.content.Context

object TrafficMaskCatalog {

    @Volatile
    private var cachedRussiaHosts: List<String>? = null
    @Volatile
    private var cachedGlobalHosts: List<String>? = null
    @Volatile
    private var cachedWhiteHosts: List<String>? = null

    fun getHosts(context: Context): List<String> {
        return getRussiaHosts(context)
    }

    fun getRussiaHosts(context: Context): List<String> {
        cachedRussiaHosts?.let { return it }
        val result = loadHosts(context, "traffic_mask_russia.sni")
        cachedRussiaHosts = result
        return result
    }

    fun getGlobalHosts(context: Context): List<String> {
        cachedGlobalHosts?.let { return it }
        val result = loadHosts(context, "traffic_mask_global.sni")
        cachedGlobalHosts = result
        return result
    }

    fun getWhiteHosts(context: Context): List<String> {
        cachedWhiteHosts?.let { return it }
        val result = loadHosts(context, "white.sni", fallbackToDefault = false)
        cachedWhiteHosts = result
        return result
    }

    private fun loadHosts(
        context: Context,
        assetName: String,
        fallbackToDefault: Boolean = true,
    ): List<String> {
        val loaded = try {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotBlank() }
                    .map { normalizeHost(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        return if (loaded.isNotEmpty()) {
            loaded
        } else if (fallbackToDefault) {
            listOf(DEFAULT_FALLBACK_HOST)
        } else {
            emptyList()
        }
    }

    private fun normalizeHost(raw: String): String {
        var host = raw.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim('.')
        if (host.isBlank()) return ""
        val valid = host.matches(Regex("^[a-z0-9.-]+$")) &&
            host.contains('.') &&
            !host.contains("..") &&
            !host.startsWith("-") &&
            !host.endsWith("-")
        return if (valid) host else ""
    }

    private const val DEFAULT_FALLBACK_HOST = "ads.max.ru"
}
