package com.mehdiacho.yap.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sessions in per-user Firestore: users/{uid}/sessions/{sessionId}.
 * Firestore's built-in offline persistence gives local-first reads/writes + sync.
 * Mapping is manual (no reflection deserialization surprises).
 */
class FirestoreSessionRepository {
    private val db get() = FirebaseFirestore.getInstance()

    /** For writes: creates the guest account if there isn't one, because a write needs an owner. */
    private suspend fun col() = run {
        TranscriptionClient.ensureSignedIn()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        db.collection("users").document(uid).collection("sessions")
    }

    /** For reads: takes the uid rather than fetching one, so a read can never mint an account. */
    private fun sessionsOf(uid: String) =
        db.collection("users").document(uid).collection("sessions")

    /**
     * Follows the signed-in account rather than sampling it once.
     *
     * On a fresh install nobody is signed in — the library is empty and must not mint an
     * account to prove it. But the account arrives moments later, the instant the user
     * records, and a flow that only read the uid at subscribe time would sit on that empty
     * list forever: the first note would land in Firestore and never appear on screen until
     * the app was restarted. Listening to auth state means the query attaches the moment
     * there is a user to attach it for.
     */
    private fun rawSessions(): Flow<List<Session>> = callbackFlow {
        var reg: ListenerRegistration? = null
        val auth = FirebaseAuth.getInstance()
        val onAuth = FirebaseAuth.AuthStateListener { a ->
            reg?.remove()
            val uid = a.currentUser?.uid
            reg = if (uid == null) {
                trySend(emptyList())
                null
            } else {
                sessionsOf(uid).orderBy("createdAt", Query.Direction.DESCENDING)
                    .addSnapshotListener { snap, err ->
                        if (err != null) { close(err); return@addSnapshotListener }
                        if (snap != null) trySend(snap.documents.mapNotNull { it.toSession() })
                    }
            }
        }
        // Fires immediately with the current state, so this covers the first emission too.
        auth.addAuthStateListener(onAuth)
        awaitClose {
            auth.removeAuthStateListener(onAuth)
            reg?.remove()
        }
    }

    /** Active notes (not in trash). */
    fun allSessions(): Flow<List<Session>> =
        rawSessions().map { list -> list.filter { it.trashedAt == null } }

    /** Trashed notes, newest trash first. */
    fun trashedSessions(): Flow<List<Session>> =
        rawSessions().map { list ->
            list.filter { it.trashedAt != null }.sortedByDescending { it.trashedAt }
        }

    suspend fun trash(id: String) {
        awaitTask(col().document(id).update("trashedAt", System.currentTimeMillis()))
    }

    suspend fun restore(id: String) {
        awaitTask(col().document(id).update("trashedAt", null))
    }

    /** Follows auth state for the same reason [rawSessions] does. */
    fun sessionById(id: String): Flow<Session?> = callbackFlow {
        var reg: ListenerRegistration? = null
        val auth = FirebaseAuth.getInstance()
        val onAuth = FirebaseAuth.AuthStateListener { a ->
            reg?.remove()
            val uid = a.currentUser?.uid
            reg = if (uid == null) {
                trySend(null)
                null
            } else {
                sessionsOf(uid).document(id).addSnapshotListener { snap, err ->
                    if (err != null) { close(err); return@addSnapshotListener }
                    trySend(snap?.toSession())
                }
            }
        }
        auth.addAuthStateListener(onAuth)
        awaitClose {
            auth.removeAuthStateListener(onAuth)
            reg?.remove()
        }
    }

    /**
     * Save a whole note.
     *
     * A merge, not a replace. [toMap] writes every field the app owns, nulls included, so for
     * those the two are identical. The difference is fields the app does not own: `followUp`
     * is written by the MCP server, and a plain set() from the copy this device happened to
     * hold would silently delete an outcome recorded a minute earlier.
     */
    suspend fun upsert(session: Session) {
        awaitTask(col().document(session.id).set(session.toMap(), SetOptions.merge()))
    }

    suspend fun delete(id: String) {
        awaitTask(col().document(id).delete())
    }

    /**
     * Record where a note's video landed in Cloud Storage.
     *
     * A merge write of one field rather than [upsert], because the upload finishes long after
     * transcription did and the note has moved on by then — it may be DONE, it may have
     * failed, the user may have edited it. Writing the whole object back from a stale copy
     * would undo whatever happened in between.
     */
    suspend fun setVideoStoragePath(sessionId: String, path: String) {
        awaitTask(
            col().document(sessionId).set(mapOf("videoStoragePath" to path), SetOptions.merge())
        )
    }

    /**
     * Set only the pipeline status of a note, for the same reason as [setVideoStoragePath]:
     * a long-running step marks the note busy while other writers (the server, the note's
     * own first transcription) may still be filling it in.
     */
    suspend fun setStatus(sessionId: String, status: String) {
        awaitTask(col().document(sessionId).set(mapOf("status" to status), SetOptions.merge()))
    }

    private fun Session.toMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "draftText" to draftText,
        "formattedText" to formattedText,
        "audioPath" to audioPath,
        "storagePath" to storagePath,
        "videoPath" to videoPath,
        "videoStoragePath" to videoStoragePath,
        "wordTimings" to wordTimings.map {
            mapOf("text" to it.text, "startMs" to it.startMs, "endMs" to it.endMs)
        },
        "status" to status,
        "statusReason" to statusReason,
        "statusMessage" to statusMessage,
        "createdAt" to createdAt,
        "durationMs" to durationMs,
        "category" to category,
        "topicCluster" to topicCluster,
        "trashedAt" to trashedAt,
        "pinned" to pinned,
        "bookmarks" to bookmarks,
        "extraRecordings" to extraRecordings.map {
            mapOf(
                "audioPath" to it.audioPath,
                "storagePath" to it.storagePath,
                "durationMs" to it.durationMs,
                "bookmarks" to it.bookmarks,
            )
        },
        "actionItems" to actionItems.map {
            mapOf("id" to it.id, "text" to it.text, "done" to it.done, "doneAt" to it.doneAt)
        },
        "enrichVersion" to enrichVersion,
        "source" to source,
        "sourceApp" to sourceApp,
        "sourceRef" to sourceRef,
        // followUp is deliberately absent: the app reads it and never writes it. See upsert().
    )

    private fun DocumentSnapshot.toSession(): Session? {
        if (!exists()) return null
        return Session(
            id = id,
            title = getString("title") ?: "Untitled",
            draftText = getString("draftText") ?: "",
            formattedText = getString("formattedText"),
            audioPath = getString("audioPath"),
            storagePath = getString("storagePath"),
            videoPath = getString("videoPath"),
            videoStoragePath = getString("videoStoragePath"),
            wordTimings = (get("wordTimings") as? List<*>)?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.let { m ->
                    val text = m["text"] as? String ?: return@mapNotNull null
                    WordTiming(
                        text = text,
                        startMs = (m["startMs"] as? Number)?.toLong() ?: return@mapNotNull null,
                        endMs = (m["endMs"] as? Number)?.toLong() ?: return@mapNotNull null,
                    )
                }
            } ?: emptyList(),
            status = getString("status") ?: "DONE",
            statusReason = getString("statusReason"),
            statusMessage = getString("statusMessage"),
            createdAt = getLong("createdAt") ?: 0L,
            durationMs = getLong("durationMs") ?: 0L,
            category = getString("category") ?: "Memo",
            topicCluster = getString("topicCluster") ?: "General",
            trashedAt = getLong("trashedAt"),
            pinned = getBoolean("pinned") ?: false,
            bookmarks = (get("bookmarks") as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
            extraRecordings = (get("extraRecordings") as? List<*>)?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.let { m ->
                    ExtraRecording(
                        audioPath = m["audioPath"] as? String,
                        storagePath = m["storagePath"] as? String,
                        durationMs = (m["durationMs"] as? Number)?.toLong() ?: 0L,
                        bookmarks = (m["bookmarks"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
                            ?: emptyList(),
                    )
                }
            } ?: emptyList(),
            actionItems = (get("actionItems") as? List<*>)?.mapNotNull { raw ->
                (raw as? Map<*, *>)?.let { m ->
                    val text = m["text"] as? String ?: return@mapNotNull null
                    ActionItem(
                        // Older writes may predate stable ids; fall back to the text so a
                        // tick still binds to something rather than being dropped.
                        id = m["id"] as? String ?: text.hashCode().toString(),
                        text = text,
                        done = m["done"] as? Boolean ?: false,
                        doneAt = (m["doneAt"] as? Number)?.toLong(),
                    )
                }
            } ?: emptyList(),
            enrichVersion = (get("enrichVersion") as? Number)?.toInt() ?: 0,
            // Absent on every note written before provenance was recorded. UNKNOWN, not
            // RECORDING — see SessionSource.UNKNOWN for why the difference matters.
            source = getString("source") ?: SessionSource.UNKNOWN,
            sourceApp = getString("sourceApp"),
            sourceRef = getString("sourceRef"),
            followUp = (get("followUp") as? Map<*, *>)?.let { m ->
                (m["state"] as? String)?.let { state ->
                    FollowUp(
                        state = state,
                        note = (m["note"] as? String)?.takeIf { it.isNotBlank() },
                        updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0L,
                        by = m["by"] as? String,
                    )
                }
            },
        )
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        awaitTask(col().document(id).update("pinned", pinned))
    }

    private suspend fun <T> awaitTask(task: com.google.android.gms.tasks.Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
