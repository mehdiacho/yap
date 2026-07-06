package com.mehdiacho.yap.data

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Sums the user's cloud audio bytes (users/{uid}/audio) for the Subscription storage meter. */
object StorageUsage {

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext 0L
        val dir = FirebaseStorage.getInstance().reference.child("users/$uid/audio")
        val listing = runCatching { await(dir.listAll()) }.getOrNull() ?: return@withContext 0L
        var sum = 0L
        for (item in listing.items) {
            sum += runCatching { await(item.metadata).sizeBytes }.getOrDefault(0L)
        }
        sum
    }

    private suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
