package com.example.nova

import java.util.Locale

/**
 * Что приложению позволено делать с выбранным выходом, когда он не поднимается.
 *
 * Дефект, ради которого это появилось: при выбранном EU неудачный цикл Opera
 * поднимал WARP «ненадолго, ради discovery Opera endpoints» — и оставался на нём.
 * Снаружи это выглядело как молчаливая подмена: пользователь просил EU, а через
 * минуту работал WARP RU. Тот же цикл показывал «1/50» — длину списка встроенных
 * WARP-профилей, к Opera отношения не имеющую, — и лишь потом «1/54» от
 * собственного плана запуска Opera.
 *
 * Правило то же, что у явного выбора MASQUE и импортированных профилей
 * ([MasqueStartPolicy]): **подменять выбранное приложение вправе только в
 * «Авто»**. Там выбор делает само приложение, и цепочка WARP → Opera и есть
 * его выбор. Везде ещё выбор сделал пользователь, и честных ответов на неудачу
 * ровно два — повторить выбранное или сказать, что не вышло.
 */
object RegionTransportPolicy {

    const val AUTO = "auto"

    /**
     * Выход назван пользователем: EU, US, RU, MASQUE или VLESS.
     *
     * Незнакомое значение считается явным выбором намеренно: ошибиться в сторону
     * «уважаем выбор» безопаснее, чем в сторону «подменяем молча».
     */
    fun isExplicitChoice(regionPreference: String?): Boolean = normalize(regionPreference) != AUTO

    /**
     * Можно ли ради выбранного выхода поднять другой протокол — хотя бы временно
     * и хотя бы «только для discovery».
     */
    fun allowsTransportSubstitution(regionPreference: String?): Boolean =
        !isExplicitChoice(regionPreference)

    /**
     * Позволено ли в этом цикле уходить на встроенный транспорт Opera.
     *
     * Opera законна ровно в двух случаях: выбор делает само приложение («Авто»)
     * либо Opera и есть выбранный выход (EU, US). Любой другой названный выход —
     * WARP, MASQUE, Proton — подменять ею нельзя.
     *
     * Отдельная функция, а не перечисление запрещённых значений на месте: там
     * список был неполон. Запрещён был только `ru`, поэтому выбранный MASQUE после
     * неудачи уезжал на голландский выход Opera — та самая молчаливая подмена,
     * ради которой писалось всё остальное в этом файле.
     */
    fun allowsOperaTransport(regionPreference: String?): Boolean {
        val normalized = normalize(regionPreference)
        if (normalized == "eu" || normalized == "us") return true
        return !isExplicitChoice(normalized)
    }

    /**
     * Позволено ли в этом цикле поднимать MASQUE.
     *
     * MASQUE живёт внутри бэкенда `WARP`, поэтому «выбран WARP» не мешало ему
     * запуститься: на устройстве при выбранном WARP цикл начинался с
     * «Пробуем MASQUE / HTTP3» и поднимал MASQUE-ZT, а бейдж показывал «MASQUE: RU».
     * Отказ для явно выбранного Proton в этой ветке уже стоял (N23) — здесь то же
     * правило, но для любого названного транспорта.
     *
     * Обратная сторона осознанная: на сети, где работает только MASQUE, выбранный
     * WARP теперь не подключится вовсе. Это и есть «повторить выбранное или сказать,
     * что не вышло»; молча подменить — не вариант.
     */
    fun allowsMasqueTransport(regionPreference: String?): Boolean {
        val normalized = normalize(regionPreference)
        if (normalized == "masque") return true
        return !isExplicitChoice(normalized)
    }

    /**
     * Годится ли запомненная удачная стратегия как начало нового цикла.
     *
     * Дефект, ради которого появилось: «последняя стабильная WARP-стратегия» помнит и
     * MASQUE — он живёт внутри бэкенда `WARP` и в памяти неотличим от него. При явно
     * выбранном WARP цикл стартовал прямо с неё:
     * «Сначала пробуем последнюю стабильную WARP-стратегию: MASQUE-ZT@…:8095», —
     * и поднимал MASQUE, ни разу не спросив [MasqueStartPolicy]. Пользователь просил
     * WARP, а получал MASQUE, и бейдж честно показывал «MASQUE: RU».
     *
     * Правило сужено намеренно. Проверяется только пара WARP↔MASQUE, потому что
     * именно она измерена; у EU/US, Proton и импортированных профилей свои заслоны
     * (`shouldAllowOperaTransport`, `ownProfileSourceChosen`), и трогать их вслепую
     * значило бы менять поведение там, где никто не смотрел.
     */
    fun allowsRememberedStrategy(regionPreference: String?, rememberedMode: String?): Boolean {
        if (!isExplicitChoice(regionPreference)) return true
        val masqueRemembered = rememberedMode?.contains("masque", ignoreCase = true) == true
        return when (normalize(regionPreference)) {
            "masque" -> masqueRemembered
            "ru" -> !masqueRemembered
            else -> true
        }
    }

    // `countsWarpProfileList` удалён: экран больше не угадывает знаменатель.
    //
    // Он гасил заглушку «длина списка встроенных профилей» там, где перебор ведёт не
    // WARP (EU/US, MASQUE, VLESS). Заглушки больше нет вовсе — длину очереди называет
    // та фаза, которая её и перебирает, а до первого снимка службы экран показывает
    // «...». Возвращать функцию не нужно: вернётся заодно и причина скачков.

    /**
     * Все значения, которые приложение умеет выбирать выходом.
     *
     * Единственный список — и это главное свойство. Раньше он был записан дважды:
     * в `ClientData.normalizeRegionPreference` полностью, а в одноимённой закрытой
     * функции `NovaVpnService` — без `masque` и `vless`. Из-за этого каждое из
     * семнадцати мест службы, приводивших регион к известному значению, молча
     * превращало явно выбранный MASQUE или VLESS в «Авто», а «Авто» разрешает
     * подмену транспорта — то есть выбранный MASQUE после неудачи уезжал на
     * голландский выход Opera. Ровно тот дефект, ради которого написан этот файл
     * (см. [allowsOperaTransport]), только пришедший с другой стороны.
     */
    val KNOWN_REGIONS: Set<String> = setOf("auto", "ru", "eu", "us", "masque", "vless", "proton", "tor")

    /**
     * Приводит сохранённое значение к известному, а незнакомое — к «Авто».
     *
     * Отличается от [normalize] намеренно и в обе стороны. Здесь незнакомое
     * значение — это настройка от чужой или будущей версии, и «Авто» на неё
     * честный ответ. В [normalize] незнакомое значение считается **явным
     * выбором**, потому что там цена ошибки обратная: ошибиться в сторону
     * «уважаем выбор» безопаснее, чем подменить молча.
     */
    fun normalizeKnown(value: String?): String {
        val normalized = normalize(value)
        return if (normalized in KNOWN_REGIONS) normalized else AUTO
    }

    private fun normalize(value: String?): String =
        value?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: AUTO
}
