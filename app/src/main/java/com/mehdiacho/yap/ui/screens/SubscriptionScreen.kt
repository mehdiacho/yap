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
fun SubscriptionScreen(onBack: () -> Unit) {
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

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(16.dp))
            // A failed refresh is a strip above the page, not a replacement for it. The plans
            // and whatever usage we last knew are still true and still worth showing.
            loadError?.let {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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

            // Plan cards — design copy and pricing.
            PlanCard(
                name = "Pro", price = "$1.99", priceSuffix = "/mo · $19.99/yr",
                desc = "30 hrs/mo refine · 2 GB · AI actions · priority",
                current = status?.plan == "pro",
                onSelect = { purchasing = com.mehdiacho.yap.data.BillingManager.PRO_MONTHLY to "Pro · $1.99/mo" },
            )
            Spacer(Modifier.height(12.dp))
            PlanCard(
                name = "Free", price = "$0", priceSuffix = null,
                desc = "30 min/mo refine · 50 MB · unlimited on-device",
                current = status?.plan == "free",
                onSelect = {},
            )
            Spacer(Modifier.height(12.dp))
            PlanCard(
                name = "BYOK + Storage", price = "$0.99", priceSuffix = "/mo",
                desc = "Unlimited AI (own key) · 2 GB sync",
                current = status?.plan == "byok",
                onSelect = { purchasing = com.mehdiacho.yap.data.BillingManager.BYOK_STORAGE_MONTHLY to "BYOK + Storage · $0.99/mo" },
            )

            // Redeem a code — live feature (quota promos), kept below the plans.
            Spacer(Modifier.height(22.dp))
            Text("REDEEM A CODE", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp, color = ext.subdued)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code, onValueChange = { code = it.uppercase(); redeemError = null },
                    placeholder = { Text("Promo code") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
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
                                Toast.makeText(context, "Code applied 🎉", Toast.LENGTH_SHORT).show()
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

    // Purchase bottom sheet — drives from BillingManager.purchasePhase:
    // Initiating (Play sheet up) → Verifying (server check) → tick "Purchase confirmed",
    // or ✕ "Purchase failed" with Try again. User cancel in the Play sheet auto-dismisses.
    val purchase = purchasing
    if (purchase != null) {
        val (productId, label) = purchase
        val billing = com.mehdiacho.yap.data.BillingManager
        val phase by billing.purchasePhase.collectAsState()
        fun close() { purchasing = null; billing.resetPurchasePhase() }

        ModalBottomSheet(
            onDismissRequest = { close() },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = cs.surface,
        ) {
            fun start() {
                val activity = context as? android.app.Activity ?: return close()
                billing.launchPurchase(activity, productId) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    close()
                }
            }
            LaunchedEffect(productId) {
                delay(600) // let the sheet settle before the Play sheet appears over it
                start()
            }
            // Backing out of the Play sheet quietly closes ours; success lingers then closes.
            LaunchedEffect(phase) {
                when (phase) {
                    is com.mehdiacho.yap.data.BillingManager.PurchasePhase.Cancelled -> close()
                    is com.mehdiacho.yap.data.BillingManager.PurchasePhase.Confirmed -> { delay(1800); close() }
                    else -> {}
                }
            }

            val failed = phase as? com.mehdiacho.yap.data.BillingManager.PurchasePhase.Failed
            val confirmed = phase is com.mehdiacho.yap.data.BillingManager.PurchasePhase.Confirmed
            val verifying = phase is com.mehdiacho.yap.data.BillingManager.PurchasePhase.Verifying
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 44.dp, top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Spinner morphs into a tick (success) or ✕ (failure).
                val okGreen = Color(0xFF34A853)
                androidx.compose.animation.AnimatedContent(
                    targetState = when { confirmed -> 1; failed != null -> 2; else -> 0 },
                    transitionSpec = {
                        (androidx.compose.animation.scaleIn(
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
                            initialScale = 0.4f,
                        ) + androidx.compose.animation.fadeIn()) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                    },
                    label = "purchaseIcon",
                ) { state ->
                    when (state) {
                        1 -> Box(
                            Modifier.size(64.dp).clip(CircleShape).background(okGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Check, null, tint = okGreen, modifier = Modifier.size(36.dp)) }
                        2 -> Box(
                            Modifier.size(64.dp).clip(CircleShape).background(cs.errorContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Close, null, tint = cs.error, modifier = Modifier.size(34.dp)) }
                        else -> CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 4.dp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    when {
                        confirmed -> "Purchase confirmed"
                        failed != null -> "Purchase failed"
                        verifying -> "Confirming purchase…"
                        else -> "Initiating purchase…"
                    },
                    fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = cs.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    failed?.message ?: "$label · via Google Play",
                    fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                if (failed != null) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { start() },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Try again", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { close() }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Close", fontWeight = FontWeight.SemiBold)
                    }
                }
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

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(14.dp)) {
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
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
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
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1024L * 1024 -> "%.0f MB".format(b / (1024.0 * 1024))
    else -> "%.0f KB".format(b / 1024.0)
}

/** §3.12 plan card — current plan gets 2dp Primary border, tinted bg, CURRENT badge. */
@Composable
private fun PlanCard(
    name: String,
    price: String,
    priceSuffix: String?,
    desc: String,
    current: Boolean,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (current) cs.surfaceContainerLow else cs.surface)
            .border(if (current) 2.dp else 1.dp, if (current) cs.primary else cs.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(enabled = !current) { onSelect() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                modifier = Modifier.weight(1f))
            if (current) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(cs.primary)
                    .padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text("CURRENT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp, color = cs.onPrimary)
                }
            }
        }
        Text(desc, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 7.dp, bottom = 10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(price, fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = cs.onSurface)
            if (priceSuffix != null) {
                Spacer(Modifier.width(4.dp))
                Text(priceSuffix, fontSize = 12.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
