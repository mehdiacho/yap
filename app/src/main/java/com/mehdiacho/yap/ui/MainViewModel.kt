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

    // Session id to open next (post-capture / share import). StateFlow so a cold-start
    // share doesn't lose the event before the UI subscribes; consumed by the UI.
    private val _pendingOpen = MutableStateFlow<String?>(null)
    val pendingOpen: StateFlow<String?> = _pendingOpen.asStateFlow()
    fun consumePendingOpen() { _pendingOpen.value = null }

    init {
        // Persist a real session whenever a recording finishes.
        viewModelScope.launch {
            RecordingBus.events.collect { event ->
                if (event is RecorderEvent.Finished) {
                    startTranscription(event.filePath, event.durationMs, "audio/wav")
                }
            }
        }
        backfillAudioToStorage()
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

    private suspend fun startTranscription(audioPath: String, durationMs: Long, mimeType: String) {
        // Live draft from the fed-stream recognizer (recordings only; empty for imports).
        val isOwnRecording = mimeType == "audio/wav"
        var session = Session(
            id = UUID.randomUUID().toString(),
            title = "Transcribing…",
            draftText = if (isOwnRecording) com.mehdiacho.yap.audio.LiveDraftEngine.currentDraft() else "",
            audioPath = audioPath,
            durationMs = durationMs,
            status = "TRANSCRIBING"
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
            kotlinx.coroutines.delay(2500)
            val finalDraft = com.mehdiacho.yap.audio.LiveDraftEngine.consumeDraft()
            if (finalDraft.length > session.draftText.length) {
                session = session.copy(draftText = finalDraft)
                runCatching { repository.upsert(session) }
            }
        }

        try {
            val r = TranscriptionClient.transcribe(File(audioPath), mimeType, durationMs)
            repository.upsert(
                session.copy(
                    title = r.name.ifBlank { "Recording" },
                    draftText = r.rawText,
                    formattedText = r.formattedText,
                    category = r.category.ifBlank { "Memo" },
                    topicCluster = r.topicCluster.ifBlank { "General" },
                    storagePath = r.storagePath,
                    status = "DONE"
                )
            )
        } catch (e: Exception) {
            Log.e("Yap", "transcription failed for $audioPath ($mimeType): ${e.message}", e)
            val quotaHit = (e as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
            repository.upsert(
                if (quotaHit) session.copy(title = "Monthly limit reached", status = "QUOTA")
                else session.copy(title = "Recording", status = "FAILED")
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
            session.audioPath?.let { runCatching { File(it).delete() } }
            session.storagePath?.let { path ->
                runCatching { com.mehdiacho.yap.data.AudioCache.fileFor(getApplication(), path).delete() }
                runCatching {
                    com.google.firebase.storage.FirebaseStorage.getInstance().reference.child(path).delete()
                }
            }
        }
    }
}
