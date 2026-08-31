package com.mehdiacho.yap.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * A small, self-contained recorder for one-off voice messages — currently the spoken half of
 * in-app feedback.
 *
 * Deliberately NOT [RecordingService]. That service is the note pipeline: it publishes to
 * RecordingBus, and anything listening turns what it hears into a note, uploads it, and spends
 * a transcription on it. Recording feedback through it would file the user's complaint as a
 * note in their own library and bill them for the privilege.
 *
 * So this is a plain MediaRecorder writing AAC — the audio is going to a person, not a model,
 * and a few hundred kilobytes uploads on a phone connection where a WAV of the same length
 * would not.
 */
class VoiceMemoRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Starts capture. Returns false if the mic couldn't be opened — caller shows the reason. */
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, "feedback").apply { mkdirs() }
        // In the cache directory on purpose: once a report is submitted this audio has no
        // further use, and the OS is welcome to reclaim it without asking.
        val out = File(dir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
            else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e("Yap", "feedback recorder failed to start: ${e.message}", e)
            runCatching { out.delete() }
            recorder = null
            file = null
            false
        }
    }

    /** Stops and returns the finished clip, or null if nothing usable was captured. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val out = file
        recorder = null
        file = null
        val durationMs = System.currentTimeMillis() - startedAt
        // stop() throws when it is called before the encoder has written a single frame —
        // a tap that starts and ends immediately. The file it leaves behind is unplayable.
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!stopped || out == null || !out.exists() || out.length() < 1024) {
            runCatching { out?.delete() }
            return null
        }
        return out to durationMs
    }

    /** Abandons an in-progress recording and deletes the file. */
    fun cancel() {
        val out = file
        stop()
        runCatching { out?.delete() }
    }
}
