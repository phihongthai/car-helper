package com.carhelper.signs.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/**
 * Bọc model YOLO (Ultralytics, export TFLite).
 *
 * Input: [1, H, W, 3], float32 (0..1) hoặc uint8/int8 có lượng tử hoá.
 * Output: [1, 4 + nc, N] hoặc [1, N, 4 + nc], gồm cx, cy, w, h rồi điểm từng lớp.
 *
 * Phải tạo và dùng trên CÙNG một thread (GPU delegate yêu cầu vậy).
 */
class SignDetector private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val labels: List<SignLabel>,
    val usesGpu: Boolean,
) : Closeable {

    val inputWidth: Int
    val inputHeight: Int
    val classCount: Int

    private val inputType: DataType
    private val inputScale: Float
    private val inputZeroPoint: Int
    private val channelsFirst: Boolean
    private val numBoxes: Int
    private val inputBuffer: ByteBuffer
    private val output: Array<Array<FloatArray>>
    private val pixels: IntArray

    init {
        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        require(inShape.size == 4 && inShape[3] == 3) { "Input không phải [1,H,W,3]: ${inShape.contentToString()}" }
        inputHeight = inShape[1]
        inputWidth = inShape[2]
        inputType = inTensor.dataType()
        inputScale = inTensor.quantizationParams().scale
        inputZeroPoint = inTensor.quantizationParams().zeroPoint

        val outTensor = interpreter.getOutputTensor(0)
        check(outTensor.dataType() == DataType.FLOAT32) {
            "Output phải là float32. Hãy export với half=True hoặc int8 (không dùng full-integer)."
        }
        val outShape = outTensor.shape()
        require(outShape.size == 3) { "Output không phải 3 chiều: ${outShape.contentToString()}" }
        channelsFirst = outShape[1] < outShape[2]
        val attrs = if (channelsFirst) outShape[1] else outShape[2]
        numBoxes = if (channelsFirst) outShape[2] else outShape[1]
        classCount = attrs - 4
        if (classCount != labels.size) {
            Log.w(TAG, "Model có $classCount lớp nhưng labels.txt có ${labels.size} dòng")
        }

        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
        pixels = IntArray(inputWidth * inputHeight)
    }

    /**
     * [input] phải có đúng kích thước [inputWidth] x [inputHeight].
     * Box trả về được chuẩn hoá [0,1] theo không gian input.
     */
    fun detect(input: Bitmap, scoreThreshold: Float, iouThreshold: Float): List<Detection> {
        require(input.width == inputWidth && input.height == inputHeight)
        fillInput(input)
        interpreter.run(inputBuffer, output)
        return BoxUtils.nms(decode(scoreThreshold), iouThreshold)
    }

    private fun fillInput(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBuffer.rewind()
        when (inputType) {
            DataType.FLOAT32 -> for (p in pixels) {
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((p and 0xFF) / 255f)
            }
            DataType.UINT8, DataType.INT8 -> {
                val lo = if (inputType == DataType.UINT8) 0 else -128
                val hi = if (inputType == DataType.UINT8) 255 else 127
                val scale = if (inputScale > 0f) inputScale else 1f / 255f
                fun q(channel: Int): Byte =
                    ((channel / 255f) / scale + inputZeroPoint).roundToInt().coerceIn(lo, hi).toByte()
                for (p in pixels) {
                    inputBuffer.put(q((p shr 16) and 0xFF))
                    inputBuffer.put(q((p shr 8) and 0xFF))
                    inputBuffer.put(q(p and 0xFF))
                }
            }
            else -> error("Kiểu input không hỗ trợ: $inputType")
        }
        inputBuffer.rewind()
    }

    private fun attr(a: Int, i: Int): Float =
        if (channelsFirst) output[0][a][i] else output[0][i][a]

    private fun decode(scoreThreshold: Float): List<Detection> {
        // Ultralytics thường xuất toạ độ đã chuẩn hoá cho TFLite; nếu không thì là pixel.
        var maxCoord = 0f
        for (i in 0 until numBoxes) maxCoord = maxOf(maxCoord, attr(0, i), attr(1, i))
        val sx = if (maxCoord > 2f) 1f / inputWidth else 1f
        val sy = if (maxCoord > 2f) 1f / inputHeight else 1f

        val result = ArrayList<Detection>()
        for (i in 0 until numBoxes) {
            var best = -1
            var bestScore = scoreThreshold
            for (c in 0 until classCount) {
                val s = attr(4 + c, i)
                if (s > bestScore) {
                    bestScore = s
                    best = c
                }
            }
            if (best < 0) continue
            val cx = attr(0, i) * sx
            val cy = attr(1, i) * sy
            val hw = attr(2, i) * sx / 2f
            val hh = attr(3, i) * sy / 2f
            result += Detection(
                classId = best,
                label = labels.getOrElse(best) { SignLabel("class_$best", "Biển báo") },
                score = bestScore,
                box = Box(cx - hw, cy - hh, cx + hw, cy + hh).clamped(),
            )
        }
        return result
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }

    companion object {
        private const val TAG = "SignDetector"

        fun create(context: Context, modelAsset: String, labelsAsset: String, preferGpu: Boolean = true): SignDetector {
            val model = mapAsset(context, modelAsset)
            val labels = context.assets.open(labelsAsset).bufferedReader().useLines { LabelParser.parse(it) }

            if (preferGpu) {
                var delegate: GpuDelegate? = null
                try {
                    val compat = CompatibilityList()
                    if (compat.isDelegateSupportedOnThisDevice) {
                        delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                        val interpreter = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                        return SignDetector(interpreter, delegate, labels, usesGpu = true)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU delegate lỗi, chuyển sang CPU", t)
                    delegate?.close()
                }
            }
            val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
            return SignDetector(interpreter, null, labels, usesGpu = false)
        }

        private fun mapAsset(context: Context, name: String): MappedByteBuffer =
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
