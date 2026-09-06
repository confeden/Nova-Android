package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Один резолвер в пользовательской цепочке.
 *
 * @param kind чем говорить: открытый UDP, DoH или DoT. Это не украшение —
 *        от вида зависит и порт, и то, можно ли доверять быстрому ответу.
 * @param value адрес: IP для [Kind.PLAIN], `https://…` для [Kind.DOH],
 *        `tls://имя[:порт]` для [Kind.DOT].
 * @param bootstrap адреса, по которым дозваниваться до имени зашифрованного
 *        резолвера. Без них имя пришлось бы разрешать через тот самый перехват,
 *        который его и спрашивает.
 */
data class DnsRule(
    val id: String,
    val kind: Kind,
    val value: String,
    val bootstrap: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    /**
     * Чем разговаривать с резолвером.
     *
     * [AUTO] и [PROVIDER] появились вместе с переделкой экрана под требование
     * владельца «список только защищённых адресов, а незашифрованный —
     * единственный, провайдерский».
     *
     * * [AUTO] — наш резолвер, транспорт выбирается замером: 443 и 853 блокируют
     *   порознь, и какой из них жив сегодня, знает только сеть. Одна строка на
     *   экране вместо двух одинаковых.
     * * [PROVIDER] — резолвер самого провайдера. Его адрес неизвестен до
     *   подключения: он приходит из `LinkProperties` активной сети, поэтому в
     *   правиле лежит не адрес, а метка.
     */
    enum class Kind { PLAIN, DOH, DOT, AUTO, PROVIDER }

    /**
     * Шифрованный ли резолвер. Решает, можно ли пускать его в гонку путей.
     *
     * Провайдерский — нет, и это не мелочь: подделанный ответ от оператора
     * приходит раньше настоящего, и «быстрейший» выбрал бы подделку.
     */
    val encrypted: Boolean get() = kind != Kind.PLAIN && kind != Kind.PROVIDER

    /**
     * Строка для ядра: `<цель>[|<bootstrap>]*[|via=<путь>]`.
     *
     * Весь список апстримов уезжает в ядро одной строкой через запятую, поэтому
     * поля внутри одного апстрима разделяет `|`. Значение с запятой или `|`
     * внутри развалило бы список, и такие правила сюда не доходят —
     * [normalizeValue] их не пропускает.
     */
    fun toUpstream(route: DnsRouteMode): String = toUpstream(route, value)

    /**
     * То же, но с явной целью.
     *
     * Нужно для [Kind.AUTO] и [Kind.PROVIDER]: у первого цель выбирается замером
     * транспорта, у второго приходит из свойств сети, и оба раскрываются в
     * настоящий адрес уже в службе.
     */
    fun toUpstream(route: DnsRouteMode, target: String): String = buildString {
        append(target)
        bootstrap.forEach { append('|').append(it) }
        // Открытый резолвер в гонке путей не участвует: подделанный ответ
        // приходит раньше настоящего, и «быстрейший» выбрал бы подделку. Ядро
        // проверяет это ещё раз у себя — здесь мы просто не пишем лишнего.
        val effective = if (route == DnsRouteMode.AUTO && !encrypted) DnsRouteMode.DIRECT else route
        when (effective) {
            DnsRouteMode.DIRECT -> Unit // умолчание ядра, писать нечего
            DnsRouteMode.TUNNEL -> append("|via=tunnel")
            DnsRouteMode.AUTO -> append("|via=auto")
        }
    }

    /**
     * Адреса, которые надо вывести из туннеля маршрутом, если путь прямой.
     *
     * У [Kind.PROVIDER] их здесь нет и быть не может: адрес резолвера оператора
     * известен только по свойствам активной сети, и подставляет его служба.
     */
    fun directAddresses(): List<String> = when (kind) {
        Kind.PLAIN -> listOf(value)
        Kind.PROVIDER -> emptyList()
        else -> bootstrap
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name.lowercase())
        .put("value", value)
        .put("enabled", enabled)
        .put("bootstrap", JSONArray().also { array -> bootstrap.forEach(array::put) })

    companion object {
        fun fromJson(json: JSONObject): DnsRule? {
            val kind = when (json.optString("kind").lowercase()) {
                "doh" -> Kind.DOH
                "dot" -> Kind.DOT
                "plain" -> Kind.PLAIN
                "auto" -> Kind.AUTO
                "provider" -> Kind.PROVIDER
                else -> return null
            }
            val value = normalizeValue(kind, json.optString("value")) ?: return null
            val bootstrapJson = json.optJSONArray("bootstrap")
            val bootstrap = buildList {
                for (i in 0 until (bootstrapJson?.length() ?: 0)) {
                    normalizeAddress(bootstrapJson!!.optString(i))?.let(::add)
                }
            }
            return DnsRule(
                id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                kind = kind,
                value = value,
                bootstrap = bootstrap.distinct(),
                enabled = !json.has("enabled") || json.optBoolean("enabled", true),
            )
        }

        /**
         * Приводит значение к виду, который ядро разберёт, или отказывает.
         *
         * Запятая и `|` запрещены во всех видах: первая разрывает список
         * апстримов, вторая означает начало служебного токена. Пропустить их
         * значило бы отдать в ядро правило, которое разберётся во что-то другое,
         * молча и не там, где его вводили.
         */
        fun normalizeValue(kind: Kind, raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.contains(',') || trimmed.contains('|')) return null
            return when (kind) {
                Kind.PLAIN -> normalizeAddress(trimmed)
                // Метка, а не адрес: сам адрес приходит из свойств сети при
                // подключении. Пустое значение здесь развалило бы разбор файла и
                // унесло бы с собой выключатель пользователя.
                Kind.PROVIDER -> PROVIDER_VALUE
                // Имя нашего резолвера без схемы: транспорт подставит служба.
                Kind.AUTO -> trimmed.substringAfter("://").substringBefore('/')
                    .takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) }
                Kind.DOH -> {
                    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
                    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
                    if (uri.host.isNullOrBlank()) return null
                    trimmed
                }
                Kind.DOT -> {
                    val body = if (trimmed.startsWith("tls://", ignoreCase = true)) {
                        trimmed.substring(6)
                    } else {
                        trimmed
                    }
                    val host = body.substringBefore(':').trim().trim('[', ']')
                    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
                    "tls://$body"
                }
            }
        }

        fun normalizeAddress(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.contains(',') || trimmed.contains('|')) return null
            // Только литеральные адреса: имя здесь пришлось бы резолвить через
            // тот самый перехват, ради которого bootstrap и существует.
            val looksIpv4 = trimmed.count { it == '.' } == 3 &&
                trimmed.split('.').all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toInt() in 0..255 }
            val looksIpv6 = trimmed.contains(':') &&
                trimmed.all { it.isDigit() || it in "abcdefABCDEF:." }
            return if (looksIpv4 || looksIpv6) trimmed else null
        }

        /** Метка провайдерского правила: адрес у него появляется только на сети. */
        const val PROVIDER_VALUE: String = "provider"

        fun create(kind: Kind, value: String, bootstrap: List<String> = emptyList()): DnsRule? {
            val normalized = normalizeValue(kind, value) ?: return null
            return DnsRule(
                id = UUID.randomUUID().toString(),
                kind = kind,
                value = normalized,
                bootstrap = bootstrap.mapNotNull(::normalizeAddress).distinct(),
            )
        }
    }
}

/** Каким путём приложение ходит к резолверам. */
enum class DnsRouteMode {
    /** Мимо туннеля, по сети провайдера. Работает и когда туннеля ещё нет. */
    DIRECT,

    /** Внутрь туннеля. Провайдер не видит даже факта обращения к резолверу. */
    TUNNEL,

    /** Спросить обоими путями и запомнить тот, что ответил первым. */
    AUTO;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun parse(raw: String?): DnsRouteMode = when (raw?.trim()?.lowercase()) {
            "tunnel", "vpn" -> TUNNEL
            "direct", "isp" -> DIRECT
            // «fastest» — имя из первой версии экрана; значение то же.
            "auto", "fastest" -> AUTO
            else -> AUTO
        }
    }
}

/** Порядок резолверов и путь до них — как их видит и правит пользователь. */
data class DnsRuleSet(
    val rules: List<DnsRule>,
    val routeMode: DnsRouteMode,
    /**
     * Пользуется ли туннель этим списком вообще.
     *
     * Отдельно от «включено» у каждого правила: это выключатель всей настройки
     * целиком, а не способ погасить одну строку. Выключен — резолвинг идёт по
     * встроенной цепочке, как до появления экрана.
     *
     * По умолчанию **включено**: список не пуст с первого запуска, и умолчания в
     * нём — резолвер владельца. Прийти на экран, увидеть заполненный список и
     * узнать, что он ни на что не влияет, — это ровно тот дефект, ради которого
     * весь экран и переделывался.
     */
    val enabled: Boolean = true,
) {
    /** Строки апстримов для ядра, в порядке правил; выключенные пропускаются. */
    fun upstreams(): List<String> =
        if (!enabled) emptyList()
        else rules.filter { it.enabled }.map { it.toUpstream(routeMode) }.distinct()

    /**
     * Адреса, которые обязаны обходить туннель маршрутом.
     *
     * Пусто при [DnsRouteMode.TUNNEL]: там запрос должен идти внутрь, и вырез
     * маршрута отменил бы ровно то, ради чего режим выбран. При [DnsRouteMode.AUTO]
     * вырез нужен — иначе прямой путь в гонке не с чем сравнивать.
     */
    fun directBypassAddresses(): List<String> {
        if (!enabled || routeMode == DnsRouteMode.TUNNEL) return emptyList()
        return rules.filter { it.enabled }.flatMap { it.directAddresses() }.distinct()
    }
}

/**
 * Хранилище правил DNS.
 *
 * **Файл, а не `SharedPreferences`** — и это не вкусовщина (I2). Правила пишет
 * экран настроек, а читает процесс `:vpn`; у каждого процесса свой кэш настроек,
 * и `commit()` одного откатывает запись другого. На этом уже трижды теряли
 * состояние (G2, G16, G70).
 */
object DnsRulesStore {

    private const val FILE_NAME = "dns_rules.json"

    /**
     * 3 — версия, в которой из списка ушли открытые резолверы.
     *
     * Версия 2 везла в умолчаниях `1.1.1.1` и `1.0.0.1` открытыми правилами, и
     * это был не косметический недостаток: адрес открытого правила уезжает в
     * `directBypassDnsAddresses`, оттуда — в `excludeRoute`, и запрос к нему
     * покидает туннель вообще. Тем же путём наружу уходил и замер выхода на
     * `https://1.1.1.1/cdn-cgi/trace`, из-за чего бейдж на живой сессии Proton
     * показывал российский адрес и «RU». Обновление обязано их убрать, а не
     * оставить как «выбор пользователя», которого он не делал.
     */
    private const val VERSION = 3

    private val writeLock = Any()

    /**
     * Умолчания — резолвер владельца, дважды.
     *
     * Сначала DoH, следом DoT к тому же серверу: у него нет открытой версии
     * (53/udp молчит, 53/tcp отказывает — измерено на МегаФон LTE), поэтому его
     * адреса имеют смысл только как bootstrap, а не как отдельные строки списка.
     * Два вида транспорта нужны потому, что 443 и 853 блокируются порознь.
     *
     * Дальше — открытые публичные резолверы запасной ступенью: они видят имена,
     * но лучше отвечать через них, чем не отвечать вовсе.
     */
    fun defaults(): List<DnsRule> = listOf(
        // Наш резолвер — один строкой, транспорт выберет замер: 443 и 853
        // блокируют порознь, и какой из них сегодня жив, знает только сеть.
        DnsRule(
            id = "owner",
            kind = DnsRule.Kind.AUTO,
            value = PriorityDns.HOST,
            bootstrap = PriorityDns.KNOWN_ADDRESSES,
        ),
        // Дальше — порядок, который назвал владелец. Все адреса сверены со
        // страницами самих операторов 2026-09-05; bootstrap нужен потому, что имя
        // резолвера иначе пришлось бы разрешать через тот самый перехват, который
        // его и спрашивает.
        DnsRule(
            id = "comss",
            kind = DnsRule.Kind.DOH,
            value = "https://dns.comss.one/dns-query",
            bootstrap = listOf("83.220.169.155", "212.109.195.93", "195.133.25.16"),
        ),
        DnsRule(
            id = "geohide",
            kind = DnsRule.Kind.DOH,
            value = "https://geohide.ru/dns-query",
            bootstrap = listOf("193.233.112.67", "193.233.112.68", "46.8.158.6"),
        ),
        DnsRule(
            id = "xbox",
            kind = DnsRule.Kind.DOH,
            value = "https://xbox-dns.ru/dns-query",
            bootstrap = listOf("111.88.96.50", "111.88.96.51"),
        ),
        DnsRule(
            id = "cloudflare",
            kind = DnsRule.Kind.DOH,
            value = "https://cloudflare-dns.com/dns-query",
            bootstrap = listOf("1.1.1.1", "1.0.0.1"),
        ),
        DnsRule(
            id = "google",
            kind = DnsRule.Kind.DOH,
            value = "https://dns.google/dns-query",
            bootstrap = listOf("8.8.8.8", "8.8.4.4"),
        ),
        // Последняя ступень — резолвер оператора, и он единственный открытый.
        // Выключается отдельно от остальных: это его и делает «запасным», а не
        // обязательным.
        DnsRule(
            id = PROVIDER_RULE_ID,
            kind = DnsRule.Kind.PROVIDER,
            value = DnsRule.PROVIDER_VALUE,
        ),
    )

    /** Идентификатор провайдерского правила — он же его выключатель. */
    const val PROVIDER_RULE_ID: String = "provider"

    /** Идентификаторы умолчаний версии 2 — их миграция вправе выбросить. */
    private val LEGACY_DEFAULT_IDS = setOf("owner-doh", "owner-dot", "cloudflare", "cloudflare-2")

    fun defaultSet(): DnsRuleSet = DnsRuleSet(defaults(), DnsRouteMode.AUTO)

    fun load(context: Context?): DnsRuleSet {
        val file = fileFor(context) ?: return defaultSet()
        val raw = readRaw(file)
        if (raw.isNullOrBlank()) return defaultSet()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return defaultSet()
        val array = json.optJSONArray("rules")
        val rules = buildList {
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                DnsRule.fromJson(item)?.let(::add)
            }
        }
        // Пустой список — это не «пользователь всё удалил», а сломанный или
        // недописанный файл: экран не даёт удалить последнее правило. Отдать
        // пустоту значило бы выключить резолвинг целиком.
        // Отсутствие поля — это файл прошлой версии, где выключателя не было и
        // список работал. Считать его выключенным значило бы молча отключить
        // настройку при обновлении.
        val enabled = !json.has("enabled") || json.optBoolean("enabled", true)
        if (rules.isEmpty()) {
            return DnsRuleSet(defaults(), DnsRouteMode.parse(json.optString("route_mode")), enabled)
        }
        val migrated = migrate(json.optInt("version", 1), rules)
        return DnsRuleSet(migrated, DnsRouteMode.parse(json.optString("route_mode")), enabled)
    }

    /**
     * Перевод файла версии 2 на новый список.
     *
     * Что сохраняется: правила, которые добавил сам пользователь, и их порядок, и
     * состояние выключателя у каждого. Что выбрасывается: умолчания прошлой
     * версии — оба открытых Cloudflare (они и есть утечка, ради которой всё это) и
     * пара «наш DoH + наш DoT», которую заменила одна строка с автовыбором.
     *
     * Что дописывается: новая цепочка целиком, но **после** пользовательских
     * правил и без дублей, плюс провайдерская ступень последней.
     *
     * Открытые правила пользователя тоже выбрасываются — с записью в журнал.
     * Оставить их значило бы оставить и вырез маршрута к ним, то есть оставить
     * ровно ту дыру, которую закрываем.
     */
    private fun migrate(version: Int, rules: List<DnsRule>): List<DnsRule> {
        if (version >= VERSION) return rules
        val kept = rules.filter { it.id !in LEGACY_DEFAULT_IDS && it.encrypted }
        val dropped = rules.count { it.id !in LEGACY_DEFAULT_IDS && !it.encrypted }
        if (dropped > 0) {
            LogManager.log(
                "DNS-правила: при обновлении списка убраны $dropped открытых резолвера — " +
                    "незашифрованным остаётся только резолвер провайдера, и он выключается отдельно."
            )
        }
        val byKey = kept.associateBy { it.value.lowercase() }
        val tail = defaults().filterNot { byKey.containsKey(it.value.lowercase()) }
        return kept + tail
    }

    /**
     * Счётчик записей — чтобы чужой кэш знал, что список изменился.
     *
     * Сводка на экране DNS кэширует прочитанный набор на секунду, иначе чтение
     * файла попадало бы в главный поток на каждое нажатие клавиши. Без этого
     * счётчика правка списка внутри той же секунды показывала бы прошлый состав.
     */
    @Volatile
    var revision: Long = 0L
        private set

    fun save(context: Context?, set: DnsRuleSet): Boolean {
        val file = fileFor(context) ?: return false
        val payload = JSONObject()
            .put("version", VERSION)
            .put("route_mode", set.routeMode.storageValue())
            .put("enabled", set.enabled)
            .put("rules", JSONArray().also { array -> set.rules.forEach { array.put(it.toJson()) } })
        val written = writeRaw(file, payload.toString())
        if (written) revision += 1
        return written
    }

    /** Есть ли уже сохранённые правила. Пусто — экран покажет умолчания. */
    fun exists(context: Context?): Boolean =
        fileFor(context)?.baseFile?.let { it.exists() && it.length() > 0 } == true

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }

    /**
     * Чтение мимо `readFully()`.
     *
     * До Android 11 `AtomicFile.openRead()` разрушителен для чужой незавершённой
     * записи (G66): читаем основной файл, к резервному переходим только когда
     * основной пуст.
     */
    private fun readRaw(file: AtomicFile): String? {
        val base = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
        if (!base.isNullOrBlank()) return base
        val backup = File(file.baseFile.path + ".bak")
        return runCatching { backup.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun writeRaw(file: AtomicFile, payload: String): Boolean {
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            return try {
                stream = file.startWrite()
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("DNS-правила: не записались — ${e.message}")
                false
            }
        }
    }
}
