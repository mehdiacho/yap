package com.mehdiacho.yap.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What the library says about how someone actually uses Yap.
 *
 * Computed on the client from the notes already in Room, not fetched. Every field here is
 * arithmetic over data the device is holding anyway, so the screen is instant and works with
 * no signal — and asking a server to count your own notes back to you would be slower, cost a
 * round trip, and stop working the moment you're on a train.
 *
 * The one thing NOT computed here is the persona, because that needs a model. It arrives
 * separately, and this object is what gets sent to produce it.
 */
data class MonthTotal(
    /** "2026-08". Sorts lexicographically, which is the order we want. */
    val key: String,
    val label: String,
    val totalMs: Long,
    val noteCount: Int,
)

data class TopicCount(val name: String, val count: Int, val totalMs: Long)

data class Insights(
    val totalNotes: Int,
    val totalMs: Long,
    /** Newest last, one entry per calendar month, with idle months in between filled in. */
    val months: List<MonthTotal>,
    /** Mean across months that had activity — an idle month shouldn't drag the average down. */
    val averagePerActiveMonthMs: Long,
    val averageNoteMs: Long,
    val longestNoteMs: Long,
    val topTopics: List<TopicCount>,
    /** 24 buckets, index = hour of day in the device's zone. */
    val hourHistogram: List<Int>,
    /** 7 buckets, index 0 = Monday. */
    val weekdayHistogram: List<Int>,
    val busiestHour: Int?,
    val busiestWeekday: Int?,
    /** Distinct days with at least one note — the honest denominator for "how often". */
    val activeDays: Int,
    val firstNoteAt: Long?,
) {
    /**
     * Below this there is nothing to see. Two notes produce a "busiest hour" and a "top topic"
     * that are simply restatements of those two notes, dressed up as a finding.
     */
    val hasEnoughToShow: Boolean get() = totalNotes >= 3
}

object UsageInsights {

    private val MONTH_LABELS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** How many months the chart shows. Past this the bars are too thin to compare. */
    private const val MAX_MONTHS = 12
    private const val MAX_TOPICS = 6

    /** A note's length is its own clip plus everything appended to it afterwards. */
    private fun lengthOf(s: Session): Long =
        s.durationMs + s.extraRecordings.sumOf { it.durationMs }

    /**
     * @param now injected so the month window can be tested without freezing the clock.
     */
    fun compute(
        sessions: List<Session>,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): Insights {
        // Insights are about what you kept. A trashed note still exists for 30 days, and
        // counting it would mean deleting a note doesn't change your numbers.
        val kept = sessions.filter { it.trashedAt == null }
        if (kept.isEmpty()) return empty()

        val cal = Calendar.getInstance(zone, Locale.US)
        val hours = IntArray(24)
        val weekdays = IntArray(7)
        val byMonth = linkedMapOf<String, MutableList<Session>>()
        val days = mutableSetOf<String>()

        kept.forEach { s ->
            cal.timeInMillis = s.createdAt
            hours[cal.get(Calendar.HOUR_OF_DAY)]++
            // Calendar.SUNDAY is 1 and MONDAY is 2, so this shifts to a Monday-first index.
            weekdays[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
            byMonth.getOrPut(monthKey(cal)) { mutableListOf() } += s
            days += "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        }

        val months = buildMonthSeries(byMonth, now, zone)
        val activeMonths = months.count { it.noteCount > 0 }
        val totalMs = kept.sumOf { lengthOf(it) }

        // Topic first, category as the fallback: "General" is the enrichment's way of saying
        // it found no theme, and a chart whose biggest bar is "General" has said nothing.
        val labelled = kept.map { s ->
            val label = s.topicCluster.takeIf { it.isNotBlank() && it != "General" }
                ?: s.category.takeIf { it.isNotBlank() }
                ?: "Uncategorised"
            label to s
        }
        val topics = labelled.groupBy({ it.first }, { it.second })
            .map { (name, group) -> TopicCount(name, group.size, group.sumOf { lengthOf(it) }) }
            .sortedWith(compareByDescending<TopicCount> { it.count }.thenBy { it.name })
            .take(MAX_TOPICS)

        return Insights(
            totalNotes = kept.size,
            totalMs = totalMs,
            months = months,
            averagePerActiveMonthMs = if (activeMonths > 0) totalMs / activeMonths else 0,
            averageNoteMs = totalMs / kept.size,
            longestNoteMs = kept.maxOf { lengthOf(it) },
            topTopics = topics,
            hourHistogram = hours.toList(),
            weekdayHistogram = weekdays.toList(),
            busiestHour = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
            busiestWeekday = weekdays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
            activeDays = days.size,
            firstNoteAt = kept.minOf { it.createdAt },
        )
    }

    private fun empty() = Insights(
        totalNotes = 0, totalMs = 0, months = emptyList(),
        averagePerActiveMonthMs = 0, averageNoteMs = 0, longestNoteMs = 0,
        topTopics = emptyList(), hourHistogram = List(24) { 0 },
        weekdayHistogram = List(7) { 0 }, busiestHour = null, busiestWeekday = null,
        activeDays = 0, firstNoteAt = null,
    )

    /**
     * The month series, with idle months in the middle filled in.
     *
     * The gaps matter: a chart that silently skips March puts February next to April, which
     * makes a two-month break look like steady use — the exact opposite of what a trend chart
     * is for.
     */
    private fun buildMonthSeries(
        byMonth: Map<String, List<Session>>,
        now: Long,
        zone: TimeZone,
    ): List<MonthTotal> {
        if (byMonth.isEmpty()) return emptyList()
        val cal = Calendar.getInstance(zone, Locale.US)
        cal.timeInMillis = now
        val series = mutableListOf<MonthTotal>()
        val earliest = byMonth.keys.min()

        // Walk back from this month, stopping at the first note's month or the cap.
        repeat(MAX_MONTHS) {
            val key = monthKey(cal)
            val group = byMonth[key].orEmpty()
            series += MonthTotal(
                key = key,
                label = MONTH_LABELS[cal.get(Calendar.MONTH)],
                totalMs = group.sumOf { lengthOf(it) },
                noteCount = group.size,
            )
            if (key <= earliest) return series.reversed()
            cal.add(Calendar.MONTH, -1)
        }
        return series.reversed()
    }

    private fun monthKey(cal: Calendar): String =
        "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

    /** "7am", "2pm" — the shape people say out loud, not "14:00". */
    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12am"
        hour < 12 -> "${hour}am"
        hour == 12 -> "12pm"
        else -> "${hour - 12}pm"
    }

    /** Rounds to the unit that reads: seconds under a minute, then minutes, then hours. */
    fun durationLabel(ms: Long): String {
        val totalSeconds = ms / 1000
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> "${totalSeconds / 60}m"
            else -> {
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
            }
        }
    }

    /**
     * The compact summary the persona model gets.
     *
     * Numbers and topic names only — never note text. The persona is a description of
     * behaviour, and sending the contents of someone's notes to produce it would be a much
     * larger disclosure than the feature needs or than anyone agreed to.
     */
    fun personaSummary(insights: Insights): Map<String, Any> = mapOf(
        "totalNotes" to insights.totalNotes,
        "activeDays" to insights.activeDays,
        "totalMinutes" to insights.totalMs / 60_000,
        "averageNoteSeconds" to insights.averageNoteMs / 1000,
        "averageMonthlyMinutes" to insights.averagePerActiveMonthMs / 60_000,
        "busiestHour" to (insights.busiestHour ?: -1),
        "busiestWeekday" to (insights.busiestWeekday?.let { WEEKDAY_LABELS[it] } ?: ""),
        "topTopics" to insights.topTopics.map { mapOf("name" to it.name, "count" to it.count) },
        "monthlyMinutes" to insights.months.map {
            mapOf("month" to it.key, "minutes" to it.totalMs / 60_000, "notes" to it.noteCount)
        },
    )
}
