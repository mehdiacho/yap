package com.mehdiacho.yap.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.BuildConfig
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// Amber accents for Smart Review flagged blocks (design #E0A52E family).
private val ReviewAmber = Color(0xFFE0A52E)
private val ReviewAmberBg = Color(0xFFFFF8EC)
private val ReviewAmberHighlight = Color(0xFFFFE0A8)
private val ReviewBannerBg = Color(0xFFEEF0FF)

@Composable
fun SessionDetailScreen(
    viewModel: MainViewModel,
    sessionId: String,
    onOpenSubscription: () -> Unit = {},
    onBack: () -> Unit,
) {
    // Stable flow: remember by id so we don't recreate the StateFlow every recomposition.
    val sessionFlow = remember(sessionId) { viewModel.getSessionById(sessionId) }
    val session by sessionFlow.collectAsState()
    var viewMode by remember { mutableStateOf("Formatted") }

    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended

    // Detail · loading — skeleton per the design frame (no spinner).
    if (session == null) {
        DetailSkeleton(onBack)
        return
    }
    val currentSession = session!!

    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var showEditTitle by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var editingBody by remember { mutableStateOf(false) }
    var bodyDraft by remember { mutableStateOf("") }
    // Smart Review — real run against the deployed smartReview function.
    val scope = rememberCoroutineScope()
    var reviewRunning by remember { mutableStateOf(false) }
    var reviewBlocks by remember { mutableStateOf<List<String>?>(null) }
    var reviewFlags by remember { mutableStateOf<Map<Int, com.mehdiacho.yap.data.TranscriptionClient.ReviewedBlock>>(emptyMap()) }
    val reviewActive = reviewRunning || reviewBlocks != null
    fun currentText(): String =
        if (viewMode == "Formatted") currentSession.formattedText ?: currentSession.draftText
        else currentSession.draftText

    val hasFormatted = currentSession.formattedText != null
    val draftOnly = !hasFormatted && currentSession.draftText.isNotBlank() &&
        currentSession.status !in listOf("TRANSCRIBING", "QUOTA")

    // Detail · editing block — dedicated layout (close — "Editing block" — Done).
    if (editingBody) {
        EditingBlockContent(
            title = currentSession.title,
            draft = bodyDraft,
            onDraftChange = { bodyDraft = it },
            onClose = { editingBody = false },
            onDone = {
                val updated = if (viewMode == "Formatted" && hasFormatted)
                    currentSession.copy(formattedText = bodyDraft)
                else currentSession.copy(draftText = bodyDraft)
                viewModel.updateSession(updated)
                editingBody = false
            },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().background(cs.background).statusBarsPadding()
    ) {
        // 1 · Minimal app bar — back · status chip · overflow (§3.4)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.clickable { onBack() }, tint = cs.onSurface)
            if (reviewRunning) ReviewingChip() else StatusChip(currentSession.status)
            Box {
                Icon(Icons.Default.MoreVert, "More", tint = cs.onSurface,
                    modifier = Modifier.clickable { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit title") },
                        onClick = { menuOpen = false; titleDraft = currentSession.title; showEditTitle = true })
                    DropdownMenuItem(text = { Text("Edit text") },
                        onClick = { menuOpen = false; bodyDraft = currentText(); editingBody = true })
                    DropdownMenuItem(text = { Text("Copy text") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentText()))
                            android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    DropdownMenuItem(text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, currentSession.title)
                                putExtra(android.content.Intent.EXTRA_TEXT, currentText())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share note"))
                        })
                    if (hasFormatted) {
                        DropdownMenuItem(
                            text = { Text(if (reviewActive) "Dismiss Smart Review" else "Run Smart Review") },
                            onClick = {
                                menuOpen = false
                                if (reviewActive) {
                                    reviewBlocks = null; reviewFlags = emptyMap(); reviewRunning = false
                                } else {
                                    val blocks = (currentSession.formattedText ?: "")
                                        .split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
                                    if (blocks.isEmpty()) return@DropdownMenuItem
                                    reviewBlocks = blocks
                                    reviewRunning = true
                                    scope.launch {
                                        try {
                                            val results = com.mehdiacho.yap.data.TranscriptionClient.smartReview(blocks)
                                            val flagged = results.filter { it.flagged }
                                            if (flagged.isEmpty()) {
                                                android.widget.Toast.makeText(context,
                                                    "Smart Review found no issues", android.widget.Toast.LENGTH_SHORT).show()
                                                reviewBlocks = null
                                            } else {
                                                reviewFlags = flagged.associateBy { it.blockIndex }
                                            }
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context,
                                                e.message ?: "Smart Review failed", android.widget.Toast.LENGTH_LONG).show()
                                            reviewBlocks = null
                                        } finally { reviewRunning = false }
                                    }
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete", color = cs.error) },
                        onClick = { menuOpen = false; showDeleteConfirm = true })
                }
            }
        }

        if (showEditTitle) {
            AlertDialog(
                onDismissRequest = { showEditTitle = false },
                shape = RoundedCornerShape(28.dp),
                title = { Text("Edit title") },
                text = { OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = titleDraft.isNotBlank(), onClick = {
                        viewModel.updateSession(currentSession.copy(title = titleDraft.trim()))
                        showEditTitle = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showEditTitle = false }) { Text("Cancel") } }
            )
        }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                shape = RoundedCornerShape(28.dp),
                title = { Text("Move to trash?") },
                text = { Text("You can restore it from the trash for 30 days.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.trashSessions(listOf(currentSession.id))
                        onBack()
                    }) { Text("Move to trash", color = cs.error) }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
            )
        }

        // 2 · Title block + chip row
        Column(Modifier.padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(currentSession.title, fontFamily = Outfit, fontSize = 23.sp,
                    fontWeight = FontWeight.Bold, color = cs.onSurface, lineHeight = 26.sp,
                    letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Edit, "Edit title", tint = ext.subdued,
                    modifier = Modifier.padding(top = 4.dp).size(17.dp)
                        .clickable { titleDraft = currentSession.title; showEditTitle = true })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(currentSession.category.ifBlank { "Memo" }, cs.primaryContainer, cs.onPrimaryContainer, bold = true)
                if (currentSession.topicCluster.isNotBlank() && currentSession.topicCluster != "General")
                    MetaChip(currentSession.topicCluster, cs.surfaceContainerHigh, cs.onSurfaceVariant)
            }
        }

        // Smart Review banner while checking
        if (reviewRunning) {
            Row(
                Modifier.padding(horizontal = 22.dp).padding(top = 14.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(ReviewBannerBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Verified, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Smart Review — checking ${reviewBlocks?.size ?: 0} blocks…", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
            }
        }

        // Draft-only banner — "Not refined yet · Polish now" (design · draft-only)
        if (draftOnly) {
            Row(
                Modifier.padding(horizontal = 22.dp).padding(top = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)).background(ext.warningSoft)
                    .border(1.dp, Color(0xFFF0D9A8), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Bolt, null, tint = ext.onOfflineContainer, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Not refined yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ext.onOfflineContainer)
                    Text("Polish to clean up & format.", fontSize = 12.sp, color = ext.onOfflineContainer.copy(alpha = 0.85f))
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(cs.primary)
                        .clickable { viewModel.polishSession(currentSession) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text("Polish now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimary) }
            }
        }

        // 3 · Segmented control — only when a formatted version exists (§3.4 / draft-only variant)
        if (hasFormatted) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainer).padding(5.dp)
            ) {
                listOf("Formatted", "Raw").forEach { mode ->
                    val selected = viewMode == mode
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .then(if (selected) Modifier.shadow(2.dp, RoundedCornerShape(11.dp)) else Modifier)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selected) cs.surface else Color.Transparent)
                            .clickable { viewMode = mode },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(mode, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) cs.onSurface else cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 4 · Flexible scrolling body
        Column(Modifier.weight(1f).padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            val content = currentText()
            when {
                currentSession.status == "QUOTA" -> {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(cs.errorContainer).padding(16.dp)
                    ) {
                        Text("Monthly transcription limit reached", fontWeight = FontWeight.Bold,
                            fontSize = 15.sp, color = cs.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("Your recording is saved — it can be transcribed after you upgrade, redeem a code, or when your cycle resets.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenSubscription, shape = RoundedCornerShape(50)) { Text("View plans & usage") }
                    }
                }
                currentSession.status == "TRANSCRIBING" -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(if (currentSession.draftText.isBlank()) "Transcribing…" else "Polishing draft…",
                                fontSize = 15.sp, color = cs.onSurfaceVariant)
                        }
                        if (currentSession.draftText.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(currentSession.draftText, fontSize = 15.sp, lineHeight = 24.sp, color = cs.onSurfaceVariant)
                        }
                    }
                }
                content.isBlank() -> Text("No transcription available.", fontSize = 15.sp, color = cs.onSurfaceVariant)
                reviewBlocks != null && reviewFlags.isNotEmpty() -> {
                    // Smart Review results — flagged blocks get the amber card with Keep/Fix.
                    val blocks = reviewBlocks!!
                    Column {
                        blocks.forEachIndexed { i, block ->
                            val flag = reviewFlags[i]
                            if (flag != null) {
                                SmartReviewFlaggedBlock(
                                    text = block,
                                    highlight = null,
                                    warningTitle = "Possible issue",
                                    warningBody = flag.reason ?: "Smart Review suggests a correction. Fix applies it.",
                                    fixLabel = "Fix",
                                    onKeep = {
                                        val left = reviewFlags - i
                                        reviewFlags = left
                                        if (left.isEmpty()) reviewBlocks = null
                                    },
                                    onFix = {
                                        val newBlocks = blocks.toMutableList().also { it[i] = flag.newText }
                                        viewModel.updateSession(
                                            currentSession.copy(formattedText = newBlocks.joinToString("\n\n")))
                                        val left = reviewFlags - i
                                        reviewFlags = left
                                        reviewBlocks = if (left.isEmpty()) null else newBlocks
                                    },
                                )
                            } else {
                                com.mikepenz.markdown.m3.Markdown(content = block)
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                    }
                }
                viewMode == "Formatted" && hasFormatted -> {
                    // Dialogue renderer when the note reads as speaker-labelled lines; markdown otherwise.
                    val dialogue = remember(content) { parseDialogue(content) }
                    if (dialogue != null) DialogueBody(dialogue)
                    else com.mikepenz.markdown.m3.Markdown(content = content)
                }
                else -> Text(content, fontSize = 15.sp, lineHeight = 24.sp, color = cs.onSurface)
            }
            Spacer(Modifier.height(16.dp))
        }

        // Smart Review summary bar — real issue count while results are on screen.
        if (reviewBlocks != null && reviewFlags.isNotEmpty()) {
            Column {
                HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                Box(
                    Modifier.padding(horizontal = 22.dp, vertical = 12.dp).fillMaxWidth().height(46.dp)
                        .clip(RoundedCornerShape(23.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Smart Review · ${reviewFlags.size} issue${if (reviewFlags.size == 1) "" else "s"} found",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        } else if (currentSession.audioPath == null && currentSession.storagePath == null) {
            // Detail · no-audio — muted row replaces the player.
            Column {
                HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().background(cs.surfaceContainerLow).navigationBarsPadding()
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.MusicOff, null, tint = ext.subdued, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Audio was discarded after transcription", fontSize = 13.sp, color = ext.subdued)
                }
            }
        } else {
            // 5 · Audio player bar (§2.11), docked bottom with hairline.
            HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
            AudioPlayerBar(
                audioPath = currentSession.audioPath,
                storagePath = currentSession.storagePath,
                fallbackDurationMs = currentSession.durationMs,
                bookmarks = currentSession.bookmarks,
            )
        }
    }
}

/** Reviewing chip — Primary-Container pill with a spinning autorenew glyph (§2.5 Polishing style). */
@Composable
private fun ReviewingChip() {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "rev")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1550, easing = androidx.compose.animation.core.LinearEasing)), label = "ra")
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Autorenew, null, tint = cs.onPrimaryContainer,
            modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = angle })
        Spacer(Modifier.width(6.dp))
        Text("Reviewing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
    }
}

@Composable
private fun MetaChip(label: String, bg: Color, fg: Color, bold: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(label, fontSize = 11.sp, color = fg,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---------- Dialogue renderer ----------

private sealed interface DialogueItem
private data class SpeakerLine(val speaker: String, val text: String) : DialogueItem
private data class ActionLine(val text: String) : DialogueItem

private val dialogueRegex = Regex("^\\s*\\*{0,2}([A-Z][A-Za-z .'-]{0,18}?)\\*{0,2}\\s*:\\s+(.+)$")

/** Detects speaker-labelled content ("MAYA: …"). Returns null unless most lines match. */
private fun parseDialogue(text: String): List<DialogueItem>? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.size < 2) return null
    val items = mutableListOf<DialogueItem>()
    var hits = 0
    for (line in lines) {
        val m = dialogueRegex.find(line)
        when {
            m != null && m.groupValues[1].lowercase() == "action" -> { items.add(ActionLine(m.groupValues[2])); hits++ }
            m != null -> { items.add(SpeakerLine(m.groupValues[1].trim().uppercase(), m.groupValues[2])); hits++ }
            line.startsWith(">") -> items.add(ActionLine(line.removePrefix(">").trim()))
            items.lastOrNull() is SpeakerLine -> {
                val last = items.removeAt(items.size - 1) as SpeakerLine
                items.add(last.copy(text = last.text + " " + line))
            }
            else -> return null // leading non-dialogue prose — not a dialogue note
        }
    }
    return if (hits >= lines.size * 0.6 && items.filterIsInstance<SpeakerLine>().map { it.speaker }.distinct().size >= 2)
        items else null
}

/** Detail · dialogue — speaker labels colored per speaker + italic action blockquote. */
@Composable
private fun DialogueBody(items: List<DialogueItem>) {
    val cs = MaterialTheme.colorScheme
    val speakerColors = listOf(cs.primary, cs.tertiary, YapTheme.extended.recording, cs.secondary)
    val colorFor = remember(items) {
        val order = items.filterIsInstance<SpeakerLine>().map { it.speaker }.distinct()
        order.withIndex().associate { (i, s) -> s to i % speakerColors.size }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item ->
            when (item) {
                is SpeakerLine -> Column {
                    Text(item.speaker, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = speakerColors[colorFor[item.speaker] ?: 0])
                    Spacer(Modifier.height(3.dp))
                    Text(item.text, fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurface)
                }
                is ActionLine -> Row {
                    Box(Modifier.width(3.dp).height(40.dp).background(cs.outlineVariant))
                    Spacer(Modifier.width(12.dp))
                    Text("Action: ${item.text}", fontSize = 13.sp, lineHeight = 19.sp,
                        fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

// ---------- Smart Review flagged block ----------

/**
 * Droppable Smart Review component — a flagged block with amber border, highlighted span,
 * warning sub-panel, and Keep/Fix actions. The AI review pipeline will feed these later.
 */
@Composable
fun SmartReviewFlaggedBlock(
    text: String,
    highlight: String?,
    warningTitle: String,
    warningBody: String,
    fixLabel: String,
    onKeep: () -> Unit,
    onFix: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ReviewAmberBg)
            .border(2.dp, ReviewAmber, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        val annotated = androidx.compose.ui.text.buildAnnotatedString {
            val idx = if (highlight != null) text.indexOf(highlight) else -1
            if (idx >= 0 && highlight != null) {
                append(text.substring(0, idx))
                pushStyle(androidx.compose.ui.text.SpanStyle(background = ReviewAmberHighlight))
                append(highlight)
                pop()
                append(text.substring(idx + highlight.length))
            } else append(text)
        }
        Text(annotated, fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurface)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(YapTheme.extended.offlineContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(Icons.Default.Warning, null, tint = YapTheme.extended.onOfflineContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column {
                Text(warningTitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YapTheme.extended.onOfflineContainer)
                Text(warningBody, fontSize = 11.sp, lineHeight = 15.sp,
                    color = YapTheme.extended.onOfflineContainer.copy(alpha = 0.85f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, ReviewAmber, RoundedCornerShape(10.dp))
                    .clickable { onKeep() }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Keep", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YapTheme.extended.onOfflineContainer) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(ReviewAmber)
                    .clickable { onFix() }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(fixLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A2600)) }
        }
    }
}

// ---------- Editing block ----------

/** Detail · editing block — close/"Editing block"/Done bar, bordered edit card, stepper stub. */
@Composable
private fun EditingBlockContent(
    title: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(Icons.Default.Close, "Cancel", tint = cs.onSurface, modifier = Modifier.clickable { onClose() })
            Text("Editing block", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
            Text("Done", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.primary,
                modifier = Modifier.clickable { onDone() })
        }
        Text(title, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
            letterSpacing = (-0.3).sp, lineHeight = 24.sp, color = cs.onSurface,
            modifier = Modifier.padding(horizontal = 22.dp).padding(top = 14.dp))
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.weight(1f).padding(horizontal = 22.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerLow)
                .border(2.dp, cs.primary, RoundedCornerShape(14.dp)).padding(14.dp)
        ) {
            OutlinedTextField(
                value = draft, onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                ),
            )
            // Version stepper — design stub; version history wiring is on the functionality list.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(cs.surface)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(50))
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ChevronLeft, "Older version", tint = cs.primary, modifier = Modifier.size(16.dp))
                    Text("1 / 1", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(Icons.Outlined.ChevronRight, "Newer version", tint = cs.primary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("Revert", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------- Loading skeleton ----------

/** Detail · loading — static skeleton bars with a slow opacity pulse. */
@Composable
private fun DetailSkeleton(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "skel")
    val alpha by t.animateFloat(1f, 0.55f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sa")
    val bar = cs.surfaceContainerHigh.copy(alpha = alpha)

    @Composable
    fun Bar(width: Float, height: Int, radius: Int = 6) {
        Box(Modifier.fillMaxWidth(width).height(height.dp).clip(RoundedCornerShape(radius.dp)).background(bar))
    }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Icon(Icons.Default.MoreVert, null, tint = cs.outlineVariant)
        }
        Column(Modifier.padding(horizontal = 22.dp).padding(top = 16.dp)) {
            Bar(0.8f, 24, 8); Spacer(Modifier.height(8.dp)); Bar(0.55f, 24, 8)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(70.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(bar))
                Box(Modifier.width(54.dp).height(20.dp).clip(RoundedCornerShape(8.dp)).background(bar))
            }
            Spacer(Modifier.height(16.dp)); Bar(1f, 42, 14)
            Spacer(Modifier.height(18.dp))
            listOf(1f, 0.96f, 0.88f, 0.7f).forEach { w -> Bar(w, 14); Spacer(Modifier.height(11.dp)) }
            Spacer(Modifier.height(10.dp))
            listOf(0.92f, 0.8f).forEach { w -> Bar(w, 14); Spacer(Modifier.height(11.dp)) }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
        Box(Modifier.padding(horizontal = 22.dp, vertical = 12.dp).navigationBarsPadding()) { Bar(1f, 40, 20) }
    }
}

// ---------- Audio player ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioPlayerBar(
    audioPath: String?,
    storagePath: String?,
    fallbackDurationMs: Long,
    bookmarks: List<Long> = emptyList(),
) {
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

    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(100)
        }
    }

    val cs = MaterialTheme.colorScheme
    Row(
        // navigationBarsPadding after background: surface color extends behind the gesture pill.
        modifier = Modifier.fillMaxWidth().background(cs.surfaceContainer).navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape)
                .background(if (available || remoteAvailable) cs.primary else cs.surfaceContainerHighest)
                .clickable(enabled = available || (remoteAvailable && !loadingRemote)) {
                    when {
                        available && isPlaying -> { player.pause(); isPlaying = false }
                        available -> { player.start(); isPlaying = true }
                        remoteAvailable -> remoteRequested = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loadingRemote -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                !available && remoteAvailable -> Icon(Icons.Default.CloudDownload, "Load audio from cloud", tint = cs.onPrimary)
                else -> Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (available) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // §2.11 scrubber — 4dp track, 10dp thumb.
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
            thumb = {
                Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
            },
            track = { state ->
                val frac = if (state.valueRange.endInclusive > 0f)
                    (state.value / state.valueRange.endInclusive).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary))
                    }
                    // Bookmark markers dropped while recording.
                    if (durationMs > 0 && bookmarks.isNotEmpty()) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            bookmarks.forEach { t ->
                                val f = (t.toFloat() / durationMs).coerceIn(0f, 1f)
                                Box(
                                    Modifier.offset(x = maxWidth * f - 3.dp)
                                        .size(6.dp).clip(CircleShape)
                                        .background(YapTheme.extended.recording)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.weight(1f).height(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "${fmt(if (seeking) seekPos.toInt() else positionMs)} / ${fmt(durationMs)}",
            fontSize = 12.sp, color = cs.onSurfaceVariant,
        )
    }
}

private fun fmt(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
