package com.example.nova

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters

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
            AppUpdateManager.performRepairDownload(applicationContext, metadata, path)
            Result.success()
        } catch (_: Exception) {
            ClientData(applicationContext).setUpdateRepairState(
                active = false,
                version = version,
                status = "Восстановление обновления $version завершилось ошибкой.",
            )
            applicationContext.sendBroadcast(
                Intent(AppUpdateManager.ACTION_UPDATE_STATE_CHANGED).apply {
                    setPackage(applicationContext.packageName)
                    putExtra(AppUpdateManager.EXTRA_VERSION, "")
                }
            )
            Result.failure()
        }
    }
}
