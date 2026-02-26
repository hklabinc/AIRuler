package com.hklab.airuler.gallery

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.hklab.airuler.R
import com.hklab.airuler.databinding.ActivityAirulerMeasureValuesBinding
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * ✅ DCIM/Result 이미지에 저장된 EXIF(UserComment) JSON(exifUserCommentJson)을 읽어서
 *    필름별 측정값을 표 형태로 보여주는 화면입니다.
 *
 * 요청사항:
 * - 첨부 그림(행=Film, 열=Measure) 형태를 전치(행=Measure, 열=Film) 해서 표시
 * - Measure 라벨에는 gt를 ( ) 로 표시
 * - 셀에는 value와 (err) 표시
 * - result==FAIL 인 셀은 빨간색으로 표시
 */
class AirulerMeasureValuesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_W = "extra_w"
        const val EXTRA_H = "extra_h"
    }

    private lateinit var binding: ActivityAirulerMeasureValuesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAirulerMeasureValuesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr.isNullOrBlank()) {
            finish()
            return
        }

        val uri = Uri.parse(uriStr)
        val name = intent.getStringExtra(EXTRA_NAME)
            ?: uri.lastPathSegment
            ?: "image"
        val w = intent.getIntExtra(EXTRA_W, -1)
        val h = intent.getIntExtra(EXTRA_H, -1)
        binding.txtSub.text = if (w > 0 && h > 0) {
            "$name  (${w} x ${h})"
        } else {
            name
        }

        val json = readExifUserCommentJson(uri)
        if (json.isNullOrBlank()) {
            binding.txtSub.text = "$name\n(EXIF UserComment가 없습니다)"
            return
        }

        try {
            val tableData = parseMeasureJson(json)
            renderTable(tableData)
        } catch (e: Exception) {
            binding.txtSub.text = "$name\n(JSON 파싱 실패: ${e.message})"
        }
    }

    // ---------------- EXIF / JSON ----------------

    private fun readExifUserCommentJson(uri: Uri): String? {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val raw = exif.getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null

                // Exif UserComment는 인코딩 prefix가 포함될 수 있어, JSON 구간만 안전하게 추출합니다.
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                val candidate = if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
                candidate.trim()
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class MeasureCell(
        val value: String,
        val err: String,
        val result: String
    )

    private data class TableData(
        val films: List<Int>,
        val measureOrder: List<String>,
        val gtByMeasure: Map<String, String>,
        val cellsByMeasureThenFilm: Map<String, Map<Int, MeasureCell>>
    )

    private fun parseMeasureJson(json: String): TableData {
        val root = JSONObject(json)
        val resultsArr = root.optJSONArray("results") ?: JSONArray()

        val films = mutableListOf<Int>()
        val measureOrder = mutableListOf<String>()
        val gtMap = mutableMapOf<String, String>()
        val cells = mutableMapOf<String, MutableMap<Int, MeasureCell>>()

        for (i in 0 until resultsArr.length()) {
            val filmObj = resultsArr.optJSONObject(i) ?: continue
            val filmNo = filmObj.optInt("film", i + 1)
            if (!films.contains(filmNo)) films.add(filmNo)

            val measuresArr = filmObj.optJSONArray("measures") ?: continue
            for (j in 0 until measuresArr.length()) {
                val mObj = measuresArr.optJSONObject(j) ?: continue
                val idx = mObj.optString("index").trim()
                if (idx.isBlank()) continue

                if (!measureOrder.contains(idx)) measureOrder.add(idx)

                if (!gtMap.containsKey(idx) && mObj.has("gt")) {
                    gtMap[idx] = formatDouble(mObj.optDouble("gt"))
                }

                val valueStr = if (mObj.has("value")) formatDouble(mObj.optDouble("value")) else mObj.optString("value", "")
                val errStr = if (mObj.has("err")) formatDouble(mObj.optDouble("err")) else mObj.optString("err", "")
                val resStr = mObj.optString("result", "")

                val cell = MeasureCell(
                    value = valueStr,
                    err = errStr,
                    result = resStr
                )

                val perFilm = cells.getOrPut(idx) { mutableMapOf() }
                perFilm[filmNo] = cell
            }
        }

        return TableData(
            films = films,
            measureOrder = measureOrder,
            gtByMeasure = gtMap,
            cellsByMeasureThenFilm = cells
        )
    }

    private fun formatDouble(x: Double): String {
        val s = String.format(Locale.US, "%.3f", x)
        return s.trimEnd('0').trimEnd('.')
    }

    // ---------------- UI table rendering ----------------

    private fun renderTable(data: TableData) {
        val table = binding.tableMeasures
        table.removeAllViews()

        // Header row: [Measure(gt)] + [Film #]
        val header = TableRow(this)
        header.addView(makeCell("Measure (gt)", bold = true, alignCenter = false))

        for (filmNo in data.films) {
            header.addView(makeCell("#$filmNo", bold = true, alignCenter = true))
        }
        table.addView(header)

        // Body rows: each measure -> per film values
        for (measureId in data.measureOrder) {
            val row = TableRow(this)

            val gt = data.gtByMeasure[measureId].orEmpty()
            val leftText = if (gt.isNotBlank()) "$measureId ($gt)" else measureId
            row.addView(makeCell(leftText, bold = true, alignCenter = false))

            for (filmNo in data.films) {
                val cell = data.cellsByMeasureThenFilm[measureId]?.get(filmNo)

                val value = cell?.value?.orEmpty() ?: ""
                val err = cell?.err?.orEmpty() ?: ""
                val text = when {
                    value.isNotBlank() && err.isNotBlank() -> "$value ($err)"
                    value.isNotBlank() -> value
                    err.isNotBlank() -> "($err)"
                    else -> ""
                }

                val tv = makeCell(text, bold = false, alignCenter = true)

                // FAIL이면 빨간색으로 표시
                if (cell?.result.equals("FAIL", ignoreCase = true)) {
                    tv.setTextColor(Color.RED)
                }

                row.addView(tv)
            }

            table.addView(row)
        }
    }

    private fun makeCell(text: String, bold: Boolean, alignCenter: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setPadding(dp(8), dp(6), dp(8), dp(6))
        tv.textSize = 13f
        tv.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.gravity = if (alignCenter) (Gravity.CENTER) else (Gravity.START or Gravity.CENTER_VERTICAL)
        tv.setBackgroundResource(R.drawable.bg_table_cell)
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
