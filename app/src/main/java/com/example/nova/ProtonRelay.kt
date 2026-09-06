package com.example.nova

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Вызовы API Proton через собственные релеи в Швеции.
 *
 * Зачем. Из России прямые хосты Proton закрыты на транспортном уровне (TCP 443 не
 * открывается вовсе), а штатный обход самого Proton — запасные узлы из TXT-записи
 * `protonpro.xyz` — держится на публичных DoH-резолверах и упирается в две вещи
 * сразу: сами резолверы (`dns.google`, `cloudflare-dns.com`, `1.1.1.1`) в России
 * блокируются всё плотнее, а найденные через них узлы отдают 8-21 КБ и подвисают
 * до таймаута (`kb/proton-generator.md`, P3). Итог на чистой установке — ни
 * сессии, ни регистрации ключа, ни живого списка серверов: остаётся только
 * встроенные пятьдесят узлов из прошивки, и то лишь если сессия всё-таки
 * состоялась.
 *
 * Релей снимает обе проблемы одним ходом, и ровно тем же способом, каким уже
 * лечится Opera (`OperaProxyManager.apiRelays`): **в Швецию переносятся только
 * вызовы API**. Сам туннель Proton по-прежнему набирается с адреса пользователя,
 * страна выхода не меняется, трафик пользователя через релей не идёт.
 *
 * Устройство. Релей — это HTTP-прокси с `CONNECT` и Basic-авторизацией, завёрнутый
 * в TLS. OkHttp в такой прокси ходить не умеет (`Proxy.Type.HTTP` разговаривает с
 * прокси открытым текстом), поэтому перед ним стоит [TlsRelayBridge]. TLS до
 * самого Proton при этом остаётся сквозным: релей видит только `CONNECT
 * vpn-api.proton.me:443` и поток шифробайтов — ни токенов сессии, ни ключа он
 * прочитать не может даже теоретически.
 *
 * Адреса релеев не секрет и лежат в исходниках; секрет — только пароль, и он
 * приходит из сборки (I5). Без пароля список пуст: подставлять заглушку значило
 * бы потратить попытку и получить `407`, чтобы узнать то же самое.
 */
object ProtonRelay {

    /**
     * Те же релеи, что у Opera, и намеренно те же.
     *
     * Это один сервер и один пароль; заводить Proton-у собственный вход значило бы
     * второй секрет с тем же значением, а «один секрет — один источник» (I5) —
     * правило именно про это.
     */
    private val RELAY_ENDPOINTS get() = NovaRelay.ENDPOINTS

    /**
     * Сроки заметно длиннее, чем у прямых хостов.
     *
     * У прямых они короткие потому, что там проверяется гипотеза «хост закрыт» —
     * не открывшееся за шесть секунд соединение не откроется и за тридцать. Здесь
     * наоборот: релей заведомо доступен, и единственный длинный шаг —
     * `/vpn/logicals` на ~30 КБ, который надо дождаться, а не оборвать.
     */
    private const val CONNECT_TIMEOUT_S = 8L
    private const val READ_TIMEOUT_S = 20L
    private const val CALL_TIMEOUT_S = 30L

    /** Мост и клиент на каждый релей: у каждого свой локальный порт. */
    private class Lane(val label: String, val bridge: TlsRelayBridge) {
        @Volatile
        var client: OkHttpClient? = null
    }

    private val lanes: List<Lane> by lazy {
        RELAY_ENDPOINTS.mapIndexed { index, (host, port) ->
            Lane("$host:$port", TlsRelayBridge("proton-relay-$index"))
        }
    }

    private fun password(): String = NovaRelay.password()

    /** Настроен ли релей вообще. Без ключа — нет, и это не ошибка, а сборка без него. */
    fun isConfigured(): Boolean = NovaRelay.isConfigured()

    /**
     * Клиенты, ходящие через релеи, в порядке предпочтения.
     *
     * Мост поднимается лениво и переиспользуется: каждый шаг прогона — свой запрос,
     * и поднимать мост заново на каждом значило бы лишнее TLS-рукопожатие с релеем
     * на каждый шаг. Релей, чей мост не поднялся, из списка выпадает — идти в него
     * вслепую незачем.
     */
    fun clients(): List<OkHttpClient> {
        if (!NovaRelay.isConfigured()) return emptyList()
        val credential = Credentials.basic(NovaRelay.keyId(), password())
        return lanes.mapIndexedNotNull { index, lane ->
            lane.client ?: run {
                val (host, port) = RELAY_ENDPOINTS[index]
                val endpoint = lane.bridge.start("https://$host:$port", LogManager::log) ?: return@run null
                val built = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(endpoint.localHost, endpoint.localPort)))
                    .proxyAuthenticator(BasicProxyAuthenticator(credential))
                    .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                lane.client = built
                built
            }
        }
    }

    /** Подпись релея для журнала — без пароля. */
    fun describe(index: Int): String = RELAY_ENDPOINTS.getOrNull(index)?.let { "${it.first}:${it.second}" } ?: "?"

    /**
     * Гасит мосты и забывает клиентов.
     *
     * Мост слушает порт на петле, и держать его открытым дольше самой работы
     * незачем. Авторизации он не добавляет — без неё релей отвечает `407`, —
     * поэтому открытым прокси для соседних приложений он не становится, но и
     * висеть весь сеанс ему нечего.
     */
    fun shutdown() {
        lanes.forEach { lane ->
            lane.client = null
            runCatching { lane.bridge.stop(LogManager::log) }
        }
    }

    /**
     * Basic-авторизация на релее.
     *
     * OkHttp зовёт аутентификатор **после** `407`, то есть один лишний обход на
     * соединение; переиспользование соединения делает это разовой платой. Повтор
     * ограничен явно: если заголовок уже стоял, а релей всё равно ответил `407`,
     * пароль неверен — и бесконечно переспрашивать значит превратить неверный
     * пароль в зависание.
     */
    private class BasicProxyAuthenticator(private val credential: String) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // «Ключ погашен» и «пароль неверен» приходят одним кодом 407, а лечение
            // у них разное: одно — обновить приложение, другое — пересобрать его.
            // Различает их заголовок, и без этой проверки пользователь увидел бы
            // сетевой отказ вместо причины (I4).
            if (NovaRelay.noteFromResponse(response, "Proton")) return null
            if (response.request.header("Proxy-Authorization") != null) {
                LogManager.log("Proton relay: релей не принял ключ (407 повторно) — идём другим путём.")
                return null
            }
            return response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }
}
