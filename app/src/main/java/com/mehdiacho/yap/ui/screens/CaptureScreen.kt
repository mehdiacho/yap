package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.audio.RecorderEvent
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay

private const val WAVE_BARS = 56
private const val WAVE_STEP_MS = 70L // ~14 bars/sec — calm scroll
private const val WAVE_MIN = 0.05f

@Composable
fun CaptureScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by RecordingBus.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) RecordingController.start(context)
    }

    fun requestAndStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(perms)
    }

    // Rolling waveform history, advanced at a fixed calm cadence (independent of the
    // ~50/sec meter rate) by reading the latest amplitude straight off the bus.
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(WAVE_MIN) } } }
    LaunchedEffect(Unit) {
        while (true) {
            val s = RecordingBus.state.value
            when (s.status) {
                RecorderStatus.RECORDING -> { bars.removeAt(0); bars.add(s.amplitude.coerceIn(WAVE_MIN, 1f)) }
                RecorderStatus.IDLE -> if (bars.any { it > WAVE_MIN }) { bars.removeAt(0); bars.add(WAVE_MIN) }
                else -> { /* paused: hold */ }
            }
            delay(WAVE_STEP_MS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (state.status == RecorderStatus.IDLE) {
            IdleContent(onRecord = ::requestAndStart)
        } else {
            RecordingContent(
                paused = state.status == RecorderStatus.PAUSED,
                elapsedMs = state.elapsedMs,
                bars = bars,
                onPauseResume = {
                    if (state.status == RecorderStatus.PAUSED) RecordingController.resume(context)
                    else RecordingController.pause(context)
                },
                onStop = { RecordingController.stop(context) },
            )
        }
    }
}

@Composable
private fun IdleContent(onRecord: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(132.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary).clickable { onRecord() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, "Record", tint = Color.White, modifier = Modifier.size(62.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Tap to record", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun RecordingContent(
    paused: Boolean,
    elapsedMs: Long,
    bars: SnapshotStateList<Float>,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = YapTheme.extended.recording
    val idle = MaterialTheme.colorScheme.outlineVariant
    val waveColor = if (paused) idle else recording

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (paused) idle else recording))
            Spacer(Modifier.width(8.dp))
            Text(
                if (paused) "Paused" else "Recording",
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else recording,
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(formatElapsed(elapsedMs), fontSize = 46.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        Spacer(Modifier.weight(1f))

        // Full-width, edge-to-edge, center-mirrored waveform.
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val n = bars.size
            val gap = 3.dp.toPx()
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            for (i in 0 until n) {
                val amp = bars[i]
                val h = (amp * size.height).coerceIn(barW, size.height)
                val x = i * (barW + gap)
                val top = (size.height - h) / 2f
                drawRoundRect(
                    color = waveColor,
                    topLeft = Offset(x, top),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPauseResume() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(36.dp))
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(recording).clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Stop, "Stop & save", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
