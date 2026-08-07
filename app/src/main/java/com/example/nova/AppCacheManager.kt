package com.example.nova

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class AppItem(
    val packageName: String,
    val label: String,
    var icon: Drawable? = null,
    var isSelected: Boolean = false
)

object AppCacheManager {
    private const val CACHE_SCHEMA_SALT = "include-system-apps-v2"

    private data class CachedEntry(
        val packageName: String,
        val label: String,
    )

    private val loaderDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaAppCache").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + loaderDispatcher)
    private val iconScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Any()
    private val iconCache = ConcurrentHashMap<String, Drawable.ConstantState?>()

    @Volatile
    private var cachedFingerprint = ""
    @Volatile
    private var cachedEntries: List<CachedEntry> = emptyList()

    fun prewarmAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            loadEntries(appContext)
        }
    }

    fun peekInstalledApps(context: Context, selectedApps: Set<String>): List<AppItem> {
        val appContext = context.applicationContext
        val entries = synchronized(cacheLock) {
            if (cachedEntries.isNotEmpty()) {
                cachedEntries
            } else {
                readDiskCache(appContext)?.second.orEmpty()
            }
        }
        if (entries.isEmpty()) return emptyList()
        return entries.map { entry ->
            AppItem(
                packageName = entry.packageName,
                label = entry.label,
                icon = iconCache[entry.packageName]?.newDrawable(appContext.resources),
                isSelected = selectedApps.contains(entry.packageName),
            )
        }
    }

    suspend fun getInstalledApps(context: Context, selectedApps: Set<String>): List<AppItem> {
        val appContext = context.applicationContext
        val entries = loadEntries(appContext)
        return entries.map { entry ->
            AppItem(
                packageName = entry.packageName,
                label = entry.label,
                icon = iconCache[entry.packageName]?.newDrawable(appContext.resources),
                isSelected = selectedApps.contains(entry.packageName),
            )
        }
    }

    fun bindIcon(imageView: ImageView, item: AppItem) {
        imageView.tag = item.packageName
        val appContext = imageView.context.applicationContext
        val cachedState = iconCache[item.packageName]
        if (item.icon != null) {
            imageView.setImageDrawable(item.icon)
            return
        }
        if (cachedState != null) {
            val drawable = cachedState.newDrawable(imageView.resources)
            item.icon = drawable
            imageView.setImageDrawable(drawable)
            return
        }

        imageView.setImageResource(android.R.drawable.sym_def_app_icon)
        iconScope.launch {
            val drawable = try {
                appContext.packageManager.getApplicationIcon(item.packageName)
            } catch (_: Exception) {
                null
            }
            drawable?.constantState?.let { iconCache[item.packageName] = it }
            withContext(Dispatchers.Main) {
                if (imageView.tag == item.packageName) {
                    item.icon = drawable
                    if (drawable != null) {
                        imageView.setImageDrawable(drawable)
                    } else {
                        imageView.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
            }
        }
    }

    fun clearCache(context: Context? = null) {
        synchronized(cacheLock) {
            cachedFingerprint = ""
            cachedEntries = emptyList()
            iconCache.clear()
        }
        context?.applicationContext?.let {
            runCatching { cacheFile(it).delete() }
        }
    }

    private suspend fun loadEntries(context: Context): List<CachedEntry> = withContext(loaderDispatcher) {
        synchronized(cacheLock) {
            val pm = context.packageManager
            val fingerprint = computeFingerprint(pm)
            if (cachedEntries.isNotEmpty() && cachedFingerprint == fingerprint) {
                return@synchronized cachedEntries
            }

            val diskCache = readDiskCache(context)
            if (diskCache != null && diskCache.first == fingerprint) {
                cachedFingerprint = fingerprint
                cachedEntries = diskCache.second
                return@synchronized cachedEntries
            }

            val entries = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .mapNotNull { appInfo ->
                    try {
                        CachedEntry(
                            packageName = appInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
                .toList()

            cachedFingerprint = fingerprint
            cachedEntries = entries
            writeDiskCache(context, fingerprint, entries)
            entries
        }
    }

    private fun computeFingerprint(pm: PackageManager): String {
        val packages = pm.getInstalledPackages(0)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CACHE_SCHEMA_SALT.toByteArray(Charsets.UTF_8))
        digest.update(0)
        packages
            .sortedBy { it.packageName }
            .forEach { pkg ->
                digest.update(pkg.packageName.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(pkg.lastUpdateTime.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(packageVersionCodeCompat(pkg).toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun packageVersionCodeCompat(pkg: PackageInfo): Long {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
    }

    private fun cacheFile(context: Context): File {
        return File(context.filesDir, CACHE_FILE_NAME)
    }

    private fun readDiskCache(context: Context): Pair<String, List<CachedEntry>>? {
        return try {
            val file = cacheFile(context)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val fingerprint = root.optString("fingerprint")
            if (fingerprint.isBlank()) return null
            val appsJson = root.optJSONArray("apps") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until appsJson.length()) {
                    val entry = appsJson.optJSONObject(i) ?: continue
                    val packageName = entry.optString("package")
                    val label = entry.optString("label")
                    if (packageName.isNotBlank() && label.isNotBlank()) {
                        add(CachedEntry(packageName, label))
                    }
                }
            }
            fingerprint to entries
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(context: Context, fingerprint: String, entries: List<CachedEntry>) {
        runCatching {
            val appsJson = JSONArray()
            for (entry in entries) {
                appsJson.put(
                    JSONObject().apply {
                        put("package", entry.packageName)
                        put("label", entry.label)
                    }
                )
            }
            val root = JSONObject().apply {
                put("fingerprint", fingerprint)
                put("apps", appsJson)
            }
            cacheFile(context).writeText(root.toString())
        }
    }

    private const val CACHE_FILE_NAME = "installed_apps_cache.json"
}


