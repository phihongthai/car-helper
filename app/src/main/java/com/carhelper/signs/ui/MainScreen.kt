package com.carhelper.signs.ui

import android.graphics.Paint
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.carhelper.signs.detection.Box as NormBox
import com.carhelper.signs.detection.DetectorStatus
import com.carhelper.signs.detection.FrameResult
import com.carhelper.signs.detection.SignLabel
import com.carhelper.signs.system.ThermalGovernor
import kotlin.math.roundToInt

data class UiState(
    val detector: DetectorStatus = DetectorStatus.Loading,
    val cameraDenied: Boolean = false,
    val ttsReady: Boolean? = null,
    val lastResult: FrameResult? = null,
    val lastAnnounced: SignLabel? = null,
    val darkMode: Boolean = false,
    val thermalStatus: Int = 0,
    val intervalMs: Long = 200,
    val gpsDenied: Boolean = false,
    /** Tốc độ xe từ GPS, null nếu chưa có tín hiệu. */
    val speedKmh: Float? = null,
    /** Giới hạn tốc độ đang áp dụng (biển gần nhất), null nếu không có. */
    val speedLimit: Int? = null,
    val overspeed: Boolean = false,
)

@Composable
fun MainScreen(state: UiState, previewView: PreviewView, onDarkModeChange: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // PreviewView luôn nằm trong cây UI; khi màn hình đen thì preview bị unbind nên không tốn GPU.
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (state.darkMode) {
            // Nền đen tuyệt đối: trên màn OLED các điểm ảnh gần như tắt hẳn.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDarkModeChange(false) }
            )
            // Vẫn hiện giới hạn + tốc độ: vùng nhỏ nên gần như không tốn pin/nhiệt trên OLED.
            SpeedPanel(state, Modifier.align(Alignment.BottomStart).padding(16.dp))
            return@Box
        }

        DetectionOverlay(state.lastResult, Modifier.fillMaxSize())

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusText(detectorText(state.detector))
            StatusText(
                "Giọng đọc: " + when (state.ttsReady) {
                    null -> "đang khởi tạo"
                    true -> "sẵn sàng"
                    false -> "thiếu gói tiếng Việt"
                }
            )
            StatusText("Máy ${ThermalGovernor.describe(state.thermalStatus)} · ${1000 / state.intervalMs} FPS")
            state.lastResult?.let { StatusText("Suy luận ${it.inferenceMs} ms") }
            if (state.cameraDenied) StatusText("Chưa cấp quyền camera", Color(0xFFFF8A80))
            if (state.gpsDenied) StatusText("Chưa cấp quyền vị trí: tắt cảnh báo quá tốc độ", Color(0xFFFF8A80))
        }

        SpeedPanel(state, Modifier.align(Alignment.BottomStart).padding(16.dp))

        state.lastAnnounced?.let { label ->
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0xE6FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(label.code, color = Color(0xFFB71C1C), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(label.speech, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Button(
            onClick = { onDarkModeChange(true) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("Màn hình đen") }
    }
}

/** Biển giới hạn đang áp dụng (vòng đỏ) và tốc độ GPS; tốc độ chuyển đỏ khi quá giới hạn. */
@Composable
private fun SpeedPanel(state: UiState, modifier: Modifier) {
    if (state.speedLimit == null && state.speedKmh == null) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        state.speedLimit?.let { limit ->
            Box(
                Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .border(8.dp, Color(0xFFD32F2F), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$limit", color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
        state.speedKmh?.let { speed ->
            Text(
                "${speed.roundToInt()} km/h",
                color = if (state.overspeed) Color(0xFFFF5252) else Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = Color.White) {
    Text(text, color = color, fontSize = 12.sp)
}

private fun detectorText(status: DetectorStatus): String = when (status) {
    DetectorStatus.Loading -> "Model: đang tải"
    is DetectorStatus.Ready -> "Model: ${status.classCount} lớp · ${if (status.usesGpu) "GPU" else "CPU"}"
    is DetectorStatus.Unavailable -> "Model: chưa có (${status.reason})"
}

/** Vẽ ROI và các box. PreviewView dùng FIT_CENTER nên phép quy đổi chỉ là scale + offset. */
@Composable
private fun DetectionOverlay(result: FrameResult?, modifier: Modifier) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(modifier) {
        val r = result ?: return@Canvas
        val scale = minOf(size.width / r.frameWidth, size.height / r.frameHeight)
        val w = r.frameWidth * scale
        val h = r.frameHeight * scale
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f

        fun DrawScope.rect(b: NormBox, color: Color, dashed: Boolean = false) = drawRect(
            color = color,
            topLeft = Offset(ox + b.left * w, oy + b.top * h),
            size = Size(b.width * w, b.height * h),
            style = Stroke(
                width = if (dashed) 2f else 5f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 12f)) else null,
            ),
        )

        rect(r.roi, Color(0x88FFFFFF), dashed = true)
        for (d in r.detections) {
            rect(d.box, Color.Yellow)
            drawContext.canvas.nativeCanvas.drawText(
                "${d.label.code} ${(d.score * 100).toInt()}%",
                ox + d.box.left * w,
                oy + d.box.top * h - 8f,
                textPaint,
            )
        }
    }
}
