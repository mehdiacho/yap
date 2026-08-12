package com.mehdiacho.yap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Insights are numbers presented as facts about the user, so a quiet arithmetic bug here
 * doesn't look like a bug — it looks like a claim about their life that happens to be wrong.
 * These cover the boundaries: trashed notes, appended clips, month gaps, and the empty case.
 */
class UsageInsightsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long {
        val cal = Calendar.getInstance(utc, Locale.US)
        cal.clear()
        cal.set(year, month - 1, day, hour, 0, 0)
        return cal.timeInMillis
    }

    private fun session(
        id: String,
        createdAt: Long,
        durationMs: Long = 60_000,
        topic: String = "General",
        category: String = "Memo",
        trashed: Long? = null,
        extras: List<ExtraRecording> = emptyList(),
    ) = Session(
        id = id, title = id, draftText = "", createdAt = createdAt, durationMs = durationMs,
        topicCluster = topic, category = category, trashedAt = trashed, extraRecordings = extras,
    )

    @Test
    fun `empty library yields empty insights rather than dividing by zero`() {
        val out = UsageInsights.compute(emptyList(), now = at(2026, 8, 12), zone = utc)
        assertEquals(0, out.totalNotes)
        assertEquals(0L, out.averageNoteMs)
        assertNull(out.busiestHour)
        assertFalse(out.hasEnoughToShow)
    }

    @Test
    fun `trashed notes are excluded from every figure`() {
        val out = UsageInsights.compute(
            listOf(
                session("a", at(2026, 8, 1), 60_000),
                session("b", at(2026, 8, 2), 900_000, trashed = at(2026, 8, 3)),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(1, out.totalNotes)
        assertEquals(60_000L, out.totalMs)
        assertEquals(60_000L, out.longestNoteMs)
    }

    @Test
    fun `appended clips count towards a note's length`() {
        val out = UsageInsights.compute(
            listOf(
                session(
                    "a", at(2026, 8, 1), 60_000,
                    extras = listOf(ExtraRecording(durationMs = 30_000), ExtraRecording(durationMs = 10_000)),
                ),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(100_000L, out.totalMs)
        assertEquals(100_000L, out.longestNoteMs)
    }

    @Test
    fun `idle months in the middle are kept as gaps`() {
        val out = UsageInsights.compute(
            listOf(session("a", at(2026, 5, 4)), session("b", at(2026, 8, 4))),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(listOf("2026-05", "2026-06", "2026-07", "2026-08"), out.months.map { it.key })
        assertEquals(listOf(1, 0, 0, 1), out.months.map { it.noteCount })
    }

    @Test
    fun `monthly average ignores the idle months`() {
        // Two active months at 60s each. Averaging over the four-month span would say 30s,
        // which describes a person who doesn't exist.
        val out = UsageInsights.compute(
            listOf(session("a", at(2026, 5, 4), 60_000), session("b", at(2026, 8, 4), 60_000)),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(60_000L, out.averagePerActiveMonthMs)
    }

    @Test
    fun `busiest hour and weekday are found`() {
        // 2026-08-03 is a Monday.
        val out = UsageInsights.compute(
            listOf(
                session("a", at(2026, 8, 3, hour = 7)),
                session("b", at(2026, 8, 10, hour = 7)),
                session("c", at(2026, 8, 4, hour = 15)),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(7, out.busiestHour)
        assertEquals(0, out.busiestWeekday) // Monday
    }

    @Test
    fun `topics prefer the cluster and fall back to the category`() {
        val out = UsageInsights.compute(
            listOf(
                session("a", at(2026, 8, 1), topic = "Pricing"),
                session("b", at(2026, 8, 2), topic = "Pricing"),
                session("c", at(2026, 8, 3), topic = "General", category = "Standup"),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(listOf("Pricing", "Standup"), out.topTopics.map { it.name })
        assertEquals(listOf(2, 1), out.topTopics.map { it.count })
    }

    @Test
    fun `active days counts distinct days not notes`() {
        val out = UsageInsights.compute(
            listOf(
                session("a", at(2026, 8, 3, hour = 8)),
                session("b", at(2026, 8, 3, hour = 20)),
                session("c", at(2026, 8, 5)),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        assertEquals(3, out.totalNotes)
        assertEquals(2, out.activeDays)
    }

    @Test
    fun `the persona summary carries no note text`() {
        val out = UsageInsights.compute(
            listOf(
                session("a", at(2026, 8, 1), topic = "Pricing").copy(
                    draftText = "SECRET BODY", formattedText = "SECRET BODY", title = "SECRET TITLE",
                ),
                session("b", at(2026, 8, 2)),
                session("c", at(2026, 8, 3)),
            ),
            now = at(2026, 8, 12), zone = utc,
        )
        val serialised = UsageInsights.personaSummary(out).toString()
        assertFalse(serialised.contains("SECRET"))
        assertTrue(serialised.contains("Pricing")) // topic labels do travel, by design
    }

    @Test
    fun `duration labels pick a readable unit`() {
        assertEquals("45s", UsageInsights.durationLabel(45_000))
        assertEquals("3m", UsageInsights.durationLabel(200_000))
        assertEquals("2h", UsageInsights.durationLabel(7_200_000))
        assertEquals("1h 5m", UsageInsights.durationLabel(3_900_000))
    }

    @Test
    fun `hour labels read as people say them`() {
        assertEquals("12am", UsageInsights.hourLabel(0))
        assertEquals("7am", UsageInsights.hourLabel(7))
        assertEquals("12pm", UsageInsights.hourLabel(12))
        assertEquals("9pm", UsageInsights.hourLabel(21))
    }
}
