package com.hklab.airuler.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hklab.airuler.R

/**
 * 모델 선택 화면용 어댑터
 *
 * ✅ 요구사항 반영
 * - (삭제) + Add 타일
 * - (삭제) 롱프레스 액션 패널(Status/Update/Delete)
 * - (유지) 아이콘/모델명 표시 + 탭 시 선택
 */
class ModelSelectAdapter(
    private val onSelect: (String) -> Unit,
) : RecyclerView.Adapter<ModelSelectAdapter.VH>() {

    private var items: List<ModelItem.Model> = emptyList()

    fun submit(list: List<ModelItem.Model>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_tile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        val img = holder.itemView.findViewById<ImageView>(R.id.imgIcon)
        val txtName = holder.itemView.findViewById<TextView>(R.id.txtName)

        // (구 UI 잔재) 액션/프로그레스 패널은 항상 숨김
        holder.itemView.findViewById<View>(R.id.actionShade)?.visibility = View.GONE
        holder.itemView.findViewById<View>(R.id.actionPanel)?.visibility = View.GONE
        holder.itemView.findViewById<View>(R.id.progressPanel)?.visibility = View.GONE

        val name = item.name
        txtName.text = name

        // ✅ M2379-00만 가로 크기를 타일 폭에 맞춰 축소해서 전체 이미지가 보이도록 처리
        // - 기존 기본값(centerCrop)은 다른 모델에 그대로 유지
        // - RecyclerView 재사용으로 이전 scaleType 이 남지 않도록 매 바인딩 시 명시적으로 재설정
        val baseName = name.trim().substringBefore("_FO")
        img.scaleType = if (baseName.equals("M2379-00", ignoreCase = true)) {
            ImageView.ScaleType.FIT_XY
        } else {
            ImageView.ScaleType.CENTER_CROP
        }

        // ✅ 아이콘 로딩: assets/overlay/<name>.jpg (또는 jpeg/png)
        val uri = ModelFileStore.overlayAssetUriOrNull(ctx, name)
        if (uri != null) {
            img.load(uri) {
                crossfade(false)
                allowHardware(false)
            }
        } else {
            img.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        holder.itemView.setOnClickListener { onSelect(name) }
        holder.itemView.setOnLongClickListener(null)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)
}
