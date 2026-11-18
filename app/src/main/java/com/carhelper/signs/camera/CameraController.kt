package com.carhelper.signs.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * Cấu hình camera cho việc đọc biển báo:
 * - Giới hạn FPS cảm biến ở 10–15 để máy mát hơn (model chỉ cần 4–5 FPS).
 * - Tắt auto-focus, khoá nét ở khoảng siêu tiêu cự để không bị lấy nét nhầm vào giọt mưa trên kính.
 * - Giữ auto-exposure (ép phơi sáng tay sẽ làm ảnh đen khi trời tối).
 */
data class CameraTuning(
    val fpsRange: Range<Int>?,
    val focusDiopters: Float?,
) {
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun applyTo(extender: Camera2Interop.Extender<*>) {
        fpsRange?.let { extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        focusDiopters?.let {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
    }

    companion object {
        /** 0.15 diopter ≈ nét ở 6,7 m: mọi thứ từ vài mét đến vô cực đều đủ nét. */
        private const val HYPERFOCAL_DIOPTERS = 0.15f

        fun forBackCamera(context: Context): CameraTuning {
            return try {
                val manager = context.getSystemService(CameraManager::class.java)
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: return CameraTuning(null, null)
                val ch = manager.getCameraCharacteristics(id)

                val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    .orEmpty()
                    .filter { it.upper in 10..15 }
                    .maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))

                // minFocus == 0 nghĩa là camera lấy nét cố định, không cần chỉnh.
                val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val focus = if (minFocus > 0f) HYPERFOCAL_DIOPTERS.coerceAtMost(minFocus) else null

                CameraTuning(fps, focus)
            } catch (t: Throwable) {
                Log.w("CameraTuning", "Không đọc được thông số camera", t)
                CameraTuning(null, null)
            }
        }
    }
}

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: ImageAnalysis.Analyzer,
    private val analysisExecutor: Executor,
) {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewEnabled = true
    private val tuning by lazy { CameraTuning.forBackCamera(context) }

    fun start(withPreview: Boolean) {
        previewEnabled = withPreview
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            provider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Tắt preview khi bật màn hình đen để GPU không phải vẽ khung hình. */
    fun setPreviewEnabled(enabled: Boolean) {
        if (enabled == previewEnabled) return
        previewEnabled = enabled
        bind()
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bind() {
        val p = provider ?: return

        val analysisUseCase = analysis ?: ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .also { tuning.applyTo(Camera2Interop.Extender(it)) }
            .build()
            .also {
                it.setAnalyzer(analysisExecutor, analyzer)
                analysis = it
            }

        val useCases = mutableListOf<UseCase>(analysisUseCase)
        if (previewEnabled) {
            val previewUseCase = preview ?: Preview.Builder()
                .also { tuning.applyTo(Camera2Interop.Extender(it)) }
                .build()
                .also { preview = it }
            previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            useCases += previewUseCase
        }

        try {
            p.unbindAll()
            p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        } catch (t: Throwable) {
            Log.e("CameraController", "Không bind được camera", t)
        }
    }
}
