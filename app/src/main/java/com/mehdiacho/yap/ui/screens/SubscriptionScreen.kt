package com.mehdiacho.yap.ui.screens

import android.widget.Toast
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.data.AccountClient
import com.mehdiacho.yap.data.AccountStatus
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Subscription · plans + usage (design section 10). Payments are stubbed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(onBack: () -> Unit, onOpenPlans: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    // Seeded from the last known values so the page is whole on arrival. Only a first-ever
    // open (no cache) has nothing to draw, and that case gets placeholders rather than an
    // empty screen — the shape of the page never depends on the network.
    var status by remember { mutableStateOf(AccountClient.lastKnown) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var redeemError by remember { mutableStateOf<String?>(null) }
    var purchasing by remember { mutableStateOf<Pair<String, String>?>(null) } // productId to label

    fun reload() {
        scope.launch {
            loadError = null
            refreshing = true
            try { status = AccountClient.status() } catch (e: Exception) {
                // Stale numbers beat no numbers: keep showing what we had and say it's stale.
                loadError = e.message ?: "Couldn't load your plan"
            } finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text("Subscription", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            // Where the wait belongs: a quiet marker in the chrome, while the page itself
            // stays readable underneath.
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            }
        }

        // Pull to refresh — this page's numbers change underneath you (a purchase lands, a
        // cycle rolls over), and the only way to re-read them used to be leaving the screen
        // and coming back.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { reload() },
            modifier = Modifier.weight(1f),
        ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            // A failed refresh is a strip above the page, not a replacement for it. The plans
            // and whatever usage we last knew are still true and still worth showing.
            loadError?.let {
                Row(
                    Modifier.fillMaxWidth().clip(YapShapes.lg)
                        .background(cs.errorContainer).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (status == null) it else "$it Showing your last known usage.",
                        color = cs.onErrorContainer, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retry", color = cs.onErrorContainer, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clickable { reload() })
                }
                Spacer(Modifier.height(12.dp))
            }

            UsagePanel(status)
            Spacer(Modifier.height(16.dp))

            CurrentPlanRow(status?.plan, onOpenPlans)

            // Redeem a code — live feature (quota promos).
            Spacer(Modifier.height(22.dp))
            Text("REDEEM A CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, color = ext.subdued)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase(); redeemError = null },
                    placeholder = { Text("Promo code") }, singleLine = true,
                    shape = YapShapes.card, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    enabled = code.isNotBlank() && !redeeming,
                    shape = RoundedCornerShape(50),
                    onClick = {
                        scope.launch {
                            redeeming = true; redeemError = null
                            try {
                                AccountClient.redeem(code.trim())
                                Toast.makeText(context, "Code applied", Toast.LENGTH_SHORT).show()
                                code = ""; reload()
                            } catch (e: Exception) {
                                redeemError = e.message ?: "Couldn't redeem that code"
                            } finally { redeeming = false }
                        }
                    },
                ) { Text(if (redeeming) "…" else "Apply") }
            }
            redeemError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = cs.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
        }
    }
}

/**
 * Usage panel — "THIS CYCLE · RESETS <date>" + Cloud refine / Storage meters (§3.12).
 *
 * Takes a nullable status so the panel can be drawn before the numbers arrive: the layout is
 * identical either way and only the figures fill in. That keeps the page from reflowing under
 * the reader, which is the real cost of swapping a whole screen out for a spinner.
 */
@Composable
private fun UsagePanel(s: AccountStatus?) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    // Real cloud-storage usage, summed from users/{uid}/audio metadata.
    var storageBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { storageBytes = com.mehdiacho.yap.data.StorageUsage.totalBytes() }
    val storageCap = if (s?.plan == "free") 50L * 1024 * 1024 else 2L * 1024 * 1024 * 1024
    val storageLabel = when (val b = storageBytes) {
        null -> "… / ${fmtBytes(storageCap)}"
        else -> "${fmtBytes(b)} / ${fmtBytes(storageCap)}"
    }
    val resetLabel = remember {
        val cal = Calendar.getInstance().apply {
            add(Calendar.MONTH, 1); set(Calendar.DAY_OF_MONTH, 1)
        }
        SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time).uppercase()
    }
    val capLabel = if (s == null) "…" else {
        val usedMin = s.secondsUsed / 60.0
        val capMin = (s.secondsCap / 60).coerceAtLeast(1)
        if (capMin >= 60) "%.1f / %d hrs".format(usedMin / 60.0, capMin / 60)
        else "%.1f / %d min".format(usedMin, capMin)
    }

    Column(Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainer).padding(14.dp)) {
        Text("THIS CYCLE · RESETS $resetLabel", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, color = ext.subdued)
        Spacer(Modifier.height(10.dp))
        Row {
            Text("Cloud refine", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(capLabel, fontSize = 12.sp, color = ext.subdued)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { if (s == null) 0f else (s.secondsUsed.toFloat() / s.secondsCap).coerceIn(0f, 1f) },
            trackColor = cs.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Text("Storage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(storageLabel, fontSize = 12.sp, color = ext.subdued)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { ((storageBytes ?: 0L).toFloat() / storageCap).coerceIn(0f, 1f) },
            color = cs.tertiary, trackColor = cs.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(YapShapes.xs),
        )
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.0f MB".format(b / (1024.0 * 1024))
    else -> "%.0f KB".format(b / 1024.0)
}

/**
 * What you're on now, and the way to change it.
 *
 * The plan cards used to sit directly on this screen and start a Google Play payment the
 * instant one was tapped. Checking your usage and committing to a purchase are different
 * intentions and shouldn't share a surface.
 */
@Composable
private fun CurrentPlanRow(plan: String?, onOpenPlans: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val label = when (plan) {
        "pro" -> "Pro"
        "byok" -> "Bring your own key"
        "free" -> "Free"
        null -> "…"
        else -> plan.replaceFirstChar { it.uppercase() }
    }
    Column(
        Modifier.fillMaxWidth().clip(YapShapes.panel)
            .background(cs.surfaceContainer).padding(16.dp)
    ) {
        Text("YOUR PLAN", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, color = ext.subdued)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                color = cs.onSurface, modifier = Modifier.weight(1f))
            Button(onClick = onOpenPlans, shape = RoundedCornerShape(50)) {
                Text(if (plan == "free" || plan == null) "See plans" else "Change plan",
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
