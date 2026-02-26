package com.hklab.airuler.model

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.hklab.airuler.R

class ModelSelectAdapter(
    private val onSelect: (String) -> Unit,
    private val onAdd: () -> Unit,
    private val onAddLong: () -> Unit,
    private val onDownloadOrUpdate: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val modelExists: (String) -> Boolean
) : RecyclerView.Adapter<ModelSelectAdapter.VH>() {

    private var items: List<ModelItem> = emptyList()

    private var actionTarget: String? = null
    private var showActionsForAll: Boolean = false
    private val progressMap = mutableMapOf<String, Int>()  // name -> 0..100

    fun submit(list: List<ModelItem>) {
        items = list
        actionTarget = null
        // showActionsForAll 은 유지 ("Check All Models" 상태를 refresh로 끊지 않음)
        notifyDataSetChanged()
    }

    fun clearActions() {
        if (actionTarget != null || showActionsForAll) {
            actionTarget = null
            showActionsForAll = false
            notifyDataSetChanged()
        }
    }

    /** "Check All Models": 모든 모델 타일에 액션 패널을 표시/해제 */
    fun toggleActionsForAll(): Boolean {
        showActionsForAll = !showActionsForAll
        actionTarget = null
        notifyDataSetChanged()
        return showActionsForAll
    }

    fun isActionsForAll(): Boolean = showActionsForAll
    private fun buildStatusText(ctx: android.content.Context, model: String): Pair<CharSequence, Boolean> {
        val hasTflite = ModelFileStore.downloadedModelFile(ctx, model).exists()
        val hasJson = ModelFileStore.downloadedModelJsonFile(ctx, model).exists()
        val hasObj = ModelFileStore.downloadedModelJpgFile(ctx, model).exists()

        // ✅ 요청사항: M | J | O 만 표시 (숫자# 제거, R 제거)
        val s = "M|J|O"
        val sp = SpannableString(s)

        val red = ContextCompat.getColor(ctx, android.R.color.holo_red_light)
        fun markMissing(index: Int) {
            sp.setSpan(ForegroundColorSpan(red), index, index + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), index, index + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 문자 위치: 0=M, 2=J, 4=O
        if (!hasTflite) markMissing(0)
        if (!hasJson) markMissing(2)
        if (!hasObj) markMissing(4)

        val anyMissing = (!hasTflite) || (!hasJson) || (!hasObj)
        return sp to anyMissing
    }


    fun showProgress(model: String, percent: Int?) {
        if (percent == null) progressMap.remove(model) else progressMap[model] = percent
        val idx = items.indexOfFirst { it is ModelItem.Model && it.name == model }
        if (idx >= 0) notifyItemChanged(idx)
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position]) {
            is ModelItem.Model -> 0
            is ModelItem.Add -> 1
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_tile, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        val img = holder.itemView.findViewById<ImageView>(R.id.imgIcon)
        val txtName = holder.itemView.findViewById<TextView>(R.id.txtName)
        val shade = holder.itemView.findViewById<View>(R.id.actionShade)
        val panel = holder.itemView.findViewById<View>(R.id.actionPanel)
        val btnDownload = holder.itemView.findViewById<MaterialButton>(R.id.btnDownload)
        val btnStatus = holder.itemView.findViewById<MaterialButton>(R.id.btnStatus)
        val btnDelete = holder.itemView.findViewById<MaterialButton>(R.id.btnDelete)

        val progPanel = holder.itemView.findViewById<View>(R.id.progressPanel)
        val txtProg = holder.itemView.findViewById<TextView>(R.id.txtProgress)

        when (item) {
            is ModelItem.Add -> {
                txtName.text = "Add"
                img.setImageResource(R.drawable.add)

                shade.visibility = View.GONE
                panel.visibility = View.GONE
                progPanel.visibility = View.GONE

                holder.itemView.setOnClickListener {
                    clearActions()
                    onAdd()
                }
                holder.itemView.setOnLongClickListener {
                    clearActions()
                    onAddLong()
                    true
                }
            }

            is ModelItem.Model -> {
                val name = item.name
                txtName.text = name

                // ✅ 아이콘 로딩 우선순위:
                // 1) 내부 models/<name>.jpg (다운로드된 파일)
                // 2) assets/model_icons/<name>.jpg (기본 seed)
                val downloadedJpg = ModelFileStore.downloadedModelJpgFile(ctx, name)
                when {
                    downloadedJpg.exists() -> {
                        img.load(downloadedJpg) {
                            crossfade(false)
                            allowHardware(false)
                        }
                    }
                    ModelFileStore.assetIconExists(ctx, name) -> {
                        img.load("file:///android_asset/model_icons/$name.jpg") {
                            crossfade(false)
                            allowHardware(false)
                        }
                    }
                    else -> {
                        img.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                }

                val showingActions = showActionsForAll || (actionTarget == name)
                val downloadingPercent = progressMap[name]

                // progress overlay 우선
                if (downloadingPercent != null) {
                    progPanel.visibility = View.VISIBLE
                    txtProg.text = "${downloadingPercent}%"
                    shade.visibility = View.GONE
                    panel.visibility = View.GONE
                } else {
                    progPanel.visibility = View.GONE
                    shade.visibility = if (showingActions) View.VISIBLE else View.GONE
                    panel.visibility = if (showingActions) View.VISIBLE else View.GONE

                    // ✅ Status 텍스트/테두리: "M|J|O" 형태로 표시
                    //    누락된 항목은 빨간색(+bold), 하나라도 누락이면 버튼 테두리도 빨간색
                    if (showingActions) {
                        val (statusText, anyMissing) = buildStatusText(ctx, name)
                        btnStatus.text = statusText

                        val stroke = if (anyMissing) {
                            ContextCompat.getColor(ctx, android.R.color.holo_red_light)
                        } else {
                            ContextCompat.getColor(ctx, android.R.color.white)
                        }
                        btnStatus.strokeColor = ColorStateList.valueOf(stroke)
                    } else {
                        // ✅ 재활용(view recycling) 대비: 기본 상태로 복원
                        btnStatus.text = "Status"
                        btnStatus.strokeColor = ColorStateList.valueOf(
                            ContextCompat.getColor(ctx, android.R.color.white)
                        )
                    }
                }

                btnDownload.text = if (modelExists(name)) "Update" else "Download"

                // ✅ shade를 누르면 액션 패널 닫기
                shade.setOnClickListener { clearActions() }

                holder.itemView.setOnClickListener {
                    val cur = actionTarget
                    when {
                        cur == name -> {
                            // 같은 아이템을 한번 더 누르면 액션 닫기
                            actionTarget = null
                            notifyDataSetChanged()
                        }
                        cur != null && cur != name -> {
                            // 다른 아이템 누르면: 액션 닫고 선택
                            actionTarget = null
                            notifyDataSetChanged()
                            onSelect(name)
                        }
                        else -> {
                            onSelect(name)
                        }
                    }
                }

                holder.itemView.setOnLongClickListener {
                    actionTarget = if (actionTarget == name) null else name
                    notifyDataSetChanged()
                    true
                }

                btnDownload.setOnClickListener {
                    actionTarget = null
                    notifyDataSetChanged()
                    onDownloadOrUpdate(name)
                }

                // ✅ Status 버튼: Gallery 폴더 목록 화면으로 이동 (Gallery 버튼과 동일 동작)
                btnStatus.isEnabled = true
                btnStatus.isClickable = true
                btnStatus.setOnClickListener {
                    // 패널은 닫아주고 이동 (원치 않으면 이 블록 삭제 가능)
                    if (!showActionsForAll) {
                        actionTarget = null
                        notifyDataSetChanged()
                    }
                    onStatus(name)   // ★ 핵심: Activity 쪽 콜백 호출
                }

                btnDelete.setOnClickListener {
                    actionTarget = null
                    notifyDataSetChanged()
                    onDelete(name)
                }
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)
}
