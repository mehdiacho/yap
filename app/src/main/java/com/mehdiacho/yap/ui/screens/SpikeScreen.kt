package com.mehdiacho.yap.ui.screens

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController

private const val TAG = "YapSpike"

/**
 * TEMPORARY feasibility spike for block ② (on-device transcription).
 * Runs our AudioRecord-based RecordingService and Android's SpeechRecognizer at the
 * same time to find out whether the two mic consumers can coexist on this device.
 * Outcomes we care about:
 *   A) both work  -> live draft can ride on SpeechRecognizer while we keep the WAV
 *   B) recorder silenced -> unacceptable; plan B (local model fed by our own stream)
 *   C) recognizer errors -> plan B
 */
@Composable
fun SpikeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recState by RecordingBus.state.collectAsState()

    var running by remember { mutableStateOf(false) }
    var onDeviceAvailable by remember { mutableStateOf<Boolean?>(null) }
    var recognizerStatus by remember { mutableStateOf("idle") }
    var partialCount by remember { mutableIntStateOf(0) }
    var lastPartial by remember { mutableStateOf("") }
    var finals by remember { mutableStateOf(listOf<String>()) }
    var errors by remember { mutableStateOf(listOf<String>()) }
    var peakSinceStart by remember { mutableFloatStateOf(0f) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    LaunchedEffect(recState.amplitude, running) {
        if (running && recState.amplitude > peakSinceStart) peakSinceStart = recState.amplitude
    }

    fun errName(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$code"
    }

    fun startListening(r: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        r.startListening(intent)
    }

    fun createRecognizer(): SpeechRecognizer {
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        onDeviceAvailable = onDevice
        Log.i(TAG, "onDeviceRecognitionAvailable=$onDevice sdk=${Build.VERSION.SDK_INT}")
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                recognizerStatus = "listening"; Log.i(TAG, "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() { Log.i(TAG, "onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.i(TAG, "onEndOfSpeech") }
            override fun onError(error: Int) {
                val name = errName(error)
                Log.w(TAG, "onError $name")
                errors = errors + name
                recognizerStatus = "error: $name"
                // Continuous mode: restart unless stopped (NO_MATCH/timeouts are normal between utterances)
                if (running && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    error != SpeechRecognizer.ERROR_CLIENT
                ) {
                    startListening(r)
                }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.i(TAG, "onResults ${texts?.firstOrNull()}")
                texts?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { finals = finals + it }
                if (running) startListening(r) // keep going
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                texts?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    partialCount++; lastPartial = it
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    fun startSpike() {
        peakSinceStart = 0f; partialCount = 0; lastPartial = ""; finals = emptyList(); errors = emptyList()
        running = true
        RecordingController.start(context)                 // our recorder (foreground service)
        val r = createRecognizer(); recognizer = r
        startListening(r)                                  // the system recognizer, concurrently
        recognizerStatus = "starting"
        Log.i(TAG, "spike started: recorder + recognizer concurrently")
    }

    fun stopSpike() {
        running = false
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
        RecordingController.stop(context)
        recognizerStatus = "stopped"
        Log.i(TAG, "spike stopped: peakAmp=$peakSinceStart partials=$partialCount finals=${finals.size} errors=$errors")
    }

    DisposableEffect(Unit) { onDispose { if (running) stopSpike() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (running) stopSpike(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text("Live-draft spike", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Runs the recorder and the system speech recognizer at the same time to test mic coexistence. Speak normally for ~20s.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { if (running) stopSpike() else startSpike() },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (running) "Stop spike" else "Start spike") }

        Spacer(Modifier.height(18.dp))
        StatCard("Recorder (ours)", buildString {
            append("status: ${recState.status}")
            append("  ·  elapsed: ${recState.elapsedMs / 1000}s\n")
            append("live amplitude: %.2f".format(recState.amplitude))
            append("  ·  peak this run: %.2f".format(peakSinceStart))
            if (running && recState.status == RecorderStatus.RECORDING && peakSinceStart < 0.05f && recState.elapsedMs > 4000)
                append("\n⚠ recorder appears SILENCED (flat audio)")
        })
        StatCard("Recognizer (system)", buildString {
            append("on-device available: ${onDeviceAvailable ?: "?"}\n")
            append("status: $recognizerStatus\n")
            append("partials: $partialCount")
            if (lastPartial.isNotBlank()) append("\nlast partial: “$lastPartial”")
        })
        if (finals.isNotEmpty()) StatCard("Final segments (${finals.size})", finals.joinToString("\n• ", prefix = "• "))
        if (errors.isNotEmpty()) StatCard("Recognizer errors", errors.joinToString(", "))
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StatCard(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
