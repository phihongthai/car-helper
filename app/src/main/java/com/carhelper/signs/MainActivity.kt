package com.carhelper.signs

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.carhelper.signs.camera.CameraController
import com.carhelper.signs.detection.FrameResult
import com.carhelper.signs.detection.SignAnalyzer
import com.carhelper.signs.detection.SignDetector
import com.carhelper.signs.detection.VoteTracker
import com.carhelper.signs.speech.SignSpeaker
import com.carhelper.signs.system.ThermalGovernor
import com.carhelper.signs.ui.MainScreen
import com.carhelper.signs.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val ui = MutableStateFlow(UiState())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val voter = VoteTracker(window = 5, minHits = 3, cooldownMs = 30_000)

    private lateinit var previewView: PreviewView
    private lateinit var thermal: ThermalGovernor
    private lateinit var speaker: SignSpeaker
    private lateinit var analyzer: SignAnalyzer
    private var camera: CameraController? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else ui.update { it.copy(cameraDenied = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }

        thermal = ThermalGovernor(this) { status, interval ->
            ui.update { it.copy(thermalStatus = status, intervalMs = interval) }
        }
        speaker = SignSpeaker(this) { ok -> ui.update { it.copy(ttsReady = ok) } }
        analyzer = SignAnalyzer(
            detectorFactory = { SignDetector.create(applicationContext, MODEL_ASSET, LABELS_ASSET) },
            roi = SignAnalyzer.DEFAULT_ROI,
            intervalMs = { thermal.intervalMs },
            onStatus = { status -> ui.update { it.copy(detector = status) } },
            onResult = ::onFrameResult,
        )

        setContent {
            val state by ui.collectAsState()
            MainScreen(state, previewView, ::setDarkMode)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        ui.update { it.copy(cameraDenied = false) }
        camera = CameraController(this, this, previewView, analyzer, analysisExecutor).also {
            it.start(withPreview = !ui.value.darkMode)
        }
    }

    /** Chạy trên thread phân tích ảnh. */
    private fun onFrameResult(result: FrameResult) {
        val byClass = result.detections.associateBy { it.classId }
        val announce = voter.update(byClass.keys, SystemClock.elapsedRealtime())
        val labels = announce.mapNotNull { byClass[it]?.label }
        labels.forEach { speaker.speak(it.speech) }
        ui.update { s -> s.copy(lastResult = result, lastAnnounced = labels.lastOrNull() ?: s.lastAnnounced) }
    }

    private fun setDarkMode(dark: Boolean) {
        ui.update { it.copy(darkMode = dark) }
        window.attributes = window.attributes.apply {
            screenBrightness = if (dark) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        camera?.setPreviewEnabled(!dark)
    }

    override fun onDestroy() {
        super.onDestroy()
        thermal.close()
        speaker.close()
        analysisExecutor.execute { analyzer.close() }
        analysisExecutor.shutdown()
    }

    companion object {
        const val MODEL_ASSET = "traffic_signs.tflite"
        const val LABELS_ASSET = "labels.txt"
    }
}
