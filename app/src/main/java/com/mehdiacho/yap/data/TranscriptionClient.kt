package com.mehdiacho.yap.data

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranscriptionResult(
    val rawText: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val category: String,
    val topicCluster: String,
    val formattedText: String,
    val storagePath: String? = null, // where the audio landed in Cloud Storage (null if inline fallback)
)

/**
 * Calls the backend `transcribeSession` Cloud Function. Sends the recorded WAV inline
 * (base64) for now; will move to Storage upload for larger files + retention later.
 * Uses anonymous auth so the function's auth check passes (seed of the real auth system).
 */
object TranscriptionClient {

    /**
     * Ceiling on an append clip. Append audio is sent inline as base64 in the callable
     * payload, so this is a transport limit, not a product choice — at our 16 kHz mono
     * 16-bit format it works out to roughly [MAX_APPEND_MINUTES] of speech. The capture UI
     * warns as a recording approaches it; going over is no longer destructive (the clip is
     * saved as its own note), but a warned user keeps the note they intended.
     */
    const val MAX_APPEND_BYTES = 15L * 1024 * 1024
    const val MAX_APPEND_MINUTES = 7 // MAX_APPEND_BYTES / (16000 * 2) / 60, rounded down
    // Region must match the deployed function (africa-south1, close to home).
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }
    private val auth by lazy { FirebaseAuth.getInstance() }

    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) awaitTask(auth.signInAnonymously())
    }

    /** Upload a recording to the user's Cloud Storage area; returns the storage path. */
    suspend fun uploadAudio(file: File): String {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val path = "users/$uid/audio/${file.name}"
        awaitTask(FirebaseStorage.getInstance().reference.child(path).putFile(Uri.fromFile(file)))
        return path
    }

    /**
     * The callable SDK gives up after 70s by default. Long audio blows straight past
     * that: a 31-minute recording had the client disconnect at 66s and mark the note
     * FAILED while the server was still transcribing, discarding the result.
     *
     * Raised to match the function's own 540s budget. Belt-and-braces with the Firestore
     * result channel: when a sessionId is supplied the server writes the transcript onto
     * the note itself, so even a disconnect no longer loses the work.
     */
    private const val CALL_TIMEOUT_SEC = 540L

    /** Ceiling on the inline-base64 fallback; see the guard in [transcribe]. */
    private const val MAX_INLINE_BYTES = 8L * 1024 * 1024

    private fun callable(name: String) =
        functions.getHttpsCallable(name).withTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)

    suspend fun transcribe(
        file: File,
        mimeType: String = "audio/wav",
        durationMs: Long = 0L,
        sessionId: String? = null,
    ): TranscriptionResult {
        ensureSignedIn()
        // Preferred: upload to Cloud Storage, pass the path (no inline size ceiling).
        // Fallback: inline base64 if the upload fails and the file is small enough.
        var uploadedPath: String? = null
        val payload: HashMap<String, Any> = try {
            uploadedPath = uploadAudio(file)
            hashMapOf("storagePath" to uploadedPath, "mimeType" to mimeType, "durationMs" to durationMs)
        } catch (e: Exception) {
            // Inline is a fallback for SMALL clips only. Base64-ing a large file here means
            // the byte array AND a ~4/3 string on the app heap — a 60 MB recording would
            // OOM the app trying to report that an upload failed.
            require(file.length() <= MAX_INLINE_BYTES) {
                "Couldn't upload that recording, and it's too large to send directly. " +
                    "Check your connection and try again."
            }
            Log.w("Yap", "storage upload failed, falling back to inline: ${e.message}")
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            hashMapOf("audioBase64" to b64, "mimeType" to mimeType, "durationMs" to durationMs)
        }
        sessionId?.let { payload["sessionId"] = it }
        val result = awaitTask(callable("transcribeSession").call(payload))
        return parseTranscription(result.getData(), uploadedPath)
    }

    /**
     * Re-run transcription on audio already in Cloud Storage — used by "Try again" on a
     * failed note. Skips the upload entirely: the bytes are already there, and a failure
     * is usually server-side (an OOM on a long recording, a transient model error), so
     * re-sending tens of megabytes to retry would be pure waste.
     */
    suspend fun transcribeExisting(
        storagePath: String,
        mimeType: String = "audio/wav",
        durationMs: Long = 0L,
        sessionId: String? = null,
    ): TranscriptionResult {
        ensureSignedIn()
        val payload: HashMap<String, Any> =
            hashMapOf("storagePath" to storagePath, "mimeType" to mimeType, "durationMs" to durationMs)
        sessionId?.let { payload["sessionId"] = it }
        val result = awaitTask(callable("transcribeSession").call(payload))
        return parseTranscription(result.getData(), storagePath)
    }

    private fun parseTranscription(data: Any?, storagePath: String?): TranscriptionResult {
        @Suppress("UNCHECKED_CAST")
        val map = data as? Map<String, Any?> ?: emptyMap()
        return TranscriptionResult(
            rawText = map["rawText"] as? String ?: "",
            name = map["name"] as? String ?: "Recording",
            description = map["description"] as? String ?: "",
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            category = map["category"] as? String ?: "Memo",
            topicCluster = map["topicCluster"] as? String ?: "General",
            formattedText = map["formattedText"] as? String ?: "",
            storagePath = storagePath,
        )
    }

    /** Re-upload a (merged) recording to an existing storage path, or create one. */
    suspend fun uploadAudioTo(file: File, existingPath: String?): String {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val path = existingPath ?: "users/$uid/audio/${file.name}"
        awaitTask(FirebaseStorage.getInstance().reference.child(path).putFile(Uri.fromFile(file)))
        return path
    }

    data class AppendResult(val rawText: String, val formattedText: String, val name: String)

    /**
     * Append-to-note: transcribe a new clip and merge it into the existing note.
     * The clip goes inline (append clips are short); ~15 MB guard keeps us under the
     * callable payload limit — longer additions should be their own note.
     */
    suspend fun appendTranscribe(
        clip: File,
        durationMs: Long,
        title: String,
        existingDraft: String,
        existingFormatted: String,
    ): AppendResult {
        ensureSignedIn()
        require(clip.length() < MAX_APPEND_BYTES) {
            "Additions can be up to about $MAX_APPEND_MINUTES minutes; that one is longer."
        }
        val b64 = Base64.encodeToString(clip.readBytes(), Base64.NO_WRAP)
        val result = awaitTask(
            functions.getHttpsCallable("appendToSession").call(
                hashMapOf(
                    "audioBase64" to b64,
                    "mimeType" to "audio/wav",
                    "durationMs" to durationMs,
                    "title" to title,
                    "existingDraft" to existingDraft.take(100_000),
                    "existingFormatted" to existingFormatted.take(100_000),
                )
            )
        )
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        return AppendResult(
            rawText = map["rawText"] as? String ?: "",
            formattedText = map["formattedText"] as? String ?: existingFormatted,
            name = map["name"] as? String ?: title,
        )
    }

    /** "Polish now" — formalize existing draft text without an audio round-trip. */
    suspend fun refineText(draftText: String): TranscriptionResult {
        ensureSignedIn()
        val result = awaitTask(
            functions.getHttpsCallable("refineText").call(hashMapOf("draftText" to draftText))
        )
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        return TranscriptionResult(
            rawText = draftText,
            name = map["name"] as? String ?: "Note",
            description = map["description"] as? String ?: "",
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            category = map["category"] as? String ?: "Memo",
            topicCluster = map["topicCluster"] as? String ?: "General",
            formattedText = map["formattedText"] as? String ?: draftText,
            storagePath = null,
        )
    }

    data class ReviewedBlock(
        val blockIndex: Int,
        val newText: String,
        val flagged: Boolean,
        val reason: String?,
    )

    /** Smart Review — sends paragraph blocks, returns flagged/corrected blocks. */
    suspend fun smartReview(blocks: List<String>): List<ReviewedBlock> {
        ensureSignedIn()
        val payload = hashMapOf(
            "blocks" to blocks.map { hashMapOf("versions" to listOf(it), "activeIndex" to 0) }
        )
        val result = awaitTask(functions.getHttpsCallable("smartReview").call(payload))
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        val updated = map["updatedBlocks"] as? List<*> ?: emptyList<Any>()
        return updated.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            ReviewedBlock(
                blockIndex = (m["blockIndex"] as? Number)?.toInt() ?: return@mapNotNull null,
                newText = m["newText"] as? String ?: "",
                flagged = m["flagged"] as? Boolean ?: false,
                reason = m["reason"] as? String,
            )
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
