package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class ConfigsAdapter(
    private var items: List<WarpVerifiedConfig>,
    private var metadata: Map<String, ConfigMetadata>,
    private var bestAvgPing: Double?,
    private val clientData: ClientData,
    private val activity: WarpConfigsActivity,
    private val onDataChanged: () -> Unit,
    private val onMove: (id: String, move: Move) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<ConfigsAdapter.ConfigViewHolder>() {

    enum class Move { TOP, UP, DOWN, BOTTOM }

    data class ConfigMetadata(
        val probeCount: Int,
        val pingSuccesses: Int,
        val avgPingMs: Double,
        val lastCheckedAt: Long
    )

    // Строки свёрнуты по умолчанию: тело конфигурации разворачивается по тапу,
    // иначе список из сотен профилей невозможно листать.
    private val expandedIds = mutableSetOf<String>()

    fun updateItems(newItems: List<WarpVerifiedConfig>, newMetadata: Map<String, ConfigMetadata>, newBestAvgPing: Double?) {
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                private val old = items
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                    old[oldPos].id == newItems[newPos].id

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]
                    val b = newItems[newPos]
                    return a == b && metadata[a.id] == newMetadata[b.id] && bestAvgPing == newBestAvgPing
                }
            },
            false,
        )
        items = newItems
        metadata = newMetadata
        bestAvgPing = newBestAvgPing
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_warp_config, parent, false)
        NovaFontHelper.apply(view)
        return ConfigViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class ConfigViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {
        private val header: LinearLayout = view.findViewById(R.id.row_config_header)
        private val tvTitle: TextView = view.findViewById(R.id.tv_config_title)
        private val tvCurrent: TextView = view.findViewById(R.id.tv_config_current)
        private val tvMeta: TextView = view.findViewById(R.id.tv_config_meta)
        private val tvExpand: TextView = view.findViewById(R.id.tv_config_expand)
        private val boxDetails: LinearLayout = view.findViewById(R.id.box_config_details)
        private val tvBody: TextView = view.findViewById(R.id.tv_config_body)
        private val btnCopy: TextView = view.findViewById(R.id.btn_copy_config)
        private val btnDelete: TextView = view.findViewById(R.id.btn_delete_config)
        private val btnMoveTop: TextView = view.findViewById(R.id.btn_move_top)
        private val btnMoveUp: TextView = view.findViewById(R.id.btn_move_up)
        private val btnMoveDown: TextView = view.findViewById(R.id.btn_move_down)
        private val btnMoveBottom: TextView = view.findViewById(R.id.btn_move_bottom)

        fun bind(item: WarpVerifiedConfig) {
            val isCurrent = activity.isCurrentConfigPublic(item)
            val meta = metadata[item.id]

            val displayMode = normalizeModeForDisplay(item.rawConfig, item.mode)
            tvTitle.text = when {
                item.manual -> "MANUAL"
                item.userImported -> "USER • $displayMode @ ${item.host}:${item.port}"
                else -> "$displayMode @ ${item.host}:${item.port}"
            }
            tvCurrent.visibility = if (isCurrent) View.VISIBLE else View.GONE
            // Удержание печатается снаружи ветки с пинговой статистикой: замер
            // может быть, а пинговых данных — нет, и тогда показатель, который
            // двигает профиль по списку, пропал бы с экрана вовсе. Сортировка без
            // объяснения на экране — уже записанная в дорожной карте ловушка.
            val holdStallMs = clientData.warpConfigHoldStallMs(item)
            val holdText = if (item.manual || holdStallMs < 0.0) {
                ""
            } else {
                val label = when (clientData.warpConfigHoldGrade(item)) {
                    SessionHoldMetric.GRADE_STEADY -> "держит"
                    SessionHoldMetric.GRADE_SHAKY -> "проседает"
                    SessionHoldMetric.GRADE_LOSING -> "теряет поток"
                    else -> ""
                }
                if (label.isBlank()) {
                    ""
                } else {
                    // Коротко: слово задаёт направление, число — величину. Полная
                    // формулировка «тишина N мс за окно M мс» живёт в журнале, а на
                    // карточке она вытесняла сама себя за край строки.
                    "Удержание: $label ${"%.1f".format(holdStallMs / 1000.0)} с"
                }
            }
            tvMeta.text = buildString {
                if (meta != null && !item.manual && meta.lastCheckedAt > 0L && meta.probeCount > 0) {
                    val successRate = meta.pingSuccesses.toDouble() / meta.probeCount
                    val pingFactor = if (bestAvgPing != null && bestAvgPing!! > 0.0 && meta.avgPingMs > 0.0) {
                        (bestAvgPing!! / meta.avgPingMs).coerceAtMost(1.0)
                    } else {
                        0.0
                    }
                    val quality = (successRate * pingFactor * 100.0).toInt().coerceIn(0, 100)
                    append("Качество: ${quality}%")
                    if (meta.avgPingMs > 0.0) {
                        append("   •   Ping: ${meta.avgPingMs.toInt()} ms")
                    }
                    // Удержание сессии — отдельная величина от пинга: узел с хорошим
                    // пингом может пересобирать рукопожатие втрое чаще нормы, и по
                    // одному «Качеству» этого было не видно.
                    if (holdText.isNotBlank()) {
                        append("   •   $holdText")
                    }
                    if (item.churnWindows >= 2) {
                        val perWindow = item.churnRekeys.toDouble() / item.churnWindows
                        append("   •   Рукопожатий: ${"%.1f".format(perWindow)}/окно")
                    }
                    if (!item.userImported && item.endpointSource.isNotBlank()) {
                        append("   •   ${item.endpointSource}")
                    }
                } else if (holdText.isNotBlank()) {
                    append(holdText)
                } else {
                    append(
                        when {
                            item.manual -> "Ручная конфигурация"
                            else -> "Нет данных адаптации"
                        }
                    )
                }
            }
            val accentColor = if (item.userImported) "#F3C94A" else "#50C878"
            tvMeta.setTextColor(Color.parseColor(accentColor))
            if (item.userImported) {
                view.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#241E10"))
            } else {
                view.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161E1A"))
            }

            val expanded = expandedIds.contains(item.id)
            boxDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            tvExpand.text = if (expanded) "⌃" else "⌄"
            // Тело конфигурации форматируется лениво — только для раскрытой строки.
            tvBody.text = if (expanded) activity.renderConfigForDisplayPublic(item) else ""

            header.setOnClickListener {
                if (expandedIds.contains(item.id)) expandedIds.remove(item.id) else expandedIds.add(item.id)
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }

            btnCopy.setOnClickListener {
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("warp-config", activity.renderConfigForDisplayPublic(item))
                )
                Toast.makeText(activity, "Конфигурация скопирована", Toast.LENGTH_SHORT).show()
            }
            btnDelete.setOnClickListener {
                expandedIds.remove(item.id)
                // Профили VLESS лежат отдельно от хранилища WARP, поэтому удаление идёт
                // через общий вход, а не через removeWarpVerifiedConfig напрямую.
                val removed = clientData.removeImportedConfig(item.id)
                onDataChanged()
                Toast.makeText(
                    activity,
                    if (removed) "Конфигурация удалена" else "Не удалось удалить конфигурацию",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            btnMoveTop.setOnClickListener { onMove(item.id, Move.TOP) }
            btnMoveUp.setOnClickListener { onMove(item.id, Move.UP) }
            btnMoveDown.setOnClickListener { onMove(item.id, Move.DOWN) }
            btnMoveBottom.setOnClickListener { onMove(item.id, Move.BOTTOM) }
        }
    }
}

internal fun normalizeModeForDisplay(rawConfig: String, fallbackMode: String): String {
    val hasAwgMarkers = Regex("(?im)^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])\\s*=").containsMatchIn(rawConfig)
    if (!hasAwgMarkers) return fallbackMode
    val hasCpsLines = Regex("(?im)^I[1-5]\\s*=").containsMatchIn(rawConfig)
    val hasAwg2Markers =
        Regex("(?im)^S[1-4]\\s*=").containsMatchIn(rawConfig) ||
            Regex("(?im)^H[1-4]\\s*=").containsMatchIn(rawConfig) ||
            Regex("(?im)^J(c|min|max)\\s*=").containsMatchIn(rawConfig)
    return when {
        hasCpsLines || hasAwg2Markers -> "warp-awg-exact"
        rawConfig.lowercase(Locale.US).contains("engage.cloudflareclient.com") -> "warp-awg-lite"
        else -> "warp-awg"
    }
}
