package com.example.nova

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Список с потолком высоты: `wrap_content` до [maxHeightPx], дальше прокручивается
 * внутри себя. Внешняя раскладка главного экрана не прокручивается, так что
 * вложенной прокрутки здесь нет — потолок нужен, чтобы список не наехал на «Настройки».
 */
class NovaMaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (maxHeightPx == Int.MAX_VALUE) {
            super.onMeasure(widthSpec, heightSpec)
            return
        }
        val parentLimit = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(heightSpec)
        }
        super.onMeasure(
            widthSpec,
            MeasureSpec.makeMeasureSpec(minOf(parentLimit, maxHeightPx), MeasureSpec.AT_MOST),
        )
    }
}

/**
 * Список профилей встроенной подписки Nova на главном экране (четвёртая полоса
 * при выбранной кнопке NOVA).
 *
 * Одна строка на профиль: номер, протокол, «адрес:порт» и пинг. Порядок — по пингу
 * ([NovaList.order]), первая строка — «Авто». Всё чтение файлов и разбор списка идёт
 * на одном фоновом потоке; на главном потоке остаются только раздача готовых строк
 * адаптеру и клики. Замер ([NovaProxyProcess.probe]) запускается один, пока список
 * виден и замеры старше [NovaList.PING_STALE_MS], и никогда не при сеансе DNS.
 */
class NovaProfileListController(
    activity: Activity,
    private val list: NovaMaxHeightRecyclerView,
    private val accent: () -> Int,
    private val onChosen: (String) -> Unit,
) {
    private val appContext: Context = activity.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nova-list-ui").apply { isDaemon = true } }
    private val adapter = RowAdapter()

    private val destroyed = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val probing = AtomicBoolean(false)
    private val feedTried = AtomicBoolean(false)

    @Volatile private var nodes: List<NovaList.Node> = emptyList()
    @Volatile private var pings: Map<String, NovaList.Ping> = emptyMap()
    @Volatile private var choice: String = ""
    @Volatile private var activeId: String = ""
    @Volatile private var live = false
    @Volatile private var dnsSession = false
    @Volatile private var refreshFailed = false
    @Volatile private var lastReloadAt = 0L
    @Volatile private var lastProbeAt = 0L

    /** Показан ли список (читается и главным потоком при расчёте высоты). */
    @Volatile var shown: Boolean = false
        private set

    val rowHeightPx: Int = (ROW_HEIGHT_DP * activity.resources.displayMetrics.density).toInt()

    init {
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        list.isNestedScrollingEnabled = false
    }

    // ---- вход с экрана -------------------------------------------------------------

    /** Зовётся из перерисовки селектора, несколько раз в секунду, только с главного потока. */
    fun onVisible(visible: Boolean, tunnelLive: Boolean, dnsSessionActive: Boolean) {
        live = tunnelLive
        dnsSession = dnsSessionActive
        if (!visible) {
            if (shown) {
                shown = false
                list.visibility = View.GONE
            }
            return
        }
        if (!shown) {
            shown = true
            feedTried.set(false)
            list.visibility = View.VISIBLE
            reload(full = true)
            return
        }
        if (System.currentTimeMillis() - lastReloadAt > RELOAD_EVERY_MS) reload(full = false)
    }

    /** Потолок высоты по свободному месту: от двух до десяти строк. */
    fun fitHeight(availablePx: Int) {
        val rows = (availablePx / rowHeightPx).coerceIn(MIN_ROWS, MAX_ROWS)
        list.maxHeightPx = rows * rowHeightPx
    }

    fun destroy() {
        destroyed.set(true)
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    // ---- данные --------------------------------------------------------------------

    private fun reload(full: Boolean) {
        if (destroyed.get() || !loading.compareAndSet(false, true)) return
        lastReloadAt = System.currentTimeMillis()
        runIo {
            try {
                if (full || nodes.isEmpty()) nodes = NovaList.nodes(appContext)
                pings = NovaList.pings(appContext)
                choice = NovaList.choice(appContext)
                activeId = NovaList.active(appContext)
            } finally {
                loading.set(false)
            }
            publish()
            if (nodes.isEmpty()) refreshFeed() else maybeProbe(force = false)
        }
    }

    /**
     * Оттенок сменили, пока главный экран лежал в стопке. Строки красятся акцентом
     * при привязке, поэтому перекрасить их можно только перепривязав.
     */
    fun accentChanged() {
        ui.post { if (!destroyed.get()) adapter.notifyDataSetChanged() }
    }

    private fun refreshFeed() {
        if (!feedTried.compareAndSet(false, true)) return
        refreshFailed = false
        NovaList.refreshInBackground(appContext, LogManager::log, force = true) { ok ->
            refreshFailed = !ok
            runIo {
                nodes = NovaList.nodes(appContext)
                pings = NovaList.pings(appContext)
                publish()
                maybeProbe(force = false)
            }
        }
        runIo { publish() }
    }

    private fun runIo(block: () -> Unit) {
        runCatching { io.execute { if (!destroyed.get()) runCatching(block) } }
    }

    /** Собирает строки на фоновом потоке и отдаёт адаптеру (разницу считает ListAdapter). */
    private fun publish() {
        if (destroyed.get()) return
        val snapshotNodes = nodes
        val snapshotPings = pings
        val chosenId = choice
        val now = System.currentTimeMillis()
        val measuring = probing.get()
        val out = ArrayList<Row>(snapshotNodes.size + 1)
        if (snapshotNodes.isEmpty()) {
            val text = if (refreshFailed && !NovaList.isRefreshing()) MSG_UNAVAILABLE else MSG_LOADING
            out += Row(KEY_MESSAGE, Row.MESSAGE, 0, "", text, Row.PING_NONE, 0, chosen = false, active = false)
        } else {
            val known = snapshotNodes.any { it.id == chosenId }
            out += Row(
                KEY_AUTO, Row.AUTO, 0, "", AUTO_TEXT, Row.PING_NONE, 0,
                chosen = chosenId.isEmpty() || !known, active = false,
            )
            val tunnelOn = live
            for (node in NovaList.order(snapshotNodes, snapshotPings)) {
                val p = snapshotPings[node.id]
                val pingKind: Int
                val ms: Int
                when {
                    p != null && p.ok -> { pingKind = Row.PING_OK; ms = p.ms }
                    measuring && (p == null || now - p.at > NovaList.PING_STALE_MS) -> { pingKind = Row.PING_MEASURING; ms = 0 }
                    p != null -> { pingKind = Row.PING_FAIL; ms = 0 }
                    else -> { pingKind = Row.PING_NONE; ms = 0 }
                }
                out += Row(
                    node.id, Row.NODE, node.number, node.protocol, "${node.server}:${node.port}",
                    pingKind, ms, chosen = known && node.id == chosenId,
                    active = tunnelOn && node.id == activeId,
                )
            }
        }
        ui.post { if (!destroyed.get()) adapter.submitList(out) }
    }

    // ---- замер ---------------------------------------------------------------------

    private fun maybeProbe(force: Boolean) {
        if (destroyed.get() || !shown || dnsSession) return
        val now = System.currentTimeMillis()
        // Нажатие на чип NOVA просит свежие замеры, даже если чип уже выбран.
        val forced = force || forceOnNextProbe.getAndSet(false)
        if (!forced && now - lastProbeAt < REPROBE_MIN_MS) return
        if (nodes.isEmpty() || !NovaProxyProcess.isAvailable(appContext)) return
        if (!probing.compareAndSet(false, true)) {
            if (forced) forceOnNextProbe.set(true)
            return
        }
        lastProbeAt = now
        thread(start = true, isDaemon = true, name = "nova-list-probe") {
            try {
                publish()
                // Голова списка пачкой в PAGE_SIZE; не хватило живых — следующая страница ленты.
                // Весь список не меряется никогда: он стоил бы телефону минут проб и трафика.
                NovaList.scan(
                    appContext, force = forced,
                    shouldAbort = { destroyed.get() || dnsSession },
                    onProgress = {
                        nodes = NovaList.nodes(appContext)
                        pings = NovaList.pings(appContext)
                        publish()
                    },
                )
            } finally {
                probing.set(false)
                nodes = NovaList.nodes(appContext)
                pings = NovaList.pings(appContext)
                publish()
            }
        }
    }

    /**
     * Нажатие на чип NOVA, в том числе уже выбранный: следующий замер идёт принудительно,
     * и список пересортировывается по свежему пингу.
     */
    fun requestFreshPing() {
        markFreshPingWanted()
        if (!shown) return
        runIo { maybeProbe(force = false) }
    }

    // ---- нажатия -------------------------------------------------------------------

    private fun onRowClicked(row: Row) {
        if (row.kind == Row.MESSAGE) return
        val id = if (row.kind == Row.AUTO) "" else row.key
        if (id == choice) return
        choice = id
        runIo {
            NovaList.setChoice(appContext, id)
            publish()
            ui.post { if (!destroyed.get()) onChosen(id) }
        }
    }

    private fun onRowLongClicked(row: Row) {
        if (row.kind == Row.MESSAGE) {
            feedTried.set(false)
            runIo { refreshFeed() }
        } else if (!dnsSession) {
            runIo { maybeProbe(force = true) }
        }
    }

    // ---- адаптер -------------------------------------------------------------------

    data class Row(
        val key: String,
        val kind: Int,
        val number: Int,
        val protocol: String,
        val addr: String,
        val pingKind: Int,
        val ms: Int,
        val chosen: Boolean,
        val active: Boolean,
    ) {
        companion object {
            const val AUTO = 0
            const val NODE = 1
            const val MESSAGE = 2
            const val PING_NONE = 0
            const val PING_MEASURING = 1
            const val PING_OK = 2
            const val PING_FAIL = 3
        }
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val mark: TextView = view.findViewById(R.id.tv_nova_mark)
        val num: TextView = view.findViewById(R.id.tv_nova_num)
        val proto: TextView = view.findViewById(R.id.tv_nova_proto)
        val addr: TextView = view.findViewById(R.id.tv_nova_addr)
        val ping: TextView = view.findViewById(R.id.tv_nova_ping)
        var row: Row? = null
    }

    private inner class RowAdapter : ListAdapter<Row, Holder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_nova_profile, parent, false)
            val holder = Holder(view)
            // Пинг собирается из числа и «мс» сам и уже переведён: каталог такой текст не знает.
            NovaLanguage.verbatim(holder.ping)
            NovaLanguage.verbatim(holder.mark)
            view.setOnClickListener { holder.row?.let(::onRowClicked) }
            view.setOnLongClickListener {
                holder.row?.let(::onRowLongClicked)
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.row = row
            val accentColor = accent()
            val ctx = holder.itemView.context
            val single = row.kind != Row.NODE
            val columns = if (single) View.GONE else View.VISIBLE
            holder.num.visibility = columns
            holder.proto.visibility = columns
            holder.ping.visibility = columns
            holder.num.text = "#${row.number}"
            holder.proto.text = row.protocol
            if (!single) fitProtocolColumn(holder.proto)
            holder.addr.text = row.addr
            holder.mark.text = if (row.active) "●" else ""
            holder.ping.text = when (row.pingKind) {
                Row.PING_OK -> "${row.ms} ${NovaLanguage.tr(ctx, "мс")}"
                Row.PING_MEASURING -> "…"
                Row.PING_FAIL -> "✕"
                else -> "—"
            }
            val textColor = if (row.chosen) accentColor else NORMAL_TEXT
            holder.num.setTextColor(textColor)
            holder.proto.setTextColor(textColor)
            holder.addr.setTextColor(textColor)
            holder.ping.setTextColor(textColor)
            holder.mark.setTextColor(accentColor)
            holder.itemView.isClickable = row.kind != Row.MESSAGE
            holder.itemView.background = if (row.chosen) selectionBackground(accentColor, ctx) else null
        }

        /** Ширина колонки протокола по самой длинной метке страницы; пересчёт только при смене списка. */
        private var protoWidthList: List<Row>? = null
        private var protoWidthPx = 0

        /**
         * Протокол показывается целиком («VLESS·R·GRPC» не режется), а не влезает — сокращается
         * только адрес с портом: колонка адреса берёт остаток и ужимается многоточием.
         */
        private fun fitProtocolColumn(proto: TextView) {
            val list = currentList
            if (protoWidthList !== list || protoWidthPx == 0) {
                val density = proto.resources.displayMetrics.density
                val paint = proto.paint
                var widest = 0f
                for (r in list) if (r.kind == Row.NODE) widest = maxOf(widest, paint.measureText(r.protocol))
                protoWidthPx = maxOf(
                    (62f * density).toInt(),
                    kotlin.math.ceil(widest + 8f * density).toInt(),
                )
                protoWidthList = list
            }
            val params = proto.layoutParams
            if (params != null && params.width != protoWidthPx) {
                params.width = protoWidthPx
                proto.layoutParams = params
            }
        }

    }

    companion object {
        /** Выставляется нажатием на чип NOVA; первый же замер списка сбрасывает его и идёт принудительно. */
        private val forceOnNextProbe = AtomicBoolean(false)

        fun markFreshPingWanted() = forceOnNextProbe.set(true)

        internal const val ROW_HEIGHT_DP = 22f
        internal const val MIN_ROWS = 2
        internal const val MAX_ROWS = 10
        private const val RELOAD_EVERY_MS = 3_000L
        private const val REPROBE_MIN_MS = 60_000L
        internal const val NORMAL_TEXT = 0xFFB9C4D6.toInt()

        internal fun selectionBackground(color: Int, ctx: Context): GradientDrawable =
            GradientDrawable().apply {
                setColor((color and 0x00FFFFFF) or (0x33 shl 24))
                cornerRadius = 6f * ctx.resources.displayMetrics.density
            }

        private const val KEY_AUTO = "\u0000auto"
        private const val KEY_MESSAGE = "\u0000message"

        private const val AUTO_TEXT = "Авто — лучший по ping"
        private const val MSG_LOADING = "Список Nova загружается…"
        private const val MSG_UNAVAILABLE = "Список Nova недоступен"

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
