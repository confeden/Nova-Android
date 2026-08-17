package com.example.nova

/**
 * Уход с узла, чей обратный поток не возвращается, — без обрыва для пользователя.
 *
 * Зачем. Замер на Mi A1 (Ростелеком): узел `8.47.69.6:945` за десять минут дал
 * 83,5% потерь, при этом **каждое** из двадцати пяти рукопожатий проходило за
 * 37 мс, а `rx` оставался нулевым. Пересборка сессии такую поломку не лечит —
 * лечит смена пути. До сих пор ничто не уводило с плохого узла на живой сессии:
 * замеры churn и удержания влияли только на порядок следующего перебора, то
 * есть на то, что будет после ручного переподключения.
 *
 * Почему это можно сделать бесшовно. У всех пятидесяти встроенных сидов один и
 * тот же внутренний адрес `172.16.0.2`, а внутри одной личности совпадают ещё и
 * приватный ключ с IPv6-адресом — различается только `Endpoint`. Значит переход
 * между узлами **одной личности** — это правка одного поля пира по UAPI:
 * интерфейс, его адреса, маршруты и все открытые сокеты приложений остаются на
 * месте. Наружу это неотличимо от штатного роуминга WireGuard при смене сети.
 * Двух крупнейших личностей хватает: 21 и 17 узлов из пятидесяти.
 *
 * Смена личности сюда не входит намеренно: другой приватный ключ означает
 * другой IPv6 внутри туннеля, а это уже переустановка интерфейса с обрывом
 * всех соединений — то самое, чего требовалось избежать.
 *
 * Класс чистый: он только решает, куда идти и когда остановиться. Порядок
 * кандидатов задаёт вызывающая сторона — там живут замеры качества.
 */
class LiveNodeRotation(
    private val maxRotationsPerSession: Int = MAX_ROTATIONS_PER_SESSION,
    private val settleMs: Long = SETTLE_MS,
) {

    /**
     * Кандидат на переход.
     *
     * @param rank меньше — предпочтительнее; считается снаружи по замерам
     */
    data class Candidate(
        val host: String,
        val port: Int,
        val rank: Int,
    ) {
        val key: String get() = "$host:$port"
    }

    sealed class Decision {
        /** Переходим на этот узел прямо сейчас. */
        data class Switch(
            val host: String,
            val port: Int,
            val ordinal: Int,
            val remaining: Int,
        ) : Decision() {
            val endpoint: String get() = "$host:$port"
        }

        /** Ждём: недавно уже переходили, узлу нужно дать проявить себя. */
        data class Settling(val waitedMs: Long) : Decision()

        /** Бесшовно уже некуда — дальше только обычная машинерия переподключения. */
        data class Exhausted(val reason: String) : Decision()
    }

    /** Узлы, брошенные в этой сессии: назад к ним не возвращаемся. */
    private val burned = LinkedHashSet<String>()
    private var rotations = 0
    private var lastRotationUptimeMs = 0L

    val rotationCount: Int get() = rotations

    fun reset() {
        burned.clear()
        rotations = 0
        lastRotationUptimeMs = 0L
    }

    /**
     * Решает, куда уходить с узла [currentHost]:[currentPort].
     *
     * Вызывается только когда провал подтверждён и форсированное рукопожатие
     * его не вылечило.
     */
    fun decide(
        nowUptimeMs: Long,
        currentHost: String,
        currentPort: Int,
        candidates: List<Candidate>,
    ): Decision {
        // Свежепереключённому узлу нужно время на рукопожатие и первые пакеты.
        // Без этой паузы одна затянувшаяся тишина сожгла бы всю личность за
        // несколько секунд, и виноват оказался бы не путь, а спешка.
        if (lastRotationUptimeMs != 0L) {
            val since = nowUptimeMs - lastRotationUptimeMs
            if (since in 0 until settleMs) {
                return Decision.Settling(since)
            }
        }

        if (rotations >= maxRotationsPerSession) {
            return Decision.Exhausted(
                "исчерпан лимит бесшовных переходов ($maxRotationsPerSession) за сессию"
            )
        }

        val currentKey = "$currentHost:$currentPort"
        if (currentHost.isNotBlank() && currentPort in 1..65535) {
            burned.add(currentKey)
        }

        val next = candidates
            .asSequence()
            .filter { it.host.isNotBlank() && it.port in 1..65535 }
            .filter { it.key != currentKey }
            .filter { it.key !in burned }
            .minByOrNull { it.rank }
            ?: return Decision.Exhausted("у этой личности не осталось непробованных узлов")

        burned.add(next.key)
        rotations += 1
        lastRotationUptimeMs = nowUptimeMs
        return Decision.Switch(
            host = next.host,
            port = next.port,
            ordinal = rotations,
            remaining = maxRotationsPerSession - rotations,
        )
    }

    /** Узел, который держит поток, из списка брошенных не выкидывается: он там и не был. */
    fun isBurned(host: String, port: Int): Boolean = "$host:$port" in burned

    companion object {
        /**
         * Столько бесшовных переходов за сессию, дальше — общая машинерия.
         *
         * Если два узла одной личности подряд не везут трафик, дело уже не в
         * узле: сеть, личность или маршрут целиком. Продолжать перебор внутри
         * личности значит прятать общий отказ за бесшовностью.
         *
         * Было четыре. Измерено на Mi A1 с искусственно оборванным входящим
         * потоком: **восемь переходов за два прогона, и ни один не вернул
         * трафик** — рукопожатие на новом узле проходило за 57 мс, а данные не
         * шли. Восстановило связь только обычное переподключение, до которого
         * каждый лишний переход откладывал дело на 22 секунды. Два — это ещё
         * дешёвая проверка гипотезы «плохой узел» и уже не задержка.
         */
        const val MAX_ROTATIONS_PER_SESSION = 2

        /**
         * Пауза после перехода, за которую новый узел обязан себя показать.
         *
         * Рукопожатие в замерах занимало 34–37 мс, но первые данные идут не
         * мгновенно, а детектор провала копит восемь секунд тишины. Двенадцать
         * секунд дают запас на оба этапа и при этом заметно меньше, чем время
         * до следующего подтверждённого провала.
         */
        const val SETTLE_MS = 12_000L
    }
}
