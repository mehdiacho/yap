package com.mehdiacho.yap.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The written read on someone's usage habits.
 *
 * Cached on both sides. The server caches to avoid spending a model call to re-derive the same
 * paragraph from numbers that barely moved; the client caches so the Insights screen shows it
 * immediately rather than opening with a spinner in the last card.
 */
object PersonaClient {
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }

    data class Persona(val text: String, val generatedAt: Long)

    private const val PREFS = "yap_persona_cache"
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var cached: Persona? = null

    val lastKnown: Persona? get() = cached

    fun initCache(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val text = p.getString("text", null) ?: return
        cached = Persona(text, p.getLong("generatedAt", 0L))
    }

    /** Dropped on sign-out with the rest of the profile: it describes a person, not a device. */
    fun clearCache() {
        cached = null
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * @param refresh rewrite even if the server has a cached one. Only ever set from the
     *   explicit refresh control — never automatically, or the cache buys nothing.
     */
    suspend fun fetch(insights: Insights, refresh: Boolean = false): Persona {
        TranscriptionClient.ensureSignedIn()
        val payload = hashMapOf<String, Any>(
            "summary" to UsageInsights.personaSummary(insights),
            "refresh" to refresh,
        )
        val res = awaitTask(
            functions.getHttpsCallable("getUsagePersona")
                .withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .call(payload)
        )
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        val text = (m["persona"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) throw IllegalStateException("Couldn't write a summary right now.")
        val persona = Persona(text, (m["generatedAt"] as? Number)?.toLong() ?: System.currentTimeMillis())
        cached = persona
        prefs?.edit()
            ?.putString("text", persona.text)
            ?.putLong("generatedAt", persona.generatedAt)
            ?.apply()
        return persona
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
