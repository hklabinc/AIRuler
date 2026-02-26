package com.hklab.airuler.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hklab.airuler.R

class AirulerInternalFileAdapter(
    private val onSelectionChanged: (selectedCount: Int) -> Unit,
    private val onItemClick: (InternalFileItem) -> Unit
) : ListAdapter<InternalFileItem, AirulerInternalFileAdapter.VH>(DIFF) {

    private val selected: LinkedHashSet<String> = LinkedHashSet() // file.absolutePath

    fun selectedFiles(): List<InternalFileItem> {
        val set = selected
        return currentList.filter { set.contains(it.file.absolutePath) }
    }

    fun clearSelection() {
        if (selected.isEmpty()) {
            onSelectionChanged(0)
            return
        }
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        selected.clear()
        selected.addAll(currentList.map { it.file.absolutePath })
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun isAllSelected(): Boolean = currentList.isNotEmpty() && selected.size == currentList.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_airuler_internal_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val isSel = selected.contains(item.file.absolutePath)
        holder.bind(item, isSel)

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        holder.itemView.setOnLongClickListener {
            toggle(item)
            true
        }
    }

    private fun toggle(item: InternalFileItem) {
        val key = item.file.absolutePath
        val added = !selected.contains(key)
        if (added) {
            selected.add(key)
        } else {
            selected.remove(key)
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtName: TextView = itemView.findViewById(R.id.txtFileName)
        private val shade: View = itemView.findViewById(R.id.selectionShade)
        private val check: ImageView = itemView.findViewById(R.id.imgCheck)

        fun bind(item: InternalFileItem, isSelected: Boolean) {
            txtName.text = if (isSelected) {
                "${item.displayName} (${item.sizeBytes}B)"
            } else {
                item.displayName
            }
            shade.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<InternalFileItem>() {
            override fun areItemsTheSame(oldItem: InternalFileItem, newItem: InternalFileItem): Boolean {
                return oldItem.file.absolutePath == newItem.file.absolutePath
            }

            override fun areContentsTheSame(oldItem: InternalFileItem, newItem: InternalFileItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
