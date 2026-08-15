package com.example.nova

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Докачивает и проверяет APK обновления.
 *
 * Раньше любой исход считался успехом: `performRepairDownload` возвращал `false`,
 * а worker всё равно отвечал `Result.success()`, и WorkManager снимал задачу. Для
 * человека это выглядело как «нажал обновить — ничего не произошло». Теперь неудача
 * возвращается как `Result.retry()`, пока не исчерпаны попытки: WorkManager сам
 * дождётся сети и запустит нас снова, а недокачанный `.part` останется на диске,
 * так что повтор продолжит с того же места.
 */
class UpdateRepairWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val version = inputData.getString(AppUpdateManager.EXTRA_VERSION).orEmpty()
        val url = inputData.getString(AppUpdateManager.EXTRA_URL).orEmpty()
        if (version.isBlank() || url.isBlank()) {
            return Result.failure()
        }
        val metadata = ApkUpdateMetadata(
            version = version,
            url = url,
            sha256 = inputData.getString(AppUpdateManager.EXTRA_SHA256).orEmpty(),
        )
        val path = inputData.getString(AppUpdateManager.EXTRA_PATH)
        return try {
            if (AppUpdateManager.performRepairDownload(applicationContext, metadata, path)) {
                Result.success()
            } else {
                giveUpOrRetry(version, "Загрузка обновления $version не завершилась.")
            }
        } catch (error: Exception) {
            giveUpOrRetry(
                version,
                "Загрузка обновления $version прервалась: ${error.message ?: error.javaClass.simpleName}.",
            )
        }
    }

    private fun giveUpOrRetry(version: String, reason: String): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            // Состояние остаётся активным: экран должен показывать «повторяем», а не
            // «не удалось», пока попытки не кончились.
            ClientData(applicationContext).setUpdateRepairState(
                active = true,
                version = version,
                status = "$reason Повторим, когда появится сеть.",
            )
            broadcastStateChanged()
            LogManager.log("$reason Планируем повтор (попытка ${runAttemptCount + 1} из $MAX_ATTEMPTS).")
            return Result.retry()
        }
        ClientData(applicationContext).setUpdateRepairState(
            active = false,
            version = version,
            status = "$reason Попробуй обновиться ещё раз.",
        )
        broadcastStateChanged()
        LogManager.log("$reason Попытки исчерпаны ($MAX_ATTEMPTS).")
        return Result.failure()
    }

    private fun broadcastStateChanged() {
        applicationContext.sendBroadcast(
            Intent(AppUpdateManager.ACTION_UPDATE_STATE_CHANGED).apply {
                setPackage(applicationContext.packageName)
                putExtra(AppUpdateManager.EXTRA_VERSION, "")
            }
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
