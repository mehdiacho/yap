package com.mehdiacho.yap.data

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Recordings that exist on disk but no note points at — "orphans".
 *
 * These are recoverable audio that the app captured successfully but failed to attach
 * to a note (see the append-size guard in [TranscriptionClient.appendTranscribe]). The
 * WAV is always written and closed by RecordingService before any note work happens, so
 * an orphan is a complete, playable recording, not a partial one.
 *
 * This is a safety net, not a fix: the capture pipeline should never strand audio in the
 * first place. It exists because a stranded recording was previously invisible — only
 * reachable by pulling the app's private storage off the device.
 */
object OrphanRecordings {

    /** Canonical Yap recording format: 16 kHz mono 16-bit PCM after a 44-byte header. */
    private const val HEADER_BYTES = 44L
    private const val BYTE_RATE = 16000 * 2

    data class Orphan(
        val file: File,
        val sizeBytes: Long,
        val durationMs: Long,
        /** When it was recorded — from the rec_<epochMillis>.wav name, else file mtime. */
        val recordedAt: Long,
    ) {
        val path: String get() = file.absolutePath
    }

    private fun recordingsDir(context: Context) = File(context.filesDir, "recordings")

    /**
     * Every recording on disk that no session references, newest first.
     *
     * [sessions] must include trashed notes — a recording belonging to a trashed note is
     * not an orphan, and offering to "recover" it would silently resurrect deleted audio.
     */
    fun find(context: Context, sessions: List<Session>): List<Orphan> {
        val dir = recordingsDir(context)
        if (!dir.isDirectory) return emptyList()

        // Match on FILE NAME, not absolute path. Private storage is reachable as both
        // /data/data/<pkg>/... and /data/user/0/<pkg>/..., and which one filesDir reports
        // varies. A prefix mismatch under path comparison would mark every recording as
        // stranded and invite the user to duplicate their whole library — so compare the
        // one component that is stable and already unique (rec_<epochMillis>.wav).
        val referenced = buildSet {
            sessions.forEach { s ->
                s.audioPath?.let { add(File(it).name) }
                s.extraRecordings.forEach { extra -> extra.audioPath?.let { add(File(it).name) } }
            }
        }

        return dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > HEADER_BYTES && it.name !in referenced }
            .map { f ->
                Orphan(
                    file = f,
                    sizeBytes = f.length(),
                    durationMs = durationMsOf(f),
                    recordedAt = timestampOf(f),
                )
            }
            .sortedByDescending { it.recordedAt }
    }

    /**
     * Duration from the byte count. Only valid for our own canonical WAVs; imported files
     * (shared_*.m4a and friends) get 0, which the UI renders as "unknown length" rather
     * than a wrong number.
     */
    private fun durationMsOf(file: File): Long =
        if (file.name.endsWith(".wav")) (file.length() - HEADER_BYTES) * 1000 / BYTE_RATE else 0L

    private fun timestampOf(file: File): Long =
        Regex("""^(?:rec|shared)_(\d{10,})\.""").find(file.name)
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: file.lastModified()

    /** Share a single recovered recording out to another app (Drive, mail, messaging…). */
    fun shareIntent(context: Context, orphan: Orphan): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", orphan.file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = if (orphan.file.name.endsWith(".wav")) "audio/wav" else "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Yap recording ${orphan.file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
