package com.example.nova

import android.app.Notification
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ApkUpdateMetadata(
    val version: String,
    val url: String,
    val sha256: String,
)

data class UpdateDownloadProgress(
    val state: State,
    val version: String,
    val progressPercent: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val statusLabel: String,
) {
    enum class State {
        IDLE,
        CHECKING,
        DOWNLOADING,
        PAUSED,
        READY,
        FAILED,
    }

    val isVisible: Boolean
        get() = state != State.IDLE

    val isIndeterminate: Boolean
        get() = state == State.CHECKING || (state == State.DOWNLOADING && totalBytes <= 0L)
}

data class ManualUpdateCheckResult(
    val kind: Kind,
    val version: String = "",
    val message: String = "",
) {
    enum class Kind {
        CHECKING,
        NO_UPDATE,
        DOWNLOAD_STARTED,
        DOWNLOAD_IN_PROGRESS,
        READY,
        FAILED,
    }
}

object AppUpdateManager {

    private val UPDATE_URLS = listOf(
        "https://raw.githubusercontent.com/confeden/nova_updates/main/apk_version.json",
        "https://confeden.github.io/nova_updates/apk_version.json",
    )
    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/confeden/Nova-Android/releases/latest"
    private const val PERIODIC_WORK_NAME = "nova_update_check_periodic"
    private const val REPAIR_WORK_NAME = "nova_update_repair"
    private const val CHANNEL_ID = "nova_updates"
    private const val CHANNEL_ID_UPDATED = "nova_update_installed"
    private const val NOTIFICATION_AVAILABLE_ID = 2201
    private const val NOTIFICATION_READY_ID = 2202
    private const val MIN_LAUNCH_CHECK_INTERVAL_MS = 60L * 60L * 1000L
    private const val DOWNLOAD_ATTEMPTS = 4
    private const val DOWNLOAD_RETRY_DELAY_MS = 1500L
    private const val NOTIFICATION_UPDATED_ID = 2203
    private val updateCheckInProgress = AtomicBoolean(false)
    private val installSessionInProgress = AtomicBoolean(false)

    const val ACTION_UPDATE_STATE_CHANGED = "com.example.nova.action.UPDATE_STATE_CHANGED"
    const val ACTION_DOWNLOAD_UPDATE = "com.example.nova.action.DOWNLOAD_UPDATE"
    const val ACTION_DISMISS_UPDATE = "com.example.nova.action.DISMISS_UPDATE"
    const val ACTION_INSTALL_UPDATE = "com.example.nova.action.INSTALL_UPDATE"
    const val ACTION_INSTALL_COMMIT_STATUS = "com.example.nova.action.INSTALL_COMMIT_STATUS"
    const val EXTRA_VERSION = "extra_version"
    const val EXTRA_URL = "extra_url"
    const val EXTRA_SHA256 = "extra_sha256"
    const val EXTRA_PATH = "extra_path"
    const val EXTRA_LAUNCHED_AFTER_UPDATE = "extra_launched_after_update"
    private const val EXTRA_INSTALL_SESSION_ID = "extra_install_session_id"
    private const val EXTRA_INSTALL_REQUEST_CODE = 6100

    private data class RemoteApkInfo(
        val totalBytes: Long,
        val supportsRange: Boolean,
    )

    private data class ParsedDownloadedApk(
        val packageName: String,
        val versionName: String,
    )

    private data class DownloadedApkValidationResult(
        val parsedApk: ParsedDownloadedApk? = null,
        val failureReason: String = "",
        val actualSha256: String = "",
    )

    /**
     * Итог одной попытки скачивания. `canResume` — можно ли продолжить с того места,
     * докуда дошли: после 404 продолжать нечего, после обрыва связи — есть что.
     */
    private data class DownloadAttemptOutcome(
        val completed: Boolean,
        val canResume: Boolean,
        val reason: String,
    )

    fun hasReadyDownloadedUpdate(context: Context): Boolean {
        return getReadyDownloadedUpdate(context) != null
    }

    fun getReadyDownloadedVersion(context: Context): String {
        return getReadyDownloadedUpdate(context)?.version.orEmpty()
    }

    fun syncSchedule(context: Context) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        getReadyDownloadedUpdate(appContext, clientData)
        cleanupStaleUpdateDownloads(appContext, keepDownloadId = clientData.getUpdateDownloadId())
        val workManager = WorkManager.getInstance(appContext)
        if (clientData.getAutoAppUpdate()) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(8, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_AVAILABLE_ID)
        }
    }

    fun isCheckInProgress(): Boolean = updateCheckInProgress.get()

    fun enqueueImmediateCheck(context: Context, reason: String) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        if (!clientData.getAutoAppUpdate()) return
        getReadyDownloadedUpdate(appContext, clientData)
        val now = System.currentTimeMillis()
        if (reason == "app-launch" && now - clientData.getLastUpdateCheckAt() < MIN_LAUNCH_CHECK_INTERVAL_MS) {
            return
        }
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "nova_update_check_immediate_$reason",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun performUpdateCheck(context: Context): Boolean {
        if (!updateCheckInProgress.compareAndSet(false, true)) {
            LogManager.log("Проверка обновлений уже выполняется. Повторный запуск пропускаем.")
            return false
        }
        try {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        if (!clientData.getAutoAppUpdate()) return false
        getReadyDownloadedUpdate(appContext, clientData)
        val metadata = fetchMetadata(appContext) ?: return false
        clientData.setLastUpdateCheckAt(System.currentTimeMillis())
        clientData.setLastUpdateVersion(metadata.version)
        clientData.setLastUpdateUrl(metadata.url)
        clientData.setLastUpdateSha256(metadata.sha256)
        val installedVersion = getInstalledVersion(appContext)
        val wifiTransport = isWifiTransport(appContext)
        reconcileDownloadedUpdateTarget(appContext, clientData, metadata.version)
        LogManager.log(
            "Проверка обновлений: установлена $installedVersion, доступна ${metadata.version}, " +
                "underlyingWiFi=$wifiTransport, vpnState=${clientData.getServiceState()}."
        )
        if (!isNewerVersion(metadata.version, installedVersion)) {
            LogManager.log("Новая версия не требуется: ${metadata.version} не новее $installedVersion.")
            return false
        }
        return if (wifiTransport) {
            // При активном VPN DownloadManager может видеть только VPN-сеть без флага NOT_METERED.
            // Решение о "только по Wi‑Fi" принимаем сами по underlying transport.
            enqueueDownload(appContext, metadata, allowMetered = true, automatic = true)
        } else {
            showAvailableNotification(appContext, metadata)
            true
        }
        } finally {
            updateCheckInProgress.set(false)
        }
    }

    fun performManualUpdateCheck(context: Context): ManualUpdateCheckResult {
        if (!updateCheckInProgress.compareAndSet(false, true)) {
            return ManualUpdateCheckResult(
                kind = ManualUpdateCheckResult.Kind.CHECKING,
                message = "Проверка уже выполняется",
            )
        }
        try {
            val appContext = context.applicationContext
            val clientData = ClientData(appContext)
            if (clientData.isUpdateRepairInProgress()) {
                val version = clientData.getUpdateRepairVersion().ifBlank { clientData.getLastUpdateVersion() }
                return ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.DOWNLOAD_IN_PROGRESS,
                    version = version,
                    message = "Восстанавливаем загрузку $version",
                )
            }
            getReadyDownloadedUpdate(appContext, clientData)?.let { ready ->
                return ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.READY,
                    version = ready.version,
                    message = "Обновление ${ready.version} уже скачано",
                )
            }

            val metadata = fetchMetadata(appContext)
                ?: return ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.FAILED,
                    message = "Не удалось проверить обновление",
                )

            clientData.setLastUpdateCheckAt(System.currentTimeMillis())
            clientData.setLastUpdateVersion(metadata.version)
            clientData.setLastUpdateUrl(metadata.url)
            clientData.setLastUpdateSha256(metadata.sha256)

            val installedVersion = getInstalledVersion(appContext)
            reconcileDownloadedUpdateTarget(appContext, clientData, metadata.version)

            if (!isNewerVersion(metadata.version, installedVersion)) {
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_AVAILABLE_ID)
                return ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.NO_UPDATE,
                    version = installedVersion,
                    message = "Установлена последняя версия",
                )
            }

            if (hasInFlightDownload(appContext, clientData, metadata.version)) {
                broadcastUpdateStateChanged(appContext)
                return ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.DOWNLOAD_IN_PROGRESS,
                    version = metadata.version,
                    message = "Загрузка ${metadata.version} уже выполняется",
                )
            }

            val started = enqueueDownload(
                context = appContext,
                metadata = metadata,
                allowMetered = true,
                automatic = false,
            )
            return if (started) {
                ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.DOWNLOAD_STARTED,
                    version = metadata.version,
                    message = "Начали загрузку ${metadata.version}",
                )
            } else {
                ManualUpdateCheckResult(
                    kind = ManualUpdateCheckResult.Kind.FAILED,
                    version = metadata.version,
                    message = "Не удалось запустить загрузку",
                )
            }
        } finally {
            updateCheckInProgress.set(false)
        }
    }

    fun getDownloadProgress(context: Context): UpdateDownloadProgress {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        val ready = getReadyDownloadedUpdate(appContext, clientData)
        if (ready != null) {
            return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.READY,
                version = ready.version,
                progressPercent = 100,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusLabel = "Скачано обновление ${ready.version}",
            )
        }

        if (clientData.isUpdateRepairInProgress()) {
            val version = clientData.getUpdateRepairVersion().ifBlank { clientData.getLastUpdateVersion() }
            val downloadedBytes = clientData.getUpdateRepairDownloadedBytes()
            val totalBytes = clientData.getUpdateRepairTotalBytes()
            val progressPercent = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.DOWNLOADING,
                version = version,
                progressPercent = progressPercent,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                statusLabel = clientData.getUpdateRepairStatus().ifBlank {
                    if (version.isNotBlank()) {
                        "Восстанавливаем $version..."
                    } else {
                        "Восстанавливаем обновление..."
                    }
                },
            )
        }

        val repairStatus = clientData.getUpdateRepairStatus()
        if (repairStatus.isNotBlank()) {
            return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.FAILED,
                version = clientData.getUpdateRepairVersion().ifBlank { clientData.getLastUpdateVersion() },
                progressPercent = 0,
                downloadedBytes = clientData.getUpdateRepairDownloadedBytes(),
                totalBytes = clientData.getUpdateRepairTotalBytes(),
                statusLabel = repairStatus,
            )
        }

        if (updateCheckInProgress.get()) {
            return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.CHECKING,
                version = clientData.getLastUpdateVersion(),
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusLabel = "Проверяем наличие новой версии...",
            )
        }

        val downloadId = clientData.getUpdateDownloadId()
        if (downloadId <= 0L) {
            return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.IDLE,
                version = "",
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusLabel = "",
            )
        }

        val manager = appContext.getSystemService(DownloadManager::class.java)
            ?: return UpdateDownloadProgress(
                state = UpdateDownloadProgress.State.IDLE,
                version = "",
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = 0L,
                statusLabel = "",
            )

        val storedVersion = clientData.getDownloadedApkVersion().trim()
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return UpdateDownloadProgress(
                    state = UpdateDownloadProgress.State.IDLE,
                    version = "",
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusLabel = "",
                )
            }

            val status = cursor.getIntSafe(DownloadManager.COLUMN_STATUS)
            val downloadedBytes = cursor.getLongSafe(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalBytes = cursor.getLongSafe(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val progressPercent = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            val version = storedVersion.ifBlank { clientData.getLastUpdateVersion() }

            return when (status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING -> UpdateDownloadProgress(
                    state = UpdateDownloadProgress.State.DOWNLOADING,
                    version = version,
                    progressPercent = progressPercent,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    statusLabel = if (version.isNotBlank()) {
                        "Скачиваем $version: ${progressPercent.coerceAtLeast(0)}%"
                    } else {
                        "Скачиваем обновление..."
                    },
                )
                DownloadManager.STATUS_PAUSED -> UpdateDownloadProgress(
                    state = UpdateDownloadProgress.State.PAUSED,
                    version = version,
                    progressPercent = progressPercent,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    statusLabel = if (version.isNotBlank()) {
                        "Загрузка $version приостановлена, ждём сеть..."
                    } else {
                        "Загрузка приостановлена, ждём сеть..."
                    },
                )
                DownloadManager.STATUS_FAILED -> UpdateDownloadProgress(
                    state = UpdateDownloadProgress.State.FAILED,
                    version = version,
                    progressPercent = progressPercent,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    statusLabel = if (version.isNotBlank()) {
                        "Не удалось скачать $version"
                    } else {
                        "Не удалось скачать обновление"
                    },
                )
                else -> UpdateDownloadProgress(
                    state = UpdateDownloadProgress.State.IDLE,
                    version = "",
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    statusLabel = "",
                )
            }
        }

        return UpdateDownloadProgress(
            state = UpdateDownloadProgress.State.IDLE,
            version = "",
            progressPercent = 0,
            downloadedBytes = 0L,
            totalBytes = 0L,
            statusLabel = "",
        )
    }

    fun handleAction(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_DOWNLOAD_UPDATE -> {
                val metadata = ApkUpdateMetadata(
                    version = intent.getStringExtra(EXTRA_VERSION).orEmpty(),
                    url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                    sha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty(),
                )
                if (metadata.version.isNotBlank() && metadata.url.isNotBlank()) {
                    enqueueDownload(appContext, metadata, allowMetered = true, automatic = false)
                }
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_AVAILABLE_ID)
            }
            ACTION_DISMISS_UPDATE -> {
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_AVAILABLE_ID)
            }
            ACTION_INSTALL_UPDATE -> {
                launchInstaller(appContext)
            }
            ACTION_INSTALL_COMMIT_STATUS -> {
                handleInstallCommitStatus(appContext, intent)
            }
        }
    }

    fun handleDownloadComplete(context: Context, downloadId: Long) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        if (downloadId <= 0L || downloadId != clientData.getUpdateDownloadId()) return

        val downloadManager = appContext.getSystemService(DownloadManager::class.java) ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val status = cursor.getIntSafe(DownloadManager.COLUMN_STATUS)
            if (status == DownloadManager.STATUS_FAILED) {
                // DownloadManager сдался. Раньше на этом всё и заканчивалось: кнопка
                // молчала, состояние оставалось с чужим downloadId, и следующая проверка
                // считала загрузку живой. Дальше качаем сами — с докачкой и повторами.
                val reason = cursor.getIntSafe(DownloadManager.COLUMN_REASON)
                val partialPath = clientData.getDownloadedApkPath()
                enqueueRepairForInvalidApk(
                    context = appContext,
                    clientData = clientData,
                    path = partialPath,
                    reason = "DownloadManager не смог скачать обновление (причина $reason).",
                )
                return
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) return
            val localUri = cursor.getStringSafe(DownloadManager.COLUMN_LOCAL_URI).orEmpty()
            val existingPath = clientData.getDownloadedApkPath()
            val path = when {
                localUri.startsWith("file://") -> Uri.parse(localUri).path
                existingPath.isNotBlank() -> existingPath
                else -> null
            }.orEmpty()
            if (path.isBlank()) return
            val expectedVersion = clientData.getDownloadedApkVersion().ifBlank { clientData.getLastUpdateVersion() }
            val validation = validateDownloadedApk(
                context = appContext,
                apkFile = File(path),
                expectedVersion = expectedVersion,
                expectedSha256 = clientData.getLastUpdateSha256(),
            )
            val parsedApk = validation.parsedApk
            if (parsedApk == null) {
                val repairStarted = enqueueRepairForInvalidApk(
                    context = appContext,
                    clientData = clientData,
                    path = path,
                    reason = validation.failureReason.ifBlank {
                        "Скачанный APK обновления повреждён или относится к другому пакету."
                    },
                )
                if (!repairStarted) {
                    discardInvalidDownloadedApkState(
                        context = appContext,
                        clientData = clientData,
                        path = path,
                        reason = validation.failureReason.ifBlank {
                            "Скачанный APK обновления повреждён или относится к другому пакету. Сбрасываем ready-state."
                        },
                    )
                }
                return
            }
            clientData.setDownloadedApkPath(path)
            clientData.setDownloadedApkVersion(parsedApk.versionName)
            clientData.clearUpdateRepairState()
            cleanupStaleUpdateDownloads(appContext, keepDownloadId = downloadId)
            cleanupDuplicateApkFiles(path)
            if (ClientData(appContext).getServiceState() == NovaVpnService.STATE_STOPPED) {
                showInstallReadyNotification(appContext, parsedApk.versionName)
            } else {
                refreshVpnForegroundNotification(appContext)
            }
            broadcastUpdateStateChanged(appContext)
        }
    }

    private fun fetchMetadata(context: Context): ApkUpdateMetadata? {
        for (updateUrl in UPDATE_URLS) {
            try {
                val connection = URL(updateUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "NovaAndroidUpdater/1.12")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val metadata = ApkUpdateMetadata(
                    version = json.optString("version").trim(),
                    url = json.optString("url").trim(),
                    sha256 = json.optString("sha256").trim(),
                ).takeIf { it.version.isNotBlank() && it.url.isNotBlank() }
                if (metadata != null) {
                    LogManager.log("Метаданные обновления получены из: $updateUrl")
                    return metadata
                }
            } catch (_: Exception) {
                continue
            }
        }
        return fetchLatestGithubRelease()
    }

    /**
     * Спрашивает последний релиз публичного репозитория напрямую.
     *
     * Запасной путь к `apk_version.json`: репозиторий исходников публичный, и релиз
     * появляется в нём раньше, чем кто-либо обновит ленту, — а если лента отстанет
     * или окажется недоступна, обновление иначе не найдётся вовсе.
     *
     * Контрольной суммы у релиза нет, и это осознанный размен: без неё проверка
     * скачанного APK опирается на имя пакета, версию и подпись самого Android при
     * установке. Лента с `sha256` остаётся первой по очереди именно поэтому.
     */
    private fun fetchLatestGithubRelease(): ApkUpdateMetadata? {
        return try {
            val connection = URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroidUpdater/1.12")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name").trim()
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    apkUrl = asset.optString("browser_download_url").trim()
                    if (apkUrl.isNotBlank()) break
                }
            }
            if (version.isBlank() || apkUrl.isBlank()) return null
            LogManager.log("Метаданные обновления взяты из релизов GitHub: $version")
            ApkUpdateMetadata(version = version, url = apkUrl, sha256 = "")
        } catch (error: Exception) {
            LogManager.log("Релизы GitHub недоступны: ${error.message}")
            null
        }
    }

    private fun enqueueDownload(
        context: Context,
        metadata: ApkUpdateMetadata,
        allowMetered: Boolean,
        automatic: Boolean,
    ): Boolean {
        val clientData = ClientData(context)
        if (getReadyDownloadedUpdate(context, clientData)?.version == metadata.version) {
            if (clientData.getServiceState() == NovaVpnService.STATE_STOPPED) {
                showInstallReadyNotification(context, metadata.version)
            } else {
                refreshVpnForegroundNotification(context)
            }
            broadcastUpdateStateChanged(context)
            return true
        }

        if (clientData.isUpdateRepairInProgress() && clientData.getUpdateRepairVersion() == metadata.version) {
            LogManager.log("Обновление ${metadata.version} сейчас восстанавливается. Повторную загрузку не запускаем.")
            broadcastUpdateStateChanged(context)
            return true
        }

        if (hasInFlightDownload(context, clientData, metadata.version)) {
            LogManager.log("Обновление ${metadata.version} уже скачивается. Повторную загрузку не создаём.")
            return true
        }

        NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
        clientData.clearUpdateRepairState()
        discardOlderDownloadedUpdate(context, clientData, metadata.version)
        cleanupStaleUpdateDownloads(context, keepDownloadId = -1L)

        val destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        destinationDir.mkdirs()
        val destinationFile = File(destinationDir, "Nova-${metadata.version}.apk")
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(metadata.url))
            .setTitle("Nova ${metadata.version}")
            .setDescription(
                if (automatic) "Загружаем обновление Nova по Wi‑Fi"
                else "Доступна новая версия Nova"
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(destinationFile))

        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val downloadId = manager.enqueue(request)
        LogManager.log(
            "Запустили загрузку обновления ${metadata.version}: automatic=$automatic, " +
                "allowMetered=$allowMetered, id=$downloadId"
        )
        clientData.setUpdateDownloadId(downloadId)
        clientData.setDownloadedApkPath(destinationFile.absolutePath)
        clientData.setDownloadedApkVersion(metadata.version)
        clientData.setLastUpdateVersion(metadata.version)
        clientData.setLastUpdateUrl(metadata.url)
        clientData.setLastUpdateSha256(metadata.sha256)
        broadcastUpdateStateChanged(context)
        return true
    }

    private fun showAvailableNotification(context: Context, metadata: ApkUpdateMetadata) {
        if (!ensureNotificationChannel(context) || !canNotify(context)) return
        val openAppIntent = buildOpenAppPendingIntent(context, 5006)
        val downloadIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_DOWNLOAD_UPDATE
            putExtra(EXTRA_VERSION, metadata.version)
            putExtra(EXTRA_URL, metadata.url)
            putExtra(EXTRA_SHA256, metadata.sha256)
        }
        val dismissIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_DISMISS_UPDATE
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_nova)
            .setContentTitle("Доступна новая версия Nova")
            .setContentText("Версия ${metadata.version}. По мобильной сети загрузка только вручную.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Версия ${metadata.version}. По мобильной сети загрузка только вручную.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(
                0,
                "Скачать",
                PendingIntent.getBroadcast(
                    context,
                    5001,
                    downloadIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                0,
                "Отмена",
                PendingIntent.getBroadcast(
                    context,
                    5002,
                    dismissIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_AVAILABLE_ID, notification)
    }

    private fun showInstallReadyNotification(context: Context, version: String) {
        if (!ensureNotificationChannel(context) || !canNotify(context)) return
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
        val openAppIntent = buildOpenAppPendingIntent(context, 5007)
        val installIntent = buildInstallPendingIntent(context, 5003)
        val customView = buildReadyUpdateRemoteViews(context, version, installIntent, openAppIntent)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_nova)
            .setContentTitle("Обновление Nova загружено")
            .setContentText("Версия $version готова к установке.")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_READY_ID, notification)
    }

    /**
     * Возвращает человека в приложение сразу после того, как оно обновилось само.
     *
     * Своя установка убивает процесс, поэтому обычный «продолжим после commit» тут не
     * работает: результат приходит уже в новый процесс, в фон. Пробуем поднять экран
     * напрямую — на старых Android это просто срабатывает, — и одновременно вешаем
     * уведомление «Открыть». Начиная с Android 10 запуск экрана из фона запрещён и
     * запрещён молча: система не бросает исключение, окно просто не появляется. Поэтому
     * уведомление не запасной вариант «на всякий случай», а единственный надёжный путь
     * на новых версиях; [cancelUpdatedNotification] снимает его, если экран всё-таки
     * открылся, и лишнего человек не увидит.
     */
    fun onPackageReplaced(context: Context) {
        val appContext = context.applicationContext
        val version = getInstalledVersion(appContext)
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_READY_ID)
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_AVAILABLE_ID)

        showUpdatedNotification(appContext, version)

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(EXTRA_LAUNCHED_AFTER_UPDATE, true)
        }
        // `startActivity` без исключения ещё не значит, что экран открылся: замерено на
        // Pixel 4a, система пишет своё «Abort background activity starts» и возвращает
        // управление как ни в чём не бывало. Поэтому здесь не отчитываемся об успехе —
        // об успехе отчитается сама MainActivity, сняв уведомление.
        runCatching { appContext.startActivity(launchIntent) }
            .onFailure { error ->
                LogManager.log("Nova обновлена до $version: запрос на открытие экрана отклонён: ${error.message}")
            }
        LogManager.log(
            "Nova обновлена до $version. Просим систему открыть экран; уведомление «Открыть» оставлено — " +
                "с Android 10 запуск экрана из фона может быть отклонён молча."
        )
    }

    private fun showUpdatedNotification(context: Context, version: String) {
        if (!ensureUpdatedChannel(context) || !canNotify(context)) return
        val openAppIntent = buildOpenAppPendingIntent(context, 5008)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPDATED)
            .setSmallIcon(R.drawable.ic_qs_nova)
            .setContentTitle("Nova обновлена до v$version")
            .setContentText("Нажми, чтобы открыть приложение")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setFullScreenIntent(openAppIntent, true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_UPDATED_ID, notification)
    }

    /** Снимает уведомление «Nova обновлена», когда экран уже открыт. */
    fun cancelUpdatedNotification(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_UPDATED_ID)
    }

    fun buildForegroundVpnNotification(
        context: Context,
        channelId: String,
        subtitle: String = "",
    ): Notification {
        val readyVersion = getReadyDownloadedVersion(context)
        val openAppIntent = buildOpenAppPendingIntent(context, 5005)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_qs_nova)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent)

        if (readyVersion.isNotBlank()) {
            val installIntent = buildInstallPendingIntent(context, 5004)
            val customView = buildReadyUpdateRemoteViews(context, readyVersion, installIntent, openAppIntent)
            return builder
                .setContentTitle("Nova")
                .setContentText("Обновление $readyVersion готово к установке")
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(customView)
                .setCustomBigContentView(customView)
                .build()
        }

        return builder
            .setContentTitle("Nova VPN")
            .setContentText(subtitle)
            .build()
    }

    fun installReadyUpdate(context: Context) {
        launchInstaller(context.applicationContext)
    }

    fun resumePendingInstallIfAllowed(context: Context) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        if (!clientData.consumeResumeInstallAfterPermissionGrant()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            LogManager.log("Пользователь вернулся без разрешения на установку APK для Nova. Автоповтор установки пропускаем.")
            return
        }
        if (!hasReadyDownloadedUpdate(appContext)) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Пробуем установить обновление", Toast.LENGTH_SHORT).show()
        }
        installReadyUpdate(appContext)
    }

    private fun launchInstaller(context: Context) {
        val clientData = ClientData(context)
        val readyVersion = getReadyDownloadedVersion(context)
        val apkFile = File(clientData.getDownloadedApkPath())
        if (!apkFile.exists()) {
            LogManager.log("Кнопка установки нажата, но APK обновления не найден по пути ${apkFile.absolutePath}.")
            getReadyDownloadedUpdate(context, clientData)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Файл обновления пропал, проверь загрузку ещё раз", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val validation = validateDownloadedApk(
            context = context,
            apkFile = apkFile,
            expectedVersion = readyVersion.ifBlank { clientData.getDownloadedApkVersion() },
            expectedSha256 = clientData.getLastUpdateSha256(),
        )
        val parsedApk = validation.parsedApk
        if (parsedApk == null) {
            val repairStarted = enqueueRepairForInvalidApk(
                context = context,
                clientData = clientData,
                path = apkFile.absolutePath,
                reason = validation.failureReason.ifBlank {
                    "Перед установкой обнаружен битый или неподходящий APK обновления."
                },
            )
            if (!repairStarted) {
                discardInvalidDownloadedApkState(
                    context = context,
                    clientData = clientData,
                    path = apkFile.absolutePath,
                    reason = validation.failureReason.ifBlank {
                        "Перед установкой обнаружен битый или неподходящий APK обновления. Удаляем его и сбрасываем кнопку установки."
                    },
                )
            }
            return
        }
        if (!ensurePackageInstallerPermission(context, rememberRetry = true)) {
            return
        }
        if (!installSessionInProgress.compareAndSet(false, true)) {
            LogManager.log("Установка обновления уже запускается. Повторный вызов пропускаем.")
            return
        }
        Thread {
            val success = runCatching {
                launchInstallerSession(
                    context = context,
                    apkFile = apkFile,
                    version = readyVersion.ifBlank { parsedApk.versionName },
                )
            }.onFailure { error ->
                LogManager.log("Не удалось передать APK в PackageInstaller.Session: ${error.message}")
            }.getOrDefault(false)
            if (!success) {
                installSessionInProgress.set(false)
            }
        }.start()
    }

    private fun launchInstallerSession(
        context: Context,
        apkFile: File,
        version: String,
    ): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
        }
        val sessionId = packageInstaller.createSession(sessionParams)
        var committed = false
        try {
            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent = buildInstallCommitStatusPendingIntent(
                    context = context,
                    sessionId = sessionId,
                    version = version,
                    path = apkFile.absolutePath,
                )
                session.commit(statusIntent.intentSender)
                committed = true
            }
            LogManager.log(
                "Передали APK ${if (version.isNotBlank()) version else apkFile.name} в PackageInstaller.Session ${formatSessionId(sessionId)}."
            )
            return true
        } catch (error: Exception) {
            if (!committed) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            throw error
        }
    }

    private fun handleInstallCommitStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent = getPackageInstallerConfirmationIntent(intent)
                if (confirmationIntent == null) {
                    installSessionInProgress.set(false)
                    LogManager.log(
                        "PackageInstaller.Session ${formatSessionId(sessionId)} требует подтверждение, но не передал intent."
                    )
                    return
                }
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmationIntent) }
                    .onSuccess {
                        LogManager.log(
                            "PackageInstaller.Session ${formatSessionId(sessionId)} запросил подтверждение установки ${
                                if (version.isNotBlank()) version else "обновления"
                            }."
                        )
                    }
                    .onFailure { error ->
                        installSessionInProgress.set(false)
                        LogManager.log(
                            "Не удалось открыть системный установщик для ${
                                if (version.isNotBlank()) version else "обновления"
                            }: ${error.message}"
                        )
                    }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                installSessionInProgress.set(false)
                ClientData(context).setResumeInstallAfterPermissionGrant(false)
                val clientData = ClientData(context)
                clientData.clearDownloadedUpdateState()
                clientData.setUpdateRepairState(
                    active = false,
                    version = version,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    status = "",
                )
                cleanupDownloadedApkFiles(context, keepPath = null)
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
                broadcastUpdateStateChanged(context)
                LogManager.log(
                    "Установка обновления ${if (version.isNotBlank()) version else ""} завершилась успешно через PackageInstaller.Session ${formatSessionId(sessionId)}.".trim()
                )
            }
            else -> {
                installSessionInProgress.set(false)
                LogManager.log(
                    "PackageInstaller.Session ${formatSessionId(sessionId)} завершился ошибкой: status=$status, message=$statusMessage"
                )
                handleInstallCommitFailure(
                    context = context,
                    version = version,
                    path = path,
                    status = status,
                    statusMessage = statusMessage,
                )
            }
        }
    }

    private fun handleInstallCommitFailure(
        context: Context,
        version: String,
        path: String,
        status: Int,
        statusMessage: String,
    ) {
        if (status == PackageInstaller.STATUS_FAILURE_BLOCKED || statusMessage.contains("Permission Denied", ignoreCase = true)) {
            ensurePackageInstallerPermission(context, rememberRetry = true)
            return
        }
        if (path.isBlank()) return
        val apkFile = File(path)
        if (!apkFile.exists()) return
        val clientData = ClientData(context)
        val validation = validateDownloadedApk(
            context = context,
            apkFile = apkFile,
            expectedVersion = version.ifBlank { clientData.getDownloadedApkVersion() },
            expectedSha256 = clientData.getLastUpdateSha256(),
        )
        if (validation.parsedApk == null || status == PackageInstaller.STATUS_FAILURE_INVALID) {
            val reason = buildString {
                append(
                    validation.failureReason.ifBlank {
                        "PackageInstaller отклонил APK как некорректный."
                    }
                )
                if (statusMessage.isNotBlank()) {
                    append(" Система установки: ")
                    append(statusMessage)
                }
            }
            val repairStarted = enqueueRepairForInvalidApk(
                context = context,
                clientData = clientData,
                path = apkFile.absolutePath,
                reason = reason,
            )
            if (!repairStarted && validation.parsedApk == null) {
                discardInvalidDownloadedApkState(
                    context = context,
                    clientData = clientData,
                    path = apkFile.absolutePath,
                    reason = reason,
                )
            }
        }
    }

    private fun getPackageInstallerConfirmationIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
        }
    }

    private fun ensurePackageInstallerPermission(
        context: Context,
        rememberRetry: Boolean,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }
        if (context.packageManager.canRequestPackageInstalls()) {
            return true
        }
        if (rememberRetry) {
            ClientData(context).setResumeInstallAfterPermissionGrant(true)
        }
        LogManager.log("Для установки обновления нужно разрешить установку APK из этого источника.")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "Разреши установку приложений для Nova и вернись назад",
                Toast.LENGTH_LONG,
            ).show()
        }
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(settingsIntent) }
            .recoverCatching { context.startActivity(fallbackIntent) }
            .onFailure { error ->
                LogManager.log("Не удалось открыть системный экран разрешения установки APK: ${error.message}")
            }
        return false
    }

    private fun buildInstallCommitStatusPendingIntent(
        context: Context,
        sessionId: Int,
        version: String,
        path: String,
    ): PendingIntent {
        val callbackIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_INSTALL_COMMIT_STATUS
            putExtra(EXTRA_VERSION, version)
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_INSTALL_SESSION_ID, sessionId)
        }
        return PendingIntent.getBroadcast(
            context,
            EXTRA_INSTALL_REQUEST_CODE + sessionId.coerceAtLeast(0),
            callbackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun formatSessionId(sessionId: Int): String {
        return if (sessionId >= 0) "#$sessionId" else "#?"
    }

    private fun ensureNotificationChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return true
    }

    /**
     * Отдельный канал для «Nova обновлена» — с высокой важностью.
     *
     * Важность канала задаётся один раз при создании: понизить её человек может, а
     * поднять программно уже нельзя. Канал обновлений живёт с обычной важностью и
     * не всплывает, поэтому уведомление, которое должно вернуть человека в
     * приложение, идёт своим каналом.
     */
    private fun ensureUpdatedChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_UPDATED,
                "Nova обновлена",
                NotificationManager.IMPORTANCE_HIGH,
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return true
    }

    private fun canNotify(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun buildInstallPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val installIntent = Intent(context, UpdateActionReceiver::class.java).apply {
            action = ACTION_INSTALL_UPDATE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildReadyUpdateRemoteViews(
        context: Context,
        version: String,
        installPendingIntent: PendingIntent,
        rootPendingIntent: PendingIntent,
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_update_ready).apply {
            setTextViewText(R.id.tv_update_title, "Nova $version готова")
            setTextViewText(R.id.tv_update_subtitle, "Нажми, чтобы установить обновление")
            setOnClickPendingIntent(R.id.btn_update_install, installPendingIntent)
            setOnClickPendingIntent(R.id.notification_root, rootPendingIntent)
        }
    }

    private fun getReadyDownloadedUpdate(
        context: Context,
        clientData: ClientData = ClientData(context.applicationContext),
    ): ApkUpdateMetadata? {
        var path = clientData.getDownloadedApkPath().trim()
        var version = clientData.getDownloadedApkVersion().trim()
        if (path.isBlank() || version.isBlank()) {
            recoverDownloadedUpdateStateFromFiles(context, clientData)?.let { recovered ->
                path = recovered.first
                version = recovered.second
            }
        }
        if (path.isBlank() || version.isBlank()) {
            return null
        }
        val trackedDownloadId = clientData.getUpdateDownloadId()
        if (trackedDownloadId > 0L && isDownloadStillActive(context, trackedDownloadId)) {
            return null
        }
        if (!isNewerVersion(version, getInstalledVersion(context))) {
            // Молча исчезнувшее «готовое обновление» выглядит как потерянный файл.
            LogManager.log(
                "Скачанный APK $version не новее установленного ${getInstalledVersion(context)}. Убираем его."
            )
            clientData.clearDownloadedUpdateState()
            cleanupDownloadedApkFiles(context, keepPath = null)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
            broadcastUpdateStateChanged(context)
            return null
        }
        val apkFile = File(path)
        if (!apkFile.exists()) {
            recoverDownloadedUpdateStateFromFiles(context, clientData)?.let { recovered ->
                val recoveredFile = File(recovered.first)
                if (recoveredFile.exists() && isNewerVersion(recovered.second, getInstalledVersion(context))) {
                    return ApkUpdateMetadata(
                        version = recovered.second,
                        url = clientData.getLastUpdateUrl(),
                        sha256 = clientData.getLastUpdateSha256(),
                    )
                }
            }
            clientData.clearDownloadedUpdateState()
            cleanupDownloadedApkFiles(context, keepPath = null)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
            broadcastUpdateStateChanged(context)
            return null
        }
        val validation = validateDownloadedApk(
            context = context,
            apkFile = apkFile,
            expectedVersion = version,
            expectedSha256 = clientData.getLastUpdateSha256(),
        )
        if (validation.parsedApk == null) {
            val repairStarted = enqueueRepairForInvalidApk(
                context = context,
                clientData = clientData,
                path = apkFile.absolutePath,
                reason = validation.failureReason.ifBlank {
                    "Готовое обновление не прошло проверку целостности или версии."
                },
            )
            if (!repairStarted) {
                discardInvalidDownloadedApkState(
                    context = context,
                    clientData = clientData,
                    path = apkFile.absolutePath,
                    reason = validation.failureReason.ifBlank {
                        "Готовое обновление не прошло проверку целостности или версии. Удаляем его и сбрасываем ready-state."
                    },
                )
            }
            return null
        }
        return ApkUpdateMetadata(
            version = version,
            url = clientData.getLastUpdateUrl(),
            sha256 = clientData.getLastUpdateSha256(),
        )
    }

    private fun reconcileDownloadedUpdateTarget(
        context: Context,
        clientData: ClientData,
        latestVersion: String,
    ) {
        val downloadedVersion = clientData.getDownloadedApkVersion().trim()
        if (downloadedVersion.isBlank()) return
        if (compareVersions(latestVersion, downloadedVersion) <= 0) return
        LogManager.log(
            "Найдена более новая версия $latestVersion. Удаляем ранее скачанное обновление $downloadedVersion."
        )
        discardTrackedDownloadedUpdate(context, clientData)
    }

    private fun discardOlderDownloadedUpdate(
        context: Context,
        clientData: ClientData,
        keepVersion: String,
    ) {
        val downloadedVersion = clientData.getDownloadedApkVersion().trim()
        if (downloadedVersion.isBlank()) return
        if (compareVersions(keepVersion, downloadedVersion) <= 0) return
        discardTrackedDownloadedUpdate(context, clientData)
    }

    private fun discardTrackedDownloadedUpdate(
        context: Context,
        clientData: ClientData,
    ) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val trackedDownloadId = clientData.getUpdateDownloadId()
        if (trackedDownloadId > 0L) {
            runCatching { manager?.remove(trackedDownloadId) }
        }
        val trackedPath = clientData.getDownloadedApkPath().trim()
        if (trackedPath.isNotBlank()) {
            runCatching { File(trackedPath).delete() }
        }
        clientData.clearDownloadedUpdateState()
        cleanupStaleUpdateDownloads(context, keepDownloadId = -1L)
        cleanupDownloadedApkFiles(context, keepPath = null)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
        broadcastUpdateStateChanged(context)
    }

    private fun refreshVpnForegroundNotification(context: Context) {
        val intent = Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_REFRESH_NOTIFICATION
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun broadcastUpdateStateChanged(context: Context) {
        val appContext = context.applicationContext
        val version = getReadyDownloadedVersion(appContext)
        appContext.sendBroadcast(
            Intent(ACTION_UPDATE_STATE_CHANGED).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_VERSION, version)
            }
        )
    }

    private fun isWifiTransport(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = connectivityManager.activeNetwork
        val networks = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { network ->
                if (network != active) add(network)
            }
        }
        for (network in networks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return true
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return false
            }
        }
        return false
    }

    private fun getInstalledVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0"
        } catch (_: Exception) {
            "0.0"
        }
    }

    private fun hasInFlightDownload(
        context: Context,
        clientData: ClientData,
        version: String,
    ): Boolean {
        val downloadId = clientData.getUpdateDownloadId()
        val storedVersion = clientData.getDownloadedApkVersion().trim()
        if (downloadId <= 0L || storedVersion != version) return false
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            return when (cursor.getIntSafe(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> true
                else -> false
            }
        }
        return false
    }

    private fun isDownloadStillActive(context: Context, downloadId: Long): Boolean {
        if (downloadId <= 0L) return false
        val manager = context.getSystemService(DownloadManager::class.java) ?: return false
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            return when (cursor.getIntSafe(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> true
                else -> false
            }
        }
        return false
    }

    private fun cleanupStaleUpdateDownloads(context: Context, keepDownloadId: Long) {
        val manager = context.getSystemService(DownloadManager::class.java) ?: return
        val idsToRemove = mutableListOf<Long>()
        manager.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                val downloadId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                if (downloadId == keepDownloadId) continue
                val title = cursor.getStringSafe(DownloadManager.COLUMN_TITLE).orEmpty()
                val localUri = cursor.getStringSafe(DownloadManager.COLUMN_LOCAL_URI).orEmpty()
                if (title.startsWith("Nova ") || localUri.contains("/Android/data/${context.packageName}/files/Download/Nova-")) {
                    idsToRemove += downloadId
                }
            }
        }
        if (idsToRemove.isNotEmpty()) {
            LogManager.log("Удаляем stale загрузки обновления Nova: ${idsToRemove.joinToString(",")}")
            manager.remove(*idsToRemove.toLongArray())
        }
    }

    private fun cleanupDuplicateApkFiles(keepPath: String) {
        val keepFile = File(keepPath)
        val parent = keepFile.parentFile ?: return
        parent.listFiles()?.forEach { candidate ->
            if (candidate.absolutePath == keepFile.absolutePath) return@forEach
            if (candidate.extension.equals("apk", ignoreCase = true) &&
                candidate.name.startsWith("Nova-")
            ) {
                candidate.delete()
            }
        }
    }

    private fun cleanupDownloadedApkFiles(context: Context, keepPath: String?) {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val keepAbsolutePath = keepPath?.trim().orEmpty()
        downloadDir.listFiles()?.forEach { candidate ->
            if (!candidate.name.startsWith("Nova-") || !candidate.extension.equals("apk", ignoreCase = true)) {
                return@forEach
            }
            if (keepAbsolutePath.isNotBlank() && candidate.absolutePath == keepAbsolutePath) {
                return@forEach
            }
            candidate.delete()
        }
    }

    private fun inspectDownloadedApk(
        context: Context,
        apkFile: File,
    ): ParsedDownloadedApk? {
        if (!apkFile.exists() || apkFile.length() < 1024L * 1024L) return null
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.PackageInfoFlags.of(0)
        } else {
            null
        }
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags!!)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: return null
        return ParsedDownloadedApk(
            packageName = packageInfo.packageName.orEmpty(),
            versionName = packageInfo.versionName.orEmpty(),
        ).takeIf { it.packageName.isNotBlank() && it.versionName.isNotBlank() }
    }

    private fun validateDownloadedApk(
        context: Context,
        apkFile: File,
        expectedVersion: String,
        expectedSha256: String,
    ): DownloadedApkValidationResult {
        val parsedApk = inspectDownloadedApk(context, apkFile)
            ?: return DownloadedApkValidationResult(
                failureReason = "Скачанный APK не читается Android PackageManager. Удаляем его как повреждённый.",
            )
        if (parsedApk.packageName != context.packageName) {
            return DownloadedApkValidationResult(
                failureReason = "Скачанный APK относится к другому пакету: ${parsedApk.packageName}. Удаляем его.",
            )
        }
        if (expectedVersion.isNotBlank() && parsedApk.versionName != expectedVersion) {
            return DownloadedApkValidationResult(
                failureReason = "Скачанный APK имеет неожиданную версию ${parsedApk.versionName} вместо $expectedVersion. Удаляем файл и ждём новую загрузку.",
            )
        }
        val normalizedExpectedSha256 = normalizeSha256(expectedSha256)
        if (normalizedExpectedSha256.isNotBlank()) {
            val actualSha256 = computeSha256(apkFile)
                ?: return DownloadedApkValidationResult(
                    failureReason = "Не удалось посчитать SHA-256 скачанного APK. Удаляем файл и ждём новую загрузку.",
                )
            if (actualSha256 != normalizedExpectedSha256) {
                return DownloadedApkValidationResult(
                    failureReason = "SHA-256 скачанного APK не совпал с update feed. Удаляем файл и сбрасываем ready-state.",
                    actualSha256 = actualSha256,
                )
            }
            return DownloadedApkValidationResult(parsedApk = parsedApk, actualSha256 = actualSha256)
        }
        return DownloadedApkValidationResult(parsedApk = parsedApk)
    }

    private fun computeSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeSha256(value: String): String = value.trim().lowercase()

    private fun discardInvalidDownloadedApkState(
        context: Context,
        clientData: ClientData,
        path: String,
        reason: String,
    ) {
        LogManager.log(reason)
        runCatching {
            val trackedDownloadId = clientData.getUpdateDownloadId()
            if (trackedDownloadId > 0L) {
                context.getSystemService(DownloadManager::class.java)?.remove(trackedDownloadId)
            }
        }
        runCatching { File(path).delete() }
        clientData.clearDownloadedUpdateState()
        cleanupDownloadedApkFiles(context, keepPath = null)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
        broadcastUpdateStateChanged(context)
    }

    private fun buildStoredMetadata(clientData: ClientData): ApkUpdateMetadata? {
        val version = clientData.getLastUpdateVersion().ifBlank { clientData.getDownloadedApkVersion() }
        val url = clientData.getLastUpdateUrl()
        val sha256 = clientData.getLastUpdateSha256()
        return if (version.isNotBlank() && url.isNotBlank()) {
            ApkUpdateMetadata(version = version, url = url, sha256 = sha256)
        } else {
            null
        }
    }

    private fun enqueueRepairForInvalidApk(
        context: Context,
        clientData: ClientData,
        path: String,
        reason: String,
    ): Boolean {
        val metadata = buildStoredMetadata(clientData)
        if (metadata == null) {
            LogManager.log("$reason Восстановление не запущено: нет актуальных metadata обновления.")
            return false
        }
        // Восстанавливать имеет смысл только то, что новее установленного.
        //
        // Проверено на устройстве 1.27: в ленте обновлений оставалась версия 1.26,
        // и любой не прошедший проверку файл запускал «восстановление» — семьдесят
        // шесть мегабайт трафика ради APK, который следующий же запуск выбрасывал
        // как устаревший. Само восстановление при этом отчитывалось об успехе.
        val installedVersion = getInstalledVersion(context)
        if (!isNewerVersion(metadata.version, installedVersion)) {
            LogManager.log(
                "$reason Восстановление ${metadata.version} не запускаем: " +
                    "установлена $installedVersion, качать нечего."
            )
            return false
        }
        if (clientData.isUpdateRepairInProgress() && clientData.getUpdateRepairVersion() == metadata.version) {
            LogManager.log("$reason Восстановление ${metadata.version} уже выполняется.")
            return true
        }

        val stagedPath = stageDamagedApkForRepair(context, metadata.version, path)
        val stagedBytes = stagedPath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.length()
            ?: 0L

        runCatching {
            val trackedDownloadId = clientData.getUpdateDownloadId()
            if (trackedDownloadId > 0L) {
                context.getSystemService(DownloadManager::class.java)?.remove(trackedDownloadId)
            }
        }
        clientData.clearDownloadedUpdateState()
        clientData.setUpdateRepairState(
            active = true,
            version = metadata.version,
            downloadedBytes = stagedBytes,
            totalBytes = 0L,
            status = if (stagedBytes > 0L) {
                "Восстанавливаем ${metadata.version} без полной перекачки..."
            } else {
                "Проверяем и восстанавливаем ${metadata.version}..."
            },
        )
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_READY_ID)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_AVAILABLE_ID)
        broadcastUpdateStateChanged(context)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            REPAIR_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateRepairWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                // Повтор через полминуты, дальше с удвоением: сеть, из-за которой
                // оборвалась загрузка, редко возвращается в ту же секунду.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        EXTRA_VERSION to metadata.version,
                        EXTRA_URL to metadata.url,
                        EXTRA_SHA256 to metadata.sha256,
                        EXTRA_PATH to stagedPath.orEmpty(),
                    )
                )
                .build()
        )
        LogManager.log(
            "$reason Запускаем восстановление APK ${metadata.version}" +
                if (stagedBytes > 0L) " с попыткой докачки/проверки." else "."
        )
        return true
    }

    private fun stageDamagedApkForRepair(
        context: Context,
        version: String,
        sourcePath: String,
    ): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        downloadDir.mkdirs()
        val partFile = File(downloadDir, "Nova-$version.apk.part")
        if (sourceFile.absolutePath == partFile.absolutePath) {
            return partFile.absolutePath
        }
        if (partFile.exists()) {
            partFile.delete()
        }
        if (sourceFile.renameTo(partFile)) {
            return partFile.absolutePath
        }
        return runCatching {
            sourceFile.copyTo(partFile, overwrite = true)
            sourceFile.delete()
            partFile.absolutePath
        }.getOrNull()
    }

    internal fun performRepairDownload(
        context: Context,
        metadata: ApkUpdateMetadata,
        damagedPath: String?,
    ): Boolean {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        val downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir == null) {
            clientData.setUpdateRepairState(
                active = false,
                version = metadata.version,
                status = "Не удалось восстановить обновление: каталог загрузок недоступен",
            )
            broadcastUpdateStateChanged(appContext)
            return false
        }
        downloadDir.mkdirs()
        val finalFile = File(downloadDir, "Nova-${metadata.version}.apk")
        val partFile = damagedPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(downloadDir, "Nova-${metadata.version}.apk.part")
        val remoteInfo = probeRemoteApkInfo(metadata.url)
        val totalBytes = remoteInfo?.totalBytes ?: 0L
        var resumeOffset = 0L
        if (partFile.exists()) {
            val partialBytes = partFile.length()
            val canResume = remoteInfo?.supportsRange == true && totalBytes > 0L && partialBytes in 1 until totalBytes
            if (canResume) {
                resumeOffset = partialBytes
            } else if (partialBytes <= 0L || totalBytes <= 0L || partialBytes >= totalBytes) {
                partFile.delete()
            }
        }

        clientData.setUpdateRepairState(
            active = true,
            version = metadata.version,
            downloadedBytes = resumeOffset,
            totalBytes = totalBytes,
            status = if (resumeOffset > 0L) {
                "Восстанавливаем ${metadata.version}: продолжаем с ${formatBytes(resumeOffset)}"
            } else {
                "Скачиваем исправленный APK ${metadata.version}..."
            },
        )
        broadcastUpdateStateChanged(appContext)

        val resumedOutcome = downloadRepairPayload(
            context = appContext,
            metadata = metadata,
            targetFile = partFile,
            totalBytesHint = totalBytes,
            resumeOffset = resumeOffset,
        )
        val requiresFullRetry = resumeOffset > 0L && !resumedOutcome
        if (requiresFullRetry) {
            LogManager.log(
                "Докачка обновления ${metadata.version} не дала валидный поток. Перекачиваем APK целиком."
            )
        }
        if (!resumedOutcome && !requiresFullRetry) {
            clientData.setUpdateRepairState(
                active = false,
                version = metadata.version,
                downloadedBytes = partFile.takeIf { it.exists() }?.length() ?: 0L,
                totalBytes = totalBytes,
                status = "Не удалось восстановить обновление ${metadata.version}. Повтори загрузку ещё раз.",
            )
            broadcastUpdateStateChanged(appContext)
            return false
        }

        if (requiresFullRetry) {
            partFile.delete()
            clientData.setUpdateRepairState(
                active = true,
                version = metadata.version,
                downloadedBytes = 0L,
                totalBytes = totalBytes,
                status = "Перекачиваем ${metadata.version} заново после проверки целостности...",
            )
            broadcastUpdateStateChanged(appContext)
            if (!downloadRepairPayload(
                    context = appContext,
                    metadata = metadata,
                    targetFile = partFile,
                    totalBytesHint = totalBytes,
                    resumeOffset = 0L,
                )
            ) {
                clientData.setUpdateRepairState(
                    active = false,
                    version = metadata.version,
                    downloadedBytes = partFile.takeIf { it.exists() }?.length() ?: 0L,
                    totalBytes = totalBytes,
                    status = "Не удалось перекачать обновление ${metadata.version}.",
                )
                broadcastUpdateStateChanged(appContext)
                return false
            }
        }

        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!partFile.renameTo(finalFile)) {
            runCatching {
                partFile.copyTo(finalFile, overwrite = true)
                partFile.delete()
            }.getOrElse {
                clientData.setUpdateRepairState(
                    active = false,
                    version = metadata.version,
                    downloadedBytes = partFile.takeIf { it.exists() }?.length() ?: 0L,
                    totalBytes = totalBytes,
                    status = "Не удалось подготовить исправленный APK ${metadata.version} к установке.",
                )
                broadcastUpdateStateChanged(appContext)
                return false
            }
        }

        val validation = validateDownloadedApk(
            context = appContext,
            apkFile = finalFile,
            expectedVersion = metadata.version,
            expectedSha256 = metadata.sha256,
        )
        val parsedApk = validation.parsedApk
        if (parsedApk == null) {
            if (resumeOffset > 0L) {
                LogManager.log(
                    validation.failureReason.ifBlank {
                        "Докачанный APK ${metadata.version} не прошёл финальную проверку. Перекачиваем его полностью."
                    }
                )
                runCatching { finalFile.delete() }
                partFile.delete()
                clientData.setUpdateRepairState(
                    active = true,
                    version = metadata.version,
                    downloadedBytes = 0L,
                    totalBytes = totalBytes,
                    status = "Докачка не прошла проверку. Перекачиваем ${metadata.version} полностью...",
                )
                broadcastUpdateStateChanged(appContext)
                val fullRedownloadOk = downloadRepairPayload(
                    context = appContext,
                    metadata = metadata,
                    targetFile = partFile,
                    totalBytesHint = totalBytes,
                    resumeOffset = 0L,
                )
                if (fullRedownloadOk) {
                    if (finalFile.exists()) {
                        finalFile.delete()
                    }
                    if (!partFile.renameTo(finalFile)) {
                        runCatching {
                            partFile.copyTo(finalFile, overwrite = true)
                            partFile.delete()
                        }.onFailure {
                            clientData.setUpdateRepairState(
                                active = false,
                                version = metadata.version,
                                downloadedBytes = 0L,
                                totalBytes = totalBytes,
                                status = "Не удалось подготовить обновление ${metadata.version} после полной перекачки.",
                            )
                            broadcastUpdateStateChanged(appContext)
                            return false
                        }
                    }
                    val fullValidation = validateDownloadedApk(
                        context = appContext,
                        apkFile = finalFile,
                        expectedVersion = metadata.version,
                        expectedSha256 = metadata.sha256,
                    )
                    if (fullValidation.parsedApk != null) {
                        clientData.setDownloadedApkPath(finalFile.absolutePath)
                        clientData.setDownloadedApkVersion(fullValidation.parsedApk.versionName)
                        clientData.setUpdateDownloadId(-1L)
                        clientData.clearUpdateRepairState()
                        cleanupStaleUpdateDownloads(appContext, keepDownloadId = -1L)
                        cleanupDuplicateApkFiles(finalFile.absolutePath)
                        if (clientData.getServiceState() == NovaVpnService.STATE_STOPPED) {
                            showInstallReadyNotification(appContext, fullValidation.parsedApk.versionName)
                        } else {
                            refreshVpnForegroundNotification(appContext)
                        }
                        broadcastUpdateStateChanged(appContext)
                        LogManager.log("Восстановление обновления ${fullValidation.parsedApk.versionName} завершено после полной перекачки.")
                        return true
                    }
                }
            }
            LogManager.log(
                validation.failureReason.ifBlank {
                    "Исправленный APK ${metadata.version} всё ещё не прошёл финальную проверку."
                }
            )
            runCatching { finalFile.delete() }
            clientData.setUpdateRepairState(
                active = false,
                version = metadata.version,
                downloadedBytes = 0L,
                totalBytes = totalBytes,
                status = "Исправить обновление ${metadata.version} не удалось. Нужна новая загрузка.",
            )
            broadcastUpdateStateChanged(appContext)
            return false
        }

        clientData.setDownloadedApkPath(finalFile.absolutePath)
        clientData.setDownloadedApkVersion(parsedApk.versionName)
        clientData.setUpdateDownloadId(-1L)
        clientData.clearUpdateRepairState()
        cleanupStaleUpdateDownloads(appContext, keepDownloadId = -1L)
        cleanupDuplicateApkFiles(finalFile.absolutePath)
        if (clientData.getServiceState() == NovaVpnService.STATE_STOPPED) {
            showInstallReadyNotification(appContext, parsedApk.versionName)
        } else {
            refreshVpnForegroundNotification(appContext)
        }
        broadcastUpdateStateChanged(appContext)
        LogManager.log("Восстановление обновления ${parsedApk.versionName} завершено успешно.")
        return true
    }

    /**
     * Качает APK, доводя дело до конца через обрывы связи.
     *
     * Одна попытка — это один HTTP-поток, и он рвётся: мобильная сеть, засыпающий
     * телефон, поднявшийся поверх загрузки VPN. Раньше первый же обрыв возвращал
     * «не удалось», хотя на диске лежало почти всё, — человек видел тупик и жал
     * «обновить» заново с нуля. Теперь каждая следующая попытка продолжает с того
     * места, куда дошла предыдущая, и только исчерпав их все мы признаём неудачу.
     *
     * Отдельно проверяем, что докачали до конца: сервер, закрывший соединение
     * раньше времени, выглядит как успех — поток просто кончился, — и обрезанный
     * APK доходил до проверки подписи, где его отвергали и качали целиком заново.
     */
    private fun downloadRepairPayload(
        context: Context,
        metadata: ApkUpdateMetadata,
        targetFile: File,
        totalBytesHint: Long,
        resumeOffset: Long,
    ): Boolean {
        var offset = resumeOffset
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            val outcome = downloadRepairPayloadOnce(
                context = context,
                metadata = metadata,
                targetFile = targetFile,
                totalBytesHint = totalBytesHint,
                resumeOffset = offset,
            )
            if (outcome.completed) return true
            if (attempt == DOWNLOAD_ATTEMPTS) {
                LogManager.log(
                    "Загрузка ${metadata.version} не удалась за $DOWNLOAD_ATTEMPTS попыток: ${outcome.reason}"
                )
                return false
            }

            val bytesOnDisk = targetFile.takeIf { it.exists() }?.length() ?: 0L
            // Продолжать имеет смысл, только если сервер отдаёт куски и предыдущая
            // попытка что-то дописала. Иначе цикл крутился бы вхолостую.
            offset = if (outcome.canResume && bytesOnDisk > 0L) bytesOnDisk else 0L
            LogManager.log(
                "Загрузка ${metadata.version}, попытка $attempt: ${outcome.reason}. " +
                    if (offset > 0L) "Продолжим с ${formatBytes(offset)}." else "Начнём заново."
            )
            ClientData(context).setUpdateRepairState(
                active = true,
                version = metadata.version,
                downloadedBytes = offset,
                totalBytes = totalBytesHint,
                status = "Связь оборвалась. Повторяем загрузку ${metadata.version}...",
            )
            broadcastUpdateStateChanged(context)
            runCatching { Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempt) }
        }
        return false
    }

    private fun downloadRepairPayloadOnce(
        context: Context,
        metadata: ApkUpdateMetadata,
        targetFile: File,
        totalBytesHint: Long,
        resumeOffset: Long,
    ): DownloadAttemptOutcome {
        val clientData = ClientData(context)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(metadata.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroidUpdater/1.12")
            if (resumeOffset > 0L) {
                connection.setRequestProperty("Range", "bytes=$resumeOffset-")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return DownloadAttemptOutcome(
                    completed = false,
                    canResume = responseCode !in 400..499,
                    reason = "сервер ответил HTTP $responseCode",
                )
            }
            // Ответ 200 на запрос с Range означает, что сервер отдаёт файл целиком:
            // дописывать в этом случае нельзя — получится склейка из двух начал.
            val resuming = resumeOffset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            if (resumeOffset > 0L && !resuming) {
                LogManager.log(
                    "Сервер не подтвердил Range для ${metadata.version} (HTTP $responseCode). Качаем целиком."
                )
            }

            val totalBytes = when {
                resuming -> {
                    val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                    contentRange.substringAfter('/').toLongOrNull()
                        ?: totalBytesHint
                }
                else -> connection.contentLengthLong.takeIf { it > 0L } ?: totalBytesHint
            }

            if (!resuming && targetFile.exists()) {
                targetFile.delete()
            }
            targetFile.parentFile?.mkdirs()
            connection.inputStream.buffered().use { input ->
                FileOutputStream(targetFile, resuming).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = if (resuming) resumeOffset else 0L
                    var lastBroadcastAtMs = 0L
                    var lastBroadcastBytes = downloadedBytes
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val nowMs = System.currentTimeMillis()
                        if (
                            nowMs - lastBroadcastAtMs >= 400L ||
                            downloadedBytes - lastBroadcastBytes >= 512L * 1024L
                        ) {
                            clientData.setUpdateRepairState(
                                active = true,
                                version = metadata.version,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                status = if (totalBytes > 0L) {
                                    "Восстанавливаем ${metadata.version}: ${((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L)}%"
                                } else {
                                    "Восстанавливаем ${metadata.version}..."
                                },
                            )
                            broadcastUpdateStateChanged(context)
                            lastBroadcastAtMs = nowMs
                            lastBroadcastBytes = downloadedBytes
                        }
                    }
                    output.flush()
                    if (totalBytes > 0L && downloadedBytes < totalBytes) {
                        // Поток кончился раньше файла — это обрыв, а не успех.
                        return DownloadAttemptOutcome(
                            completed = false,
                            canResume = true,
                            reason = "поток оборвался на ${formatBytes(downloadedBytes)} из ${formatBytes(totalBytes)}",
                        )
                    }
                    clientData.setUpdateRepairState(
                        active = true,
                        version = metadata.version,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        status = if (totalBytes > 0L) {
                            "Проверяем исправленный APK ${metadata.version}..."
                        } else {
                            "Проверяем восстановленный APK ${metadata.version}..."
                        },
                    )
                    broadcastUpdateStateChanged(context)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0L) {
                DownloadAttemptOutcome(completed = true, canResume = true, reason = "")
            } else {
                DownloadAttemptOutcome(
                    completed = false,
                    canResume = false,
                    reason = "файл не появился на диске",
                )
            }
        } catch (e: Exception) {
            DownloadAttemptOutcome(
                completed = false,
                canResume = true,
                reason = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun probeRemoteApkInfo(url: String): RemoteApkInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroidUpdater/1.12")
            connection.setRequestProperty("Range", "bytes=0-0")
            val code = connection.responseCode
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            val totalBytes = when {
                code == HttpURLConnection.HTTP_PARTIAL -> contentRange.substringAfter('/').toLongOrNull() ?: 0L
                else -> connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            }
            RemoteApkInfo(
                totalBytes = totalBytes,
                supportsRange = code == HttpURLConnection.HTTP_PARTIAL ||
                    connection.getHeaderField("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true),
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = value.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.lastIndex) {
            size /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${size.toLong()} ${units[unitIndex]}"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", size, units[unitIndex])
        }
    }

    private fun recoverDownloadedUpdateStateFromFiles(
        context: Context,
        clientData: ClientData,
    ): Pair<String, String>? {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val installedVersion = getInstalledVersion(context)
        val versionRegex = Regex("""^Nova-(.+?)(?:-\d+)?\.apk$""", RegexOption.IGNORE_CASE)
        val bestCandidate = downloadDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.name.startsWith("Nova-") }
            ?.mapNotNull { file ->
                val match = versionRegex.matchEntire(file.name) ?: return@mapNotNull null
                val version = match.groupValues.getOrNull(1).orEmpty().trim()
                if (version.isBlank() || !isNewerVersion(version, installedVersion)) return@mapNotNull null
                val parsed = inspectDownloadedApk(context, file) ?: return@mapNotNull null
                if (parsed.packageName != context.packageName || parsed.versionName != version) return@mapNotNull null
                file to version
            }
            ?.sortedWith(
                Comparator<Pair<File, String>> { left, right ->
                    val versionCompare = compareVersions(right.second, left.second)
                    if (versionCompare != 0) {
                        versionCompare
                    } else {
                        right.first.lastModified().compareTo(left.first.lastModified())
                    }
                }
            )
            ?.firstOrNull()
            ?: return null

        val recoveredPath = bestCandidate.first.absolutePath
        val recoveredVersion = bestCandidate.second
        clientData.setDownloadedApkPath(recoveredPath)
        clientData.setDownloadedApkVersion(recoveredVersion)
        cleanupDownloadedApkFiles(context, keepPath = recoveredPath)
        LogManager.log("Восстановили скачанное обновление из файлов: version=$recoveredVersion, path=$recoveredPath")
        return recoveredPath to recoveredVersion
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        return compareVersions(remote, local) > 0
    }

    private fun compareVersions(remote: String, local: String): Int {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)
        val max = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until max) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return if (r > l) 1 else -1
        }
        return 0
    }

    private fun parseVersionParts(value: String): List<Int> =
        value.split('.', '-', '_').mapNotNull { it.toIntOrNull() }

    private fun Cursor.getIntSafe(columnName: String): Int {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getInt(index) else 0
    }

    private fun Cursor.getLongSafe(columnName: String): Long {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getLong(index) else 0L
    }

    private fun Cursor.getStringSafe(columnName: String): String? {
        val index = getColumnIndex(columnName)
        return if (index >= 0) getString(index) else null
    }
}
