package com.hklab.airuler.model

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hklab.airuler.R

data class ModelRow(
    val model: String,
    val size: String?,
    val dateKey: String?,
    val dateDisplay: String?,
    val jsonUpdated: String?
)

class ModelUpdateAdapter(
    private val selectedModels: Set<String>,
    private val onLongPress: (model: String) -> Unit,
    private val onTap: (model: String) -> Unit,
) : RecyclerView.Adapter<ModelUpdateAdapter.VH>() {

    private var rows: List<ModelRow> = emptyList()

    fun submit(list: List<ModelRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_update_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        holder.bind(
            no = position + 1,
            row = r,
            selected = selectedModels.contains(r.model),
            onLongPress = onLongPress,
            onTap = onTap
        )
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val txtNo: TextView = v.findViewById(R.id.cNo)
        private val txtModel: TextView = v.findViewById(R.id.cModel)
        private val txtSize: TextView = v.findViewById(R.id.cSize)
        private val txtDate: TextView = v.findViewById(R.id.cDate)
        private val txtJson: TextView = v.findViewById(R.id.cJson)

        fun bind(
            no: Int,
            row: ModelRow,
            selected: Boolean,
            onLongPress: (String) -> Unit,
            onTap: (String) -> Unit,
        ) {
            txtNo.text = no.toString()
            txtModel.text = row.model
            txtSize.text = row.size ?: ""
            txtDate.text = row.dateDisplay ?: ""
            txtJson.text = row.jsonUpdated ?: ""

            itemView.setBackgroundResource(
                if (selected) R.drawable.bg_table_row_selected else android.R.color.transparent
            )

            itemView.setOnLongClickListener {
                onLongPress(row.model)
                true
            }

            itemView.setOnClickListener {
                onTap(row.model)
            }
        }
    }
}
