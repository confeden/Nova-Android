package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class WarpConfigCardAdapter(
    private val onDelete: (String) -> Unit,
) : RecyclerView.Adapter<WarpConfigCardAdapter.ViewHolder>() {

    private var items: List<WarpConfigCardSnapshot> = emptyList()

    fun setItems(newItems: List<WarpConfigCardSnapshot>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_warp_config, parent, false)
        NovaFontHelper.apply(view)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.current.visibility = if (item.current) View.VISIBLE else View.GONE
        holder.meta.text = item.meta
        holder.body.text = item.body
        val accentColor = if (item.userImported) "#F3C94A" else "#50C878"
        holder.meta.setTextColor(Color.parseColor(accentColor))
        holder.itemView.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (item.userImported) "#241E10" else "#161E1A")
        )
        holder.copy.setOnClickListener {
            val clipboard = holder.itemView.context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("warp-config", item.body))
            Toast.makeText(holder.itemView.context, "Конфигурация скопирована", Toast.LENGTH_SHORT).show()
        }
        holder.delete.setOnClickListener {
            onDelete(item.id)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_config_title)
        val current: TextView = view.findViewById(R.id.tv_config_current)
        val meta: TextView = view.findViewById(R.id.tv_config_meta)
        val body: TextView = view.findViewById(R.id.tv_config_body)
        val delete: TextView = view.findViewById(R.id.btn_delete_config)
        val copy: TextView = view.findViewById(R.id.btn_copy_config)
    }
}
