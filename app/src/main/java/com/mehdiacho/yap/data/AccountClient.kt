package com.mehdiacho.yap.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AccountStatus(
    val plan: String,
    val byokUnlocked: Boolean,
    /** A key is stored and in use — transcription runs on it and the plan cap doesn't apply. */
    val byokActive: Boolean,
    val byokKeyHint: String?,
    val expiresAt: Long?,
    val cycle: String,
    val secondsUsed: Long,
    val secondsCap: Long,
)

/** BYOK key state. The key itself is write-only server-side and never comes back. */
data class ByokStatus(
    val unlocked: Boolean,
    val active: Boolean,
    val provider: String?,
    val keyHint: String?,
    val updatedAt: Long?,
)

/** Plan + usage + promo redemption against the backend. */
object AccountClient {
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }

    // ----------------------------------------------------------------- cache
    //
    // Your plan and this cycle's usage change rarely and slowly. Waiting on a round trip
    // before drawing anything turned a settings row into a blank screen with a spinner —
    // the one screen in the app that did that. Keeping the last answer means the page is
    // whole the instant it opens and the numbers correct themselves a moment later.

    private const val PREFS = "yap_account_cache"
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var cached: AccountStatus? = null

    /** Last known status — may be stale, never blocks. Null only before the first fetch ever. */
    val lastKnown: AccountStatus? get() = cached

    fun initCache(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        if (!p.contains("plan")) return
        cached = AccountStatus(
            plan = p.getString("plan", "free")!!,
            byokUnlocked = p.getBoolean("byokUnlocked", false),
            byokActive = p.getBoolean("byokActive", false),
            byokKeyHint = p.getString("byokKeyHint", null),
            expiresAt = p.getLong("expiresAt", 0L).takeIf { it > 0L },
            cycle = p.getString("cycle", "")!!,
            secondsUsed = p.getLong("secondsUsed", 0L),
            secondsCap = p.getLong("secondsCap", 1800L),
        )
    }

    private fun remember(s: AccountStatus) {
        cached = s
        prefs?.edit()
            ?.putString("plan", s.plan)
            ?.putBoolean("byokUnlocked", s.byokUnlocked)
            ?.putBoolean("byokActive", s.byokActive)
            ?.putString("byokKeyHint", s.byokKeyHint)
            ?.putLong("expiresAt", s.expiresAt ?: 0L)
            ?.putString("cycle", s.cycle)
            ?.putLong("secondsUsed", s.secondsUsed)
            ?.putLong("secondsCap", s.secondsCap)
            ?.apply()
    }

    suspend fun status(): AccountStatus {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(functions.getHttpsCallable("getAccountStatus").call())
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return AccountStatus(
            plan = m["plan"] as? String ?: "free",
            byokUnlocked = m["byokUnlocked"] as? Boolean ?: false,
            byokActive = m["byokActive"] as? Boolean ?: false,
            byokKeyHint = m["byokKeyHint"] as? String,
            expiresAt = (m["expiresAt"] as? Number)?.toLong(),
            cycle = m["cycle"] as? String ?: "",
            secondsUsed = (m["secondsUsed"] as? Number)?.toLong() ?: 0L,
            secondsCap = (m["secondsCap"] as? Number)?.toLong() ?: 1800L,
        ).also { remember(it) }
    }

    /** Current BYOK state for the key screen. */
    suspend fun byokStatus(): ByokStatus {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(functions.getHttpsCallable("getByokStatus").call())
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return ByokStatus(
            unlocked = m["unlocked"] as? Boolean ?: false,
            active = m["active"] as? Boolean ?: false,
            provider = m["provider"] as? String,
            keyHint = m["keyHint"] as? String,
            updatedAt = (m["updatedAt"] as? Number)?.toLong(),
        )
    }

    /**
     * Validate and store the user's own provider key. The server proves the key works by
     * making a real (tiny) request before saving, so a success here means transcription
     * will actually run on it — not merely that the string looked plausible.
     *
     * Throws with a human-readable message on a rejected or unfunded key.
     * Returns the last-4 hint; the key itself is never readable again.
     */
    suspend fun setByokKey(apiKey: String, provider: String = "gemini"): String {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(
            functions.getHttpsCallable("setByokKey")
                .withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .call(hashMapOf("apiKey" to apiKey, "provider" to provider))
        )
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return m["keyHint"] as? String ?: apiKey.takeLast(4)
    }

    /** Remove the stored key — transcription falls back to the built-in one. */
    suspend fun clearByokKey() {
        TranscriptionClient.ensureSignedIn()
        awaitTask(functions.getHttpsCallable("clearByokKey").call())
    }

    /** Throws with a human-readable message on invalid/ineligible codes. */
    suspend fun redeem(code: String) {
        TranscriptionClient.ensureSignedIn()
        awaitTask(functions.getHttpsCallable("redeemPromo").call(hashMapOf("code" to code)))
    }

    data class ReferralInfo(val code: String, val qualifiedCount: Int, val needed: Int)

    /** The caller's referral code (server-created on first call) + progress toward BYOK. */
    suspend fun referralInfo(): ReferralInfo {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(functions.getHttpsCallable("getReferralInfo").call())
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return ReferralInfo(
            code = m["code"] as? String ?: "",
            qualifiedCount = (m["qualifiedCount"] as? Number)?.toInt() ?: 0,
            needed = (m["needed"] as? Number)?.toInt() ?: 5,
        )
    }

    /** Applies a friend's referral code to this (new) account. */
    suspend fun applyReferral(code: String) {
        TranscriptionClient.ensureSignedIn()
        awaitTask(functions.getHttpsCallable("applyReferral").call(hashMapOf("code" to code)))
    }

    /** Irreversibly deletes the account server-side (data + storage + auth user). */
    suspend fun deleteAccount() {
        TranscriptionClient.ensureSignedIn()
        awaitTask(functions.getHttpsCallable("deleteAccount").call())
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
