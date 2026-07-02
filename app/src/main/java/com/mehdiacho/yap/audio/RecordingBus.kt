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
)

sealed interface RecorderEvent {
  data class Finished(val filePath: String, val durationMs: Long) : RecorderEvent
  data class Failed(val reason: String) : RecorderEvent
  data object Discarded : RecorderEvent
}

/** Process-wide bridge between the recording service and the UI/ViewModel. */
object RecordingBus {
  private val _state = MutableStateFlow(RecorderUiState())
  val state: StateFlow<RecorderUiState> = _state.asStateFlow()

  private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

  internal fun update(s: RecorderUiState) { _state.value = s }
  internal fun current(): RecorderUiState = _state.value
  internal fun emit(e: RecorderEvent) { _events.tryEmit(e) }
}

/** Fire-and-forget controls for the recording service. */
object RecordingController {
  fun start(context: Context) = send(context, RecordingService.ACTION_START, foreground = true)
  fun pause(context: Context) = send(context, RecordingService.ACTION_PAUSE)
  fun resume(context: Context) = send(context, RecordingService.ACTION_RESUME)
  fun stop(context: Context) = send(context, RecordingService.ACTION_STOP)
  fun discard(context: Context) = send(context, RecordingService.ACTION_DISCARD)

  private fun send(context: Context, action: String, foreground: Boolean = false) {
    val intent = Intent(context, RecordingService::class.java).setAction(action)
    if (foreground) ContextCompat.startForegroundService(context, intent)
    else context.startService(intent)
  }
}
