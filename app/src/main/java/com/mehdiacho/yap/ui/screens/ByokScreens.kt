package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.AccountClient
import com.mehdiacho.yap.data.AuthManager
// (referral info + deleteAccount both go through AccountClient)
import com.mehdiacho.yap.ui.components.Pill
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

// =====================================================================================
// BYOK — Unlock (pay-once / referral) and API-key screens (design section 10).
// Key persistence, referral counting, and purchases are stubs → functionality list.
// =====================================================================================

/** Unlock BYOK — "Pay once $4.99" card + "Or invite 5 friends" referral card. */
@Composable
fun UnlockByokScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun stub(what: String) =
        android.widget.Toast.makeText(context, "$what coming soon", android.widget.Toast.LENGTH_SHORT).show()

    // Real referral progress + server-issued code (getReferralInfo).
    var referral by remember { mutableStateOf<AccountClient.ReferralInfo?>(null) }
    LaunchedEffect(Unit) {
        referral = runCatching { AccountClient.referralInfo() }.getOrNull()
    }
    val invited = referral?.qualifiedCount ?: 0
    val needed = referral?.needed ?: 5
    val refCode = referral?.code
        ?: (AuthManager.currentUser?.uid ?: "YOU").filter { it.isLetterOrDigit() }.take(6).uppercase()
    // A real link now. It used to be "yap.app/r/CODE" — a domain nobody owns, pointing at
    // nothing, so every invite ever sent was a dead end. See data/InstallReferral.kt.
    val refLink = com.mehdiacho.yap.data.InstallReferral.inviteLink(refCode)
    var enteredCode by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text("Unlock BYOK", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))

            // Intro — key tile + copy
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(60.dp).clip(YapShapes.panel).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Key, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Use your own AI key for unlimited refines. Unlock it one of two ways:",
                    fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))

            // Pay once card
            Column(
                Modifier.fillMaxWidth().clip(YapShapes.panel)
                    .background(cs.surfaceContainerLow)
                    .border(2.dp, cs.primary, YapShapes.panel).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pay once", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        modifier = Modifier.weight(1f))
                    Text("$4.99", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = cs.primary)
                }
                Text("One-time · unlock forever", fontSize = 12.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val activity = context as? android.app.Activity ?: return@Button
                        com.mehdiacho.yap.data.BillingManager.launchPurchase(
                            activity, com.mehdiacho.yap.data.BillingManager.BYOK_UNLOCK
                        ) { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Unlock for $4.99", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            // Referral card
            Column(
                Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surface)
                    .border(1.dp, cs.outlineVariant, YapShapes.panel).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Or invite $needed friends", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text("$invited / $needed", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
                Spacer(Modifier.height(10.dp))
                // Progress dots — one segment per needed referral.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(needed) { i ->
                        Box(
                            Modifier.weight(1f).height(8.dp).clip(CircleShape)
                                .background(if (i < invited) cs.primary else cs.surfaceVariant)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Referral-link chip with copy.
                Row(
                    Modifier.fillMaxWidth().clip(YapShapes.lg).background(cs.surfaceContainer)
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(refLink))
                            android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The code, not the URL: the full Play link with its encoded referrer is
                    // far too long to read, and the code is the part worth recognising.
                    Text(com.mehdiacho.yap.data.InstallReferral.inviteLabel(refCode),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                        color = cs.onSurface, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ContentCopy, "Copy link", tint = cs.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT,
                                "Catch every idea before it slips away. Try Yap: $refLink")
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Share invite"))
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Outlined.IosShare, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share invite", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Counts when a friend signs up and saves 3 notes, or subscribes.",
                    fontSize = 11.sp, lineHeight = 16.sp, color = ext.subdued)
            }
            Spacer(Modifier.height(14.dp))

            // Someone who installed the app first, then heard about the code, has no way in
            // through the install referrer — it was already read (and empty) on their first
            // launch. This is that path, and it's also the only one that works for a sideload.
            Column(
                Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surface)
                    .border(1.dp, cs.outlineVariant, YapShapes.panel).padding(16.dp)
            ) {
                Text("Were you invited?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Enter a friend's code to credit them. Only works in your first week.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = ext.subdued)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = enteredCode,
                        onValueChange = { enteredCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) },
                        placeholder = { Text("CODE") },
                        singleLine = true, shape = YapShapes.card,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                applying = true
                                applyResult = try {
                                    AccountClient.applyReferral(enteredCode)
                                    enteredCode = ""
                                    "Thanks — your friend has been credited."
                                } catch (e: Exception) {
                                    e.message ?: "That code didn't work."
                                } finally { applying = false }
                            }
                        },
                        enabled = !applying && enteredCode.length >= 4,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.height(52.dp),
                    ) { Text("Apply", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
                applyResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** API key screen — locked hero, or provider chips + key validation when unlocked. */
@Composable
fun ByokKeyScreen(onBack: () -> Unit, onOpenUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<com.mehdiacho.yap.data.ByokStatus?>(null) }
    LaunchedEffect(Unit) {
        status = runCatching { AccountClient.byokStatus() }
            .getOrDefault(com.mehdiacho.yap.data.ByokStatus(false, false, null, null, null))
    }
    val unlocked = status?.unlocked

    when (unlocked) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> {
            // BYOK · locked
            Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                        modifier = Modifier.clickable { onBack() })
                    Spacer(Modifier.width(14.dp))
                    Text("API key", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        letterSpacing = (-0.3).sp, color = cs.onSurface)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(84.dp).clip(YapShapes.xl).background(ext.warningSoft),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, null, tint = ext.onOfflineContainer, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Bring your own key", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                        fontSize = 21.sp, color = cs.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Text("Unlock BYOK to use your own AI provider for unlimited refines.",
                        fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Button(onClick = onOpenUnlock, shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text("Unlock for $4.99", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenUnlock, shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(54.dp)) {
                        Text("Invite friends", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(38.dp))
                }
            }
        }
        true -> {
            // BYOK · unlocked + key validation
            var key by remember { mutableStateOf("") }
            // empty | saving | saved | invalid.
            // Validation is an explicit action, not a per-keystroke debounce: proving a key
            // works means making a real request against it, and firing that on every
            // character would hammer the provider (and the user's own bill) while they type.
            var state by remember { mutableStateOf(if (status?.active == true) "saved" else "empty") }
            var savedHint by remember { mutableStateOf(status?.keyHint) }
            var error by remember { mutableStateOf<String?>(null) }
            val context = androidx.compose.ui.platform.LocalContext.current

            Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                        modifier = Modifier.clickable { onBack() })
                    Spacer(Modifier.width(14.dp))
                    Text("API key", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        letterSpacing = (-0.3).sp, color = cs.onSurface)
                    Spacer(Modifier.weight(1f))
                    Pill(
                        label = "Unlocked",
                        background = ext.refinedContainer,
                        contentColor = ext.onRefinedContainer,
                        leading = {
                            Icon(Icons.Outlined.LockOpen, null, tint = ext.onRefinedContainer,
                                modifier = Modifier.size(13.dp))
                        },
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("Provider", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Gemini — selected/filled
                        Row(
                            Modifier.clip(YapShapes.md).background(cs.primary)
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Gemini", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimary)
                        }
                        ProviderSoonChip("OpenAI")
                        ProviderSoonChip("Anthropic")
                    }
                    Spacer(Modifier.height(16.dp))

                    Text(
                        if (state == "saved") "Replace your Gemini key" else "Paste your Gemini key",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    val borderColor = when (state) {
                        "saved" -> cs.tertiary
                        "invalid" -> cs.error
                        "saving" -> cs.primary
                        else -> cs.outlineVariant
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(YapShapes.card).background(cs.surface)
                            .border(2.dp, borderColor, YapShapes.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (key.isEmpty()) Text(
                                if (state == "saved") "AIza••••${savedHint ?: ""}" else "AIza…",
                                fontSize = 14.sp, color = ext.subdued,
                            )
                            BasicTextField(
                                value = key,
                                onValueChange = {
                                    key = it
                                    error = null
                                    if (state == "invalid") state = "empty"
                                },
                                enabled = state != "saving",
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp, color = cs.onSurface, letterSpacing = 0.3.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        when (state) {
                            "saved" -> Icon(Icons.Default.CheckCircle, null, tint = cs.tertiary, modifier = Modifier.size(22.dp))
                            "invalid" -> Icon(Icons.Default.Error, null, tint = cs.error, modifier = Modifier.size(22.dp))
                            "saving" -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val candidate = key.trim()
                            if (candidate.isBlank()) return@Button
                            state = "saving"
                            error = null
                            scope.launch {
                                runCatching { AccountClient.setByokKey(candidate) }
                                    .onSuccess { hint ->
                                        savedHint = hint
                                        key = ""
                                        state = "saved"
                                    }
                                    .onFailure { e ->
                                        state = "invalid"
                                        // The server distinguishes "wrong key" from "right key,
                                        // empty account"; surface whichever it decided.
                                        error = e.message?.takeIf { it.isNotBlank() }
                                            ?: "Couldn't verify that key. Check it and try again."
                                    }
                            }
                        },
                        enabled = key.isNotBlank() && state != "saving",
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                    ) {
                        Text(
                            if (state == "saving") "Verifying…" else "Verify & save",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                    }

                    // Inline validation caption
                    Spacer(Modifier.height(8.dp))
                    when (state) {
                        "saved" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = cs.tertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Valid key · connected to Gemini", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = cs.tertiary)
                        }
                        "invalid" -> Text(error ?: "That key doesn't look right. Check it and try again.",
                            fontSize = 12.sp, lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.error)
                        "saving" -> Text("Checking it against Gemini…", fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.primary)
                    }

                    // Success confirmation panel
                    if (state == "saved") {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(YapShapes.card).background(ext.successSoft).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Bolt, null, tint = ext.onRefinedContainer, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Using your key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = ext.onRefinedContainer)
                                Text("Unlimited refines · billed by your provider", fontSize = 12.sp,
                                    color = ext.onRefinedContainer.copy(alpha = 0.8f))
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                // Docked destructive link
                HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                val canRemove = state == "saved" || savedHint != null
                Box(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp)
                        .clickable(enabled = canRemove && state != "saving") {
                            scope.launch {
                                runCatching { AccountClient.clearByokKey() }
                                    .onSuccess {
                                        key = ""; savedHint = null; state = "empty"; error = null
                                        android.widget.Toast.makeText(
                                            context,
                                            "Key removed. Back to the built-in allowance.",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    .onFailure { e ->
                                        android.widget.Toast.makeText(
                                            context,
                                            e.message ?: "Couldn't remove the key.",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Remove key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (canRemove) cs.error else ext.subdued)
                }
            }
        }
    }
}

@Composable
private fun ProviderSoonChip(name: String) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Row(
        Modifier.clip(YapShapes.md).background(cs.surfaceContainer)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 13.sp, color = ext.subdued)
        Spacer(Modifier.width(5.dp))
        Box(Modifier.clip(YapShapes.sm).background(ext.offlineContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("SOON", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ext.onOfflineContainer)
        }
    }
}
