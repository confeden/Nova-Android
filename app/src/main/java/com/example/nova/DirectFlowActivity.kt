package com.example.nova

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Экран «Прямой поток»: кто идёт мимо туннеля всегда.
 *
 * Отдельный Activity, а не блок в общих настройках, потому что здесь живёт
 * список всех установленных приложений с поиском — ему нужна полная высота
 * экрана и собственный жизненный цикл загрузки.
 *
 * Правило действует в любом режиме раздельного туннелирования, поэтому экран
 * намеренно отвязан от переключателя режимов: пользователь настраивает его один
 * раз и не возвращается сюда при смене режима.
 */
class DirectFlowActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData

    private lateinit var swRussian: Switch
    private lateinit var swShowSystem: Switch
    private lateinit var etSearch: EditText
    private lateinit var tvSummary: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: DirectFlowAppAdapter

    private var allApps: List<AppItem> = emptyList()

    /**
     * Свой выбор пользователя — то, что он отметил руками поверх закрытого списка.
     *
     * Держится в памяти отдельно от хранилища, потому что список приложений
     * пересобирается асинхронно и обращаться к SharedPreferences на каждую строку
     * при прокрутке незачем.
     */
    private val customDirect: MutableSet<String> = mutableSetOf()

    /**
     * Что человек снял с закрытого списка.
     *
     * Отдельный набор, а не удаление из [RussianDirectApps]: список зашит в
     * приложение и пополняется с обновлениями, а снятое должно пережить их и не
     * вернуться само.
     */
    private val curatedExcluded: MutableSet<String> = mutableSetOf()

    /**
     * Флаг подавления рекурсии: программная установка состояния переключателей
     * поднимает их слушателей, а те записали бы в хранилище то, что оттуда
     * только что прочитали.
     */
    private var suppressListeners = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val reapplyHandler = Handler(Looper.getMainLooper())

    /** Стоит ли в очереди отложенное применение — нужно, чтобы не потерять его при уходе с экрана. */
    private var reapplyPending = false

    private val reapplyRunnable = Runnable {
        reapplyPending = false
        if (isFinishing || isDestroyed) return@Runnable
        runReapply()
    }

    /**
     * Отдаёт новые правила живому сеансу — без переподключения.
     */
    private fun runReapply() {
        if (!SessionReapply.applyToLiveSession(this, clientData)) {
            // Молчать нельзя: пользователь уже видит галочку на своём месте и
            // считает, что правило работает, — а туннель остался прежним.
            Toast.makeText(
                this,
                "Не удалось применить изменения к текущему сеансу",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Контекст логгера нужно передавать до любого обращения к LogManager
        LogManager.setAppContext(this)
        setContentView(R.layout.activity_direct_flow)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)
        customDirect.addAll(clientData.getDirectApps())
        curatedExcluded.addAll(clientData.getDirectAppsExcluded())

        swRussian = findViewById(R.id.sw_direct_russian)
        swShowSystem = findViewById(R.id.sw_direct_show_system)
        etSearch = findViewById(R.id.et_direct_search)
        tvSummary = findViewById(R.id.tv_direct_summary)
        rvApps = findViewById(R.id.rv_direct_apps)

        initViews()
        attachListeners()
        loadApps()
    }

    /** Начальное состояние переключателей и списка — строго из хранилища. */
    private fun initViews() {
        suppressListeners = true

        adapter = DirectFlowAppAdapter { pkg, isChecked -> onCustomToggled(pkg, isChecked) }
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.setHasFixedSize(true)
        rvApps.adapter = adapter

        val russianEnabled = clientData.isRussianDirectAppsEnabled()
        swRussian.isChecked = russianEnabled
        swShowSystem.isChecked = clientData.isShowSystemAppsEnabled()

        applyCuratedEnabledState(russianEnabled)
        updateSummary()

        suppressListeners = false
    }

    /**
     * Подключает слушатели. Каждое изменение сохраняется немедленно, чтобы
     * внезапное завершение Activity не отменило уже сделанный выбор.
     */
    private fun attachListeners() {
        swRussian.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            clientData.setRussianDirectAppsEnabled(isChecked)
            DirectAppsPolicy.invalidate()
            applyCuratedEnabledState(isChecked)
            LogManager.log("DirectFlow: российский список → $isChecked")
            scheduleReapply()
        }

        swShowSystem.setOnCheckedChangeListener { _, isChecked ->
            if (suppressListeners) return@setOnCheckedChangeListener
            // Ключ тот же, что у списка раздельного туннелирования, и это
            // осознанно: два списка приложений с разными наборами строк
            // выглядели бы как ошибка.
            clientData.setShowSystemAppsEnabled(isChecked)
            submitFilteredApps()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                submitFilteredApps(s?.toString().orEmpty())
            }
        })
    }

    /**
     * Первая отрисовка идёт из кэша, чтобы экран не открывался пустым, а полный
     * список догоняет её из фонового опроса `PackageManager`.
     */
    private fun loadApps() {
        AppCacheManager.prewarmAsync(this)

        val cached = AppCacheManager.peekInstalledApps(this, customDirect)
        if (cached.isNotEmpty()) {
            allApps = markCurated(cached)
            submitFilteredApps()
            updateSummary()
        }

        scope.launch {
            allApps = markCurated(AppCacheManager.getInstalledApps(this@DirectFlowActivity, customDirect))
            submitFilteredApps()
            updateSummary()
        }
    }

    /**
     * Помечает строки закрытого списка.
     *
     * Намеренно по [RussianDirectApps.contains], а не по [DirectAppsPolicy.resolve]:
     * тот отдаёт пустое множество при выключенном мастер-переключателе и отбрасывает
     * банки, поставленные из Play. То есть ровно те строки пропали бы с экрана,
     * который про них и рассказывает, — а объяснить их можно только показав.
     */
    private fun markCurated(items: List<AppItem>): List<AppItem> {
        items.forEach { item ->
            val curated = RussianDirectApps.contains(item.packageName)
            item.isDirect = curated
            // Галочка строки закрытого списка показывает не «выбрано
            // пользователем», а «действует»: снятое живёт вычитанием, и без
            // этой строки снятое приложение открывалось бы отмеченным.
            if (curated) item.isSelected = item.packageName !in curatedExcluded
        }
        return items
    }

    /**
     * Фильтрует и сортирует список.
     *
     * Строки закрытого списка идут первыми: экран прежде всего объясняет, кто уже
     * уходит мимо туннеля без участия пользователя.
     */
    private fun submitFilteredApps(query: String = etSearch.text?.toString().orEmpty()) {
        if (!::adapter.isInitialized) return

        val lowered = query.lowercase()

        val showSystem = clientData.isShowSystemAppsEnabled()

        val filtered = if (lowered.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(lowered) || it.packageName.lowercase().contains(lowered) }
        }.filter { item ->
            // Уже отмеченное приложение и строка закрытого списка не исчезают,
            // даже если они системные: иначе выбор остался бы без видимой строки,
            // и снять его было бы нечем.
            showSystem || !item.isSystem || item.isSelected || item.isDirect
        }.sortedWith(
            compareByDescending<AppItem> { it.isDirect }
                .thenByDescending { it.isSelected }
                .thenBy { it.label.lowercase() }
        )

        adapter.setData(filtered)
    }

    /**
     * Пользователь отметил или снял своё приложение.
     */
    private fun onCustomToggled(packageName: String, isChecked: Boolean) {
        // Строки закрытого списка и свои приложения хранятся по-разному: первые
        // вычитанием, вторые перечислением. Решает происхождение пакета, а не
        // то, что было нарисовано в строке.
        if (RussianDirectApps.contains(packageName)) {
            if (isChecked) curatedExcluded.remove(packageName) else curatedExcluded.add(packageName)
            clientData.setDirectAppsExcluded(curatedExcluded.toSet())
        } else if (isChecked) {
            customDirect.add(packageName)

            // Режим 1 раздельного туннелирования вычитает прямые пакеты из своего
            // разрешающего списка. Останься приложение и там — его галочка на
            // экране раздельного туннелирования была бы включена и неактивна,
            // обещая туннель, которого уже не будет. Снимаем сразу.
            val split = clientData.getSplitApps()
            if (packageName in split) {
                clientData.setSplitApps(split - packageName)
            }
            clientData.setDirectApps(customDirect.toSet())
        } else {
            customDirect.remove(packageName)
            clientData.setDirectApps(customDirect.toSet())
        }

        DirectAppsPolicy.invalidate()

        updateSummary()
        scheduleReapply()
    }

    /**
     * Гасит строки закрытого списка при выключенном мастер-переключателе.
     *
     * Свой выбор пользователя от этого переключателя не зависит и не гаснет —
     * он живёт своей настройкой.
     */
    private fun applyCuratedEnabledState(enabled: Boolean) {
        adapter.setCuratedActive(enabled)
        tvSummary.alpha = if (enabled) 1f else 0.45f
    }

    /** Сводка считается по загруженному списку, чтобы показывать только реально установленное. */
    private fun updateSummary() {
        val curatedInstalled = allApps.count { it.isDirect && it.packageName !in curatedExcluded }
        val removed = allApps.count { it.isDirect && it.packageName in curatedExcluded }
        val base = "Из списка: $curatedInstalled · своих: ${customDirect.size}"
        // Про снятое пишем, только когда оно есть: строка «снято: 0» на пустом
        // месте предлагала бы искать то, чего не делали.
        tvSummary.text = if (removed > 0) "$base · снято: $removed" else base
    }

    /**
     * Применяет изменения к идущему сеансу с задержкой.
     *
     * Отмечая несколько приложений подряд, пользователь пересобрал бы туннель
     * столько же раз; пауза склеивает серию нажатий в один реаплай.
     */
    private fun scheduleReapply() {
        if (!SessionReapply.isSessionLikelyActive(this, clientData)) return
        reapplyHandler.removeCallbacks(reapplyRunnable)
        reapplyPending = true
        reapplyHandler.postDelayed(reapplyRunnable, REAPPLY_DELAY_MS)
    }

    override fun onPause() {
        super.onPause()
        // Уход с экрана — это конец правки, и отложенный реаплай обязан случиться
        // прямо здесь. `onDestroy` снимает его с очереди, а между `onPause` и
        // `onDestroy` главный поток очередь не разбирает: по кнопке «назад»
        // отложенный вызов не выполнился бы никогда.
        if (reapplyPending) {
            reapplyHandler.removeCallbacks(reapplyRunnable)
            reapplyPending = false
            runReapply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reapplyHandler.removeCallbacks(reapplyRunnable)
        scope.cancel()
    }

    private companion object {
        /** Столько же, сколько у списка раздельного туннелирования, — ощущение отклика одно. */
        const val REAPPLY_DELAY_MS = 650L
    }
}
