package com.hklab.airuler.gallery

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hklab.airuler.R
import com.hklab.airuler.media.AirulerMediaStore

class AirulerGalleryAdapter(
    private val onSelectionChanged: (selectedCount: Int, lastSelectedItem: AirulerMediaStore.ImageItem?) -> Unit,
    private val onItemClick: (AirulerMediaStore.ImageItem) -> Unit
) : ListAdapter<AirulerMediaStore.ImageItem, AirulerGalleryAdapter.VH>(DIFF) {

    private val selected: LinkedHashSet<Uri> = LinkedHashSet()
    private var lastSelected: AirulerMediaStore.ImageItem? = null

    fun selectedUris(): List<Uri> = selected.toList()

    fun clearSelection() {
        if (selected.isEmpty()) {
            onSelectionChanged(0, null)
            return
        }
        selected.clear()
        lastSelected = null
        notifyDataSetChanged()
        onSelectionChanged(0, null)
    }

    fun selectAll() {
        selected.clear()
        selected.addAll(currentList.map { it.uri })
        lastSelected = currentList.lastOrNull()
        notifyDataSetChanged()
        onSelectionChanged(selected.size, lastSelected)
    }

    fun isAllSelected(): Boolean {
        return currentList.isNotEmpty() && selected.size == currentList.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_airuler_gallery_image, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val isSel = selected.contains(item.uri)
        holder.bind(item, isSel)

        // ✅ 짧게 클릭: 이미지 뷰어로 열기
        holder.itemView.setOnClickListener { onItemClick(item) }

        // ✅ 길게 클릭: 선택 토글(Toast 제거)
        holder.itemView.setOnLongClickListener {
            toggle(item)
            true
        }
    }

    private fun toggle(item: AirulerMediaStore.ImageItem) {
        val uri = item.uri
        val added = !selected.contains(uri)
        if (added) {
            selected.add(uri)
            lastSelected = item
        } else {
            selected.remove(uri)
            if (lastSelected?.uri == uri) {
                lastSelected = currentList.firstOrNull { selected.contains(it.uri) }
            }
        }
        notifyDataSetChanged()
        onSelectionChanged(selected.size, lastSelected)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgThumb: ImageView = itemView.findViewById(R.id.imgThumb)
        private val shade: View = itemView.findViewById(R.id.selectionShade)
        private val check: ImageView = itemView.findViewById(R.id.imgCheck)

        fun bind(item: AirulerMediaStore.ImageItem, isSelected: Boolean) {
            imgThumb.load(item.uri) {
                size(256)
                allowHardware(false)
            }
            shade.visibility = if (isSelected) View.VISIBLE else View.GONE
            check.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AirulerMediaStore.ImageItem>() {
            override fun areItemsTheSame(
                oldItem: AirulerMediaStore.ImageItem,
                newItem: AirulerMediaStore.ImageItem
            ) = oldItem.uri == newItem.uri

            override fun areContentsTheSame(
                oldItem: AirulerMediaStore.ImageItem,
                newItem: AirulerMediaStore.ImageItem
            ) = oldItem == newItem
        }
    }
}