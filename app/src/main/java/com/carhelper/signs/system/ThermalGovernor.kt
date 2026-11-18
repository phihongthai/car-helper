package com.carhelper.signs.system

import android.content.Context
import android.os.PowerManager
import java.io.Closeable

/**
 * Tự giảm tần suất chạy model khi máy nóng lên:
 * bình thường 5 FPS -> 3 FPS -> 2 FPS -> 1 FPS.
 */
class ThermalGovernor(context: Context, private val onChange: (status: Int, intervalMs: Long) -> Unit) : Closeable {

    private val powerManager = context.getSystemService(PowerManager::class.java)

    @Volatile
    var intervalMs: Long = intervalFor(powerManager.currentThermalStatus)
        private set

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        intervalMs = intervalFor(status)
        onChange(status, intervalMs)
    }

    init {
        powerManager.addThermalStatusListener(listener)
        onChange(powerManager.currentThermalStatus, intervalMs)
    }

    override fun close() {
        powerManager.removeThermalStatusListener(listener)
    }

    companion object {
        fun intervalFor(status: Int): Long = when {
            status <= PowerManager.THERMAL_STATUS_LIGHT -> 200L
            status == PowerManager.THERMAL_STATUS_MODERATE -> 333L
            status == PowerManager.THERMAL_STATUS_SEVERE -> 500L
            else -> 1000L
        }

        fun describe(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "mát"
            PowerManager.THERMAL_STATUS_LIGHT -> "ấm"
            PowerManager.THERMAL_STATUS_MODERATE -> "nóng"
            PowerManager.THERMAL_STATUS_SEVERE -> "rất nóng"
            else -> "quá nóng"
        }
    }
}
