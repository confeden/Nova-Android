package com.example.nova

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Память подбора `I1` по сетям: какой домен в поддельном QUIC Initial на какой сети работал.
 *
 * Зачем. [AwgI1Adaptation] подбирает имя на профиль, а работает оно на сеть: дома оно
 * проходит, у оператора режется, у соседа по подъезду наоборот. Без памяти о сети каждый
 * переезд начинался с прежнего перебора. Здесь запоминается пара «сеть — домен» и итог
 * последних подключений, чтобы на знакомой сети сразу ставить то, что на ней уже прошло, а
 * известно негодное — пропускать.
 *
 * Объект чистый: ни `Context`, ни ввода-вывода, ни времени — всё приходит снаружи. Файл
 * читает и пишет `ClientData` (тот же `awg_i1_overrides.json`, что и у подбора, I2).
 *
 * Приватность. Сеть опознаётся ключом, а не именем: для Wi-Fi это первые восемь знаков
 * SHA-256 от имени сети (или шлюза, если имя Android не отдал), для сотовой — код оператора
 * MCC+MNC. Имя точки доступа в файл не попадает.
 */
object NetworkI1Memory {

    /** Сколько сетей помним; при переполнении вытесняется давно не виденная. */
    const val MAX_NETWORKS = 32

    /** Сколько доменов помним на сеть — хвост самых давних отбрасывается. */
    const val MAX_SNI_PER_NETWORK = 16

    /** Столько неудач подряд без единого успеха между ними — и домен на сети считается негодным. */
    const val DEMOTE_FAILURES = 2

    /**
     * @param successes сколько раз домен дал data-plane на этой сети
     * @param failures неудачи **с последнего успеха**: успех обнуляет счётчик, потому что
     *        важно, держится ли домен сейчас, а не что было весной
     */
    data class SniStat(
        val sni: String,
        val successes: Int,
        val failures: Int,
        val lastAt: Long,
    )

    data class NetworkEntry(
        val key: String,
        val usedAt: Long,
        val stats: List<SniStat>,
    )

    /** Сети от давней к свежей: последняя — та, с которой работали только что. */
    data class Memory(val networks: List<NetworkEntry> = emptyList())

    /**
     * Ключ сети из простых входов.
     *
     * @param networkClass `wifi`, `cell`, `eth` (то же правило, что у
     *        `stableSuccessNetworkClassFromSignature`); `other` и null — ключа нет
     * @param wifiName имя Wi-Fi, пустое, если Android его не отдаёт (нужно разрешение на геолокацию)
     * @param gateway шлюз по умолчанию — запасная опора для Wi-Fi вместо имени
     * @param operatorNumeric `TelephonyManager.getNetworkOperator()`, например `25002`
     * @return null, когда сеть опознать нечем: лучше не помнить, чем слить все безымянные сети в одну
     */
    fun networkKey(
        networkClass: String?,
        wifiName: String,
        gateway: String,
        operatorNumeric: String,
    ): String? = when (networkClass?.trim()?.lowercase()) {
        "wifi" -> {
            val identity = wifiName.trim().ifBlank { gateway.trim() }
            if (identity.isEmpty()) null else "wifi:" + hash8(identity)
        }
        "cell" -> {
            val code = operatorNumeric.trim()
            if (code.length in 5..6 && code.all { it.isDigit() }) "cell:$code" else null
        }
        "eth" -> "eth"
        else -> null
    }

    internal fun hash8(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { "%02x".format(it) }

    /** Негоден ли домен на сети: [DEMOTE_FAILURES] неудач подряд после последнего успеха. */
    private fun SniStat.demoted(): Boolean = failures >= DEMOTE_FAILURES

    /**
     * Записывает итог подключения с доменом [sni] на сети [key].
     *
     * Пустой ключ или домен — запись не меняется. Сеть с этим итогом становится самой
     * свежей, а при переполнении вытесняется самая давняя.
     */
    fun record(memory: Memory, key: String?, sni: String, success: Boolean, nowMs: Long): Memory {
        val networkKey = key?.trim().orEmpty()
        val name = SniMaskPolicy.normalizeHost(sni)
        if (networkKey.isEmpty() || name.isBlank()) return memory
        val existing = memory.networks.firstOrNull { it.key == networkKey }
        val stats = existing?.stats.orEmpty()
        val old = stats.firstOrNull { it.sni == name }
        val updated = if (success) {
            SniStat(name, (old?.successes ?: 0) + 1, 0, nowMs)
        } else {
            SniStat(name, old?.successes ?: 0, (old?.failures ?: 0) + 1, nowMs)
        }
        val newStats = (stats.filter { it.sni != name } + updated)
            .sortedByDescending { it.lastAt }
            .take(MAX_SNI_PER_NETWORK)
        val entry = NetworkEntry(networkKey, nowMs, newStats)
        val networks = (memory.networks.filter { it.key != networkKey } + entry)
            .let { if (it.size > MAX_NETWORKS) it.takeLast(MAX_NETWORKS) else it }
        return Memory(networks)
    }

    /** Лучший домен сети: был успех и он не негоден сейчас; больше успехов — выше, затем свежее. */
    fun best(memory: Memory, key: String?): String? {
        val networkKey = key?.trim().orEmpty()
        if (networkKey.isEmpty()) return null
        return memory.networks.firstOrNull { it.key == networkKey }
            ?.stats
            ?.filter { it.successes > 0 && !it.demoted() }
            ?.maxWithOrNull(compareBy<SniStat> { it.successes }.thenBy { it.lastAt })
            ?.sni
    }

    /** Домены, которые на этой сети уже подвели и предлагать их снова незачем. */
    fun demoted(memory: Memory, key: String?): Set<String> {
        val networkKey = key?.trim().orEmpty()
        if (networkKey.isEmpty()) return emptySet()
        return memory.networks.firstOrNull { it.key == networkKey }
            ?.stats
            ?.filter { it.demoted() }
            ?.map { it.sni }
            ?.toSet()
            .orEmpty()
    }

    fun toJson(memory: Memory): JSONObject = JSONObject().put(
        "n",
        JSONArray().also { array ->
            memory.networks.forEach { net ->
                array.put(
                    JSONObject()
                        .put("k", net.key)
                        .put("at", net.usedAt)
                        .put(
                            "s",
                            JSONArray().also { list ->
                                net.stats.forEach { stat ->
                                    list.put(
                                        JSONObject()
                                            .put("sni", stat.sni)
                                            .put("ok", stat.successes)
                                            .put("bad", stat.failures)
                                            .put("at", stat.lastAt),
                                    )
                                }
                            },
                        ),
                )
            }
        },
    )

    /** Разбор устойчив к мусору: битая запись пропускается, а не роняет весь подбор. */
    fun fromJson(json: JSONObject?): Memory {
        val array = json?.optJSONArray("n") ?: return Memory()
        val networks = ArrayList<NetworkEntry>()
        for (i in 0 until array.length()) {
            val net = array.optJSONObject(i) ?: continue
            val key = net.optString("k")
            if (key.isBlank()) continue
            val list = net.optJSONArray("s")
            val stats = ArrayList<SniStat>()
            if (list != null) {
                for (j in 0 until list.length()) {
                    val stat = list.optJSONObject(j) ?: continue
                    val sni = stat.optString("sni")
                    if (sni.isBlank()) continue
                    stats += SniStat(sni, stat.optInt("ok", 0), stat.optInt("bad", 0), stat.optLong("at", 0L))
                }
            }
            networks += NetworkEntry(key, net.optLong("at", 0L), stats.take(MAX_SNI_PER_NETWORK))
        }
        return Memory(networks.takeLast(MAX_NETWORKS))
    }
}
