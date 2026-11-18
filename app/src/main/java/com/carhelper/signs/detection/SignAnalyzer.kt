package com.carhelper.signs.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.Closeable
import kotlin.math.min

/**
 * Nhận frame từ CameraX (RGBA, trong RAM, không ghi xuống đĩa), giới hạn tần suất,
 * cắt vùng quan tâm (ROI), letterbox vào input model rồi chạy nhận diện.
 *
 * Chạy trên một executor đơn luồng. Model được tạo lười ở frame đầu tiên để
 * GPU delegate nằm cùng thread với lúc chạy.
 */
class SignAnalyzer(
    private val detectorFactory: () -> SignDetector,
    /** Vùng cắt chuẩn hoá trên khung đã xoay thẳng. */
    private val roi: Box,
    private val intervalMs: () -> Long,
    private val onStatus: (DetectorStatus) -> Unit,
    private val onResult: (FrameResult) -> Unit,
    private val scoreThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.45f,
) : ImageAnalysis.Analyzer, Closeable {

    private var detector: SignDetector? = null
    private var speedReader: SpeedReader? = null
    private var initAttempted = false
    private var lastTimestampNs = -1L

    private var inputBitmap: Bitmap? = null
    private var inputCanvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val padColor = Color.rgb(114, 114, 114) // giống letterbox của Ultralytics

    override fun analyze(image: ImageProxy) {
        try {
            process(image)
        } catch (t: Throwable) {
            Log.e(TAG, "Lỗi phân tích frame", t)
        } finally {
            image.close() // bắt buộc, kể cả frame bị bỏ qua
        }
    }

    private fun process(image: ImageProxy) {
        val ts = image.imageInfo.timestamp
        if (lastTimestampNs >= 0 && ts - lastTimestampNs < intervalMs() * 1_000_000L) return
        lastTimestampNs = ts

        val det = ensureDetector() ?: return
        val start = SystemClock.elapsedRealtime()

        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        val uprightW = if (rotation % 180 == 0) src.width else src.height
        val uprightH = if (rotation % 180 == 0) src.height else src.width

        val roiL = roi.left * uprightW
        val roiT = roi.top * uprightH
        val roiW = roi.width * uprightW
        val roiH = roi.height * uprightH
        val inW = det.inputWidth
        val inH = det.inputHeight
        val scale = min(inW / roiW, inH / roiH)
        val padX = (inW - roiW * scale) / 2f
        val padY = (inH - roiH * scale) / 2f

        // Một phép biến đổi duy nhất: xoay thẳng -> cắt ROI -> co giãn -> căn giữa.
        uprightMatrix(matrix, rotation, src)
        matrix.postTranslate(-roiL, -roiT)
        matrix.postScale(scale, scale)
        matrix.postTranslate(padX, padY)

        val bmp = inputBitmap ?: Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888).also {
            inputBitmap = it
            inputCanvas = Canvas(it)
        }
        val canvas = inputCanvas!!
        canvas.drawColor(padColor)
        canvas.save()
        canvas.clipRect(padX, padY, padX + roiW * scale, padY + roiH * scale)
        canvas.drawBitmap(src, matrix, paint)
        canvas.restore()

        val raw = det.detect(bmp, scoreThreshold, iouThreshold)

        // Đưa toạ độ từ không gian input về khung hình đầy đủ.
        fun mapX(x: Float) = ((x * inW - padX) / scale + roiL) / uprightW
        fun mapY(y: Float) = ((y * inH - padY) / scale + roiT) / uprightH
        val mapped = raw.map { d ->
            val box = Box(mapX(d.box.left), mapY(d.box.top), mapX(d.box.right), mapY(d.box.bottom)).clamped()
            val withBox = d.copy(box = box)
            if (d.label.code == SpeedReader.SPEED_CODE) readSpeed(withBox, src, rotation, uprightW, uprightH) else withBox
        }
        src.recycle()

        onResult(
            FrameResult(
                detections = mapped,
                frameWidth = uprightW,
                frameHeight = uprightH,
                roi = roi,
                inferenceMs = SystemClock.elapsedRealtime() - start,
            )
        )
    }

    /**
     * Cắt biển tốc độ từ frame gốc (độ phân giải đầy đủ, không phải ảnh 640 đã thu nhỏ),
     * phóng lên [SPEED_CROP_PX] rồi OCR. Đọc được số thì đổi sang classId/nhãn riêng của giá trị đó.
     */
    private fun readSpeed(d: Detection, src: Bitmap, rotation: Int, uprightW: Int, uprightH: Int): Detection {
        val reader = speedReader ?: SpeedReader().also { speedReader = it }
        val padW = d.box.width * SPEED_CROP_PAD
        val padH = d.box.height * SPEED_CROP_PAD
        val l = (d.box.left - padW).coerceAtLeast(0f) * uprightW
        val t = (d.box.top - padH).coerceAtLeast(0f) * uprightH
        val w = (d.box.right + padW).coerceAtMost(1f) * uprightW - l
        val h = (d.box.bottom + padH).coerceAtMost(1f) * uprightH - t
        if (w < 4f || h < 4f) return d

        val s = SPEED_CROP_PX / maxOf(w, h)
        val crop = Bitmap.createBitmap((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val m = Matrix()
        uprightMatrix(m, rotation, src)
        m.postTranslate(-l, -t)
        m.postScale(s, s)
        Canvas(crop).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, m, paint)
        }
        val kmh = reader.read(crop)
        crop.recycle()
        return if (kmh == null) d else d.copy(classId = SpeedReader.speedClassId(kmh), label = SpeedReader.speedLabel(kmh))
    }

    private fun ensureDetector(): SignDetector? {
        if (detector != null || initAttempted) return detector
        initAttempted = true
        onStatus(DetectorStatus.Loading)
        detector = try {
            detectorFactory().also { onStatus(DetectorStatus.Ready(it.classCount, it.usesGpu)) }
        } catch (t: Throwable) {
            Log.e(TAG, "Không tải được model", t)
            onStatus(DetectorStatus.Unavailable(t.message ?: t.javaClass.simpleName))
            null
        }
        return detector
    }

    override fun close() {
        detector?.close()
        detector = null
        speedReader?.close()
        speedReader = null
        inputBitmap?.recycle()
        inputBitmap = null
        inputCanvas = null
    }

    companion object {
        private const val TAG = "SignAnalyzer"
        private const val SPEED_CROP_PX = 192f
        private const val SPEED_CROP_PAD = 0.1f

        /** Đặt [m] thành phép xoay đưa frame cảm biến về khung đứng thẳng. */
        private fun uprightMatrix(m: Matrix, rotation: Int, src: Bitmap) {
            m.reset()
            when (rotation) {
                90 -> { m.postRotate(90f); m.postTranslate(src.height.toFloat(), 0f) }
                180 -> { m.postRotate(180f); m.postTranslate(src.width.toFloat(), src.height.toFloat()) }
                270 -> { m.postRotate(270f); m.postTranslate(0f, src.width.toFloat()) }
            }
        }

        /**
         * Xe chạy bên phải nên biển chủ yếu nằm ở phía trên bên phải khung hình (landscape).
         * Cắt vùng này ở độ phân giải gốc giúp biển nhỏ ở xa không bị mất chi tiết.
         */
        val DEFAULT_ROI = Box(left = 0.40f, top = 0.0f, right = 1.0f, bottom = 0.75f)
    }
}
