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
)

/**
 * Calls the backend `transcribeSession` Cloud Function. Sends the recorded WAV inline
 * (base64) for now; will move to Storage upload for larger files + retention later.
 * Uses anonymous auth so the function's auth check passes (seed of the real auth system).
 */
object TranscriptionClient {
    private val functions by lazy { FirebaseFunctions.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) awaitTask(auth.signInAnonymously())
    }

    suspend fun transcribe(file: File, mimeType: String = "audio/wav"): TranscriptionResult {
        ensureSignedIn()
        // Preferred: upload to Cloud Storage, pass the path (no inline size ceiling).
        // Fallback: inline base64 if the upload fails and the file is small enough.
        val payload: HashMap<String, Any> = try {
            val uid = auth.currentUser!!.uid
            val path = "users/$uid/audio/${file.name}"
            val ref = FirebaseStorage.getInstance().reference.child(path)
            awaitTask(ref.putFile(Uri.fromFile(file)))
            hashMapOf("storagePath" to path, "mimeType" to mimeType)
        } catch (e: Exception) {
            Log.w("Yap", "storage upload failed, falling back to inline: ${e.message}")
            val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            hashMapOf("audioBase64" to b64, "mimeType" to mimeType)
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
        )
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
