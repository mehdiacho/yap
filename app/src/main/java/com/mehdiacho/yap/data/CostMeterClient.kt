package com.mehdiacho.yap.data

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches the development build's spend meter.
 *
 * Whole-project spend is admin-only on the server, so an ordinary account gets
 * PERMISSION_DENIED here. That is the expected answer rather than a fault, and it is reported
 * as [NotPermitted] so the screen can leave the card out entirely instead of showing everyone
 * a red error about a feature that was never theirs.
 */
object CostMeterClient {
    private val functions by lazy { FirebaseFunctions.getInstance("africa-south1") }

    object NotPermitted : Exception("Not an admin account.")

    suspend fun fetch(cycle: String? = null): CostMeter {
        TranscriptionClient.ensureSignedIn()
        val payload = hashMapOf<String, Any>()
        if (cycle != null) payload["cycle"] = cycle
        val res = try {
            awaitTask(
                functions.getHttpsCallable("getCostMeter")
                    .withTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .call(payload)
            )
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED) throw NotPermitted
            throw e
        }

        @Suppress("UNCHECKED_CAST")
        val m = res.getData() as? Map<String, Any?> ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val projected = m["projected"] as? Map<String, Any?> ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val actual = m["actual"] as? Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val perOpRaw = projected["perOp"] as? List<Map<String, Any?>> ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val servicesRaw = actual?.get("byService") as? List<Map<String, Any?>> ?: emptyList()

        return CostMeter(
            cycle = m["cycle"] as? String ?: "",
            projectedUsd = num(projected["usd"]),
            calls = num(projected["calls"]).toInt(),
            audioMinutes = num(projected["audioMinutes"]),
            perOp = perOpRaw.map {
                OpCost(
                    op = it["op"] as? String ?: "?",
                    model = it["model"] as? String ?: "?",
                    usd = num(it["usd"]),
                    calls = num(it["calls"]).toInt(),
                    textIn = num(it["textIn"]).toLong(),
                    audioIn = num(it["audioIn"]).toLong(),
                    out = num(it["out"]).toLong(),
                )
            },
            // Null, not zero. "We don't know" and "it cost nothing" look identical on a bar
            // chart and mean opposite things.
            actualUsd = actual?.get("usd")?.let { num(it) },
            actualCurrency = actual?.get("currency") as? String ?: "USD",
            actualAsOf = (actual?.get("asOf") as? Number)?.toLong(),
            byService = servicesRaw.map {
                ServiceCost(it["service"] as? String ?: "?", num(it["cost"]))
            },
            actualReason = m["actualReason"] as? String,
            unpricedModels = (m["unpricedModels"] as? List<*>)?.mapNotNull { it as? String }
                ?: emptyList(),
        )
    }

    private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
