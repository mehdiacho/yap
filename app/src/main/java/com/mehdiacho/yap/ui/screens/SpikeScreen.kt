package com.mehdiacho.yap.ui.screens

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt
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
    var mode by remember { mutableStateOf("") } // "concurrent" or "fed"
    var onDeviceAvailable by remember { mutableStateOf<Boolean?>(null) }
    var recognizerStatus by remember { mutableStateOf("idle") }
    var partialCount by remember { mutableIntStateOf(0) }
    var lastPartial by remember { mutableStateOf("") }
    var finals by remember { mutableStateOf(listOf<String>()) }
    var errors by remember { mutableStateOf(listOf<String>()) }
    var peakSinceStart by remember { mutableFloatStateOf(0f) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    // Test B (fed stream) state: our own single AudioRecord fanned out to WAV + recognizer pipe.
    var fedRunning by remember { mutableStateOf(false) }
    var fedAmp by remember { mutableFloatStateOf(0f) }
    var fedBytes by remember { mutableLongStateOf(0L) }
    var fedStop by remember { mutableStateOf<(() -> Unit)?>(null) }

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
            override fun onSegmentResults(segmentResults: Bundle) {
                val texts = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.i(TAG, "onSegmentResults ${texts?.firstOrNull()}")
                texts?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { finals = finals + it }
            }
            override fun onEndOfSegmentedSession() {
                Log.i(TAG, "onEndOfSegmentedSession")
                recognizerStatus = "segmented session ended"
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    // ---- Test B: single AudioRecord fanned out to WAV file + recognizer pipe (API 33 blessed path)
    fun startFed() {
        if (Build.VERSION.SDK_INT < 33) {
            recognizerStatus = "fed mode needs API 33+"; return
        }
        peakSinceStart = 0f; partialCount = 0; lastPartial = ""; finals = emptyList(); errors = emptyList()
        fedAmp = 0f; fedBytes = 0L
        mode = "fed"; fedRunning = true; running = true

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]; val writeSide = pipe[1]

        val r = createRecognizer(); recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        r.startListening(intent)
        recognizerStatus = "fed: streaming"
        Log.i(TAG, "fed mode: startListening with EXTRA_AUDIO_SOURCE pipe")

        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        fedStop = { stopFlag.set(true) }
        thread(name = "spike-fed-capture") {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            val wav = File(File(context.filesDir, "recordings").apply { mkdirs() }, "spike_fed.wav")
            val raf = RandomAccessFile(wav, "rw"); raf.setLength(0); raf.write(ByteArray(44))
            var dataBytes = 0L
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 2560))
            } catch (e: SecurityException) {
                Log.e(TAG, "fed: mic permission"); fedRunning = false; running = false; return@thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "fed: AudioRecord init failed"); rec.release(); fedRunning = false; running = false; return@thread
            }
            val buf = ByteArray(640)
            rec.startRecording()
            try {
                while (!stopFlag.get()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    raf.write(buf, 0, n); dataBytes += n
                    runCatching { out.write(buf, 0, n) }.onFailure {
                        Log.w(TAG, "fed: pipe write failed (recognizer closed?): ${it.message}")
                    }
                    fedBytes = dataBytes
                    // quick RMS->0..1
                    var sum = 0.0; var c = 0; var i = 0
                    while (i + 1 < n) { val s = (buf[i].toInt() and 0xff) or (buf[i+1].toInt() shl 8); sum += (s*s).toDouble(); c++; i += 2 }
                    if (c > 0) {
                        val db = 20.0 * log10(sqrt(sum / c) / 32768.0 + 1e-7)
                        val a = ((db + 50) / 50.0).toFloat().coerceIn(0f, 1f)
                        fedAmp = a; if (a > peakSinceStart) peakSinceStart = a
                    }
                }
            } finally {
                rec.stop(); rec.release()
                runCatching { out.close() } // EOS -> recognizer finishes the segmented session
                // finalize WAV header
                val h = ByteArray(44)
                fun ascii(o: Int, s: String) { for (k in s.indices) h[o + k] = s[k].code.toByte() }
                fun i32(o: Int, v: Int) { h[o]=(v and 0xff).toByte(); h[o+1]=(v shr 8 and 0xff).toByte(); h[o+2]=(v shr 16 and 0xff).toByte(); h[o+3]=(v shr 24 and 0xff).toByte() }
                fun i16(o: Int, v: Int) { h[o]=(v and 0xff).toByte(); h[o+1]=(v shr 8 and 0xff).toByte() }
                ascii(0,"RIFF"); i32(4,(36+dataBytes).toInt()); ascii(8,"WAVE"); ascii(12,"fmt "); i32(16,16)
                i16(20,1); i16(22,1); i32(24,16000); i32(28,32000); i16(32,2); i16(34,16); ascii(36,"data"); i32(40,dataBytes.toInt())
                raf.seek(0); raf.write(h); raf.close()
                Log.i(TAG, "fed capture ended: bytes=$dataBytes (~${dataBytes/32000}s) wav=${wav.length()}")
                fedRunning = false
            }
        }
    }

    fun stopFed() {
        fedStop?.invoke(); fedStop = null
        running = false
        recognizerStatus = "stream closed — waiting for final segments…"
        // recognizer is destroyed on dispose or next start
    }

    fun startSpike() {
        peakSinceStart = 0f; partialCount = 0; lastPartial = ""; finals = emptyList(); errors = emptyList()
        mode = "concurrent"
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

    DisposableEffect(Unit) {
        onDispose {
            if (running) { if (mode == "fed") stopFed() else stopSpike() }
            recognizer?.let { runCatching { it.destroy() } }
        }
    }

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

        if (!running) {
            Button(onClick = { startSpike() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Test A — concurrent (recorder + recognizer)")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { startFed() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Test B — fed stream (blessed, API 33)")
            }
        } else {
            Button(
                onClick = { if (mode == "fed") stopFed() else stopSpike() },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Stop test ${if (mode == "fed") "B" else "A"}") }
        }

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
        if (mode == "fed") {
            StatCard("Fan-out capture (Test B)", buildString {
                append("capturing: $fedRunning\n")
                append("bytes: $fedBytes (~${fedBytes / 32000}s of audio)\n")
                append("live amplitude: %.2f  ·  peak: %.2f".format(fedAmp, peakSinceStart))
                if (fedRunning && peakSinceStart < 0.05f && fedBytes > 64000)
                    append("\n⚠ our AudioRecord appears SILENCED")
            })
        }
        if (mode != "fed") StatCard("Recorder (ours)", buildString {
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
