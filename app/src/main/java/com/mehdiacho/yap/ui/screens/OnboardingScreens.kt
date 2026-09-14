package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController
import com.mehdiacho.yap.data.AuthManager
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

// =====================================================================================
// Onboarding — 7 steps after Welcome, in the revised order:
//   Welcome → 1 About → 2 Account → 3 Permission → 4 First note (record/discard/saved)
//   → 5 How it works → 6 AI key → 7 Suggested setup.
// Conventions: every page carries a "STEP n OF 7" eyebrow; the info trio (5–7) carries an
// advancing 3-dot pill; "Next" moves within that phase, "Continue" moves between phases.
// The flow is sealed — no navbar, no outside navigation, state survives recreation.
// =====================================================================================

object Onboarding {
    private const val PREFS = "yap_prefs"
    private const val KEY_DONE = "onboarding_done"
    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    fun markDone(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
}

private fun hasMicPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
fun OnboardingFlow(viewModel: MainViewModel, onFinish: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Saveable so rotation / process recreation doesn't reset the flow to the start.
    var step by rememberSaveable { mutableStateOf(0) }          // 0 = welcome
    var savedShown by rememberSaveable { mutableStateOf(false) } // first note reached "Saved!"
    var accountLogin by rememberSaveable { mutableStateOf(false) }
    val recState by RecordingBus.state.collectAsState()
    val recording = recState.status != RecorderStatus.IDLE

    // Step-back map. First-note goes back to Permission only when it's still ungranted
    // (otherwise Account), so back never lands on a page with nothing left to do.
    fun goBack() {
        step = when (step) {
            1 -> 0; 2 -> 1
            3 -> 2
            4 -> if (hasMicPermission(context)) 2 else 3
            5 -> 4  // renders Saved if a note was captured, else First note
            6 -> 5; 7 -> 6
            else -> step
        }
    }
    // System back steps backwards through the flow instead of exiting the app.
    // Disabled while recording — the discard gesture is the intentional way out there.
    androidx.activity.compose.BackHandler(enabled = step > 0 && !recording) { goBack() }

    when {
        step == 4 && recording -> OnbRecording(
            paused = recState.status == RecorderStatus.PAUSED,
            elapsedMs = recState.elapsedMs,
            onPauseResume = {
                if (recState.status == RecorderStatus.PAUSED) RecordingController.resume(context)
                else RecordingController.pause(context)
            },
            onStop = { RecordingController.stop(context); savedShown = true },
            onDiscard = {
                RecordingController.discard(context)
                android.widget.Toast.makeText(context, "Recording discarded", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
        step == 0 -> OnbWelcome(
            onStart = { step = 1 },
            onHaveAccount = { accountLogin = true; step = 2 },
        )
        step == 1 -> OnbAbout(onContinue = { step = 2 }, onBack = ::goBack)
        step == 2 -> OnbAccount(login = accountLogin, onToggleLogin = { accountLogin = it },
            onDone = { existingAccount ->
                // Returning users have already been onboarded — don't walk them through it again.
                if (existingAccount) { Onboarding.markDone(context); onFinish() }
                else step = if (hasMicPermission(context)) 4 else 3
            }, onBack = ::goBack)
        step == 3 -> OnbPermission(
            onGranted = { step = 4 },
            onSkip = { step = 4 },
            onBack = ::goBack,
        )
        step == 4 && savedShown -> OnbSaved(viewModel, onContinue = { step = 5 })
        step == 4 -> OnbFirstNote(onSkip = { step = 5 }, onBack = ::goBack)
        step == 5 -> OnbHowItWorks(onNext = { step = 6 }, onBack = ::goBack)
        step == 6 -> OnbAiKey(onNext = { step = 7 }, onBack = ::goBack)
        step == 7 -> OnbSuggestedSetup(onFinish = { Onboarding.markDone(context); onFinish() }, onBack = ::goBack)
    }
}

/** Top-left back arrow — steps backwards through the flow (same map as system back). */
@Composable
private fun OnbBackButton(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack, "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(10.dp),
        )
    }
}

/** "STEP n OF 7" eyebrow — on every content page for orientation. */
@Composable
private fun OnbEyebrow(n: Int, centered: Boolean = false, modifier: Modifier = Modifier) {
    Text("STEP $n OF 7", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.primary,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = modifier)
}

/** 3-dot progression pill for the info phase (How it works · AI key · Setup). */
@Composable
private fun OnbDots(active: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(3) { i ->
            if (i == active - 1) {
                Box(Modifier.width(22.dp).height(7.dp).clip(CircleShape).background(cs.primary))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(cs.surfaceVariant))
            }
        }
    }
}

/** Welcome — gradient icon mark, Display wordmark, two CTAs. */
@Composable
private fun OnbWelcome(onStart: () -> Unit, onHaveAccount: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(cs.background)) {
        val mark = (maxWidth.value * 0.233f).dp.coerceIn(72.dp, 96.dp) // §1.6 icon mark
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(mark)
                        .shadow(14.dp, YapShapes.dialog)
                        .clip(YapShapes.dialog)
                        .background(Brush.linearGradient(listOf(Color(0xFF2E7BEF), Color(0xFF1A63D8), Color(0xFF104BAE)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Color.White, modifier = Modifier.size(mark * 0.55f))
                }
                Spacer(Modifier.height(32.dp))
                Text("Yap", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp,
                    letterSpacing = (-1).sp, color = cs.onSurface)
                Spacer(Modifier.height(20.dp))
                Text("Capture any idea,\nthe instant it strikes.", fontSize = 18.sp, lineHeight = 26.sp,
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Button(onClick = onStart, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Get started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onHaveAccount, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("I already have an account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(38.dp))
        }
    }
}

/** 1 · About — the outcome pitch (distinct from How-it-works' mechanics). */
@Composable
private fun OnbAbout(onContinue: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(1, centered = true, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(72.dp).clip(YapShapes.xl).background(cs.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Bolt, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(26.dp))
            Text("Never lose\nan idea again", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp,
                color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text("Yap turns rambles into clean, organized notes. Start talking and a draft appears on your phone straight away, then tidies and files itself once it uploads.",
                fontSize = 15.sp, lineHeight = 23.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(onClick = onContinue, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(38.dp))
    }
}

/** 2 · Account — create (default) or log in, Google, guest escape. */
@Composable
private fun OnbAccount(login: Boolean, onToggleLogin: (Boolean) -> Unit, onDone: (Boolean) -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> AuthManager.SignInResult) {
        scope.launch {
            busy = true; error = null
            try { onDone(block().existingAccount) } catch (e: AuthManager.SignInCancelled) {
                // Backed out of the Google sheet. Not an error, so no banner.
            } catch (e: Exception) {
                error = e.message ?: "That email or password didn't match."
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(2)
        Spacer(Modifier.height(10.dp))
        Text(if (login) "Welcome back" else "Save your notes\nacross devices",
            fontFamily = Outfit, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp, color = cs.onSurface)
        Spacer(Modifier.height(10.dp))
        Text(if (login) "Log in to pick up right where you left off."
            else "Or skip. Everything works offline as a guest.",
            fontSize = 14.sp, color = cs.onSurfaceVariant)

        Spacer(Modifier.height(26.dp))
        if (error != null) {
            Row(
                Modifier.fillMaxWidth().clip(YapShapes.card).background(cs.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Error, null, tint = cs.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(error!!, fontSize = 13.sp, color = cs.onErrorContainer)
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = email, onValueChange = { email = it; error = null },
            label = { Text("Email address") }, singleLine = true, isError = error != null,
            shape = YapShapes.card,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it; error = null },
            label = { Text("Password") }, singleLine = true, isError = error != null,
            shape = YapShapes.card,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = { PeekabooToggle(showPassword) { showPassword = !showPassword } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        // Requirements are visible from the first keystroke — not only after a failure.
        if (!login) PasswordChecklist(password)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                run {
                    AuthManager.signInWithEmail(email.trim(), password).also { r ->
                        // Brand-new email accounts get a (non-blocking) verification email.
                        if (!login && !r.existingAccount) {
                            runCatching { AuthManager.sendEmailVerification() }
                            android.widget.Toast.makeText(context,
                                "Verification email sent to ${email.trim()}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            enabled = !busy && email.isNotBlank() &&
                (if (login) password.isNotEmpty() else passwordMeetsPolicy(password)),
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(
                when {
                    error != null -> "Try again"
                    login -> "Log in"
                    else -> "Create account"
                },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Mode switch — "Already have an account?" under the create button (and back).
        TextButton(onClick = { onToggleLogin(!login) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (login) "New here? Create an account" else "Already have an account? Log in",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (login) {
            ForgotPasswordButton(
                email = email,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 14.sp,
                onError = { error = it },
            )
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
            Text("  or  ", color = YapTheme.extended.subdued, fontSize = 12.sp)
            HorizontalDivider(Modifier.weight(1f), color = cs.outlineVariant)
        }
        Spacer(Modifier.height(16.dp))
        GoogleGradientButton(busy = busy) { run { AuthManager.signInWithGoogle(context) } }
        TextButton(onClick = { onDone(false) }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Text("Continue as guest", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** 3 · Permission — dedicated step (auto-skipped when already granted). */
@Composable
private fun OnbPermission(onGranted: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) onGranted() else onSkip()
    }
    fun launchPermission() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
        permissionLauncher.launch(perms)
    }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(3, centered = true, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(72.dp).clip(YapShapes.xl).background(cs.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Mic, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(26.dp))
            Text("Allow microphone\naccess", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
                color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Text("Yap needs your mic to capture ideas. Recordings stay on your device until you choose to refine them in the cloud.",
                fontSize = 15.sp, lineHeight = 23.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(onClick = ::launchPermission, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Allow microphone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Not now", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(26.dp))
    }
}

/** 4 · First note — hero mic; permission fallback if step 3 was skipped. */
@Composable
private fun OnbFirstNote(onSkip: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) RecordingController.start(context)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(cs.background)) {
        val dia = (maxWidth.value * 0.367f).dp.coerceIn(120.dp, 148.dp)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(horizontal = 16.dp)) { OnbBackButton(onBack) }
            OnbEyebrow(4, centered = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("Tap to capture\nyour first idea", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
                color = cs.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Ramble for a few seconds. We'll handle the rest.",
                fontSize = 14.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp))

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(dia)
                            .shadow(12.dp, CircleShape)
                            .clip(CircleShape).background(cs.primary)
                            .clickable {
                                if (hasMicPermission(context)) RecordingController.start(context)
                                else permissionLauncher.launch(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                    else arrayOf(Manifest.permission.RECORD_AUDIO)
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Mic, "Record", tint = cs.onPrimary, modifier = Modifier.size(dia * 0.47f))
                    }
                }
            }
            TextButton(onClick = onSkip) { Text("Skip", color = cs.onSurfaceVariant) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 4 · First note · recording — teaches the hold-Stop-to-discard gesture. */
@Composable
private fun OnbRecording(
    paused: Boolean,
    elapsedMs: Long,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val accent = ext.recording
    val bars = remember { mutableStateListOf<Float>().apply { repeat(24) { add(0.12f) } } }
    LaunchedEffect(Unit) {
        while (true) {
            val s = RecordingBus.state.value
            if (s.status == RecorderStatus.RECORDING) { bars.removeAt(0); bars.add(s.amplitude.coerceIn(0.12f, 1f)) }
            kotlinx.coroutines.delay(70)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(cs.background)) {
        val centerDia = (maxWidth.value * 0.267f).dp.coerceIn(88.dp, 108.dp)
        val sideDia = centerDia * 0.6f
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            val dotAlpha = if (paused) 1f else rememberDotPulse()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape)
                    .background((if (paused) cs.onSurfaceVariant else accent).copy(alpha = dotAlpha)))
                Spacer(Modifier.width(7.dp))
                Text(if (paused) "Paused" else "Recording",
                    color = if (paused) cs.onSurfaceVariant else accent,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(formatElapsedOnb(elapsedMs), fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Waveform11(bars, paused)

            val draft by com.mehdiacho.yap.audio.LiveDraftEngine.draftText.collectAsState()
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(YapShapes.panel)
                    .background(cs.surfaceContainer).padding(16.dp)
            ) {
                Text("LIVE DRAFT", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                if (draft.isBlank()) {
                    Text("Your words appear here as you speak…", fontSize = 14.sp, color = ext.subdued, lineHeight = 21.sp)
                } else {
                    val scroll = rememberScrollState()
                    LaunchedEffect(draft) { scroll.animateScrollTo(scroll.maxValue) }
                    Text(draft, fontSize = 15.sp, lineHeight = 23.sp, color = cs.onSurface,
                        modifier = Modifier.verticalScroll(scroll))
                }
            }

            // Teaching tip for the discard gesture — onboarding only.
            Spacer(Modifier.height(14.dp))
            Text("Tip: tap Stop to save · hold it and slide up to discard",
                fontSize = 12.sp, color = ext.subdued)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 34.dp)) {
                TransportSideButton(sideDia,
                    icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    desc = if (paused) "Resume" else "Pause", onClick = onPauseResume)
                Spacer(Modifier.width(28.dp))
                RecordStopButton(centerDia, paused, accent, onStop = onStop, onDiscard = onDiscard)
                Spacer(Modifier.width(28.dp))
                Spacer(Modifier.size(sideDia)) // keeps the row balanced (design)
            }
        }
    }
}

private fun formatElapsedOnb(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** 4 · First note · saved — success disc, copy, preview card, Continue (next phase). */
@Composable
private fun OnbSaved(viewModel: MainViewModel, onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val sessions by viewModel.allSessions.collectAsState()
    val latest = sessions.firstOrNull()

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(ext.refinedContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = ext.onRefinedContainer, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Saved!", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Text("Your first idea is captured as a draft. It'll polish itself the next time you're online.",
                fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            if (latest != null) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(latest.status)
                        Spacer(Modifier.weight(1f))
                        Text("just now", fontSize = 11.sp, color = ext.subdued)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(latest.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                }
            }
        }
        Button(onClick = onContinue, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(38.dp))
    }
}

/** 5 · How it works — feature cards, dots 1/3, "Next" (same phase). */
@Composable
private fun OnbHowItWorks(onNext: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(5, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text("How Yap works", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 25.sp,
            letterSpacing = (-0.3).sp, color = cs.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureCard(Icons.Default.Bolt, "Drafts instantly", "Transcribed on your phone, offline.")
            FeatureCard(Icons.Default.AutoAwesome, "Polishes when online", "Cleaned up & formatted in the cloud.")
            FeatureCard(Icons.Outlined.Hub, "Auto-organized", "Sorted into clusters & collections.")
        }
        OnbDots(active = 1, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 30.dp))
        Button(onClick = onNext, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(34.dp))
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainerLow).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(YapShapes.card).background(cs.primaryContainer),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 6 · AI key — dots 2/3, "Next" (same phase). */
@Composable
private fun OnbAiKey(onNext: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    // A padlock with no sentence next to it reads as a broken feature. A tester filed it as
    // one on 2026-09-08: "Would be great to have instruction on why 'Use my own key' is
    // locked." It is not broken and it is not the app behind a paywall - it is the one mode
    // that costs Yap nothing to run, which is why it is bought once instead of monthly.
    var whyLocked by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(6, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text("Choose your AI", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 25.sp,
            letterSpacing = (-0.3).sp, color = cs.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        Text("You can change this anytime in Settings.", fontSize = 14.sp, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(22.dp))

        Row(
            Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainerLow)
                .border(2.dp, cs.primary, YapShapes.panel).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(YapShapes.lg).background(cs.primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = cs.onPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Use Yap's AI", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("Free · 30 min/mo cloud refine", fontSize = 13.sp, color = cs.onSurfaceVariant)
            }
            Icon(Icons.Default.RadioButtonChecked, null, tint = cs.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surface)
                .border(1.dp, cs.outlineVariant, YapShapes.panel)
                .clickable { whyLocked = !whyLocked }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(YapShapes.lg).background(cs.surfaceContainerHigh),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Key, null, tint = ext.subdued, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Use my own key", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Lock, null, tint = ext.onOfflineContainer, modifier = Modifier.size(16.dp))
                    }
                    Text("Unlimited refines on your own Gemini key", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                Text(if (whyLocked) "Hide" else "Why locked?", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = cs.primary)
            }
            if (whyLocked) {
                Text(
                    "On your own key Yap never pays for your refines, so this one is bought once " +
                        "rather than monthly: $4.99 one-time, or free once five people you invited " +
                        "have signed up and saved three notes each. Unlock it whenever you like in " +
                        "Settings → API key (BYOK) — you don't need it to start.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        OnbDots(active = 2, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 30.dp))
        Button(onClick = onNext, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Next", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(34.dp))
    }
}

/** 7 · Suggested setup — dots 3/3, real widget + tile pin requests. */
@Composable
private fun OnbSuggestedSetup(onFinish: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // What each row says for itself once its button has been pressed. Both system dialogs
    // here can decline without showing anything, and a button that appears to do nothing
    // reads as a broken app - a tester filed exactly that on 2026-09-08: "Home-screen
    // widget failed to add".
    var widgetHint by remember { mutableStateOf<String?>(null) }
    var tileHint by remember { mutableStateOf<String?>(null) }
    val manualWidget = "Your launcher wouldn't show the dialog. Long-press the home screen → Widgets → Yap."
    val manualTile = "Pull down Quick Settings → edit (pencil) → drag Yap in."

    fun requestPinWidget() {
        val mgr = android.appwidget.AppWidgetManager.getInstance(context)
        val provider = android.content.ComponentName(context, com.mehdiacho.yap.widget.RecordWidgetProvider::class.java)
        // requestPinAppWidget returns false when the launcher refuses - and several do,
        // including some that answer true to isRequestPinAppWidgetSupported. Discarding that
        // Boolean is what turned a launcher's refusal into a button that did nothing at all.
        val asked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            mgr.isRequestPinAppWidgetSupported &&
            mgr.requestPinAppWidget(provider, null, null)
        widgetHint = if (asked) null else manualWidget
    }
    fun requestAddTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = context.getSystemService(android.app.StatusBarManager::class.java)
            if (sbm == null) { tileHint = manualTile; return }
            sbm.requestAddTileService(
                android.content.ComponentName(context, com.mehdiacho.yap.tile.RecordTileService::class.java),
                "Yap",
                android.graphics.drawable.Icon.createWithResource(context, com.mehdiacho.yap.R.drawable.ic_tile_mic),
                context.mainExecutor,
            ) { result ->
                // Added, already there, or the user said no - all three mean the shade did
                // its job. Anything else means it never asked, so say how to do it by hand.
                tileHint = when (result) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                    else -> manualTile
                }
            }
        } else {
            tileHint = manualTile
        }
    }

    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        OnbBackButton(onBack)
        OnbEyebrow(7, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text("One-tap capture,\nfrom anywhere", fontFamily = Outfit, fontWeight = FontWeight.Bold,
            fontSize = 25.sp, lineHeight = 29.sp, letterSpacing = (-0.3).sp, color = cs.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        Text("Add these so an idea is never more than a tap away.", fontSize = 14.sp,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(22.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SetupRow(
                tile = {
                    Box(Modifier.size(56.dp).clip(YapShapes.panel)
                        .background(Brush.linearGradient(listOf(Color(0xFF2E7BEF), Color(0xFF1A63D8)))),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                },
                title = "Home-screen widget", body = "Tap to record from your launcher.",
                hint = widgetHint,
                onAdd = ::requestPinWidget,
            )
            SetupRow(
                tile = {
                    Box(Modifier.size(56.dp).clip(YapShapes.panel).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Tune, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(30.dp))
                    }
                },
                title = "Quick Settings tile", body = "Record from the pull-down shade.",
                hint = tileHint,
                onAdd = ::requestAddTile,
            )
        }

        OnbDots(active = 3, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 30.dp))
        Button(onClick = onFinish, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Start capturing", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(34.dp))
    }
}

@Composable
private fun SetupRow(
    tile: @Composable () -> Unit,
    title: String,
    body: String,
    hint: String? = null,
    onAdd: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainerLow).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tile()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text(body, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Button(onClick = onAdd, shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Add", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        hint?.let {
            Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}
