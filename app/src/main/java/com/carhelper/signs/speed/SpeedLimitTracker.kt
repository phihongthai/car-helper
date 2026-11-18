package com.carhelper.signs.speed

/**
 * Giữ giới hạn tốc độ đang áp dụng và quyết định khi nào cảnh báo quá tốc độ.
 *
 * - Giới hạn được đặt khi đọc được biển tốc độ, thay bằng biển mới,
 *   và tự hết sau [expireMeters] mét (model không nhận diện được biển "hết hạn chế",
 *   còn theo QCVN 41 biển hết hiệu lực ở nút giao kế tiếp).
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
) {
    private var limit: Int? = null
    private var setAtMeters = 0.0
    private var lastOdometer = 0.0
    private var overSince: Long? = null
    private var lastAlertAt: Long? = null

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
    }

    /**
     * Gọi mỗi lần có vị trí GPS mới. [odometerMeters] là quãng đường cộng dồn.
     * Trả về true nếu cần đọc cảnh báo quá tốc độ ngay bây giờ.
     */
    @Synchronized
    fun onSpeed(speedKmh: Float, nowMs: Long, odometerMeters: Double): Boolean {
        lastOdometer = odometerMeters
        if (limit != null && odometerMeters - setAtMeters > expireMeters) limit = null
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
}
