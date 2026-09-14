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

/**
 * Where a note came from.
 *
 * Kept because the app genuinely cannot tell afterwards, and guessing was doing harm: a note
 * made from shared text has no audio, and the no-audio row told people their "audio was
 * discarded after transcription" — inviting them to go looking for a recording that never
 * existed, or to conclude their data had been mishandled. What a note is made of is part of
 * what the note IS, and it is also the first thing the grouped view and any agent reading
 * these notes will want to filter on.
 */
object SessionSource {
    /** Captured live, in the app, through RecordingService. */
    const val RECORDING = "RECORDING"
    const val SHARED_AUDIO = "SHARED_AUDIO"
    const val SHARED_VIDEO = "SHARED_VIDEO"
    /** Text sent in through the share sheet. Has no recording and never did. */
    const val SHARED_TEXT = "SHARED_TEXT"
    /**
     * Made by merging other notes together (Library → select → Group).
     *
     * Its sources keep existing and keep their audio; this note is the document written from
     * them, and has no recording of its own. [Session.sourceRef] holds the ids it was built
     * from, so the trail back is never guesswork.
     */
    const val MERGED = "MERGED"
    /** Rescued from disk by the orphan scan; the original context is gone. */
    const val RECOVERED = "RECOVERED"
    /**
     * Written before this field existed.
     *
     * Deliberately its own value rather than defaulting old notes to RECORDING: most of them
     * were recordings, but some were not, and quietly asserting a provenance we never
     * captured is the same class of mistake this field exists to fix. Readers treat UNKNOWN
     * as "don't claim anything".
     */
    const val UNKNOWN = "UNKNOWN"
}

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
    /** One of [SessionSource]. New notes always carry a real value. */
    val source: String = SessionSource.RECORDING,
    /**
     * The app this note was shared from, as a display name ("TikTok", "Files") — resolved at
     * import time from the sharing package, because the label is what a person recognises and
     * the package name is what survives an uninstall. Null for anything not shared in.
     */
    val sourceApp: String? = null,
    /**
     * Whatever the share carried alongside the file — usually the source URL. Apps like
     * TikTok put the link in EXTRA_TEXT next to the video, and it is the only durable pointer
     * back to where a clip actually came from.
     */
    val sourceRef: String? = null,
) {
    /** True once every task in the note is ticked (and there was at least one). */
    val allDone: Boolean get() = actionItems.isNotEmpty() && actionItems.all { it.done }

    /** True when this note has a video to play — locally or in the cloud. */
    val hasVideo: Boolean get() = videoPath != null || videoStoragePath != null

    /**
     * True when this note is known never to have had a recording.
     *
     * SHARED_TEXT and MERGED qualify — one arrived as text, the other was written from other
     * notes. UNKNOWN deliberately does not: an old note might have had audio that has since
     * been cleaned up, and saying "there was never a recording" about one that had one is the
     * same false claim in the other direction.
     *
     * Callers that explain the absence to the user must distinguish the two — the reason
     * differs, and so does the copy. See SessionDetailScreen.
     */
    val neverHadAudio: Boolean
        get() = source == SessionSource.SHARED_TEXT || source == SessionSource.MERGED

    /** The notes this one was merged from, if it was. Empty for everything else. */
    val mergedFrom: List<String>
        get() = if (source == SessionSource.MERGED)
            sourceRef?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        else emptyList()

    val hasTasks: Boolean get() = actionItems.isNotEmpty()
    val tasksDone: Int get() = actionItems.count { it.done }
    /** True when there is at least one task still outstanding. */
    val hasOpenTasks: Boolean get() = actionItems.any { !it.done }
}

/** Bump when a new enrichment pass is added; the backfill picks up everything below it. */
const val CURRENT_ENRICH_VERSION = 1
