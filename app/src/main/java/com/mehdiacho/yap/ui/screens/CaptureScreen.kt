package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.audio.RecorderEvent
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay

private const val WAVE_BARS = 56
private const val WAVE_STEP_MS = 70L // ~14 bars/sec — calm scroll
private const val WAVE_MIN = 0.05f

@Composable
fun CaptureScreen(
    viewModel: MainViewModel,
    autoStart: MutableState<Boolean>? = null,
) {
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            viewModel.importSharedAudio(uri, mime)
        }
    }
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

    // Auto-start when launched from the Quick Settings tile (or other external entry).
    LaunchedEffect(autoStart?.value) {
        if (autoStart?.value == true && state.status == RecorderStatus.IDLE) {
            requestAndStart()
            autoStart.value = false
        }
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

    val sessions by viewModel.allSessions.collectAsState()

    Box(Modifier.fillMaxSize()) {
        if (state.status == RecorderStatus.IDLE) {
            IdleContent(
                lastSession = sessions.firstOrNull(),
                onRecord = ::requestAndStart,
                onImport = { importLauncher.launch(arrayOf("audio/*", "video/*")) },
            )
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
                onDiscard = {
                    RecordingController.discard(context)
                    Toast.makeText(context, "Recording discarded", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun IdleContent(
    lastSession: Session?,
    onRecord: () -> Unit,
    onImport: () -> Unit,
) {
    val user by com.mehdiacho.yap.data.AuthManager.authState()
        .collectAsState(initial = com.mehdiacho.yap.data.AuthManager.currentUser)
    val name = user?.displayName?.takeIf { it.isNotBlank() }?.substringBefore(' ')
        ?: user?.email?.takeIf { it.isNotBlank() }?.substringBefore('@')
        ?: "there"
    val greeting = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val signedIn = user != null && user?.isAnonymous == false

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The design frame is 320 units wide, so every measurement is that fraction of the
        // actual screen width — the record button is 132/320 = 41.25% of the width, etc.
        val s = (maxWidth.value / 320f).coerceIn(0.85f, 1.7f)

        Column(Modifier.fillMaxSize()) {
            // Avatar + sync pill (design padding 8 / 22)
            Row(
                Modifier.fillMaxWidth().padding(top = (8 * s).dp, start = (22 * s).dp, end = (22 * s).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size((38 * s).dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.firstOrNull()?.uppercaseChar()?.toString() ?: "Y",
                        fontWeight = FontWeight.Bold, fontSize = (15 * s).sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(YapTheme.extended.refinedContainer)
                        .padding(horizontal = (12 * s).dp, vertical = (6 * s).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudDone, null,
                        tint = YapTheme.extended.onRefinedContainer, modifier = Modifier.size((15 * s).dp))
                    Spacer(Modifier.width((6 * s).dp))
                    Text(if (signedIn) "Synced" else "Guest",
                        fontWeight = FontWeight.SemiBold, fontSize = (12 * s).sp,
                        color = YapTheme.extended.onRefinedContainer)
                }
            }

            // Greeting (design padding 22 / 28, Outfit 24)
            Column(Modifier.padding(top = (22 * s).dp, start = (28 * s).dp, end = (28 * s).dp)) {
                Text("$greeting, $name",
                    fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = (24 * s).sp,
                    letterSpacing = (-0.3 * s).sp, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height((4 * s).dp))
                Text("What's on your mind?", fontSize = (14 * s).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Last note (design padding 20 / 24; card radius 18, padding 14/16)
            if (lastSession != null) {
                Column(Modifier.padding(top = (20 * s).dp, start = (24 * s).dp, end = (24 * s).dp)) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape((18 * s).dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = (16 * s).dp, vertical = (14 * s).dp)
                    ) {
                        Text("LAST NOTE", fontSize = (11 * s).sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = (0.3 * s).sp, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height((5 * s).dp))
                        Text(lastSession.title, fontSize = (15 * s).sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.height((8 * s).dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val done = lastSession.status == "DONE"
                            Row(
                                Modifier.clip(RoundedCornerShape(999.dp))
                                    .background(if (done) YapTheme.extended.refinedContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = (9 * s).dp, vertical = (3 * s).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (done) {
                                    Icon(Icons.Default.CheckCircle, null,
                                        tint = YapTheme.extended.onRefinedContainer, modifier = Modifier.size((12 * s).dp))
                                    Spacer(Modifier.width((4 * s).dp))
                                }
                                Text(if (done) "Refined" else lastSession.status.lowercase().replaceFirstChar { it.uppercase() },
                                    fontSize = (11 * s).sp, fontWeight = FontWeight.SemiBold,
                                    color = if (done) YapTheme.extended.onRefinedContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width((6 * s).dp))
                            Text(relativeTime(lastSession.createdAt), fontSize = (11 * s).sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            // Hero mic + import (fills remaining, centered)
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size((132 * s).dp)
                        .shadow((22 * s).dp, CircleShape,
                            spotColor = MaterialTheme.colorScheme.primary,
                            ambientColor = MaterialTheme.colorScheme.primary)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable { onRecord() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, "Record", tint = Color.White, modifier = Modifier.size((62 * s).dp))
                }
                Spacer(Modifier.height((20 * s).dp))
                Text("Tap to record", fontSize = (15 * s).sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height((14 * s).dp))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onImport() }
                        .padding(horizontal = (15 * s).dp, vertical = (8 * s).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.UploadFile, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size((18 * s).dp))
                    Spacer(Modifier.width((7 * s).dp))
                    Text("Import a file", fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height((8 * s).dp))
        }
    }
}

private fun relativeTime(ts: Long): String {
    val mins = (System.currentTimeMillis() - ts) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

@Composable
private fun RecordingContent(
    paused: Boolean,
    elapsedMs: Long,
    bars: SnapshotStateList<Float>,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
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
        Text(
            formatElapsed(elapsedMs),
            style = MaterialTheme.typography.displayLarge, // Outfit, per the kit's big timer
            color = MaterialTheme.colorScheme.onBackground,
        )

        // Live draft — streams in from the fed-stream recognizer while you speak.
        val draft by com.mehdiacho.yap.audio.LiveDraftEngine.draftText.collectAsState()
        if (draft.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp).weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp)
            ) {
                Text(
                    "LIVE DRAFT",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(6.dp))
                val scroll = androidx.compose.foundation.rememberScrollState()
                LaunchedEffect(draft) { scroll.animateScrollTo(scroll.maxValue) }
                Text(
                    draft,
                    fontSize = 15.sp, lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(scroll),
                )
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

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
            // Discard: throw away the recording, nothing is saved.
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onDiscard() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Delete, contentDescription = "Discard recording",
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(30.dp))
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(recording).clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Stop, "Stop & save", tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.width(30.dp))
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
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
