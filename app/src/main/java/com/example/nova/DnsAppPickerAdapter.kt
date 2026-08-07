package com.example.nova

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DnsAppPickerAdapter(
    private var selectedPackage: String,
    private val onSelect: (AppItem) -> Unit,
) : RecyclerView.Adapter<DnsAppPickerAdapter.ViewHolder>() {

    private var items: List<AppItem> = emptyList()

    fun setData(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelectedPackage(packageName: String) {
        selectedPackage = packageName
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_icon)
        val name: TextView = view.findViewById(R.id.tv_name)
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
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.packageName == selectedPackage
        holder.check.isClickable = false
        holder.itemView.setOnClickListener {
            onSelect(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
