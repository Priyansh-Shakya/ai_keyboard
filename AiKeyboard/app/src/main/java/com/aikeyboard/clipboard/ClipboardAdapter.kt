package com.aikeyboard.clipboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.R

class ClipboardAdapter(
    private val onPaste: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    fun updateItems(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val clipText: TextView = v.findViewById(R.id.clip_text)
        val btnPaste: View = v.findViewById(R.id.btn_paste_clip)
        val btnDelete: View = v.findViewById(R.id.btn_delete_clip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clipboard, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = items[position]
        holder.clipText.text = text
        holder.itemView.setOnClickListener { onPaste(text) }
        holder.btnPaste.setOnClickListener { onPaste(text) }
        holder.btnDelete.setOnClickListener { onDelete(text) }
    }
}
