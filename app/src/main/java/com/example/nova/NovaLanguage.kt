package com.example.nova

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.text.Spanned
import android.text.TextUtils
import android.util.AtomicFile
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Язык интерфейса: выбор, хранение и перевод текста в момент показа.
 *
 * ## Как устроено
 *
 * Код продолжает ставить русский текст — это исходник, и по нему ищут строки в
 * логах (I14). Перевод подставляется там, где текст попадает на экран:
 *
 *  * виды из разметки создаёт [NovaViewInflater] — это наши наследники `TextView`,
 *    `Button`, `RadioButton`, `CheckBox`, `Switch`, и каждый их `setText` проходит
 *    через [onSetText]. Исходник запоминается в теге вида;
 *  * всё, что создано кодом, и подписи/описания обходит [localizeTree]: после каждой
 *    раскладки экрана и при смене языка;
 *  * окна диалогов подключаются через [watchWindow] (его зовёт `NovaDialogs.style`);
 *  * всплывающие сообщения, уведомления, виджет — явным [tr].
 *
 * Смена языка ничего не пересоздаёт: у каждого вида в теге лежит русский
 * исходник, и обход просто переводит его заново. Так главный экран не теряет ни
 * анимацию, ни состояние подключения.
 *
 * ## Где хранится выбор
 *
 * В файле `ui_language`, а не в `SharedPreferences`: уведомление строит процесс
 * `:vpn`, а настройки кэшируются попроцессно (I2). Нет файла — выбора не было, и
 * язык берётся из системы: русская система → русский, любая другая → английский
 * (из списка языков системы берётся первый, который мы умеем).
 *
 * Пишет файл только основной процесс. `:vpn` перечитывает его не чаще раза в пару
 * секунд, а живую службу вдобавок будит [EXTRA_UI_LANGUAGE] в
 * `ACTION_REFRESH_NOTIFICATION` (I19).
 */
object NovaLanguage {

    /**
     * Поддерживаемые языки. Новый язык — новая строка здесь и каталог
     * `assets/i18n/<code>.json`, собранный `tools/i18n/extract_ru_strings.py`.
     *
     * Названия языков — на самих этих языках и никогда не переводятся: человек,
     * случайно включивший чужой язык, должен узнать свой в списке.
     */
    enum class Language(val code: String, val badge: String, val nativeName: String) {
        RU("ru", "RU", "Русский"),
        EN("en", "EN", "English"),
    }

    /** Язык, на котором написан сам интерфейс. Каталога у него нет — текст идёт как есть. */
    val SOURCE = Language.RU

    /** Код выбранного языка в `ACTION_REFRESH_NOTIFICATION` для процесса `:vpn`. */
    const val EXTRA_UI_LANGUAGE = "com.example.nova.extra.UI_LANGUAGE"

    fun interface Listener {
        fun onLanguageChanged(language: Language)
    }

    private const val TAG = "NovaI18n"
    private const val CHOICE_FILE = "ui_language"
    private const val DISK_CHECK_INTERVAL_MS = 2_000L

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    /** Язык, которым переводится всё прямо сейчас. */
    @Volatile
    private var active: Language = SOURCE

    /** Явный выбор человека; `null` — следуем системе. */
    @Volatile
    private var explicitChoice: Language? = null

    /** Процесс не пишет выбор сам и обязан замечать чужую запись (`:vpn`). */
    @Volatile
    private var followsForeignWrites = false

    @Volatile
    private var choiceStamp = Long.MIN_VALUE

    @Volatile
    private var nextDiskCheckAtMs = 0L

    /**
     * Номер смены языка. Экран, переведённый при другом номере, при возврате
     * переводится заново.
     */
    @Volatile
    var epoch = 0
        private set

    // Читается без блокировки с любого потока (уведомление в `:vpn`), пишется под
    // `catalogLock`: атомарный массив, чтобы опубликованный каталог был виден сразу.
    private val catalogs = AtomicReferenceArray<NovaTranslationCatalog?>(Language.values().size)
    private val catalogLock = Any()
    private val cache = LruCache<String, String>(768)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaLanguageWrite").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = LinkedHashSet<Listener>()
    private val reportedMisses = HashSet<String>()

    // ---------------------------------------------------------------- выбор языка

    /** Текущий язык интерфейса. */
    fun current(context: Context): Language {
        ensureInitialized(context)
        refreshFromDiskIfDue()
        return active
    }

    /**
     * Выбор человека: сохраняет, переводит все живые экраны сразу же и будит
     * остальные поверхности — уведомление в `:vpn`, виджет, плитку.
     *
     * Только основной процесс и только главный поток.
     */
    fun select(context: Context, language: Language) {
        ensureInitialized(context)
        val changed = language != active
        explicitChoice = language
        persistChoice(context.applicationContext, language)
        if (!changed) return
        applyActive(language)
        // Экран, с которого выбирают, переводится в любом случае — даже если его
        // забыли подключить через [install] и в списке живых экранов его нет.
        if (context is Activity && context !in trackedActivities) localizeActivity(context)
        retranslateTrackedActivities()
        propagateToOtherSurfaces(context.applicationContext, language)
        listeners.toList().forEach { runCatching { it.onLanguageChanged(language) } }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /**
     * Процессу `:vpn`: выбор пишет основной процесс, а этот должен его замечать.
     */
    fun followForeignWrites(context: Context) {
        ensureInitialized(context)
        followsForeignWrites = true
    }

    /**
     * Язык из `ACTION_REFRESH_NOTIFICATION`. Файл к этому моменту уже записан или
     * вот-вот будет, но ждать его незачем: намерение пришло ровно ради этого.
     */
    fun applyFromIntent(context: Context, intent: Intent?) {
        val code = intent?.getStringExtra(EXTRA_UI_LANGUAGE) ?: return
        val language = Language.values().firstOrNull { it.code == code } ?: return
        ensureInitialized(context)
        // Отметка файла намеренно не сбрасывается: запись могла ещё не закончиться,
        // и перечитанный старый файл откатил бы выбор. Новая отметка появится сама,
        // когда запись дойдёт до диска.
        explicitChoice = language
        if (language != active) applyActive(language)
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext ?: context
            appContext = app
            val stored = readChoice(app)
            explicitChoice = stored
            active = stored ?: systemLanguage()
            initialized = true
        }
        prewarmCatalog(active)
    }

    /**
     * Первый поддерживаемый язык из списка языков системы; ни одного — английский.
     */
    fun systemLanguage(): Language {
        val locales = Resources.getSystem().configuration.locales
        for (index in 0 until locales.size()) {
            val code = locales[index]?.language ?: continue
            Language.values().firstOrNull { it.code == code }?.let { return it }
        }
        return Language.EN
    }

    private fun applyActive(language: Language) {
        active = language
        epoch++
        cache.evictAll()
        prewarmCatalog(language)
    }

    /**
     * Проверка чужой записи в `:vpn`. `stat` раз в пару секунд, не чаще: перевод
     * зовётся на каждое уведомление.
     */
    private fun refreshFromDiskIfDue() {
        if (!followsForeignWrites) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextDiskCheckAtMs) return
        nextDiskCheckAtMs = now + DISK_CHECK_INTERVAL_MS
        val context = appContext ?: return
        val stamp = runCatching { choiceFile(context).lastModified() }.getOrDefault(0L)
        if (stamp != choiceStamp) {
            explicitChoice = readChoice(context)
        }
        val wanted = explicitChoice ?: systemLanguage()
        if (wanted != active) applyActive(wanted)
    }

    private fun choiceFile(context: Context) = File(context.filesDir, CHOICE_FILE)

    private fun readChoice(context: Context): Language? {
        val file = choiceFile(context)
        return runCatching {
            choiceStamp = file.lastModified()
            if (!file.exists()) return null
            val code = String(AtomicFile(file).readFully(), Charsets.UTF_8).trim()
            Language.values().firstOrNull { it.code == code }
        }.getOrNull()
    }

    /**
     * Запись в отдельном потоке: `AtomicFile` делает `fsync`, а зовут это с нажатия (I13).
     */
    private fun persistChoice(context: Context, language: Language) {
        writer.execute {
            runCatching {
                val file = AtomicFile(choiceFile(context))
                val stream = file.startWrite()
                try {
                    stream.write(language.code.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }.onFailure { error ->
                LogManager.log("Язык интерфейса: не удалось сохранить выбор ${language.code}: ${error.message}")
            }
        }
    }

    /**
     * Уведомление строит `:vpn`, виджет рисует лаунчер, плитку — шторка. Ни одно из
     * них не узнает о смене само, пока не придёт следующее событие.
     */
    private fun propagateToOtherSurfaces(context: Context, language: Language) {
        runCatching { NovaWidgetProvider.refreshAll(context) }
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, NovaTileService::class.java))
        }
        runCatching {
            if (ClientData(context).getServiceState() != NovaVpnService.STATE_STOPPED) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NovaVpnService::class.java).apply {
                        action = NovaVpnService.ACTION_REFRESH_NOTIFICATION
                        putExtra(EXTRA_UI_LANGUAGE, language.code)
                    },
                )
            }
        }
    }

    // ---------------------------------------------------------------- перевод

    /** Перевод строки для явных мест: всплывающие сообщения, уведомления, виджет. */
    fun tr(context: Context, text: String): String = translate(context, text)

    /**
     * То же без контекста — для кода, где его нет (ответы локального прокси). Работает,
     * когда процесс уже подключил язык: основной — любым экраном, `:vpn` — в
     * `NovaVpnService.onCreate`. До этого текст возвращается как есть.
     */
    fun tr(text: String): String = translate(null, text)

    /** То же для `CharSequence`: оформленный текст (со спанами) не трогается. */
    fun tr(context: Context, text: CharSequence): CharSequence {
        if (text is String) return translate(context, text)
        if (text is Spanned && hasSpans(text)) return text
        val source = text.toString()
        val out = translate(context, source)
        return if (out === source) text else out
    }

    private fun translate(context: Context?, text: String): String {
        if (!initialized) {
            ensureInitialized(context ?: return text)
        }
        refreshFromDiskIfDue()
        val language = active
        if (language == SOURCE || text.isEmpty()) return text
        if (!NovaTranslationCatalog.hasCyrillic(text)) return text
        val catalog = catalog(language) ?: return text
        cache.get(text)?.let { return it }
        val out = catalog.translate(text)
        if (out === text) reportMiss(text)
        cache.put(text, out)
        return out
    }

    private fun catalog(language: Language): NovaTranslationCatalog? {
        if (language == SOURCE) return null
        catalogs.get(language.ordinal)?.let { return it }
        val context = appContext ?: return null
        synchronized(catalogLock) {
            catalogs.get(language.ordinal)?.let { return it }
            val loaded = runCatching { loadCatalog(context, language) }
                .onFailure { error ->
                    LogManager.log("Язык интерфейса: каталог ${language.code} не прочитан: ${error.message}")
                }
                .getOrElse { NovaTranslationCatalog.fromEntries(emptyMap(), Locale.ENGLISH) }
            catalogs.set(language.ordinal, loaded)
            return loaded
        }
    }

    private fun loadCatalog(context: Context, language: Language): NovaTranslationCatalog {
        val text = context.assets.open("i18n/${language.code}.json").use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
        val json = JSONObject(text)
        val entries = HashMap<String, String>(json.length() * 2)
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            entries[key] = json.optString(key)
        }
        return NovaTranslationCatalog.fromEntries(entries, Locale(language.code))
    }

    /**
     * Каталог читается заранее в фоне: первый экран иначе ждал бы разбор JSON на
     * главном потоке.
     */
    private fun prewarmCatalog(language: Language) {
        if (language == SOURCE || catalogs.get(language.ordinal) != null) return
        Thread({ catalog(language) }, "NovaLanguageCatalog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Непереведённый текст — в logcat, один раз на текст и только по запросу:
     * `adb shell setprop log.tag.NovaI18n DEBUG`. В журнал приложения не пишется:
     * там он был бы шумом.
     */
    private fun reportMiss(text: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        synchronized(reportedMisses) {
            if (reportedMisses.size > 2_000 || !reportedMisses.add(text)) return
        }
        Log.d(TAG, "missing: " + text.replace("\n", "\\n"))
    }

    private fun hasSpans(text: Spanned): Boolean =
        text.getSpans(0, text.length, Any::class.java).isNotEmpty()

    // ---------------------------------------------------------------- виды

    /**
     * Вид, чей `setText` идёт через [onSetText]. [setTextDirect] — прямой вызов
     * `super.setText`, в обход перевода.
     */
    interface LocalizedText {
        fun setTextDirect(text: CharSequence?, type: TextView.BufferType?)
    }

    /**
     * Тело `setText` у наших видов. Зовётся и из конструктора `TextView`, поэтому
     * пользуется только тегами — полей наследника в этот момент ещё нет.
     */
    fun onSetText(view: TextView, text: CharSequence?, type: TextView.BufferType?) {
        val target = view as LocalizedText
        // Вид получает обратно наш же вывод — исходник не меняется.
        //
        // Так делает EmojiCompat: догрузив шрифт эмодзи, он ставит в вид его же
        // текст, уже со своей разметкой (`EmojiSpan`). Без этой ветки текст со
        // спанами считался новым «оформленным» текстом, русский исходник стирался, и
        // «⚙️ Settings» больше не возвращалась в «⚙️ Настройки» (Mi A1). Тем же
        // путём приходит и `tv.text = tv.text` из кода.
        val storedOutput = view.getTag(R.id.nova_i18n_text_output) as? String
        if (text != null &&
            storedOutput != null &&
            view.getTag(R.id.nova_i18n_text_source) != null &&
            TextUtils.equals(text, storedOutput)
        ) {
            target.setTextDirect(text, type)
            return
        }
        if (text.isNullOrEmpty() ||
            view.getTag(R.id.nova_i18n_verbatim) != null ||
            (text is Spanned && hasSpans(text)) ||
            !NovaTranslationCatalog.hasCyrillic(text)
        ) {
            view.setTag(R.id.nova_i18n_text_source, null)
            view.setTag(R.id.nova_i18n_text_output, null)
            target.setTextDirect(text, type)
            return
        }
        val source = text.toString()
        val out = translate(view.context, source)
        // Тот же текст на том же языке уже стоит — вид не трогаем. `setText` при
        // `wrap_content` уходит в `requestLayout` даже на том же тексте, а код
        // экранов сравнивает подпись с исходником и после перевода видел бы
        // «другой текст» на каждом тике.
        if (source == view.getTag(R.id.nova_i18n_text_source) &&
            out == view.getTag(R.id.nova_i18n_text_output) &&
            TextUtils.equals(view.text, out)
        ) {
            return
        }
        view.setTag(R.id.nova_i18n_text_source, source)
        view.setTag(R.id.nova_i18n_text_output, out)
        target.setTextDirect(out, type)
    }

    /**
     * Русский исходник того, что сейчас показывает вид. Для кода, который сравнивает
     * подпись с литералом.
     */
    fun sourceText(view: TextView): CharSequence? {
        val output = view.getTag(R.id.nova_i18n_text_output) as? String
        val source = view.getTag(R.id.nova_i18n_text_source) as? String
        return if (source != null && TextUtils.equals(view.text, output)) source else view.text
    }

    /**
     * Вид и всё под ним показываются как есть: журнал, тело конфигурации, имена
     * приложений и профилей — чужой текст, совпадение с фразой каталога его бы
     * исказило.
     */
    fun verbatim(view: View?) {
        view?.setTag(R.id.nova_i18n_verbatim, true)
    }

    /**
     * Переводит дерево видов текущим языком. Возвращает `true`, если что-то поменялось.
     */
    fun localizeTree(root: View): Boolean {
        ensureInitialized(root.context)
        return visit(root)
    }

    private fun visit(view: View): Boolean {
        if (view.getTag(R.id.nova_i18n_verbatim) != null) return false
        var changed = false
        if (view is TextView) {
            if (view !is EditText) changed = localizeText(view) or changed
            changed = localizeHint(view) or changed
        }
        changed = localizeContentDescription(view) or changed
        if (view is RecyclerView) watchRecyclerView(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                changed = visit(view.getChildAt(index)) or changed
            }
        }
        return changed
    }

    private fun localizeText(view: TextView): Boolean {
        val current = view.text ?: return false
        if (current.isEmpty()) return false
        val storedSource = view.getTag(R.id.nova_i18n_text_source) as? String
        val storedOutput = view.getTag(R.id.nova_i18n_text_output) as? String
        val source = if (storedSource != null && TextUtils.equals(current, storedOutput)) {
            storedSource
        } else {
            if (current is Spanned && hasSpans(current)) return false
            if (!NovaTranslationCatalog.hasCyrillic(current)) return false
            current.toString()
        }
        val out = translate(view.context, source)
        view.setTag(R.id.nova_i18n_text_source, source)
        view.setTag(R.id.nova_i18n_text_output, out)
        if (TextUtils.equals(current, out)) return false
        if (view is LocalizedText) {
            view.setTextDirect(out, TextView.BufferType.NORMAL)
        } else {
            view.text = out
        }
        return true
    }

    private fun localizeHint(view: TextView): Boolean {
        val current = view.hint ?: return false
        if (current.isEmpty()) return false
        val storedSource = view.getTag(R.id.nova_i18n_hint_source) as? String
        val storedOutput = view.getTag(R.id.nova_i18n_hint_output) as? String
        val source = if (storedSource != null && TextUtils.equals(current, storedOutput)) {
            storedSource
        } else {
            if (!NovaTranslationCatalog.hasCyrillic(current)) return false
            current.toString()
        }
        val out = translate(view.context, source)
        view.setTag(R.id.nova_i18n_hint_source, source)
        view.setTag(R.id.nova_i18n_hint_output, out)
        if (TextUtils.equals(current, out)) return false
        view.hint = out
        return true
    }

    private fun localizeContentDescription(view: View): Boolean {
        val current = view.contentDescription ?: return false
        if (current.isEmpty()) return false
        val storedSource = view.getTag(R.id.nova_i18n_cd_source) as? String
        val storedOutput = view.getTag(R.id.nova_i18n_cd_output) as? String
        val source = if (storedSource != null && TextUtils.equals(current, storedOutput)) {
            storedSource
        } else {
            if (!NovaTranslationCatalog.hasCyrillic(current)) return false
            current.toString()
        }
        val out = translate(view.context, source)
        view.setTag(R.id.nova_i18n_cd_source, source)
        view.setTag(R.id.nova_i18n_cd_output, out)
        if (TextUtils.equals(current, out)) return false
        view.contentDescription = out
        return true
    }

    /**
     * Строки списка, появившиеся при прокрутке, общей раскладки экрана не вызывают:
     * `RecyclerView` раскладывает их сам. Без этого описания и подсказки строк,
     * докрученных до экрана, оставались русскими (DNS-правила, Mi A1).
     */
    private fun watchRecyclerView(list: RecyclerView) {
        if (list.getTag(R.id.nova_i18n_window_watch) != null) return
        list.setTag(R.id.nova_i18n_window_watch, true)
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    localizeTree(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            },
        )
    }

    // ---------------------------------------------------------------- окна

    /**
     * Окно диалога: переводится сразу и перед каждой отрисовкой, пока что-то
     * меняется. Пункты списка появляются уже после показа, при раскладке, — их
     * ловит только проверка перед кадром.
     *
     * Кадр с изменившимся текстом отменяется, чтобы русский текст не мелькнул, но не
     * два подряд: текст, который меняется каждый кадр, иначе не нарисовался бы никогда.
     */
    fun watchWindow(window: Window?) {
        val decor = window?.decorView ?: return
        if (decor.getTag(R.id.nova_i18n_window_watch) != null) return
        decor.setTag(R.id.nova_i18n_window_watch, true)
        localizeTree(decor)
        var cancelledLastFrame = false
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val changed = localizeTree(decor)
                    if (changed && !cancelledLastFrame) {
                        cancelledLastFrame = true
                        return false
                    }
                    cancelledLastFrame = false
                    return true
                }
            },
        )
    }

    // ---------------------------------------------------------------- экраны

    private val trackedActivities = WeakHashMap<Activity, Int>()
    private val activityTitles = WeakHashMap<Activity, CharSequence>()

    @Volatile
    private var callbacksInstalled = false

    /**
     * Подключает экран. Зовётся из `NovaTheme.apply` — до `super.onCreate`, поэтому
     * `onActivityCreated` первого же экрана доходит до обработчиков.
     */
    fun install(activity: Activity) {
        ensureInitialized(activity)
        if (callbacksInstalled) return
        synchronized(lock) {
            if (callbacksInstalled) return
            callbacksInstalled = true
        }
        val application = activity.application
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
        application.registerComponentCallbacks(configurationCallbacks)
    }

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            trackedActivities[activity] = -1
        }

        override fun onActivityStarted(activity: Activity) {
            attachLayoutWalker(activity)
            if (trackedActivities[activity] != epoch) localizeActivity(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            if (trackedActivities[activity] != epoch) localizeActivity(activity)
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            trackedActivities.remove(activity)
            activityTitles.remove(activity)
        }
    }

    /**
     * Смена языка системы, пока человек сам язык не выбирал: интерфейс следует за
     * системой без перезапуска.
     */
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if (explicitChoice != null) return
            val wanted = systemLanguage()
            if (wanted == active) return
            mainHandler.post {
                applyActive(wanted)
                retranslateTrackedActivities()
                listeners.toList().forEach { runCatching { it.onLanguageChanged(wanted) } }
            }
        }

        @Deprecated("ComponentCallbacks")
        override fun onLowMemory() = Unit
    }

    /**
     * После каждой раскладки — обход экрана. Он ловит виды, созданные кодом (кнопки
     * подрегиона, строки списков), и подписи полей. Обход дешёвый: у уже переведённого
     * вида это два чтения тега.
     */
    private fun attachLayoutWalker(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        if (decor.getTag(R.id.nova_i18n_window_watch) != null) return
        decor.setTag(R.id.nova_i18n_window_watch, true)
        decor.viewTreeObserver.addOnGlobalLayoutListener { localizeTree(decor) }
    }

    private fun localizeActivity(activity: Activity) {
        trackedActivities[activity] = epoch
        val source = activityTitles[activity] ?: activity.title?.also { activityTitles[activity] = it }
        if (source != null && NovaTranslationCatalog.hasCyrillic(source)) {
            activity.title = tr(activity, source)
        }
        activity.window?.decorView?.let { localizeTree(it) }
    }

    private fun retranslateTrackedActivities() {
        trackedActivities.keys.toList().forEach { activity ->
            if (!activity.isDestroyed) localizeActivity(activity)
        }
    }
}
