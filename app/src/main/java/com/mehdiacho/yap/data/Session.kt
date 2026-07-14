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
    val status: String = "DRAFT",      // TRANSCRIBING, DONE, FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val category: String = "Memo",
    val topicCluster: String = "General",
    val trashedAt: Long? = null,      // soft delete; purged ~30 days after this timestamp
    val pinned: Boolean = false,      // pinned notes sort first in the Library
    val bookmarks: List<Long> = emptyList(), // elapsed-ms markers dropped while recording
    val extraRecordings: List<ExtraRecording> = emptyList(), // appended clips, oldest first
)
