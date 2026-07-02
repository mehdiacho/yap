package com.mehdiacho.yap.data

import android.content.Context
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Local cache for cloud-only audio (OneDrive model): downloaded on demand, then persists
 * so reopening a note doesn't re-download. Cleared only via Settings ("Clear downloads").
 * Distinct from audioPath originals (device-born / available-offline), which are never
 * touched by cache clearing.
 */
object AudioCache {
    private fun dir(context: Context) = File(context.filesDir, "audiocache").apply { mkdirs() }

    fun fileFor(context: Context, storagePath: String): File =
        File(dir(context), storagePath.substringAfterLast('/'))

    fun isCached(context: Context, storagePath: String?): Boolean =
        storagePath != null && fileFor(context, storagePath).let { it.exists() && it.length() > 0 }

    /** Download (or reuse) the cached copy of a cloud recording. */
    suspend fun download(context: Context, storagePath: String): File {
        val target = fileFor(context, storagePath)
        if (target.exists() && target.length() > 0) return target
        val tmp = File(target.parentFile, target.name + ".part")
        suspendCancellableCoroutine { cont ->
            FirebaseStorage.getInstance().reference.child(storagePath).getFile(tmp)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        tmp.renameTo(target)
        return target
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes all downloaded (online-only) copies; returns bytes freed. */
    fun clear(context: Context): Long {
        val freed = totalBytes(context)
        dir(context).listFiles()?.forEach { it.delete() }
        return freed
    }
}
