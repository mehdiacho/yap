package com.mehdiacho.yap.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun SessionDetailScreen(
    viewModel: MainViewModel,
    sessionId: String,
    onOpenSubscription: () -> Unit = {},
    onBack: () -> Unit,
) {
    // Stable flow: remember by id so we don't recreate the StateFlow every recomposition
    // (that was causing the screen to jitter / recompose infinitely).
    val sessionFlow = remember(sessionId) { viewModel.getSessionById(sessionId) }
    val session by sessionFlow.collectAsState()
    var viewMode by remember { mutableStateOf("Formatted") }

    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val currentSession = session!!

    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var showEditTitle by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    fun currentText(): String =
        if (viewMode == "Formatted") currentSession.formattedText ?: currentSession.draftText
        else currentSession.draftText

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.clickable { onBack() },
                tint = MaterialTheme.colorScheme.onBackground
            )
            val isRefined = currentSession.status == "REFINED"
            val statusColor = if (isRefined) YapTheme.extended.onRefinedContainer else MaterialTheme.colorScheme.onSurfaceVariant
            val statusBg = if (isRefined) YapTheme.extended.refinedContainer else MaterialTheme.colorScheme.surfaceVariant
            Row(
                modifier = Modifier.clip(RoundedCornerShape(50)).background(statusBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRefined) {
                    Icon(Icons.Default.CheckCircle, null, tint = statusColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(currentSession.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Box {
                Icon(
                    Icons.Default.MoreVert, "More",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.clickable { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit title") },
                        onClick = { menuOpen = false; titleDraft = currentSession.title; showEditTitle = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy text") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentText()))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, currentSession.title)
                                putExtra(android.content.Intent.EXTRA_TEXT, currentText())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share note"))
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; showDeleteConfirm = true }
                    )
                }
            }
        }

        if (showEditTitle) {
            AlertDialog(
                onDismissRequest = { showEditTitle = false },
                title = { Text("Edit title") },
                text = {
                    OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it }, singleLine = true)
                },
                confirmButton = {
                    TextButton(
                        enabled = titleDraft.isNotBlank(),
                        onClick = {
                            viewModel.updateSession(currentSession.copy(title = titleDraft.trim()))
                            showEditTitle = false
                        }
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showEditTitle = false }) { Text("Cancel") } }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete this note?") },
                text = { Text("The note, its transcript, and its recording will be removed everywhere. This can't be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSessionCompletely(currentSession)
                        onBack()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                currentSession.title, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground, lineHeight = 30.sp
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    currentSession.category, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Formatted / Raw toggle
        Row(
            modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)
        ) {
            listOf("Formatted", "Raw").forEach { mode ->
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (viewMode == mode) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { viewMode = mode }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode,
                        fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal,
                        color = if (viewMode == mode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Content
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 22.dp).verticalScroll(rememberScrollState())
        ) {
            val content = if (viewMode == "Formatted") currentSession.formattedText ?: currentSession.draftText
            else currentSession.draftText
            when {
                currentSession.status == "QUOTA" -> {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)
                    ) {
                        Text(
                            "Monthly transcription limit reached",
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your recording is saved — it can be transcribed after you upgrade, redeem a code, or when your cycle resets.",
                            fontSize = 13.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenSubscription) { Text("View plans & usage") }
                    }
                }
                currentSession.status == "TRANSCRIBING" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Transcribing…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                content.isBlank() -> {
                    Text(
                        "No transcription available.",
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                viewMode == "Formatted" && currentSession.formattedText != null -> {
                    // AI-formatted notes are Markdown — render them properly.
                    com.mikepenz.markdown.m3.Markdown(content = content)
                }
                else -> {
                    Text(content, fontSize = 15.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // Real audio player (local file, or streamed from Cloud Storage on other devices)
        AudioPlayerBar(
            audioPath = currentSession.audioPath,
            storagePath = currentSession.storagePath,
            fallbackDurationMs = currentSession.durationMs
        )
    }
}

@Composable
private fun AudioPlayerBar(audioPath: String?, storagePath: String?, fallbackDurationMs: Long) {
    val player = remember { MediaPlayer() }
    var available by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(fallbackDurationMs.toInt()) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableFloatStateOf(0f) }

    // Local audio (original or cached download) prepares automatically. Cloud-only audio
    // is NOT auto-fetched — the user taps the cloud button; the download then PERSISTS in
    // the cache (OneDrive model) so reopening the note doesn't re-download.
    val context = androidx.compose.ui.platform.LocalContext.current
    val localOk = remember(audioPath) { audioPath != null && File(audioPath).exists() }
    val cachedOk = remember(storagePath) { com.mehdiacho.yap.data.AudioCache.isCached(context, storagePath) }
    val remoteAvailable = !localOk && !cachedOk && storagePath != null
    var remoteRequested by remember(storagePath) { mutableStateOf(false) }
    var loadingRemote by remember { mutableStateOf(false) }

    LaunchedEffect(audioPath, storagePath, remoteRequested) {
        val source: String? = when {
            localOk -> audioPath
            cachedOk -> com.mehdiacho.yap.data.AudioCache.fileFor(context, storagePath!!).absolutePath
            remoteRequested && storagePath != null -> {
                loadingRemote = true
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.mehdiacho.yap.data.AudioCache.download(context, storagePath).absolutePath
                    }.getOrNull()
                }
            }
            else -> null
        }
        if (source != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    player.reset()
                    player.setDataSource(source)
                    player.prepare()
                    durationMs = player.duration
                    player.setOnCompletionListener {
                        isPlaying = false; positionMs = 0; player.seekTo(0)
                    }
                    available = true
                }
            }
        }
        loadingRemote = false
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(100)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape)
                .background(
                    when {
                        available || remoteAvailable -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .clickable(enabled = available || (remoteAvailable && !loadingRemote)) {
                    when {
                        available && isPlaying -> { player.pause(); isPlaying = false }
                        available -> { player.start(); isPlaying = true }
                        remoteAvailable -> remoteRequested = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                loadingRemote -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White
                )
                !available && remoteAvailable -> Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = "Load audio from cloud",
                    tint = Color.White
                )
                else -> Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (available) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val sliderValue = if (seeking) seekPos else positionMs.toFloat()
        Slider(
            value = sliderValue,
            onValueChange = { seeking = true; seekPos = it },
            onValueChangeFinished = {
                val target = seekPos.toInt()
                runCatching { player.seekTo(target) }
                positionMs = target
                seeking = false
            },
            valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
            enabled = available && durationMs > 0,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${fmt(if (seeking) seekPos.toInt() else positionMs)} / ${fmt(durationMs)}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun fmt(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
