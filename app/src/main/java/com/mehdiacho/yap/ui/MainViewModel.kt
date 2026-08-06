package com.mehdiacho.yap.ui

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mehdiacho.yap.audio.RecorderEvent
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.data.FirestoreSessionRepository
import com.mehdiacho.yap.data.OrphanRecordings
import com.mehdiacho.yap.data.CURRENT_ENRICH_VERSION
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.data.TranscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FirestoreSessionRepository()

    val allSessions: StateFlow<List<Session>> = repository.allSessions()
        .catch { e ->
            Log.e("Yap", "sessions listener failed: ${e.message}", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trashedSessions: StateFlow<List<Session>> = repository.trashedSessions()
        .catch { e ->
            Log.e("Yap", "trash listener failed: ${e.message}", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Session id to open next (post-capture / share import). StateFlow so a cold-start
    // share doesn't lose the event before the UI subscribes; consumed by the UI.
    private val _pendingOpen = MutableStateFlow<String?>(null)
    val pendingOpen: StateFlow<String?> = _pendingOpen.asStateFlow()
    fun consumePendingOpen() { _pendingOpen.value = null }

    // True after the first Firestore snapshot lands — drives the Library loading skeleton
    // (before this, an empty list means "still loading", not "no notes").
    private val _sessionsLoaded = MutableStateFlow(false)
    val sessionsLoaded: StateFlow<Boolean> = _sessionsLoaded.asStateFlow()

    // Append-to-note: when set, the next finished recording merges into this note
    // instead of creating a new one. Cleared on finish/discard.
    private val _appendTarget = MutableStateFlow<Session?>(null)
    val appendTarget: StateFlow<Session?> = _appendTarget.asStateFlow()
    fun setAppendTarget(session: Session?) { _appendTarget.value = session }

    // One-shot, user-visible message (shown as a toast). Used for outcomes the user would
    // otherwise never learn about — notably an append that had to be saved separately.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    /**
     * Category chosen on the Capture screen before recording starts.
     *
     * Held here rather than in the screen so it survives navigating away mid-thought, and so
     * the transcription path can read it without threading it through four call sites.
     */
    private val _pendingCategory = MutableStateFlow<String?>(null)
    val pendingCategory: StateFlow<String?> = _pendingCategory.asStateFlow()
    fun setPendingCategory(category: String?) {
        _pendingCategory.value = category?.takeIf { it.isNotBlank() }
    }

    /** Progress of the action-item backfill: null when idle, else done-count to total. */
    private val _backfillProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val backfillProgress: StateFlow<Pair<Int, Int>?> = _backfillProgress.asStateFlow()

    /** Tick or untick a task on a note. */
    fun setActionItemDone(session: Session, itemId: String, done: Boolean) {
        viewModelScope.launch {
            val updated = session.copy(
                actionItems = session.actionItems.map {
                    if (it.id == itemId) it.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null)
                    else it
                }
            )
            runCatching { repository.upsert(updated) }
                .onFailure { Log.e("Yap", "task toggle failed: ${it.message}", it) }
        }
    }

    /**
     * Bring every older note up to [CURRENT_ENRICH_VERSION].
     *
     * The alternative — making every screen cope with notes that predate a feature — is how
     * codebases accumulate "if this field is missing" branches that never get removed. One
     * pass, one shape, and readers can assume the field is there.
     *
     * Explicit rather than automatic on launch: it costs a model call per note, and silently
     * spending someone's quota (or their own API credit) the first time they open the app
     * after an update isn't ours to decide.
     */
    fun backfillActionItems() {
        if (_backfillProgress.value != null) return // already running
        viewModelScope.launch {
            val stale = allSessions.value.filter {
                it.enrichVersion < CURRENT_ENRICH_VERSION && it.trashedAt == null
            }
            if (stale.isEmpty()) {
                _notice.value = "All notes are already up to date."
                return@launch
            }
            _backfillProgress.value = 0 to stale.size
            var processed = 0
            try {
                // Server caps a batch at 10; page through so a large library still completes.
                stale.chunked(10).forEach { chunk ->
                    val texts = chunk.map { it.id to (it.formattedText?.takeIf { t -> t.isNotBlank() } ?: it.draftText) }
                    val results = TranscriptionClient.enrichNotes(texts)
                    chunk.forEach { session ->
                        val tasks = results[session.id]
                        // Absent means that note failed; leave its version alone so the next
                        // run retries it rather than marking it done with nothing.
                        if (tasks != null) {
                            runCatching {
                                repository.upsert(
                                    session.copy(
                                        actionItems = mergeTasks(session.actionItems, tasks, session.id),
                                        enrichVersion = CURRENT_ENRICH_VERSION,
                                    )
                                )
                            }
                        }
                    }
                    processed += chunk.size
                    _backfillProgress.value = processed to stale.size
                }
                _notice.value = "Scanned ${stale.size} note${if (stale.size == 1) "" else "s"} for tasks."
            } catch (e: Exception) {
                Log.e("Yap", "backfill failed: ${e.message}", e)
                _notice.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Couldn't finish scanning. Already-scanned notes are saved."
            } finally {
                _backfillProgress.value = null
            }
        }
    }

    /**
     * Keep completion state across a re-extraction.
     *
     * Tasks are matched by text, not position: re-running the extractor on an edited note can
     * reorder, add or drop items, and losing every tick because a sentence moved would make
     * the feature untrustworthy the first time it happened.
     */
    private fun mergeTasks(
        existing: List<com.mehdiacho.yap.data.ActionItem>,
        fresh: List<String>,
        sessionId: String,
    ): List<com.mehdiacho.yap.data.ActionItem> {
        val byText = existing.associateBy { it.text.trim().lowercase() }
        return fresh.mapIndexed { i, text ->
            val prior = byText[text.trim().lowercase()]
            com.mehdiacho.yap.data.ActionItem(
                id = prior?.id ?: "$sessionId-$i",
                text = text,
                done = prior?.done ?: false,
                doneAt = prior?.doneAt,
            )
        }
    }

    // Recordings on disk that no note references. Populated on demand by the recovery
    // screen rather than continuously — it's a rare-path tool, not library state.
    private val _orphans = MutableStateFlow<List<OrphanRecordings.Orphan>>(emptyList())
    val orphans: StateFlow<List<OrphanRecordings.Orphan>> = _orphans.asStateFlow()
    private val _orphansScanning = MutableStateFlow(false)
    val orphansScanning: StateFlow<Boolean> = _orphansScanning.asStateFlow()

    /**
     * Rescan private storage for stranded recordings. Compares against active AND trashed
     * notes, so audio attached to a trashed note is never offered as "lost".
     */
    fun refreshOrphans() {
        viewModelScope.launch(Dispatchers.IO) {
            _orphansScanning.value = true
            try {
                val active = runCatching { repository.allSessions().first() }.getOrDefault(emptyList())
                val trashed = runCatching { repository.trashedSessions().first() }.getOrDefault(emptyList())
                _orphans.value = OrphanRecordings.find(getApplication(), active + trashed)
            } catch (e: Exception) {
                Log.e("Yap", "orphan scan failed: ${e.message}", e)
                _orphans.value = emptyList()
            } finally {
                _orphansScanning.value = false
            }
        }
    }

    /** Turn a stranded recording into a real note (transcribe + save), then reopen the list. */
    fun recoverOrphan(orphan: OrphanRecordings.Orphan) {
        viewModelScope.launch {
            _notice.value = "Recovering “${orphan.file.name}” — transcribing now."
            try {
                startTranscription(orphan.path, orphan.durationMs, "audio/wav")
            } catch (e: Exception) {
                Log.e("Yap", "orphan recovery failed: ${e.message}", e)
                _notice.value = "Couldn't recover that recording: ${e.message}"
            }
            refreshOrphans()
        }
    }

    /** Discard a stranded recording for good. Only reachable behind a confirm dialog. */
    fun discardOrphan(orphan: OrphanRecordings.Orphan) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { orphan.file.delete() }
                .onFailure { Log.w("Yap", "orphan delete failed: ${it.message}") }
            refreshOrphans()
        }
    }

    init {
        viewModelScope.launch {
            runCatching { repository.allSessions().first() }
            _sessionsLoaded.value = true
        }
    }

    init {
        // Persist a real session whenever a recording finishes.
        viewModelScope.launch {
            RecordingBus.events.collect { event ->
                when (event) {
                    is RecorderEvent.Finished -> {
                        val target = _appendTarget.value
                        _appendTarget.value = null
                        if (target != null) appendRecording(target, event.filePath, event.durationMs, event.bookmarks)
                        else startTranscription(event.filePath, event.durationMs, "audio/wav", event.bookmarks)
                    }
                    is RecorderEvent.Discarded -> _appendTarget.value = null
                    else -> {}
                }
            }
        }
        backfillAudioToStorage()
        purgeExpiredTrash()
    }

    /** Anything in the trash for more than 30 days is permanently removed. */
    private fun purgeExpiredTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                repository.trashedSessions().first()
                    .filter { (it.trashedAt ?: Long.MAX_VALUE) < cutoff }
                    .forEach { s ->
                        Log.i("Yap", "purging expired trash: ${s.id} '${s.title}'")
                        deleteSessionCompletely(s)
                    }
            } catch (e: Exception) {
                Log.w("Yap", "trash purge failed: ${e.message}")
            }
        }
    }

    /** Move notes to the trash (restorable for 30 days). */
    fun trashSessions(ids: Collection<String>) {
        viewModelScope.launch {
            ids.forEach { id ->
                try { repository.trash(id) } catch (e: Exception) {
                    Log.e("Yap", "trash failed for $id: ${e.message}", e)
                }
            }
        }
    }

    fun restoreSession(id: String) {
        viewModelScope.launch {
            try { repository.restore(id) } catch (e: Exception) {
                Log.e("Yap", "restore failed: ${e.message}", e)
            }
        }
    }

    /**
     * One-time backfill: upload any recording that predates Storage support (or whose
     * upload was missed) and link its cloud location onto the session document.
     */
    private fun backfillAudioToStorage() {
        val prefs = getApplication<Application>().getSharedPreferences("yap", Context.MODE_PRIVATE)
        if (prefs.getBoolean("audio_backfilled_v1", false)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessions = repository.allSessions().first()
                val pending = sessions.filter {
                    it.storagePath == null && it.audioPath != null && File(it.audioPath).exists()
                }
                var failures = 0
                pending.forEach { s ->
                    try {
                        val path = TranscriptionClient.uploadAudio(File(s.audioPath!!))
                        repository.upsert(s.copy(storagePath = path))
                        Log.i("Yap", "backfilled audio for '${s.title}' -> $path")
                    } catch (e: Exception) {
                        failures++
                        Log.w("Yap", "backfill failed for ${s.id}: ${e.message}")
                    }
                }
                // Only mark done when everything uploaded, so failures retry next launch.
                if (failures == 0) prefs.edit().putBoolean("audio_backfilled_v1", true).apply()
            } catch (e: Exception) {
                Log.e("Yap", "audio backfill failed: ${e.message}", e)
            }
        }
    }

    fun getSessionById(id: String): StateFlow<Session?> {
        return repository.sessionById(id)
            .catch { e ->
                Log.e("Yap", "session listener failed: ${e.message}", e)
                emit(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    /** Import an audio/video file shared into Yap from another app, then transcribe it. */
    fun importSharedAudio(uri: Uri, mimeType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val ext = mimeType.substringAfter('/', "bin").take(5)
                val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
                val file = File(dir, "shared_${System.currentTimeMillis()}.$ext")
                val input = ctx.contentResolver.openInputStream(uri)
                if (input == null) {
                    Log.w("YapShare", "openInputStream null for $uri")
                    return@launch
                }
                input.use { it.copyTo(file.outputStream()) }
                Log.d("YapShare", "copied ${file.length()} bytes from $uri ($mimeType)")
                val durationMs = runCatching {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(ctx, uri)
                    val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                    d
                }.getOrDefault(0L)
                startTranscription(file.absolutePath, durationMs, mimeType)
            } catch (e: Exception) {
                Log.e("YapShare", "import failed: ${e.message}", e)
            }
        }
    }

    /** Import shared plain text as a note (no transcription). */
    fun importSharedText(text: String) {
        viewModelScope.launch {
            val session = Session(
                id = UUID.randomUUID().toString(),
                title = "Shared note",
                draftText = text,
                formattedText = text,
                status = "DONE"
            )
            repository.upsert(session)
            _pendingOpen.value = session.id
        }
    }

    private suspend fun startTranscription(
        audioPath: String,
        durationMs: Long,
        mimeType: String,
        bookmarks: List<Long> = emptyList(),
    ) {
        // Live draft from the fed-stream recognizer (recordings only; empty for imports).
        val isOwnRecording = mimeType == "audio/wav"
        var session = Session(
            id = UUID.randomUUID().toString(),
            title = "Transcribing…",
            draftText = if (isOwnRecording) com.mehdiacho.yap.audio.LiveDraftEngine.currentDraft() else "",
            audioPath = audioPath,
            durationMs = durationMs,
            status = "TRANSCRIBING",
            bookmarks = bookmarks,
            // Applied straight away so the note is filed correctly while it transcribes,
            // not only once the server answers.
            category = _pendingCategory.value ?: "Memo",
        )
        try {
            repository.upsert(session)
        } catch (e: Exception) {
            Log.e("Yap", "failed to save session: ${e.message}", e)
            return
        }
        _pendingOpen.value = session.id // open the note immediately (draft visible right away)

        if (isOwnRecording) {
            // Final draft segments flush a moment after the stream closes — pick them up.
            // Generation-tagged: if a new recording starts during the wait, consume returns ""
            // and we keep the draft snapshot instead of stealing the new session's text.
            val draftGen = com.mehdiacho.yap.audio.LiveDraftEngine.generation
            kotlinx.coroutines.delay(2500)
            val finalDraft = com.mehdiacho.yap.audio.LiveDraftEngine.consumeDraft(draftGen)
            if (finalDraft.length > session.draftText.length) {
                session = session.copy(draftText = finalDraft)
                runCatching { repository.upsert(session) }
            }
        }

        try {
            val chosenCategory = _pendingCategory.value
            val r = TranscriptionClient.transcribe(
                File(audioPath), mimeType, durationMs, sessionId = session.id,
                category = chosenCategory,
                // Hold on to the cloud copy even if the transcription that follows is
                // refused — a note stopped at the monthly cap is transcribed later, and
                // that should be a re-run, not a second upload of the whole recording.
                onUploaded = { path -> session = session.copy(storagePath = path) },
            )
            val title = r.name.ifBlank { "Recording" }
            repository.upsert(
                session.copy(
                    title = title,
                    draftText = r.rawText,
                    formattedText = r.formattedText,
                    // The user's pick wins over the model's guess.
                    category = chosenCategory ?: r.category.ifBlank { "Memo" },
                    topicCluster = r.topicCluster.ifBlank { "General" },
                    storagePath = r.storagePath,
                    actionItems = mergeTasks(emptyList(), r.actionItems, session.id),
                    enrichVersion = CURRENT_ENRICH_VERSION,
                    status = "DONE",
                    statusReason = null,
                    statusMessage = null,
                )
            )
            // Heads-up "Your note is polished ✨" — skipped while the app is on screen.
            com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), session.id, title)
        } catch (e: Exception) {
            Log.e("Yap", "transcription failed for $audioPath ($mimeType): ${e.message}", e)
            if (isClientGaveUp(e)) {
                // Leave it TRANSCRIBING: the server finishes and writes the note itself,
                // and the Firestore listener will surface it.
                _notice.value = "Still transcribing — this one's long. It'll appear shortly."
                return
            }
            val quotaHit = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
            repository.upsert(
                if (quotaHit) session.copy(title = "Monthly limit reached", status = "QUOTA")
                else session.copy(
                    title = "Recording",
                    status = "FAILED",
                    // The server's message explains WHY (out of provider credit, bad audio,
                    // service down). Falling back to the raw exception is still better than
                    // the old bare "failed" with nothing to act on.
                    statusMessage = e.message?.takeIf { it.isNotBlank() },
                )
            )
        }
    }

    fun updateSession(session: Session) {
        viewModelScope.launch {
            try { repository.upsert(session) } catch (e: Exception) {
                Log.e("Yap", "update failed: ${e.message}", e)
            }
        }
    }

    /**
     * Append-to-note: transcribe the new clip and merge it into the existing note (server-side
     * integration prompt). The audio is NOT spliced — the clip stays its own recording, and the
     * detail player pages through primary + appended clips with swipe dots.
     */
    private suspend fun appendRecording(
        target: Session,
        clipPath: String,
        clipDurationMs: Long,
        clipBookmarks: List<Long>,
    ) {
        runCatching { repository.upsert(target.copy(status = "TRANSCRIBING")) }
        try {
            val clip = File(clipPath)
            val r = TranscriptionClient.appendTranscribe(
                clip, clipDurationMs, target.title, target.draftText, target.formattedText ?: ""
            )

            // Cloud copy is best-effort — the clip stays playable locally either way.
            val clipStoragePath = runCatching { TranscriptionClient.uploadAudioTo(clip, null) }
                .onFailure { Log.w("Yap", "clip upload failed (kept local-only): ${it.message}") }
                .getOrNull()
            val extra = com.mehdiacho.yap.data.ExtraRecording(
                audioPath = clip.absolutePath,
                storagePath = clipStoragePath,
                durationMs = clipDurationMs,
                bookmarks = clipBookmarks,
            )

            val title = r.name.ifBlank { target.title }
            repository.upsert(
                target.copy(
                    title = title,
                    draftText = if (r.rawText.isBlank()) target.draftText
                        else target.draftText + "\n\n" + r.rawText,
                    formattedText = r.formattedText.ifBlank { target.formattedText },
                    extraRecordings = target.extraRecordings + extra,
                    status = "DONE",
                    statusReason = null,
                    statusMessage = null,
                )
            )
            com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), target.id, title)
        } catch (e: Exception) {
            Log.e("Yap", "append failed: ${e.message}", e)
            runCatching { repository.upsert(target) } // restore the note to its prior state
            // NEVER drop the audio. An append can fail for reasons the user can't predict
            // (clip over the inline-payload ceiling, network, quota); silently restoring the
            // note used to strand a complete recording on disk with nothing pointing at it.
            // Saving it as its own note keeps the work and is trivially merged by hand.
            _notice.value = e.message?.takeIf { it.isNotBlank() }
                ?.let { "$it Saved as a separate note instead." }
                ?: "Couldn't add to “${target.title}”. Saved as a separate note instead."
            runCatching {
                startTranscription(clipPath, clipDurationMs, "audio/wav", clipBookmarks)
            }.onFailure { fallback ->
                Log.e("Yap", "append fallback also failed: ${fallback.message}", fallback)
                _notice.value =
                    "That recording couldn't be saved to a note. " +
                        "Find it in Settings → Recover recordings."
            }
        }
    }

    /**
     * True when the call merely stopped waiting — the function keeps running server-side
     * and writes the transcript onto the note itself (see the sessionId path in
     * transcribeSession). Marking the note FAILED here is what previously discarded a
     * finished 31-minute transcription.
     */
    private fun isClientGaveUp(e: Exception): Boolean {
        val code = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code
        return code == com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
            code == com.google.firebase.functions.FirebaseFunctionsException.Code.CANCELLED
    }

    /** How one attempt at (re-)transcribing an existing note ended. */
    private enum class Attempt { DONE, QUOTA, FAILED, NO_AUDIO, STILL_RUNNING }

    /**
     * Run transcription over a note that already exists — the shared body behind "Try again"
     * on a failed note and the catch-up run for recordings that hit the monthly cap.
     *
     * The two paths must agree on every detail (which audio is used, what a second failure
     * leaves behind, whether tasks come back), so they are one function rather than two that
     * drift. Prefers the cloud copy: a failure here is usually server-side, and re-sending
     * tens of megabytes to retry would be pure waste.
     */
    private suspend fun transcribeExistingNote(session: Session): Attempt {
        val localFile = session.audioPath?.let { File(it) }?.takeIf { it.exists() }

        // Where the audio lives in the cloud. Seeded from the note, and — when the note has
        // no pointer — found by name, because upload paths are deterministic. Recordings
        // refused at the monthly cap were uploaded before they were refused, so the bytes
        // are already there; asking Storage costs one round trip and is the difference
        // between "we can transcribe this" and "that audio isn't available" on any device
        // that doesn't happen to hold the local file. Also filled in by the upload below,
        // so even a second failure leaves the note pointing at what we already store.
        var cloudPath = session.storagePath ?: session.audioPath
            ?.substringAfterLast('/')
            ?.let { runCatching { TranscriptionClient.findUploadedAudio(it) }.getOrNull() }
        if (cloudPath == null && localFile == null) return Attempt.NO_AUDIO
        // A category the user picked deliberately is passed back to the server so it wins
        // over the model's fresh guess, exactly as it does on a first transcription. The
        // default "Memo" means nobody chose, so the model is free to file it.
        val deliberateCategory = session.category.takeIf { it.isNotBlank() && it != "Memo" }

        runCatching {
            repository.upsert(session.copy(status = "TRANSCRIBING", title = "Transcribing…"))
        }
        return try {
            val r = if (cloudPath != null) {
                TranscriptionClient.transcribeExisting(
                    cloudPath, "audio/wav", session.durationMs, session.id, deliberateCategory
                )
            } else {
                TranscriptionClient.transcribe(
                    localFile!!, "audio/wav", session.durationMs,
                    sessionId = session.id,
                    category = deliberateCategory,
                    onUploaded = { cloudPath = it },
                )
            }
            // "Monthly limit reached" was a status, not a name — never keep it as the title.
            val priorTitle = session.title
                .takeIf { it.isNotBlank() && it != "Monthly limit reached" && it != "Transcribing…" }
            val title = r.name.ifBlank { priorTitle ?: "Recording" }
            repository.upsert(
                session.copy(
                    title = title,
                    draftText = r.rawText,
                    formattedText = r.formattedText,
                    category = deliberateCategory ?: r.category.ifBlank { session.category },
                    topicCluster = r.topicCluster.ifBlank { session.topicCluster },
                    storagePath = r.storagePath ?: cloudPath,
                    // Same shape as a first-time transcription. A note rescued here must not
                    // come back missing its tasks and stale by one enrichment version — that
                    // is exactly the "notes that predate a feature" problem the backfill exists
                    // to avoid, and it would be self-inflicted.
                    actionItems = mergeTasks(session.actionItems, r.actionItems, session.id),
                    enrichVersion = CURRENT_ENRICH_VERSION,
                    status = "DONE",
                    statusReason = null,
                    statusMessage = null,
                )
            )
            com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), session.id, title)
            Attempt.DONE
        } catch (e: Exception) {
            Log.e("Yap", "transcribe failed for ${session.id}: ${e.message}", e)
            if (isClientGaveUp(e)) return Attempt.STILL_RUNNING
            val quotaHit = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
            // Restore a settled state rather than leaving the note spinning forever.
            runCatching {
                repository.upsert(
                    session.copy(
                        storagePath = cloudPath,
                        title = if (quotaHit) "Monthly limit reached" else session.title,
                        status = if (quotaHit) "QUOTA" else "FAILED",
                        statusMessage = if (quotaHit) null else e.message?.takeIf { it.isNotBlank() },
                    )
                )
            }
            if (quotaHit) Attempt.QUOTA else Attempt.FAILED
        }
    }

    /**
     * "Try again" on a note whose transcription failed, and "Transcribe now" on one that hit
     * the monthly cap.
     *
     * A FAILED note used to be a dead end: the audio was intact but there was no way to
     * re-run it, so a server-side hiccup permanently cost you the note's text.
     */
    fun retryTranscription(session: Session) {
        viewModelScope.launch {
            when (transcribeExistingNote(session)) {
                Attempt.NO_AUDIO ->
                    _notice.value = "That recording's audio isn't available on this device."
                Attempt.STILL_RUNNING ->
                    _notice.value = "Still transcribing — this one's long. It'll appear shortly."
                Attempt.QUOTA -> _notice.value =
                    "Still over the monthly limit. Your recording is safe and stays in the queue."
                Attempt.FAILED -> _notice.value = "Still couldn't transcribe that."
                Attempt.DONE -> Unit // the note itself is the feedback
            }
        }
    }

    // ------------------------------------------------------- waiting on quota

    /** Progress of the catch-up run: null when idle, else done-count to total. */
    private val _pendingTranscribeProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val pendingTranscribeProgress: StateFlow<Pair<Int, Int>?> =
        _pendingTranscribeProgress.asStateFlow()

    /** Notes whose recording is intact but was never transcribed: the cap was already spent. */
    private fun pendingNotes(): List<Session> =
        allSessions.value
            .filter { it.status == "QUOTA" && it.trashedAt == null }
            .sortedBy { it.createdAt } // oldest first: it has been waiting longest

    /**
     * Transcribe every recording that was turned away at the monthly cap.
     *
     * Strictly sequential. Running these in parallel would race the same usage counter, so
     * a cap with room for two more recordings would start five and refund three — and each
     * one is a 1 GiB function invocation holding an entire recording in memory.
     *
     * Stops at the first note that is turned away again: past that point every further call
     * is a round trip that can only fail, and the notes it would burn are the ones the user
     * would most want done once there IS room.
     */
    fun transcribePending() {
        if (_pendingTranscribeProgress.value != null) return // already running
        viewModelScope.launch {
            val queue = pendingNotes()
            if (queue.isEmpty()) {
                _notice.value = "No recordings are waiting to be transcribed."
                return@launch
            }
            _pendingTranscribeProgress.value = 0 to queue.size
            var done = 0
            var stoppedOnQuota = false
            var failed = 0
            try {
                for ((index, note) in queue.withIndex()) {
                    _pendingTranscribeProgress.value = index to queue.size
                    when (transcribeExistingNote(note)) {
                        Attempt.DONE, Attempt.STILL_RUNNING -> done++
                        Attempt.QUOTA -> { stoppedOnQuota = true; break }
                        // A note with no audio left, or one the provider refused, must not
                        // stall the rest of the queue behind it.
                        Attempt.FAILED, Attempt.NO_AUDIO -> failed++
                    }
                }
                val remaining = queue.size - done - failed
                _notice.value = when {
                    stoppedOnQuota && done > 0 ->
                        "Transcribed $done. $remaining still over the monthly limit."
                    stoppedOnQuota -> "Still over the monthly limit — nothing was transcribed."
                    done > 0 && failed > 0 ->
                        "Transcribed $done. $failed couldn't be recovered."
                    done > 0 -> "Transcribed $done waiting recording${if (done == 1) "" else "s"}."
                    else -> "Couldn't transcribe those recordings."
                }
            } catch (e: Exception) {
                Log.e("Yap", "pending transcription run failed: ${e.message}", e)
                _notice.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Couldn't finish. Anything already transcribed is saved."
            } finally {
                _pendingTranscribeProgress.value = null
            }
        }
    }

    /** Only auto-resume once per app launch, however many times the Library recomposes. */
    private var autoResumeAttempted = false

    /**
     * Pick up waiting recordings on their own once there's room for them.
     *
     * Unlike the task backfill this does NOT ask first, and the difference is intent: these
     * are recordings the user already asked to transcribe and was refused. Making them
     * hunt for a button to re-issue a request they already made — after paying to upgrade,
     * no less — is the app failing to keep a promise its own error message made.
     *
     * Gated on real headroom read from the server, so a still-capped account doesn't spend
     * a launch firing calls that can only come back refused.
     */
    fun resumePendingTranscriptions() {
        if (autoResumeAttempted || _pendingTranscribeProgress.value != null) return
        autoResumeAttempted = true
        viewModelScope.launch {
            if (pendingNotes().isEmpty()) return@launch
            val status = runCatching { com.mehdiacho.yap.data.AccountClient.status() }
                .onFailure { Log.w("Yap", "account status unavailable: ${it.message}") }
                .getOrNull() ?: return@launch
            // On their own key the plan cap doesn't apply at all.
            val hasHeadroom = status.byokActive || status.secondsUsed < status.secondsCap
            if (!hasHeadroom) return@launch
            transcribePending()
        }
    }

    /** "Polish now" — cloud-formalize a draft-only note's existing text (no audio round-trip). */
    fun polishSession(session: Session) {
        viewModelScope.launch {
            runCatching { repository.upsert(session.copy(status = "TRANSCRIBING")) }
            try {
                val r = TranscriptionClient.refineText(session.draftText)
                val title = r.name.ifBlank { session.title }
                repository.upsert(
                    session.copy(
                        title = title,
                        formattedText = r.formattedText,
                        category = r.category.ifBlank { session.category },
                        topicCluster = r.topicCluster.ifBlank { session.topicCluster },
                        status = "DONE",
                        statusReason = null,
                        statusMessage = null,
                    )
                )
                com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), session.id, title)
            } catch (e: Exception) {
                Log.e("Yap", "polish failed: ${e.message}", e)
                runCatching { repository.upsert(session) } // restore prior state
            }
        }
    }

    /** Pin/unpin notes — pinned notes sort first in the Library. */
    fun setPinned(ids: Collection<String>, pinned: Boolean) {
        viewModelScope.launch {
            ids.forEach { id ->
                try { repository.setPinned(id, pinned) } catch (e: Exception) {
                    Log.e("Yap", "pin failed for $id: ${e.message}", e)
                }
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try { repository.delete(id) } catch (e: Exception) {
                Log.e("Yap", "delete failed: ${e.message}", e)
            }
        }
    }

    /** Delete the note everywhere: Firestore doc, local audio, cached download, cloud object. */
    fun deleteSessionCompletely(session: Session) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.delete(session.id) } catch (e: Exception) {
                Log.e("Yap", "delete doc failed: ${e.message}", e)
                return@launch
            }
            val locals = listOfNotNull(session.audioPath) + session.extraRecordings.mapNotNull { it.audioPath }
            val remotes = listOfNotNull(session.storagePath) + session.extraRecordings.mapNotNull { it.storagePath }
            locals.forEach { runCatching { File(it).delete() } }
            remotes.forEach { path ->
                runCatching { com.mehdiacho.yap.data.AudioCache.fileFor(getApplication(), path).delete() }
                runCatching {
                    com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(path).delete()
                }
            }
        }
    }
}
