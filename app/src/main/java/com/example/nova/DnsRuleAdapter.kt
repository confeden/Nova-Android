package com.example.nova

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

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

    /**
     * Режим «Изменить порядок»: только в нём строку вообще можно потянуть.
     *
     * Вне режима ручки нет, а вместе с ней нет и случайного перетаскивания:
     * палец, опустившийся на левый край строки ради прокрутки, менял порядок
     * опроса резолверов и ничего об этом не говорил.
     */
    var reorderEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, items.size)
        }

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

        /**
         * Порог, после которого касание ручки становится перетаскиванием.
         *
         * Половина системного — не экономия: и RecyclerView, и внешний
         * ScrollView забирают жест себе, как только палец прошёл по вертикали
         * полный `scaledTouchSlop`, и до целого порога этот обработчик уже не
         * доживёт — перетаскивание не началось бы никогда. Половины хватает,
         * чтобы простое касание ручки и дрожание пальца строку не двигали.
         */
        val dragSlopPx: Int = ViewConfiguration.get(view.context).scaledTouchSlop / 2
        var dragDownY: Float = 0f
        var dragArmed: Boolean = false
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
        holder.kind.text = transportLabel(rule)
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
        //
        // В режиме изменения порядка правка и удаление выключены у всех строк:
        // палец там занят перетаскиванием, и нажатие, не доехавшее до порога,
        // открывало бы диалог вместо того, чтобы ничего не делать.
        val editable = !reorderEnabled &&
            rule.kind != DnsRule.Kind.PROVIDER && rule.kind != DnsRule.Kind.AUTO
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
        if (reorderEnabled) {
            holder.drag.visibility = View.VISIBLE
            holder.drag.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holder.dragDownY = event.rawY
                        holder.dragArmed = true
                    }
                    // Тянем не с нажатия, а с движения: `startDrag` входит в
                    // перетаскивание сразу, без всякого порога, и палец,
                    // опустившийся на ручку, уносил строку ещё до того, как
                    // человек решил, тянет он её или просто листает.
                    MotionEvent.ACTION_MOVE ->
                        if (holder.dragArmed &&
                            abs(event.rawY - holder.dragDownY) >= holder.dragSlopPx
                        ) {
                            holder.dragArmed = false
                            onStartDrag(holder)
                        }
                    else -> holder.dragArmed = false
                }
                // Событие забираем себе: тот, кто не взял ACTION_DOWN, ни
                // одного ACTION_MOVE потом не увидит, и порог мерить будет нечем.
                true
            }
        } else {
            // Слушателя снимаем, а не только прячем: переиспользованная строка
            // иначе унесла бы его с собой и тянулась бы при выключенном режиме.
            holder.drag.setOnTouchListener(null)
            holder.drag.visibility = View.GONE
            holder.dragArmed = false
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        /**
         * Подпись слева — это **транспорт**, а не вид значения.
         *
         * Человек выбирает в диалоге именно его, и правило, записанное
         * DoH-ссылкой, может спрашиваться обоими путями. Показывать при этом
         * «DoH» значило бы называть строку не тем, чем она работает.
         *
         * У открытого и провайдерского транспорта нет: там подпись про вид.
         */
        fun transportLabel(rule: DnsRule): String = when (rule.kind) {
            DnsRule.Kind.PLAIN -> "Обычный"
            DnsRule.Kind.PROVIDER -> "Открытый"
            else -> when (rule.transport) {
                DnsRule.Transport.ANY -> "Любой"
                DnsRule.Transport.DOH -> "DoH"
                DnsRule.Transport.DOT -> "DoT"
            }
        }

        /** Что показать в строке вместо технического значения. */
        fun valueLabel(rule: DnsRule): String = when (rule.kind) {
            DnsRule.Kind.PROVIDER -> "Резолвер провайдера"
            else -> rule.value
        }

        /** Пояснение под адресом. Пусто — пояснять нечего. */
        fun noteLabel(rule: DnsRule): String = when {
            rule.kind == DnsRule.Kind.PROVIDER ->
                "без шифрования, только когда не ответил ни один защищённый выше"
            // Пояснение принадлежит транспорту, а не нашей строке: с тех пор как
            // «любой» можно выбрать любому резолверу, оно нужно каждому такому.
            //
            // Коротко, в одну строку. Полная фраза «транспорт выбирается
            // автоматически: что быстрее — DoH или DoT» не влезала в ширину
            // строки и обрывалась многоточием на каждом резолвере: шесть
            // одинаковых обрубков подряд читаются как сбой, а не как подсказка.
            rule.encrypted && rule.transport == DnsRule.Transport.ANY ->
                "быстрее из DoH и DoT"
            else -> ""
        }
    }
}
