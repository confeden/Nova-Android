package com.example.nova

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        return try {
            AppUpdateManager.performUpdateCheck(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
