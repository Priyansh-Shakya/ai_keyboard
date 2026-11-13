package com.aikeyboard.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.R
import com.aikeyboard.data.Note

class NotesAdapter(
    private val onPaste: (Note) -> Unit,
    private val onEdit: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : RecyclerView.Adapter<NotesAdapter.VH>() {

    private val notes = mutableListOf<Note>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.note_title)
        val preview: TextView = view.findViewById(R.id.note_preview)
        val btnPaste: TextView = view.findViewById(R.id.btn_paste_note)
        val btnDelete: TextView = view.findViewById(R.id.btn_delete_note)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val note = notes[position]
        holder.title.text = note.title.ifEmpty { "Untitled" }
        holder.preview.text = note.content.take(80).replace("\n", " ")
        holder.itemView.setOnClickListener { onEdit(note) }     // tap card = edit
        holder.btnPaste.setOnClickListener { onPaste(note) }    // ↑ = insert into the app
        holder.btnDelete.setOnClickListener { onDelete(note) }
    }

    override fun getItemCount() = notes.size

    fun updateNotes(newNotes: List<Note>) {
        notes.clear(); notes.addAll(newNotes); notifyDataSetChanged()
    }

    fun addNote(note: Note) {
        notes.add(0, note); notifyItemInserted(0)
    }

    fun replaceNote(note: Note) {
        val i = notes.indexOfFirst { it.id == note.id }
        if (i >= 0) { notes[i] = note; notifyItemChanged(i) }
    }

    fun removeNote(id: String) {
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) { notes.removeAt(i); notifyItemRemoved(i) }
    }
}
