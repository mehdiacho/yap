package com.mehdiacho.yap.ui

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mehdiacho.yap.audio.RecorderEvent
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.data.AppDatabase
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.data.SessionRepository
import com.mehdiacho.yap.data.TranscriptionClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: SessionRepository
    val allSessions: StateFlow<List<Session>>

    // Emits a session id to open right after a recording is captured (post-capture flow).
    private val _navigateToSession = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigateToSession: SharedFlow<String> = _navigateToSession.asSharedFlow()

    init {
        val sessionDao = AppDatabase.getDatabase(application).sessionDao()
        repository = SessionRepository(sessionDao)
        allSessions = repository.allSessions.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        // Persist a real session whenever a recording finishes.
        viewModelScope.launch {
            RecordingBus.events.collect { event ->
                if (event is RecorderEvent.Finished) {
                    saveRecording(event.filePath, event.durationMs)
                }
            }
        }
    }

    private fun saveRecording(audioPath: String, durationMs: Long) {
        viewModelScope.launch { startTranscription(audioPath, durationMs, "audio/wav") }
    }

    /** Import an audio/video file shared into Yap from another app, then transcribe it. */
    fun importSharedAudio(uri: Uri, mimeType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val ext = mimeType.substringAfter('/', "bin").take(5)
                val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
                val file = File(dir, "shared_${System.currentTimeMillis()}.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                } ?: return@launch
                val durationMs = runCatching {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(ctx, uri)
                    val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                    d
                }.getOrDefault(0L)
                startTranscription(file.absolutePath, durationMs, mimeType)
            } catch (e: Exception) {
                // best-effort; a failed import simply doesn't create a note
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
            repository.insertSession(session)
            _navigateToSession.tryEmit(session.id)
        }
    }

    private suspend fun startTranscription(audioPath: String, durationMs: Long, mimeType: String) {
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = "Transcribing…",
            draftText = "",
            audioPath = audioPath,
            durationMs = durationMs,
            status = "TRANSCRIBING"
        )
        repository.insertSession(session)
        _navigateToSession.tryEmit(session.id) // open the note immediately (shows "Transcribing…")

        try {
            val r = TranscriptionClient.transcribe(File(audioPath), mimeType)
            repository.updateSession(
                session.copy(
                    title = r.name.ifBlank { "Recording" },
                    draftText = r.rawText,
                    formattedText = r.formattedText,
                    category = r.category.ifBlank { "Memo" },
                    topicCluster = r.topicCluster.ifBlank { "General" },
                    status = "DONE"
                )
            )
        } catch (e: Exception) {
            repository.updateSession(session.copy(title = "Recording", status = "FAILED"))
        }
    }

    fun getSessionById(id: String): StateFlow<Session?> {
        return repository.getSessionById(id).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            null
        )
    }

    fun saveDraftSession(draftText: String) {
        viewModelScope.launch {
            val session = Session(
                id = UUID.randomUUID().toString(),
                title = "Idea ${System.currentTimeMillis() % 1000}",
                draftText = draftText,
                status = "OFFLINE_PENDING"
            )
            repository.insertSession(session)
            // Mocking refinement process locally
            mockCloudRefinement(session.id)
        }
    }

    private fun mockCloudRefinement(sessionId: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000) // Simulating network
            repository.getSessionById(sessionId).collect { session ->
                if (session != null && session.status == "OFFLINE_PENDING") {
                    repository.updateSession(session.copy(status = "POLISHING"))
                    kotlinx.coroutines.delay(3000)
                    repository.updateSession(
                        session.copy(
                            status = "REFINED",
                            formattedText = session.draftText + "\n\n*Refined!*"
                        )
                    )
                }
            }
        }
    }
    
    fun updateSession(session: Session) {
        viewModelScope.launch {
            repository.updateSession(session)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
        }
    }
}
