package com.mehdiacho.yap.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AccountStatus(
    val plan: String,
    val byokUnlocked: Boolean,
    val expiresAt: Long?,
    val cycle: String,
    val secondsUsed: Long,
    val secondsCap: Long,
)

/** Plan + usage + promo redemption against the backend. */
object AccountClient {
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }

    suspend fun status(): AccountStatus {
        TranscriptionClient.ensureSignedIn()
        val res = awaitTask(functions.getHttpsCallable("getAccountStatus").call())
        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()
        return AccountStatus(
            plan = m["plan"] as? String ?: "free",
            byokUnlocked = m["byokUnlocked"] as? Boolean ?: false,
            expiresAt = (m["expiresAt"] as? Number)?.toLong(),
            cycle = m["cycle"] as? String ?: "",
            secondsUsed = (m["secondsUsed"] as? Number)?.toLong() ?: 0L,
            secondsCap = (m["secondsCap"] as? Number)?.toLong() ?: 1800L,
        )
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
