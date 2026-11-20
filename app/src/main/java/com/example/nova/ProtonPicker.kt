package com.example.nova

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

internal class ProtonPicker(
    val view: View,
    private val accent: () -> Int,
    private val onPick: (ProtonSelection) -> Unit,
) {
    data class Node(val host: String, val country: String, val name: String, val pingMs: Int)

    private data class Option(val key: String, val label: String, val selected: Boolean = false)

    val shown: Boolean get() = view.visibility == View.VISIBLE
    val rowHeightPx = (NovaProfileListController.ROW_HEIGHT_DP * view.resources.displayMetrics.density).toInt()
    private val headerHeightPx = view.findViewById<TextView>(R.id.tv_proton_region).layoutParams.height

    private var selection = ProtonSelection()
    private var nodes = emptyList<Node>()
    private val regions = Column(R.id.rv_proton_regions) { key ->
        if (key != selection.country) onPick(selection.copy(country = key, host = ""))
    }
    private val servers = Column(R.id.rv_proton_servers) { key ->
        onPick(selection.copy(host = key))
    }
    private val ports = Column(R.id.rv_proton_ports) { key ->
        onPick(selection.copy(port = key.toIntOrNull()))
    }
    private val columns = listOf(regions, servers, ports)

    fun show(nodes: List<Node>, selection: ProtonSelection) {
        val opening = !shown
        if (opening) view.visibility = View.VISIBLE
        if (!opening && this.nodes == nodes && this.selection == selection) return

        val loaded = nodes != this.nodes
        val countryChanged = selection.country != this.selection.country
        this.nodes = nodes
        this.selection = selection

        val countryPings = nodes.filter { it.pingMs > 0 }.groupBy { it.country }
            .mapValues { (_, items) -> items.map { it.pingMs }.sorted()[items.size / 2] }
        val countries = CountryDisplayOrder.orderByPing(nodes.map { it.country } + selection.country, countryPings)
        regions.submit(countries.map { Option(it, it) }, selection.country, opening || loaded)

        val available = nodes.filter { selection.country.isEmpty() || it.country == selection.country }
        val names = available.groupingBy { it.name }.eachCount()
        val serverOptions = available.map {
            Option(it.host, if (names.getValue(it.name) > 1) "${it.name} · ${it.host}" else it.name)
        }.toMutableList()
        if (selection.host.isNotEmpty() && available.none { it.host == selection.host }) {
            serverOptions.add(0, Option(selection.host, NovaLanguage.tr(view.context, "Недоступный сервер Proton")))
        }
        servers.submit(serverOptions, selection.host, opening || loaded || countryChanged)
        ports.submit(
            ProtonProfileStore.SELECTABLE_PORTS.map { Option(it.toString(), it.toString()) },
            selection.port?.toString().orEmpty(),
            opening,
        )
    }

    fun hide() {
        if (shown) view.visibility = View.GONE
    }

    fun fitHeight(availablePx: Int) {
        val rows = ((availablePx - headerHeightPx) / rowHeightPx)
            .coerceIn(NovaProfileListController.MIN_ROWS, NovaProfileListController.MAX_ROWS)
        columns.forEach { it.list.maxHeightPx = rows * rowHeightPx }
    }

    fun accentChanged() {
        columns.forEach { it.adapter.notifyDataSetChanged() }
    }

    private inner class Column(listId: Int, onClick: (String) -> Unit) {
        val list = view.findViewById<NovaMaxHeightRecyclerView>(listId)
        val adapter = OptionAdapter(onClick)
        private var pendingReveal = false

        init {
            list.layoutManager = LinearLayoutManager(view.context)
            list.adapter = adapter
            list.itemAnimator = null
            list.isNestedScrollingEnabled = false
            list.maxHeightPx = NovaProfileListController.MIN_ROWS * rowHeightPx
        }

        fun submit(options: List<Option>, selected: String, revealSelected: Boolean) {
            val rows = (listOf(Option("", "Auto")) + options).map { it.copy(selected = it.key == selected) }
            pendingReveal = pendingReveal || revealSelected
            adapter.submitList(rows) {
                if (pendingReveal) {
                    pendingReveal = false
                    list.scrollToPosition(rows.indexOfFirst { it.selected }.coerceAtLeast(0))
                }
            }
        }
    }

    private inner class OptionAdapter(private val onClick: (String) -> Unit) :
        ListAdapter<Option, OptionHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionHolder {
            val text = LayoutInflater.from(parent.context).inflate(R.layout.item_proton_option, parent, false) as TextView
            NovaLanguage.verbatim(text)
            NovaFontHelper.apply(text)
            return OptionHolder(text).also { holder ->
                text.setOnClickListener {
                    val position = holder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) onClick(getItem(position).key)
                }
            }
        }

        override fun onBindViewHolder(holder: OptionHolder, position: Int) {
            val option = getItem(position)
            val text = holder.text
            val accentColor = accent()
            text.text = option.label
            text.isSelected = option.selected
            text.setTextColor(if (option.selected) accentColor else NovaProfileListController.NORMAL_TEXT)
            text.background = if (option.selected) {
                NovaProfileListController.selectionBackground(accentColor, text.context)
            } else {
                null
            }
        }
    }

    private class OptionHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    companion object {
        fun nodesFrom(profiles: List<ProtonProfile>): List<Node> =
            profiles.map {
                Node(
                    it.entryIp.trim().trim('[', ']').lowercase(Locale.US),
                    ProtonProfileStore.displayCountry(it),
                    it.serverName.ifBlank { it.entryIp },
                    it.pingMs,
                )
            }.filter { it.host.isNotBlank() }.distinctBy { it.host }

        private val DIFF = object : DiffUtil.ItemCallback<Option>() {
            override fun areItemsTheSame(a: Option, b: Option) = a.key == b.key
            override fun areContentsTheSame(a: Option, b: Option) = a == b
        }
    }
}
