package com.mehdiacho.yap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Profile(
    val greenForrestId: String,
    val displayName: String?,
    val photoUrl: String?,
    val email: String?,
    val emailVerified: Boolean,
    val isGuest: Boolean,
    val memberSince: Long?,
) {
    /** First name for the home greeting, or null when we genuinely don't know it. */
    val firstName: String? get() = displayName?.trim()?.takeIf { it.isNotBlank() }?.substringBefore(' ')
}

/**
 * The account's identity: name, avatar, and Green Forrest ID.
 *
 * Cached to disk for the same reason the subscription page is — a profile screen that opens
 * as a spinner is a profile screen that feels broken, and none of this changes between
 * launches unless the user changes it themselves.
 */
object ProfileClient {
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }

    private const val PREFS = "yap_profile_cache"
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var cached: Profile? = null

    /** Last known profile. May be stale, never blocks. Null only before the first fetch ever. */
    val lastKnown: Profile? get() = cached

    fun initCache(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val id = p.getString("greenForrestId", null) ?: return
        cached = Profile(
            greenForrestId = id,
            displayName = p.getString("displayName", null),
            photoUrl = p.getString("photoUrl", null),
            email = p.getString("email", null),
            emailVerified = p.getBoolean("emailVerified", false),
            isGuest = p.getBoolean("isGuest", true),
            memberSince = p.getLong("memberSince", 0L).takeIf { it > 0L },
        )
    }

    private fun remember(profile: Profile) {
        cached = profile
        prefs?.edit()
            ?.putString("greenForrestId", profile.greenForrestId)
            ?.putString("displayName", profile.displayName)
            ?.putString("photoUrl", profile.photoUrl)
            ?.putString("email", profile.email)
            ?.putBoolean("emailVerified", profile.emailVerified)
            ?.putBoolean("isGuest", profile.isGuest)
            ?.putLong("memberSince", profile.memberSince ?: 0L)
            ?.apply()
    }

    /** Drops the cache on sign-out, so the next account doesn't briefly wear this one's face. */
    fun clearCache() {
        cached = null
        prefs?.edit()?.clear()?.apply()
    }

    suspend fun fetch(): Profile {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(functions.getHttpsCallable("getProfile").call())
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return Profile(
            greenForrestId = m["greenForrestId"] as? String ?: "",
            displayName = (m["displayName"] as? String)?.takeIf { it.isNotBlank() },
            photoUrl = (m["photoUrl"] as? String)?.takeIf { it.isNotBlank() },
            email = (m["email"] as? String)?.takeIf { it.isNotBlank() },
            emailVerified = m["emailVerified"] as? Boolean ?: false,
            isGuest = m["isGuest"] as? Boolean ?: false,
            memberSince = (m["memberSince"] as? Number)?.toLong(),
        ).also { remember(it) }
    }

    suspend fun setName(displayName: String): Profile {
        TranscriptionClient.ensureSignedIn()
        awaitTask(
            functions.getHttpsCallable("setProfileName")
                .call(hashMapOf("displayName" to displayName))
        )
        // The server writes the auth user; the local FirebaseUser is a snapshot from sign-in
        // time and won't know until it's told to re-read. Without this the greeting keeps
        // showing the old name until the app is restarted.
        reloadAuthUser()
        return fetch()
    }

    /**
     * Uploads an avatar and points the account at it.
     *
     * Re-encoded to a bounded JPEG first. A modern phone camera roll image is 3-12MB at
     * 4000px wide, and it is about to be displayed in a 44dp circle: uploading the original
     * would spend the user's data and their Storage quota on ~15000× more pixels than any
     * surface in the app can show.
     */
    suspend fun setPhoto(context: Context, source: Uri): Profile = withContext(Dispatchers.IO) {
        TranscriptionClient.ensureSignedIn()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val jpeg = downscaleToJpeg(context, source)
            ?: throw IllegalArgumentException("That image couldn't be read.")

        // Fixed filename, so replacing an avatar overwrites rather than accumulating every
        // photo the user ever tried. The cache-busting query on the download URL changes
        // anyway, so viewers still see the new one.
        val ref = FirebaseStorage.getInstance().reference.child("users/$uid/profile/avatar.jpg")
        awaitTask(ref.putBytes(jpeg))
        val url = awaitTask(ref.downloadUrl).toString()

        awaitTask(functions.getHttpsCallable("setProfilePhoto").call(hashMapOf("photoUrl" to url)))
        reloadAuthUser()
        fetch()
    }

    /** Removes the avatar. The Storage object goes too — an unreferenced blob is just cost. */
    suspend fun clearPhoto(): Profile {
        TranscriptionClient.ensureSignedIn()
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        awaitTask(functions.getHttpsCallable("setProfilePhoto").call(hashMapOf("photoUrl" to "")))
        runCatching {
            awaitTask(
                FirebaseStorage.getInstance().reference.child("users/$uid/profile/avatar.jpg").delete()
            )
        }
        reloadAuthUser()
        return fetch()
    }

    private const val AVATAR_MAX_PX = 512
    private const val AVATAR_QUALITY = 88

    private fun downscaleToJpeg(context: Context, uri: Uri): ByteArray? {
        // Two passes: measure with inJustDecodeBounds so a huge image never has to exist in
        // memory at full size, then decode straight to roughly the size we want.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= AVATAR_MAX_PX &&
            bounds.outHeight / (sample * 2) >= AVATAR_MAX_PX
        ) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > AVATAR_MAX_PX) {
            val ratio = AVATAR_MAX_PX.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, AVATAR_QUALITY, out)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }

    private suspend fun reloadAuthUser() {
        runCatching { FirebaseAuth.getInstance().currentUser?.let { awaitTask(it.reload()) } }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
