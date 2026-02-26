package com.hklab.airuler.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hklab.airuler.R

class AirulerGalleryFolderAdapter(
    private val onClick: (AirulerGalleryFoldersActivity.FolderEntry) -> Unit
) : ListAdapter<AirulerGalleryFoldersActivity.FolderEntry, AirulerGalleryFolderAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_airuler_gallery_folder, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgThumb: ImageView = itemView.findViewById(R.id.imgFolderThumb)
        private val txtName: TextView = itemView.findViewById(R.id.txtFolderName)
        private val txtCount: TextView = itemView.findViewById(R.id.txtFolderCount)

        fun bind(item: AirulerGalleryFoldersActivity.FolderEntry) {
            txtName.text = item.title
            txtCount.text = "${item.imageCount}"

            // 최신 이미지 1장으로 폴더 썸네일 표시(없으면 placeholder)
            imgThumb.load(item.preview) {
                placeholder(android.R.drawable.ic_menu_report_image)
                error(android.R.drawable.ic_menu_report_image)
                size(256)
                allowHardware(false)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AirulerGalleryFoldersActivity.FolderEntry>() {
            override fun areItemsTheSame(
                oldItem: AirulerGalleryFoldersActivity.FolderEntry,
                newItem: AirulerGalleryFoldersActivity.FolderEntry
            ): Boolean = oldItem.kind == newItem.kind && oldItem.key == newItem.key && oldItem.extFilter == newItem.extFilter

            override fun areContentsTheSame(
                oldItem: AirulerGalleryFoldersActivity.FolderEntry,
                newItem: AirulerGalleryFoldersActivity.FolderEntry
            ): Boolean = oldItem == newItem
        }
    }
}