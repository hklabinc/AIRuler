package com.hklab.airuler

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import com.hklab.airuler.databinding.ActivityStatusBinding
import kotlin.math.roundToInt

class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val b = readBatteryInfo()
        val thermal = getThermalStatusLabel()

        binding.txtTemp.text = "온도: ${b.tempC?.let { String.format("%.1f°C", it) } ?: "N/A"} | Thermal: $thermal"
        binding.txtBattery.text = "배터리: ${b.percent?.let { "$it%" } ?: "N/A"} | ${b.statusLabel}"
        binding.txtMemory.text = buildMemoryText()
        binding.txtDevice.text = "Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})"
    }

    data class BatteryInfo(
        val percent: Int?,
        val tempC: Float?,
        val statusLabel: String
    )
    
    private fun readBatteryInfo(): BatteryInfo {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else null

        val tempTenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val tempC = if (tempTenth != Int.MIN_VALUE) (tempTenth / 10f) else null

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }

        return BatteryInfo(percent = percent, tempC = tempC, statusLabel = statusLabel)
    }

    private fun getThermalStatusLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "N/A"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    private fun buildMemoryText(): String {
        fun bytesToMbStr(bytes: Long): String =
            String.format("%.1fMB", bytes / 1024.0 / 1024.0)

        // ---- App(heap) memory ----
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val total = rt.totalMemory()
        val max = rt.maxMemory()
        val heapPct = if (max > 0) (used.toDouble() * 100.0 / max.toDouble()) else 0.0

        val heapLine =
            "App Heap: used=${bytesToMbStr(used)} / total=${bytesToMbStr(total)} / max=${bytesToMbStr(max)}" +
                    String.format(" (%.1f%%)", heapPct)

        // ---- System memory ----
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val sysPct = if (mi.totalMem > 0) (1.0 - mi.availMem.toDouble() / mi.totalMem.toDouble()) * 100.0 else 0.0
        val sysLine =
            "System Mem: avail=${bytesToMbStr(mi.availMem)} / total=${bytesToMbStr(mi.totalMem)}" +
                    String.format(" (used≈%.1f%%)", sysPct) +
                    if (mi.lowMemory) " [LOW]" else ""

        return heapLine + "\n" + sysLine
    }
}
