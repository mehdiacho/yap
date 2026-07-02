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
    val topicCluster: String = "General"
)
