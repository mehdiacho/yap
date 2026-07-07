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

    suspend fun transcribe(
        file: File,
        mimeType: String = "audio/wav",
        durationMs: Long = 0L,
    ): TranscriptionResult {
        ensureSignedIn()
        // Preferred: upload to Cloud Storage, pass the path (no inline size ceiling).
        // Fallback: inline base64 if the upload fails and the file is small enough.
        var uploadedPath: String? = null
        val payload: HashMap<String, Any> = try {
            uploadedPath = uploadAudio(file)
            hashMapOf("storagePath" to uploadedPath, "mimeType" to mimeType, "durationMs" to durationMs)
        } catch (e: Exception) {
            Log.w("Yap", "storage upload failed, falling back to inline: ${e.message}")
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            hashMapOf("audioBase64" to b64, "mimeType" to mimeType, "durationMs" to durationMs)
        }
        val result = awaitTask(functions.getHttpsCallable("transcribeSession").call(payload))

        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        return TranscriptionResult(
            rawText = map["rawText"] as? String ?: "",
            name = map["name"] as? String ?: "Recording",
            description = map["description"] as? String ?: "",
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            category = map["category"] as? String ?: "Memo",
            topicCluster = map["topicCluster"] as? String ?: "General",
            formattedText = map["formattedText"] as? String ?: "",
            storagePath = uploadedPath,
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
