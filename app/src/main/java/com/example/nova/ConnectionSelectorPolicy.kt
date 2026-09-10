package com.example.nova

import java.util.Locale

/**
 * Одно описание селектора протокола/региона на всё приложение.
 *
 * ## Зачем отдельный объект
 *
 * Порядок кнопок был выписан **четырьмя** копиями подряд — в `protocolButtons`
 * из `onCreate`, в списке внутри `onResume`, в `refreshConnectionSelector` и
 * ещё раз позиционной картой индексов в `restoreRegionSelection`. Пятая копия на
 * главном экране — это ровно тот способ, которым случается G49: один список
 * узнаёт о новом значении, остальные молчат.
 *
 * Поэтому здесь лежит **всё**, что о селекторе надо знать обоим экранам: порядок,
 * подписи, разбиение на строки, соответствие «значение ↔ позиция», правила
 * доступности и правило выбранной кнопки. Объект чистый: ни `Context`, ни `View`,
 * ни ввода-вывода — решения обязаны проверяться тестом, а не наблюдением на
 * устройстве.
 *
 * ## Кнопка и сохранённое значение — разные словари
 *
 * Кнопок шесть, а сохранённых значений региона больше: `eu` и `us` — это **один**
 * транспорт (встроенная Opera), и владелец попросил называть его «OPERA», а
 * выбор между EU и US вынести отдельной строкой под селектором. Поэтому здесь
 * два словаря: [ORDER] — значения **кнопок**, [storedValueForChip] переводит
 * нажатую кнопку в то, что ляжет в `exit_region_state`, а [indexOf] переводит
 * обратно. Внутренний словарь службы (`eu`/`us`) не менялся вовсе — так смена
 * подписи не тронула семнадцать мест, которые эти два значения разбирают.
 */
object ConnectionSelectorPolicy {

    /**
     * Значения кнопок в порядке кнопок.
     *
     * `masque` стоит третьим намеренно — так он и лежит в `protocolButtons`.
     * `opera` заменил пару `eu`/`us`: это одна кнопка на один транспорт.
     * `tor` дописан **в конец**: `SettingsActivity.configureRegionSelector`
     * разбирает кнопки позиционно (`getOrNull(0..5)`), и вставка в середину
     * молча переименовала бы половину селектора.
     */
    val ORDER: List<String> = listOf("auto", "ru", "masque", "opera", "proton", "tor")

    /** Подписи в том же порядке. `ru` — это WARP, и это не опечатка. */
    val LABELS: List<String> = listOf("AUTO", "WARP", "MASQUE", "OPERA", "PROTON", "TOR")

    /**
     * Разбиение на строки: сколько кнопок в каждой.
     *
     * Три смысловые полосы вместо одной прокручиваемой ленты — требование
     * владельца, и оно же читается лучше: скрытая за краем кнопка выглядит как
     * отсутствующая.
     *
     * * строка 1 — всё, что работает через Cloudflare (`AUTO` начинает именно с
     *   них, поэтому стоит здесь);
     * * строка 2 — зарубежные VPN (встроенная Opera и Proton);
     * * строка 3 — Tor.
     *
     * Сумма обязана равняться [SIZE]; за этим следит тест.
     */
    val ROWS: List<Int> = listOf(3, 2, 1)

    /** Сколько кнопок обязано быть в группе. Меньше — селектор не настраивается. */
    val SIZE: Int get() = ORDER.size

    /** Значение кнопки «встроенная Opera». */
    const val CHIP_OPERA: String = "opera"

    /** Значение кнопки Tor. */
    const val CHIP_TOR: String = "tor"

    /**
     * Мост WebTunnel: соединение выглядит обычным HTTPS к настоящему сайту с
     * настоящим сертификатом, а секрет — путь внутри TLS.
     */
    const val TOR_ENTRY_WEBTUNNEL: String = "webtunnel"

    /** Мост obfs4 — «похоже ни на что»: поток равномерно случайный с первого байта. */
    const val TOR_ENTRY_OBFS4: String = "obfs4"

    /** Обычный (vanilla) мост: сам протокол Tor, но адрес не в публичных списках. */
    const val TOR_ENTRY_VANILLA: String = "vanilla"

    /** Вход напрямую в сеть Tor, без мостов вовсе. */
    const val TOR_ENTRY_DIRECT: String = "direct"

    /**
     * Способы входа в сеть Tor: `значение` → подпись на кнопке.
     *
     * **Порядок — по убыванию вероятности пройти из России**, и он же порядок
     * кнопок. Обоснование по замерам, а не по вкусу:
     *
     *  * `webtunnel` — снаружи это HTTPS к живому сайту, сопровождаемому обычным
     *    сертификатом; блокировать его нечем, кроме блокировки самого домена.
     *    Именно он, по разбору `kb/dns-tunnel-and-tor.md`, вытащил Telegram и
     *    Gemini в Петербурге, а сборщик OnionHop отдаёт живые строки (57,5 % по
     *    апгрейду до WebSocket, замер из РФ).
     *  * `obfs4` — «похоже ни на что», и это же его слабое место: равномерная
     *    случайность с первого байта сама по себе признак. У нас 14 из 14
     *    настоящих рукопожатий с мостами из РФ (S49), то есть работает, но
     *    считается детектируемым.
     *  * `vanilla` — обфускации нет вовсе, спасает только то, что адрес моста не
     *    в публичных списках; DPI, узнающий рукопожатие Tor, его видит.
     *  * `direct` — публичные входные узлы, их адреса известны всем.
     *
     * Названия — общепринятые имена самих транспортов Tor, а не наши выдумки:
     * так они называются в torrc, в Tor Browser и в списках мостов.
     *
     * Почему выбор вообще возможен. `webtunnel` и `obfs4` ходят через наш SOCKS
     * в ядре Go, и его сокеты помечаются `protect()`. У `vanilla` и прямого
     * входа соединение открывает сам tor своим сокетом — и это безопасно ровно
     * потому, что собственный пакет Nova исключён из своего же VPN во **всех**
     * трёх режимах раздельного туннелирования (`applyOperaSplitTunnelPolicy`).
     */
    val TOR_ENTRY_MODES: List<Pair<String, String>> = listOf(
        TOR_ENTRY_WEBTUNNEL to "WEBTUNNEL",
        TOR_ENTRY_OBFS4 to "OBFS4",
        TOR_ENTRY_VANILLA to "VANILLA",
        TOR_ENTRY_DIRECT to "БЕЗ МОСТОВ",
    )

    /**
     * Нужен ли способу входа наш локальный SOCKS с транспортом в ядре.
     *
     * У `vanilla` и прямого входа tor соединяется сам, и поднимать прокси
     * незачем — а лишний слушатель на петле это лишняя поверхность.
     */
    fun torEntryUsesPluggableTransport(mode: String): Boolean =
        normalizeTorEntry(mode).let { it == TOR_ENTRY_WEBTUNNEL || it == TOR_ENTRY_OBFS4 }

    /** Какие мосты нужны этому способу входа; пусто — мосты не нужны вовсе. */
    fun torBridgeTransportFor(mode: String): String = when (normalizeTorEntry(mode)) {
        TOR_ENTRY_WEBTUNNEL -> "webtunnel"
        TOR_ENTRY_OBFS4 -> "obfs4"
        TOR_ENTRY_VANILLA -> "vanilla"
        else -> ""
    }

    /**
     * Что подставить, если способ входа ещё не выбирали.
     *
     * Первый в списке, то есть самый вероятный для России. Проверено на Pixel 4a
     * 2026-09-10: `webtunnel` доходит до 100 % за четыре секунды и все пять
     * мостов приняты без единого отказа. Ошибиться здесь дёшево — способ меняется
     * одним нажатием.
     */
    const val DEFAULT_TOR_ENTRY: String = TOR_ENTRY_WEBTUNNEL

    /** Приводит способ входа к известному; всё незнакомое — к [DEFAULT_TOR_ENTRY]. */
    fun normalizeTorEntry(value: String?): String {
        val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
        return if (TOR_ENTRY_MODES.any { it.first == normalized }) normalized else DEFAULT_TOR_ENTRY
    }

    /**
     * Подрегионы Opera: `значение региона` → подпись.
     *
     * Список зашит в трёх местах службы (`normalizeOperaCountry`,
     * `OperaEndpointFeed.KNOWN_REGIONS`, `normalizeOperaRegionCode`) и никогда не
     * приходит из ленты: лента публикует **адреса** по регионам, а не сами
     * регионы. Здесь он ровно для подписи, значения те же `eu`/`us`.
     */
    val OPERA_SUB_REGIONS: List<Pair<String, String>> = listOf("eu" to "EU", "us" to "US")

    /** Что подставить, если пользователь ещё не выбирал регион Opera. */
    const val DEFAULT_OPERA_SUB_REGION: String = "eu"

    /** Приставка строки подрегионов под селектором. Точный ключ поиска в логах. */
    const val SUB_REGION_PREFIX: String = "Регион: "

    /** Приставка той же строки для Tor: там выбирают не регион, а способ входа. */
    const val TOR_ENTRY_PREFIX: String = "Вход: "

    /** Подпись строки подвыбора: у Tor она про способ входа, у остальных — про регион. */
    fun subRegionPrefixFor(chipValue: String): String =
        if (chipValue == CHIP_TOR) TOR_ENTRY_PREFIX else SUB_REGION_PREFIX

    /**
     * Строка под селектором при выборе Tor.
     *
     * Текст обязан описывать то, что произошло на самом деле, а не то, что
     * задумано. Прежняя версия обещала сразу две неправды: «выбор сохранён» —
     * хотя оба экрана тогда намеренно **не** записывали `tor` регионом, — и
     * «мосты уже загружаются» даже тогда, когда сбор не начинался, потому что
     * список младше суток или прогон уже идёт. С 1.32.2 выбор **сохраняется**:
     * транспорт есть. Осталось сказать о его цене, и сказать до подключения, а
     * не после — «интернет стал медленным» иначе читается как поломка.
     *
     * @param bridgesAreLoading начался ли сбор именно сейчас.
     * @param knownBridges сколько мостов уже лежит на диске.
     */
    fun torNoticeFor(bridgesAreLoading: Boolean, knownBridges: Int): String {
        val head = when {
            bridgesAreLoading -> "TOR: собираем мосты, без них подключения не будет"
            knownBridges > 0 -> "TOR выбран, мостов в списке $knownBridges"
            else -> "TOR выбран, мосты соберутся при подключении"
        }
        return "$head. Через Tor идёт только TCP: QUIC и UDP не пойдут, скорость " +
            "заметно ниже обычной, а первое подключение занимает до минуты."
    }

    /**
     * Позиция кнопки для сохранённого значения региона.
     *
     * `eu` и `us` ведут на одну кнопку OPERA. Незнакомое значение — «Авто».
     */
    fun indexOf(region: String?): Int {
        val normalized = RegionTransportPolicy.normalizeKnown(region)
        if (normalized == "eu" || normalized == "us") return ORDER.indexOf(CHIP_OPERA)
        val index = ORDER.indexOf(normalized)
        // `vless` знает `RegionTransportPolicy`, но кнопки у него нет: он
        // выбирается как семейство импортированных протоколов, а не как регион.
        // «Авто» здесь честнее, чем упасть или показать чужую нажатой.
        return if (index >= 0) index else 0
    }

    /** Значение кнопки по позиции. За пределами списка — «Авто». */
    fun valueAt(index: Int): String = ORDER.getOrNull(index) ?: "auto"

    /**
     * Что записать в предпочтение, когда нажата кнопка [chipValue].
     *
     * Для OPERA это запомненный подрегион, а не сама строка `opera`: словарь
     * службы её не знает, и `normalizeKnown` превратил бы её в «Авто», а «Авто»
     * разрешает подмену транспорта (I1). Для остальных кнопок значение своё.
     */
    fun storedValueForChip(chipValue: String, rememberedOperaSubRegion: String?): String {
        if (chipValue != CHIP_OPERA) return chipValue
        return normalizeOperaSubRegion(rememberedOperaSubRegion)
    }

    /** Приводит подрегион Opera к `eu`/`us`; всё прочее — к [DEFAULT_OPERA_SUB_REGION]. */
    fun normalizeOperaSubRegion(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return if (OPERA_SUB_REGIONS.any { it.first == normalized }) normalized else DEFAULT_OPERA_SUB_REGION
    }

    /**
     * Строка подрегионов под селектором для выбранной кнопки.
     *
     * @param chipValue значение выбранной кнопки.
     * @param protonCountries страны, которые реально есть в выпущенных профилях
     *        Proton. Пустой список — писать нечего: на свежей установке профилей
     *        ещё нет, а показать десять стран, из которых доступна ни одна, —
     *        это обещание, которого приложение не выполнит.
     * @return пары `значение` → `подпись`, пусто — строку показывать не надо.
     */
    fun subRegionsFor(chipValue: String, protonCountries: Collection<String>): List<Pair<String, String>> =
        when (chipValue) {
            CHIP_OPERA -> OPERA_SUB_REGIONS
            CHIP_TOR -> TOR_ENTRY_MODES
            "proton" -> {
                val countries = CountryDisplayOrder.order(protonCountries)
                if (countries.isEmpty()) {
                    emptyList()
                } else {
                    // «AUTO» — это отдельная кнопка, а не пустое место.
                    //
                    // Пустое предпочтение означает «страна не важна», и без своей
                    // кнопки оно рисовалось отметкой на первой стране списка. Тогда
                    // нажатие на эту самую страну не давало ничего: `RadioGroup`
                    // молчит о нажатии на уже отмеченную кнопку, предпочтение
                    // оставалось пустым, и экран показывал NL, пока служба шла на
                    // самый быстрый узел где угодно. Выбрать эту страну можно было
                    // только через другую и обратно.
                    listOf(ANY_COUNTRY to "AUTO") + countries.map { it to it }
                }
            }
            else -> emptyList()
        }

    /** Значение подрегиона «страна не важна». */
    const val ANY_COUNTRY: String = ""

    /**
     * Какая кнопка обязана быть нажата.
     *
     * @param storedRegion сохранённое предпочтение.
     * @param protonPreparationRequested идёт ли выпуск Proton.
     *
     * Идущая подготовка Proton **сильнее** сохранённого значения. Предпочтение
     * `proton` записывается только по успеху, а перерисовок до него случается
     * много: служба шлёт состояние через долю секунды после старта, приёмник
     * перенастраивает селектор, и кнопка отскакивала бы на прежний транспорт
     * посреди выпуска. Тот же случай — отказ выпуска: без явной проверки
     * переключатель уезжал бы в «Авто», то есть отказ молча менял бы транспорт (I1).
     */
    fun selectedIndex(storedRegion: String?, protonPreparationRequested: Boolean): Int =
        if (protonPreparationRequested) indexOf("proton") else indexOf(storedRegion)

    /** Что можно нажать при этих условиях. */
    data class Availability(
        val enabled: List<Boolean>,
        /**
         * Сохранённое значение стало недоступным и должно быть переписано на
         * «Авто». Пусто — переписывать нечего.
         */
        val rewriteStoredTo: String?,
        /** Причина запрета для строки под селектором. Пусто — запрета нет. */
        val lockReason: String,
    )

    const val REGISTRATION_LOCK_REASON: String =
        "Идёт регистрация устройства — выбор протокола станет доступен, когда она закончится."

    const val OPERA_UNSUPPORTED_TOAST: String =
        "OPERA недоступна на этом устройстве: встроенный Opera runtime не поддерживается."

    /**
     * @param operaSupported поддерживает ли устройство встроенный Opera runtime.
     *        Без него OPERA недостижима в принципе.
     * @param deviceRegistrationInProgress выдаётся ли прямо сейчас ключ MASQUE.
     *        Смена протокола в этот момент роняет тот самый туннель, изнутри
     *        которого ключ и выдают: регистрация начинается заново, а снаружи это
     *        выглядит как «MASQUE не включается».
     * @param storedRegion сохранённое предпочтение — нужно, чтобы понять, не стало
     *        ли оно недостижимым.
     */
    fun availability(
        operaSupported: Boolean,
        deviceRegistrationInProgress: Boolean,
        storedRegion: String?,
    ): Availability {
        if (deviceRegistrationInProgress) {
            return Availability(
                enabled = List(SIZE) { false },
                rewriteStoredTo = null,
                lockReason = REGISTRATION_LOCK_REASON,
            )
        }
        if (operaSupported) {
            return Availability(List(SIZE) { true }, null, "")
        }
        val normalized = RegionTransportPolicy.normalizeKnown(storedRegion)
        return Availability(
            enabled = ORDER.map { it != CHIP_OPERA },
            // Переписываем, только если пользователь стоял именно на недоступном:
            // иначе безобидный заход на экран менял бы чужой выбор (I1).
            rewriteStoredTo = if (normalized == "eu" || normalized == "us") "auto" else null,
            lockReason = "",
        )
    }

    /** Прозрачность выключенной кнопки — общая для обоих экранов. */
    const val DISABLED_ALPHA: Float = 0.45f

    /**
     * Идентификаторы кнопок в том же порядке — чтобы список строился в одном месте.
     *
     * Идентификаторы намеренно **одинаковые** в обеих разметках: `findViewById`
     * ищет в дереве своего экрана, так что совпадение имён никого не путает, зато
     * позволяет обоим экранам собирать группу одним и тем же кодом. Ровно этого
     * не хватало, когда список был выписан четырьмя копиями.
     */
    val BUTTON_IDS: List<Int> = listOf(
        R.id.rb_exit_auto,
        R.id.rb_exit_ru,
        R.id.rb_exit_masque,
        R.id.rb_exit_opera,
        R.id.rb_exit_proton,
        R.id.rb_exit_tor,
    )
}
