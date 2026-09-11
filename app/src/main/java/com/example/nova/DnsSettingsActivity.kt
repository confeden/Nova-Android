package com.example.nova

import android.content.Intent
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
    private lateinit var btnDnsReorder: TextView
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
    private lateinit var dnsAppPickerAdapter: DnsAppPickerAdapter

    /**
     * Включён ли режим «Изменить порядок».
     *
     * Перетаскивание живёт только в нём. Вне режима у строки нет ручки, а
     * [ItemTouchHelper.Callback.getMovementFlags] отдаёт нули — то есть и прямой
     * `startDrag` ничего не начнёт, и жест, застигнутый выключением режима, не
     * доедет до записи порядка.
     */
    private var reorderMode = false

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
        // Режим не переживает уход с экрана: вернувшись, человек снова видит
        // список, который нельзя задеть пальцем случайно.
        setReorderMode(false)
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
        btnDnsReorder = findViewById(R.id.btn_dns_reorder)
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
            btnDnsReorder,
            btnAddDnsRule,
            btnResetDnsRules,
            btnPickApp,
            swAppOverride,
        )
        // Экран открывается в покое: список только читают. Заодно это ставит
        // подпись кнопки и прячет «Добавить резолвер».
        setReorderMode(false)
    }

    /**
     * Список резолверов: адаптер, перетаскивание и перехват жеста у ScrollView.
     *
     * Смахивание намеренно выключено (второй аргумент `SimpleCallback` — 0): у
     * строки есть кнопка удаления, а горизонтальный жест внутри прокручиваемого
     * экрана спорит с самой прокруткой.
     */
    private fun bindDnsRulesList() {
        dnsRuleAdapter = DnsRuleAdapter(
            onEdit = { position -> showDnsRuleDialog(position) },
            onToggle = { position, isChecked -> onDnsRuleToggled(position, isChecked) },
            onDelete = { position -> onDnsRuleDeleted(position) },
            onStartDrag = { holder -> dnsRuleTouchHelper.startDrag(holder) },
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
            // Флаги спрашиваем на каждый жест, а не берём из конструктора: вне
            // режима изменения порядка их нет вовсе, так что и прямой
            // `startDrag` по ручке ничего не начнёт, и жест, застигнутый
            // выключением режима, оборвётся сам.
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (!reorderMode) {
                ItemTouchHelper.Callback.makeMovementFlags(0, 0)
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

    /**
     * Перетаскивание — отдельный режим, а не постоянное свойство списка.
     *
     * Ручка стояла у каждой строки всегда, и палец, опустившийся на левый край
     * ради прокрутки, менял порядок опроса резолверов молча: список ростом в
     * семь строк живёт в окне на 240dp, то есть прокручивается всегда. Теперь
     * тянуть можно только тогда, когда об этом попросили.
     */
    private fun setReorderMode(enabled: Boolean) {
        val changed = reorderMode != enabled
        reorderMode = enabled
        dnsRuleAdapter.reorderEnabled = enabled
        btnDnsReorder.text = if (enabled) "Готово" else "Изменить порядок"
        // «Добавить резолвер» живёт в том же режиме: экран в покое — только список.
        btnAddDnsRule.visibility = if (enabled) View.VISIBLE else View.GONE
        // Журналим только настоящее переключение: этот же метод ставит начальное
        // состояние при открытии экрана и снимает режим в [onPause], и без
        // проверки в журнал уходила бы строка на каждое открытие настроек.
        if (!changed) return
        LogManager.log(
            if (enabled) "DNS-правила: включён режим изменения порядка."
            else "DNS-правила: режим изменения порядка выключен."
        )
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
        btnDnsReorder.setOnClickListener {
            // Тот же заслон, что у остальных правок списка: до конца чтения
            // файла порядок менять не на чем, и нажатие пропало бы молча.
            if (!dnsRulesReady("режим изменения порядка")) return@setOnClickListener
            setReorderMode(!reorderMode)
        }
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
        renderExclusiveActionState()
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
            "${if (isChecked) "включено" else "выключено"} ${DnsRuleAdapter.transportLabel(rule)} ${rule.value}"
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
        if (dnsRuleAdapter.itemCount <= 1) {
            Toast.makeText(
                this,
                "Нужен хотя бы один резолвер: пустой список некому опрашивать.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val removed = dnsRuleAdapter.removeAt(position) ?: return
        persistDnsRules("удалено ${DnsRuleAdapter.transportLabel(removed)} ${removed.value}")
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
                DnsRule.Transport.ANY -> "https://dns.example.com/dns-query или tls://dns.example.com"
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
            .setTitle(if (existing == null) "Новый резолвер" else "Изменить резолвер")
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
                val transport = selectedTransport()
                val raw = valueField.text?.toString()
                val kind = selectedKind(raw)
                val value = DnsRule.normalizeValue(kind, raw)
                if (value == null) {
                    // Текст один на все три кнопки: вид больше не следует за
                    // кнопкой, и «Для DoT нужен адрес вида tls://…» требовал бы
                    // того, чего код уже не требует.
                    valueError.text = "Нужен адрес вида https://host/dns-query или tls://host"
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
                    dnsRuleAdapter.addItem(created)
                    rvDnsRules.post { rvDnsRules.scrollToPosition(dnsRuleAdapter.itemCount - 1) }
                    persistDnsRules("добавлено ${DnsRuleAdapter.transportLabel(created)} $value")
                } else {
                    val updated = existing.copy(
                        kind = kind,
                        value = value,
                        bootstrap = addresses,
                        transport = transport,
                    )
                    dnsRuleAdapter.replaceAt(position, updated)
                    persistDnsRules("изменено ${DnsRuleAdapter.transportLabel(updated)} $value")
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDnsRulesResetDialog() {
        if (!dnsRulesReady("сброс к умолчаниям")) return
        AlertDialog.Builder(this)
            .setTitle("Вернуть резолверы по умолчанию?")
            .setMessage(
                "Список заменится штатным: наш резолвер, затем Comss, GeoHide, Xbox, " +
                    "Cloudflare и Google — все шифрованные, — и последней ступенью " +
                    "открытый резолвер провайдера. Ваши правила и их порядок пропадут."
            )
            .setPositiveButton("Сбросить") { _, _ ->
                dnsRuleAdapter.submit(DnsRulesStore.defaults())
                persistDnsRules("сброс к умолчаниям")
                Toast.makeText(this, "Список резолверов сброшен.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Сначала включите DNS для приложения.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedOverridePackage.isBlank()) {
            Toast.makeText(this, "Сначала выберите приложение.", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Правило DNS для приложения отменено.", Toast.LENGTH_LONG).show()
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
