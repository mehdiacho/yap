package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private const val WAVE_BARS = 48

@Composable
fun CaptureScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by RecordingBus.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingController.start(context)
        }
    }

    fun requestAndStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(perms)
    }

    // Rolling amplitude history for the waveform — advance one bar per audio buffer
    // (keyed on elapsedMs, which ticks with every ~20ms recorded chunk => smooth motion).
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0.06f) } } }
    LaunchedEffect(state.elapsedMs) {
        if (state.status == RecorderStatus.RECORDING) {
            bars.removeAt(0)
            bars.add(state.amplitude.coerceIn(0.06f, 1f))
        }
    }

    // Confirm the save when a recording finishes.
    LaunchedEffect(Unit) {
        RecordingBus.events.collect { event ->
            if (event is RecorderEvent.Finished) {
                Toast.makeText(context, "Recording saved to Library", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onRecord() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, "Record", tint = Color.White, modifier = Modifier.size(62.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Tap to record",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
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

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(if (paused) MaterialTheme.colorScheme.onSurfaceVariant else recording)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (paused) "Paused" else "Recording",
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else recording,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            formatElapsed(elapsedMs),
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.weight(1f))

        // Waveform
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bars.forEach { amp ->
                Box(
                    Modifier
                        .width(4.dp)
                        .height((8 + amp * 56).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (paused) MaterialTheme.colorScheme.outlineVariant else recording)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPauseResume() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (paused) "Resume" else "Pause",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(36.dp))
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(recording)
                    .clickable { onStop() },
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
