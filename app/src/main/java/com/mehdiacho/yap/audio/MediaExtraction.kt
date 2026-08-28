package com.mehdiacho.yap.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/** The audio pulled out of a shared video, ready to upload. */
data class ExtractedAudio(
    val file: File,
    /** What to tell the server this is — matters, it picks the decoder and the quota maths. */
    val mimeType: String,
    val durationMs: Long,
)

/**
 * Pulls the audio track out of a shared video so we upload a few hundred KB instead of tens
 * of megabytes.
 *
 * **Why M4A and not MP3.** The obvious instinct is "convert it to MP3", but that would be the
 * worst of the three options here. Android ships an MP3 *decoder* and no MP3 *encoder*, so
 * producing one means bundling LAME or FFmpeg — several MB of native code — and then paying
 * a full decode/re-encode, which costs battery and throws away quality on the way through.
 *
 * An MP4's audio track is already AAC, which beats MP3 at equal size anyway. So instead of
 * converting anything we **remux**: [MediaExtractor] reads the encoded audio samples and
 * [MediaMuxer] writes those exact bytes into an audio-only MP4 container. Nothing is decoded,
 * nothing is re-encoded, no quality is lost, no dependency is added, and it runs at file-copy
 * speed. The result is `audio/mp4`, which the backend already normalises and Gemini already
 * accepts.
 *
 * A 10-minute 1080p clip goes from ~150 MB to ~10 MB this way, and that is the file that gets
 * uploaded and billed for.
 */
object MediaExtraction {

    private const val TAG = "YapMedia"

    /** Big enough for an AAC frame; only used when the track doesn't declare its own. */
    private const val FALLBACK_BUFFER_BYTES = 512 * 1024

    fun isVideo(mimeType: String?): Boolean = mimeType?.startsWith("video/") == true

    /**
     * Copy the audio track of [source] into a standalone file.
     *
     * Returns null when there is nothing to extract or the remux fails — an MP4 whose audio
     * isn't AAC, a container the muxer won't take. Callers fall back to uploading the
     * original file, which still transcribes, just less efficiently. A rare slow path is a
     * much better answer than a failed import.
     */
    fun extractAudio(context: Context, source: Uri, outputDir: File): ExtractedAudio? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val out = File(outputDir.apply { mkdirs() }, "audio_${System.currentTimeMillis()}.m4a")
        try {
            extractor.setDataSource(context, source, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "no audio track in $source")
                return null
            }
            extractor.selectTrack(trackIndex)

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val dest = muxer.addTrack(format)
            muxer.start()

            val capacity = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
                .getOrNull()?.takeIf { it > 0 } ?: FALLBACK_BUFFER_BYTES
            val buffer = ByteBuffer.allocate(capacity)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(dest, buffer, info)
                samples++
                extractor.advance()
            }
            // Checked before stop(): stopping a muxer that was never written to throws.
            if (samples == 0) {
                Log.w(TAG, "audio track carried no samples")
                out.delete()
                return null
            }

            // Stop here rather than leaving it to `finally`: the muxer only flushes the
            // container header on stop(), so the file isn't complete — or measurable —
            // until it has run.
            muxer.stop()
            muxer.release()
            muxer = null

            val durationMs = runCatching { format.getLong(MediaFormat.KEY_DURATION) / 1000 }
                .getOrDefault(0L)
            Log.d(TAG, "remuxed $samples samples -> ${out.length()} bytes (${durationMs}ms)")
            return ExtractedAudio(out, "audio/mp4", durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "audio extraction failed, will upload the original: ${e.message}")
            out.delete()
            return null
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
