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
import com.mehdiacho.yap.data.AccountClient
import com.mehdiacho.yap.data.BillingManager
import com.mehdiacho.yap.ui.components.Pill
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One purchasable plan. [productId] null means Free — nothing to buy. */
private data class Plan(
    val key: String,
    val name: String,
    val price: String,
    val suffix: String?,
    val desc: String,
    val productId: String?,
    val entitlement: String,
)

/**
 * Plans — choose, then subscribe.
 *
 * Split out of Subscription because those are two different questions. "What am I on and how
 * much have I used" is something you check; "which plan do I want" is something you decide.
 * Tapping a plan card used to launch Google Play's payment sheet immediately, which turns a
 * glance into a purchase flow with no step in between to change your mind. Selection and
 * commitment are now separate taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended

    var status by remember { mutableStateOf(AccountClient.lastKnown) }
    var refreshing by remember { mutableStateOf(false) }
    var yearly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var purchasing by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun reload() {
        scope.launch {
            refreshing = true
            try { status = AccountClient.status() } catch (_: Exception) {
            } finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val plans = remember(yearly) {
        listOf(
            Plan(
                key = "pro",
                name = if (yearly) "Pro" else "Pro",
                price = if (yearly) "$19.99" else "$1.99",
                suffix = if (yearly) "/year" else "/month",
                desc = "30 hrs a month of cloud refine · 2 GB storage · AI actions · priority queue",
                productId = if (yearly) BillingManager.PRO_YEARLY else BillingManager.PRO_MONTHLY,
                entitlement = "pro",
            ),
            Plan(
                key = "byok",
                name = "Bring your own key",
                price = "$0.99",
                suffix = "/month",
                desc = "Unlimited refine on your own Gemini key · 2 GB storage",
                productId = BillingManager.BYOK_STORAGE_MONTHLY,
                entitlement = "byok",
            ),
            Plan(
                key = "free",
                name = "Free",
                price = "$0",
                suffix = null,
                desc = "30 min a month of cloud refine · 50 MB storage · unlimited on-device",
                productId = null,
                entitlement = "free",
            ),
        )
    }
    val chosen = plans.firstOrNull { it.key == selected }
    val currentPlan = status?.plan

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text("Choose a plan", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant)
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { reload() },
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().height(42.dp).clip(YapShapes.lg)
                        .background(cs.surfaceContainer).padding(4.dp)
                ) {
                    listOf(false to "Monthly", true to "Yearly").forEach { (isYear, label) ->
                        val on = yearly == isYear
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(YapShapes.md)
                                .background(if (on) cs.surface else Color.Transparent)
                                .clickable { yearly = isYear },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) cs.onSurface else cs.onSurfaceVariant)
                        }
                    }
                }
                if (yearly) {
                    Spacer(Modifier.height(8.dp))
                    Text("Two months free compared with paying monthly.",
                        fontSize = 12.sp, color = ext.subdued)
                }

                Spacer(Modifier.height(14.dp))
                plans.forEach { plan ->
                    SelectablePlanCard(
                        name = plan.name,
                        price = plan.price,
                        suffix = plan.suffix,
                        desc = plan.desc,
                        current = currentPlan == plan.entitlement,
                        selected = selected == plan.key,
                        onSelect = { selected = if (selected == plan.key) null else plan.key },
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Spacer(Modifier.height(90.dp)) // clear the docked bar
            }
        }

        // Docked commit bar. Disabled until a plan is picked, so the button always refers to
        // something specific rather than being a generic "subscribe".
        val canBuy = chosen?.productId != null && currentPlan != chosen.entitlement
        Column(
            Modifier.fillMaxWidth().background(cs.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = {
                    val p = chosen ?: return@Button
                    val id = p.productId ?: return@Button
                    purchasing = id to "${p.name} · ${p.price}${p.suffix ?: ""}"
                },
                enabled = canBuy,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    when {
                        chosen == null -> "Select a plan"
                        currentPlan == chosen.entitlement -> "You're on this plan"
                        chosen.productId == null -> "Free is your fallback plan"
                        else -> "Subscribe to ${chosen.name}"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Billed through Google Play. Cancel any time from the Play Store.",
                fontSize = 11.sp, color = ext.subdued, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    val purchase = purchasing
    if (purchase != null) {
        PurchaseSheet(
            productId = purchase.first,
            label = purchase.second,
            onConfirmed = { reload() },
            onClose = { purchasing = null },
        )
    }
}

/** Plan card — tap to select. Current plan is labelled and never offered for purchase. */
@Composable
private fun SelectablePlanCard(
    name: String,
    price: String,
    suffix: String?,
    desc: String,
    current: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val shape = YapShapes.panel
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.4f) else cs.surfaceContainer)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .then(
                    if (selected) Modifier.background(cs.primary)
                    else Modifier.border(2.dp, cs.outlineVariant, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Default.Check, null, tint = cs.onPrimary,
                modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    color = cs.onSurface)
                if (current) {
                    Spacer(Modifier.width(8.dp))
                    Pill(
                        label = "Current",
                        background = ext.refinedContainer,
                        contentColor = ext.onRefinedContainer,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(price, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    color = cs.onSurface)
                suffix?.let {
                    Text(it, fontSize = 12.sp, color = ext.subdued,
                        modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * The Google Play purchase flow, from our side of the glass.
 *
 * Drives entirely off [BillingManager.purchasePhase]: Initiating (Play sheet up) → Verifying
 * (our server grants the entitlement) → confirmed, or a failure the user can act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseSheet(
    productId: String,
    label: String,
    onConfirmed: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val billing = BillingManager
    val phase by billing.purchasePhase.collectAsState()
    fun close() { onClose(); billing.resetPurchasePhase() }

    ModalBottomSheet(
        onDismissRequest = { close() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = cs.surface,
    ) {
        fun start() {
            val activity = context as? android.app.Activity ?: return close()
            billing.launchPurchase(activity, productId) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                close()
            }
        }
        LaunchedEffect(productId) {
            delay(600) // let the sheet settle before Play's sheet appears over it
            start()
        }
        LaunchedEffect(phase) {
            when (phase) {
                is BillingManager.PurchasePhase.Cancelled -> close()
                is BillingManager.PurchasePhase.Confirmed -> {
                    onConfirmed()
                    delay(1800)
                    close()
                }
                else -> {}
            }
        }

        val failed = phase as? BillingManager.PurchasePhase.Failed
        val confirmed = phase is BillingManager.PurchasePhase.Confirmed
        val verifying = phase is BillingManager.PurchasePhase.Verifying
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 44.dp, top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                    confirmed -> "You're all set"
                    failed != null -> "Purchase didn't complete"
                    verifying -> "Activating your plan…"
                    else -> "Opening Google Play…"
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
                // Once Play has taken the payment, relaunching the buy flow can only return
                // ITEM_ALREADY_OWNED and land straight back here. Retry the step that failed.
                Button(
                    onClick = { if (failed.paid) billing.retryVerification(productId) else start() },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(if (failed.paid) "Retry activation" else "Try again",
                        fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { close() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Close", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
