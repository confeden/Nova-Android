package com.example.nova

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** Шаг счётчика у длинных сетевых шагов, см. [publishTicking]. */
    private const val TICK_PERIOD_SECONDS = 2L

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
     * Аренда прогона, взятая этим процессом, и её старшинство.
     *
     * Хранится здесь, а не передаётся по цепочке вызовов, ровно потому, что
     * признак жизни ставится из [publish] — а туда доходит и тик длинного шага,
     * который о владельце ничего не знает.
     */
    private val activeLease = AtomicReference<Pair<String, Int>?>(null)

    /** Что уже сказано журналу: тики одного шага не должны его засорять. */
    private val lastLoggedStep = AtomicReference("")

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

    /**
     * @param step true — это новый шаг прогона, а не тик секундомера внутри него.
     *
     * Шаги пишутся в журнал. Раньше не писался ни один: строки уходили только
     * слушателям экрана, и на устройстве, где экран уже закрыт, весь прогон
     * оставлял в журнале ровно одну строку — свой итог. Разобрать по такому
     * журналу, на каком шаге всё встало, нельзя было в принципе (I4).
     */
    private fun publish(text: String, step: Boolean = true) {
        lastStatus.set(text)
        if (step && lastLoggedStep.getAndSet(text) != text) LogManager.log(text)
        activeLease.get()?.let { (owner, priority) ->
            runCatching { leaseStore?.heartbeatRunLease(owner, priority) }
        }
        listeners.forEach { runCatching { it.onStatus(text) } }
    }

    /**
     * Хранилище для признака жизни аренды.
     *
     * Отдельным полем, потому что [publish] вызывается и из тика, у которого на
     * руках нет ни контекста, ни хранилища.
     */
    @Volatile
    private var leaseStore: ProtonProfileStore? = null

    /**
     * Шаг с бегущим счётчиком секунд.
     *
     * Сетевые шаги прогона длинные и без единого признака жизни: «беру список
     * серверов» висело на экране 2 мин 27 с одной и той же строкой, потому что
     * весь шаг — один вызов, внутрь которого экран не видит. Гонка по хостам
     * сократила это до ~23 с, но неподвижная строка и на двадцать третьей секунде
     * читается как зависание. Счётчик ничего не обещает и ничего не предсказывает
     * — он только показывает, что работа идёт.
     */
    private fun <T> publishTicking(text: String, block: () -> T): T {
        publish(text)
        val ticker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ProtonStatusTick").apply { isDaemon = true }
        }
        val startedAt = System.nanoTime()
        ticker.scheduleWithFixedDelay(
            {
                val seconds = (System.nanoTime() - startedAt) / 1_000_000_000L
                runCatching { publish("$text — $seconds с", step = false) }
            },
            TICK_PERIOD_SECONDS,
            TICK_PERIOD_SECONDS,
            TimeUnit.SECONDS,
        )
        return try {
            block()
        } finally {
            // `shutdownNow`, а не `shutdown`: иначе уже поставленный тик успел бы
            // перебить статус следующего шага собственным, устаревшим.
            ticker.shutdownNow()
        }
    }

    /**
     * Запускает прогон, если он ещё не идёт.
     *
     * @param background прогон никто не ждёт: он идёт сам по себе при живом
     *        подключении, чтобы к моменту выбора Proton всё уже было готово.
     *        Такой прогон уступает явному выбору пользователя и не берётся, пока
     *        тот идёт.
     * @param onFinished вызывается в рабочем потоке ровно один раз за прогон.
     * @return false, если прогон уже шёл — тогда [onFinished] вызван не будет, а
     *         экран увидит происходящее через слушателя статуса.
     */
    fun ensureProfiles(
        context: Context,
        force: Boolean = false,
        background: Boolean = false,
        onFinished: ((Outcome) -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val store = ProtonProfileStore(appContext)
        val priority = if (background) {
            ProtonProfileStore.RUN_PRIORITY_BACKGROUND
        } else {
            ProtonProfileStore.RUN_PRIORITY_USER
        }
        val owner = "${if (background) "bg" else "user"}:${android.os.Process.myPid()}"
        if (!running.compareAndSet(false, true)) return false
        if (!store.tryAcquireRunLease(owner, priority)) {
            running.set(false)
            LogManager.log(
                "Proton: выпуск уже ведёт другой процесс (${store.readRunLease()?.owner ?: "?"}) — " +
                    "второй прогон не начинаем."
            )
            return false
        }
        activeLease.set(owner to priority)
        leaseStore = store
        lastLoggedStep.set("")
        worker.execute {
            // Заход мимо собственного туннеля живёт ровно столько, сколько прогон.
            //
            // Владелец попросил, чтобы профили получались и без VPN, и при
            // поднятом — «как получится». Обычный путь при живом туннеле идёт
            // внутрь него; этот клиент даёт тому же запросу второй шанс снаружи.
            // Ставится здесь, а не в самом [ProtonApi], потому что там нет и не
            // должно быть `Context`. Снимается в `finally`: привязка сделана к
            // конкретной сети, и пережить смену сети она не должна.
            ProtonApi.useBypassClient(buildBypassClient(appContext))
            val outcome = try {
                run(appContext, store, owner, force)
            } catch (e: Exception) {
                LogManager.log("Proton: прогон упал — ${e.message}")
                Outcome(emptyList(), "Proton: ошибка — ${shortReason(e)}", false)
            } finally {
                ProtonApi.useBypassClient(null)
                activeLease.set(null)
                leaseStore = null
                store.releaseRunLease(owner)
                // Мост до релея слушает порт на петле, и держать его дольше самой
                // работы незачем: следующий прогон поднимет его заново за одно
                // TLS-рукопожатие.
                runCatching { ProtonRelay.shutdown() }
                running.set(false)
            }
            publish(outcome.message)
            onFinished?.let { runCatching { it(outcome) } }
        }
        return true
    }

    /**
     * Клиент, ходящий по сети под туннелем: и сокет, и разрешение имени.
     *
     * Одной `socketFactory` мало. Имя разрешает `Dns.SYSTEM`, то есть системный
     * резолвер, а он при живом туннеле спрашивает **его** DNS — привязанный сокет
     * тогда шёл бы на адрес, полученный изнутри туннеля. `Network.getAllByName`
     * спрашивает резолвер самой этой сети, и обход получается настоящим.
     *
     * Сроки те же, что у прямых хостов в [ProtonApi]: хост, не открывший
     * соединение за шесть секунд, не откроет его и за тридцать.
     *
     * `null` — сети под туннелем не нашлось (или VPN не поднят вовсе, и обходить
     * нечего). Тогда всё работает как раньше.
     */
    private fun buildBypassClient(context: Context): okhttp3.OkHttpClient? {
        val network = UnderlyingNetwork.select(context) ?: return null
        return runCatching {
            okhttp3.OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> =
                        network.getAllByName(hostname).toList()
                })
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }.getOrNull()
    }

    /**
     * Прогон продолжается, только пока аренда наша.
     *
     * Отобрать её может лишь явный выбор Proton пользователем; фоновая подготовка
     * при этом обязана закончиться, не записав ни профилей, ни личности, — иначе
     * она перезапишет чужой ключ, а сервер помнит только последний.
     */
    private fun holdsLease(store: ProtonProfileStore, owner: String): Boolean =
        store.ownsRunLease(owner)

    private fun shortReason(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).take(48)

    private fun run(
        context: Context,
        store: ProtonProfileStore,
        owner: String,
        force: Boolean,
    ): Outcome {
        // Узел, через который прошлый заход достучался до API, подсказывается до
        // первого запроса: поиск через DoH — самое хрупкое звено, и пропустить его
        // значит продолжить прогон, а не начать его заново.
        ProtonApi.seedAlternativeHost(store.readAlternativeHost())

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
        if (!force && !needsLiveNodes(store) && certAlive && existing.size >= TARGET_COUNT && measured) {
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

        // Попытка отмечается здесь, до первого сетевого шага. Всё, что ниже, может
        // выйти исключением, и тогда отметка ниже по тексту не выполнится вовсе —
        // а `needsLiveNodes` останется истинным навсегда и заведёт этот же прогон
        // через пятнадцать минут, и так весь сеанс.
        store.writeNodesAttempt()

        val session = publishTicking("Proton: создаю сессию") {
            ProtonApi.createCredentiallessSession(device)
        }

        // Отказ этого шага — не отказ прогона: ниже лежит встроенный список. Из
        // России `/vpn/logicals` не возвращает пустоту, а **подвисает до таймаута**
        // (P3), то есть выходит исключением — и без этого перехвата запас,
        // положенный ровно на этот случай, не доставался никогда.
        val liveServers = publishTicking("Proton: беру список серверов") {
            try {
                ProtonApi.fetchFreeServers(device, session)
            } catch (e: Exception) {
                LogManager.log("Proton: /vpn/logicals не ответил — ${e.message}")
                emptyList()
            }
        }
        // Живой список всегда в приоритете, встроенный — запасной. Из России падает
        // ровно этот шаг: маленькие вызовы по альтернативному маршруту проходят, а
        // `/vpn/logicals` отдаёт 8–21 КБ и подвисает до таймаута (P3). Без запаса
        // генератор на такой сети не доходил до конца никогда.
        val servers = if (liveServers.isNotEmpty()) {
            LogManager.log("Proton: получено ${liveServers.size} бесплатных узлов.")
            // Момент попытки отмечается всегда, а источник — тот, что вышел. По этой
            // паре следующий заход решает, стоит ли снова ходить за живым списком:
            // без неё удавшийся прогон на встроенном списке выглядел как готовый
            // навсегда, и живой список не появлялся до переустановки.
            store.writeNodesSource(ProtonProfileStore.NODES_LIVE)
            liveServers
        } else {
            val bundled = ProtonNodeCatalog.load(context)
            if (bundled.isEmpty()) {
                store.writeNodesSource(ProtonProfileStore.NODES_BUNDLED)
                return Outcome(emptyList(), "Proton: серверы не выданы", false)
            }
            // Молчаливая подмена источника здесь читалась бы как живой список (I4),
            // а он старее и без нагрузки — по нему нельзя судить о загруженности.
            LogManager.log(
                "Proton: список серверов от API не пришёл, берём встроенный — " +
                    "${bundled.size} узлов из прошивки. Живой список попробуем снова " +
                    "не раньше чем через ${ProtonProfileStore.NODES_REFRESH_MS / 3_600_000} ч."
            )
            store.writeNodesSource(ProtonProfileStore.NODES_BUNDLED)
            publish("Proton: беру встроенный список узлов")
            bundled
        }

        // Проверка вплотную к регистрации ключа, а не только перед записью личности:
        // сервер помнит **последний** зарегистрированный ключ, и прогон, потерявший
        // аренду, отобрал бы ключ у победителя, ничего при этом не записав.
        if (!holdsLease(store, owner)) return preempted()

        // Живой сертификат переиспользуется: он выдаётся на год, и повторная
        // регистрация того же ключа — лишний запрос без единого последствия.
        val certExpiresAt = account?.certExpiresAt?.takeIf { certAlive } ?: run {
            publishTicking("Proton: регистрирую ключ") {
                ProtonApi.registerClientKey(device, session, ProtonCrypto.ed25519PublicKeyPem(seed))
            }
        }

        // Последняя точка, где уступить ещё бесплатно: дальше идёт запись личности,
        // а она общая для обоих процессов.
        if (!holdsLease(store, owner)) return preempted()

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

        // Порядок здесь больше ничего не решает: `writeAccount` правит файл, а не
        // пересобирает его, и чужие поля переживают запись. Раньше решал — узел
        // приходилось писать строго после аккаунта, иначе его вымывало.
        store.writeAlternativeHost(ProtonApi.currentAlternativeHost())

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
                    pingSource = ProtonLatency.SOURCE_NONE,
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

        if (!holdsLease(store, owner)) return preempted()

        // Кандидаты уходят в **свой** файл, а мерит их служба: `protect()` есть
        // только у `VpnService`, а незащищённый сокет ушёл бы внутрь поднятого
        // туннеля и ранжировал бы профили по его каналу, а не по прямому пути до
        // Proton.
        //
        // Именно в свой, а не поверх рабочего списка: до этой правки восемьдесят
        // неизмеренных кандидатов лежали в `proton_profiles.json` всё время замера,
        // и очередь подключения, пересобранная в этом окне, строилась из них
        // (см. `ProtonProfileStore.candidatesFile`).
        store.writeCandidates(candidates)
        store.writeProbeState(
            ProtonProfileStore.ProbeState(
                ProtonProfileStore.STATE_REQUESTED,
                candidates.size,
                0,
                0,
            )
        )
        publish("Proton: проверка 0/${candidates.size}")
        val probeRequested = requestProbe(context)

        val probed = if (probeRequested) awaitProbe(store, candidates.size) else null

        // Замер не дошёл до конца — но список-то выпущен, и выбрасывать его значит
        // отдать пользователю «Proton не готов» там, где готово всё, кроме порядка.
        // Раньше рабочий файл к этому моменту уже содержал кандидатов (их писали
        // прямо в него), и неудача замера оставляла их на месте сама собой. Теперь
        // кандидаты лежат отдельно, поэтому запасной путь нужен явный.
        val selected = probed?.takeIf { it.isNotEmpty() } ?: run {
            val fallback = candidates.sortedBy { it.load }.take(TARGET_COUNT)
            LogManager.log(
                if (!probeRequested) {
                    "Proton: службу не удалось попросить о замере"
                } else if (probed == null) {
                    "Proton: замер не завершился за ${PROBE_WAIT_MS / 1000} с"
                } else {
                    "Proton: служба отказалась мерить (${store.readProbeState()?.state ?: "?"})"
                } + " — сохраняем ${fallback.size} профилей в порядке нагрузки. " +
                    "Порядок не по задержке, но список рабочий."
            )
            store.writeProfiles(fallback)
            store.writeProbeState(
                ProtonProfileStore.ProbeState(
                    ProtonProfileStore.STATE_DONE,
                    candidates.size,
                    candidates.size,
                    0,
                )
            )
            return Outcome(fallback, "Proton: ${fallback.size} шт., замер не прошёл", true)
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

    /**
     * Есть ли всё, без чего выбор Proton не поднимется: личность, живой сертификат и
     * полный список узлов.
     *
     * Замер сюда **намеренно не входит**, хотя [run] без него в сеть всё-таки идёт.
     * Разница по назначению: у прогона замер — это ранжирование, и переделать его при
     * явном выборе полезно. А для фоновой подготовки «замер не прошёл» — это
     * состояние сети, а не незаконченная работа: на сети, где узлы Proton не отвечают
     * на пробу (открытый P11), список никогда не станет измеренным, и подготовка
     * заводила бы полный сорокасекундный прогон каждые пятнадцать минут до конца
     * сеанса, ничего этим не меняя.
     */
    fun isPreparationComplete(context: Context): Boolean {
        val store = ProtonProfileStore(context)
        val account = store.readAccount() ?: return false
        if (account.certExpiresAt <= System.currentTimeMillis() + CERT_RENEW_MARGIN_MS) return false
        if (store.readProfiles().size < TARGET_COUNT) return false
        return !needsLiveNodes(store)
    }

    /**
     * Пора ли снова пытаться получить **живой** список узлов.
     *
     * Тот же предикат стоит и в [run], и в [isPreparationComplete], и это не
     * дублирование, а условие их согласия. Разойдись они — и получается вечный
     * цикл: подготовка считает работу незаконченной и заводит прогон, прогон
     * считает набор готовым и возвращается из кэша, не сходив в сеть и ничего не
     * изменив, — и так на каждом сердцебиении службы, то есть раз в 45-120 с весь
     * сеанс. Именно поэтому отметка времени пишется на **каждую** попытку, а не на
     * удачную: без этого предикат никогда не перестал бы быть истинным.
     */
    private fun needsLiveNodes(store: ProtonProfileStore): Boolean {
        if (store.readNodesSource() == ProtonProfileStore.NODES_LIVE) return false
        val checkedAt = store.readNodesCheckedAt()
        return System.currentTimeMillis() - checkedAt > ProtonProfileStore.NODES_REFRESH_MS
    }

    /** Итог прогона, у которого аренду забрал явный выбор пользователя. */
    private fun preempted(): Outcome {
        LogManager.log("Proton: выпуск перехватил явный выбор пользователя — фоновый прогон заканчиваем.")
        return Outcome(emptyList(), "Proton: подготовку продолжает выбор пользователя", false)
    }

    /**
     * Просит службу померить кандидатов.
     *
     * @return false, если просьба не ушла. Отличать это обязательно: раньше отказ
     *         только писался в журнал, а прогон всё равно уходил ждать ответа
     *         службы **две минуты** — которого не будет, потому что мерить никто
     *         не начинал. Две минуты «Proton: проверка 0/80» на экране, а затем
     *         тот же запасной список, что можно было отдать сразу.
     */
    private fun requestProbe(context: Context): Boolean =
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_PROBE_PROTON_PROFILES
                },
            )
            true
        }.getOrElse { error ->
            LogManager.log("Proton: не удалось попросить службу о замере — ${error.message}")
            false
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
            // Признак жизни аренды: замер идёт до двух минут, а `publish` здесь
            // случается только при сдвиге счётчика — без этого аренда протухла бы
            // прямо посреди работающего прогона.
            activeLease.get()?.let { (owner, priority) ->
                runCatching { store.heartbeatRunLease(owner, priority) }
            }
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
