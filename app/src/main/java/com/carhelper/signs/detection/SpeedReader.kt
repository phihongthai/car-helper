package com.carhelper.signs.detection

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Đọc con số trên biển hạn chế tốc độ bằng ML Kit Text Recognition
 * (model đóng gói sẵn trong APK, chạy offline).
 *
 * Model nhận diện chỉ có một lớp "hạn chế tốc độ" cho mọi giá trị,
 * nên số được đọc riêng từ ảnh cắt của biển.
 * Gọi đồng bộ, chỉ dùng trên thread phân tích ảnh (không phải main thread).
 */
class SpeedReader : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(crop: Bitmap): Int? = try {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(crop, 0)), TIMEOUT_MS, TimeUnit.MILLISECONDS)
        SpeedParser.parse(text.text)
    } catch (t: Throwable) {
        Log.w(TAG, "OCR lỗi", t)
        null
    }

    override fun close() = recognizer.close()

    companion object {
        private const val TAG = "SpeedReader"
        private const val TIMEOUT_MS = 400L

        /** Mã trong labels.txt của lớp biển hạn chế tốc độ. */
        const val SPEED_CODE = "P.127"

        /** classId ảo cho từng giá trị tốc độ, để bỏ phiếu và chống lặp riêng từng biển. */
        fun speedClassId(kmh: Int): Int = SPEED_ID_BASE + kmh

        /** Giá trị tốc độ nếu [classId] là classId ảo của biển tốc độ đã đọc số, ngược lại null. */
        fun speedFromClassId(classId: Int): Int? = if (classId > SPEED_ID_BASE) classId - SPEED_ID_BASE else null

        private const val SPEED_ID_BASE = 10_000

        fun speedLabel(kmh: Int) = SignLabel("$SPEED_CODE $kmh", "Tốc độ tối đa $kmh")
    }
}

object SpeedParser {
    /** Các giá trị có trên biển P.127 ở Việt Nam. */
    private val VALID = setOf(5, 10, 15, 20, 25, 30, 35, 40, 50, 60, 70, 80, 90, 100, 110, 120)
    private val NUMBER = Regex("\\d{1,3}")

    /**
     * Lấy số tốc độ hợp lệ trong chữ OCR, bỏ qua "km/h" và nhiễu.
     * Có nhiều số thì lấy số lớn nhất: mảnh vụn OCR thường là một chữ số lẻ.
     */
    fun parse(text: String): Int? {
        // OCR hay nhầm chữ O/o với số 0 trên biển.
        val normalized = text.replace('O', '0').replace('o', '0')
        return NUMBER.findAll(normalized).mapNotNull { it.value.toIntOrNull() }.filter { it in VALID }.maxOrNull()
    }
}
