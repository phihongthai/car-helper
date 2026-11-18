package com.carhelper.signs.detection

import kotlin.math.max
import kotlin.math.min

/** Một loại biển báo: mã QCVN 41 và câu sẽ được đọc lên. [silent] = chỉ hiện, không đọc. */
data class SignLabel(val code: String, val speech: String, val silent: Boolean = false)

/** Hình chữ nhật toạ độ chuẩn hoá [0,1]. Không dùng RectF để test được trên JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height

    fun clamped(): Box = Box(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    fun iou(other: Box): Float {
        val iw = min(right, other.right) - max(left, other.left)
        val ih = min(bottom, other.bottom) - max(top, other.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

data class Detection(
    val classId: Int,
    val label: SignLabel,
    val score: Float,
    /** Toạ độ chuẩn hoá theo khung hình đã xoay thẳng (upright). */
    val box: Box,
)

data class FrameResult(
    val detections: List<Detection>,
    val frameWidth: Int,
    val frameHeight: Int,
    val roi: Box,
    val inferenceMs: Long,
)

sealed interface DetectorStatus {
    data object Loading : DetectorStatus
    data class Ready(val classCount: Int, val usesGpu: Boolean) : DetectorStatus
    data class Unavailable(val reason: String) : DetectorStatus
}

object LabelParser {
    /** Mỗi dòng: `MÃ|câu đọc[|im]`. Dòng trống và dòng bắt đầu bằng `#` bị bỏ qua. */
    fun parse(lines: Sequence<String>): List<SignLabel> = lines
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split("|", limit = 3)
            val code = parts[0].trim()
            SignLabel(
                code = code,
                speech = parts.getOrNull(1)?.trim()?.ifEmpty { null } ?: code,
                silent = parts.getOrNull(2)?.trim().equals("im", ignoreCase = true),
            )
        }
        .toList()
}

object BoxUtils {
    /**
     * NMS không phân biệt lớp: một biển thật chỉ nên có một nhãn,
     * kể cả khi model phân vân giữa "50" và "60".
     */
    fun nms(detections: List<Detection>, iouThreshold: Float, maxDetections: Int = 20): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in detections.sortedByDescending { it.score }) {
            if (kept.size >= maxDetections) break
            if (kept.none { it.box.iou(d.box) > iouThreshold }) kept.add(d)
        }
        return kept
    }
}
