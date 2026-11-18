package com.carhelper.signs.detection

/**
 * Lọc nhiễu đa khung hình: chỉ báo một biển khi nó xuất hiện ở ít nhất
 * [minHits] trong [window] frame gần nhất, và không báo lại trong [cooldownMs].
 *
 * Không thread-safe: chỉ gọi từ thread phân tích ảnh.
 */
class VoteTracker(
    private val window: Int = 5,
    private val minHits: Int = 3,
    private val cooldownMs: Long = 30_000,
) {
    private val history = ArrayDeque<Set<Int>>()
    private val lastAnnouncedAt = HashMap<Int, Long>()

    init {
        require(window > 0 && minHits in 1..window)
    }

    /** Trả về các classId cần đọc lên ở frame này. */
    fun update(classIds: Set<Int>, nowMs: Long): List<Int> {
        history.addLast(classIds)
        while (history.size > window) history.removeFirst()

        val toAnnounce = ArrayList<Int>()
        for (id in classIds) {
            val hits = history.count { id in it }
            if (hits < minHits) continue
            val last = lastAnnouncedAt[id]
            if (last != null && nowMs - last < cooldownMs) continue
            lastAnnouncedAt[id] = nowMs
            toAnnounce.add(id)
        }
        return toAnnounce
    }

    fun reset() {
        history.clear()
        lastAnnouncedAt.clear()
    }
}
