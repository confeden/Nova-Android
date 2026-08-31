package com.example.nova

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Гонка одного и того же запроса по нескольким адресам: выигрывает первый
 * ответивший, остальные обрываются.
 *
 * Зачем: шесть запасных узлов Proton, перебираемые по очереди, стоили **2 мин 27 с**
 * молчания на одном `/vpn/logicals` (Mi A1, Ростелеком, чистая установка) — и все
 * шесть кончились таймаутом. Последовательный перебор платит полным сроком за
 * каждый неответивший адрес, параллельный — один раз за всех. На той же сети это
 * 28 с вместо 147 с.
 *
 * Примитив общий, несмотря на имя: им же гоняются запасные входы Cloudflare в
 * `MainActivity.firstTrace` — задача там ровно та же, и второй копии этого кода
 * заводить нечего.
 *
 * **Только для идемпотентных запросов.** `/auth/v4/credentialless` привязывает
 * сессию к пользователю, и второй дошедший запрос получает
 * `400 Session already tied to a user` — ровно та поломка, ради которой в
 * [ProtonApi.createCredentiallessSession] написан повтор. Запросы с телом обязаны
 * идти по очереди, и [ProtonApi] решает это по `body != null`, а не по имени пути:
 * новый POST не должен попасть в гонку по недосмотру.
 */
internal object ProtonRace {

    /**
     * Пул создаётся лениво и живёт до конца процесса: потоки демонские и
     * освобождаются сами после минуты простоя, а за прогон генератора гонка
     * запускается от силы трижды.
     */
    private val executor: ExecutorService by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "proton-race").apply { isDaemon = true }
        }
    }

    /**
     * @param attempts попытки; каждая возвращает результат или null — «не вышло».
     *        Исключение из попытки считается тем же «не вышло»: разбирать причину —
     *        дело вызывающего, он единственный знает, чем отличается «не достучались»
     *        от «ответили ошибкой».
     * @param cancelAll обрывает всё ещё бегущие запросы, как только появился
     *        победитель: без этого проигравшие досиживали бы до своего таймаута,
     *        держа потоки и сокеты, и выигрыш по времени достался бы только
     *        вызывающему, но не системе.
     * @return результат первой удавшейся попытки, либо null, если не удалась ни
     *         одна. Во втором случае функция дожидается **всех**: вызывающий решает
     *         по накопленным ошибкам, идти ли на обходной маршрут, а решение по
     *         половине ответов было бы решением по случайной половине.
     */
    fun <T : Any> firstSuccess(attempts: List<() -> T?>, cancelAll: () -> Unit): T? {
        if (attempts.isEmpty()) return null
        // Одиночную попытку гоняем на месте: поток и очередь тут ничего не ускоряют.
        if (attempts.size == 1) return attempts.first().invoke()

        val completion = ExecutorCompletionService<T?>(executor)
        attempts.forEach { attempt -> completion.submit(Callable { attempt() }) }

        var winner: T? = null
        var interrupted = false
        var pending = attempts.size
        while (pending > 0) {
            pending--
            val completed = try {
                completion.take()
            } catch (_: InterruptedException) {
                // Прерывание — это отмена попытки подключения снаружи, и проглотить
                // его нельзя: `take()` снимает флаг, поэтому следующий заход снова
                // ждал бы полный срок. Снаружи это выглядит как «подключение
                // зависло, кнопка не отвечает».
                interrupted = true
                break
            }
            val result = runCatching { completed.get() }.getOrNull() ?: continue
            winner = result
            break
        }
        if (winner != null || interrupted) runCatching { cancelAll() }
        if (interrupted) Thread.currentThread().interrupt()
        return winner
    }
}
