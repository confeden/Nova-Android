package com.example.nova

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Крошечный HTTP-сервер, который помогает настроить клиента.
 *
 * Отдаёт две вещи: страницу с параметрами подключения на русском и PAC-файл.
 * Слушает на тех же адресах, что и прокси, но на отдельном порту, потому что порт
 * прокси разбирает запросы по правилам прокси, а не обычного веб-сервера.
 *
 * Логин и пароль показываются **только** клиентам, пришедшим через раздачу самого
 * телефона: такой клиент находится в сети, которую создал и защитил владелец
 * устройства. В чужой общей сети Wi-Fi страница отправляет за паролем в приложение.
 */
object GatewayPortal {

    private data class BoundListener(
        val host: String,
        val socket: ServerSocket,
        val worker: Thread,
    )

    @Volatile
    private var listeners: List<BoundListener> = emptyList()

    @Volatile
    private var endpointsByHost: Map<String, GatewayEndpoint> = emptyMap()

    private val running = AtomicBoolean(false)

    @Synchronized
    fun sync(
        context: Context,
        endpoints: List<GatewayEndpoint>,
        logger: (String) -> Unit,
    ) {
        val appContext = context.applicationContext
        val port = ClientData(appContext).getGatewayPortalPort()
        // Портал нужен только тем, кто подключается снаружи; на loopback он бесполезен.
        val desired = endpoints.map { it.host }.distinct().sorted()
        endpointsByHost = endpoints.associateBy { it.host }

        val current = listeners
        if (
            running.get() &&
            current.isNotEmpty() &&
            current.all { !it.socket.isClosed } &&
            current.map { it.host }.sorted() == desired
        ) {
            return
        }

        stop(logger)
        if (desired.isEmpty()) return

        running.set(true)
        val bound = mutableListOf<BoundListener>()
        for (host in desired) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(host, port))
                }
            }.getOrElse { error ->
                logger("Портал раздачи не смог занять $host:$port: ${error.message}")
                continue
            }
            val worker = thread(start = true, isDaemon = true, name = "NovaGatewayPortal-$host") {
                while (running.get() && !socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    thread(start = true, isDaemon = true, name = "NovaGatewayPortalClient") {
                        runCatching { handle(appContext, client) }
                        runCatching { client.close() }
                    }
                }
            }
            bound += BoundListener(host = host, socket = socket, worker = worker)
        }

        if (bound.isEmpty()) {
            running.set(false)
            listeners = emptyList()
            return
        }
        listeners = bound
        logger("Портал раздачи открыт на ${bound.joinToString(", ") { "${it.host}:$port" }}.")
    }

    @Synchronized
    fun stop(logger: (String) -> Unit) {
        running.set(false)
        val active = listeners
        listeners = emptyList()
        active.forEach { runCatching { it.socket.close() } }
        active.forEach { listener ->
            if (listener.worker.isAlive && listener.worker !== Thread.currentThread()) {
                runCatching { listener.worker.join(400L) }
            }
        }
        if (active.isNotEmpty()) {
            logger("Портал раздачи закрыт.")
        }
    }

    fun isRunning(): Boolean = running.get() && listeners.any { !it.socket.isClosed }

    /** Адрес, который стоит показать пользователю: раздача важнее общей сети. */
    fun portalUrl(context: Context, endpoints: List<GatewayEndpoint>): String {
        val endpoint = endpoints.firstOrNull { it.downstream } ?: endpoints.firstOrNull() ?: return ""
        return "http://${endpoint.host}:${ClientData(context).getGatewayPortalPort()}/"
    }

    private fun handle(context: Context, socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readAsciiLine(input)?.trim().orEmpty()
        if (requestLine.isBlank()) return
        // Заголовки читаем и выбрасываем: ничего из них нам не нужно, но не вычитав
        // их, мы получим reset при записи ответа.
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isBlank()) break
        }

        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)?.uppercase(Locale.US).orEmpty()
        if (method != "GET" && method != "HEAD") {
            respond(output, 405, "text/plain; charset=utf-8", "Method Not Allowed", headOnly = false)
            return
        }
        val path = parts.getOrNull(1)?.substringBefore('?').orEmpty().ifBlank { "/" }
        val localHost = socket.localAddress?.hostAddress.orEmpty()
        val endpoint = endpointsByHost[localHost]
        val headOnly = method == "HEAD"

        when (path) {
            "/nova.pac", "/proxy.pac", "/wpad.dat" -> respond(
                output,
                200,
                "application/x-ns-proxy-autoconfig",
                buildPac(context, localHost),
                headOnly,
            )
            else -> respond(
                output,
                200,
                "text/html; charset=utf-8",
                buildPage(context, localHost, endpoint),
                headOnly,
            )
        }
    }

    private fun buildPac(context: Context, host: String): String {
        val port = ClientData(context).getLocalProxyPort()
        // Локальные адреса отдаём напрямую: заворачивать роутер и принтер в VPN
        // бессмысленно, через прокси они просто перестанут отвечать.
        //
        // isInNet вызывается только для литеральных IP. Для имени эта функция сначала
        // сделала бы DNS-запрос — на каждый URL, синхронно, в самом клиенте. Отсюда же
        // отказ от shExpMatch("10.*"): под такой шаблон попадает и хост 10.example.com.
        return """
            function FindProxyForURL(url, host) {
              if (isPlainHostName(host) || shExpMatch(host, "*.local")) return "DIRECT";
              var literal = host.length > 0;
              for (var i = 0; i < host.length; i++) {
                var c = host.charAt(i);
                if ((c < "0" || c > "9") && c != ".") { literal = false; break; }
              }
              if (literal &&
                  (isInNet(host, "10.0.0.0", "255.0.0.0") ||
                   isInNet(host, "172.16.0.0", "255.240.0.0") ||
                   isInNet(host, "192.168.0.0", "255.255.0.0") ||
                   isInNet(host, "169.254.0.0", "255.255.0.0") ||
                   isInNet(host, "127.0.0.0", "255.0.0.0"))) {
                return "DIRECT";
              }
              return "PROXY $host:$port";
            }
        """.trimIndent()
    }

    private fun buildPage(
        context: Context,
        host: String,
        endpoint: GatewayEndpoint?,
    ): String {
        val clientData = ClientData(context)
        val proxyPort = clientData.getLocalProxyPort()
        val portalPort = clientData.getGatewayPortalPort()
        val downstream = endpoint?.downstream == true
        val openForTethered = clientData.isGatewayOpenForTethered()
        val needsAuth = !(downstream && openForTethered)
        val credentials = clientData.ensureLocalProxyCredentials()
        val pacUrl = "http://$host:$portalPort/nova.pac"

        val credentialsBlock = when {
            !needsAuth -> """
                <p class="ok">Логин и пароль не нужны: для устройств, подключённых к раздаче
                этого телефона, авторизация отключена в настройках Nova.</p>
            """.trimIndent()
            downstream -> """
                <div class="row"><span>Логин</span><b>${escape(credentials.first)}</b></div>
                <div class="row"><span>Пароль</span><b>${escape(credentials.second)}</b></div>
            """.trimIndent()
            else -> """
                <p class="warn">Логин и пароль здесь не показаны: вы подключены через общую
                сеть Wi-Fi, а не через раздачу телефона. Возьмите их на экране
                «Раздача через Nova» в самом приложении.</p>
            """.trimIndent()
        }

        val connectionLabel = endpoint?.kind?.title ?: "Сеть"
        val tunnelUp = clientData.getServiceState() == NovaVpnService.STATE_CONNECTED
        val allowDirect = clientData.isGatewayAllowDirectWithoutVpn()
        val stateBlock = when {
            tunnelUp -> """
                <div class="state ok">VPN подключён — всё, что пойдёт через этот прокси,
                выйдет в интернет через него.</div>
            """.trimIndent()
            allowDirect -> """
                <div class="state warn">VPN сейчас не подключён. В настройках Nova разрешён
                выход напрямую, поэтому интернет работает, но <b>без защиты</b>.</div>
            """.trimIndent()
            else -> """
                <div class="state warn">VPN сейчас не подключён, поэтому трафик не
                выпускается: иначе вы вышли бы в интернет со своим обычным адресом и не
                заметили этого. Настроить прокси можно прямо сейчас — как только VPN
                поднимется, всё заработает само.</div>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Раздача через Nova</title>
            <style>
              body { margin:0; padding:24px; background:#121212; color:#fff;
                     font-family:-apple-system,Roboto,Segoe UI,sans-serif; line-height:1.5; }
              .wrap { max-width:640px; margin:0 auto; }
              h1 { color:#50C878; font-size:24px; margin:0 0 4px; }
              h2 { font-size:16px; margin:28px 0 8px; color:#50C878; }
              .sub { color:#AAA; font-size:13px; margin:0 0 20px; }
              .card { background:rgba(255,255,255,.08);
                      border-radius:12px; padding:14px 16px; margin-bottom:16px; }
              .row { display:flex; justify-content:space-between; gap:16px;
                     padding:6px 0; border-bottom:1px solid rgba(255,255,255,.08); }
              .row:last-child { border-bottom:0; }
              .row span { color:#AAA; }
              .row b { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:16px;
                       word-break:break-all; text-align:right; }
              ol { padding-left:20px; } li { margin:6px 0; }
              .warn { color:#FFB347; font-size:14px; margin:0; }
              .ok { color:#50C878; font-size:14px; margin:0; }
              .note { color:#888; font-size:12px; margin-top:24px; }
              code { background:rgba(255,255,255,.1); padding:1px 5px; border-radius:4px; }
              .state { border-radius:10px; padding:12px 14px; margin:0 0 18px; font-size:14px; }
              .state.ok { background:rgba(80,200,120,.15); color:#7fe3a3; }
              .state.warn { background:rgba(255,179,71,.15); color:#ffc073; }
              .lead { font-size:15px; margin:0 0 16px; }
              .excl li { margin:8px 0; }
              .excl b { color:#ffc073; }
            </style>
            </head>
            <body><div class="wrap">
              <h1>Раздача через Nova</h1>
              <p class="sub">Вы подключены к этому телефону через: $connectionLabel</p>

              $stateBlock

              <p class="lead">Чтобы этот компьютер или телефон выходил в интернет через
                 VPN, пропишите у себя в настройках сети прокси-сервер
                 <b>$host</b>, порт <b>$proxyPort</b>. Ниже — как это сделать
                 на разных системах.</p>

              <div class="card">
                <div class="row"><span>Адрес прокси</span><b>$host</b></div>
                <div class="row"><span>Порт</span><b>$proxyPort</b></div>
                <div class="row"><span>Тип</span><b>HTTP и SOCKS5</b></div>
                $credentialsBlock
              </div>

              <h2>Windows</h2>
              <ol>
                <li>Параметры → Сеть и Интернет → Прокси-сервер.</li>
                <li>«Настройка прокси вручную» → Использовать прокси-сервер → Вкл.</li>
                <li>Адрес <code>$host</code>, порт <code>$proxyPort</code> → Сохранить.</li>
              </ol>

              <h2>macOS и iOS</h2>
              <ol>
                <li>Настройки Wi-Fi → текущая сеть → Прокси (HTTP).</li>
                <li>Сервер <code>$host</code>, порт <code>$proxyPort</code>.</li>
                <li>Там же включите прокси для HTTPS.</li>
              </ol>

              <h2>Android и Android TV</h2>
              <ol>
                <li>Настройки Wi-Fi → долгое нажатие на сеть → Изменить сеть.</li>
                <li>Дополнительно → Прокси → Вручную.</li>
                <li>Имя узла <code>$host</code>, порт <code>$proxyPort</code>.</li>
              </ol>

              <h2>Автоматическая настройка</h2>
              <p>Там, где есть пункт «Автоматическая настройка прокси» или PAC, укажите
                 адрес скрипта:</p>
              <div class="card"><div class="row"><span>PAC</span><b>$pacUrl</b></div></div>

              <h2>Что пойдёт мимо VPN</h2>
              <p>Честно и без мелкого шрифта: прокси переносит только TCP-соединения.
                 Остальное ваша система отправит в обход, со своим обычным адресом.</p>
              <ul class="excl">
                <li><b>Онлайн-игры, звонки и видеосвязь.</b> Почти всегда UDP —
                    прокси такой трафик не переносит.</li>
                <li><b>QUIC и HTTP/3.</b> Тоже UDP. Браузеры при настроенном прокси
                    обычно сами откатываются на обычный TCP, но не все и не всегда.</li>
                <li><b>Системные DNS-запросы.</b> Имена сайтов, которые вы открываете
                    через прокси, разрешает сам телефон — по правилам DNS, заданным в
                    Nova. А вот запросы от программ, не использующих прокси, уходят на
                    ваш обычный DNS, и провайдер увидит, к каким доменам вы обращались.
                    Занять на телефоне стандартный порт DNS приложение не может: этот
                    порт системный, для него нужны права root.<br>
                    <b>Совет:</b> если в настройках клиента есть выбор между SOCKS5 и
                    SOCKS5h — берите <code>socks5h</code>. Тогда имя сайта уходит на
                    телефон целиком, и его разрешает он. Обычный <code>socks5</code>
                    сначала разрешает имя у вас и только потом идёт через прокси.</li>
                <li><b>Программы без поддержки прокси.</b> Системная настройка прокси
                    обязательна не для всех: торренты, часть игр и утилит её игнорируют.</li>
                <li><b>Локальная сеть.</b> Роутер, принтер, сетевые диски остаются
                    доступны напрямую — так и задумано.</li>
              </ul>
              <p class="note">Если нужно, чтобы через VPN шло совершенно всё, включая UDP,
                 на самом устройстве должен работать VPN-клиент. Раздача без root на такое
                 не способна ни у одного приложения — это ограничение Android, а не Nova.</p>
            </div></body></html>
        """.trimIndent()
    }

    private fun escape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        contentType: String,
        body: String,
        headOnly: Boolean,
    ) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (code == 200) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) output.write(payload)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(value.toChar())
            if (buffer.length > 8_192) return buffer.toString()
        }
    }
}
