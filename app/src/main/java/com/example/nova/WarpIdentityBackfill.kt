package com.example.nova

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фоновая добыча WARP-личности через уже поднятый туннель.
 *
 * Зачем это нужно. Регистрация MASQUE требует access token и device id, а их
 * выдаёт только `api.cloudflareclient.com`. У российских провайдеров этот
 * домен режется по SNI, поэтому на части устройств личности нет вовсе —
 * и выбранный пользователем MASQUE не запускается, сколько его ни выбирай.
 *
 * Внутри туннеля блокировки провайдера нет: имя узла спрятано за шифрованием.
 * Поэтому как только связь поднялась и устоялась, Nova один раз тихо
 * регистрируется через туннель и откладывает результат про запас.
 *
 * Чего эта операция **не** делает:
 *  - не трогает активную конфигурацию и не переподключает туннель;
 *  - не переключает транспорт и ничего не показывает в интерфейсе;
 *  - не повторяется чаще раза в шесть часов при неудаче.
 */
object WarpIdentityBackfill {

    /** Пауза после подключения: нужен устоявшийся data-plane, а не первый успех. */
    private const val SETTLE_DELAY_MS = 25_000L

    private val running = AtomicBoolean(false)

    /**
     * @param ignoreCooldown не выдерживать шестичасовую паузу после неудачи: так
     * работает случай, когда MASQUE выбран пользователем и он ждёт результата прямо
     * сейчас
     * @param tunnelStillUp проверяется дважды — до и после паузы, а также
     * непосредственно перед запросом: за это время связь могла оборваться,
     * и тогда запрос ушёл бы напрямую в заблокированный домен.
     */
    fun scheduleAfterConnect(
        context: Context,
        logger: (String) -> Unit,
        tunnelStillUp: () -> Boolean,
        ignoreCooldown: Boolean = false,
        onIdentityReady: () -> Unit = {},
    ) {
        val clientData = ClientData(context)
        if (!clientData.shouldAttemptWarpIdentityBackfill(ignoreCooldown = ignoreCooldown)) return
        if (!running.compareAndSet(false, true)) return

        Thread(
            {
                try {
                    Thread.sleep(SETTLE_DELAY_MS)
                    if (!tunnelStillUp()) return@Thread
                    // Состояние могло измениться за время паузы: например,
                    // личность пришла обычным путём при переподключении.
                    if (!clientData.shouldAttemptWarpIdentityBackfill(ignoreCooldown = ignoreCooldown)) return@Thread

                    logger(
                        "Готового MASQUE-профиля нет, а туннель уже поднят. Тихо готовим его через туннель: " +
                            "внутри него блокировка api.cloudflareclient.com по SNI не действует. " +
                            "Текущее подключение не трогаем."
                    )

                    var reserve = clientData.getReserveWarpIdentity()
                    var registeredNow = false
                    if (reserve == null) {
                        val client = WarpClient(
                            context = context,
                            logger = logger,
                            shouldAbort = { !tunnelStillUp() },
                        )
                        val config = client.registerThroughActiveTunnel()
                        if (config == null || config.accessToken.isNullOrBlank() || config.deviceId.isNullOrBlank()) {
                            clientData.markWarpIdentityBackfillFailure()
                            logger(
                                "Фоновая регистрация через туннель не дала личность. " +
                                    "Повторим не раньше чем через пятнадцать минут."
                            )
                            return@Thread
                        }
                        clientData.saveReserveWarpIdentity(config)
                        reserve = config
                        registeredNow = true
                        logger("Фоновая регистрация через туннель прошла. Личность отложена про запас.")
                    }

                    // Лицензию применяем к каждому новому устройству.
                    //
                    // Личность приложение перевыпускает само при отказе Cloudflare, а
                    // введённый пользователем ключ живёт отдельно и переживает эти
                    // перевыпуски. Для самого MASQUE лицензия не нужна — бесплатный
                    // аккаунт служба обслуживает (замер 2026-08-12, см. register.go), —
                    // но заданный пользователем ключ надо переносить на новое устройство,
                    // иначе оплаченные скорость и маршруты теряются при перевыпуске.
                    val license = clientData.getWarpPlusLicense()
                    val knownAccountType = clientData.getWarpAccountType()
                    val alreadyLicensed = !registeredNow &&
                        knownAccountType.isNotBlank() &&
                        !knownAccountType.equals("free", ignoreCase = true)
                    if (license.isNotBlank() && !alreadyLicensed) {
                        runCatching {
                            nova.Nova.setWarpLicense(
                                reserve.accessToken.orEmpty(),
                                reserve.deviceId.orEmpty(),
                                license,
                            )
                        }.onSuccess { accountType ->
                            clientData.setWarpAccountType(accountType)
                            logger("Лицензия WARP+ привязана к устройству. Аккаунт: $accountType.")
                        }.onFailure { error ->
                            logger("Привязать лицензию WARP+ не удалось: ${error.message}")
                        }
                    }

                    if (!tunnelStillUp()) return@Thread
                    // Регистрация MASQUE переводит устройство Cloudflare в режим
                    // masque. Делаем это только над собственной запасной
                    // личностью: над активной это сменило бы тип туннеля прямо
                    // под работающим соединением.
                    // Ключ выпускаем обычным запросом, а не обфусцированным транспортом.
                    //
                    // Внутри туннеля имя api.cloudflareclient.com провайдеру не видно, и
                    // обфускация там не нужна. Она ещё и вредит: замер 2026-08-12 —
                    // устройство, которому ключ выпустили обфусцированным транспортом,
                    // сервер принимает по TLS и HTTP/3, но на запрос CONNECT-IP не
                    // отвечает никогда. Регистрация того же устройства идёт обычным
                    // запросом (OkHttp) — и она работает.
                    // Условие проверяется ещё раз, вплотную к выпуску ключа.
                    //
                    // Между проверкой в начале потока и этим местом лежит регистрация через
                    // туннель, а она идёт минутами. За это время цикл подключения мог сам
                    // добыть ключ — и тогда фоновый выпуск отобрал бы его: сервер хранит
                    // только последний ключ, а конфигурация в настройках осталась бы от
                    // предыдущего. Ровно этот путь и превращал одну неудачу в бесконечную.
                    if (!clientData.shouldAttemptWarpIdentityBackfill(ignoreCooldown = ignoreCooldown)) {
                        logger(
                            "MASQUE-профиль появился, пока шла фоновая регистрация. " +
                                "Второй выпуск ключа отменяем: он отобрал бы уже сохранённый."
                        )
                        return@Thread
                    }
                    val masqueJson = runCatching {
                        nova.Nova.setPlainCloudflareApiPreferred(true)
                        try {
                            nova.Nova.ensureMasqueConfig(
                                clientData.getMasqueConfigJson().orEmpty(),
                                reserve.accessToken.orEmpty(),
                                reserve.deviceId.orEmpty(),
                                "Nova Android",
                            ).orEmpty()
                        } finally {
                            // Предпочтение снимаем сразу: вне туннеля обычный запрос
                            // упрётся в блокировку по SNI, и обфускация снова нужна.
                            nova.Nova.setPlainCloudflareApiPreferred(false)
                        }
                    }.getOrElse { error ->
                        logger("Регистрация MASQUE через туннель не удалась: ${error.message}")
                        ""
                    }
                    if (masqueJson.isBlank()) {
                        clientData.markWarpIdentityBackfillFailure()
                        logger(
                            "MASQUE-профиль через туннель получить не удалось. " +
                                "Личность сохранена, следующую попытку сделаем не раньше чем через шесть часов."
                        )
                        return@Thread
                    }
                    clientData.saveMasqueConfigJson(masqueJson)
                    // Ключ есть — сообщаем службе. Если туннель поднимался только ради
                    // регистрации, ждать «следующего подключения» пользователю не надо.
                    onIdentityReady()
                    logger(
                        "MASQUE-профиль подготовлен через туннель и сохранён. " +
                            "При следующем подключении выбранный MASQUE стартует без регистрации."
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    ClientData(context).markWarpIdentityBackfillFailure()
                    logger("Фоновая регистрация через туннель прервалась: ${e.message}")
                } finally {
                    running.set(false)
                }
            },
            "NovaWarpIdentityBackfill",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}
