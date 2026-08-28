package com.mehdiacho.yap.data

/** An additional recording appended to a note. Kept as its own playable clip (the note text
 *  merges, the audio doesn't) — the detail player pages through primary + extras with dots. */
data class ExtraRecording(
    val audioPath: String? = null,   // local file on this device
    val storagePath: String? = null, // cloud copy (users/{uid}/audio/…)
    val durationMs: Long = 0L,
    val bookmarks: List<Long> = emptyList(), // clip-relative elapsed-ms markers
)

/**
 * A task pulled out of a note's text — "email Sam the deck", "book the venue".
 *
 * Separate from the prose rather than parsed out of it on every read: the note's text is
 * edited freely, and a checkbox whose state lived in a markdown "- [x]" would be destroyed
 * the first time the wording changed.
 */
data class ActionItem(
    /** Stable for the life of the note, so completion survives re-extraction and re-ordering. */
    val id: String,
    val text: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
)

/**
 * One spoken word and where it falls in the recording.
 *
 * Only present on notes made from a shared video, where there is a picture to caption. The
 * timings come from the transcription model, not from anything we measure locally — see
 * functions/src/transcribeWords.ts.
 */
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

data class Session(
    val id: String,
    val title: String,
    val draftText: String,
    val formattedText: String? = null,
    val audioPath: String? = null,     // local file on this device (may not exist on others)
    val storagePath: String? = null,   // canonical copy in Cloud Storage (users/{uid}/audio/…)
    /**
     * The shared video this note was made from, kept so it can be played back with captions.
     *
     * Separate from [audioPath] on purpose: what we transcribe is the extracted audio track,
     * which is a fraction of the size (see MediaExtraction.kt). The video is the thing the
     * user watches; the audio is the thing we upload to the model.
     */
    val videoPath: String? = null,        // local file on this device
    val videoStoragePath: String? = null, // cloud copy (users/{uid}/video/…)
    /** Word-level timings for the caption track. Empty for every note without a video. */
    val wordTimings: List<WordTiming> = emptyList(),
    val status: String = "DRAFT",      // TRANSCRIBING, DONE, FAILED, QUOTA, POLISHING
    // Why a FAILED note failed. The server sets these (see functions/src/providerErrors.ts)
    // so "the AI account needs topping up" doesn't read the same as "that audio was bad".
    // Both null on every other status.
    val statusReason: String? = null,  // PROVIDER_CREDIT, PROVIDER_RATE_LIMIT, PROVIDER_AUTH, …
    val statusMessage: String? = null, // human-readable, shown verbatim on the note
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val category: String = "Memo",
    val topicCluster: String = "General",
    val trashedAt: Long? = null,      // soft delete; purged ~30 days after this timestamp
    val pinned: Boolean = false,      // pinned notes sort first in the Library
    val bookmarks: List<Long> = emptyList(), // elapsed-ms markers dropped while recording
    val extraRecordings: List<ExtraRecording> = emptyList(), // appended clips, oldest first
    /** Tasks found in this note. Empty is a valid answer — see [enrichVersion]. */
    val actionItems: List<ActionItem> = emptyList(),
    /**
     * Which enrichment pass has been run over this note.
     *
     * Rather than teaching every reader to cope with notes that predate a feature, a note
     * below [CURRENT_ENRICH_VERSION] is simply brought up to date by a batch pass (Settings →
     * Transcription → Scan notes for tasks, or lazily when the note is opened). New features
     * bump the constant; the backfill does the rest. That keeps one code path for all notes
     * instead of a growing pile of "if this field is missing" branches.
     *
     * 0 = never enriched. 1 = action items extracted.
     */
    val enrichVersion: Int = 0,
) {
    /** True once every task in the note is ticked (and there was at least one). */
    val allDone: Boolean get() = actionItems.isNotEmpty() && actionItems.all { it.done }

    /** True when this note has a video to play — locally or in the cloud. */
    val hasVideo: Boolean get() = videoPath != null || videoStoragePath != null

    val hasTasks: Boolean get() = actionItems.isNotEmpty()
    val tasksDone: Int get() = actionItems.count { it.done }
    /** True when there is at least one task still outstanding. */
    val hasOpenTasks: Boolean get() = actionItems.any { !it.done }
}

/** Bump when a new enrichment pass is added; the backfill picks up everything below it. */
const val CURRENT_ENRICH_VERSION = 1
