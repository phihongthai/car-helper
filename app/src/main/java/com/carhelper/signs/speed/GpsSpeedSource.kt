package com.carhelper.signs.speed

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import java.io.Closeable

/**
 * Tốc độ xe từ GPS (LocationManager, không cần Google Play services) và quãng đường cộng dồn.
 * Callback chạy trên main thread.
 */
class GpsSpeedSource(
    context: Context,
    private val onUpdate: (speedKmh: Float, odometerMeters: Double, bearingDeg: Float?, nowMs: Long) -> Unit,
) : Closeable {

    private val manager = context.getSystemService(LocationManager::class.java)
    private var last: Location? = null
    private var odometer = 0.0

    private val listener = LocationListener { loc ->
        if (!loc.hasSpeed()) return@LocationListener
        // Bỏ điểm sai số lớn khi cộng quãng đường (trong hầm, giữa nhà cao tầng).
        val prev = last
        if (loc.accuracy <= MAX_ACCURACY_M) {
            if (prev != null) odometer += prev.distanceTo(loc)
            last = loc
        }
        onUpdate(loc.speed * 3.6f, odometer, if (loc.hasBearing()) loc.bearing else null, loc.elapsedRealtimeNanos / 1_000_000)
    }

    /** Cần quyền ACCESS_FINE_LOCATION đã được cấp. */
    @SuppressLint("MissingPermission")
    fun start() {
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
    }

    override fun close() = manager.removeUpdates(listener)

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val MAX_ACCURACY_M = 30f
    }
}
