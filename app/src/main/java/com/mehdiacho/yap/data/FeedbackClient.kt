package com.mehdiacho.yap.data

import android.os.Build
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.tasks.await

/** What a piece of feedback is about. [id] is what the backend stores; [label] is what's shown. */
data class FeedbackTopic(val id: String, val label: String)

/**
 * Sends in-app feedback.
 *
 * The voice message is the part worth protecting: it goes to Cloud Storage first and the note
 * only mentions its path, so a long recording never has to fit inside a callable payload, and
 * a failed submit leaves the audio where a retry can still find it.
 */
object FeedbackClient {

    /**
     * Offered topics, in the order shown.
     *
     * "Something else" is last and always present. A fixed list without an escape hatch
     * quietly discards whatever the author didn't anticipate, which is precisely the feedback
     * worth having.
     */
    val topics = listOf(
        FeedbackTopic("bug", "Something's broken"),
        FeedbackTopic("transcription-quality", "Transcription quality"),
        FeedbackTopic("feature-request", "I wish it could…"),
        FeedbackTopic("billing", "Billing or plans"),
        FeedbackTopic("performance", "Speed or battery"),
        FeedbackTopic("design", "How it looks or feels"),
        FeedbackTopic("other", "Something else"),
    )

    private fun functions() = FirebaseFunctions.getInstance("africa-south1")

    /**
     * Upload a recorded message and return its Storage path.
     *
     * Lives under `users/{uid}/feedback/` so the existing per-user Storage rule covers it —
     * the author can reach their own recording and nobody else can.
     */
    private suspend fun uploadVoice(file: File): String {
        val uid = AuthManager.currentUser?.uid ?: error("Sign in to send feedback.")
        val path = "users/$uid/feedback/${UUID.randomUUID()}.m4a"
        FirebaseStorage.getInstance().reference.child(path).putFile(
            android.net.Uri.fromFile(file)
        ).await()
        return path
    }

    /**
     * Submit one report. [voice] is optional; either it or [message] must carry something,
     * which the backend enforces too.
     */
    suspend fun submit(
        topicId: String,
        message: String,
        voice: File? = null,
        voiceDurationMs: Long = 0L,
    ) {
        val voicePath = voice?.takeIf { it.exists() && it.length() > 0 }?.let { uploadVoice(it) }
        val payload = hashMapOf<String, Any>(
            "topic" to topicId,
            "message" to message,
            "platform" to "android",
            "appVersion" to com.mehdiacho.yap.BuildConfig.VERSION_NAME,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "osVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        voicePath?.let {
            payload["voicePath"] = it
            payload["voiceDurationMs"] = voiceDurationMs
        }
        functions().getHttpsCallable("submitFeedback").call(payload).await()
    }
}
