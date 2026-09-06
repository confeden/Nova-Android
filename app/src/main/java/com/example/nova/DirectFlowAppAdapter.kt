package com.example.nova

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Список приложений для экрана «Прямой поток».
 *
 * Отдельный адаптер, а не флаг в [AppAdapter], потому что смысл галочки здесь
 * обратный: отмеченное приложение идёт **мимо** туннеля, а не в него. Строки
 * закрытого списка отличаются от остальных только подписью `direct` — снять с
 * них галочку можно так же, как поставить её на любое своё приложение.
 */
class DirectFlowAppAdapter(private val onToggle: (String, Boolean) -> Unit) :
    RecyclerView.Adapter<DirectFlowAppAdapter.ViewHolder>() {

    private var items: List<AppItem> = emptyList()

    /**
     * Действует ли закрытый список прямо сейчас.
     *
     * При выключенном мастер-переключателе его строки не убираются из списка, а
     * гаснут: исчезнувший ряд читался бы как «приложение не установлено», хотя
     * оно на месте и вернётся одним движением переключателя.
     */
    private var curatedActive: Boolean = true

    fun setData(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setCuratedActive(active: Boolean) {
        if (curatedActive == active) return
        curatedActive = active
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
        val direct: TextView = view.findViewById(R.id.tv_app_direct)
        val check: CheckBox = view.findViewById(R.id.cb_select)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_selection, parent, false)
        NovaFontHelper.apply(view)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        AppCacheManager.bindIcon(holder.icon, item)

        // Слушатель снимается до записи isChecked: вид переиспользуется, и иначе
        // чужая строка успела бы сохранить своё состояние в эту.
        holder.check.setOnCheckedChangeListener(null)

        // Галочка есть у всех строк, включая строки закрытого списка.
        //
        // Сначала они были только для чтения — рассуждение было «их выбрал не
        // пользователь». Владелец возразил по делу: список — это предложение, а
        // не запрет, и снять с него приложение он должен уметь. Снятое хранится
        // отдельным вычитанием, поэтому пополнение списка в новой версии
        // работает сразу, а снятое остаётся снятым.
        //
        // Нажимать нечего только пока выключен мастер-переключатель: строки
        // закрытого списка тогда не действуют вовсе, и галочка обещала бы выбор,
        // которого нет.
        val clickable = !item.isDirect || curatedActive
        holder.direct.visibility = if (item.isDirect) View.VISIBLE else View.GONE
        holder.check.isChecked = item.isSelected
        holder.check.isEnabled = clickable
        holder.check.alpha = if (clickable) 1f else 0.4f
        holder.itemView.alpha = if (item.isDirect && !curatedActive) 0.45f else 1f
        if (clickable) {
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                onToggle(item.packageName, isChecked)
            }
        }
    }

    override fun getItemCount() = items.size
}
