package com.mehdiacho.yap.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.OrphanRecordings
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme

// =====================================================================================
// Recover recordings — a safety net for audio that never made it onto a note.
//
// This list should normally be empty. It exists because a failed append used to strand a
// complete recording with nothing referencing it and no way to reach it from inside the
// app. The capture pipeline now saves such clips as their own note automatically; this
// screen covers anything stranded before that fix, or by a failure the fallback can't
// handle (e.g. no network at the moment of failure).
// =====================================================================================

@Composable
fun RescueRecordingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val context = LocalContext.current
    val orphans by viewModel.orphans.collectAsState()
    val scanning by viewModel.orphansScanning.collectAsState()
    var pendingDelete by remember { mutableStateOf<OrphanRecordings.Orphan?>(null) }

    // Scan on entry so the list is never stale after a recovery.
    LaunchedEffect(Unit) { viewModel.refreshOrphans() }

    // Deliberately does NOT auto-navigate to the recovered note: MainScreen owns
    // pendingOpen and the app disabled post-capture auto-open on purpose (it used to
    // hijack whatever screen you were on). The recovered note appears in the Library.

    SettingsSubScaffold("Recover recordings", onBack) {
        Text(
            "Audio Yap captured but couldn't attach to a note shows up here. Recovering one " +
                "transcribes it and saves it as its own note.",
            fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        when {
            scanning && orphans.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("Checking storage…", fontSize = 14.sp, color = cs.onSurfaceVariant)
            }

            orphans.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.CheckCircle, null, tint = ext.subdued,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing stranded", fontFamily = Outfit, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = cs.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every recording on this device belongs to a note.",
                    fontSize = 13.sp, color = ext.subdued,
                )
            }

            else -> orphans.forEach { orphan ->
                OrphanRow(
                    orphan = orphan,
                    onRecover = { viewModel.recoverOrphan(orphan) },
                    onShare = {
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(
                                    OrphanRecordings.shareIntent(context, orphan),
                                    "Share recording",
                                )
                            )
                        }.onFailure {
                            Toast.makeText(context, "Couldn't share that file", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                    onDelete = { pendingDelete = orphan },
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(28.dp),
            icon = {
                Icon(
                    Icons.Default.Warning, null, tint = ext.onOfflineContainer,
                    modifier = Modifier.size(30.dp),
                )
            },
            title = {
                Text(
                    "Delete this recording?", fontFamily = Outfit,
                    fontWeight = FontWeight.Bold, fontSize = 19.sp,
                )
            },
            text = {
                Text(
                    "${formatOrphanLength(target.durationMs)} of audio will be permanently " +
                        "removed from this device. This can't be undone.",
                    fontSize = 13.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.discardOrphan(target); pendingDelete = null },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun OrphanRow(
    orphan: OrphanRecordings.Orphan,
    onRecover: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended

    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainer)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Mic, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatOrphanLength(orphan.durationMs),
                    fontFamily = Outfit, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = cs.onSurface,
                )
                Text(
                    "${formatOrphanDate(orphan.recordedAt)} · ${orphan.sizeBytes / (1024 * 1024)} MB",
                    fontSize = 12.sp, color = ext.subdued,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRecover,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            ) { Text("Save as note", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onShare) { Text("Share", fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) { Text("Delete", fontSize = 13.sp, color = cs.error) }
        }
    }
}

private fun formatOrphanLength(durationMs: Long): String {
    if (durationMs <= 0L) return "Recording"
    val totalSec = durationMs / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min > 0) "$min min ${sec}s" else "${sec}s"
}

private fun formatOrphanDate(epochMs: Long): String =
    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
