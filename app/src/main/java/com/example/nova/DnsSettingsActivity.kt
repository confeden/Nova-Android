package com.example.nova

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsSettingsActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var swGlobalDns: Switch
    private lateinit var rvDnsRules: RecyclerView
    private lateinit var btnAddDnsRule: TextView
    private lateinit var btnGetPersonalDns: TextView
    private lateinit var btnResetDnsRules: TextView
    private lateinit var dnsRuleAdapter: DnsRuleAdapter
    private lateinit var dnsRuleTouchHelper: ItemTouchHelper
    private lateinit var rgRouteMode: RadioGroup
    private lateinit var swAppOverride: Switch
    private lateinit var tvSelectedApp: TextView
    private lateinit var btnPickApp: TextView
    private lateinit var etAppSearch: EditText
    private lateinit var rvDnsApps: RecyclerView
    private lateinit var etAppPrimary: EditText
    private lateinit var etAppFallback: EditText
    private lateinit var swAppPlainFallback: Switch
    private lateinit var tvSummary: TextView
    private lateinit var tvPrivateDnsWarning: TextView
    private lateinit var dnsAppPickerAdapter: DnsAppPickerAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Менялось ли на экране что-то, чего идущий сеанс ещё не знает.
     *
     * Настройки DNS применяются при установке туннеля: путь до резолверов
     * выражается вырезом маршрута, а сам список уезжает в ядро строками
     * upstream'ов. Сохранить их и промолчать — это «настройка сохранилась и не
     * действует» (I4), причём до следующего подключения, то есть, возможно,
     * часами.
     */
    private var dnsChangePending = false
    private var appPickerJob: Job? = null
    private var allDnsApps: List<AppItem> = emptyList()
    private var suppressUiCallbacks = false
    private var selectedOverridePackage: String = ""
    private var selectedOverrideLabel: String = ""

    private var dnsRouteMode: DnsRouteMode = DnsRouteMode.AUTO

    /**
     * Правила прочитаны с диска.
     *
     * До этого записывать нечего: список в адаптере ещё пуст, и сохранение по
     * первому же касанию переключателя пути стёрло бы то, что лежит в файле.
     */
    private var dnsRulesLoaded = false

    /**
     * Прежний `dns_settings_json` целиком, как его прочитали при открытии.
     *
     * Глобальных полей у экрана больше нет — их место занял список правил, — но
     * запись остаётся общей с блоком «DNS для приложения». Пишем её от этого
     * снимка, чтобы не обнулить чужие поля своей записью.
     */
    private var legacyConfig: DnsSettingsConfig = DnsSettingsConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном,
        // и выбор доехал бы только до следующего открытия экрана.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        applyZeroTransitionOpen()
        setContentView(R.layout.activity_dns_settings)
        NovaFontHelper.apply(findViewById(android.R.id.content))
        findViewById<TextView>(R.id.tv_dns_rules_hint).collapseToInfoIcon(findViewById(R.id.sw_global_dns))

        clientData = ClientData(this)
        bindViews()
        bindListeners()
        loadConfig()
        loadDnsRules()
    }

    /**
     * Уход с экрана — конец правки, и здесь настройки доезжают до туннеля.
     *
     * Почему при уходе, а не на каждое нажатие: применение — это пересборка
     * туннеля (а на Opera ещё и `stop-then-start`), и делать её на каждый
     * переключатель значило бы рвать соединения за каждую галочку. Почему
     * вообще: без этого переключатель «Через VPN» вставал в нужное положение,
     * запись на диск проходила, а запросы продолжали уходить мимо туннеля до
     * следующего подключения — и ни строки об этом.
     */
    override fun onPause() {
        super.onPause()
        if (!dnsChangePending) return
        dnsChangePending = false
        if (!SessionReapply.isSessionLikelyActive(this, clientData)) {
            LogManager.log("DNS-правила: сеанса нет — настройки применятся при подключении.")
            return
        }
        if (!SessionReapply.applyToLiveSession(this, clientData)) {
            LogManager.log("DNS-правила: применить к идущему сеансу не удалось — нужно переподключение.")
            Toast.makeText(
                this,
                "Настройки DNS сохранены, но применить их к текущему сеансу не вышло — переподключите VPN.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appPickerJob?.cancel()
        scope.cancel()
    }

    override fun finish() {
        super.finish()
        applyZeroTransitionClose()
    }

    private fun bindViews() {
        swGlobalDns = findViewById(R.id.sw_global_dns)
        rvDnsRules = findViewById(R.id.rv_dns_rules)
        btnAddDnsRule = findViewById(R.id.btn_add_dns_rule)
        btnGetPersonalDns = findViewById(R.id.btn_dns_get_personal)
        btnResetDnsRules = findViewById(R.id.btn_reset_dns_rules)
        rgRouteMode = findViewById(R.id.rg_dns_route_mode)
        swAppOverride = findViewById(R.id.sw_app_override_dns)
        tvSelectedApp = findViewById(R.id.tv_selected_dns_app)
        btnPickApp = findViewById(R.id.btn_pick_dns_app)
        etAppSearch = findViewById(R.id.et_dns_app_search)
        rvDnsApps = findViewById(R.id.rv_dns_apps)
        etAppPrimary = findViewById(R.id.et_app_primary_dns)
        etAppFallback = findViewById(R.id.et_app_fallback_dns)
        swAppPlainFallback = findViewById(R.id.sw_app_plain_fallback)
        tvSummary = findViewById(R.id.tv_dns_runtime_summary)
        tvPrivateDnsWarning = findViewById(R.id.tv_dns_private_dns_warning)
        bindDnsRulesList()
        dnsAppPickerAdapter = DnsAppPickerAdapter(selectedOverridePackage) { selected ->
            selectedOverridePackage = selected.packageName
            selectedOverrideLabel = selected.label
            renderSelectedApp()
            hideAppPicker()
            applyExclusiveDnsAppMode()
        }
        rvDnsApps.layoutManager = LinearLayoutManager(this)
        rvDnsApps.adapter = dnsAppPickerAdapter
        rvDnsApps.isNestedScrollingEnabled = true
        rvDnsApps.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        TvFocusHelper.install(
            this,
            swGlobalDns,
            btnGetPersonalDns,
            btnAddDnsRule,
            btnResetDnsRules,
            btnPickApp,
            swAppOverride,
        )
    }

    /**
     * Список резолверов: адаптер, перетаскивание и перехват жеста у ScrollView.
     *
     * Смахивание намеренно выключено (второй аргумент `SimpleCallback` — 0): у
     * строки есть кнопка удаления, а горизонтальный жест внутри прокручиваемого
     * экрана спорит с самой прокруткой.
     *
     * Перетаскивание доступно всегда, отдельного режима нет: тянут за ручку
     * строки, а случайный сдвиг при прокрутке отсекает порог движения в адаптере.
     */
    private fun bindDnsRulesList() {
        dnsRuleAdapter = DnsRuleAdapter(
            onEdit = { position -> showDnsRuleDialog(position) },
            onToggle = { position, isChecked -> onDnsRuleToggled(position, isChecked) },
            onDelete = { position -> onDnsRuleDeleted(position) },
            onStartDrag = { holder -> dnsRuleTouchHelper.startDrag(holder) },
            onPersonalSlot = { showPersonalDnsDialog() },
        )
        rvDnsRules.layoutManager = LinearLayoutManager(this)
        rvDnsRules.adapter = dnsRuleAdapter
        rvDnsRules.isNestedScrollingEnabled = true
        // Переключатель строки перерисовывает её же: со штатной анимацией
        // замены строка успевает моргнуть насквозь под самым пальцем.
        (rvDnsRules.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        // Без этого палец, ведущий строку вверх, уводит вместо неё весь экран:
        // ScrollView перехватывает вертикальное движение первым.
        rvDnsRules.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (dnsRuleAdapter.isSlot(viewHolder.adapterPosition)) {
                // Незаполненную строку «Персональный DNS-AI» не двигают: это не правило.
                0
            } else {
                ItemTouchHelper.Callback.makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                    0,
                )
            }

            // Тянут только за ручку: долгое нажатие на строке ничего не двигает,
            // иначе задержка пальца на строке перед правкой выглядела бы как сбой.
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                return dnsRuleAdapter.moveItem(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.8f
                    rvDnsRules.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            // Пишем на отпускании, а не на каждом шаге: за одно перетаскивание
            // onMove срабатывает столько раз, сколько строк перепрыгнули.
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                rvDnsRules.parent?.requestDisallowInterceptTouchEvent(false)
                persistDnsRules("порядок изменён")
            }
        }
        dnsRuleTouchHelper = ItemTouchHelper(dragCallback)
        dnsRuleTouchHelper.attachToRecyclerView(rvDnsRules)
    }

    private fun bindListeners() {
        swGlobalDns.setOnCheckedChangeListener { _, checked ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            // Тот же заслон, что и у выбора пути: до конца чтения списка запись
            // правил выходит молча, а `loadDnsRules` возвращает переключатель
            // обратно — нажатие пропадало бы без единой строки в журнале.
            if (!dnsRulesReady("переключатель своего DNS")) return@setOnCheckedChangeListener
            renderUiState()
            persistConfig()
            persistDnsRules(if (checked) "свой DNS включён" else "свой DNS выключен")
        }
        btnAddDnsRule.setOnClickListener { showDnsRuleDialog(null) }
        btnGetPersonalDns.setOnClickListener { openVitrinaMessage() }
        btnResetDnsRules.setOnClickListener { showDnsRulesResetDialog() }
        swAppOverride.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            renderUiState()
            persistConfig()
        }
        swAppPlainFallback.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        rgRouteMode.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            // Пока список не прочитан, выбор пути принимать нельзя: запись
            // правил его всё равно не заберёт, а отметку тут же перебьёт
            // значение с диска — и две записи разошлись бы между собой.
            if (!dnsRulesReady("смена пути до резолверов")) return@setOnCheckedChangeListener
            dnsRouteMode = checkedRouteMode()
            persistConfig()
            persistDnsRules("путь до резолверов — ${dnsRouteMode.storageValue()}")
        }
        btnPickApp.setOnClickListener {
            if (selectedOverridePackage.isNotBlank()) {
                cancelAppDnsRule()
            } else if (rvDnsApps.visibility == View.VISIBLE) {
                hideAppPicker()
            } else {
                openAppPicker()
            }
        }
        etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                submitDnsAppList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val fields = listOf(
            etAppPrimary,
            etAppFallback,
        )
        fields.forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressUiCallbacks) return
                    persistConfig()
                }
            })
        }
    }

    private fun loadConfig() {
        val config = clientData.getDnsSettingsConfig()
        legacyConfig = config
        suppressUiCallbacks = true
        swGlobalDns.isChecked = config.globalEnabled
        // Радиокнопку пути здесь не отмечаем: путь приходит из DnsRulesStore, а
        // тот читается с диска, то есть уже не в этом такте (см. loadDnsRules).
        // Прежнее значение всё же запоминаем — до конца чтения экран может
        // успеть сохранить старую запись, и записать в неё «auto» вместо
        // выбранного пути значило бы соврать в сводке.
        dnsRouteMode = DnsRouteMode.parse(config.routeMode)
        swAppOverride.isChecked = config.appOverride.enabled
        selectedOverridePackage = config.appOverride.packageName
        selectedOverrideLabel = config.appOverride.appLabel
        etAppFallback.setText(config.appOverride.encryptedFallback)
        etAppPrimary.setText(config.appOverride.primaryDns)
        swAppPlainFallback.isChecked = config.appOverride.allowPlainFallback
        suppressUiCallbacks = false
        renderSelectedApp()
        renderUiState()
        updateSummary()
    }

    private fun renderUiState() {
        // Список и путь остаются доступными при выключенном переключателе: он
        // означает «сейчас не пользуемся», а не «править нельзя». Гасить их
        // значило бы заставить включить настройку, чтобы её настроить.
        val appOverrideEnabled = swAppOverride.isChecked
        val appViews = listOf<View>(
            btnPickApp,
            etAppSearch,
            rvDnsApps,
            etAppPrimary,
            etAppFallback,
            swAppPlainFallback,
        )
        appViews.forEach { view ->
            view.isEnabled = appOverrideEnabled
            view.alpha = if (appOverrideEnabled) 1f else 0.55f
        }
        tvSelectedApp.alpha = if (appOverrideEnabled) 1f else 0.55f
        renderPrivateDnsWarning()
        renderExclusiveActionState()
    }

    /**
     * Предупреждение: системный «Частный DNS» перекрывает весь этот список.
     *
     * При строгом («имя узла провайдера») и при «Автоматически» Android шифрует
     * запросы сам — DoT уходит мимо UDP/53, на котором построен перехват ядра, —
     * и порядок резолверов здесь ни на что не влияет: список опрашивается только
     * когда до ядра доходит открытый запрос. Замерено на Pixel 4a: при DNS-AI
     * первым в списке страница dns-ai.ru/ip показывала «Используется DNS-AI: нет»,
     * а стоило выключить системный «Частный DNS» — лог ядра тут же резолвил через
     * первый резолвер списка. Молча показывать заполненный список, который не
     * действует, — тот же дефект, ради которого экран и переделывался (I4). Та же
     * причина и лечение уже названы в «Обходе по доменам».
     *
     * Показывается только когда список включён: при выключенном своём DNS
     * перекрывать нечего.
     */
    private fun renderPrivateDnsWarning() {
        val mode = privateDnsMode()
        if (mode.isEmpty() || !swGlobalDns.isChecked) {
            tvPrivateDnsWarning.visibility = View.GONE
            return
        }
        tvPrivateDnsWarning.text =
            "«Частный DNS» в системе включён ($mode): Android шифрует запросы сам, " +
                "и этот список не действует — резолверы опрашиваются, только когда он выключен. " +
                "Отключите его в настройках Android → Сеть и интернет → Частный DNS."
        tvPrivateDnsWarning.visibility = View.VISIBLE
    }

    /**
     * Режим системного «Частного DNS», если он включён.
     *
     * Читается из настроек системы, а не из `LinkProperties`: при поднятом VPN
     * активная сеть — наша, и та описывала бы её, а не выбор человека. Пустая
     * строка — «Частный DNS» выключен.
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
     * Возврат из настроек Android: «Частный DNS» мог измениться, пока экран был
     * в фоне. Перечитываем предупреждение, чтобы оно не отставало от системы.
     */
    override fun onResume() {
        super.onResume()
        if (::swGlobalDns.isInitialized) renderPrivateDnsWarning()
    }

    private fun renderSelectedApp() {
        tvSelectedApp.text = if (selectedOverridePackage.isBlank()) {
            "Выбрано приложение: пока не выбрано"
        } else {
            "Выбрано приложение: ${selectedOverrideLabel.ifBlank { selectedOverridePackage }}"
        }
    }

    private fun persistConfig() {
        // Глобальные поля берём из снимка: их полей на экране больше нет, а
        // сводка в шапке всё ещё построена на них.
        val updated = legacyConfig.copy(
            globalEnabled = swGlobalDns.isChecked,
            routeMode = dnsRouteMode.storageValue(),
            appOverride = DnsAppOverride(
                enabled = swAppOverride.isChecked,
                packageName = selectedOverridePackage,
                appLabel = selectedOverrideLabel,
                primaryDns = etAppPrimary.text?.toString().orEmpty(),
                secondaryDns = "",
                encryptedFallback = etAppFallback.text?.toString().orEmpty(),
                allowPlainFallback = swAppPlainFallback.isChecked,
            ),
        )
        legacyConfig = updated
        clientData.saveDnsSettingsConfig(updated)
        dnsChangePending = true
        updateSummary()
    }

    /**
     * Сводка считается в рабочем потоке.
     *
     * `getDnsSettingsSummary` читает `dns_rules.json`, а зовут её и из `onCreate`,
     * и после каждой правки списка — то есть блокирующий ввод-вывод оказывался в
     * главном потоке (I13). Кэш внутри снял его с набора текста, но не с самого
     * потока; здесь он снят целиком.
     */
    private fun updateSummary() {
        renderExclusiveActionState()
        lifecycleScope.launch(Dispatchers.IO) {
            val text = clientData.getDnsSettingsSummary()
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                tvSummary.text = text
            }
        }
    }

    // ---------------------------------------------------------------- правила

    /** Чтение правил с диска: файл, а не настройки, поэтому только в IO. */
    private fun loadDnsRules() {
        lifecycleScope.launch(Dispatchers.IO) {
            val set = DnsRulesStore.load(this@DnsSettingsActivity)
            withContext(Dispatchers.Main) {
                dnsRouteMode = set.routeMode
                suppressUiCallbacks = true
                rgRouteMode.check(routeRadioId(set.routeMode))
                // Выключатель показывает состояние **хранилища правил**, а не
                // унаследованный `global_enabled`: именно оно решает, пользуется
                // ли туннель этим списком.
                swGlobalDns.isChecked = set.enabled
                suppressUiCallbacks = false
                dnsRuleAdapter.submit(set.rules)
                dnsRulesLoaded = true
            }
        }
    }

    /**
     * Запись всего набора правил — по событию, а не по нажатию клавиши.
     *
     * Прежний экран писал весь JSON на каждый символ в поле; здесь запись
     * происходит ровно на принятом диалоге, отпущенном перетаскивании,
     * переключателе строки, удалении и смене пути.
     */
    private fun persistDnsRules(reason: String) {
        if (!dnsRulesLoaded) return
        val snapshot = DnsRuleSet(dnsRuleAdapter.snapshot(), dnsRouteMode, swGlobalDns.isChecked)
        // Правила ушли в туннель не сами: их надо отдать живому сеансу, и делаем
        // это один раз, при уходе с экрана (см. [onPause]).
        dnsChangePending = true
        val appContext = applicationContext
        // Запись переживает экран намеренно. `lifecycleScope` отменяется в
        // `onDestroy`, а здесь между запуском и самой записью есть переключение
        // потока: по кнопке «назад» сразу после правки отмена успевала первой, и
        // правило пропадало молча.
        storeWriterScope.launch {
            val saved = DnsRulesStore.save(appContext, snapshot)
            LogManager.log(
                if (saved) {
                    "DNS-правила: $reason, сохранено ${snapshot.rules.size} шт."
                } else {
                    "DNS-правила: $reason — записать не удалось."
                }
            )
            withContext(Dispatchers.Main) {
                // Запись живёт дольше экрана, а сводка — нет: после `onDestroy`
                // трогать `View` нельзя.
                if (isFinishing || isDestroyed) return@withContext
                // Обновляем сводку после записи на диск, иначе шапка показывает прошлый список.
                updateSummary()
            }
        }
    }

    /**
     * Готов ли экран менять правила.
     *
     * Отказ молчаливым не делаем: окно между открытием экрана и концом чтения
     * файла крошечное, и без записи в журнал пропавшее нажатие было бы нечем
     * объяснить.
     */
    private fun dnsRulesReady(action: String): Boolean {
        if (dnsRulesLoaded) return true
        LogManager.log("DNS-правила: список ещё читается, «$action» пропущено.")
        return false
    }

    private fun checkedRouteMode(): DnsRouteMode = when (rgRouteMode.checkedRadioButtonId) {
        R.id.rb_dns_route_direct -> DnsRouteMode.DIRECT
        R.id.rb_dns_route_tunnel -> DnsRouteMode.TUNNEL
        else -> DnsRouteMode.AUTO
    }

    private fun routeRadioId(mode: DnsRouteMode): Int = when (mode) {
        DnsRouteMode.DIRECT -> R.id.rb_dns_route_direct
        DnsRouteMode.TUNNEL -> R.id.rb_dns_route_tunnel
        DnsRouteMode.AUTO -> R.id.rb_dns_route_fastest
    }

    private fun onDnsRuleToggled(position: Int, isChecked: Boolean) {
        val rule = dnsRuleAdapter.itemAt(position) ?: return
        if (rule.enabled == isChecked) return
        dnsRuleAdapter.replaceAt(position, rule.copy(enabled = isChecked))
        persistDnsRules(
            "${if (isChecked) "включено" else "выключено"} ${DnsRuleAdapter.transportLabel(rule)} ${DnsAiEntrances.logValue(rule.value)}"
        )
        // Выключить можно и все сразу: список от этого не пустеет, но резолвинг
        // уходит на встроенную цепочку, и об этом честнее сказать сразу.
        if (dnsRuleAdapter.snapshot().none { it.enabled }) {
            Toast.makeText(
                this,
                "Все резолверы выключены — DNS пойдёт по встроенной цепочке.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun onDnsRuleDeleted(position: Int) {
        // Пустой список — это не «без своего DNS», а без резолвинга вообще:
        // хранилище такой файл всё равно не примет и вернёт умолчания.
        if (dnsRuleAdapter.ruleCount <= 1) {
            Toast.makeText(
                this,
                "Нужен хотя бы один резолвер: пустой список некому опрашивать.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val removed = dnsRuleAdapter.removeAt(position) ?: return
        persistDnsRules("удалено ${DnsRuleAdapter.transportLabel(removed)} ${DnsAiEntrances.logValue(removed.value)}")
    }

    /**
     * Добавление и правка резолвера.
     *
     * @param position строка списка или `null` для нового правила.
     */
    private fun showDnsRuleDialog(position: Int?) {
        if (!dnsRulesReady(if (position == null) "добавление резолвера" else "правка резолвера")) return
        val existing = position?.let { dnsRuleAdapter.itemAt(it) }
        if (position != null && existing == null) return

        val builder = AlertDialog.Builder(this)
        // Именно контекст диалога: разметка берёт цвета из темы, а тема у окна
        // диалога своя.
        val view = LayoutInflater.from(builder.context)
            .inflate(R.layout.dialog_dns_rule_edit, null, false)
        NovaFontHelper.apply(view)

        val kindGroup = view.findViewById<RadioGroup>(R.id.rg_dns_rule_kind)
        val valueField = view.findViewById<EditText>(R.id.et_dns_rule_value)
        val valueError = view.findViewById<TextView>(R.id.tv_dns_rule_value_error)
        // Пояснения диалога — за значком «i» у короткой подписи; сами тексты остаются в дереве.
        view.findViewById<TextView>(R.id.tv_dns_rule_transport_hint).collapseToInfoIcon()
        view.findViewById<TextView>(R.id.tv_dns_rule_value_hint).collapseToInfoIcon()
        // Поля bootstrap на экране больше нет. Адреса, по которым дозваниваются до
        // имени резолвера, приложение подставляет само: для нашего сервера они
        // зашиты (`PriorityDns.KNOWN_ADDRESSES`), для чужого имени берутся свежим
        // резолвом мимо VPN при подключении. Просить их у человека значило бы
        // требовать знания, которого у него нет, ради значения, которое приложение
        // и так знает лучше.

        // Группа выбирает транспорт, а не вид значения. Открытого варианта в ней
        // нет и быть не может: адрес открытого правила уезжает в вырез маршрута,
        // а это утечка имён наружу (I8, D10, G96).
        fun selectedTransport(): DnsRule.Transport = when (kindGroup.checkedRadioButtonId) {
            R.id.rb_dns_rule_doh -> DnsRule.Transport.DOH
            R.id.rb_dns_rule_dot -> DnsRule.Transport.DOT
            else -> DnsRule.Transport.ANY
        }

        /**
         * Вид значения — из того, что человек написал, **и только**.
         *
         * Кнопка выбирает транспорт, а не форму записи. Пока вид брался у кнопки,
         * переключение готового DoH-правила на «DoT» отправляло его адрес в
         * нормализатор DoT, и `https://cloudflare-dns.com/dns-query` превращался
         * в `tls://https://cloudflare-dns.com/dns-query`: хостом становилось
         * «https», имя резолвера уезжало в путь, и правило молча выпадало из
         * цепочки — ни ошибки на экране, ни строки в журнале.
         *
         * Обе формы принимаются всегда: `https://имя/путь` и `tls://имя`. Вторую
         * цель служба соберёт из того же имени сама
         * (`dnsRuleDohTarget`/`dnsRuleDotTarget`), поэтому спрашивать два адреса
         * не нужно и записанный вид ничего не запрещает.
         */
        fun selectedKind(raw: String?): DnsRule.Kind =
            if (DnsRule.normalizeValue(DnsRule.Kind.DOH, raw) != null) {
                DnsRule.Kind.DOH
            } else {
                DnsRule.Kind.DOT
            }

        fun renderTransport() {
            valueField.hint = when (selectedTransport()) {
                DnsRule.Transport.DOT -> "tls://dns.example.com"
                DnsRule.Transport.ANY ->
                    "https://dns.example.com/dns-query, tls://dns.example.com или личная ссылка DNS-AI"
                else -> "https://dns.example.com/dns-query"
            }
        }

        kindGroup.check(
            when (existing?.transport) {
                DnsRule.Transport.DOH -> R.id.rb_dns_rule_doh
                DnsRule.Transport.DOT -> R.id.rb_dns_rule_dot
                // Новое правило заводится «любым»: владелец просил, чтобы
                // спрашивалось то, что отвечает быстрее.
                else -> R.id.rb_dns_rule_any
            }
        )
        valueField.setText(existing?.value.orEmpty())
        renderTransport()
        kindGroup.setOnCheckedChangeListener { _, _ ->
            valueError.visibility = View.GONE
            renderTransport()
        }

        val dialog = builder
            .setTitle(if (existing == null) "Новый сторонний резолвер" else "Изменить резолвер")
            .setView(view)
            // Обработчик вешаем после показа: штатный закрывает диалог до того,
            // как мы успеем сказать, что адрес не годится.
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            // Диалог собран через `create()`, а не `showNova()`, потому что
            // обработчик показа нужен ему самому. Кнопки красим здесь же.
            NovaDialogs.style(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pickedTransport = selectedTransport()
                val raw = valueField.text?.toString()
                // Ссылка DNS-AI неверной формы: говорим, что не так, и не цитируем её — токен
                // это секрет.
                DnsAiEntrances.linkError(raw)?.let { problem ->
                    valueError.text = problem
                    valueError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val kind = selectedKind(raw)
                val value = DnsRule.normalizeValue(kind, raw)
                // У `cf.` и личных ссылок DoT нет: выбор кнопки для них не действует.
                val transport = if (value != null && DnsAiEntrances.isDohOnly(value)) {
                    DnsRule.Transport.DOH
                } else {
                    pickedTransport
                }
                if (value == null) {
                    // Текст один на все три кнопки: вид больше не следует за
                    // кнопкой, и «Для DoT нужен адрес вида tls://…» требовал бы
                    // того, чего код уже не требует.
                    valueError.text =
                        "Нужен адрес вида https://host/dns-query или tls://host, либо личная ссылка DNS-AI"
                    valueError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                valueError.visibility = View.GONE

                // Старый bootstrap сохраняем только если адрес не изменился. Иначе ядро
                // пойдёт на старые IP с новым TLS SNI, получит ошибку сертификата и правило
                // перестанет работать, так как при непустом bootstrap имя заново не резолвится.
                // При смене адреса и для новых правил отдаём пустой список, чтобы сервис
                // разрешил новое имя при подключении.
                val addresses = if (existing != null && existing.value.equals(value, ignoreCase = true)) {
                    existing.bootstrap
                } else {
                    emptyList()
                }

                val duplicate = dnsRuleAdapter.snapshot().withIndex().any { (index, rule) ->
                    index != position && rule.value.equals(value, ignoreCase = true)
                }
                if (duplicate) {
                    valueError.text = "Такой резолвер в списке уже есть."
                    valueError.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                if (existing == null) {
                    val created = DnsRule.create(kind, value, addresses, transport)
                    if (created == null) {
                        valueError.text = "Не удалось разобрать адрес — проверьте написание."
                        valueError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    val addedAt = dnsRuleAdapter.addItem(created)
                    DnsAiEntrances.personalLinkOf(value)?.let(::checkPersonalLink)
                    rvDnsRules.post { rvDnsRules.scrollToPosition(addedAt) }
                    persistDnsRules("добавлено ${DnsRuleAdapter.transportLabel(created)} ${DnsAiEntrances.logValue(value)}")
                } else {
                    val updated = existing.copy(
                        kind = kind,
                        value = value,
                        bootstrap = addresses,
                        transport = transport,
                    )
                    dnsRuleAdapter.replaceAt(position, updated)
                    if (existing.value != value) DnsAiEntrances.personalLinkOf(value)?.let(::checkPersonalLink)
                    persistDnsRules("изменено ${DnsRuleAdapter.transportLabel(updated)} ${DnsAiEntrances.logValue(value)}")
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Окно строки «Персональный DNS-AI»: вставить ссылку DoH из Витрины и проверить её.
     *
     * Ссылка попадает в список, только если сервер ответил на настоящий пробный запрос по GET.
     * Не ответил — правило не создаётся, а причина (сеть, TLS, код сервера) выводится в окне.
     * Токен — секрет: ни в окне, ни в журнале его нет.
     */
    private fun showPersonalDnsDialog() {
        if (!dnsRulesReady("персональный DNS-AI")) return
        val builder = AlertDialog.Builder(this)
        val view = LayoutInflater.from(builder.context).inflate(R.layout.dialog_dns_personal, null, false)
        NovaFontHelper.apply(view)
        val field = view.findViewById<EditText>(R.id.et_dns_personal_link)
        val status = view.findViewById<TextView>(R.id.tv_dns_personal_status)
        val neutralColor = status.currentTextColor
        var checking: Job? = null

        fun showStatus(text: String, error: Boolean) {
            status.text = NovaLanguage.tr(this, text)
            status.setTextColor(if (error) 0xFFE05252.toInt() else neutralColor)
            status.visibility = View.VISIBLE
        }

        val dialog = builder
            .setTitle("Персональный DNS-AI")
            .setView(view)
            // Обработчики вешаем после показа: штатный закрывает окно до проверки.
            .setPositiveButton("Добавить", null)
            .setNeutralButton("В Витрину", null)
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            NovaDialogs.style(dialog)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openVitrinaMessage() }
            positive.setOnClickListener {
                if (checking?.isActive == true) return@setOnClickListener
                val raw = field.text?.toString()
                if (raw.isNullOrBlank()) {
                    showStatus("Вставьте ссылку из Витрины.", true)
                    return@setOnClickListener
                }
                val link = DnsAiEntrances.parsePersonalLink(raw)
                if (link == null) {
                    showStatus(
                        DnsAiEntrances.linkError(raw)
                            ?: "Это не личная ссылка DNS-AI. Ожидается https://ru.dns-ai.ru/<токен> (можно с {?dns} на конце).",
                        true,
                    )
                    return@setOnClickListener
                }
                if (dnsRuleAdapter.snapshot().any { it.value.equals(link.url, ignoreCase = true) }) {
                    showStatus("Такая ссылка в списке уже есть.", true)
                    return@setOnClickListener
                }
                positive.isEnabled = false
                field.isEnabled = false
                showStatus("Проверяю: пробный запрос к серверу по GET…", false)
                checking = scope.launch {
                    val result = withContext(Dispatchers.IO) { DnsAiEntrances.checkLinkDetailed(link, 10) }
                    LogManager.log(
                        "DNS-AI: личная ссылка ${link.host}/… — проверка: ${result.state.name.lowercase()}, ${result.describe()}."
                    )
                    if (result.state != DnsAiEntrances.LinkState.ACCEPTED) {
                        positive.isEnabled = true
                        field.isEnabled = true
                        val act = this@DnsSettingsActivity
                        showStatus(
                            NovaLanguage.tr(act, "Не добавлено:") + " " + result.describe { NovaLanguage.tr(act, it) },
                            true,
                        )
                        return@launch
                    }
                    val created = DnsRule.create(DnsRule.Kind.DOH, link.url, emptyList(), DnsRule.Transport.DOH)
                    if (created == null) {
                        positive.isEnabled = true
                        field.isEnabled = true
                        showStatus("Сервер ответил, но ссылку не удалось сохранить.", true)
                        return@launch
                    }
                    val addedAt = dnsRuleAdapter.addItem(created)
                    rvDnsRules.post { rvDnsRules.scrollToPosition(addedAt) }
                    persistDnsRules("добавлено DoH ${DnsAiEntrances.logValue(created.value)}")
                    Toast.makeText(
                        this@DnsSettingsActivity,
                        NovaLanguage.tr(this@DnsSettingsActivity, "Персональный DNS-AI добавлен: сервер ответил."),
                        Toast.LENGTH_LONG,
                    ).show()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener { checking?.cancel() }
        dialog.show()
    }

    /**
     * Живая проверка личной ссылки DNS-AI после сохранения: один настоящий DoH-запрос по GET.
     *
     * Правило сохраняется в любом случае — «не удалось проверить» не значит «не принята», и
     * человек может быть без сети. Живёт дольше экрана, как и запись правил. Токен в журнал
     * не попадает.
     */
    private fun checkPersonalLink(link: DnsAiEntrances.PersonalLink) {
        val appContext = applicationContext
        storeWriterScope.launch {
            val state = DnsAiEntrances.checkLink(link)
            LogManager.log("DNS-AI: личная ссылка ${link.host}/… — проверка: ${state.name.lowercase()}.")
            val text = when (state) {
                DnsAiEntrances.LinkState.ACCEPTED -> "Личная ссылка DNS-AI принята сервером."
                DnsAiEntrances.LinkState.REJECTED ->
                    "Сервер не принял личную ссылку (404): проверьте её. Правило сохранено."
                DnsAiEntrances.LinkState.UNVERIFIED ->
                    "Личную ссылку не удалось проверить: сервер не ответил. Правило сохранено."
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, NovaLanguage.tr(appContext, text), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Открывает в Telegram сообщение группы, где Витрина выдаёт личную ссылку DNS-AI.
     *
     * Сначала приложение Telegram по `tg://` (тема и сообщение), иначе обычная ссылка
     * `t.me` — её откроет браузер. Отказ не молчаливый (I4): человеку говорят, что открыть
     * нечем, и пишут в журнал.
     */
    private fun openVitrinaMessage() {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(DnsAiEntrances.VITRINA_APP_LINK))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(DnsAiEntrances.VITRINA_WEB_LINK))
        val opened = runCatching {
            if (app.resolveActivity(packageManager) != null) startActivity(app) else startActivity(web)
        }.recoverCatching { startActivity(web) }
        if (opened.isFailure) {
            LogManager.log("DNS-AI: сообщение Витрины не открылось — ${opened.exceptionOrNull()?.message}")
            Toast.makeText(
                this,
                NovaLanguage.tr(this, "Не удалось открыть Telegram. Ссылка: t.me/nova_txt/322474/375424"),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showDnsRulesResetDialog() {
        if (!dnsRulesReady("сброс к умолчаниям")) return
        AlertDialog.Builder(this)
            .setTitle("Вернуть резолверы по умолчанию?")
            .setMessage(
                "Список заменится штатным: наш резолвер и cf.dns-ai.ru — оба шифрованные — " +
                    "и последней ступенью открытый резолвер провайдера. " +
                    "Ваши правила, личные ссылки DNS-AI и их порядок пропадут."
            )
            .setPositiveButton("Сбросить") { _, _ ->
                dnsRuleAdapter.submit(DnsRulesStore.defaults())
                persistDnsRules("сброс к умолчаниям")
                Toast.makeText(this, NovaLanguage.tr(this, "Список резолверов сброшен."), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .showNova()
    }

    private fun renderExclusiveActionState() {
        val status = clientData.getDnsAppOverrideStatus()
        val appOverrideEnabled = swAppOverride.isChecked
        val hasSelectedApp = selectedOverridePackage.isNotBlank()
        btnPickApp.isEnabled = appOverrideEnabled || hasSelectedApp
        btnPickApp.alpha = if (btnPickApp.isEnabled) 1f else 0.55f
        btnPickApp.text = when {
            hasSelectedApp -> "Отменить правило"
            !appOverrideEnabled -> "Включите DNS для приложения"
            rvDnsApps.visibility == View.VISIBLE -> "Скрыть список"
            status.waitingForExclusiveMode -> "Выбрать приложение"
            else -> "Выбрать приложение"
        }
    }

    private fun applyExclusiveDnsAppMode() {
        if (!swAppOverride.isChecked) {
            Toast.makeText(this, NovaLanguage.tr(this, "Сначала включите DNS для приложения."), Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedOverridePackage.isBlank()) {
            Toast.makeText(this, NovaLanguage.tr(this, "Сначала выберите приложение."), Toast.LENGTH_SHORT).show()
            return
        }
        val currentMode = clientData.getSplitMode()
        val currentApps = clientData.getSplitApps()
        val targetApps = setOf(selectedOverridePackage)
        if (!(currentMode == 1 && currentApps == targetApps)) {
            val existingSnapshot = clientData.getDnsExclusiveRestoreSnapshot()
            if (existingSnapshot == null || existingSnapshot.targetPackage != selectedOverridePackage) {
                clientData.saveDnsExclusiveRestoreSnapshot(
                    mode = currentMode,
                    apps = currentApps,
                    targetPackage = selectedOverridePackage,
                )
            }
        }
        clientData.setSplitMode(1)
        clientData.setSplitApps(targetApps)
        persistConfig()
        updateSummary()
        Toast.makeText(
            this,
            "VPN переключён на режим: только ${selectedOverrideLabel.ifBlank { selectedOverridePackage }}",
            Toast.LENGTH_LONG,
        ).show()
        reapplyActiveSession()
    }

    private fun cancelAppDnsRule() {
        val snapshot = clientData.getDnsExclusiveRestoreSnapshot()
        if (snapshot != null) {
            clientData.setSplitMode(snapshot.mode)
            clientData.setSplitApps(snapshot.apps)
            clientData.clearDnsExclusiveRestoreSnapshot()
        }
        selectedOverridePackage = ""
        selectedOverrideLabel = ""
        hideAppPicker()
        renderSelectedApp()
        persistConfig()
        updateSummary()
        Toast.makeText(this, NovaLanguage.tr(this, "Правило DNS для приложения отменено."), Toast.LENGTH_LONG).show()
        reapplyActiveSession()
    }

    /**
     * Отдаёт настройки идущему сеансу.
     *
     * Раньше намерение собиралось здесь руками, и в нём не было ни «Прямого
     * потока», ни маскировки SNI, ни обхода по доменам. Процесс `:vpn` держит
     * свою копию настроек (I2), и всё, чего нет в extras, он применяет старым
     * значением (I19) — то есть правка DNS молча откатывала чужую настройку,
     * если та ещё не доехала. Набор полей обязан быть один на всё приложение,
     * и он в [SessionReapply.buildIntent].
     */
    private fun reapplyActiveSession() {
        // Применили здесь — значит, [onPause] делать этого второй раз не должен.
        dnsChangePending = false
        runCatching {
            ContextCompat.startForegroundService(this, SessionReapply.buildIntent(this, clientData))
        }.onFailure {
            Toast.makeText(
                this,
                "Параметры сохранены. Переподключите VPN, если режим не применился сразу.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openAppPicker() {
        appPickerJob?.cancel()
        btnPickApp.isEnabled = false
        btnPickApp.text = "Загружаем список..."
        etAppSearch.visibility = View.VISIBLE
        rvDnsApps.visibility = View.VISIBLE
        appPickerJob = scope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppCacheManager.getInstalledApps(this@DnsSettingsActivity, emptySet())
                    .sortedBy { it.label.lowercase() }
            }
            allDnsApps = apps
            btnPickApp.isEnabled = true
            renderExclusiveActionState()
            if (apps.isEmpty()) {
                Toast.makeText(
                    this@DnsSettingsActivity,
                    "Список приложений пуст.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            submitDnsAppList(etAppSearch.text?.toString().orEmpty())
        }
    }

    private fun hideAppPicker() {
        etAppSearch.visibility = View.GONE
        rvDnsApps.visibility = View.GONE
        etAppSearch.setText("")
        renderExclusiveActionState()
    }

    private fun submitDnsAppList(query: String = "") {
        val normalizedQuery = query.trim().lowercase()
        val filtered = if (normalizedQuery.isBlank()) {
            allDnsApps
        } else {
            allDnsApps.filter { app ->
                app.label.lowercase().contains(normalizedQuery) ||
                    app.packageName.lowercase().contains(normalizedQuery)
            }
        }
        dnsAppPickerAdapter.setSelectedPackage(selectedOverridePackage)
        dnsAppPickerAdapter.setData(filtered)
    }

    private fun applyZeroTransitionOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun applyZeroTransitionClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        /**
         * Запись правил живёт дольше экрана.
         *
         * `lifecycleScope` отменяется в `onDestroy`, а запись уходит в IO через
         * переключение потока: по кнопке «назад» сразу после правки отмена
         * успевала раньше самой записи, и правило пропадало без единого слова.
         * Область процесса, а не экрана, и работает она с `applicationContext`,
         * чтобы не удерживать активность.
         */
        val storeWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
