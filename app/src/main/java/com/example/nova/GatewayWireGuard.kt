package com.example.nova

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import nova.Nova
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * WireGuard-сервер раздачи: устройства подключаются обычным WireGuard-клиентом, а
 * сервер в Go-ядре (`engine/gateway_awg.go`) заново открывает их соединения сокетами
 * `:vpn`, и они идут в туннель Nova. Root не нужен.
 *
 * Состояние — файл `gateway_wg.json` (I2): экран пишет, `:vpn` читает заново на каждом
 * `sync`. Закрытые ключи устройств хранятся, чтобы конфиг и QR можно было показать
 * снова; файл лежит в приватном каталоге приложения.
 */
object GatewayWireGuard {
    const val PORT = 51820
    const val MTU = 1280
    const val SERVER_V4 = "10.66.0.1"
    const val SERVER_V6 = "fd66:6e6f:7661::1"
    private const val FIRST_INDEX = 2
    private const val LAST_INDEX = 250

    data class Device(
        val id: String,
        val name: String,
        val index: Int,
        val privateKey: String,
        val publicKey: String,
    ) {
        val address4: String get() = "10.66.0.$index"
        val address6: String get() = "fd66:6e6f:7661::$index"
    }

    data class State(
        val serverPrivate: String,
        val serverPublic: String,
        val devices: List<Device>,
    )

    private val lock = Any()
    private var running = false
    private var appliedPeers: String? = null

    private fun file(context: Context) =
        AtomicFile(File(context.applicationContext.filesDir, "gateway_wg.json"))

    private fun generateKeyPair(): Pair<String, String> {
        val priv = ByteArray(32)
        val pub = ByteArray(32)
        X25519.generatePrivateKey(SecureRandom(), priv)
        X25519.generatePublicKey(priv, 0, pub, 0)
        return Base64.encodeToString(priv, Base64.NO_WRAP) to Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    private fun read(context: Context): State? {
        val f = file(context)
        val raw = runCatching { f.baseFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                File(f.baseFile.path + ".bak").takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }.getOrNull()
            ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val devices = json.optJSONArray("devices") ?: JSONArray()
            State(
                serverPrivate = json.getString("server_private"),
                serverPublic = json.getString("server_public"),
                devices = (0 until devices.length()).map { i ->
                    val d = devices.getJSONObject(i)
                    Device(
                        id = d.getString("id"),
                        name = d.optString("name"),
                        index = d.getInt("index"),
                        privateKey = d.getString("private"),
                        publicKey = d.getString("public"),
                    )
                },
            )
        }.getOrNull()
    }

    private fun write(context: Context, state: State) {
        val json = JSONObject()
            .put("server_private", state.serverPrivate)
            .put("server_public", state.serverPublic)
            .put(
                "devices",
                JSONArray().also { arr ->
                    state.devices.forEach { d ->
                        arr.put(
                            JSONObject()
                                .put("id", d.id)
                                .put("name", d.name)
                                .put("index", d.index)
                                .put("private", d.privateKey)
                                .put("public", d.publicKey),
                        )
                    }
                },
            )
        val f = file(context)
        val stream = f.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            f.finishWrite(stream)
        } catch (t: Throwable) {
            f.failWrite(stream)
            throw t
        }
    }

    /** Состояние с ключами сервера; создаёт их при первом обращении. */
    fun ensure(context: Context): State = synchronized(lock) {
        read(context)?.let { return it }
        val (priv, pub) = generateKeyPair()
        State(priv, pub, emptyList()).also { write(context, it) }
    }

    fun devices(context: Context): List<Device> = read(context)?.devices.orEmpty()

    /** Новое устройство; `null`, если адресное пространство кончилось. */
    fun addDevice(context: Context, name: String): Device? = synchronized(lock) {
        val state = ensure(context)
        val used = state.devices.map { it.index }.toSet()
        val index = (FIRST_INDEX..LAST_INDEX).firstOrNull { it !in used } ?: return null
        val (priv, pub) = generateKeyPair()
        val device = Device(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "WireGuard $index" },
            index = index,
            privateKey = priv,
            publicKey = pub,
        )
        write(context, state.copy(devices = state.devices + device))
        device
    }

    fun removeDevice(context: Context, id: String) = synchronized(lock) {
        val state = ensure(context)
        write(context, state.copy(devices = state.devices.filterNot { it.id == id }))
    }

    /**
     * Обычный WireGuard-конфиг. `AllowedIPs = ::/0` обязателен: без него IPv6 клиента
     * пошёл бы мимо Nova напрямую через точку доступа.
     */
    fun clientConfig(state: State, device: Device, host: String): String {
        val endpointHost = if (host.contains(':')) "[$host]" else host
        return buildString {
            append("[Interface]\n")
            append("PrivateKey = ${device.privateKey}\n")
            append("Address = ${device.address4}/32, ${device.address6}/128\n")
            append("DNS = $SERVER_V4, $SERVER_V6\n")
            append("MTU = $MTU\n\n")
            append("[Peer]\n")
            append("PublicKey = ${state.serverPublic}\n")
            append("Endpoint = $endpointHost:$PORT\n")
            append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            append("PersistentKeepalive = 25\n")
        }
    }

    private fun peersJson(state: State): String = JSONArray().also { arr ->
        state.devices.forEach { d ->
            arr.put(
                JSONObject()
                    .put("public_key", d.publicKey)
                    .put("allowed", JSONArray().put("${d.address4}/32").put("${d.address6}/128")),
            )
        }
    }.toString()

    private fun serverConfigJson(state: State): String = JSONObject()
        .put("listen_port", PORT)
        .put("private_key", state.serverPrivate)
        .put("addr4", SERVER_V4)
        .put("addr6", SERVER_V6)
        .put("mtu", MTU)
        .put("peers", JSONArray(peersJson(state)))
        .toString()

    /**
     * Приводит сервер в `:vpn` к желаемому виду: запущен, пока раздача включена;
     * список устройств подхватывается на лету; выпуск наружу разрешён, когда туннель
     * поднят либо владелец разрешил выход напрямую. Вызывается из каждого `sync`.
     */
    fun syncServer(
        context: Context,
        shouldRun: Boolean,
        egressAllowed: Boolean,
        logger: (String) -> Unit,
    ) = synchronized(lock) {
        runCatching { Nova.setGatewayEgressAllowed(egressAllowed) }
        if (!shouldRun) {
            if (running) {
                runCatching { Nova.stopGatewayServer() }
                running = false
                appliedPeers = null
                logger("Сервер раздачи WireGuard остановлен.")
            }
            return
        }
        val state = ensure(context)
        val peers = peersJson(state)
        if (!running) {
            try {
                Nova.startGatewayServer(serverConfigJson(state))
                running = true
                appliedPeers = peers
                logger("Сервер раздачи WireGuard запущен на UDP $PORT, устройств: ${state.devices.size}.")
            } catch (t: Throwable) {
                logger("Сервер раздачи WireGuard не запустился: ${t.message ?: t::class.java.simpleName}")
            }
            return
        }
        if (peers != appliedPeers) {
            try {
                Nova.updateGatewayPeers(peers)
                appliedPeers = peers
                logger("Сервер раздачи WireGuard: список устройств обновлён (${state.devices.size}).")
            } catch (t: Throwable) {
                logger("Сервер раздачи WireGuard: не удалось обновить устройства: ${t.message}")
            }
        }
    }

    fun stats(): String = runCatching { Nova.gatewayServerStats() }.getOrDefault("")
}
