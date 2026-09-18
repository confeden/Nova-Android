package com.example.nova

import java.util.Locale

/**
 * Перевод уже готового русского текста по каталогу «русский исходник → перевод».
 *
 * ## Почему ключ — сам русский текст
 *
 * Интерфейс написан по-русски, и русские литералы остаются в коде: по ним ищут
 * строки в логах и в базе знаний (I14). Переписать 1100 строк на идентификаторы
 * ресурсов значило бы потерять эти ключи и тронуть каждый путь подключения.
 * Поэтому код по-прежнему ставит русский текст, а перевод подменяет его в момент
 * показа — так же устроен gettext, где ключ — исходная фраза.
 *
 * ## Что умеет сопоставление
 *
 * Текст на экран часто приходит уже собранным: `"Попытка $n из $total"` — это
 * «Попытка 3 из 12», а не сам литерал. Поэтому у каталога две части:
 *
 *  * точные строки — `"Настройки" → "Settings"`;
 *  * шаблоны с местами `{0}`, `{1}` — `"Попытка {0} из {1}" → "Attempt {0} of {1}"`.
 *    Подставленные значения переводятся тем же каталогом: в «Статус: подключено»
 *    переведётся и рамка, и само «подключено».
 *
 * Если целиком не сошлось, текст режется: по строкам, по разделителям вида « · »,
 * по предложениям. Экран склеивает части через `joinToString` и `append`, и каждая
 * часть — отдельный литерал. Из всех разрезаний берётся то, после которого русских
 * букв осталось меньше всего. Последний шаг — куски с полем по краю
 * («Встроенных: »), узнанные прямо внутри текста.
 *
 * Разбор одного текста ограничен бюджетом [WORK_BUDGET]: длинный текст с десятком
 * разделителей иначе перебирал бы разрезания вложенно и заметно дольше кадра.
 *
 * Непереведённый текст возвращается **тем же объектом**: по `===` вызывающий
 * отличает «перевода нет» от «перевод совпал с исходником».
 *
 * Класс без зависимостей от Android: его проверяют обычные unit-тесты.
 */
class NovaTranslationCatalog private constructor(
    private val exact: HashMap<String, String>,
    private val upper: HashMap<String, String>,
    private val templates: Array<Template>,
    private val fragmentsByFirstChar: HashMap<Char, Array<Fragment>>,
    private val infixes: Array<Infix>,
) {

    val size: Int get() = exact.size + templates.size

    /**
     * Шаблон: исходник, разрезанный местами подстановки на куски.
     *
     * `chunks.size == slots.size + 1`; `slots[i]` — номер места между `chunks[i]`
     * и `chunks[i + 1]`. Перевод разобран заранее на такие же куски, чтобы сборка
     * не парсила строку на каждом кадре.
     */
    private class Template(
        val chunks: Array<String>,
        val slots: IntArray,
        val outChunks: Array<String>,
        val outSlots: IntArray,
    ) {
        /** Длина всех буквальных кусков: чем больше, тем конкретнее шаблон. */
        val literalLength: Int = chunks.sumOf { it.length }

        /** Самый длинный кусок — дешёвый фильтр до регулярного выражения. */
        val anchor: String = chunks.maxByOrNull { it.length }.orEmpty()

        @Volatile
        private var compiled: Regex? = null

        val regex: Regex
            get() = compiled ?: Regex(
                buildString {
                    append('^')
                    chunks.forEachIndexed { index, chunk ->
                        if (chunk.isNotEmpty()) append(Regex.escape(chunk))
                        if (index < slots.size) append("(.*?)")
                    }
                    append('$')
                },
                RegexOption.DOT_MATCHES_ALL,
            ).also { compiled = it }

        fun mightMatch(text: String): Boolean =
            text.length >= literalLength &&
                text.startsWith(chunks.first()) &&
                text.endsWith(chunks.last()) &&
                (anchor.isEmpty() || text.contains(anchor))
    }

    /**
     * Кусок фразы с явным полем по краю — «Встроенных: », « • вручную: ». Такие куски
     * код склеивает через `buildString`/`append`, и целой фразы в каталоге нет;
     * зато сами куски узнаются внутри готового текста: их граница задана пробелом.
     */
    private class Fragment(val source: String, val translation: String)

    /**
     * Шаблон-вставка: начинается с поля и русского слова — « + ещё {0}», « (адресами
     * применяются первые {0})». Код приклеивает его к чужой строке («DNS: dns.x.ru
     * + ещё 6, автовыбор пути»), поэтому он ищется внутри текста, а не целиком.
     * Последнее место, если за ним нет буквального текста, берёт только одно «слово»
     * до запятой или пробела: иначе « + ещё {0}» съел бы весь хвост строки.
     */
    private class Infix(val template: Template, val regex: Regex)

    /** Сколько разборов ещё можно сделать для одного текста. */
    private class Budget(var left: Int)

    /**
     * Переводит текст. Возвращает сам [text], если перевести нечего.
     */
    fun translate(text: String): String = translate(text, 0, Budget(WORK_BUDGET))

    private fun translate(text: String, depth: Int, budget: Budget): String {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH || !hasCyrillic(text)) return text
        exact[text]?.let { return it }
        if (budget.left <= 0) return text
        budget.left--

        // Шаблон, после которого не осталось русского, — ответ. Если осталось, шаблон
        // мог проглотить склейку: «{0} КБ/с» целиком съедает строку уведомления
        // «WARP · NL · ↓ 1,4 МБ/с  ↑ 20 КБ/с», и «МБ/с» остаётся в месте подстановки.
        // Тогда ниже пробуем разрезать и берём вариант, где русского меньше.
        val viaTemplate = matchTemplate(text, depth, budget)
        if (viaTemplate != null && !hasCyrillic(viaTemplate)) return viaTemplate

        // Поля вокруг текста: разметка и код добавляют пробелы и переводы строк
        // там, где каталог хранит фразу без них.
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return text
        val end = text.indexOfLast { !it.isWhitespace() }
        if (start > 0 || end < text.length - 1) {
            val core = text.substring(start, end + 1)
            val translated = translate(core, depth, budget)
            if (translated === core) return viaTemplate ?: text
            return lessRussian(viaTemplate, text.substring(0, start) + translated + text.substring(end + 1))
        }

        upper[text]?.let { return it }

        var best = viaTemplate
        // Знак препинания, который код дописал после фразы каталога: «Режим сети: » +
        // «не определён — российские идут первыми» + «.».
        val last = text.last()
        if (last in TRAILING_PUNCTUATION && text.length > 1 && text[text.length - 2] != last) {
            val core = text.dropLast(1)
            val translated = translate(core, depth, budget)
            if (translated !== core) {
                val candidate = translated + last
                if (!hasCyrillic(candidate)) return candidate
                best = lessRussian(best, candidate)
            }
        }
        if (depth < MAX_DEPTH) {
            // Все разделители, а не первый сработавший: в «Сейчас выбран OPERA, поэтому
            // регулировка скрыта: она не изменила бы ничего. Выберите…» разрез по «: »
            // тоже что-то переводит, но целиком фразы находит только разрез по «. ».
            for (separator in SEPARATORS) {
                if (budget.left <= 0) break
                val split = splitAndTranslate(text, separator, depth, budget) ?: continue
                best = lessRussian(best, split)
                if (!hasCyrillic(split)) return split
            }
        }
        // Куски и вставки ищутся в уже частично переведённом тексте: разрезание и
        // они дополняют друг друга, а не соревнуются.
        replaceFragments(best ?: text)?.let { best = lessRussian(best, it) }
        if (best == null || hasCyrillic(best!!)) {
            replaceInfixes(best ?: text, budget)?.let { best = lessRussian(best, it) }
        }
        return best ?: text
    }

    private fun replaceInfixes(text: String, budget: Budget): String? {
        var current = text
        var changed = false
        for (infix in infixes) {
            if (budget.left <= 0) break
            if (!current.contains(infix.template.chunks.first())) continue
            current = infix.regex.replace(current) { match ->
                changed = true
                val values = arrayOfNulls<String>(MAX_SLOTS)
                infix.template.slots.forEachIndexed { position, slot ->
                    values[slot] = translate(match.groupValues[position + 1], MAX_DEPTH, budget)
                }
                assemble(infix.template, values)
            }
        }
        return if (changed) current else null
    }

    private fun assemble(template: Template, values: Array<String?>): String = buildString {
        template.outChunks.forEachIndexed { index, chunk ->
            append(chunk)
            if (index < template.outSlots.size) append(values[template.outSlots[index]].orEmpty())
        }
    }

    /** Из двух частичных переводов — тот, где русских букв меньше; при равенстве — первый. */
    private fun lessRussian(first: String?, second: String): String {
        if (first == null) return second
        return if (cyrillicCount(first) <= cyrillicCount(second)) first else second
    }

    /**
     * Замена кусков с полем по краю внутри текста, от самых длинных. Последний шаг:
     * он не знает структуры фразы и нужен только там, где её собрал `append`.
     */
    private fun replaceFragments(text: String): String? {
        if (fragmentsByFirstChar.isEmpty()) return null
        val out = StringBuilder(text.length + 16)
        var index = 0
        var changed = false
        while (index < text.length) {
            val match = fragmentsByFirstChar[text[index]]?.firstOrNull { text.startsWith(it.source, index) }
            if (match != null) {
                out.append(match.translation)
                index += match.source.length
                changed = true
            } else {
                out.append(text[index])
                index++
            }
        }
        return if (changed) out.toString() else null
    }

    private fun matchTemplate(text: String, depth: Int, budget: Budget): String? {
        if (depth > MAX_DEPTH) return null
        for (template in templates) {
            if (!template.mightMatch(text)) continue
            val match = template.regex.matchEntire(text) ?: continue
            val values = arrayOfNulls<String>(MAX_SLOTS)
            template.slots.forEachIndexed { position, slot ->
                val raw = match.groupValues[position + 1]
                values[slot] = translate(raw, depth + 1, budget)
            }
            return assemble(template, values)
        }
        return null
    }

    /**
     * Режет по разделителю и переводит части. `null` — ни одна часть не перевелась,
     * значит и резать было незачем.
     *
     * Строки переводятся каждая сама по себе. Для остальных разделителей части ещё и
     * склеиваются обратно, если так перевод выходит полнее: в «Aurora mint — Тёмный
     * индиго и холодная мята — развитие прежнего вида» тире стоит и между именем темы
     * и описанием, и внутри самого описания, и резать по каждому значит потерять
     * описание целиком. Выбирается разбиение с наименьшим числом русских букв, при
     * равенстве — с более длинными кусками.
     */
    private fun splitAndTranslate(text: String, separator: String, depth: Int, budget: Budget): String? {
        if (!text.contains(separator)) return null
        val parts = text.split(separator)
        if (parts.size < 2) return null
        if (separator == "\n" || parts.size > MAX_MERGED_PARTS) {
            var changed = false
            val translated = parts.map { part ->
                val out = translate(part, depth + 1, budget)
                if (out !== part) changed = true
                out
            }
            return if (changed) translated.joinToString(separator) else null
        }
        val count = parts.size
        val cost = IntArray(count + 1) { Int.MAX_VALUE }
        val output = arrayOfNulls<String>(count + 1)
        val changed = BooleanArray(count + 1)
        cost[0] = 0
        output[0] = ""
        for (end in 1..count) {
            for (start in 0 until end) {
                if (cost[start] == Int.MAX_VALUE) continue
                // Весь текст целиком уже пробовал вызывающий.
                if (start == 0 && end == count) continue
                val segment = parts.subList(start, end).joinToString(separator)
                val translated = translate(segment, depth + 1, budget)
                val total = cost[start] + cyrillicCount(translated)
                if (total < cost[end]) {
                    cost[end] = total
                    output[end] = if (start == 0) translated else output[start] + separator + translated
                    changed[end] = changed[start] || translated !== segment
                }
            }
        }
        return if (changed[count]) output[count] else null
    }

    companion object {
        /** Глубина вложенных подстановок и разрезаний: дальше текст не разбирается. */
        private const val MAX_DEPTH = 3

        /** Больше частей не склеивается перебором: он растёт квадратично. */
        private const val MAX_MERGED_PARTS = 8

        /**
         * Разборов на один текст. Обычная фраза укладывается в единицы; сотни уходят
         * только на длинный абзац, собранный из многих кусков.
         */
        private const val WORK_BUDGET = 400

        /**
         * Длиннее этого текст не переводится вовсе. Интерфейсных фраз такой длины нет,
         * а шаблон с несколькими местами на многостраничном тексте — это перебор
         * с возвратами на каждом кадре.
         */
        private const val MAX_TEXT_LENGTH = 12000

        private const val MAX_SLOTS = 16

        /** Одиночный знак в конце, который код ставит после готовой фразы. */
        private const val TRAILING_PUNCTUATION = ".:;!?"

        /**
         * Разделители, которыми экран склеивает готовые фразы: строки, « · » уведомления,
         * два пробела между скоростями, предложения. Порядок важен: сначала широкие,
         * иначе « · » разрежет «  ·  » и оставит пробелы висеть на частях.
         */
        private val SEPARATORS = listOf("\n", "  ·  ", " · ", " • ", " | ", "  ", " — ", "; ", ": ", ". ", ", ")

        private val PLACEHOLDER = Regex("""\{(\d+)\}""")

        private fun isCyrillic(c: Char): Boolean = c.code in 0x0400..0x04FF

        private fun cyrillicCount(text: String): Int = text.count(::isCyrillic)

        fun hasCyrillic(text: CharSequence): Boolean {
            for (index in 0 until text.length) {
                if (isCyrillic(text[index])) return true
            }
            return false
        }

        /**
         * Собирает каталог из пар «исходник → перевод».
         *
         * Пустой перевод и перевод, у которого места подстановки не совпадают с
         * исходником, пропускаются: такой перевод на экране потерял бы число или имя
         * узла, и лучше показать русский текст, чем неверный.
         */
        fun fromEntries(entries: Map<String, String>, targetLocale: Locale): NovaTranslationCatalog {
            val exact = HashMap<String, String>(entries.size * 3)
            val templates = ArrayList<Template>()
            val templateSources = HashSet<String>()
            fun add(source: String, translation: String, primary: Boolean) {
                if (source.isEmpty() || translation.isEmpty()) return
                if (!PLACEHOLDER.containsMatchIn(source)) {
                    if (primary || !exact.containsKey(source)) exact[source] = translation
                    return
                }
                if (!templateSources.add(source)) return
                parseTemplate(source, translation)?.let { templates += it }
            }
            for ((source, translation) in entries) add(source, translation, primary = true)
            // Производные записи — после всех исходных, чтобы ни одна не перебила
            // настоящую фразу каталога.
            for ((source, translation) in entries) {
                for ((variantSource, variantTranslation) in variants(source, translation)) {
                    add(variantSource, variantTranslation, primary = false)
                }
            }
            val upper = HashMap<String, String>()
            for ((source, translation) in exact) {
                val key = source.uppercase(RUSSIAN)
                if (key != source && !exact.containsKey(key)) {
                    upper[key] = translation.uppercase(targetLocale)
                }
            }
            // Куски с полем по краю и хотя бы двумя русскими буквами: одна буква
            // («с», «в») встречается внутри любой фразы и переводилась бы где попало.
            val fragments = exact.entries
                .filter { (source, _) ->
                    (source.first().isWhitespace() || source.last().isWhitespace()) &&
                        cyrillicCount(source) >= 2
                }
                .map { (source, translation) -> Fragment(source, translation) }
                .sortedByDescending { it.source.length }
            val fragmentsByFirstChar = HashMap<Char, Array<Fragment>>()
            fragments.groupBy { it.source.first() }.forEach { (first, group) ->
                fragmentsByFirstChar[first] = group.toTypedArray()
            }
            // Конкретный шаблон раньше общего: «Пинг: {0} мс» прежде «{0} мс».
            templates.sortWith(
                compareByDescending<Template> { it.literalLength }.thenBy { it.slots.size }
            )
            val infixes = templates
                .filter { template ->
                    val head = template.chunks.first()
                    head.isNotEmpty() && head.first().isWhitespace() && cyrillicCount(head) >= 2
                }
                .map { template -> Infix(template, infixRegex(template)) }
            return NovaTranslationCatalog(
                exact,
                upper,
                templates.toTypedArray(),
                fragmentsByFirstChar,
                infixes.toTypedArray(),
            )
        }

        private val RUSSIAN = Locale("ru")

        /**
         * Производные записи одной фразы: её строки по отдельности, она же без полей
         * по краям и без точки в конце.
         *
         * Код собирает абзацы из фраз каталога (`append` в `buildString`) и режет их
         * иначе, чем они лежат в каталоге: «…не влияет.» с переводом строки на конце
         * приходит строкой без него, «…ничего. » — предложением без точки после
         * разрезания по «. ». Строка перевода соответствует строке исходника: число
         * переводов строк у них одинаковое, это проверяют `merge_catalog.py` и тест
         * каталога.
         *
         * Слишком короткие производные (меньше трёх русских букв) не добавляются:
         * «с {0}» совпало бы с любым текстом, начинающимся на «с».
         */
        private fun variants(source: String, translation: String): List<Pair<String, String>> {
            val base = ArrayList<Pair<String, String>>()
            base += source to translation
            if (source.contains('\n')) {
                val sourceLines = source.split('\n')
                val translationLines = translation.split('\n')
                if (sourceLines.size == translationLines.size) {
                    sourceLines.indices.forEach { index -> base += sourceLines[index] to translationLines[index] }
                }
            }
            val out = ArrayList<Pair<String, String>>()
            for ((src, tr) in base) {
                val trimmedSource = src.trim()
                val trimmedTranslation = tr.trim()
                if (trimmedSource.isEmpty() || trimmedTranslation.isEmpty()) continue
                out += trimmedSource to trimmedTranslation
                if (trimmedSource.endsWith('.') && !trimmedSource.endsWith("..") &&
                    trimmedTranslation.endsWith('.') && !trimmedTranslation.endsWith("..")
                ) {
                    out += trimmedSource.dropLast(1) to trimmedTranslation.dropLast(1)
                }
                if (src != source) out += src to tr
            }
            return out.filter { (src, _) -> src != source && cyrillicCount(src) >= 3 }
        }

        private fun infixRegex(template: Template): Regex {
            val chunks = template.chunks
            val slots = template.slots
            return Regex(
                buildString {
                    chunks.forEachIndexed { index, chunk ->
                        if (chunk.isNotEmpty()) append(Regex.escape(chunk))
                        if (index < slots.size) {
                            val trailing = index == slots.size - 1 && chunks.last().isEmpty()
                            append(if (trailing) "([^\\s,;·•)]+)" else "(.+?)")
                        }
                    }
                },
            )
        }

        private fun parseTemplate(source: String, translation: String): Template? {
            val (chunks, slots) = split(source)
            val (outChunks, outSlots) = split(translation)
            if (slots.isEmpty() || slots.any { it >= MAX_SLOTS }) return null
            if (slots.toSet().size != slots.size) return null
            if (slots.sorted() != outSlots.sorted()) return null
            return Template(
                chunks = chunks.toTypedArray(),
                slots = slots.toIntArray(),
                outChunks = outChunks.toTypedArray(),
                outSlots = outSlots.toIntArray(),
            )
        }

        private fun split(text: String): Pair<List<String>, List<Int>> {
            val chunks = ArrayList<String>()
            val slots = ArrayList<Int>()
            var last = 0
            for (match in PLACEHOLDER.findAll(text)) {
                chunks += text.substring(last, match.range.first)
                slots += match.groupValues[1].toInt()
                last = match.range.last + 1
            }
            chunks += text.substring(last)
            return chunks to slots
        }
    }
}
