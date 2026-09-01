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
    val actionItems: List<String> = emptyList(), // tasks the server pulled out of the text
    /** Word timings for captioning. Empty unless they were asked for AND the model had them. */
    val wordTimings: List<WordTiming> = emptyList(),
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

    /**
     * Signs in as a guest if nobody is signed in yet, creating the account.
     *
     * Call this only where the user is committing something of their own — a recording, a
     * profile edit, a redeemed code, a purchase, a feedback report. Anything that merely
     * *reads* must use [signedInUid] instead and cope with null, because an account created
     * for a read is an account that exists for someone who never asked for one.
     *
     * That is not hypothetical. Every release triggers a Play pre-launch report, which
     * installs the app on a rack of devices and taps through it; when browsing created
     * accounts, each release minted a fresh batch, and 237 of 271 accounts were robots that
     * had never recorded a thing.
     */
    suspend fun ensureSignedIn() {
        if (auth.currentUser == null) awaitTask(auth.signInAnonymously())
    }

    /** The signed-in uid, or null if nobody is. Never creates an account. */
    fun signedInUid(): String? = auth.currentUser?.uid

    /** Upload a recording to the user's Cloud Storage area; returns the storage path. */
    suspend fun uploadAudio(file: File): String {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val path = "users/$uid/audio/${file.name}"
        awaitTask(FirebaseStorage.getInstance().reference.child(path).putFile(Uri.fromFile(file)))
        return path
    }

    /**
     * Upload the source video of a note, so it still plays after a reinstall or on another
     * device. Kept apart from [uploadAudio] by path (`video/` not `audio/`) because the two
     * are wanted at different moments: the audio has to be up before transcription can start,
     * the video only has to be up before someone tries to watch it.
     */
    suspend fun uploadVideo(file: File): String {
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val path = "users/$uid/video/${file.name}"
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
        /** Category chosen before recording. Overrides the model's guess server-side. */
        category: String? = null,
        /**
         * Called the moment the audio is safely in Cloud Storage, before transcription is
         * even attempted.
         *
         * The upload and the transcription fail independently: hitting the monthly cap
         * leaves a perfectly good recording sitting in the bucket, and because this
         * function only returns the path on success, the note used to be left with no
         * pointer to it. Transcribing it later then meant re-uploading the whole file —
         * or, after a reinstall, finding the local copy gone and the cloud copy orphaned.
         */
        onUploaded: ((String) -> Unit)? = null,
        /**
         * Ask for word-level timings. Only set for a note that has a video to caption —
         * they cost a preview model and a slower request, and nothing else can show them.
         */
        wantWordTimings: Boolean = false,
    ): TranscriptionResult {
        ensureSignedIn()
        // Preferred: upload to Cloud Storage, pass the path (no inline size ceiling).
        // Fallback: inline base64 if the upload fails and the file is small enough.
        var uploadedPath: String? = null
        val payload: HashMap<String, Any> = try {
            uploadedPath = uploadAudio(file)
            onUploaded?.invoke(uploadedPath)
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
        category?.takeIf { it.isNotBlank() }?.let { payload["category"] = it }
        if (wantWordTimings) payload["wantWordTimings"] = true
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
        /** Category already on the note. Keeps a deliberate filing from being re-guessed. */
        category: String? = null,
    ): TranscriptionResult {
        ensureSignedIn()
        val payload: HashMap<String, Any> =
            hashMapOf("storagePath" to storagePath, "mimeType" to mimeType, "durationMs" to durationMs)
        sessionId?.let { payload["sessionId"] = it }
        category?.takeIf { it.isNotBlank() }?.let { payload["category"] = it }
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
            actionItems = (map["actionItems"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            wordTimings = (map["words"] as? List<*>)?.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                WordTiming(
                    text = m["text"] as? String ?: return@mapNotNull null,
                    startMs = (m["startMs"] as? Number)?.toLong() ?: return@mapNotNull null,
                    endMs = (m["endMs"] as? Number)?.toLong() ?: return@mapNotNull null,
                )
            } ?: emptyList(),
        )
    }

    /**
     * Extract action items for a batch of notes — the backfill for everything recorded
     * before tasks existed. Server caps each call at 10 notes; page through.
     *
     * Notes missing from the response failed and are simply retried next pass, so a partial
     * result never needs special handling here.
     */
    suspend fun enrichNotes(notes: List<Pair<String, String>>): Map<String, List<String>> {
        if (notes.isEmpty()) return emptyMap()
        ensureSignedIn()
        val payload = hashMapOf(
            "notes" to notes.map { (id, text) ->
                hashMapOf("id" to id, "text" to text.take(40_000))
            }
        )
        val result = awaitTask(
            functions.getHttpsCallable("enrichNotes")
                .withTimeout(300, TimeUnit.SECONDS)
                .call(payload)
        )
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        return (map["results"] as? List<*> ?: emptyList<Any>()).mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            id to ((m["tasks"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList())
        }.toMap()
    }

    /**
     * The cloud copy of a recording, looked up by file name — or null if it isn't there.
     *
     * Upload paths are deterministic ([uploadAudio]), so a note that lost its `storagePath`
     * can still find its own audio. That matters most where it's least visible: a recording
     * refused at the monthly cap was uploaded before it was refused, and after a reinstall
     * (or on any other device) the cloud copy is the only copy left. Without this the note
     * would report its audio as unavailable while the bytes sat in the bucket.
     */
    suspend fun findUploadedAudio(fileName: String): String? {
        if (fileName.isBlank()) return null
        ensureSignedIn()
        val uid = auth.currentUser!!.uid
        val path = "users/$uid/audio/$fileName"
        return try {
            awaitTask(FirebaseStorage.getInstance().reference.child(path).metadata)
            path
        } catch (e: Exception) {
            Log.d("Yap", "no cloud copy at $path: ${e.message}")
            null
        }
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

    /** One occurrence the model thinks was deliberate; [reason] is short and shown inline. */
    data class OccurrenceVerdict(val index: Int, val intentional: Boolean, val reason: String)

    /**
     * Advisory pass over propagated corrections — which remaining occurrences look
     * intentional ("cloud storage" when correcting cloud→Claude).
     *
     * Purely optional. The occurrence list is produced locally by [TextCorrections] and is
     * shown before this returns; callers are expected to time it out and carry on. Fixing a
     * typo must never wait on the network.
     */
    suspend fun reviewCorrections(
        from: String,
        to: String,
        snippets: List<String>,
    ): List<OccurrenceVerdict> {
        ensureSignedIn()
        val result = awaitTask(
            functions.getHttpsCallable("reviewCorrections")
                .withTimeout(30, TimeUnit.SECONDS)
                .call(hashMapOf("from" to from, "to" to to, "snippets" to snippets))
        )
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?> ?: emptyMap()
        return (map["verdicts"] as? List<*> ?: emptyList<Any>()).mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            OccurrenceVerdict(
                index = (m["index"] as? Number)?.toInt() ?: return@mapNotNull null,
                intentional = m["intentional"] as? Boolean ?: false,
                reason = m["reason"] as? String ?: "",
            )
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
