package com.example.nova

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Выпуск и обновление Proton-профилей.
 *
 * Кнопки нет: работа начинается от выбора региона «AWG Proton». Поэтому здесь
 * синглтон, а не задача экрана — генерация переживает уход с экрана и поворот, и
 * повторный вход в настройки подхватывает уже идущий прогон, а не запускает второй.
 *
 * Порядок работы: личность → сессия → список узлов → регистрация ключа → замер
 * рукопожатием → отбор пятидесяти лучших по задержке.
 */
object ProtonProfileManager {

    /** Сколько профилей должно получиться. */
    const val TARGET_COUNT = 50

    /** Сколько кандидатов разрешено перебрать, чтобы набрать [TARGET_COUNT]. */
    const val CANDIDATE_LIMIT = 80

    /** Потолок ожидания замера: 80 кандидатов по 12 в ряд с трёхсекундным сроком. */
    private const val PROBE_WAIT_MS = 120_000L

    /** Перевыпуск за месяц до конца сертификата: молча умерший профиль хуже лишнего прогона. */
    private const val CERT_RENEW_MARGIN_MS = 30L * 24 * 60 * 60 * 1000

    fun interface StatusListener {
        fun onStatus(text: String)
    }

    /** Итог прогона: доведён ли набор до цели и к чему подключаться. */
    data class Outcome(val profiles: List<ProtonProfile>, val message: String, val ready: Boolean)

    private val running = AtomicBoolean(false)
    private val lastStatus = AtomicReference("")
    private val listeners = CopyOnWriteArrayList<StatusListener>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ProtonProfiles") }

    /**
     * Пользователь выбрал Proton и ещё не передумал.
     *
     * Живёт здесь, а не на экране, по двум причинам. Во-первых, подготовка переживает
     * поворот: у нового экземпляра `SettingsActivity` своё поле было бы пустым, и
     * переключатель возвращался бы на прежний регион, а брошенный опрос старого
     * экземпляра всё равно доводил бы выпуск до конца. Во-вторых, отменить её должно
     * быть чем: выбор другого транспорта обязан отменять именно **выпуск**, иначе
     * прогон доходит до конца и записывает регион «proton» поверх уже сделанного
     * явного выбора — то самое молчаливое переопределение, которое запрещает I1.
     */
    private val preparationRequested = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Выбор Proton сделан: держится до успеха, отказа или выбора другого транспорта. */
    fun markPreparationRequested() {
        preparationRequested.set(true)
    }

    /**
     * Пользователь передумал или подготовка закончилась.
     *
     * Сам прогон не прерывается: он уже в сети и обрывать его посреди регистрации
     * ключа незачем. Отменяется **применение** итога — регион и подключение.
     */
    fun cancelPreparation() {
        preparationRequested.set(false)
    }

    fun isPreparationRequested(): Boolean = preparationRequested.get()

    fun currentStatus(): String = lastStatus.get()

    fun addListener(listener: StatusListener) {
        listeners += listener
        lastStatus.get().takeIf { it.isNotBlank() }?.let(listener::onStatus)
    }

    fun removeListener(listener: StatusListener) {
        listeners -= listener
    }

    private fun publish(text: String) {
        lastStatus.set(text)
        listeners.forEach { runCatching { it.onStatus(text) } }
    }

    /**
     * Запускает прогон, если он ещё не идёт.
     *
     * @param onFinished вызывается в рабочем потоке ровно один раз за прогон.
     * @return false, если прогон уже шёл — тогда [onFinished] вызван не будет, а
     *         экран увидит происходящее через слушателя статуса.
     */
    fun ensureProfiles(
        context: Context,
        force: Boolean = false,
        onFinished: ((Outcome) -> Unit)? = null,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        worker.execute {
            val outcome = try {
                run(appContext, force)
            } catch (e: Exception) {
                LogManager.log("Proton: прогон упал — ${e.message}")
                Outcome(emptyList(), "Proton: ошибка — ${shortReason(e)}", false)
            } finally {
                running.set(false)
            }
            publish(outcome.message)
            onFinished?.let { runCatching { it(outcome) } }
        }
        return true
    }

    private fun shortReason(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).take(48)

    private fun run(context: Context, force: Boolean): Outcome {
        val store = ProtonProfileStore(context)

        publish("Proton: проверяю профили")
        val existing = store.readProfiles()
        val account = store.readAccount()
        val certAlive = account != null &&
            account.certExpiresAt > System.currentTimeMillis() + CERT_RENEW_MARGIN_MS

        // Сохранённый список принимается только если он **измерен**. Профили без
        // единого ответа на пробу лежат в порядке нагрузки, а не задержки, и
        // считать такой набор готовым значило бы навсегда закрепить неудачный
        // замер: следующий заход просто вернул бы его же.
        val measured = existing.any { it.pingMs > 0 }
        if (!force && certAlive && existing.size >= TARGET_COUNT && measured) {
            // Их могло накопиться больше цели — оставляем лучшие и не ходим в сеть.
            val trimmed = existing.sortedBy { effectivePing(it) }.take(TARGET_COUNT)
            if (trimmed.size != existing.size) store.writeProfiles(trimmed)
            val best = trimmed.firstOrNull()
            return Outcome(
                profiles = trimmed,
                message = "Proton: готово, ${trimmed.size} шт., лучший ${best?.pingMs ?: 0} мс",
                ready = true,
            )
        }

        val device = account?.device ?: ProtonProfileStore.buildDeviceProfile()
        val seed = account?.seed?.takeIf { certAlive } ?: ProtonCrypto.randomSeed()

        publish("Proton: создаю сессию")
        val session = ProtonApi.createCredentiallessSession(device)

        publish("Proton: беру список серверов")
        // Отказ этого шага — не отказ прогона: ниже лежит встроенный список. Из
        // России `/vpn/logicals` не возвращает пустоту, а **подвисает до таймаута**
        // (P3), то есть выходит исключением — и без этого перехвата запас,
        // положенный ровно на этот случай, не доставался никогда.
        val liveServers = try {
            ProtonApi.fetchFreeServers(device, session)
        } catch (e: Exception) {
            LogManager.log("Proton: /vpn/logicals не ответил — ${e.message}")
            emptyList()
        }
        // Живой список всегда в приоритете, встроенный — запасной. Из России падает
        // ровно этот шаг: маленькие вызовы по альтернативному маршруту проходят, а
        // `/vpn/logicals` отдаёт 8–21 КБ и подвисает до таймаута (P3). Без запаса
        // генератор на такой сети не доходил до конца никогда.
        val servers = if (liveServers.isNotEmpty()) {
            LogManager.log("Proton: получено ${liveServers.size} бесплатных узлов.")
            liveServers
        } else {
            val bundled = ProtonNodeCatalog.load(context)
            if (bundled.isEmpty()) {
                return Outcome(emptyList(), "Proton: серверы не выданы", false)
            }
            // Молчаливая подмена источника здесь читалась бы как живой список (I4),
            // а он старее и без нагрузки — по нему нельзя судить о загруженности.
            LogManager.log(
                "Proton: список серверов от API не пришёл, берём встроенный — " +
                    "${bundled.size} узлов из прошивки."
            )
            publish("Proton: беру встроенный список узлов")
            bundled
        }

        // Живой сертификат переиспользуется: он выдаётся на год, и повторная
        // регистрация того же ключа — лишний запрос без единого последствия.
        val certExpiresAt = account?.certExpiresAt?.takeIf { certAlive } ?: run {
            publish("Proton: регистрирую ключ")
            ProtonApi.registerClientKey(device, session, ProtonCrypto.ed25519PublicKeyPem(seed))
        }

        store.writeAccount(
            ProtonProfileStore.Account(
                seed = seed,
                uid = session.uid,
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                certExpiresAt = certExpiresAt,
                device = device,
            )
        )

        val whiteHosts = runCatching { TrafficMaskCatalog.getWhiteHosts(context) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        val random = Random(System.nanoTime())

        // Кандидаты: сначала наименее загруженные, портов — по кругу. Если оператор
        // глушит один порт, кандидаты на нём просто не ответят и отсеются замером.
        val candidates = servers
            .sortedWith(compareBy<ProtonApi.Server> { it.load }.thenBy { it.score })
            .take(CANDIDATE_LIMIT)
            .mapIndexed { index, server ->
                val sni = whiteHosts.randomOrNull(random).orEmpty()
                ProtonProfile(
                    serverName = server.name,
                    country = server.country,
                    city = server.city,
                    entryIp = server.entryIp,
                    port = ProtonProfileStore.PORTS[index % ProtonProfileStore.PORTS.size],
                    peerPublicKey = server.peerPublicKey,
                    pingMs = -1,
                    load = server.load,
                    sni = sni,
                    // Параметры мусора — ровно те, что стоят на сайте по умолчанию
                    // и проверены владельцем на живом подключении через Amnezia.
                    // Свои случайные (3-7 пакетов по 30-120 байт) отличались от
                    // рабочего конфига, и расхождение было незачем: выигрыша от них
                    // никто не измерял, а сравнивать с эталоном они мешали.
                    junkCount = 3,
                    junkMin = 1,
                    junkMax = 3,
                    i1 = if (sni.isBlank()) "" else ProtonQuicInitial.buildI1(sni),
                    createdAt = System.currentTimeMillis(),
                )
            }

        // Кандидаты уходят в файл, а мерит их служба: `protect()` есть только у
        // `VpnService`, а незащищённый сокет ушёл бы внутрь поднятого туннеля и
        // ранжировал бы профили по его каналу, а не по прямому пути до Proton.
        store.writeProfiles(candidates)
        store.writeProbeState(
            ProtonProfileStore.ProbeState(
                ProtonProfileStore.STATE_REQUESTED,
                candidates.size,
                0,
                0,
            )
        )
        publish("Proton: проверка 0/${candidates.size}")
        requestProbe(context)

        val selected = awaitProbe(store, candidates.size)
        if (selected == null) {
            return Outcome(emptyList(), "Proton: проверка не завершилась", false)
        }

        val alive = store.readProbeState()?.alive ?: 0
        return when {
            selected.isEmpty() -> Outcome(selected, "Proton: серверы не отобрались", false)
            // Ни один узел не ответил на пробу — профили сохранены по нагрузке, и
            // говорить «лучший N мс» было бы враньём: замера нет.
            alive == 0 -> Outcome(
                selected,
                "Proton: ${selected.size} шт., замер не прошёл",
                true,
            )
            selected.size < TARGET_COUNT -> Outcome(
                selected,
                "Proton: только ${selected.size} из $TARGET_COUNT",
                true,
            )
            else -> Outcome(
                selected,
                "Proton: готово, ${selected.size} шт., лучший ${selected.first().pingMs} мс",
                true,
            )
        }
    }

    private fun requestProbe(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_PROBE_PROTON_PROFILES
                },
            )
        }.onFailure { error ->
            LogManager.log("Proton: не удалось попросить службу о замере — ${error.message}")
        }
    }

    /**
     * Ждёт, пока служба домерит.
     *
     * Опрос файла, а не широковещание: состояние всё равно обязано жить в файле —
     * его пишет `:vpn`, а читает интерфейс, и `SharedPreferences` между процессами
     * не работают (I2). Заводить поверх этого ещё и рассылку значило бы держать два
     * источника одной истины.
     *
     * @return отобранные профили, либо null, если служба не ответила за отведённое
     *         время. Пустой список — законный ответ «никто не отозвался».
     */
    private fun awaitProbe(store: ProtonProfileStore, total: Int): List<ProtonProfile>? {
        val deadline = System.currentTimeMillis() + PROBE_WAIT_MS
        var lastDone = -1
        while (System.currentTimeMillis() < deadline) {
            val state = store.readProbeState()
            if (state != null) {
                if (state.done != lastDone) {
                    lastDone = state.done
                    publish("Proton: проверка ${state.done}/${state.total.takeIf { it > 0 } ?: total}")
                }
                if (state.state == ProtonProfileStore.STATE_FAILED) return emptyList()
                if (state.state == ProtonProfileStore.STATE_DONE) return store.readProfiles()
            }
            try {
                Thread.sleep(700L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun effectivePing(profile: ProtonProfile): Int =
        if (profile.pingMs > 0) profile.pingMs else Int.MAX_VALUE
}
