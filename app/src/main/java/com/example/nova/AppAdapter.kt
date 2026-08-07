package com.example.nova

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(private val onToggle: (String, Boolean) -> Unit) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var items: List<AppItem> = emptyList()

    fun setData(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
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
        
        // Avoid listener firing during recycle
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.isSelected
        
        holder.check.setOnCheckedChangeListener { _, isChecked ->
            item.isSelected = isChecked
            onToggle(item.packageName, isChecked)
        }
    }

    override fun getItemCount() = items.size
}
