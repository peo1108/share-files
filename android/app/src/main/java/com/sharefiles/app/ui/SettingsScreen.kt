package com.sharefiles.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sharefiles.app.AppState
import com.sharefiles.app.Connection
import com.sharefiles.app.core.ApiFailure
import com.sharefiles.app.core.AppLog
import com.sharefiles.app.core.SpeedTest
import java.io.IOException
import kotlinx.coroutines.launch

/** Full scale of the gauge: what a 2.5 Gbps cable can carry. */
private const val GAUGE_MAX_BYTES_PER_SECOND = 312_500_000.0

@Composable
fun SettingsScreen(state: AppState, wide: Boolean) {
    val server by state.paired.collectAsStateWithLifecycle()
    val connection by state.connection.collectAsStateWithLifecycle()
    val log by AppLog.lines.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }

    val speedCard: @Composable () -> Unit = { SpeedTestCard(state) }
    val infoCard: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().glass().padding(18.dp)) {
            SectionTitle("PC đã ghép")
            Spacer(Modifier.height(8.dp))
            Text(server?.serverName ?: "", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                (connection as? Connection.Online)?.host ?: "Chưa kết nối",
                color = Palette.Subtle,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Chứng chỉ: " + server?.fingerprint?.take(16).orEmpty(),
                color = Palette.Faint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            GhostButton("Huỷ ghép đôi", color = Palette.Danger) { confirmUnpair = true }
        }
    }
    val logCard: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().glass().padding(18.dp)) {
            SectionTitle("Nhật ký")
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    if (log.isEmpty()) Text("Chưa có gì", color = Palette.Faint, fontSize = 12.sp)
                    log.asReversed().forEach { line ->
                        Text(line, color = Palette.Subtle, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { speedCard() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                infoCard()
                logCard()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            speedCard()
            infoCard()
            logCard()
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Huỷ ghép đôi?") },
            text = { Text("Muốn dùng lại thì phải quét mã QR trên PC lần nữa.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    state.unpair()
                }) { Text("Huỷ ghép đôi", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Không") } },
        )
    }
}

@Composable
private fun SpeedTestCard(state: AppState) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var live by remember { mutableDoubleStateOf(0.0) }
    var downloadResult by remember { mutableStateOf<Double?>(null) }
    var uploadResult by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().glass().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionTitle("Đo tốc độ tới PC", Modifier.fillMaxWidth())
        Spacer(Modifier.height(2.dp))
        Text(
            "Chỉ đo đường truyền, không đụng tới ổ đĩa",
            Modifier.fillMaxWidth(),
            color = Palette.Faint,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1.4f), contentAlignment = Alignment.Center) {
            Gauge((live / GAUGE_MAX_BYTES_PER_SECOND).toFloat())
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 26.dp)) {
                Text(
                    "%.0f".format(live / 1_000_000),
                    color = Palette.Text,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("MB/s", color = Palette.Subtle, fontSize = 14.sp)
                Text(if (running) phase else " ", color = Palette.Amber, fontSize = 12.sp)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ResultTile("Tải về máy", downloadResult, Modifier.weight(1f))
            ResultTile("Gửi lên PC", uploadResult, Modifier.weight(1f))
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Danger, fontSize = 13.sp)
        }

        Spacer(Modifier.height(14.dp))
        GradientButton(
            if (running) "Đang đo…" else "Bắt đầu đo",
            Modifier.fillMaxWidth(),
            Icons.Rounded.Speed,
            enabled = !running,
        ) {
            val api = state.api()
            if (api == null) {
                error = "Chưa kết nối với PC"
                return@GradientButton
            }
            running = true
            error = null
            downloadResult = null
            uploadResult = null
            scope.launch {
                try {
                    val test = SpeedTest(api)
                    phase = "Đang đo tải về"
                    downloadResult = test.download { live = it }
                    phase = "Đang đo gửi lên"
                    uploadResult = test.upload { live = it }
                    AppLog.add(
                        "Đo tốc độ: tải về %.0f MB/s, gửi lên %.0f MB/s".format(
                            (downloadResult ?: 0.0) / 1_000_000,
                            (uploadResult ?: 0.0) / 1_000_000,
                        ),
                    )
                } catch (e: IOException) {
                    error = "Mất kết nối khi đang đo"
                } catch (e: ApiFailure) {
                    error = e.message
                } finally {
                    live = 0.0
                    running = false
                }
            }
        }
    }
}

@Composable
private fun ResultTile(label: String, bytesPerSecond: Double?, modifier: Modifier) {
    Column(modifier.glass(16).padding(12.dp)) {
        Text(label, color = Palette.Subtle, fontSize = 12.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            bytesPerSecond?.let(::formatSpeed) ?: "—",
            color = Palette.Text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (bytesPerSecond != null) {
            Text("%.2f Gbps".format(bytesPerSecond * 8 / 1e9), color = Palette.Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Gauge(fraction: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = size.minDimension * 0.075f
        val start = 150f
        val sweep = 240f
        // The arc ends 30 degrees below centre, so it is only 3/4 of a circle tall.
        val visibleHeight = 0.75f
        val diameter = minOf(size.width - stroke, (size.height - stroke) / visibleHeight)
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter * visibleHeight) / 2)
        val arc = Size(diameter, diameter)

        drawArc(
            color = Palette.Card,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arc,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        if (fraction > 0f) {
            drawArc(
                brush = Brush.linearGradient(listOf(Palette.Violet, Palette.Pink, Palette.Amber)),
                startAngle = start,
                sweepAngle = sweep * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}
