package com.mehdiacho.yap.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.CostMeter
import com.mehdiacho.yap.data.CostMeterFormat
import com.mehdiacho.yap.data.UsageInsights
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme

/**
 * What this month is costing, and whether we saw it coming.
 *
 * Two bars on one shared scale. Scaling them independently would let both fill their track,
 * which is the one thing this card must never do — the entire claim it makes is that these two
 * lengths are comparable.
 *
 * The invoice bar is allowed to be absent rather than zero. Billing export stays off until
 * someone switches it on, and a zero-length "Google" bar beside a full "our estimate" one reads
 * as "we are wildly over-estimating" when it means "nobody has enabled the export". Same reason
 * the freshness line is not optional: the export lags by hours, and a live projection next to a
 * stale invoice always shows a gap that is mostly the lag. When the invoice figure is too old
 * to compare, the verdict pill is withheld rather than computed from mismatched windows.
 */
@Composable
fun CostMeterCard(
    meter: CostMeter?,
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
            Text(
                "Gemini API spend", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurface, modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    Modifier.size(15.dp), strokeWidth = 2.dp, color = cs.onSurfaceVariant,
                )
            } else {
                Icon(
                    Icons.Outlined.Refresh, "Refresh", tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(17.dp).clickable { onRefresh() },
                )
            }
        }

        if (meter == null) {
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Text("Reading the meter…", fontSize = 14.sp, color = ext.subdued)
                error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error, fontSize = 13.sp, lineHeight = 19.sp,
                        color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = cs.primary, modifier = Modifier.clickable { onRefresh() },
                    )
                }
                else -> Text("Tap refresh to read it.", fontSize = 14.sp, color = ext.subdued)
            }
            return@Column
        }

        val fractions = CostMeterFormat.barFractions(meter)
        val stale = CostMeterFormat.isStale(meter)

        // ------------------------------------------------------------------ live gauge
        Spacer(Modifier.height(4.dp))
        Text(
            "${meter.calls} model ${if (meter.calls == 1) "call" else "calls"} · " +
                UsageInsights.durationLabel((meter.audioMinutes * 60_000).toLong()) + " of audio",
            fontSize = 11.sp, color = ext.subdued,
        )

        // ------------------------------------------------------------------------ bars
        Spacer(Modifier.height(14.dp))
        MeterBar(
            label = "Our estimate",
            value = CostMeterFormat.money(meter.projectedUsd),
            fraction = fractions.first,
            color = cs.primary,
        )
        Spacer(Modifier.height(10.dp))
        MeterBar(
            label = "Google billing",
            value = meter.actualUsd?.let { CostMeterFormat.money(it) } ?: "—",
            fraction = fractions.second,
            color = if (stale) cs.outline else cs.tertiary,
        )

        // The line that keeps the comparison honest.
        Spacer(Modifier.height(6.dp))
        Text(
            if (meter.actualUsd != null) {
                CostMeterFormat.freshnessLabel(meter.actualAsOf) +
                    if (stale) " — too far behind to compare" else ""
            } else {
                meter.actualReason ?: "No billing figure available."
            },
            fontSize = 11.sp, lineHeight = 16.sp,
            color = if (stale) cs.error else ext.subdued,
        )

        // --------------------------------------------------------------------- verdict
        val verdict = CostMeterFormat.ratioLabel(meter)?.takeIf { !stale }
        if (verdict != null) {
            Spacer(Modifier.height(12.dp))
            Pill(
                label = verdict,
                background = cs.surfaceContainerHigh,
                contentColor = cs.onSurface,
                bold = true,
                maxWidth = 260.dp,
            )
        }

        // ------------------------------------------------------------------- breakdown
        if (meter.perOp.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "WHERE IT GOES", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, color = ext.subdued,
            )
            Spacer(Modifier.height(8.dp))
            val biggest = meter.perOp.maxOf { it.usd }.coerceAtLeast(1e-9)
            meter.perOp.forEach { op ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    Text(
                        CostMeterFormat.opLabel(op), fontSize = 12.sp, color = cs.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(104.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f).height(6.dp).clip(CircleShape)
                            .background(cs.surfaceContainerHigh),
                    ) {
                        Box(
                            Modifier.fillMaxWidth((op.usd / biggest).toFloat().coerceIn(0.02f, 1f))
                                .fillMaxHeight().clip(CircleShape).background(cs.primary),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        CostMeterFormat.money(op.usd), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = ext.subdued,
                        maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(58.dp),
                    )
                }
                Text(
                    "${op.calls} calls · ${CostMeterFormat.tokens(op.audioIn)} audio · " +
                        "${CostMeterFormat.tokens(op.textIn)} in · " +
                        "${CostMeterFormat.tokens(op.out)} out",
                    fontSize = 10.sp, color = ext.subdued,
                    modifier = Modifier.padding(start = 112.dp),
                )
            }
        }

        // What Google billed us for beyond the model calls — the answer to "is Gemini even the
        // expensive part?", which on a project this size it frequently is not.
        if (meter.byService.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "BILLED SERVICES", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, color = ext.subdued,
            )
            Spacer(Modifier.height(6.dp))
            meter.byService.forEach { svc ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        svc.service, fontSize = 11.sp, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(CostMeterFormat.money(svc.usd), fontSize = 11.sp, color = ext.subdued)
                }
            }
        }

        // Spend we know happened and cannot value. Saying so beats quietly reading low.
        if (meter.unpricedModels.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "No price on file for ${meter.unpricedModels.joinToString(", ")} — " +
                    "their calls are counted but valued at zero.",
                fontSize = 11.sp, lineHeight = 16.sp, color = cs.error,
            )
        }
    }
}

@Composable
private fun MeterBar(label: String, value: String, fraction: Float, color: Color) {
    val cs = MaterialTheme.colorScheme
    val width by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(420), label = "meterBar")
    Column {
        Row {
            Text(
                label, fontSize = 11.sp, color = YapTheme.extended.subdued,
                modifier = Modifier.weight(1f),
            )
            Text(
                value, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)
                .background(cs.surfaceContainerHigh),
        ) {
            Box(Modifier.fillMaxWidth(width).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}
