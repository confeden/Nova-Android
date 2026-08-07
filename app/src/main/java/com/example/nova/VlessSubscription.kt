package com.example.nova

import java.io.BufferedReader
import java.util.Locale

/**
 * Разбор тела подписки на VLESS-профили и вычисление разницы между старым и
 * новым содержимым.
 *
 * Всё здесь — чистые функции без сети и без Android API: тело читается
 * построчно из [BufferedReader], поэтому список на десятки тысяч строк не
 * попадает в память целиком и не блокирует UI.
 */
object VlessSubscription {

    /** Метаданные подписки из заголовков ответа (или из комментариев в теле). */
    data class Metadata(
        val title: String = "",
        val updateIntervalHours: Int = 0,
        val uploadBytes: Long = -1,
        val downloadBytes: Long = -1,
        val totalBytes: Long = -1,
        val expireAtSeconds: Long = -1,
        val webPageUrl: String = "",
    )

    /** Итог импорта: ничего не отбрасывается молча. */
    data class ImportStats(
        val linesSeen: Int = 0,
        val parsed: Int = 0,
        val invalid: Int = 0,
        val duplicates: Int = 0,
        val kept: Int = 0,
        /** Сколько уникальных профилей осталось за пределами лимита. */
        val skippedOverLimit: Int = 0,
        /** Чтение прервано по достижении лимита — остаток подписки не читался. */
        val stoppedEarly: Boolean = false,
        /**
         * Ссылки других протоколов: сколько каждого. Смешанные подписки — норма,
         * и такие строки не ошибка, а осознанный пропуск: сказать пользователю
         * «12 hysteria2 пропущено» честнее, чем «12 ошибок».
         */
        val skippedByProtocol: Map<String, Int> = emptyMap(),
    ) {
        val truncated: Boolean get() = skippedOverLimit > 0 || stoppedEarly

        val skippedOtherProtocol: Int get() = skippedByProtocol.values.sum()

        /** Человекочитаемая сводка пропущенного, для экрана импорта и логов. */
        fun describeSkippedProtocols(): String =
            skippedByProtocol.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key} — ${it.value}" }
    }

    /**
     * Потоковый разбор: конфигурации отдаются в [onConfig] по одной и нигде не
     * накапливаются. Дубликаты отсеиваются по [VlessConfig.identity] через набор
     * 64-битных хэшей — на подписке в 400 тысяч строк это единицы мегабайт против
     * сотен мегабайт при построении полного списка объектов.
     *
     * Возвращает статистику; вызывающая сторона решает, что делать с усечением.
     */
    fun parseStreaming(
        reader: BufferedReader,
        limit: Int = 20_000,
        stopWhenFull: Boolean = false,
        onConfig: (VlessConfig) -> Unit,
    ): ImportStats {
        var linesSeen = 0
        var parsed = 0
        var invalid = 0
        var duplicates = 0
        var kept = 0
        var skipped = 0
        var stoppedEarly = false
        val skippedByProtocol = LinkedHashMap<String, Int>()
        val seen = LongHashSet(expected = minOf(limit, 1 shl 16))

        while (true) {
            val rawLine = reader.readLine() ?: break
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            linesSeen++
            val otherProtocol = foreignProtocolOf(line)
            if (otherProtocol != null) {
                skippedByProtocol[otherProtocol] = (skippedByProtocol[otherProtocol] ?: 0) + 1
                continue
            }
            val config = VlessConfig.parse(line)
            if (config == null) {
                invalid++
                continue
            }
            parsed++
            if (!seen.add(hash64(config.identity))) {
                duplicates++
                continue
            }
            if (kept >= limit) {
                if (stopWhenFull) {
                    // Дальше не читаем: на подписке в 94 МБ это экономит
                    // и трафик, и время — скачивается лишь начало.
                    stoppedEarly = true
                    break
                }
                skipped++
                continue
            }
            kept++
            onConfig(config)
        }

        return ImportStats(
            linesSeen = linesSeen,
            parsed = parsed,
            invalid = invalid,
            duplicates = duplicates,
            kept = kept,
            skippedOverLimit = skipped,
            stoppedEarly = stoppedEarly,
            skippedByProtocol = skippedByProtocol,
        )
    }

    /**
     * Возвращает имя схемы, если строка — ссылка заведомо другого протокола.
     *
     * Смешанные подписки встречаются постоянно: в списке BLACK_VLESS_RUS_mobile.txt
     * рядом со 135 vless лежат 12 hysteria2 и 2 vmess. Nova умеет только vless,
     * поэтому остальные пропускаются — но их надо посчитать отдельно от битых
     * строк, иначе отчёт об импорте выглядит как список ошибок.
     *
     * Схему определяем по разделителю `://`, а не по списку известных протоколов:
     * завтра появится новый, и он всё равно попадёт в «пропущено», а не в «ошибки».
     */
    private fun foreignProtocolOf(line: String): String? {
        val separator = line.indexOf("://")
        if (separator <= 0) return null
        val scheme = line.substring(0, separator).lowercase(Locale.US)
        if (scheme == "vless") return null
        // Схема — это буквы, цифры, плюс, минус и точка (RFC 3986). Всё прочее
        // означает, что перед нами не ссылка, а мусор: пусть считается битой строкой.
        if (scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            return null
        }
        return scheme
    }

    /** FNV-1a: коллизии для 64 бит на таких объёмах пренебрежимо редки. */
    private fun hash64(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (index in value.indices) {
            hash = hash xor value[index].code.toLong()
            hash *= 0x100000001b3L
        }
        // 0 зарезервирован под «пусто» в LongHashSet.
        return if (hash == 0L) 1L else hash
    }

    /** Открытая адресация без упаковки в объекты: 387k Long вместо 387k Object. */
    private class LongHashSet(expected: Int) {
        private var keys: LongArray
        private var size = 0
        private var mask: Int

        init {
            var capacity = 16
            while (capacity < expected * 2) capacity = capacity shl 1
            keys = LongArray(capacity)
            mask = capacity - 1
        }

        fun add(key: Long): Boolean {
            var index = (key.hashCode() and mask)
            while (true) {
                val current = keys[index]
                if (current == 0L) {
                    keys[index] = key
                    size++
                    if (size * 2 > keys.size) grow()
                    return true
                }
                if (current == key) return false
                index = (index + 1) and mask
            }
        }

        private fun grow() {
            val old = keys
            keys = LongArray(old.size shl 1)
            mask = keys.size - 1
            size = 0
            for (key in old) if (key != 0L) add(key)
        }
    }

    data class Diff(
        val added: List<VlessConfig>,
        val removed: List<VlessConfig>,
        val kept: List<VlessConfig>,
        /** Профили, у которых изменилось только имя — их не нужно пересоздавать. */
        val renamed: List<Pair<VlessConfig, VlessConfig>>,
    ) {
        val isEmpty: Boolean
            get() = added.isEmpty() && removed.isEmpty() && renamed.isEmpty()
    }

    /**
     * Читает подписку построчно. Поддерживает три формы, которые реально
     * отдают провайдеры: список ссылок, base64 от всего тела и base64 без
     * переносов строк.
     */
    fun parse(reader: BufferedReader, limit: Int = 20_000): List<VlessConfig> =
        parseWithMetadata(reader, limit).first

    /**
     * Один проход по телу: ссылки разбираются на лету, а `#`-комментарии из
     * первых строк собираются как метаданные. Список строк целиком нигде не
     * материализуется.
     */
    fun parseWithMetadata(
        reader: BufferedReader,
        limit: Int = 20_000,
    ): Pair<List<VlessConfig>, Metadata> {
        val result = ArrayList<VlessConfig>()
        val base64Buffer = StringBuilder()
        val commentLines = ArrayList<String>(METADATA_SCAN_LINES)
        var sawPlainLink = false
        var lineNumber = 0

        reader.forEachLine { rawLine ->
            if (result.size >= limit) return@forEachLine
            lineNumber++
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine
            if (line.startsWith("#") || line.startsWith("//")) {
                if (lineNumber <= METADATA_SCAN_LINES) commentLines.add(line)
                return@forEachLine
            }

            val config = VlessConfig.parse(line)
            if (config != null) {
                sawPlainLink = true
                result.add(config)
                return@forEachLine
            }
            // Строка не ссылка — возможно, это кусок base64-подписки.
            if (!sawPlainLink && base64Buffer.length < MAX_BASE64_CHARS && looksLikeBase64(line)) {
                base64Buffer.append(line)
            }
        }

        if (result.isEmpty() && base64Buffer.isNotEmpty()) {
            decodeBase64(base64Buffer.toString())?.let { decoded ->
                decoded.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .onEach { line ->
                        if (line.startsWith("#") && commentLines.size < METADATA_SCAN_LINES) {
                            commentLines.add(line)
                        }
                    }
                    .filterNot { it.startsWith("#") }
                    .mapNotNull { VlessConfig.parse(it) }
                    .take(limit)
                    .forEach(result::add)
            }
        }
        return result to parseInlineMetadata(commentLines)
    }

    private const val METADATA_SCAN_LINES = 10

    /**
     * Заголовки ответа и `#`-комментарии в первых строках тела несут одни и те
     * же поля: статические хостинги (GitHub raw) не умеют ставить заголовки.
     */
    fun parseMetadata(headers: Map<String, String>): Metadata {
        fun header(name: String): String =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

        val rawTitle = header("profile-title")
        val title = if (rawTitle.startsWith("base64:", ignoreCase = true)) {
            decodeBase64(rawTitle.substring("base64:".length))?.trim().orEmpty()
        } else {
            rawTitle
        }

        val userInfo = header("subscription-userinfo")
            .split(';')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                part.substring(0, eq).trim().lowercase(Locale.US) to part.substring(eq + 1).trim()
            }
            .toMap()

        fun longOf(key: String): Long = userInfo[key]?.toLongOrNull() ?: -1

        return Metadata(
            title = title,
            updateIntervalHours = header("profile-update-interval").trim().toIntOrNull() ?: 0,
            uploadBytes = longOf("upload"),
            downloadBytes = longOf("download"),
            totalBytes = longOf("total"),
            expireAtSeconds = longOf("expire"),
            webPageUrl = header("profile-web-page-url"),
        )
    }

    /** Те же поля, записанные в теле как `#profile-title: ...`. */
    fun parseInlineMetadata(firstLines: List<String>): Metadata {
        val headers = firstLines.asSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") || it.startsWith("//") }
            .map { it.trimStart('#', '/').trim() }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            .toMap()
        return parseMetadata(headers)
    }

    /**
     * Сравнивает по [VlessConfig.identity], поэтому переименование узла не
     * выглядит как удаление и добавление: пользовательские отметки и
     * измеренные задержки переживают обновление подписки.
     */
    fun diff(previous: List<VlessConfig>, next: List<VlessConfig>): Diff {
        val previousByIdentity = previous.associateBy { it.identity }
        val nextByIdentity = next.associateBy { it.identity }

        val added = next.filter { it.identity !in previousByIdentity }
        val removed = previous.filter { it.identity !in nextByIdentity }
        val kept = ArrayList<VlessConfig>()
        val renamed = ArrayList<Pair<VlessConfig, VlessConfig>>()
        for (config in next) {
            val old = previousByIdentity[config.identity] ?: continue
            kept.add(config)
            if (old.remark != config.remark) renamed.add(old to config)
        }
        return Diff(added = added, removed = removed, kept = kept, renamed = renamed)
    }

    /** Итог применения свежей загрузки к локальному списку профилей. */
    data class SyncPlan(
        val links: List<String>,
        val added: Int,
        val removed: Int,
    ) {
        val changed: Boolean get() = added > 0 || removed > 0
    }

    /**
     * Что сделать со списком профилей после загрузки подписки.
     *
     * Три правила, и каждое стоит за конкретной ошибкой:
     *
     * 1. **Выжившие остаются на своих местах.** Перебор уже расставил их по
     *    работоспособности, и пересортировка по порядку подписки обнулила бы всю
     *    накопленную статистику — мёртвые записи снова уехали бы наверх.
     * 2. **Удаляются только те, что были в подписке и пропали.** Профили, добавленные
     *    вставкой из буфера, подписке не принадлежат, и убирать их она не вправе.
     * 3. **Первая загрузка ничего не удаляет.** Состава прошлой загрузки нет, и любой
     *    уже сохранённый профиль выглядел бы как «пропал из подписки».
     */
    fun planSync(
        existingLinks: List<String>,
        freshLinks: List<String>,
        previousIdentities: Collection<String>,
        limit: Int,
    ): SyncPlan {
        val freshByIdentity = LinkedHashMap<String, String>()
        for (link in freshLinks) {
            val identity = VlessConfig.parse(link)?.identity ?: continue
            freshByIdentity.putIfAbsent(identity, link.trim())
        }
        val goneIdentities = previousIdentities.toSet() - freshByIdentity.keys

        val kept = ArrayList<String>(existingLinks.size)
        var removed = 0
        for (link in existingLinks) {
            val identity = VlessConfig.parse(link)?.identity
            if (identity != null && identity in goneIdentities) {
                removed++
                continue
            }
            kept += link
        }
        val keptIdentities = kept.mapNotNullTo(HashSet()) { VlessConfig.parse(it)?.identity }

        var added = 0
        for ((identity, link) in freshByIdentity) {
            if (kept.size >= limit) break
            if (!keptIdentities.add(identity)) continue
            kept += link
            added++
        }
        return SyncPlan(links = kept, added = added, removed = removed)
    }

    private const val MAX_BASE64_CHARS = 8 * 1024 * 1024

    private val BASE64_LINE = Regex("^[A-Za-z0-9+/=_-]+$")

    private fun looksLikeBase64(line: String): Boolean = BASE64_LINE.matches(line)

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /**
     * Своя реализация вместо java.util.Base64 (API 26+) и android.util.Base64
     * (недоступен в JVM-тестах): подписки приходят и в url-safe алфавите, и без
     * padding, и с переносами строк.
     */
    private fun decodeBase64(value: String): String? {
        val symbols = value.filter { !it.isWhitespace() && it != '=' }
        if (symbols.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(symbols.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (symbol in symbols) {
            val normalized = when (symbol) {
                '-' -> '+'
                '_' -> '/'
                else -> symbol
            }
            val index = ALPHABET.indexOf(normalized)
            if (index < 0) return null
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val decoded = String(out.toByteArray(), Charsets.UTF_8)
        return decoded.takeIf { it.isNotEmpty() }
    }
}
