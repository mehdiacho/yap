package com.mehdiacho.yap.data

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
)
