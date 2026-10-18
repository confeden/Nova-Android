package com.example.nova

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Строка списка импортированных профилей. [id] совпадает с ключом отключения в ClientData. */
data class ImportedProfileRow(
    val id: String,
    val title: String,
    val host: String,
    val port: Int,
    val tcp: Boolean,
) {
    val target: ImportedProfilePingStore.Target
        get() = ImportedProfilePingStore.Target(id, host, port, tcp)
}

/** Состояние строки для адаптера: что показать, включая замер и отключение. */
data class ImportedProfileRowState(
    val row: ImportedProfileRow,
    val pingMs: Int?,
    val disabled: Boolean,
    /** Профиль, на котором сейчас держится туннель: подсвечивается акцентом темы. */
    val active: Boolean = false,
)

/**
 * Все импортированные профили: VLESS из своего хранилища и AWG/WARP с пометкой
 * `userImported`. Профили Proton и собственные профили Nova в список не входят:
 * их вносит не пользователь.
 */
object ImportedProfileCatalog {
    fun load(clientData: ClientData): List<ImportedProfileRow> {
        val vless = clientData.getVlessProfilesAsConfigs().map { config ->
            ImportedProfileRow(
                id = config.id,
                title = "${config.endpointSource.ifBlank { "${config.host}:${config.port}" }} • ${config.mode}",
                host = config.host,
                port = config.port,
                tcp = true,
            )
        }
        val wireguard = clientData.getWarpVerifiedConfigs()
            .filter {
                it.userImported &&
                    !it.engine.equals("masque", ignoreCase = true) &&
                    it.endpointSource != ProtonProfileStore.ENDPOINT_SOURCE &&
                    it.endpointSource != WarpGeneratedStore.ENDPOINT_SOURCE
            }
            .map { config ->
                ImportedProfileRow(
                    id = config.id,
                    title = "USER • ${config.mode} @ ${config.host}:${config.port}",
                    host = config.host,
                    port = config.port,
                    tcp = false,
                )
            }
        return vless + wireguard
    }
}

/**
 * Список импортированных профилей в диалоге, открываемом из главного экрана.
 *
 * Поток данных: кэш замеров и отключённые показываются сразу; устаревшие (старше часа)
 * перемеряются; кнопка «Проверить» перемеряет всё. Результаты ложатся в адаптер
 * через [publish]: при сортировке порядок пересчитывается не чаще раза в секунду
 * ([ThrottleGate]), а текст пингов обновляется сразу.
 */
class ImportedProfilesSheet(
    private val activity: Activity,
    private val clientData: ClientData,
    /** Профиль, активный сейчас (null, если туннель не поднят). Берётся снаружи: знает экран. */
    private val activeIdProvider: () -> String?,
    /** Выбор строки: диалог уже закрыт, профиль включён, если был отключён. */
    private val onSelect: (ImportedProfileRow) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val sortGate = ThrottleGate(ImportedProfileRules.SORT_INTERVAL_MS)
    private val pings = HashMap<String, Int>()
    private val measuredAt = HashMap<String, Long>()
    private var rows: List<ImportedProfileRow> = emptyList()
    private var order: List<String> = emptyList()
    private var disabled: Set<String> = emptySet()
    private var activeId: String? = null
    private var sortByLatency = false
    private var sortScheduled = false
    private var probing: Job? = null
    private var closed = false
    private var dialog: AlertDialog? = null

    private lateinit var title: TextView
    private lateinit var checkButton: TextView
    private lateinit var sortButton: TextView
    private lateinit var emptyView: TextView
    private lateinit var listView: RecyclerView
    private lateinit var adapter: RowAdapter

    fun show() {
        val view = buildView()
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(NovaLanguage.tr(activity, "Закрыть"), null)
            .setOnDismissListener { close() }
            .showNova()
        scope.launch {
            val (loaded, disabledIds, cached) = withContext(Dispatchers.IO) {
                Triple(
                    ImportedProfileCatalog.load(clientData),
                    clientData.disabledImportedProfileIds(),
                    ImportedProfilePingStore.readAll(activity),
                )
            }
            rows = loaded
            disabled = disabledIds
            for ((id, entry) in cached) {
                pings[id] = entry.pingMs
                measuredAt[id] = entry.measuredAt
            }
            publish(resort = true)
            val now = System.currentTimeMillis()
            startProbe(rows.filter { ImportedProfileRules.isStale(measuredAt[it.id], now) })
        }
    }

    private fun close() {
        if (closed) return
        closed = true
        probing?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        onClosed()
    }

    private fun startProbe(targets: List<ImportedProfileRow>) {
        if (targets.isEmpty() || probing?.isActive == true) return
        probing = scope.launch {
            setChecking(true)
            val network = withContext(Dispatchers.IO) { ImportedProfilePingStore.underlyingNetwork(activity) }
            ImportedProfilePingStore.measureAll(network, targets.map { it.target }) { id, ms ->
                val now = System.currentTimeMillis()
                pings[id] = ms
                measuredAt[id] = now
                ImportedProfilePingStore.writeAsync(activity, id, ms, now)
                onProbeResult()
            }
            setChecking(false)
            publish(resort = true)
        }
    }

    /** Результат замера: текст пингов — сразу, порядок — не чаще [ImportedProfileRules.SORT_INTERVAL_MS]. */
    private fun onProbeResult() {
        if (!sortByLatency) {
            publish(resort = false)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val wait = sortGate.delayUntilAllowed(now)
        if (wait == 0L) {
            sortGate.markRan(now)
            publish(resort = true)
            return
        }
        publish(resort = false)
        if (!sortScheduled) {
            sortScheduled = true
            handler.postDelayed({
                sortScheduled = false
                if (closed) return@postDelayed
                sortGate.markRan(SystemClock.elapsedRealtime())
                publish(resort = true)
            }, wait)
        }
    }

    private fun publish(resort: Boolean) {
        if (resort || !sortByLatency) {
            order = if (sortByLatency) {
                ImportedProfileRules.sortByLatency(rows) { pings[it.id] }.map { it.id }
            } else {
                rows.map { it.id }
            }
        }
        activeId = activeIdProvider()
        val byId = rows.associateBy { it.id }
        val states = order.mapNotNull { id ->
            byId[id]?.let { ImportedProfileRowState(it, pings[id], id in disabled, id == activeId) }
        }
        // После пересортировки список встаёт в начало: самые быстрые профили видны сразу.
        // Во время перетаскивания пальцем не трогаем — человек сам листает.
        val toTop = resort && sortByLatency &&
            listView.scrollState != RecyclerView.SCROLL_STATE_DRAGGING
        adapter.submitList(states) {
            if (toTop) listView.scrollToPosition(0)
        }
        val count = ImportedProfileRules.formatCount(rows.size, NovaLanguage.tr(activity, "к"))
        title.text = NovaLanguage.tr(activity, "Импортированные профили ({0})").replace("{0}", count)
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onToggle(row: ImportedProfileRow) {
        val nowDisabled = row.id !in disabled
        disabled = if (nowDisabled) disabled + row.id else disabled - row.id
        publish(resort = false)
        ImportedProfilePingStore.persistDisabledAsync(clientData, row.id, nowDisabled)
    }

    /**
     * Нажатие на строку: включить профиль, если он был отключён, закрыть список и
     * передать выбор дальше. Включение пишется синхронно: сразу после него экран
     * поднимает туннель, и отключённый профиль туда попасть не должен.
     */
    private fun onRowTap(state: ImportedProfileRowState) {
        if (state.disabled) {
            clientData.setImportedProfileDisabled(state.row.id, false)
            disabled = disabled - state.row.id
        }
        dialog?.dismiss()
        onSelect(state.row)
    }

    private fun setChecking(on: Boolean) {
        checkButton.isEnabled = !on
        checkButton.alpha = if (on) 0.5f else 1f
        checkButton.text = NovaLanguage.tr(activity, if (on) "Проверка…" else "Проверить")
    }

    private fun updateSortButton() {
        val label = NovaLanguage.tr(activity, "По задержке")
        sortButton.text = if (sortByLatency) "$label ✓" else label
    }

    private fun buildView(): View {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val palette = Palette.from(ctx)

        title = TextView(ctx).apply {
            textSize = 15f
            setTextColor(palette.title)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // Заголовок в одну строку: на узком экране сжимается текст, а не переносится.
            // Сжатие до размера — API 26+; ниже он просто обрезается многоточием.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 11, 15, 1, TypedValue.COMPLEX_UNIT_SP,
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        checkButton = pill(ctx, palette, ::dp).apply {
            text = NovaLanguage.tr(ctx, "Проверить")
            contentDescription = NovaLanguage.tr(ctx, "Проверить все профили")
            setOnClickListener { startProbe(rows) }
        }
        sortButton = pill(ctx, palette, ::dp).apply {
            contentDescription = NovaLanguage.tr(ctx, "Сортировать по задержке")
            setOnClickListener {
                sortByLatency = !sortByLatency
                updateSortButton()
                publish(resort = true)
            }
        }
        updateSortButton()

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(checkButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(sortButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }

        emptyView = TextView(ctx).apply {
            text = NovaLanguage.tr(ctx, "Нет импортированных профилей")
            setTextColor(palette.hint)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }

        adapter = RowAdapter(palette, density, onToggle = { row -> onToggle(row) }, onTap = { state -> onRowTap(state) })
        // Разметка списка — в view_imported_profiles_list: там постоянная полоса прокрутки
        // и её цвета из темы. Остальное (высота, адаптер) задаётся здесь.
        listView = LayoutInflater.from(ctx).inflate(R.layout.view_imported_profiles_list, null) as RecyclerView
        listView.apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@ImportedProfilesSheet.adapter
            itemAnimator = null
            isNestedScrollingEnabled = true
            val maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, minOf(maxHeight, dp(460)))
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(header)
            addView(emptyView)
            addView(listView)
        }
    }

    private fun pill(ctx: Context, palette: Palette, dp: (Int) -> Int): TextView = TextView(ctx).apply {
        setBackgroundResource(R.drawable.bg_region_chip)
        setTextColor(palette.title)
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isClickable = true
        isFocusable = true
    }

    /** Цвета берутся из темы, как у карточек и кнопок главного экрана. */
    private class Palette(val title: Int, val value: Int, val hint: Int, val accent: Int) {
        companion object {
            fun from(ctx: Context): Palette = Palette(
                title = attrColor(ctx, R.attr.novaTextTitle, 0xFFFFFFFF.toInt()),
                value = attrColor(ctx, R.attr.novaTextValue, 0xFFB9C4D6.toInt()),
                hint = attrColor(ctx, R.attr.novaTextHint, 0xFF8A94A6.toInt()),
                accent = attrColor(ctx, R.attr.novaAccent, 0xFF7AA2FF.toInt()),
            )

            private fun attrColor(ctx: Context, attr: Int, fallback: Int): Int {
                val value = TypedValue()
                if (!ctx.theme.resolveAttribute(attr, value, true)) return fallback
                return when {
                    value.resourceId != 0 -> ContextCompat.getColor(ctx, value.resourceId)
                    value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
                    else -> fallback
                }
            }
        }
    }

    private class RowHolder(
        val container: LinearLayout,
        val toggle: TextView,
        val name: TextView,
        val ping: TextView,
    ) : RecyclerView.ViewHolder(container)

    /**
     * Адаптер на ListAdapter: DiffUtil меняет только изменившиеся строки, поэтому
     * список не мигает и позиция прокрутки сохраняется. Идентификаторы стабильны.
     */
    private class RowAdapter(
        private val palette: Palette,
        private val density: Float,
        private val onToggle: (ImportedProfileRow) -> Unit,
        private val onTap: (ImportedProfileRowState) -> Unit,
    ) : ListAdapter<ImportedProfileRowState, RowHolder>(DIFF) {

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = getItem(position).row.id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val ctx = parent.context
            fun dp(value: Int): Int = (value * density).toInt()
            // Кнопка ✕/＋ на 20% меньше прежней (44 → 36 dp): 36 dp — минимум для касания.
            val toggle = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(palette.title)
                setBackgroundResource(R.drawable.bg_region_chip)
                isClickable = true
                isFocusable = true
            }
            val name = TextView(ctx).apply {
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(palette.title)
                // Имя забирает всю оставшуюся ширину, задержка стоит у правого края.
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            }
            val ping = TextView(ctx).apply {
                textSize = 13f
                maxLines = 1
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(palette.value)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(2), dp(4), 0, dp(4))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
                // Вся строка — кнопка выбора; отдельно от неё стоит ✕/＋.
                isClickable = true
                isFocusable = true
                // Нажатие подсвечивается поверх фона: фон занят подсветкой активного профиля.
                val ripple = TypedValue()
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    foreground = ContextCompat.getDrawable(ctx, ripple.resourceId)
                }
                addView(toggle, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(name)
                addView(ping)
            }
            return RowHolder(container, toggle, name, ping)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val state = getItem(position)
            val ctx = holder.container.context
            val label = state.row.title
            holder.toggle.text = if (state.disabled) "＋" else "✕"
            holder.toggle.contentDescription = NovaLanguage.tr(
                ctx,
                if (state.disabled) "Включить профиль {0}" else "Отключить профиль {0}",
            ).replace("{0}", label)
            holder.toggle.setOnClickListener { onToggle(state.row) }
            holder.container.setOnClickListener { onTap(state) }
            holder.name.text = label
            holder.ping.text = pingLabel(ctx, state.pingMs)
            val dim = if (state.disabled) 0.4f else 1f
            holder.name.alpha = dim
            holder.ping.alpha = dim
            // Активный профиль: тонкая акцентная обводка, лёгкая заливка того же цвета, имя — акцентом.
            holder.container.background = if (state.active) {
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = density * 12f
                    setColor(withAlpha(palette.accent, 0x26))
                    setStroke((density * 1.5f).toInt().coerceAtLeast(1), palette.accent)
                }
            } else {
                null
            }
            holder.name.setTextColor(if (state.active) palette.accent else palette.title)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha shl 24)

        private fun pingLabel(ctx: Context, ping: Int?): String = when (ping) {
            null, ImportedProfileRules.NOT_MEASURABLE -> "—"
            ImportedProfileRules.TIMED_OUT -> NovaLanguage.tr(ctx, "таймаут")
            else -> NovaLanguage.tr(ctx, "{0} мс").replace("{0}", ping.toString())
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ImportedProfileRowState>() {
                override fun areItemsTheSame(a: ImportedProfileRowState, b: ImportedProfileRowState): Boolean =
                    a.row.id == b.row.id

                override fun areContentsTheSame(a: ImportedProfileRowState, b: ImportedProfileRowState): Boolean =
                    a == b
            }
        }
    }
}
