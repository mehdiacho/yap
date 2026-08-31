package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mehdiacho.yap.data.FeedbackClient
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme
import java.io.File
import kotlinx.coroutines.launch

/**
 * Send feedback.
 *
 * The voice option is not a secondary path. People structure their sentences when they type,
 * and the structuring sands off the thing they actually meant — spoken feedback is looser and
 * more honest. So recording is offered as an equal, right next to the text box, rather than
 * buried behind an attachment icon.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var topic by remember { mutableStateOf(FeedbackClient.topics.first()) }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val recorder = remember { com.mehdiacho.yap.audio.VoiceMemoRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<File?>(null) }
    var clipMs by remember { mutableStateOf(0L) }

    // Ticks only while recording, so the elapsed counter moves without a timer running for
    // the whole life of the screen.
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        elapsed = 0L
        while (recording) {
            kotlinx.coroutines.delay(200)
            elapsed += 200
        }
    }

    fun beginRecording() {
        if (recorder.start()) {
            recording = true
            error = null
        } else {
            error = "Couldn't start recording. Another app may be using the microphone."
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else error = "Yap needs microphone access to record a message."
    }

    // The screen owns the recorder, so an abandoned recording is stopped and deleted when the
    // user navigates away rather than left running with the mic held open.
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel() } }

    SettingsSubScaffold("Feedback", onBack) {
        if (sent) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, null, tint = cs.onPrimaryContainer) }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Thank you — that's been sent.", fontFamily = Outfit, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "It goes straight to the person building this.",
                    fontSize = 13.sp, color = ext.subdued,
                )
                Spacer(Modifier.height(22.dp))
                Button(onClick = onBack) { Text("Done") }
            }
            return@SettingsSubScaffold
        }

        Text(
            "What's this about?", fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = cs.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeedbackClient.topics.forEach { t ->
                val selected = t.id == topic.id
                Text(
                    t.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (selected) cs.primaryContainer else cs.surfaceContainer)
                        .clickable { topic = t }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("Say whatever's on your mind. Blunt is fine.") },
            shape = YapShapes.card,
        )

        Spacer(Modifier.height(18.dp))
        Text(
            "Or just say it", fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, color = cs.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Often easier than typing, and we'd rather hear what you actually think.",
            fontSize = 12.sp, color = ext.subdued,
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().clip(YapShapes.card).background(cs.surfaceContainer)
                .border(1.dp, cs.outlineVariant, YapShapes.card).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val existing = clip
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(if (recording) cs.errorContainer else cs.primaryContainer)
                    .clickable(enabled = !sending) {
                        when {
                            recording -> {
                                val done = recorder.stop()
                                if (done != null) {
                                    clip = done.first
                                    clipMs = done.second
                                } else {
                                    error = "That was too short to send."
                                }
                                recording = false
                            }
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED -> beginRecording()
                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Default.Stop else Icons.Default.Mic,
                    if (recording) "Stop recording" else "Record a message",
                    tint = if (recording) cs.onErrorContainer else cs.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        recording -> "Recording… ${elapsed / 1000}s"
                        existing != null -> "Message recorded · ${clipMs / 1000}s"
                        else -> "Record a voice message"
                    },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                )
                if (existing != null && !recording) {
                    Text(
                        "Sent along with anything you typed.",
                        fontSize = 12.sp, color = ext.subdued,
                    )
                }
            }
            if (existing != null && !recording) {
                Icon(
                    Icons.Default.Close, "Discard recording", tint = ext.subdued,
                    modifier = Modifier.clickable {
                        runCatching { existing.delete() }
                        clip = null
                        clipMs = 0L
                    },
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = cs.error)
        }

        Spacer(Modifier.height(22.dp))
        // Something has to be there. The backend refuses an empty report too — this just says
        // so before the round trip.
        val canSend = !sending && (message.isNotBlank() || clip != null)
        Button(
            onClick = {
                scope.launch {
                    sending = true
                    error = null
                    try {
                        FeedbackClient.submit(topic.id, message.trim(), clip, clipMs)
                        // The recording has done its job; it lives in the cache and there is
                        // no reason to leave a copy of the user's voice on the device.
                        runCatching { clip?.delete() }
                        sent = true
                    } catch (e: Exception) {
                        error = e.message?.takeIf { it.isNotBlank() }
                            ?: "Couldn't send that. Check your connection and try again."
                    }
                    sending = false
                }
            },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = cs.onPrimary,
                )
            } else {
                Text("Send feedback", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
