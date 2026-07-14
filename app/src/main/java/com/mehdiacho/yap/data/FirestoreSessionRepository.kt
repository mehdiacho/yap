package com.mehdiacho.yap.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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

    private suspend fun col() = run {
        TranscriptionClient.ensureSignedIn()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        db.collection("users").document(uid).collection("sessions")
    }

    private fun rawSessions(): Flow<List<Session>> = callbackFlow {
        val collection = col()
        val reg = collection.orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                if (snap != null) trySend(snap.documents.mapNotNull { it.toSession() })
            }
        awaitClose { reg.remove() }
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

    fun sessionById(id: String): Flow<Session?> = callbackFlow {
        val collection = col()
        val reg = collection.document(id).addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.toSession())
        }
        awaitClose { reg.remove() }
    }

    suspend fun upsert(session: Session) {
        awaitTask(col().document(session.id).set(session.toMap()))
    }

    suspend fun delete(id: String) {
        awaitTask(col().document(id).delete())
    }

    private fun Session.toMap(): Map<String, Any?> = mapOf(
        "title" to title,
        "draftText" to draftText,
        "formattedText" to formattedText,
        "audioPath" to audioPath,
        "storagePath" to storagePath,
        "status" to status,
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
            status = getString("status") ?: "DONE",
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
