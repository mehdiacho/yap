package com.mehdiacho.yap.data

import java.util.Locale

/**
 * The numbers behind the development build's spend meter, and the arithmetic for showing them.
 *
 * Kept apart from the network call for the usual reason — this is where the display bugs live,
 * and they are testable only if nothing here needs Firebase. The bugs worth guarding against
 * are the ones that make the meter *look* fine while saying something false: two bars drawn on
 * their own scales, a lagging invoice compared against a live projection, a real cost of four
 * tenths of a cent rounded to "$0.00".
 */

data class OpCost(
    val op: String,
    val model: String,
    val usd: Double,
    val calls: Int,
    val textIn: Long,
    val audioIn: Long,
    val out: Long,
)

data class ServiceCost(val service: String, val usd: Double)

data class CostMeter(
    val cycle: String,
    /** What our own token accounting says this month has cost so far. */
    val projectedUsd: Double,
    val calls: Int,
    val audioMinutes: Double,
    val perOp: List<OpCost>,
    /** What Google's billing export says. Null when the export isn't configured or readable. */
    val actualUsd: Double?,
    val actualCurrency: String,
    /** Epoch ms of the newest exported row. Null when nothing has been exported yet. */
    val actualAsOf: Long?,
    val byService: List<ServiceCost>,
    /** Why `actualUsd` is null, in words, so the card never shows a bare unexplained blank. */
    val actualReason: String?,
    /** Models the server met but had no price for — spend we know happened and can't value. */
    val unpricedModels: List<String>,
)

object CostMeterFormat {

    /**
     * Money, at whatever precision makes the number legible.
     *
     * A development project spends fractions of a cent per note, and a plain two-decimal
     * format renders the entire meter as "$0.00" — which reads as "nothing is happening"
     * rather than "this is a small number". Locale is pinned to US because the string is
     * already hard-coded to a dollar sign; letting the device choose gives "$0,0481".
     */
    fun money(usd: Double): String = when {
        usd <= 0.0 -> "$0"
        usd >= 100 -> String.format(Locale.US, "$%,.0f", usd)
        usd >= 1 -> String.format(Locale.US, "$%.2f", usd)
        else -> String.format(Locale.US, "$%.4f", usd)
    }

    /**
     * The headline: how far off our estimate is.
     *
     * Null when there is nothing honest to compare — no invoice figure, or a projection of
     * zero, which would divide out to an infinity dressed up as a finding.
     */
    fun ratio(meter: CostMeter): Double? {
        val actual = meter.actualUsd ?: return null
        if (meter.projectedUsd <= 0.0) return null
        return actual / meter.projectedUsd
    }

    fun ratioLabel(meter: CostMeter): String? {
        val r = ratio(meter) ?: return null
        return when {
            r >= 1.05 -> String.format(Locale.US, "%.2fx over projection", r)
            r <= 0.95 -> String.format(Locale.US, "%.2fx of projection", r)
            else -> "matches projection"
        }
    }

    /**
     * Bar lengths for the projected and actual figures, on ONE shared scale.
     *
     * Two bars scaled independently both fill their track and say nothing at all — the entire
     * reason to draw them next to each other is that their lengths are comparable. When the
     * invoice is unknown its bar is zero-length rather than absent, so the row keeps its shape.
     */
    fun barFractions(meter: CostMeter): Pair<Float, Float> {
        val actual = meter.actualUsd ?: 0.0
        val max = maxOf(meter.projectedUsd, actual)
        if (max <= 0.0) return 0f to 0f
        return (meter.projectedUsd / max).toFloat() to (actual / max).toFloat()
    }

    /** Beyond this the invoice figure is too old to be compared against a live projection. */
    private const val STALE_AFTER_MS = 36L * 3_600_000

    fun isStale(meter: CostMeter, now: Long = System.currentTimeMillis()): Boolean {
        val asOf = meter.actualAsOf ?: return meter.actualUsd != null
        return now - asOf > STALE_AFTER_MS
    }

    /**
     * How far behind the invoice figure is.
     *
     * This is the label that stops the meter from lying. Billing export lags by hours; a live
     * projection held against a stale actual always shows the projection ahead, and without
     * this line that gap reads as "we are under-estimating" when it means "the last few hours
     * are not exported yet".
     */
    fun freshnessLabel(asOf: Long?, now: Long = System.currentTimeMillis()): String {
        if (asOf == null) return "nothing exported yet"
        val ms = (now - asOf).coerceAtLeast(0)
        val minutes = ms / 60_000
        val hours = ms / 3_600_000
        val days = ms / 86_400_000
        return when {
            minutes < 90 -> "as of ${minutes}m ago"
            hours < 48 -> "as of ${hours}h ago"
            else -> "as of ${days}d ago"
        }
    }

    /** "transcribe" reads better than "transcribe" jammed against a model id in a narrow row. */
    fun opLabel(op: OpCost): String = op.op

    /** Compact token count for the breakdown rows: 1920 -> "1.9k", 2_400_000 -> "2.4M". */
    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
