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

    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Spec §1.6 — ONLY these scale with screen width; everything else is fixed dp/sp.
        val wv = maxWidth.value
        val edge = (wv * 0.061f).dp.coerceIn(20.dp, 28.dp)   // screen edge margin
        val captureDia = (wv * 0.367f).dp.coerceIn(120.dp, 148.dp) // §2.3 idle
        val avatar = (wv * 0.106f).dp.coerceIn(34.dp, 44.dp)
        val micIcon = captureDia * 0.47f

        Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
            Spacer(Modifier.height(8.dp))

            // 1 · Top row — avatar + sync chip (§3.1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(avatar).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "Y",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(ext.successSoft).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudDone, null, tint = ext.onRefinedContainer, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (signedIn) "Synced" else "Guest",
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ext.onRefinedContainer)
                }
            }

            // 2 · Greeting (Headline Outfit 24 + Body-small subtitle)
            Spacer(Modifier.height(22.dp))
            Text("$greeting, $name",
                fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                lineHeight = 28.sp, letterSpacing = (-0.4).sp, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text("What's on your mind?", fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)

            // 3 · Last-note card (§2.4-lite)
            if (lastSession != null) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(cs.surfaceContainer).padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("LAST NOTE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp, color = ext.subdued)
                    Spacer(Modifier.height(5.dp))
                    Text(lastSession.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val done = lastSession.status == "DONE"
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (done) ext.refinedContainer else ext.draftNeutral)
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (done) {
                                Icon(Icons.Default.CheckCircle, null, tint = ext.onRefinedContainer,
                                    modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (done) "Refined" else lastSession.status.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = if (done) ext.onRefinedContainer else cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(relativeTime(lastSession.createdAt), fontSize = 11.sp, color = ext.subdued)
                    }
                }
            }

            // 4 · ★ Flexible spacer centering block 5 (§3.1)
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 5 · Capture button + label + import pill
                Box(
                    Modifier.size(captureDia)
                        .shadow(18.dp, CircleShape, spotColor = cs.primary, ambientColor = cs.primary)
                        .clip(CircleShape).background(cs.primary).clickable { onRecord() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Mic, "Record", tint = cs.onPrimary, modifier = Modifier.size(micIcon))
                }
                Spacer(Modifier.height(20.dp))
                Text("Tap to record", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainer)
                        .clickable { onImport() }.padding(horizontal = 15.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.UploadFile, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Import a file", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                }
            }
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
