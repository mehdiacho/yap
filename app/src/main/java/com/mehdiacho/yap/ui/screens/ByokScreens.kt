package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay
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

    // Referral progress is stubbed at 0 until the backend counts qualified invites.
    val invited = 0
    val refCode = remember {
        (AuthManager.currentUser?.uid ?: "YOU").filter { it.isLetterOrDigit() }.take(6).uppercase()
    }
    val refLink = "yap.app/r/$refCode"

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
                Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer),
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
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(cs.surfaceContainerLow)
                    .border(2.dp, cs.primary, RoundedCornerShape(18.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pay once", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                        modifier = Modifier.weight(1f))
                    Text("$4.99", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = cs.primary)
                }
                Text("One-time · unlock forever", fontSize = 12.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { stub("Purchase") }, shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text("Unlock for $4.99", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))

            // Referral card
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surface)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp)).padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Or invite 5 friends", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = cs.onSurface, modifier = Modifier.weight(1f))
                    Text("$invited / 5", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
                Spacer(Modifier.height(10.dp))
                // Progress dots — 5 segments.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(5) { i ->
                        Box(
                            Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (i < invited) cs.primary else cs.surfaceVariant)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Referral-link chip with copy.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainer)
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString("https://$refLink"))
                            android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(refLink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                        color = cs.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ContentCopy, "Copy link", tint = cs.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT,
                                "Catch every idea before it slips away — try Yap: https://$refLink")
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
    var unlocked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        unlocked = runCatching { AccountClient.status().byokUnlocked }.getOrDefault(false)
    }

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
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(ext.warningSoft),
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
                        Text("Invite friends · 0 of 5", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(38.dp))
                }
            }
        }
        true -> {
            // BYOK · unlocked + key validation
            var key by remember { mutableStateOf("") }
            var state by remember { mutableStateOf("empty") } // empty | validating | valid | invalid
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
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(ext.refinedContainer)
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.LockOpen, null, tint = ext.onRefinedContainer, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Unlocked", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ext.onRefinedContainer)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(20.dp))
                    Text("Provider", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Gemini — selected/filled
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(cs.primary)
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

                    Text("Paste your Gemini key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Spacer(Modifier.height(8.dp))
                    val borderColor = when (state) {
                        "valid" -> cs.tertiary
                        "invalid" -> cs.error
                        "validating" -> cs.primary
                        else -> cs.outlineVariant
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                            .border(2.dp, borderColor, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (key.isEmpty()) Text("AIza…", fontSize = 14.sp, color = ext.subdued)
                            BasicTextField(
                                value = key,
                                onValueChange = {
                                    key = it
                                    state = if (it.isBlank()) "empty" else "validating"
                                    if (it.isNotBlank()) scope.launch {
                                        // Stub validation — real check calls the provider (functionality list).
                                        delay(900)
                                        if (key == it) state = if (it.startsWith("AIza") && it.length >= 12) "valid" else "invalid"
                                    }
                                },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp, color = cs.onSurface, letterSpacing = 0.3.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        when (state) {
                            "valid" -> Icon(Icons.Default.CheckCircle, null, tint = cs.tertiary, modifier = Modifier.size(22.dp))
                            "invalid" -> Icon(Icons.Default.Error, null, tint = cs.error, modifier = Modifier.size(22.dp))
                            "validating" -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    // Inline validation caption
                    Spacer(Modifier.height(8.dp))
                    when (state) {
                        "valid" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, null, tint = cs.tertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Valid key · connected to Gemini", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = cs.tertiary)
                        }
                        "invalid" -> Text("That key doesn't look right — check it and try again.",
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.error)
                        "validating" -> Text("Validating…", fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.primary)
                    }

                    // Success confirmation panel
                    if (state == "valid") {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ext.successSoft).padding(14.dp),
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
                Box(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp)
                        .clickable(enabled = key.isNotBlank()) {
                            key = ""; state = "empty"
                            android.widget.Toast.makeText(context, "Key removed", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Remove key", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = if (key.isNotBlank()) cs.error else ext.subdued)
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
        Modifier.clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainer)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 13.sp, color = ext.subdued)
        Spacer(Modifier.width(5.dp))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(ext.offlineContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("SOON", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ext.onOfflineContainer)
        }
    }
}
