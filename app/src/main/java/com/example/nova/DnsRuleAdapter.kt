package com.example.nova

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Список резолверов: порядок строк и есть порядок опроса.
 *
 * Список живёт **здесь**, а не в активности. Перетаскивание меняет позиции
 * поштучно (`notifyItemMoved`), и вторая копия списка на стороне экрана
 * разъехалась бы с этой на первом же перетаскивании — сохранили бы порядок,
 * который пользователь не видел. Активность спрашивает состояние через
 * [snapshot] в тот момент, когда собирается его записать.
 */
class DnsRuleAdapter(
    private val onEdit: (Int) -> Unit,
    private val onToggle: (Int, Boolean) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<DnsRuleAdapter.ViewHolder>() {

    private val items = mutableListOf<DnsRule>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<DnsRule>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Текущий порядок правил — то, что надо записать в хранилище. */
    fun snapshot(): List<DnsRule> = items.toList()

    fun itemAt(position: Int): DnsRule? = items.getOrNull(position)

    fun addItem(rule: DnsRule) {
        items.add(rule)
        notifyItemInserted(items.size - 1)
    }

    fun replaceAt(position: Int, rule: DnsRule) {
        if (position !in items.indices) return
        items[position] = rule
        notifyItemChanged(position)
    }

    fun removeAt(position: Int): DnsRule? {
        if (position !in items.indices) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val drag: ImageView = view.findViewById(R.id.iv_dns_rule_drag)
        val kind: TextView = view.findViewById(R.id.tv_dns_rule_kind)
        val value: TextView = view.findViewById(R.id.tv_dns_rule_value)
        val bootstrap: TextView = view.findViewById(R.id.tv_dns_rule_bootstrap)
        val enabled: SwitchCompat = view.findViewById(R.id.sw_dns_rule_enabled)
        val delete: ImageView = view.findViewById(R.id.btn_dns_rule_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dns_rule, parent, false)
        // Шрифт ставим здесь: NovaFontHelper обходит дерево экрана, но внутрь
        // RecyclerView не заходит — строки создаются позже него.
        NovaFontHelper.apply(view)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = items[position]
        holder.kind.text = kindLabel(rule.kind)
        holder.value.text = valueLabel(rule)
        // Bootstrap-адреса с экрана убраны: владелец попросил список защищённых
        // адресов, а не техническую сводку. Они никуда не делись — их подставляет
        // само приложение, и без них имя резолвера пришлось бы разворачивать через
        // тот самый перехват, который его и спрашивает.
        val note = noteLabel(rule)
        if (note.isEmpty()) {
            holder.bootstrap.visibility = View.GONE
        } else {
            holder.bootstrap.visibility = View.VISIBLE
            holder.bootstrap.text = note
        }
        // Выключенное правило остаётся в списке и на своём месте — гасим только
        // подписи, чтобы порядок читался и по выключенным строкам.
        val contentAlpha = if (rule.enabled) 1f else 0.45f
        holder.kind.alpha = contentAlpha
        holder.value.alpha = contentAlpha
        holder.bootstrap.alpha = contentAlpha

        // Слушателя снимаем до присвоения: переиспользованная строка иначе
        // сообщила бы о «переключении» чужого правила.
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = rule.enabled
        holder.enabled.setOnCheckedChangeListener { _, isChecked ->
            val current = holder.adapterPosition
            if (current != RecyclerView.NO_POSITION) onToggle(current, isChecked)
        }

        // Две строки нельзя ни править, ни удалять.
        //
        // Провайдерскую — потому что её адрес приходит от сети, а не от
        // пользователя, а удаление оставило бы без запасного пути. Выключатель у
        // неё при этом свой: именно это и просили — «его тоже можно отключить».
        //
        // Строку нашего резолвера — потому что диалог правки знает только DoH и
        // DoT. Открытая по ней правка превращала бы автовыбор транспорта в
        // обычный DoH, то есть молча выбрасывала половину, ради которой автовыбор
        // и заведён (443 и 853 блокируют порознь), и первым делом показывала
        // ошибку «нужен адрес вида https://…» на строке, которую никто не менял.
        val editable = rule.kind != DnsRule.Kind.PROVIDER && rule.kind != DnsRule.Kind.AUTO
        holder.itemView.isClickable = editable
        holder.itemView.setOnClickListener(
            if (!editable) null else View.OnClickListener {
                val current = holder.adapterPosition
                if (current != RecyclerView.NO_POSITION) onEdit(current)
            }
        )
        holder.delete.visibility = if (editable) View.VISIBLE else View.INVISIBLE
        holder.delete.setOnClickListener(
            if (!editable) null else View.OnClickListener {
                val current = holder.adapterPosition
                if (current != RecyclerView.NO_POSITION) onDelete(current)
            }
        )
        holder.drag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        fun kindLabel(kind: DnsRule.Kind): String = when (kind) {
            DnsRule.Kind.PLAIN -> "Обычный"
            DnsRule.Kind.DOH -> "DoH"
            DnsRule.Kind.DOT -> "DoT"
            DnsRule.Kind.AUTO -> "DoH/DoT"
            DnsRule.Kind.PROVIDER -> "Открытый"
        }

        /** Что показать в строке вместо технического значения. */
        fun valueLabel(rule: DnsRule): String = when (rule.kind) {
            DnsRule.Kind.PROVIDER -> "Резолвер провайдера"
            else -> rule.value
        }

        /** Пояснение под адресом. Пусто — пояснять нечего. */
        fun noteLabel(rule: DnsRule): String = when (rule.kind) {
            DnsRule.Kind.AUTO -> "транспорт выбирается автоматически: что быстрее — DoH или DoT"
            DnsRule.Kind.PROVIDER ->
                "без шифрования, только когда не ответил ни один защищённый выше"
            else -> ""
        }
    }
}
