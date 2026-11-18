package com.carhelper.signs.speed

/**
 * Giữ giới hạn tốc độ đang áp dụng và quyết định khi nào cảnh báo quá tốc độ.
 *
 * - Giới hạn được đặt khi đọc được biển tốc độ, thay bằng biển mới.
 * - Theo QCVN 41 biển hết hiệu lực ở nút giao kế tiếp. Không có bản đồ nên coi
 *   "đổi hướng > [turnDegrees] trong [turnWindowMeters] mét" (rẽ, quay đầu) là đã qua nút giao.
 *   Đi thẳng qua ngã tư thì không phát hiện được, nên vẫn tự hết sau [expireMeters] mét.
 * - Chỉ cảnh báo khi vượt quá [toleranceKmh] liên tục [sustainMs] (lọc nhiễu GPS),
 *   và nhắc lại tối đa mỗi [repeatMs] nếu vẫn còn quá tốc độ.
 *
 * Thread-safe: biển đến từ thread phân tích ảnh, tốc độ đến từ callback GPS.
 */
class SpeedLimitTracker(
    val toleranceKmh: Int = 3,
    private val sustainMs: Long = 3_000,
    private val repeatMs: Long = 20_000,
    private val expireMeters: Double = 2_000.0,
    private val turnDegrees: Float = 60f,
    private val turnWindowMeters: Double = 60.0,
    /** Dưới tốc độ này hướng GPS không đáng tin (xe đứng yên hướng nhảy lung tung). */
    private val minBearingSpeedKmh: Float = 10f,
) {
    private var limit: Int? = null
    private var setAtMeters = 0.0
    private var lastOdometer = 0.0
    private var overSince: Long? = null
    private var lastAlertAt: Long? = null
    /** (quãng đường, hướng) trong [turnWindowMeters] mét gần nhất. */
    private val headings = ArrayDeque<Pair<Double, Float>>()

    /** Giới hạn đang áp dụng, null nếu chưa có hoặc đã hết hiệu lực. */
    @get:Synchronized
    val currentLimit: Int? get() = limit

    /** Gọi khi đọc được biển tốc độ (đã qua bỏ phiếu đa khung hình). */
    @Synchronized
    fun onLimitSign(kmh: Int) {
        if (kmh != limit) {
            overSince = null
            lastAlertAt = null
        }
        limit = kmh
        setAtMeters = lastOdometer
        headings.clear() // chỉ tính các khúc rẽ SAU biển
    }

    /**
     * Gọi mỗi lần có vị trí GPS mới. [odometerMeters] là quãng đường cộng dồn,
     * [bearingDeg] là hướng đi 0..360 (null nếu GPS không có).
     * Trả về true nếu cần đọc cảnh báo quá tốc độ ngay bây giờ.
     */
    @Synchronized
    fun onSpeed(speedKmh: Float, nowMs: Long, odometerMeters: Double, bearingDeg: Float? = null): Boolean {
        lastOdometer = odometerMeters
        if (limit != null && odometerMeters - setAtMeters > expireMeters) limit = null
        if (limit != null && bearingDeg != null && speedKmh >= minBearingSpeedKmh && turned(odometerMeters, bearingDeg)) {
            limit = null
        }
        val lim = limit
        if (lim == null || speedKmh <= lim + toleranceKmh) {
            overSince = null
            return false
        }
        val since = overSince ?: nowMs.also { overSince = it }
        if (nowMs - since < sustainMs) return false
        val last = lastAlertAt
        if (last != null && nowMs - last < repeatMs) return false
        lastAlertAt = nowMs
        return true
    }

    private fun turned(odometer: Double, bearing: Float): Boolean {
        while (headings.isNotEmpty() && odometer - headings.first().first > turnWindowMeters) headings.removeFirst()
        val turned = headings.any { angleDiff(it.second, bearing) > turnDegrees }
        headings.addLast(odometer to bearing)
        if (turned) headings.clear()
        return turned
    }

    companion object {
        /** Độ lệch nhỏ nhất giữa hai hướng, 0..180. */
        fun angleDiff(a: Float, b: Float): Float {
            val d = ((a - b) % 360f + 360f) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
