package com.example.nova

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран настройки обхода VPN по доменам и зонам.
 *
 * Вынесен в отдельный Activity, а не в общие настройки, потому что у него
 * много интерактивных элементов (несколько чекбоксов + мультистрочное поле),
 * которые требуют собственного жизненного цикла и мешали бы общей логике.
 */
class DomainBypassActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData

    private lateinit var swBypass: Switch
    private lateinit var cbZoneRu: CheckBox
    private lateinit var cbZoneSu: CheckBox
    private lateinit var cbZoneCyrillic: CheckBox
    private lateinit var etCustom: EditText
    private lateinit var tvSummary: TextView

    /**
     * Флаг подавления рекурсии: при программной установке состояния чекбоксов
     * срабатывают их слушатели и снова вызывают сохранение, что не страшно, но
     * приводит к лишним записям в SharedPreferences при инициализации.
     */
    private var suppressListeners = false

    private val reapplyHandler = Handler(Looper.getMainLooper())

    /** Стоит ли в очереди отложенное применение — нужно, чтобы не потерять его при уходе с экрана. */
    private var reapplyPending = false

    private val reapplyRunnable = Runnable {
        reapplyPending = false
        if (isFinishing || isDestroyed) return@Runnable
        runReapply()
    }

    /**
     * Тик обновления сводки.
     *
     * Счётчики растут в процессе `:vpn` по ходу сеанса, а экран узнаёт о них
     * только из файла — значит, надо перечитывать. Живёт строго между `onResume` и
     * `onPause`: будить процесс ради невидимого экрана незачем.
     */
    private val statsHandler = Handler(Looper.getMainLooper())

    /**
     * Чтение состояния ядра идёт здесь, а не на главном потоке.
     *
     * За одну строку сводки платят три дорогих обращения: `AtomicFile` со
     * счётчиками, разбор JSON и `getRunningServices` через binder. Раз в две
     * секунды на главном потоке это заметная задержка ввода прямо в поле, куда
     * человек печатает домен (I13).
     */
    private val statsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaDomainBypassStats").apply { isDaemon = true }
    }

    /**
     * Последняя прочитанная строка о состоянии ядра.
     *
     * `null` до первого чтения — это «ещё не знаем», и сводка в этот момент
     * показывает только правила. Пустая строка означает «сказать нечего».
     */
    @Volatile
    private var coreStatusCache: String? = null

    private val statsRunnable = object : Runnable {
        override fun run() {
            refreshCoreStatusAsync()
            statsHandler.postDelayed(this, STATS_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Контекст логгера нужно передавать до любого обращения к LogManager
        LogManager.setAppContext(this)
        setContentView(R.layout.activity_domain_bypass)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)

        swBypass = findViewById(R.id.sw_domain_bypass)
        cbZoneRu = findViewById(R.id.cb_zone_ru)
        cbZoneSu = findViewById(R.id.cb_zone_su)
        cbZoneCyrillic = findViewById(R.id.cb_zone_cyrillic)
        etCustom = findViewById(R.id.et_domain_bypass_custom)
        tvSummary = findViewById(R.id.tv_domain_bypass_summary)

        initViews()
        attachListeners()
    }

    /**
     * Устанавливает начальное состояние всех элементов из сохранённых настроек.
     *
     * Зоны при первом запуске (пустая строка) отображают ru, su и cyrillic
     * как выбранные — это наиболее ожидаемый набор для российского пользователя.
     */
    private fun initViews() {
        suppressListeners = true

        val bypassEnabled = clientData.isDomainBypassEnabled()
        swBypass.isChecked = bypassEnabled

        val zonesRaw = clientData.getDomainBypassZonesRaw()
        val isFirstRun = zonesRaw.isEmpty()

        if (isFirstRun) {
            // Показываем дефолтный набор зон без записи — запись произойдёт
            // при первом изменении любого чекбокса, чтобы не трогать хранилище
            // без действия пользователя
            cbZoneRu.isChecked = true
            cbZoneSu.isChecked = true
            cbZoneCyrillic.isChecked = true
        } else {
            val zones = DomainBypassRules.parseZones(zonesRaw)
            cbZoneRu.isChecked = "ru" in zones
            cbZoneSu.isChecked = "su" in zones
            cbZoneCyrillic.isChecked = DomainBypassRules.CYRILLIC_TOKEN in zones
        }

        val customRaw = clientData.getDomainBypassCustomRaw() ?: ""
        etCustom.setText(customRaw)

        applyEnabledState(bypassEnabled)
        updateSummary()

        suppressListeners = false
    }

    /**
     * Подключает слушатели к переключателю, чекбоксам и текстовому полю.
     *
     * Все изменения сохраняются немедленно, чтобы пользователь не потерял
     * данные при внезапном завершении Activity (например, входящий звонок).
     */
    private fun attachListeners() {
        swBypass.setOnCheckedChangeListener { _, isChecked ->
            clientData.setDomainBypassEnabled(isChecked)
            // При первом запуске галочки показывают набор по умолчанию, но в
            // хранилище пусто. Момент включения — это и есть согласие человека с
            // тем, что он видит, поэтому набор надо записать здесь: иначе ядро
            // получило бы пустой список зон, а экран продолжал бы показывать
            // отмеченные `.ru` и `.su`.
            if (isChecked && clientData.getDomainBypassZonesRaw().isEmpty()) saveZones()
            applyEnabledState(isChecked)
            updateSummary()
            LogManager.log("DomainBypass: мастер-переключатель → $isChecked")
            scheduleReapply()
        }

        val zoneChangeListener = { _: android.widget.CompoundButton, _: Boolean ->
            if (!suppressListeners) {
                saveZones()
                updateSummary()
                // Зоны теперь действуют, и правка обязана доехать до ядра: без
                // этого снятая галочка молча оставалась бы включённой до
                // следующего подключения.
                scheduleReapply()
            }
        }

        cbZoneRu.setOnCheckedChangeListener(zoneChangeListener)
        cbZoneSu.setOnCheckedChangeListener(zoneChangeListener)
        cbZoneCyrillic.setOnCheckedChangeListener(zoneChangeListener)

        etCustom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppressListeners) {
                    // Записывать поле на каждое нажатие нельзя: сеттер идёт через
                    // `commit()`, то есть синхронная запись на диск с главного
                    // потока по букве. Значение сохраняет тот же отложенный
                    // прогон, что и переподнимает сеанс, плюс `onPause` — уйти с
                    // экрана, не сохранив набранное, невозможно.
                    updateSummary()
                    scheduleReapply(REAPPLY_TYPING_DELAY_MS)
                }
            }
        })
    }

    /**
     * Собирает строку зон из текущего состояния чекбоксов и записывает её.
     *
     * Токен "cyrillic" — специальное значение, оно не является TLD, а сигнализирует
     * движку о необходимости проверять Unicode-блок символов TLD на кириллицу.
     */
    private fun saveZones() {
        val raw = buildCurrentZonesRaw()
        clientData.setDomainBypassZonesRaw(raw)
        LogManager.log("DomainBypass: зоны сохранены → \"$raw\"")
    }

    /**
     * Включает или отключает элементы управления зонами и доменами.
     *
     * При выключенном мастер-переключателе весь блок визуально затухает,
     * чтобы пользователь понял, что настройки неактивны, но их можно сохранить заранее.
     */
    private fun applyEnabledState(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.45f

        // Зоны снова включены: ядро больше не выражает их маршрутами.
        //
        // Раньше здесь стояла заглушка — чекбоксы показывали сохранённый выбор, но
        // нажать их было нельзя, потому что маршруты Android фиксирует в
        // `establish()` и знает только адреса, а списка всех адресов зоны `.ru` не
        // существует. Теперь адреса берутся из перехваченных DNS-ответов, а
        // совпавшие потоки уходят наружу через защищённый сокет — маршруты не
        // трогаются вовсе. Границы механики (только WARP и MASQUE, только TCP
        // 80/443, потолок адресов) названы в подсказке и в сводке, а не спрятаны
        // за отключённым чекбоксом.
        cbZoneRu.isEnabled = enabled
        cbZoneRu.alpha = alpha

        cbZoneSu.isEnabled = enabled
        cbZoneSu.alpha = alpha

        cbZoneCyrillic.isEnabled = enabled
        cbZoneCyrillic.alpha = alpha

        etCustom.isEnabled = enabled
        etCustom.alpha = alpha

        tvSummary.alpha = alpha
    }

    /**
     * Обновляет строку-сводку, чтобы пользователь сразу видел, сколько
     * правил будет применено — без открытия отдельного экрана.
     *
     * Количество доменов подсчитывается через [DomainBypassRules.parseDomains],
     * чтобы значение точно совпадало с тем, что применит движок VPN.
     */
    private fun updateSummary() {
        val zoneLabels = mutableListOf<String>()
        if (cbZoneRu.isChecked) zoneLabels.add(".ru")
        if (cbZoneSu.isChecked) zoneLabels.add(".su")
        if (cbZoneCyrillic.isChecked) zoneLabels.add("кириллические")

        val customRaw = etCustom.text?.toString() ?: ""
        val domainCount = DomainBypassRules.parseDomains(customRaw).size

        // Потолок называется вслух: вырезом маршрута служба применяет первые
        // MAX_APPLIED_DOMAINS, и молчаливое «доменов: 40» обещало бы покрытие,
        // которого нет.
        val domainPart = if (domainCount > MAX_APPLIED_DOMAINS) {
            "доменов: $domainCount (адресами применяются первые $MAX_APPLIED_DOMAINS)"
        } else {
            "доменов: $domainCount"
        }
        val zonePart = if (zoneLabels.isEmpty()) "зоны не выбраны" else "зоны: ${zoneLabels.joinToString(", ")}"

        tvSummary.text = listOfNotNull(
            "$zonePart · $domainPart",
            coreStatusCache?.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
    }

    /**
     * Перечитывает состояние ядра в фоне и обновляет сводку.
     *
     * Признак «переключатель включён» снимается здесь, на главном потоке: из
     * рабочего к `View` обращаться нельзя, а после `onDestroy` его и не будет.
     */
    private fun refreshCoreStatusAsync() {
        val bypassOn = swBypass.isChecked
        val submitted = runCatching {
            statsExecutor.execute {
                val status = runCatching { coreStatusLine(bypassOn) }.getOrElse { error ->
                    LogManager.log("Обход по доменам: состояние ядра не прочиталось — ${error.message}")
                    ""
                }
                // Кэш ставит сам рабочий поток. Делать это посылкой на главный
                // означало бы, что при закрытом экране он не поставится никогда
                // (I18).
                coreStatusCache = status
                statsHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    updateSummary()
                }
            }
        }
        if (submitted.isFailure) {
            // Исполнитель уже закрыт: экран уходит. Сводка останется прежней —
            // это ровно то, что нужно, и молчание здесь не скрывает отказа.
            return
        }
    }

    /**
     * Вторая строка сводки — что на самом деле делает ядро прямо сейчас.
     *
     * Счётчики без этой строки врали бы дважды: нулями от прошлого сеанса и
     * молчанием там, где механика вообще не работает (Opera и VLESS отдают TUN в
     * tun2proxy; без перехвата DNS зонам неоткуда узнать адреса). Ровно эти два
     * случая ядро и помечает в файле, и сказать о них надо словами, а не пустотой
     * (I4).
     *
     * Зовётся из рабочего потока ([refreshCoreStatusAsync]) — ни одного
     * обращения к `View` внутри быть не должно, состояние переключателя
     * приходит параметром.
     *
     * @return пустая строка, когда обход выключен: писать о выключенном нечего.
     */
    private fun coreStatusLine(bypassOn: Boolean): String {
        if (!bypassOn) return ""
        if (!SessionReapply.isSessionLikelyActive(this, clientData)) {
            return "VPN не подключён — правила применятся при подключении."
        }
        val stats = clientData.getDomainBypassStats()
            ?: return "Ядро ещё не отчиталось о правилах."
        if (!stats.transportSupported) {
            val backend = stats.backend.ifBlank { "текущий" }
            // Вырез маршрута считается один раз, в момент подключения. Правка
            // списка на живом сеансе на него не действует — ни добавление, ни
            // удаление, — и обещать обратное нельзя.
            return "Транспорт $backend отдаёт трафик tun2proxy: зоны не работают, " +
                "свои домены выведены вырезом маршрута — правки списка подействуют " +
                "после переподключения."
        }
        if (stats.relayState == RELAY_STATE_GAVE_UP) {
            // Релей снял перехват сам: запись в TUN перестала проходить. Молчать
            // об этом нельзя — обход выглядел бы включённым и не работал (I4).
            return "Релей обхода остановился — трафик вернулся в туннель. " +
                "Переподключитесь, чтобы поднять его заново."
        }
        if (!stats.dnsInterceptEnabled) {
            return "Перехват DNS выключен — адреса зон учить неоткуда. Включите шифрованный DNS."
        }
        if (stats.learned == 0 && privateDnsMode().isNotEmpty()) {
            // Измерено на Pixel 4a (Android 14) 2026-09-06: при «Частном DNS» в
            // режиме «Автоматически» ядро не выучило ни одного адреса, при
            // «Выключено» — восемнадцать и семь перелитых потоков. Системный
            // резолвер шифрует запросы сам, открытого UDP/53 в туннеле не
            // появляется, и учиться не на чем. Показывать в этом случае ноль без
            // объяснения — то же самое, что промолчать (I4).
            return "Выучено адресов: 0. Похоже, мешает «Частный DNS» (${privateDnsMode()}): " +
                "система шифрует запросы сама, и ядру их не видно. Отключите его в настройках " +
                "Android → Сеть и интернет → Частный DNS."
        }
        return buildString {
            append("Выучено адресов: ${stats.learned} из $MAX_LEARNED_ADDRESSES")
            append(", потоков мимо туннеля: ${stats.relayed}")
            if (stats.dropped > 0) append(", вытеснено: ${stats.dropped}")
            // Пакеты, а не потоки, и не «неудачи»: HTTP/3 к обойдённому имени
            // спокойно доходит через туннель, отката на TCP не будет, то есть
            // для него обход не действует. Слово «пакетов» здесь обязательно —
            // «QUIC через туннель: 40000» иначе читается как сорок тысяч сбоев.
            if (stats.quicSkipped > 0) append(", пакетов QUIC мимо обхода: ${stats.quicSkipped}")
        }
    }

    /**
     * Режим «Частного DNS», если он включён.
     *
     * Читается из настроек системы, а не из `LinkProperties`: при поднятом VPN
     * активная сеть — наша, и `isPrivateDnsActive` описывал бы её, а не то, что
     * выбрал человек. Значение и есть то, что он видит на экране Android.
     *
     * @return пустая строка, когда «Частный DNS» выключен.
     */
    private fun privateDnsMode(): String {
        val raw = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "private_dns_mode")
        }.getOrNull()?.trim().orEmpty()
        return when (raw.lowercase()) {
            "", "off" -> ""
            "opportunistic" -> "Автоматически"
            "hostname" -> "имя узла провайдера"
            else -> raw
        }
    }

    /**
     * Возвращает строку зон из текущего UI без обращения к хранилищу.
     *
     * Нужна при первом запуске, когда хранилище ещё пустое, а сводку
     * уже нужно показать на основе дефолтных чекбоксов.
     */
    private fun buildCurrentZonesRaw(): String {
        val tokens = mutableListOf<String>()
        if (cbZoneRu.isChecked) tokens.add("ru")
        if (cbZoneSu.isChecked) tokens.add("su")
        if (cbZoneCyrillic.isChecked) tokens.add(DomainBypassRules.CYRILLIC_TOKEN)
        return tokens.joinToString(",")
    }

    /**
     * Дополнительное сохранение в onPause гарантирует, что данные не потеряются,
     * если пользователь ввёл текст и сразу перешёл в другое приложение — TextWatcher
     * срабатывает на каждый символ, но onPause отловит и незавершённый ввод.
     */
    /** Пишет поле в хранилище, если оно правда изменилось. */
    private fun persistCustomDomains() {
        val text = etCustom.text?.toString().orEmpty()
        if (text == clientData.getDomainBypassCustomRaw()) return
        clientData.setDomainBypassCustomRaw(text)
    }

    override fun onPause() {
        super.onPause()
        statsHandler.removeCallbacks(statsRunnable)
        persistCustomDomains()
        // Уход с экрана — это конец правки, и отложенный реаплай обязан случиться
        // прямо здесь. `onDestroy` снимает его с очереди, а между `onPause` и
        // `onDestroy` главный поток очередь не разбирает: по кнопке «назад»
        // отложенный вызов не выполнился бы никогда, и список доехал бы до службы
        // только при следующем подключении.
        if (reapplyPending) {
            reapplyHandler.removeCallbacks(reapplyRunnable)
            reapplyPending = false
            runReapply()
        }
    }

    /**
     * Применяет изменения к идущему сеансу с задержкой.
     *
     * Правка списка — это серия событий (символ за символом, переключатель следом),
     * и без паузы каждая уезжала бы в службу отдельным намерением. Пауза склеивает
     * серию в одно применение.
     *
     * @param delayMs для ввода текста пауза длиннее: 650 мс — это обычная пауза между
     * словами, и правило уезжало бы посреди набора домена.
     */
    private fun scheduleReapply(delayMs: Long = REAPPLY_DELAY_MS) {
        if (!SessionReapply.isSessionLikelyActive(this, clientData)) return
        reapplyHandler.removeCallbacks(reapplyRunnable)
        reapplyPending = true
        reapplyHandler.postDelayed(reapplyRunnable, delayMs)
    }

    /**
     * Отдаёт новые правила живому сеансу — без переподключения.
     *
     * Раньше здесь стоял полный реаплай, то есть пересборка туннеля с обрывом всех
     * соединений (а на Opera — ещё и `stop-then-start`). Платить этим за снятую
     * галочку `.su` не за что: ядро перечитывает правила на каждом DNS-ответе, и
     * достаточно одного намерения [NovaVpnService.ACTION_APPLY_DOMAIN_BYPASS].
     *
     * Молчать при отказе нельзя (I4): человек видит свой домен в поле и считает,
     * что правило работает, — а до службы оно не доехало.
     */
    private fun runReapply() {
        // Сначала записать, потом применять. Намерение собирается из хранилища, а
        // поле сохраняется только здесь и в `onPause` — без этой строки службе
        // уехал бы список, каким он был до правки.
        persistCustomDomains()
        if (!SessionReapply.applyDomainBypassToLiveSession(this, clientData)) {
            Toast.makeText(
                this,
                "Не удалось применить изменения к текущему сеансу",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSummary()
        statsHandler.removeCallbacks(statsRunnable)
        // Первое чтение — сразу, а не через тик: иначе после возврата на экран
        // строка о состоянии ядра две секунды показывала бы прошлый сеанс.
        refreshCoreStatusAsync()
        statsHandler.postDelayed(statsRunnable, STATS_TICK_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        reapplyHandler.removeCallbacks(reapplyRunnable)
        statsHandler.removeCallbacks(statsRunnable)
        statsExecutor.shutdownNow()
    }

    private companion object {
        /** Столько же, сколько у «прямого потока», — ощущение отклика во всех списках одно. */
        const val REAPPLY_DELAY_MS = 650L

        /** Пауза, после которой набор домена считается законченным. */
        const val REAPPLY_TYPING_DELAY_MS = 1_800L

        /**
         * Столько имён из списка служба разрешает в адреса и выводит вырезом маршрута.
         *
         * Значение обязано совпадать с `DOMAIN_BYPASS_MAX_DOMAINS` в
         * `NovaVpnService`: расхождение означало бы, что экран считает одно, а
         * туннель применяет другое — и молчит об этом. К обходу по зонам этот
         * потолок отношения не имеет: там правила уезжают в ядро целиком, а
         * ограничен набор выученных адресов.
         */
        const val MAX_APPLIED_DOMAINS = 32

        /**
         * Ядро сообщило, что релей обхода сдался.
         *
         * Значение из `domainBypassRelayGaveUp` в
         * `nova-core/engine/domain_bypass.go`: `0` — не поднимался, `1` —
         * работает, `2` — отказался и вернул трафик в туннель.
         */
        const val RELAY_STATE_GAVE_UP = 2

        /**
         * Потолок выученных адресов в ядре.
         *
         * Обязан совпадать с `domainBypassLearnedCap` в
         * `nova-core/engine/domain_bypass.go`. Потолок жёсткий: под каждый адрес
         * заводится локальный адрес netstack, а убрать его оттуда нечем —
         * `ensureLocalAddressLocked` умеет только добавлять.
         */
        const val MAX_LEARNED_ADDRESSES = 256

        /** Шаг опроса счётчиков — тот же порядок, что и опрос состояния службы. */
        const val STATS_TICK_MS = 2_000L
    }
}
