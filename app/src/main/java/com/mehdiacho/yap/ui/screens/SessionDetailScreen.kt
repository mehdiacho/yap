package com.mehdiacho.yap.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.BuildConfig
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.components.Pill
import com.mehdiacho.yap.ui.note.NoteBody
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapDims
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The two ways to read a note.
 *
 * "Formatted" and "Raw" described how the text was produced, not what you get. **Note** is the
 * one you live in — the tidied prose plus everything the enrichment pass pulled out of it
 * (tasks today, more later). **Transcript** is the verbatim record and stays exactly that:
 * nothing derived is layered on top, because the whole point of keeping it is to be able to
 * check what was actually said.
 */
private const val NOTE_VIEW = "Note"
private const val TRANSCRIPT_VIEW = "Transcript"

@Composable
fun SessionDetailScreen(
    viewModel: MainViewModel,
    sessionId: String,
    onOpenSubscription: () -> Unit = {},
    onOpenApiKey: () -> Unit = {},
    onAppendRecording: (com.mehdiacho.yap.data.Session) -> Unit = {},
    onBack: () -> Unit,
) {
    // Stable flow: remember by id so we don't recreate the StateFlow every recomposition.
    val sessionFlow = remember(sessionId) { viewModel.getSessionById(sessionId) }
    val session by sessionFlow.collectAsState()
    var viewMode by remember { mutableStateOf(NOTE_VIEW) }

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
    // The text as it stood when editing began. Kept so a hand correction can be diffed
    // against the result — without it there is no "before", and nothing to propagate.
    var bodyOriginal by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Propagate-a-correction state: one detected substitution plus everywhere it still applies.
    var pendingCorrection by remember {
        mutableStateOf<com.mehdiacho.yap.data.TextCorrections.Correction?>(null)
    }
    var pendingOccurrences by remember {
        mutableStateOf<List<com.mehdiacho.yap.data.TextCorrections.Occurrence>>(emptyList())
    }
    var correctionCautions by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var correctionChecking by remember { mutableStateOf(false) }

    fun currentText(): String =
        if (viewMode == NOTE_VIEW) currentSession.formattedText ?: currentSession.draftText
        else currentSession.draftText

    /**
     * The note as text for copying and sharing.
     *
     * Only the Note view has markup to strip. The transcript is already verbatim speech, and
     * running it through the block parser would be a no-op at best and could reflow it at
     * worst — the one view whose whole value is being unaltered.
     */
    fun shareableText(): String =
        if (viewMode == NOTE_VIEW) com.mehdiacho.yap.ui.note.NoteExport.asPlainText(currentText())
        else currentText()

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
                val saved = bodyDraft
                val updated = if (viewMode == NOTE_VIEW && hasFormatted)
                    currentSession.copy(formattedText = saved)
                else currentSession.copy(draftText = saved)
                viewModel.updateSession(updated)
                editingBody = false

                // Transcription mishears a word the same way every time, so a single fix is
                // rarely the whole fix. Offer the rest — never apply them unasked.
                val corrections = com.mehdiacho.yap.data.TextCorrections
                    .detectCorrections(bodyOriginal, saved)
                val firstWithHits = corrections.firstNotNullOfOrNull { c ->
                    val hits = com.mehdiacho.yap.data.TextCorrections.findOccurrences(saved, c)
                    if (hits.isEmpty()) null else c to hits
                }
                if (firstWithHits != null) {
                    val (correction, hits) = firstWithHits
                    pendingCorrection = correction
                    pendingOccurrences = hits
                    correctionCautions = emptyMap()
                    correctionChecking = true
                    scope.launch {
                        // Advisory only, and time-boxed: the list is already on screen and
                        // usable. If the model is slow or the account is out of credit, the
                        // user simply gets no annotations rather than a blocked edit.
                        val advice = runCatching {
                            kotlinx.coroutines.withTimeout(12_000) {
                                com.mehdiacho.yap.data.TranscriptionClient.reviewCorrections(
                                    correction.from,
                                    correction.to,
                                    hits.map { "${it.contextBefore}${it.matched}${it.contextAfter}" },
                                )
                            }
                        }.getOrNull()
                        correctionCautions = advice.orEmpty()
                            .filter { it.intentional && it.index in hits.indices }
                            .associate { it.index to it.reason.ifBlank { "May be intentional here" } }
                        correctionChecking = false
                    }
                }
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
            StatusChip(currentSession.status)
            // Common actions moved to the docked pill bar above the player — one tap each,
            // always visible, instead of two taps through an overflow. Only genuinely rare
            // and destructive items stay behind the three dots.
            Box {
                Icon(Icons.Default.MoreVert, "More", tint = cs.onSurface,
                    modifier = Modifier.clickable { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Add a recording") },
                        onClick = { menuOpen = false; onAppendRecording(currentSession) })
                    DropdownMenuItem(text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, currentSession.title)
                                putExtra(android.content.Intent.EXTRA_TEXT, shareableText())
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share note"))
                        })
                    // Markdown as a file you take away, which is what it's good for: the
                    // structure survives into an editor or a repo. Sent as text/markdown with
                    // a .md extension rather than pasted, because pasted Markdown arrives as
                    // a paragraph of literal asterisks in every app that receives it.
                    DropdownMenuItem(text = { Text("Export as Markdown") },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        com.mehdiacho.yap.ui.note.NoteExport.shareMarkdown(
                                            context, currentSession.title, currentText(),
                                        ),
                                        "Export note",
                                    )
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context, "Couldn't create the file", android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete", color = cs.error) },
                        onClick = { menuOpen = false; showDeleteConfirm = true })
                }
            }
        }

        if (showEditTitle) {
            AlertDialog(
                onDismissRequest = { showEditTitle = false },
                shape = YapShapes.dialog,
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
                shape = YapShapes.dialog,
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

        // "You fixed one. Here are the others." Raised after an edit that changed a single
        // word, never applied without an explicit choice.
        pendingCorrection?.let { correction ->
            CorrectionReviewSheet(
                correction = correction,
                occurrences = pendingOccurrences,
                cautions = correctionCautions,
                checking = correctionChecking,
                onApply = { accepted ->
                    val base = currentText()
                    val fixed = com.mehdiacho.yap.data.TextCorrections.apply(base, accepted)
                    if (fixed != base) {
                        viewModel.updateSession(
                            if (viewMode == NOTE_VIEW && hasFormatted)
                                currentSession.copy(formattedText = fixed)
                            else currentSession.copy(draftText = fixed)
                        )
                    }
                    pendingCorrection = null
                    pendingOccurrences = emptyList()
                },
                onDismiss = {
                    pendingCorrection = null
                    pendingOccurrences = emptyList()
                },
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
                // Where a shared note came in from. Only shown when the platform actually told
                // us — an empty "From" chip on every note the user recorded themselves would
                // be noise on the overwhelming majority of the library.
                currentSession.sourceApp?.takeIf { it.isNotBlank() }?.let {
                    MetaChip("From $it", cs.surfaceContainerHigh, cs.onSurfaceVariant)
                }
            }
        }

        // Draft-only banner — "Not refined yet · Polish now" (design · draft-only)
        if (draftOnly) {
            Row(
                Modifier.padding(horizontal = 22.dp).padding(top = 16.dp).fillMaxWidth()
                    .clip(YapShapes.panel).background(ext.warningSoft)
                    .border(1.dp, ext.onOfflineContainer.copy(alpha = 0.25f), YapShapes.panel)
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
                    .clip(YapShapes.card).background(cs.surfaceContainer).padding(5.dp)
            ) {
                listOf(NOTE_VIEW, TRANSCRIPT_VIEW).forEach { mode ->
                    val selected = viewMode == mode
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .then(if (selected) Modifier.shadow(2.dp, YapShapes.md) else Modifier)
                            .clip(YapShapes.md)
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
            // A note made from a shared video leads with the video. It is the source the
            // rest of the note was derived from, and captions only mean anything next to
            // the picture they belong to — so it heads the note rather than being docked
            // at the bottom like an audio recording.
            if (currentSession.hasVideo) {
                com.mehdiacho.yap.ui.components.VideoNotePlayer(
                    videoPath = currentSession.videoPath,
                    videoStoragePath = currentSession.videoStoragePath,
                    words = currentSession.wordTimings,
                    fallbackDurationMs = currentSession.durationMs,
                )
                Spacer(Modifier.height(18.dp))
            }
            val content = currentText()
            when {
                currentSession.status == "QUOTA" -> {
                    Column(
                        Modifier.fillMaxWidth().clip(YapShapes.card)
                            .background(cs.errorContainer).padding(16.dp)
                    ) {
                        Text("Monthly transcription limit reached", fontWeight = FontWeight.Bold,
                            fontSize = 15.sp, color = cs.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("Your recording is saved. It can be transcribed once you upgrade, redeem a code, or your cycle resets.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer)
                        Spacer(Modifier.height(12.dp))
                        // The banner above promises the recording can be transcribed later,
                        // and until this button existed there was no way to do it: the
                        // upgrade landed and the note stayed stuck. Offered unconditionally
                        // — if the cap is still spent, saying so costs one round trip and
                        // is better than hiding the only way forward.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.retryTranscription(currentSession) },
                                shape = RoundedCornerShape(50),
                            ) { Text("Transcribe now") }
                            TextButton(onClick = onOpenSubscription) { Text("Plans & usage") }
                        }
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
                // A failed transcription used to render as a flat "No transcription
                // available." with no way forward — the audio was fine, but the note was
                // permanently empty. Offer the retry instead.
                currentSession.status == "FAILED" -> {
                    Column(
                        Modifier.fillMaxWidth().clip(YapShapes.card)
                            .background(cs.errorContainer).padding(16.dp)
                    ) {
                        Text("Transcription didn't finish", fontWeight = FontWeight.Bold,
                            fontSize = 15.sp, color = cs.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        // Prefer the server's explanation. A generic "usually temporary" is
                        // actively misleading when the real cause is an empty AI billing
                        // account — no amount of retrying fixes that on its own.
                        Text(
                            currentSession.statusMessage?.takeIf { it.isNotBlank() }
                                ?: "Your recording is safe. Only the transcription failed. " +
                                "This is usually temporary.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = cs.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        // Retrying an invalid key or rejected audio just reproduces the same
                        // failure, so don't invite it — those need a change first.
                        val worthRetrying = currentSession.statusReason !in
                            listOf("PROVIDER_AUTH", "AUDIO_REJECTED")
                        if (worthRetrying) {
                            Button(
                                onClick = { viewModel.retryTranscription(currentSession) },
                                shape = RoundedCornerShape(50),
                            ) { Text("Try again") }
                        }
                        if (currentSession.statusReason == "PROVIDER_AUTH") {
                            Button(onClick = onOpenApiKey, shape = RoundedCornerShape(50)) {
                                Text("Check API key")
                            }
                        }
                    }
                }
                content.isBlank() -> Text("No transcription available.", fontSize = 15.sp, color = cs.onSurfaceVariant)
                viewMode == NOTE_VIEW && hasFormatted -> {
                    // Dialogue renderer when the note reads as speaker-labelled lines; the
                    // app's own block renderer otherwise. Neither one is a Markdown renderer
                    // any more — the source is still Markdown, but what you read is Yap's
                    // layout, and the raw form is a deliberate export rather than the default.
                    val dialogue = remember(content) { parseDialogue(content) }
                    if (dialogue != null) DialogueBody(dialogue)
                    else NoteBody(content)
                }
                else -> Text(content, fontSize = 15.sp, lineHeight = 24.sp, color = cs.onSurface)
            }

            // Derived content belongs to the Note view only. Tasks are something we inferred
            // FROM the transcript, so showing them under it would misrepresent the one view
            // whose value is being an unedited record.
            if (viewMode == NOTE_VIEW) ActionItemsSection(
                session = currentSession,
                onToggle = { item, done -> viewModel.setActionItemDone(currentSession, item.id, done) },
                onAct = { item ->
                    // Hand off to whatever the user actually uses — assistant, task app, chat.
                    // Carrying the note's title gives the receiving app the context that makes
                    // a bare "Email Sam the deck" actionable.
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, item.text)
                        putExtra(
                            android.content.Intent.EXTRA_TEXT,
                            "${item.text}\n\n(from “${currentSession.title}” in Yap)",
                        )
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Send task to…"))
                },
            )
            Spacer(Modifier.height(16.dp))
        }

        // Action pills — docked above the player so the two most-used things (Edit, Copy)
        // are a single tap from anywhere in the note.
        HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionPill(Icons.Outlined.Edit, "Edit") {
                bodyOriginal = currentText()
                bodyDraft = bodyOriginal
                editingBody = true
            }
            ActionPill(Icons.Default.ContentCopy, "Copy") {
                // What lands on the clipboard is what's on screen. Pasting "## Decisions"
                // and rows of asterisks into a message is nobody's intent when they tap
                // Copy on a note they are looking at. Markdown has its own export.
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(shareableText()))
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            ActionPill(Icons.Default.Mic, "Add recording") { onAppendRecording(currentSession) }
            ActionPill(Icons.Default.Share, "Share") {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, currentSession.title)
                    putExtra(android.content.Intent.EXTRA_TEXT, shareableText())
                }
                context.startActivity(android.content.Intent.createChooser(send, "Share note"))
            }
        }

        if (currentSession.hasVideo) {
            // Nothing docked here. The player at the top of the note already plays this
            // note's audio — it is the same performance with the picture attached — and a
            // second transport for it would be two controls for one thing.
        } else if (currentSession.audioPath == null && currentSession.storagePath == null &&
            currentSession.extraRecordings.isEmpty()) {
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
                    // "Audio was discarded" is a claim, and on a note made from shared text it
                    // was a false one — it sent people hunting for a recording that never
                    // existed, or left them thinking their audio had been mishandled. Say
                    // which of the two actually happened.
                    Text(
                        when {
                            currentSession.source == com.mehdiacho.yap.data.SessionSource.MERGED ->
                                "Merged from ${currentSession.mergedFrom.size} notes — the recordings are on those"
                            currentSession.neverHadAudio ->
                                "Shared as text — there was no recording"
                            else -> "Audio was discarded after transcription"
                        },
                        fontSize = 13.sp,
                        color = ext.subdued,
                    )
                }
            }
        } else {
            // 5 · Audio player bar (§2.11), docked bottom with hairline. Appended clips are
            // separate recordings — dots + horizontal swipe (within the player only) switch.
            HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
            MultiRecordingPlayer(currentSession)
        }
    }
}

/**
 * One docked action. Labelled, not icon-only: an unlabelled glyph row is a guessing game,
 * and these are the actions people reach for constantly.
 */
@Composable
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
}

@Composable
private fun MetaChip(label: String, bg: Color, fg: Color, bold: Boolean = false) {
    // Category and topic are both user data, so both get the width cap: an unusually long
    // one used to widen the chip until it ran off the edge of the title block.
    Pill(
        label = label,
        background = bg,
        contentColor = fg,
        shape = YapShapes.md,
        bold = bold,
        maxWidth = YapDims.chipMaxWidth,
    )
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
                .clip(YapShapes.card).background(cs.surfaceContainerLow)
                .border(2.dp, cs.primary, YapShapes.card).padding(14.dp)
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
                Box(Modifier.width(70.dp).height(20.dp).clip(YapShapes.md).background(bar))
                Box(Modifier.width(54.dp).height(20.dp).clip(YapShapes.md).background(bar))
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

/**
 * Pages through the note's recordings (primary + appended clips). One recording renders the
 * plain player bar; more get pager dots and horizontal swipe confined to the player area —
 * the note content above never moves. Each switch recreates the bar (fresh MediaPlayer).
 */
@Composable
private fun MultiRecordingPlayer(session: com.mehdiacho.yap.data.Session) {
    val cs = MaterialTheme.colorScheme
    val recordings = remember(session) {
        buildList {
            if (session.audioPath != null || session.storagePath != null) {
                add(com.mehdiacho.yap.data.ExtraRecording(
                    session.audioPath, session.storagePath, session.durationMs, session.bookmarks))
            }
            addAll(session.extraRecordings)
        }
    }
    if (recordings.size == 1) {
        val r = recordings[0]
        AudioPlayerBar(r.audioPath, r.storagePath, r.durationMs, r.bookmarks)
        return
    }

    var current by rememberSaveable(session.id) { mutableIntStateOf(0) }
    if (current > recordings.lastIndex) current = recordings.lastIndex
    Column(
        Modifier.fillMaxWidth().background(cs.surfaceContainer)
            .pointerInput(recordings.size) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        val threshold = 56.dp.toPx()
                        if (acc <= -threshold && current < recordings.lastIndex) current++
                        else if (acc >= threshold && current > 0) current--
                    },
                ) { _, dragAmount -> acc += dragAmount }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            recordings.forEachIndexed { i, _ ->
                Box(
                    Modifier.padding(horizontal = 3.dp)
                        .size(width = if (i == current) 18.dp else 7.dp, height = 7.dp)
                        .clip(YapShapes.xs)
                        .background(if (i == current) cs.primary else cs.surfaceVariant)
                        .clickable { current = i },
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (current == 0) "Original" else "Addition $current",
                fontSize = 11.sp, color = cs.onSurfaceVariant,
            )
        }
        androidx.compose.animation.AnimatedContent(
            targetState = current,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.slideInHorizontally(tween(200)) { dir * it / 3 } +
                    androidx.compose.animation.fadeIn(tween(200))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(tween(200)) { -dir * it / 3 } +
                        androidx.compose.animation.fadeOut(tween(150)))
            },
            label = "recPager",
        ) { idx ->
            val r = recordings[idx]
            AudioPlayerBar(r.audioPath, r.storagePath, r.durationMs, r.bookmarks)
        }
    }
}

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
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(cs.primary))
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
