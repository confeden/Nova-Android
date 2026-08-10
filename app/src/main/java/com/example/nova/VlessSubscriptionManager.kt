package com.example.nova

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Загрузка и обновление подписки VLESS.
 *
 * Одна точка для экрана импорта и для фонового воркера: обновление подписки — это
 * та же загрузка, что и первый импорт, только с валидаторами прошлого ответа и без
 * пользователя рядом. Разводить их по двум реализациям значило бы чинить каждую
 * ошибку дважды.
 */
object VlessSubscriptionManager {

    private const val PERIODIC_WORK_NAME = "nova_vless_subscription_refresh"

    /** По умолчанию раз в полсуток: подписки живут долго, а условный запрос дёшев. */
    private const val DEFAULT_INTERVAL_HOURS = 12L

    /**
     * WorkManager не запускает периодическую работу чаще, чем раз в 15 минут, а
     * верхняя граница нужна, чтобы объявленный подпиской месяц не превратился в
     * «обновлений не будет никогда».
     */
    private const val MIN_INTERVAL_HOURS = 1L
    private const val MAX_INTERVAL_HOURS = 168L

    sealed class Outcome {
        /** Подписка не менялась: сервер ответил 304 либо состав тот же. */
        data class Unchanged(val total: Int) : Outcome()

        /**
         * Тело загрузилось, но ссылок VLESS в нём нет — значит, это подписка другого
         * протокола, и разбирать её должен вызывающий. Отличать этот случай от «состав
         * не изменился» обязательно: иначе подписка AWG отвечала бы «не изменилась» и
         * молча не импортировалась.
         */
        object NoVlessFound : Outcome()

        data class Updated(
            val added: Int,
            val removed: Int,
            val total: Int,
            val stats: VlessSubscription.ImportStats,
        ) : Outcome()

        data class Failed(val message: String) : Outcome()
    }

    /**
     * Блокирующий вызов — запускать на IO-потоке.
     *
     * @param force игнорировать валидаторы. Нужен, когда пользователь сам просит
     * обновить: 304 в ответ на его нажатие выглядит как «ничего не произошло», хотя
     * причина в кэше.
     */
    fun refresh(
        context: Context,
        url: String,
        force: Boolean = false,
        progress: VlessSubscriptionFetcher.Progress? = null,
    ): Outcome {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return Outcome.Failed("адрес подписки пуст")

        val stored = clientData.getVlessSubscription()
        val previous = stored?.takeIf { it.url.equals(normalizedUrl, ignoreCase = true) }
        // Смена адреса подписки — это тоже обновление состава, а не первый импорт.
        // Пока состав прежней подписки не учитывался, её профили оставались в списке
        // навсегда: новый адрес про них ничего не знает, и удалять их было некому.
        val replacedSubscription = stored != null && previous == null
        val validators = if (force || previous == null) {
            VlessSubscriptionFetcher.Validators()
        } else {
            VlessSubscriptionFetcher.Validators(previous.etag, previous.lastModified)
        }

        val now = System.currentTimeMillis()
        return when (val result = VlessSubscriptionFetcher.fetch(normalizedUrl, validators, progress = progress)) {
            is VlessSubscriptionFetcher.Result.NotModified -> {
                val total = clientData.getVlessProfileLinks().size
                clientData.saveVlessSubscription(
                    (previous ?: VlessSubscriptionState(url = normalizedUrl)).copy(
                        lastCheckedAt = now,
                        lastStatus = "не менялась",
                    )
                )
                LogManager.log("Подписка VLESS не менялась (304), профилей: $total.")
                Outcome.Unchanged(total)
            }

            is VlessSubscriptionFetcher.Result.Failed -> {
                clientData.saveVlessSubscription(
                    (previous ?: VlessSubscriptionState(url = normalizedUrl)).copy(
                        lastCheckedAt = now,
                        lastStatus = result.message,
                    )
                )
                LogManager.log("Подписка VLESS не загрузилась: ${result.message}")
                Outcome.Failed(result.message)
            }

            is VlessSubscriptionFetcher.Result.Ok -> {
                // Пустой результат по незнакомому адресу — не наша подписка. Сохранять
                // её как подписку VLESS нельзя: обновлять потом будет нечего, а
                // настоящий разбор AWG/WARP так и не случится.
                if (result.configs.isEmpty() && previous == null) {
                    LogManager.log("По адресу подписки нет ссылок VLESS — разбираем как обычный текст.")
                    return Outcome.NoVlessFound
                }
                val links = result.configs.map { it.toUri() }
                val sync = clientData.syncVlessSubscriptionProfiles(
                    freshLinks = links,
                    // Первый импорт ничего не удаляет: состава прошлой загрузки нет,
                    // и любой уже сохранённый профиль выглядел бы как «пропал». А вот
                    // состав прежней подписки при смене адреса учитываем — её узлы в
                    // новой подписке не числятся, значит им место в удалённых.
                    previousIdentities = (previous ?: stored)?.knownIdentities.orEmpty(),
                )
                if (replacedSubscription && sync.removed > 0) {
                    LogManager.log(
                        "Адрес подписки сменился: профили прежней подписки, которых нет " +
                            "в новой, удалены (${sync.removed})."
                    )
                }
                val changed = sync.added > 0 || sync.removed > 0
                clientData.saveVlessSubscription(
                    VlessSubscriptionState(
                        url = normalizedUrl,
                        title = result.metadata.title.ifBlank { previous?.title.orEmpty() },
                        etag = result.validators.etag,
                        lastModified = result.validators.lastModified,
                        // Интервал по умолчанию задаём явно, а не нулём: ноль
                        // разворачивался в те же 12 часов только внутри планировщика,
                        // а в настройках подписки читался как «не задан».
                        updateIntervalHours = result.metadata.updateIntervalHours.takeIf { it > 0 }
                            ?: previous?.updateIntervalHours?.takeIf { it > 0 }
                            ?: DEFAULT_INTERVAL_HOURS.toInt(),
                        lastCheckedAt = now,
                        lastChangedAt = if (changed) now else previous?.lastChangedAt ?: now,
                        lastStatus = if (changed) "обновлена" else "не менялась",
                        knownIdentities = result.configs.map { it.identity },
                    )
                )
                syncSchedule(appContext)
                LogManager.log(
                    "Подписка VLESS загружена: в подписке ${result.configs.size}, " +
                        "добавлено ${sync.added}, удалено ${sync.removed}, всего ${sync.total}."
                )
                if (changed) {
                    Outcome.Updated(sync.added, sync.removed, sync.total, result.stats)
                } else {
                    Outcome.Unchanged(sync.total)
                }
            }
        }
    }

    /** Ставит или снимает периодическое обновление в зависимости от наличия подписки. */
    fun syncSchedule(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        val subscription = ClientData(appContext).getVlessSubscription()
        if (subscription == null) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val hours = subscription.updateIntervalHours
            .takeIf { it > 0 }
            ?.toLong()
            ?.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
            ?: DEFAULT_INTERVAL_HOURS
        val request = PeriodicWorkRequestBuilder<VlessSubscriptionWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            // UPDATE, а не REPLACE: смена интервала не должна отменять уже отсчитанное
            // время и откладывать ближайшее обновление на новый полный период.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
