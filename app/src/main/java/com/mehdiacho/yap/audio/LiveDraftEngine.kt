package com.mehdiacho.yap.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Tier-1 live draft: fans the recorder's PCM stream into the system SpeechRecognizer via
 * EXTRA_AUDIO_SOURCE + EXTRA_SEGMENTED_SESSION (Android 13+, proven by the feasibility
 * spike — no mic contention because the recognizer never opens the mic).
 *
 * Design constraints:
 *  - feed() is called from the recorder thread and MUST NEVER BLOCK (a stalled pipe would
 *    stall the recorder and lose audio) -> buffers go through a bounded queue drained by
 *    a dedicated writer thread; overflow drops draft audio, never recording audio.
 *  - Failures are silent: no draft is never worse than no recording.
 */
object LiveDraftEngine {
    private const val TAG = "YapDraft"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var queue: LinkedBlockingQueue<ByteArray>? = null
    private var writer: Thread? = null
    private var segments = mutableListOf<String>()
    @Volatile private var partial = ""
    @Volatile private var running = false

    // Session generation. Back-to-back recordings overlap the previous session's delayed
    // teardown (destroy at +4s, draft consume at +2.5s) with the new session — every delayed
    // or callback-driven mutation checks it still belongs to the current generation.
    @Volatile var generation = 0
        private set

    // Names the recognizer tends to fumble; user-defined vocabulary joins this later.
    private val biasing = arrayListOf("Yap", "Firebase", "Kotlin", "Gaborone", "Botswana")

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Called by RecordingService on the main thread when recording starts. */
    fun start(context: Context) {
        if (!isSupported || running) return
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.i(TAG, "no recognition service available; draft disabled"); return
            }
            running = true
            generation++
            // A recognizer from the previous session may still be lingering in its 4s
            // flush window — tear it down now so it can't interfere with this session.
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
            segments = mutableListOf(); partial = ""
            _draftText.value = ""

            val pipe = ParcelFileDescriptor.createPipe()
            val q = LinkedBlockingQueue<ByteArray>(256) // ~5s of 20ms buffers
            queue = q

            writer = thread(name = "yap-draft-writer") {
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                try {
                    while (true) {
                        val buf = q.take()
                        if (buf.isEmpty()) break // poison pill = end of stream
                        out.write(buf)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "writer ended: ${e.message}")
                } finally {
                    runCatching { out.close() } // EOS -> recognizer flushes final segments
                }
            }

            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(makeListener(generation))
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, biasing)
            }
            r.startListening(intent)
            _active.value = true
            Log.i(TAG, "live draft started (fed-stream)")
        } catch (e: Exception) {
            Log.w(TAG, "start failed: ${e.message}")
            cleanup()
        }
    }

    /** Called from the recorder thread for every captured buffer. Never blocks. */
    fun feed(buf: ByteArray, len: Int) {
        if (!running) return
        val q = queue ?: return
        val copy = buf.copyOf(len)
        if (!q.offer(copy)) {
            q.poll() // drop oldest draft audio rather than block the recorder
            q.offer(copy)
        }
    }

    /** Called when recording stops/discards. Closes the stream; finals arrive shortly after. */
    fun stop() {
        if (!running) return
        running = false
        queue?.offer(ByteArray(0)) // poison pill -> writer closes pipe -> recognizer flushes
        queue = null
        // Give the recognizer a moment to deliver final segments, then destroy. Destroy the
        // instance captured NOW — if a new session started meanwhile, `recognizer` points to
        // its recognizer and must not be touched (that was the empty-draft bug).
        val r = recognizer
        val myGen = generation
        mainHandler.postDelayed({
            r?.let { runCatching { it.destroy() } }
            if (generation == myGen) {
                recognizer = null
                _active.value = false
            }
        }, 4000)
    }

    /** The full draft so far (segments + trailing partial). */
    fun currentDraft(): String =
        (segments + listOfNotNull(partial.takeIf { it.isNotBlank() })).joinToString(" ").trim()

    /** Consume the draft for attaching to the finished session. Pass the [generation] observed
     *  when that recording finished — if a new recording has started since, this returns ""
     *  and leaves the new session's draft untouched. */
    fun consumeDraft(expectedGeneration: Int = generation): String {
        if (expectedGeneration != generation) return ""
        val d = currentDraft()
        segments = mutableListOf(); partial = ""
        return d
    }

    private fun publish() {
        _draftText.value = currentDraft()
    }

    private fun cleanup() {
        running = false
        queue?.offer(ByteArray(0))
        queue = null
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        _active.value = false
    }

    /** Listener bound to one session: results arriving after a newer session started are
     *  dropped instead of appended into the new session's draft. */
    private fun makeListener(myGen: Int) = object : RecognitionListener {
        private val stale get() = myGen != generation
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            Log.w(TAG, "recognizer error $error; draft disabled for this recording")
            // Keep whatever draft we have; don't disturb the recording.
        }
        override fun onResults(results: Bundle?) {
            if (stale) return
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { segments.add(it.trim()); partial = ""; publish() }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (stale) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.let { partial = it; publish() }
        }
        override fun onSegmentResults(segmentResults: Bundle) {
            if (stale) return
            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { segments.add(it.trim()); partial = ""; publish() }
        }
        override fun onEndOfSegmentedSession() {
            Log.i(TAG, "segmented session ended; draft='${currentDraft().take(80)}…'")
            if (!stale) publish()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
