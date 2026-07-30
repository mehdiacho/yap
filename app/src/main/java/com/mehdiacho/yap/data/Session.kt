package com.mehdiacho.yap.data

/** An additional recording appended to a note. Kept as its own playable clip (the note text
 *  merges, the audio doesn't) — the detail player pages through primary + extras with dots. */
data class ExtraRecording(
    val audioPath: String? = null,   // local file on this device
    val storagePath: String? = null, // cloud copy (users/{uid}/audio/…)
    val durationMs: Long = 0L,
    val bookmarks: List<Long> = emptyList(), // clip-relative elapsed-ms markers
)

data class Session(
    val id: String,
    val title: String,
    val draftText: String,
    val formattedText: String? = null,
    val audioPath: String? = null,     // local file on this device (may not exist on others)
    val storagePath: String? = null,   // canonical copy in Cloud Storage (users/{uid}/audio/…)
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
)
