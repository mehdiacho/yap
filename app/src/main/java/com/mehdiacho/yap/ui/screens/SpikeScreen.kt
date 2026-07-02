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

/** Fixed reading script = ground truth for scoring the recognizer. ~30s at normal pace. */
private const val SCRIPT =
    "Yap is a voice first app for capturing ideas the moment they strike. " +
    "On Thursday the fifth of March I recorded thirty seven notes between Gaborone and Johannesburg. " +
    "The quick brown fox jumps over the lazy dog while measuring temperatures near zero degrees. " +
    "Please transcribe brand names like Firebase, Xiaomi, and Kotlin correctly. " +
    "If this sentence survives with ninety five percent accuracy, the on device engine is probably good enough for a first draft."

/** Normalize for fair comparison: lowercase, %->percent, digits/ordinals -> words, strip punctuation. */
private fun normalizeTokens(text: String): List<String> {
    val cleaned = text.lowercase()
        .replace("%", " percent ")
        .replace(Regex("(\\d+)(st|nd|rd|th)\\b"), "$1")
        .replace(Regex("[^a-z0-9\\s]"), " ")
    return cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.flatMap { tok ->
        if (tok.all { it.isDigit() }) numberToWords(tok.toIntOrNull() ?: 0) else listOf(tok)
    }
}

private fun numberToWords(n: Int): List<String> {
    if (n > 9999) return listOf(n.toString())
    val ones = listOf("zero","one","two","three","four","five","six","seven","eight","nine","ten",
        "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen")
    val tens = listOf("","","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety")
    val out = mutableListOf<String>()
    var v = n
    if (v >= 1000) { out += ones[v / 1000]; out += "thousand"; v %= 1000 }
    if (v >= 100) { out += ones[v / 100]; out += "hundred"; v %= 100 }
    when {
        v == 0 && out.isEmpty() -> out += "zero"
        v in 1..19 -> out += ones[v]
        v >= 20 -> { out += tens[v / 10]; if (v % 10 != 0) out += ones[v % 10] }
    }
    return out
}

/** Word error rate: Levenshtein distance over token lists / reference length. */
private fun wordErrorRate(reference: List<String>, hypothesis: List<String>): Float {
    if (reference.isEmpty()) return 1f
    val d = Array(reference.size + 1) { IntArray(hypothesis.size + 1) }
    for (i in 0..reference.size) d[i][0] = i
    for (j in 0..hypothesis.size) d[0][j] = j
    for (i in 1..reference.size) for (j in 1..hypothesis.size) {
        val cost = if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
    }
    return d[reference.size][hypothesis.size].toFloat() / reference.size
}

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

        Spacer(Modifier.height(14.dp))

        // The reading script — the ground truth we score against.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp)
        ) {
            Text("READ THIS ALOUD", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(6.dp))
            Text(SCRIPT, fontSize = 15.sp, lineHeight = 23.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }

        // Accuracy vs the script (computed after Stop, from final segments).
        if (!running && finals.isNotEmpty()) {
            val ref = remember(finals) { normalizeTokens(SCRIPT) }
            val hyp = remember(finals) { normalizeTokens(finals.joinToString(" ")) }
            val wer = remember(finals) { wordErrorRate(ref, hyp) }
            StatCard(
                "Accuracy vs script",
                "word error rate: %.1f%%  (accuracy ≈ %.1f%%)\nref words: %d · heard words: %d"
                    .format(wer * 100, (1f - wer).coerceAtLeast(0f) * 100, ref.size, hyp.size)
            )
        }

        Spacer(Modifier.height(4.dp))
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
