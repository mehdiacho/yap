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

/**
 * How long "Add a recording" waits for the note's own transcription to land before merging
 * into it anyway. Covers an ordinary recording's server run; recordings finished behind it
 * queue for that long too, so it is not open-ended.
 */
private const val SETTLE_WAIT_MS = 3 * 60_000L

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
            _notice.value = "Recovering “${orphan.file.name}”. Transcribing now."
            try {
                startTranscription(
                    orphan.path, orphan.durationMs, "audio/wav",
                    source = com.mehdiacho.yap.data.SessionSource.RECOVERED,
                )
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
                        // One recording, one transcription — however many collectors this
                        // bus turns out to have. See RecordingBus.claimRecording: a second
                        // MainActivity used to mean a second ViewModel here, and the same
                        // audio was billed twice under two different notes.
                        if (!RecordingBus.claimRecording(event.filePath)) {
                            Log.w("Yap", "duplicate Finished ignored for ${event.filePath}")
                            return@collect
                        }
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

    /**
     * Import an audio/video file shared into Yap from another app, then transcribe it.
     *
     * [sourceApp] and [sourceRef] are what the share itself told us — the app that sent it and
     * the link that came with it. Recorded now because nothing later can recover them: once
     * the bytes are copied into our own storage, a clip from TikTok and a clip from the camera
     * roll are indistinguishable.
     */
    fun importSharedAudio(
        uri: Uri,
        mimeType: String,
        sourceApp: String? = null,
        sourceRef: String? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val isVideo = com.mehdiacho.yap.audio.MediaExtraction.isVideo(mimeType)
                val ext = mimeType.substringAfter('/', "bin").take(5)
                // Videos are kept apart from recordings. The orphan-recovery scan walks
                // recordings/ looking for audio no note claims, and a video sitting there
                // is claimed by `videoPath` rather than `audioPath` — so it would be read
                // as stranded and the user invited to "recover" a note that is perfectly
                // intact.
                val dir = File(ctx.filesDir, if (isVideo) "videos" else "recordings").apply { mkdirs() }
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

                // A shared video is two things at once: a soundtrack to transcribe and a
                // picture to watch afterwards. Split them — upload the audio track alone
                // (a fraction of the bytes), keep the video for playback, and ask for word
                // timings so the transcript can be laid over it as captions.
                if (isVideo) {
                    val extracted = com.mehdiacho.yap.audio.MediaExtraction.extractAudio(
                        ctx, Uri.fromFile(file), File(ctx.filesDir, "recordings")
                    )
                    if (extracted != null) {
                        startTranscription(
                            extracted.file.absolutePath,
                            extracted.durationMs.takeIf { it > 0 } ?: durationMs,
                            extracted.mimeType,
                            videoPath = file.absolutePath,
                            wantWordTimings = true,
                            source = com.mehdiacho.yap.data.SessionSource.SHARED_VIDEO,
                            sourceApp = sourceApp,
                            sourceRef = sourceRef,
                        )
                    } else {
                        // Couldn't split it — send the video as-is, which still transcribes.
                        // No timings: they need an audio-only source.
                        startTranscription(
                            file.absolutePath, durationMs, mimeType,
                            videoPath = file.absolutePath,
                            source = com.mehdiacho.yap.data.SessionSource.SHARED_VIDEO,
                            sourceApp = sourceApp,
                            sourceRef = sourceRef,
                        )
                    }
                } else {
                    startTranscription(
                        file.absolutePath, durationMs, mimeType,
                        source = com.mehdiacho.yap.data.SessionSource.SHARED_AUDIO,
                        sourceApp = sourceApp,
                        sourceRef = sourceRef,
                    )
                }
            } catch (e: Exception) {
                Log.e("YapShare", "import failed: ${e.message}", e)
            }
        }
    }

    /** Import shared plain text as a note (no transcription). */
    fun importSharedText(text: String, sourceApp: String? = null) {
        viewModelScope.launch {
            val session = Session(
                id = UUID.randomUUID().toString(),
                title = "Shared note",
                draftText = text,
                formattedText = text,
                status = "DONE",
                source = com.mehdiacho.yap.data.SessionSource.SHARED_TEXT,
                sourceApp = sourceApp,
                // The text IS the reference here — a shared link arrives as the body of the
                // note, so duplicating it into sourceRef would say nothing new.
                sourceRef = null
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
        /** The video this note was made from, when it arrived through the share sheet. */
        videoPath: String? = null,
        /** Ask for word timings so the video can be captioned. See MediaExtraction.kt. */
        wantWordTimings: Boolean = false,
        /** Where this audio came from. One of [com.mehdiacho.yap.data.SessionSource]. */
        source: String = com.mehdiacho.yap.data.SessionSource.RECORDING,
        /** Display name of the app it was shared from, when it was shared. */
        sourceApp: String? = null,
        /** The link or hint that travelled with the share. */
        sourceRef: String? = null,
    ) {
        // Live draft from the fed-stream recognizer (recordings only; empty for imports).
        val isOwnRecording = mimeType == "audio/wav"
        var session = Session(
            id = UUID.randomUUID().toString(),
            title = "Transcribing…",
            draftText = if (isOwnRecording) com.mehdiacho.yap.audio.LiveDraftEngine.currentDraft() else "",
            audioPath = audioPath,
            videoPath = videoPath,
            durationMs = durationMs,
            status = "TRANSCRIBING",
            bookmarks = bookmarks,
            // Applied straight away so the note is filed correctly while it transcribes,
            // not only once the server answers.
            category = _pendingCategory.value ?: "Memo",
            source = source,
            sourceApp = sourceApp,
            sourceRef = sourceRef,
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
                wantWordTimings = wantWordTimings,
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
                    wordTimings = r.wordTimings,
                    actionItems = mergeTasks(emptyList(), r.actionItems, session.id),
                    enrichVersion = CURRENT_ENRICH_VERSION,
                    status = "DONE",
                    statusReason = null,
                    statusMessage = null,
                )
            )
            // Heads-up "Your note is ready" — skipped while the app is on screen.
            com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), session.id, title)
        } catch (e: Exception) {
            Log.e("Yap", "transcription failed for $audioPath ($mimeType): ${e.message}", e)
            if (isClientGaveUp(e)) {
                // Leave it TRANSCRIBING: the server finishes and writes the note itself,
                // and the Firestore listener will surface it.
                _notice.value = "This one's long, so it's still transcribing. It'll appear shortly."
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
        } finally {
            // Deliberately in `finally`: the video is worth keeping however the transcription
            // turned out. A note that failed can be retried, and one whose client gave up
            // early (that path returns straight out of the catch) is still being finished by
            // the server — in both cases the video is the half we'd have no way to recover.
            uploadVideoIfAny(session.id, videoPath)
        }
    }

    /**
     * Put the note's video in Cloud Storage so it survives a reinstall and plays on other
     * devices.
     *
     * Runs after transcription rather than alongside it. The audio upload gates the whole
     * pipeline — nothing happens until it lands — but nobody is waiting on the video, and
     * pushing tens of megabytes up a phone connection while the model is working would slow
     * the thing the user IS waiting for. Failure is silent by design: the local copy still
     * plays, and a note is not worth failing over an upload nobody asked for yet.
     */
    private suspend fun uploadVideoIfAny(sessionId: String, videoPath: String?) {
        val file = videoPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 } ?: return
        runCatching {
            val path = TranscriptionClient.uploadVideo(file)
            repository.setVideoStoragePath(sessionId, path)
            Log.d("YapShare", "video uploaded to $path")
        }.onFailure { Log.w("YapShare", "video upload failed (non-fatal): ${it.message}") }
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
        tapped: Session,
        clipPath: String,
        clipDurationMs: Long,
        clipBookmarks: List<Long>,
    ) {
        // `tapped` is the note as the screen showed it when "Add a recording" was pressed —
        // possibly minutes ago, and possibly while that note was still transcribing. Merging
        // into that copy and writing it back is how a finished transcript (title, text,
        // tasks, cloud pointer) was replaced by the live draft on 2026-09-23. Work from the
        // note as it stands now, and wait for its own transcription to land first.
        val target = settled(tapped.id) ?: tapped
        val priorStatus = target.status
        runCatching { repository.setStatus(target.id, "TRANSCRIBING") }
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

            // Re-read once more: the append call can take a minute, and a task ticked or a
            // title edited in that time belongs in the result too.
            val now = latest(target.id) ?: target
            val title = r.name.takeIf { it.isNotBlank() && it != "Transcribing…" } ?: now.title
            repository.upsert(
                now.copy(
                    title = title,
                    draftText = if (r.rawText.isBlank()) target.draftText
                        else target.draftText + "\n\n" + r.rawText,
                    formattedText = r.formattedText.ifBlank { target.formattedText },
                    extraRecordings = now.extraRecordings + extra,
                    status = "DONE",
                    statusReason = null,
                    statusMessage = null,
                )
            )
            com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), target.id, title)
        } catch (e: Exception) {
            Log.e("Yap", "append failed: ${e.message}", e)
            // Put the status back and nothing else — the rest of the note was never touched.
            runCatching { repository.setStatus(target.id, priorStatus) }
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

    /** The note as it stands in the database now, not as some screen last saw it. */
    private suspend fun latest(id: String): Session? =
        runCatching { repository.sessionById(id).first() }.getOrNull()

    /**
     * The note once it is no longer transcribing, or as it stands after [SETTLE_WAIT_MS].
     *
     * A note whose client gave up waiting is still being finished by the server, which writes
     * the result onto it directly. Anything that merges into that note has to wait for it.
     */
    private suspend fun settled(id: String): Session? =
        kotlinx.coroutines.withTimeoutOrNull(SETTLE_WAIT_MS) {
            runCatching {
                repository.sessionById(id).first { it == null || it.status != "TRANSCRIBING" }
            }.getOrNull()
        } ?: latest(id)

    /** Notes being retranscribed right now, so a second tap doesn't start a second run. */
    private val retranscribing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * "Retranscribe" from a note's menu: run the note's audio through transcription again and
     * replace its text, whatever state it is in.
     *
     * "Try again" only appears on a note that failed. A note can also come out wrong without
     * failing — cut short, overwritten, or transcribed before a model or prompt improved — and
     * then there was no way to re-run it at all. The primary recording is transcribed first;
     * clips added with "Add a recording" are then merged back in, in order, the same way they
     * were the first time.
     */
    fun retranscribe(session: Session) {
        if (!retranscribing.add(session.id)) {
            _notice.value = "Already retranscribing this note."
            return
        }
        viewModelScope.launch {
            try {
                val before = latest(session.id) ?: session
                when (transcribeExistingNote(before)) {
                    Attempt.DONE -> {
                        val missed = reappendExtras(session.id, before.extraRecordings)
                        _notice.value = if (missed == 0) "Retranscribed."
                        else "Retranscribed, but $missed added recording(s) couldn't be included."
                    }
                    Attempt.STILL_RUNNING -> {
                        _notice.value = "Still transcribing — this one's long. It'll appear shortly."
                        // The server finishes the primary recording on its own and writes only
                        // that recording's text. Added clips have to be merged back after it
                        // lands, or their words silently drop out of the note.
                        if (before.extraRecordings.isNotEmpty()) {
                            val landed = settled(session.id)
                            if (landed?.status == "DONE") {
                                val missed = reappendExtras(session.id, before.extraRecordings)
                                _notice.value = if (missed == 0) "Retranscribed."
                                else "Retranscribed, but $missed added recording(s) couldn't be included."
                            } else {
                                _notice.value = "The added recordings weren't retranscribed. Try again once the note has finished."
                            }
                        }
                    }
                    Attempt.NO_AUDIO -> _notice.value = "That note's recording isn't available."
                    Attempt.QUOTA, Attempt.FAILED -> {
                        // The note had text before this run; a failed re-run shouldn't turn it
                        // into a failed note. Put its status and title back.
                        latest(session.id)?.let { now ->
                            runCatching {
                                repository.upsert(
                                    now.copy(
                                        title = before.title,
                                        status = before.status,
                                        statusReason = before.statusReason,
                                        statusMessage = before.statusMessage,
                                    )
                                )
                            }
                        }
                        _notice.value = "Couldn't retranscribe that. The note is unchanged."
                    }
                }
            } finally {
                retranscribing.remove(session.id)
            }
        }
    }

    /** Merge each added clip back into a freshly retranscribed note. Returns how many failed. */
    private suspend fun reappendExtras(
        id: String,
        extras: List<com.mehdiacho.yap.data.ExtraRecording>,
    ): Int {
        var missed = 0
        for (extra in extras) {
            val file = extra.audioPath?.let { File(it) }?.takeIf { it.exists() }
                ?: extra.storagePath?.let { path ->
                    runCatching {
                        com.mehdiacho.yap.data.AudioCache.download(getApplication(), path)
                    }.getOrNull()
                }
            val note = latest(id)
            if (file == null || note == null) { missed++; continue }
            runCatching {
                val r = TranscriptionClient.appendTranscribe(
                    file, extra.durationMs, note.title, note.draftText, note.formattedText ?: ""
                )
                val now = latest(id) ?: note
                repository.upsert(
                    now.copy(
                        title = r.name.ifBlank { now.title },
                        draftText = if (r.rawText.isBlank()) note.draftText
                            else note.draftText + "\n\n" + r.rawText,
                        formattedText = r.formattedText.ifBlank { note.formattedText },
                    )
                )
            }.onFailure {
                Log.e("Yap", "re-append of ${extra.audioPath ?: extra.storagePath} failed: ${it.message}", it)
                missed++
            }
        }
        return missed
    }

    /** "Download recording": copy the note's audio to Downloads/Yap on the phone. */
    fun downloadRecording(session: Session) {
        viewModelScope.launch(Dispatchers.IO) {
            _notice.value = "Saving the recording…"
            val result = runCatching {
                com.mehdiacho.yap.data.RecordingSaver.save(getApplication(), latest(session.id) ?: session)
            }
            _notice.value = result.fold(
                onSuccess = { o ->
                    when {
                        o.saved == 0 -> "That note's recording isn't available to save."
                        o.missing > 0 -> "Saved ${o.saved} recording(s) to ${o.folder}. ${o.missing} couldn't be found."
                        o.saved == 1 -> "Saved to ${o.folder}."
                        else -> "Saved ${o.saved} recordings to ${o.folder}."
                    }
                },
                onFailure = {
                    Log.e("Yap", "download recording failed: ${it.message}", it)
                    "Couldn't save the recording: ${it.message ?: "unknown error"}"
                },
            )
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
                    stoppedOnQuota -> "Still over the monthly limit, so nothing was transcribed."
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
        viewModelScope.launch { polish(session, notify = true) }
    }

    /**
     * Run one note through the formalize pass. True if it came back formatted.
     *
     * [notify] is off for bulk runs: a notification per note turns a five-note Formalize into
     * five pings for something the user is already watching happen on screen.
     */
    private suspend fun polish(session: Session, notify: Boolean): Boolean {
        runCatching { repository.upsert(session.copy(status = "TRANSCRIBING")) }
        return try {
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
            if (notify) com.mehdiacho.yap.ui.Notifier.refineDone(getApplication(), session.id, title)
            true
        } catch (e: Exception) {
            Log.e("Yap", "polish failed: ${e.message}", e)
            runCatching { repository.upsert(session) } // restore prior state
            false
        }
    }

    // ------------------------------------------------------- multi-select bulk actions

    /** Progress of a bulk Formalize: null when idle, else done-count to total. */
    private val _formalizeProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val formalizeProgress: StateFlow<Pair<Int, Int>?> = _formalizeProgress.asStateFlow()

    /** True while a Group merge is in flight. One call, so there is no per-note count. */
    private val _grouping = MutableStateFlow(false)
    val grouping: StateFlow<Boolean> = _grouping.asStateFlow()

    /**
     * The note a Group just produced, for the Library to open. Consumed like [pendingOpen].
     *
     * Unlike post-capture auto-open — disabled because it hijacked whatever screen you were
     * on — this navigation is the direct result of the user tapping Group and waiting for it,
     * so landing on the merged note is the answer to what they asked, not an interruption.
     */
    private val _groupedNote = MutableStateFlow<String?>(null)
    val groupedNote: StateFlow<String?> = _groupedNote.asStateFlow()
    fun consumeGroupedNote() { _groupedNote.value = null }

    /**
     * Formalize several notes at once — the Library's multi-select "Formalize".
     *
     * Strictly sequential. Firing N calls at once would spike the provider's rate limit on
     * the exact action a user is most likely to run over a big selection, and the failure
     * would land on an arbitrary subset with no way to tell which.
     *
     * Notes with no text are skipped rather than sent: there is nothing to formalize, and the
     * server rejects an empty draft anyway. Callers decide whether already-formatted notes
     * belong in [sessions] — re-running one is legitimate (it is how you redo a bad pass) and
     * this is not the layer that should be guessing about that.
     */
    fun formalizeSessions(sessions: List<Session>, skipped: Int = 0) {
        if (_formalizeProgress.value != null) return // already running
        val queue = sessions.filter { it.draftText.isNotBlank() }.sortedBy { it.createdAt }
        if (queue.isEmpty()) {
            _notice.value = "Those notes have no text to formalize."
            return
        }
        // Claim the run here, not inside the coroutine. The guard above and the button's
        // disabled state both key off this value, and neither can help during the frames
        // between launching and the body running — long enough for a second tap to start a
        // duplicate run over the same notes.
        _formalizeProgress.value = 0 to queue.size
        // The caller may have filtered out notes that were already formalized. Say so —
        // "Formalized 2" after selecting 5 otherwise reads as three silent failures.
        val alsoSkipped = if (skipped > 0) " $skipped w${if (skipped == 1) "as" else "ere"} already done." else ""
        viewModelScope.launch {
            var done = 0
            try {
                for ((index, note) in queue.withIndex()) {
                    _formalizeProgress.value = index to queue.size
                    if (polish(note, notify = false)) done++
                }
                val failed = queue.size - done
                _notice.value = when {
                    done > 0 && failed > 0 -> "Formalized $done. $failed didn't come back.$alsoSkipped"
                    done > 0 -> "Formalized $done note${if (done == 1) "" else "s"}.$alsoSkipped"
                    else -> "Couldn't formalize those notes."
                }
            } catch (e: Exception) {
                Log.e("Yap", "bulk formalize failed: ${e.message}", e)
                _notice.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Couldn't finish. Anything already formalized is saved."
            } finally {
                _formalizeProgress.value = null
            }
        }
    }

    /**
     * Merge several notes into one new note — the Library's multi-select "Group".
     *
     * The sources are left exactly as they were. A merge that consumed its inputs would be
     * the one action here a user could not undo by hand, and "group" does not read like a
     * word that deletes things. If they want the originals gone they can select and trash
     * them, which is reversible for 30 days.
     */
    fun groupSessions(sessions: List<Session>) {
        if (_grouping.value) return
        val usable = sessions.filter { it.draftText.isNotBlank() || !it.formattedText.isNullOrBlank() }
        if (usable.size < 2) {
            _notice.value = "Pick at least two notes with something in them."
            return
        }
        // Claimed synchronously — see formalizeSessions. It matters more here: a duplicate
        // run doesn't just waste a call, it creates a second merged note the user never
        // asked for and has to go and delete.
        _grouping.value = true
        viewModelScope.launch {
            try {
                val r = TranscriptionClient.groupSessions(usable)
                val merged = Session(
                    id = UUID.randomUUID().toString(),
                    title = r.name.ifBlank { "Merged note" },
                    draftText = r.rawText,
                    formattedText = r.formattedText,
                    status = "DONE",
                    category = r.category.ifBlank { "Memo" },
                    topicCluster = r.topicCluster.ifBlank { "General" },
                    source = com.mehdiacho.yap.data.SessionSource.MERGED,
                    // The trail back to what this was built from. Ids, not titles: titles get
                    // edited and a merged note should still be able to say where it came from.
                    sourceRef = usable.joinToString(",") { it.id },
                )
                repository.upsert(merged)
                _groupedNote.value = merged.id
            } catch (e: Exception) {
                Log.e("Yap", "group failed: ${e.message}", e)
                _notice.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Couldn't merge those notes. Nothing was changed."
            } finally {
                _grouping.value = false
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
