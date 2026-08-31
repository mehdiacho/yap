package com.mehdiacho.yap.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderStatus { IDLE, RECORDING, PAUSED }

/** Live recorder state, published by [RecordingService] and observed by the UI. */
data class RecorderUiState(
  val status: RecorderStatus = RecorderStatus.IDLE,
  val elapsedMs: Long = 0L,
  val amplitude: Float = 0f, // smoothed 0..1, drives the waveform
  val silenced: Boolean = false, // another app took the mic (auto-paused; audio so far is safe)
)

sealed interface RecorderEvent {
  data class Finished(
    val filePath: String,
    val durationMs: Long,
    val bookmarks: List<Long> = emptyList(), // elapsed-ms markers dropped while recording
  ) : RecorderEvent
  data class Failed(val reason: String) : RecorderEvent
  data object Discarded : RecorderEvent
}

/** Process-wide bridge between the recording service and the UI/ViewModel. */
object RecordingBus {
  private val _state = MutableStateFlow(RecorderUiState())
  val state: StateFlow<RecorderUiState> = _state.asStateFlow()

  private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

  /**
   * Recordings already handed to a transcriber, newest last.
   *
   * This is a SharedFlow with no idea how many collectors it has, and for a while it had
   * two: the notification and the floating mic could each leave an extra MainActivity
   * alive, and every ViewModel behind one collected this bus. A single recording was then
   * transcribed twice under two different note ids — two uploads, two Gemini runs, two
   * notes with almost the same title, and double the bill for one piece of audio.
   *
   * The launch flags that allowed the second activity are fixed, but the guard stays here
   * rather than in the ViewModel: this object is the only thing in the process that all
   * consumers share, so it is the only place a claim can be honoured no matter how many
   * of them there turn out to be. Transcription costs real money, and "we believe there
   * is exactly one listener" is not something worth betting the bill on.
   */
  private val claimed = ArrayDeque<String>()

  internal fun update(s: RecorderUiState) { _state.value = s }
  internal fun current(): RecorderUiState = _state.value
  internal fun emit(e: RecorderEvent) { _events.tryEmit(e) }

  /**
   * Take ownership of a finished recording. True for the first caller, false for everyone
   * after — so a duplicate delivery becomes a no-op instead of a second transcription.
   *
   * Bounded: only the last few paths are remembered. Names carry a millisecond timestamp
   * and a duplicate arrives within seconds, so anything older cannot collide, and an
   * unbounded set on a process-wide singleton would just leak for the life of the app.
   */
  fun claimRecording(filePath: String): Boolean = synchronized(claimed) {
    if (claimed.contains(filePath)) return false
    claimed.addLast(filePath)
    while (claimed.size > 16) claimed.removeFirst()
    true
  }
}

/** Fire-and-forget controls for the recording service. */
object RecordingController {
  fun start(context: Context) = send(context, RecordingService.ACTION_START, foreground = true)
  fun pause(context: Context) = send(context, RecordingService.ACTION_PAUSE)
  fun resume(context: Context) = send(context, RecordingService.ACTION_RESUME)
  fun stop(context: Context) = send(context, RecordingService.ACTION_STOP)
  fun discard(context: Context) = send(context, RecordingService.ACTION_DISCARD)
  fun bookmark(context: Context) = send(context, RecordingService.ACTION_BOOKMARK)

  private fun send(context: Context, action: String, foreground: Boolean = false) {
    val intent = Intent(context, RecordingService::class.java).setAction(action)
    if (foreground) ContextCompat.startForegroundService(context, intent)
    else context.startService(intent)
  }
}
