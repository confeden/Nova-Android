package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Профиль DNS-туннеля в списке пользователя.
 *
 * @param id устойчивый идентификатор: по нему профиль двигают и удаляют, и он
 *        не меняется при переименовании.
 * @param builtIn профиль владельца, встроенный в приложение. Его нельзя
 *        удалить, и он **выключается**, как только появился хоть один свой —
 *        см. [DnsProfileList.selectable].
 * @param configured заполнен ли он на самом деле. Встроенный профиль появляется
 *        в списке раньше, чем под него поднят сервер, и врать об этом нельзя.
 */
data class DnsProfile(
    val id: String,
    val name: String,
    val engine: DnsProfileImport.Engine,
    val domain: String,
    val key: String,
    val encryptionMethod: Int = 0,
    val flavour: String = "",
    val resolvers: List<String> = emptyList(),
    val recordType: String = "",
    val maxQnameLen: Int = 0,
    val needsSsh: Boolean = false,
    val builtIn: Boolean = false,
    val configured: Boolean = true,
) {
    /** Готов ли профиль к подключению — без оглядки на остальной список. */
    val usable: Boolean
        get() = configured && domain.isNotBlank() && key.isNotBlank() && !needsSsh &&
            when (engine) {
                DnsProfileImport.Engine.DNSTT -> true
                // Шифр выбирает сервер, и незнакомый номер — это не «попробуем»:
                // движок с чужим шифром молча не договорится ни об одной несущей.
                DnsProfileImport.Engine.COTTEN -> StormDnsConfig.supportsMethod(encryptionMethod)
            }

    /** Короткое объяснение, почему профиль не подключится. Пусто — подключится. */
    val blockedReason: String
        get() = when {
            !configured -> "сервер ещё не настроен"
            domain.isBlank() || key.isBlank() -> "не хватает адреса или ключа"
            needsSsh -> "за туннелем SSH, клиента пока нет"
            engine == DnsProfileImport.Engine.COTTEN && !StormDnsConfig.supportsMethod(encryptionMethod) ->
                "шифр $encryptionMethod движку неизвестен"
            else -> ""
        }
}

/** Именованный список адресов DNS. */
data class DnsResolverList(
    val id: String,
    val name: String,
    val addresses: List<String>,
    val builtIn: Boolean = false,
)

/**
 * Список профилей и правила выбора.
 *
 * Объект чистый: порядок, выключение встроенного и выбор активного решаются
 * здесь и проверяются тестом, а не наблюдением на экране.
 */
data class DnsProfileList(
    val profiles: List<DnsProfile> = emptyList(),
    /** Идентификатор выбранного списка адресов; пусто — список не выбран. */
    val resolverListId: String = "",
    /** Списки, которые импортировал пользователь. Встроенные лежат в [BUILT_IN_LISTS]. */
    val importedLists: List<DnsResolverList> = emptyList(),
) {

    /** Есть ли хоть один профиль, принесённый пользователем. */
    val hasOwnProfiles: Boolean get() = profiles.any { !it.builtIn }

    /**
     * Можно ли выбрать этот профиль.
     *
     * Правило владельца: как только человек импортировал свой профиль,
     * встроенный выключается и остаётся выключенным, пока свои не удалят.
     * Смысл в том, что встроенный — это запасной выход на чужой сервер, а не
     * то, чем стоит пользоваться, имея собственный.
     */
    fun selectable(profile: DnsProfile): Boolean =
        if (profile.builtIn) !hasOwnProfiles else true

    /**
     * Профиль, которым будем подключаться: первый сверху, который и выбираем, и
     * который готов. Порядок в списке и есть приоритет — потому его и двигают.
     */
    fun active(): DnsProfile? = profiles.firstOrNull { selectable(it) && it.usable }

    /** Все адреса выбранного списка: свой, затем встроенный, иначе пусто. */
    fun resolverAddresses(): List<String> {
        if (resolverListId.isEmpty()) return emptyList()
        importedLists.firstOrNull { it.id == resolverListId }?.let { return it.addresses }
        return BUILT_IN_LISTS.firstOrNull { it.id == resolverListId }?.addresses.orEmpty()
    }

    fun allLists(): List<DnsResolverList> = BUILT_IN_LISTS + importedLists

    /**
     * Профиль в том виде, в каком его берёт транспорт.
     *
     * Адреса из самого профиля идут **впереди** выбранного списка: их прислали
     * вместе с точкой выхода, то есть они про неё и знают, а список — общий.
     */
    fun toEndpoint(profile: DnsProfile): DnsTunnelEndpoint {
        val extra = (profile.resolvers + resolverAddresses())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { addr ->
                val type = if (addr.startsWith("https://", ignoreCase = true)) {
                    DnsTunnelResolver.TYPE_DOH
                } else {
                    DnsTunnelResolver.TYPE_UDP
                }
                DnsTunnelResolver(type, addr)
            }
        return DnsTunnelEndpoint(
            zone = profile.domain,
            pubKey = profile.key,
            dnsttCompat = true,
            maxQnameLen = profile.maxQnameLen,
            recordType = profile.recordType,
            extraResolvers = extra,
        )
    }

    fun withProfileMoved(id: String, delta: Int): DnsProfileList {
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return this
        val target = index + delta
        if (target !in profiles.indices) return this
        val next = ArrayList(profiles)
        next.add(target, next.removeAt(index))
        return copy(profiles = next)
    }

    /** Встроенный профиль не удаляется: он часть приложения, а не выбор человека. */
    fun withProfileRemoved(id: String): DnsProfileList {
        val victim = profiles.firstOrNull { it.id == id } ?: return this
        if (victim.builtIn) return this
        return copy(profiles = profiles.filterNot { it.id == id })
    }

    /**
     * Добавляет профиль, заменяя такой же.
     *
     * Совпадением считается пара «домен + ключ»: человек вставляет ту же ссылку
     * второй раз чаще, чем заводит два разных профиля на один сервер, и вторая
     * копия в списке — это путаница, а не запас.
     */
    fun withProfileAdded(profile: DnsProfile): DnsProfileList {
        val without = profiles.filterNot {
            !it.builtIn && it.domain == profile.domain && it.key == profile.key
        }
        // Свой профиль встаёт **над** встроенным: он и должен работать.
        val builtIns = without.filter { it.builtIn }
        val own = without.filterNot { it.builtIn }
        return copy(profiles = own + profile + builtIns)
    }

    /**
     * Добавляет свой список адресов и сразу его выбирает.
     *
     * Три случая, и они разные:
     * - **те же адреса** уже лежат — это повторная вставка того же самого, и
     *   заводить второй такой же незачем: выбираем существующий;
     * - **то же имя, другие адреса** — это обновление списка под тем же именем;
     * - иначе — новый список.
     *
     * Имя приходит готовым (см. [nextImportedListName]), потому что уникальность
     * имени — вопрос всего набора, а не одного списка.
     */
    fun withListAdded(list: DnsResolverList): DnsProfileList {
        val same = importedLists.firstOrNull { it.addresses == list.addresses }
        if (same != null) return copy(resolverListId = same.id)
        return copy(
            importedLists = importedLists.filterNot { it.name == list.name } + list,
            resolverListId = list.id,
        )
    }

    /**
     * Свободное имя для очередного своего списка: «$base», «$base 2», «$base 3»…
     *
     * Без этого каждый импорт приходил бы под одним именем и молча затирал
     * предыдущий — а человек, вставивший две подборки из разных каналов, видел
     * бы одну и не понимал, куда делась вторая.
     */
    fun nextImportedListName(base: String): String {
        if (importedLists.none { it.name == base }) return base
        var n = 2
        while (importedLists.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    companion object {
        const val BUILT_IN_PROFILE_ID = "nova-default"

        /**
         * Профиль владельца на сервере, известном сборке.
         *
         * Живой адрес приходит из ленты ([DnsServerFeed]) и подставляется при
         * чтении списка (см. [DnsProfileStore.read]); этот — на случай, когда
         * ленты ещё не было.
         */
        val BUILT_IN_PROFILE: DnsProfile = DnsServerFeed.builtInProfile(DnsServerFeed.SEED)

        /**
         * Встроенные списки адресов DNS.
         *
         * Это те самые подборки, которые ходят по тематическим каналам вместе с
         * профилями: у каждой свой набор операторских и публичных резолверов, и
         * какая из них живая — зависит от сети, поэтому выбор оставлен человеку.
         */
        val BUILT_IN_LISTS: List<DnsResolverList> = listOf(
            DnsResolverList(
                id = "ru-mixed",
                name = "Российские, смешанный",
                builtIn = true,
                addresses = listOf(
                    "94.25.113.230", "95.167.150.28", "91.240.86.14", "85.95.168.122",
                    "185.22.235.137", "195.166.180.239", "188.0.190.35", "217.18.135.118",
                    "95.167.75.62", "95.167.26.10", "46.254.19.23", "46.243.233.247",
                    "81.200.149.54", "81.200.149.162", "79.174.92.201",
                ),
            ),
            DnsResolverList(
                id = "ru-yandex",
                name = "С резолверами Яндекса",
                builtIn = true,
                addresses = listOf(
                    "77.88.8.2:53", "77.88.8.3:53", "77.88.8.7:53", "77.88.8.88:53",
                    "77.88.8.8:53", "79.174.92.201:53", "81.200.149.162:53",
                    "83.169.217.22:53", "85.95.168.122:53", "91.240.86.14:53",
                    "94.25.113.230:53", "95.167.75.62:53",
                ),
            ),
            DnsResolverList(
                id = "ru-mobile",
                name = "Операторские и мобильные",
                builtIn = true,
                addresses = listOf(
                    "176.59.127.161:53", "176.59.63.148:53", "176.59.63.204:53",
                    "188.0.190.45:53", "188.0.191.12:53", "188.0.191.31:53",
                    "31.131.251.183:53", "31.131.251.83:53", "5.61.8.20:53",
                    "77.88.8.1:53", "77.88.8.2:53", "77.88.8.88:53", "77.88.8.8:53",
                    "81.200.18.145:53", "84.53.201.202:53", "85.95.168.122:53",
                    "85.95.168.126:53", "95.167.185.66:53", "95.167.26.90:53",
                ),
            ),
        )

        /** Профиль из разобранной ссылки. */
        fun fromImport(profile: DnsProfileImport.Profile, id: String): DnsProfile = DnsProfile(
            id = id,
            name = profile.name.ifBlank { profile.domain },
            engine = profile.engine,
            domain = profile.domain,
            key = profile.key,
            encryptionMethod = profile.encryptionMethod,
            flavour = profile.flavour,
            resolvers = profile.resolvers,
            recordType = profile.recordType,
            maxQnameLen = profile.maxQnameLen,
            needsSsh = profile.innerSsh != null,
        )
    }
}

/**
 * Список профилей на диске — файлом, а не в `SharedPreferences`.
 *
 * Пишет экран, читает процесс `:vpn`, а prefs кэшируются попроцессно (I2).
 */
object DnsProfileStore {

    private const val FILE_NAME = "dns_profiles.json"

    fun read(context: Context?): DnsProfileList {
        val builtIn = DnsServerFeed.builtInProfile(DnsServerFeed.current(context))
        val file = fileFor(context) ?: return withBuiltIn(DnsProfileList(), builtIn)
        val raw = runCatching { file.baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull().orEmpty()
        if (raw.isBlank()) return withBuiltIn(DnsProfileList(), builtIn)
        return withBuiltIn(runCatching { fromJson(raw) }.getOrDefault(DnsProfileList()), builtIn)
    }

    fun write(context: Context?, list: DnsProfileList): Boolean {
        val file = fileFor(context) ?: return false
        return runCatching {
            val stream = file.startWrite()
            try {
                stream.write(toJson(list).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }.getOrDefault(false)
    }

    /**
     * Встроенный профиль дописывается при каждом чтении, а не хранится в файле.
     *
     * Иначе он застыл бы в том виде, в каком был на момент первой записи, и
     * настроенный однажды сервер не доехал бы до тех, у кого файл уже есть.
     */
    fun withBuiltIn(
        list: DnsProfileList,
        builtIn: DnsProfile = DnsProfileList.BUILT_IN_PROFILE,
    ): DnsProfileList {
        if (list.profiles.any { it.builtIn }) {
            return list.copy(
                profiles = list.profiles.map {
                    if (it.builtIn) builtIn else it
                }
            )
        }
        return list.copy(profiles = list.profiles + builtIn)
    }

    fun toJson(list: DnsProfileList): String {
        val profiles = JSONArray()
        // Встроенный в файл не пишем: он приходит из кода (см. withBuiltIn).
        list.profiles.filterNot { it.builtIn }.forEach { p ->
            profiles.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("engine", p.engine.name)
                    .put("domain", p.domain)
                    .put("key", p.key)
                    .put("encryptionMethod", p.encryptionMethod)
                    .put("flavour", p.flavour)
                    .put("resolvers", JSONArray(p.resolvers))
                    .put("recordType", p.recordType)
                    .put("maxQnameLen", p.maxQnameLen)
                    .put("needsSsh", p.needsSsh)
            )
        }
        val lists = JSONArray()
        list.importedLists.forEach { l ->
            lists.put(
                JSONObject().put("id", l.id).put("name", l.name)
                    .put("addresses", JSONArray(l.addresses))
            )
        }
        return JSONObject()
            .put("profiles", profiles)
            .put("resolverListId", list.resolverListId)
            .put("lists", lists)
            .toString()
    }

    fun fromJson(raw: String): DnsProfileList {
        val json = JSONObject(raw)
        val profiles = ArrayList<DnsProfile>()
        json.optJSONArray("profiles")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val engine = runCatching {
                    DnsProfileImport.Engine.valueOf(o.optString("engine"))
                }.getOrDefault(DnsProfileImport.Engine.DNSTT)
                profiles.add(
                    DnsProfile(
                        id = o.optString("id").ifBlank { "p$i" },
                        name = o.optString("name"),
                        engine = engine,
                        domain = o.optString("domain"),
                        key = o.optString("key"),
                        encryptionMethod = o.optInt("encryptionMethod", 0),
                        flavour = o.optString("flavour"),
                        resolvers = stringList(o.optJSONArray("resolvers")),
                        recordType = o.optString("recordType"),
                        maxQnameLen = o.optInt("maxQnameLen", 0),
                        needsSsh = o.optBoolean("needsSsh", false),
                    )
                )
            }
        }
        val lists = ArrayList<DnsResolverList>()
        json.optJSONArray("lists")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                lists.add(
                    DnsResolverList(
                        id = o.optString("id").ifBlank { "l$i" },
                        name = o.optString("name"),
                        addresses = stringList(o.optJSONArray("addresses")),
                    )
                )
            }
        }
        return DnsProfileList(
            profiles = profiles,
            resolverListId = json.optString("resolverListId"),
            importedLists = lists,
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val v = array.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }
}
