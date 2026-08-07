package com.example.nova

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Фоновое обновление подписки VLESS.
 *
 * Без него список профилей замерзал на момент импорта: провайдеры ротируют ключи
 * постоянно, и через неделю вся подписка оказывалась мёртвой — перебор честно
 * проходил её целиком и не находил ни одного живого узла.
 */
class VlessSubscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val url = ClientData(applicationContext).getVlessSubscription()?.url.orEmpty()
        if (url.isBlank()) return Result.success()
        return when (VlessSubscriptionManager.refresh(applicationContext, url)) {
            // Неудача сети — обычное дело в фоне: повторим по расписанию WorkManager,
            // а не с нуля при следующем запуске приложения.
            is VlessSubscriptionManager.Outcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}
