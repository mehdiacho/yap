package com.mehdiacho.yap.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.BuildConfig
import com.mehdiacho.yap.data.CostMeter
import com.mehdiacho.yap.data.CostMeterClient
import com.mehdiacho.yap.data.CostMeterFormat
import com.mehdiacho.yap.data.Insights
import com.mehdiacho.yap.data.PersonaClient
import com.mehdiacho.yap.data.UsageInsights
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapDims
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme
import com.mehdiacho.yap.ui.components.CostMeterCard
import com.mehdiacho.yap.ui.components.Pill
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How you use Yap, over time.
 *
 * Everything except the last card is computed on-device from notes already in Room, so the
 * screen is drawn before the first frame and needs no network. The persona is the one part
 * that calls out, and it is cached — see functions/src/persona.ts for what it is and is not
 * given.
 */
@Composable
fun InsightsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val scope = rememberCoroutineScope()
    val sessions by viewModel.allSessions.collectAsState()

    val insights = remember(sessions) { UsageInsights.compute(sessions) }
    var persona by remember { mutableStateOf(PersonaClient.lastKnown) }
    var personaLoading by remember { mutableStateOf(false) }
    var personaError by remember { mutableStateOf<String?>(null) }

    fun loadPersona(force: Boolean) {
        if (!insights.hasEnoughToShow) return
        scope.launch {
            personaLoading = true
            personaError = null
            try {
                persona = PersonaClient.fetch(insights, refresh = force)
            } catch (e: Exception) {
                personaError = e.message ?: "Couldn't write a summary right now."
            } finally { personaLoading = false }
        }
    }
    LaunchedEffect(insights.totalNotes) { if (persona == null) loadPersona(false) }

    // Spend meter: development builds only, and admin-only again on the server. Left null
    // when either gate says no, which is also what makes the card disappear rather than
    // announce itself to someone who cannot see it.
    var meter by remember { mutableStateOf<CostMeter?>(null) }
    var meterLoading by remember { mutableStateOf(false) }
    var meterError by remember { mutableStateOf<String?>(null) }
    var meterAllowed by remember { mutableStateOf(BuildConfig.DEBUG) }

    fun loadMeter() {
        if (!meterAllowed) return
        scope.launch {
            meterLoading = true
            meterError = null
            try {
                meter = CostMeterClient.fetch()
            } catch (e: CostMeterClient.NotPermitted) {
                meterAllowed = false
            } catch (e: Exception) {
                meterError = e.message ?: "Couldn't read the meter."
            } finally { meterLoading = false }
        }
    }
    LaunchedEffect(Unit) { loadMeter() }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text("Insights", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
        }

        if (!insights.hasEnoughToShow) {
            // Three notes produce a "busiest hour" and a "top topic" that are just those three
            // notes restated as findings. Say there isn't enough yet instead of inventing a
            // pattern out of nothing.
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Insights, null, tint = cs.outlineVariant,
                    modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(14.dp))
                Text("Not enough yet", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                    fontSize = 19.sp, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Record a few more notes and this page will show when you talk, how long " +
                        "for, and what you keep coming back to.",
                    fontSize = 14.sp, lineHeight = 21.sp, color = ext.subdued,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // ------------------------------------------------------------- headline stats
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    Modifier.weight(1f),
                    value = UsageInsights.durationLabel(insights.totalMs),
                    label = "recorded",
                )
                StatTile(
                    Modifier.weight(1f),
                    value = "${insights.totalNotes}",
                    label = if (insights.totalNotes == 1) "note" else "notes",
                )
                StatTile(
                    Modifier.weight(1f),
                    value = "${insights.activeDays}",
                    label = if (insights.activeDays == 1) "day" else "days",
                )
            }

            // -------------------------------------------------------------- month trend
            Spacer(Modifier.height(26.dp))
            SectionHeading("BY MONTH", "avg ${UsageInsights.durationLabel(insights.averagePerActiveMonthMs)}/mo")
            Spacer(Modifier.height(12.dp))
            MonthChart(insights)

            // --------------------------------------------------------------- note length
            Spacer(Modifier.height(26.dp))
            SectionHeading("HOW YOU TALK", null)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    Modifier.weight(1f),
                    value = UsageInsights.durationLabel(insights.averageNoteMs),
                    label = "average note",
                )
                StatTile(
                    Modifier.weight(1f),
                    value = UsageInsights.durationLabel(insights.longestNoteMs),
                    label = "longest",
                )
            }

            // ----------------------------------------------------------------- when
            Spacer(Modifier.height(26.dp))
            SectionHeading(
                "WHEN YOU RECORD",
                insights.busiestHour?.let { "peak ${UsageInsights.hourLabel(it)}" },
            )
            Spacer(Modifier.height(12.dp))
            HourStrip(insights)
            Spacer(Modifier.height(14.dp))
            WeekdayRow(insights)

            // ----------------------------------------------------------------- topics
            if (insights.topTopics.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                SectionHeading("WHAT YOU TALK ABOUT", null)
                Spacer(Modifier.height(10.dp))
                val busiest = insights.topTopics.first().count.coerceAtLeast(1)
                insights.topTopics.forEach { topic ->
                    TopicRow(topic.name, topic.count, topic.count.toFloat() / busiest)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ---------------------------------------------------------------- persona
            Spacer(Modifier.height(26.dp))
            SectionHeading("THE SHAPE OF IT", null)
            Spacer(Modifier.height(10.dp))
            PersonaCard(
                persona = persona,
                loading = personaLoading,
                error = personaError,
                onRefresh = { loadPersona(true) },
            )

            if (meterAllowed) {
                Spacer(Modifier.height(26.dp))
                SectionHeading("SPEND THIS MONTH", meter?.cycle)
                Spacer(Modifier.height(10.dp))
                CostMeterCard(
                    meter = meter,
                    loading = meterLoading,
                    error = meterError,
                    onRefresh = { loadMeter() },
                )
            }

            Spacer(Modifier.height(20.dp))
            insights.firstNoteAt?.let {
                Text(
                    "Since ${SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(it))}. " +
                        "Counted on this device from the notes you've kept.",
                    fontSize = 11.sp, lineHeight = 16.sp, color = ext.subdued,
                )
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun SectionHeading(label: String, trailing: String?) {
    val ext = YapTheme.extended
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
            color = ext.subdued, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier = Modifier, value: String, label: String) {
    val cs = MaterialTheme.colorScheme
    // Fixed height so the three tiles stay a row of equal boxes whatever lands in them —
    // "1h 12m" and "8" would otherwise size differently.
    Column(
        modifier.height(76.dp).clip(YapShapes.panel).background(cs.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            letterSpacing = (-0.3).sp, color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = YapTheme.extended.subdued,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Minutes per month.
 *
 * Bars, not a line: the months are discrete buckets, and a line implies a continuous quantity
 * moving between them. Idle months are drawn as empty slots rather than skipped, because the
 * gap is the most informative thing a usage chart can show.
 */
@Composable
private fun MonthChart(insights: Insights) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val peak = insights.months.maxOfOrNull { it.totalMs }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        insights.months.forEach { month ->
            val fraction = (month.totalMs.toFloat() / peak).coerceIn(0f, 1f)
            val grown by animateFloatAsState(fraction, tween(500), label = "bar-${month.key}")
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            // A month with a couple of minutes in it still gets a visible
                            // sliver; a zero month gets a flat mark on the axis, and the two
                            // must not look the same.
                            .fillMaxHeight((grown * 0.94f + 0.06f).coerceAtMost(1f))
                            .clip(YapShapes.xs)
                            .background(if (month.noteCount > 0) cs.primary else cs.surfaceContainerHigh),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(month.label, fontSize = 10.sp, color = ext.subdued, maxLines = 1)
            }
        }
    }
}

/** 24 slim columns. Reads as a daily rhythm at a glance without needing an axis. */
@Composable
private fun HourStrip(insights: Insights) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val peak = insights.hourHistogram.max().coerceAtLeast(1)
    Column {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            insights.hourHistogram.forEachIndexed { hour, count ->
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight((count.toFloat() / peak).coerceAtLeast(0.06f))
                        .clip(YapShapes.xs)
                        .background(
                            if (hour == insights.busiestHour) cs.primary
                            else if (count > 0) cs.primary.copy(alpha = 0.45f)
                            else cs.surfaceContainerHigh
                        ),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("12am", "6am", "12pm", "6pm").forEach {
                Text(it, fontSize = 9.sp, color = ext.subdued, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WeekdayRow(insights: Insights) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(YapDims.chipHeight),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        UsageInsights.WEEKDAY_LABELS.forEachIndexed { index, label ->
            val active = index == insights.busiestWeekday
            val any = insights.weekdayHistogram[index] > 0
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(YapShapes.md)
                    .background(
                        when {
                            active -> cs.primary
                            any -> cs.surfaceContainerHigh
                            else -> cs.surfaceContainerLow
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.take(1),
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        active -> cs.onPrimary
                        any -> cs.onSurfaceVariant
                        else -> cs.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun TopicRow(name: String, count: Int, fraction: Float) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(112.dp))
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(8.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.04f, 1f)).fillMaxHeight()
                    .clip(CircleShape).background(cs.primary),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ext.subdued,
            modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaClient.Persona?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Column(
        Modifier.fillMaxWidth().clip(YapShapes.panel)
            .border(1.dp, cs.outlineVariant, YapShapes.panel)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Read on your habits", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant)
            } else {
                Icon(Icons.Outlined.Refresh, "Rewrite", tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(17.dp).clickable { onRefresh() })
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            persona != null -> {
                Text(persona.text, fontSize = 14.sp, lineHeight = 22.sp, color = cs.onSurface)
                Spacer(Modifier.height(10.dp))
                // Says outright what the model was given. A paragraph about someone's habits
                // invites the question, and the answer is better volunteered than found out.
                Pill(
                    label = "From your numbers, not your notes",
                    background = cs.surfaceContainerHigh,
                    contentColor = ext.subdued,
                    bold = false,
                    maxWidth = 260.dp,
                )
            }
            loading -> Text("Reading your usage…", fontSize = 14.sp, color = ext.subdued)
            error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = cs.primary, modifier = Modifier.clickable { onRefresh() })
            }
            else -> Text("Tap refresh to have this written.", fontSize = 14.sp, color = ext.subdued)
        }
    }
}
