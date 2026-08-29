package com.mehdiacho.yap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meter's job is to be believed against a real invoice, so the failures worth pinning are
 * the ones that leave it looking authoritative and saying something false: bars on mismatched
 * scales, a verdict computed from a stale invoice, real spend rounded away to "$0.00".
 */
class CostMeterFormatTest {

    private fun meter(
        projected: Double = 1.0,
        actual: Double? = null,
        asOf: Long? = null,
    ) = CostMeter(
        cycle = "2026-08",
        projectedUsd = projected,
        calls = 12,
        audioMinutes = 4.0,
        perOp = emptyList(),
        actualUsd = actual,
        actualCurrency = "USD",
        actualAsOf = asOf,
        byService = emptyList(),
        actualReason = null,
        unpricedModels = emptyList(),
    )

    private val now = 1_800_000_000_000L
    private fun hoursAgo(h: Long) = now - h * 3_600_000

    // -------------------------------------------------------------------------- money

    @Test
    fun `fractions of a cent stay visible`() {
        // A development month spends thousandths of a dollar. Two decimals would render the
        // whole meter as "$0.00", which reads as "nothing is happening".
        assertEquals("$0.0048", CostMeterFormat.money(0.004811))
    }

    @Test
    fun `ordinary amounts read as money`() {
        assertEquals("$4.81", CostMeterFormat.money(4.811))
        assertEquals("$0", CostMeterFormat.money(0.0))
    }

    @Test
    fun `large amounts drop the noise digits`() {
        assertEquals("$1,204", CostMeterFormat.money(1204.37))
    }

    // --------------------------------------------------------------------------- bars

    @Test
    fun `both bars are drawn on one scale`() {
        // The whole claim of the card is that these two lengths are comparable.
        val (p, a) = CostMeterFormat.barFractions(meter(projected = 2.0, actual = 4.0))
        assertEquals(0.5f, p, 1e-6f)
        assertEquals(1.0f, a, 1e-6f)
    }

    @Test
    fun `an unknown invoice draws no bar rather than a full one`() {
        val (p, a) = CostMeterFormat.barFractions(meter(projected = 2.0, actual = null))
        assertEquals(1.0f, p, 1e-6f)
        assertEquals(0f, a, 1e-6f)
    }

    @Test
    fun `an empty month does not divide by zero`() {
        val (p, a) = CostMeterFormat.barFractions(meter(projected = 0.0, actual = null))
        assertEquals(0f, p, 1e-6f)
        assertEquals(0f, a, 1e-6f)
    }

    // ------------------------------------------------------------------------ verdict

    @Test
    fun `the verdict names the multiple we are out by`() {
        val m = meter(projected = 2.0, actual = 5.0)
        assertEquals("2.50x over projection", CostMeterFormat.ratioLabel(m))
    }

    @Test
    fun `an estimate that lands is said to land`() {
        assertEquals("matches projection", CostMeterFormat.ratioLabel(meter(2.0, 2.02)))
    }

    @Test
    fun `over-estimating is reported too`() {
        assertEquals("0.50x of projection", CostMeterFormat.ratioLabel(meter(4.0, 2.0)))
    }

    @Test
    fun `no invoice means no verdict`() {
        assertNull(CostMeterFormat.ratioLabel(meter(projected = 2.0, actual = null)))
    }

    @Test
    fun `a zero projection yields no verdict instead of an infinity`() {
        assertNull(CostMeterFormat.ratioLabel(meter(projected = 0.0, actual = 5.0)))
    }

    // ---------------------------------------------------------------------- freshness

    @Test
    fun `a recent export is reported in minutes then hours then days`() {
        assertEquals("as of 30m ago", CostMeterFormat.freshnessLabel(now - 30 * 60_000, now))
        assertEquals("as of 5h ago", CostMeterFormat.freshnessLabel(hoursAgo(5), now))
        assertEquals("as of 3d ago", CostMeterFormat.freshnessLabel(hoursAgo(72), now))
    }

    @Test
    fun `a missing export timestamp says so plainly`() {
        assertEquals("nothing exported yet", CostMeterFormat.freshnessLabel(null, now))
    }

    @Test
    fun `a fresh invoice is comparable`() {
        assertFalse(CostMeterFormat.isStale(meter(2.0, 4.0, hoursAgo(6)), now))
    }

    @Test
    fun `a day-and-a-half-old invoice is not comparable to a live projection`() {
        // Past this the gap between the bars is mostly export lag, and reading it as
        // under-estimation is the exact mistake this meter exists to prevent.
        assertTrue(CostMeterFormat.isStale(meter(2.0, 4.0, hoursAgo(40)), now))
    }

    @Test
    fun `an invoice figure with no timestamp counts as stale`() {
        assertTrue(CostMeterFormat.isStale(meter(2.0, 4.0, asOf = null), now))
    }

    @Test
    fun `an absent invoice is not called stale`() {
        // Nothing to be stale about — the card explains the missing export instead.
        assertFalse(CostMeterFormat.isStale(meter(2.0, actual = null), now))
    }

    // ------------------------------------------------------------------------- tokens

    @Test
    fun `token counts stay short enough for a narrow row`() {
        assertEquals("512", CostMeterFormat.tokens(512))
        assertEquals("1.9k", CostMeterFormat.tokens(1_920))
        assertEquals("2.4M", CostMeterFormat.tokens(2_400_000))
    }
}
