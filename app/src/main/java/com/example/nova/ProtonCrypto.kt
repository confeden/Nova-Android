package com.example.nova

import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/**
 * Криптография Proton-профилей: одна ed25519-личность на все серверы и проверка
 * узла настоящим рукопожатием WireGuard.
 *
 * Почему одна личность. Proton регистрирует пира по **ed25519**-ключу и выводит
 * X25519-половину сам, поэтому один зарегистрированный ключ подходит ко всем
 * серверам сразу — отдельная регистрация на каждый из пятидесяти профилей не
 * нужна и стоила бы пятидесяти запросов к API.
 *
 * Почему рукопожатие, а не TCP-connect. Узлы Proton слушают только UDP, и
 * `Socket.connect` к ним не значит ничего: он либо мгновенно «удаётся» на UDP-сокете,
 * не отправив ни байта, либо падает на TCP. Настоящее рукопожатие WireGuard — это
 * единственная проверка, которая одновременно измеряет задержку **и** доказывает,
 * что сервер знает наш ключ: на незнакомый статический ключ WireGuard молчит.
 *
 * Чем за это платят. Доведённое до конца рукопожатие — это уже сессия: Proton
 * отдаёт ключ последнему, кто им поздоровался, и живой туннель на другом узле
 * замолкает секунд через пятнадцать. А узел, которому рукопожатия надоели,
 * перестаёт отвечать на этот ключ на 25+ минут. Поэтому рукопожатием здесь мерят
 * **один** узел — тот, к которому собираются подключаться, — и никогда пачку
 * (`kb/proton-generator.md`, Nova PC `kb/profiles.md#wg-probe`).
 */
object ProtonCrypto {

    /**
     * Имя протокола ровно в том виде, в каком его пишет WireGuard, — `ChaChaPoly`,
     * не `ChaCha20Poly1305`.
     *
     * Здесь шестнадцать заходов расследования и стоила одна лишняя буква: имя
     * входит в самый первый `BLAKE2s`, из него растёт вся цепочка ключей, и
     * инициация с другим именем **правильна по форме и не расшифровывается ни
     * одним сервером**. Молчание на неё неотличимо от молчания мёртвого узла —
     * так «ответили 0 из 50» и объясняли то сетью, то блокировкой, то занятой
     * личностью (G218, P5-P16, N37).
     *
     * Эталон — `NoiseConstruction` в `tools/amneziawg-go/device/noise-protocol.go`
     * этого же репозитория: то есть ядро, которое поднимает наши туннели, всё это
     * время здоровалось не так, как проба.
     */
    private const val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
    private const val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com"
    private const val LABEL_MAC1 = "mac1----"

    /** Фиксированная DER-шапка SubjectPublicKeyInfo для ed25519 — 12 байт перед самим ключом. */
    private const val ED25519_SPKI_PREFIX = "MCowBQYDK2VwAyEA"

    /**
     * Пауза перед повтором рукопожатия и число повторов на один узел.
     *
     * Пауза заведомо больше `HandshakeInitationRate` (20 мс) из wireguard-go: пира
     * бомбить нельзя, инициации, пришедшие чаще, сервер отбрасывает не глядя.
     */
    private const val RETRY_AFTER_MS = 700
    private const val RETRY_LIMIT = 3

    /** Размер `handshake response`: 4+4+4 заголовка, 32 эфемерных, 16 AEAD и две метки. */
    private const val RESPONSE_SIZE = 92

    private val secureRandom = SecureRandom()

    fun randomSeed(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

    /**
     * Публичный ключ ed25519 в том виде, в каком его ждёт `/vpn/v1/certificate`: PEM
     * поверх SubjectPublicKeyInfo. Шапка постоянна, поэтому собирается строкой, а не
     * через ASN.1-энкодер.
     */
    fun ed25519PublicKeyPem(seed: ByteArray): String {
        require(seed.size == 32) { "seed должен быть 32 байта" }
        val pub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        Ed25519.generatePublicKey(seed, 0, pub, 0)
        val body = ED25519_SPKI_PREFIX + Base64.encodeToString(pub, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    /**
     * Приватный ключ WireGuard из того же семени: `clamp(SHA-512(seed)[0:32])`.
     *
     * Это стандартная конверсия ed25519 → x25519 (`crypto_sign_ed25519_sk_to_curve25519`).
     * Сервер выполняет её же над публичной половиной, поэтому выданный сертификат
     * туннелю не нужен вовсе — достаточно того, что ключ зарегистрирован.
     */
    fun wireGuardPrivateKeyBase64(seed: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-512").digest(seed)
        val priv = hash.copyOf(32)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()
        return Base64.encodeToString(priv, Base64.NO_WRAP)
    }

    fun publicKeyFor(privateKeyBase64: String): String {
        val priv = Base64.decode(privateKeyBase64, Base64.DEFAULT)
        val pub = ByteArray(32)
        X25519.scalarMultBase(priv, 0, pub, 0)
        return Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    // --- рукопожатие ---------------------------------------------------------

    private fun hash(vararg parts: ByteArray): ByteArray {
        val d = Blake2sDigest(256)
        parts.forEach { d.update(it, 0, it.size) }
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = HMac(Blake2sDigest(256))
        mac.init(KeyParameter(key))
        mac.update(message, 0, message.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** KDF из спецификации WireGuard: τ0 = HMAC(key, input), τi = HMAC(τ0, τ(i-1) ‖ i). */
    private fun kdf(key: ByteArray, input: ByteArray, count: Int): List<ByteArray> {
        val t0 = hmac(key, input)
        val out = ArrayList<ByteArray>(count)
        var prev = ByteArray(0)
        for (i in 1..count) {
            prev = hmac(t0, prev + byteArrayOf(i.toByte()))
            out += prev
        }
        return out
    }

    private fun aead(key: ByteArray, counter: Long, plain: ByteArray, ad: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = ((counter shr (8 * i)) and 0xFF).toByte()
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, ad))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val len = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    /**
     * Обратная сторона [aead]: открывает и проверяет метку.
     *
     * Нужна только для ответа на рукопожатие. Возвращает null на любой сбой —
     * подделанная метка, чужой ключ, не тот размер, — потому что для вызывающего
     * все эти случаи означают одно: ответ не наш.
     */
    private fun aeadOpen(key: ByteArray, counter: Long, sealed: ByteArray, ad: ByteArray): ByteArray? {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = ((counter shr (8 * i)) and 0xFF).toByte()
        return try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, ad))
            val out = ByteArray(cipher.getOutputSize(sealed.size))
            val len = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            val total = len + cipher.doFinal(out, len)
            out.copyOf(total)
        } catch (_: Exception) {
            null
        }
    }

    private fun keyedBlake2s16(key: ByteArray, message: ByteArray): ByteArray {
        val d = Blake2sDigest(key, 16, null, null)
        d.update(message, 0, message.size)
        return ByteArray(16).also { d.doFinal(it, 0) }
    }

    private fun tai64n(nowMs: Long): ByteArray {
        val seconds = 0x400000000000000AL + nowMs / 1000L
        val nanos = ((nowMs % 1000L) * 1_000_000L).toInt()
        val out = ByteArray(12)
        for (i in 0 until 8) out[i] = ((seconds shr (8 * (7 - i))) and 0xFF).toByte()
        for (i in 0 until 4) out[8 + i] = ((nanos shr (8 * (3 - i))) and 0xFF).toByte()
        return out
    }

    /**
     * Отправленная инициация вместе с состоянием, без которого ответ нельзя
     * проверить.
     *
     * Состояние обязано пережить отправку: ключи ответа выводятся из цепочки и
     * стенограммы **этой** инициации, и второй такой же пакет даст другие. Без него
     * от ответа остаётся только «пришёл пакет с двойкой в первом байте» — а это
     * может прислать кто угодно.
     */
    internal class Initiation(
        val packet: ByteArray,
        val chainKey: ByteArray,
        val hash: ByteArray,
        val ephPriv: ByteArray,
        val staticPriv: ByteArray,
        val senderIndex: ByteArray,
    )

    /**
     * Собирает 148-байтное `handshake initiation` ровно по спецификации WireGuard.
     *
     * Все случайные величины — параметры, чтобы результат был воспроизводим и
     * сравним с эталоном: `ProtonHandshakeVectorTest` прогоняет через неё
     * фиксированный вектор, снятый с `wireguard-go`. Без этого «узел не отвечает»
     * невозможно отличить от ошибки в собственной криптографии — а такое молчание
     * уже стоило шестнадцати заходов расследования (G218).
     *
     * `mac2` остаётся нулевым: он заполняется только в ответ на cookie-запрос,
     * которого при первом пакете не бывает.
     */
    internal fun buildInitiation(
        staticPriv: ByteArray,
        peerPub: ByteArray,
        ephPriv: ByteArray,
        senderIndex: ByteArray,
        timestamp: ByteArray,
    ): Initiation {
        require(staticPriv.size == 32 && peerPub.size == 32) { "ключи должны быть 32-байтными" }

        val staticPub = ByteArray(32).also { X25519.scalarMultBase(staticPriv, 0, it, 0) }

        var ck = hash(CONSTRUCTION.toByteArray())
        var h = hash(ck, IDENTIFIER.toByteArray())
        h = hash(h, peerPub)

        val ephPub = ByteArray(32).also { X25519.scalarMultBase(ephPriv, 0, it, 0) }

        ck = kdf(ck, ephPub, 1)[0]
        h = hash(h, ephPub)

        val dhEphStatic = ByteArray(32)
        X25519.scalarMult(ephPriv, 0, peerPub, 0, dhEphStatic, 0)
        var derived = kdf(ck, dhEphStatic, 2)
        ck = derived[0]
        val encryptedStatic = aead(derived[1], 0L, staticPub, h)
        h = hash(h, encryptedStatic)

        val dhStaticStatic = ByteArray(32)
        X25519.scalarMult(staticPriv, 0, peerPub, 0, dhStaticStatic, 0)
        derived = kdf(ck, dhStaticStatic, 2)
        ck = derived[0]
        val encryptedTimestamp = aead(derived[1], 0L, timestamp, h)

        // Стенограмма домешивает и зашифрованную метку времени — последним шагом
        // `CreateMessageInitiation`. Для самого пакета это уже ничего не меняет, а
        // ответ без этого шага не откроется: сервер считает свою стенограмму от
        // того же места.
        h = hash(h, encryptedTimestamp)

        val head = byteArrayOf(1, 0, 0, 0) + senderIndex + ephPub + encryptedStatic + encryptedTimestamp
        val mac1 = keyedBlake2s16(hash(LABEL_MAC1.toByteArray(), peerPub), head)
        return Initiation(
            packet = head + mac1 + ByteArray(16),
            chainKey = ck,
            hash = h,
            ephPriv = ephPriv,
            staticPriv = staticPriv,
            senderIndex = senderIndex,
        )
    }

    /**
     * Ответ ли это **на нашу** инициацию — по `ConsumeMessageResponse` из
     * `wireguard-go`.
     *
     * Почему мало типа пакета. Проба раньше засчитывала любую 2 в первом байте, а
     * WireGuard в ответ на непонятный пакет молчит — значит, «ответ» приходил либо
     * настоящий, либо ниоткуда, и разницы не было видно. Теперь ответ засчитывается
     * только когда на нём сходится пустой AEAD: это доказывает, что на той стороне
     * тот самый пир, что он знает наш статический ключ и что сессия действительно
     * поднялась бы. Молчание после этого — честное «нет», а не «мы не разобрали».
     *
     * Общий ключ (`psk`) у нас нулевой: ни один профиль Proton и ни одно
     * встроенное семя `PresharedKey` не несут.
     */
    internal fun verifyResponse(initiation: Initiation, response: ByteArray, length: Int): Boolean {
        if (length != RESPONSE_SIZE) return false
        if (response[0].toInt() != 2 || response[1].toInt() != 0 ||
            response[2].toInt() != 0 || response[3].toInt() != 0
        ) {
            return false
        }
        // Индекс получателя — это номер, который мы сами положили в инициацию.
        // Проверяется первым: он отсекает и чужой ответ, и ответ на прошлую попытку
        // с другим эфемерным ключом, не считая ни одного скалярного умножения.
        for (i in 0 until 4) if (response[8 + i] != initiation.senderIndex[i]) return false
        return try {
            val responderEph = response.copyOfRange(12, 44)
            var ck = kdf(initiation.chainKey, responderEph, 1)[0]
            var h = hash(initiation.hash, responderEph)

            val dhEph = ByteArray(32)
            X25519.scalarMult(initiation.ephPriv, 0, responderEph, 0, dhEph, 0)
            ck = kdf(ck, dhEph, 1)[0]

            val dhStatic = ByteArray(32)
            X25519.scalarMult(initiation.staticPriv, 0, responderEph, 0, dhStatic, 0)
            ck = kdf(ck, dhStatic, 1)[0]

            val derived = kdf(ck, ByteArray(32), 3)
            h = hash(h, derived[1])
            aeadOpen(derived[2], 0L, response.copyOfRange(44, 60), h)?.isEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Свежая инициация на каждую попытку — эфемерный ключ, номер и метка времени
     * новые.
     *
     * Повторять **тот же** пакет нельзя: `ConsumeMessageInitiation` отбрасывает
     * инициацию, метка времени которой не больше уже виденной, — то есть сервер,
     * до которого первый пакет дошёл, на дубликат промолчит по правилам протокола.
     * Пока рукопожатие было заведомо нерасшифровываемым (G218), это ничего не
     * стоило; теперь повтор обязан быть новым пакетом, иначе он бесполезен.
     */
    private fun buildInitiation(privateKeyB64: String, peerPublicKeyB64: String): Initiation {
        val ephPriv = ByteArray(32).also { secureRandom.nextBytes(it) }
        ephPriv[0] = (ephPriv[0].toInt() and 248).toByte()
        ephPriv[31] = (ephPriv[31].toInt() and 127).toByte()
        ephPriv[31] = (ephPriv[31].toInt() or 64).toByte()
        return buildInitiation(
            staticPriv = Base64.decode(privateKeyB64, Base64.DEFAULT),
            peerPub = Base64.decode(peerPublicKeyB64, Base64.DEFAULT),
            ephPriv = ephPriv,
            senderIndex = ByteArray(4).also { secureRandom.nextBytes(it) },
            timestamp = tai64n(System.currentTimeMillis()),
        )
    }

    /**
     * Публичный ключ x25519 по приватному — чтобы сверить личность с той, которой
     * пользуется движок.
     *
     * Нужен ровно для журнала: `GoLog` печатает пира как `peer(bmXO…fgyo)`, и без
     * такой же подписи наших ключей «узел молчит» нельзя отличить от «мы стучимся
     * не тем ключом и не к тому пиру».
     */
    fun x25519PublicKeyBase64(privateKeyB64: String): String {
        return runCatching {
            val priv = Base64.decode(privateKeyB64, Base64.DEFAULT)
            require(priv.size == 32) { "приватный ключ должен быть 32 байта" }
            val pub = ByteArray(32).also { X25519.scalarMultBase(priv, 0, it, 0) }
            Base64.encodeToString(pub, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    /** Подпись ключа в том же виде, что печатает движок: `bmXO…fgyo`. */
    fun abbreviateKey(value: String): String {
        val key = value.trim()
        if (key.length < 9) return if (key.isEmpty()) "пусто" else key
        return "${key.take(4)}…${key.takeLast(4)}"
    }

    /**
     * Минимальный DNS-запрос `A` для проверки живости сокета.
     *
     * Нужен не ради имени: ответ любого вида доказывает, что пакет ушёл и вернулся
     * на тот же сокет.
     */
    fun dnsQuery(host: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Идентификатор фиксирован: ответ мы не разбираем, важен сам факт ответа.
        out.write(byteArrayOf(0x4E, 0x56, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        host.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return@forEach
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1))
        return out.toByteArray()
    }

    /**
     * Уходит ли пакет с этого сокета и возвращается ли ответ.
     *
     * Вопрос, на который иначе нет ответа: «узел молчит» и «наш сокет никуда не
     * шлёт» снаружи выглядят одинаково — оба дают `-1`. Проба берётся к заведомо
     * отвечающей службе и строит сокет **ровно так же**, как замер рукопожатия:
     * тот же `protect()`, та же привязка к сети. Ответ здесь означает, что
     * молчание рукопожатия относится к узлу или к самому рукопожатию, а не к
     * маршруту наших пакетов.
     *
     * @return время ответа в миллисекундах или -1.
     */
    fun probeUdpAnswerRttMs(
        host: String,
        port: Int,
        payload: ByteArray,
        timeoutMs: Int,
        protect: ((DatagramSocket) -> Unit)? = null,
    ): Int {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect?.invoke(socket)
            socket.soTimeout = timeoutMs
            val address = InetSocketAddress(host, port)
            if (address.isUnresolved) return -1
            val startedAt = System.nanoTime()
            socket.send(DatagramPacket(payload, payload.size, address))
            val buffer = ByteArray(512)
            socket.receive(DatagramPacket(buffer, buffer.size))
            ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Разбирает значение AmneziaWG вида `<b 0x…>` в байты. */
    fun decodeAwgBinary(value: String): ByteArray? {
        val hex = value.trim()
            .removePrefix("<")
            .removeSuffix(">")
            .trim()
            .removePrefix("b")
            .trim()
            .removePrefix("0x")
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Отправляет рукопожатие и ждёт ответ типа 2.
     *
     * **Перед рукопожатием уходит ровно та же обфускация, что и у настоящего
     * подключения**: сначала `I1` (поддельный QUIC Initial), затем `Jc` мусорных
     * пакетов случайной длины. Голое рукопожатие как проба было ошибкой: на сети с
     * DPI блокируют именно его, и замер отвечал «узел мёртв» там, где реальное
     * подключение с обфускацией поднялось бы. Стоковый WireGuard на той стороне
     * лишние датаграммы просто отбрасывает, так что для Proton это безопасно.
     *
     * @param protect защита сокета от собственного туннеля. Обязателен: замер
     *        должен описывать прямой путь до узла, а не путь через поднятый
     *        туннель, иначе ранжирование описывает чужой канал. Вызывать может
     *        только процесс службы — у интерфейса `protect()` нет.
     * @return RTT в миллисекундах или -1, если **подтверждённого** ответа нет.
     *         Засчитывается только ответ, на котором сошёлся пустой AEAD
     *         ([verifyResponse]): пакет с двойкой в первом байте сам по себе не
     *         значит ничего. Тишина означает либо недоступный узел, либо
     *         незарегистрированный ключ — WireGuard не различает эти случаи и в
     *         обоих просто молчит.
     */
    fun probeHandshakeRttMs(
        privateKeyB64: String,
        peerPublicKeyB64: String,
        host: String,
        port: Int,
        timeoutMs: Int,
        i1: String = "",
        junkCount: Int = 0,
        junkMin: Int = 0,
        junkMax: Int = 0,
        protect: ((DatagramSocket) -> Unit)? = null,
        diagnosticLabel: String? = null,
    ): Int {
        val sent = ArrayList<Initiation>(RETRY_LIMIT + 1)
        val first = try {
            buildInitiation(privateKeyB64, peerPublicKeyB64)
        } catch (e: Exception) {
            LogManager.log("Proton probe: не собрали рукопожатие для $host:$port — ${e.message}")
            return -1
        }
        sent += first
        val packet = first.packet
        if (diagnosticLabel != null) {
            // Подпись самого пакета. «Рукопожатие собрано» и «собрано правильное
            // рукопожатие» — разные факты, а наружу оба выходили одинаково.
            // 148 байт и заголовок `01000000` — это весь внешний признак, который
            // можно проверить, не имея ответа.
            LogManager.log(
                "$diagnosticLabel: инициация ${packet.size} Б, заголовок " +
                    packet.take(4).joinToString("") { "%02x".format(it) } +
                    ", mac2 нулевой=${packet.takeLast(16).all { it.toInt() == 0 }}."
            )
        }
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            protect?.invoke(socket)
            socket.soTimeout = timeoutMs
            val address = InetSocketAddress(host, port)
            if (address.isUnresolved) return -1

            decodeAwgBinary(i1)?.let { bytes ->
                socket.send(DatagramPacket(bytes, bytes.size, address))
            }
            if (junkCount > 0 && junkMax >= junkMin && junkMin > 0) {
                repeat(junkCount.coerceAtMost(32)) {
                    val size = if (junkMax > junkMin) {
                        junkMin + secureRandom.nextInt(junkMax - junkMin + 1)
                    } else {
                        junkMin
                    }
                    val junk = ByteArray(size.coerceIn(1, 1280)).also(secureRandom::nextBytes)
                    socket.send(DatagramPacket(junk, junk.size, address))
                }
            }

            val startedAt = System.nanoTime()
            socket.send(DatagramPacket(packet, packet.size, address))
            val buffer = ByteArray(256)
            val deadline = startedAt + timeoutMs * 1_000_000L
            // Рукопожатие повторяется на **том же сокете**, как это делает и сам
            // движок (`RekeyTimeout`). Одиночный выстрел объявлял узел мёртвым там,
            // где живое подключение поднимается: первый пакет нового потока теряется
            // штатно, а пятёрка адресов остаётся той же, и повтор идёт уже по
            // «знакомому» для сети потоку.
            var nextRetryAt = startedAt + RETRY_AFTER_MS * 1_000_000L
            var retriesLeft = RETRY_LIMIT
            socket.soTimeout = RETRY_AFTER_MS.coerceAtMost(timeoutMs)
            // Ответ может прийти не первым: сервер вправе прислать cookie-запрос
            // (тип 3) на любой из отправленных мусорных пакетов, и приняв его за
            // ответ мы записали бы узел в мёртвые. Срок при этом общий — иначе
            // болтливый узел растянул бы замер на весь пул.
            var result = -1
            while (System.nanoTime() < deadline) {
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    // Тишина в короткое окно — повод повторить, а не приговор.
                    if (retriesLeft > 0 && System.nanoTime() >= nextRetryAt) {
                        retriesLeft--
                        nextRetryAt = System.nanoTime() + RETRY_AFTER_MS * 1_000_000L
                        // Повтор — **новый** пакет: дубликат сервер отбрасывает как
                        // повтор метки времени. Прежняя инициация остаётся в списке,
                        // потому что ответ на неё может ещё прийти.
                        val retry = runCatching { buildInitiation(privateKeyB64, peerPublicKeyB64) }.getOrNull()
                        if (retry != null) {
                            sent += retry
                            socket.send(DatagramPacket(retry.packet, retry.packet.size, address))
                        }
                        if (diagnosticLabel != null) {
                            LogManager.log("$diagnosticLabel: повтор рукопожатия, осталось $retriesLeft.")
                        }
                    }
                    continue
                }
                // Проверяется ответ на **любую** из отправленных инициаций: ответ на
                // первую вполне может прийти уже после повтора, и выбросить его
                // значило бы записать живой узел в мёртвые.
                val authentic = response.length >= 1 && buffer[0].toInt() == 2 &&
                    sent.any { verifyResponse(it, buffer, response.length) }
                if (diagnosticLabel != null) {
                    LogManager.log(
                        "$diagnosticLabel: пришёл пакет типа ${buffer[0].toInt()} длиной " +
                            "${response.length}" +
                            if (buffer[0].toInt() == 2) {
                                ", рукопожатие ${if (authentic) "сошлось" else "НЕ сошлось"}."
                            } else {
                                "."
                            }
                    )
                }
                if (authentic) {
                    result = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(1)
                    break
                }
            }
            if (result < 0 && diagnosticLabel != null) {
                LogManager.log("$diagnosticLabel: ответа нет за ${timeoutMs} мс и ${RETRY_LIMIT} повторов.")
            }
            result
        } catch (e: Exception) {
            if (diagnosticLabel != null) {
                LogManager.log("$diagnosticLabel: ответа нет — ${e.javaClass.simpleName} ${e.message}")
            }
            -1
        } finally {
            runCatching { socket?.close() }
        }
    }
}
