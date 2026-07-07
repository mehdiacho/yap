package com.mehdiacho.yap.ui.screens

import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import com.mehdiacho.yap.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.audio.RecorderEvent
import com.mehdiacho.yap.audio.RecorderStatus
import com.mehdiacho.yap.audio.RecordingBus
import com.mehdiacho.yap.audio.RecordingController
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.CaptureChrome
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.rememberOfflineState
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.delay

private const val WAVE_BARS = 56
private const val WAVE_STEP_MS = 70L // ~14 bars/sec — calm scroll
private const val WAVE_MIN = 0.05f

@Composable
fun CaptureScreen(
    viewModel: MainViewModel,
    autoStart: MutableState<Boolean>? = null,
    onOpenSession: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "audio/mpeg"
            viewModel.importSharedAudio(uri, mime)
        }
    }
    val state by RecordingBus.state.collectAsState()

    var permDenied by remember { mutableStateOf(false) }
    var permDeniedForever by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permDenied = false; permDeniedForever = false
            RecordingController.start(context)
        } else {
            permDenied = true
            // "Don't ask again": the system refuses to show the dialog — the only path
            // left is app settings, so the CTA reroutes there.
            val activity = context as? android.app.Activity
            permDeniedForever = activity != null &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO)
        }
    }

    fun requestAndStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(perms)
    }

    // Auto-start when launched from the Quick Settings tile (or other external entry).
    LaunchedEffect(autoStart?.value) {
        if (autoStart?.value == true && state.status == RecorderStatus.IDLE) {
            requestAndStart()
            autoStart.value = false
        }
    }

    // Rolling waveform history, advanced at a fixed calm cadence (independent of the
    // ~50/sec meter rate) by reading the latest amplitude straight off the bus.
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(WAVE_MIN) } } }
    LaunchedEffect(Unit) {
        while (true) {
            val s = RecordingBus.state.value
            when (s.status) {
                RecorderStatus.RECORDING -> { bars.removeAt(0); bars.add(s.amplitude.coerceIn(WAVE_MIN, 1f)) }
                RecorderStatus.IDLE -> if (bars.any { it > WAVE_MIN }) { bars.removeAt(0); bars.add(WAVE_MIN) }
                else -> { /* paused: hold */ }
            }
            delay(WAVE_STEP_MS)
        }
    }

    val sessions by viewModel.allSessions.collectAsState()

    // UI-level states not tracked by the recorder (IDLE/RECORDING/PAUSED). Real triggers wire
    // permission-denied and offline; `devState` is a debug-only previewer for the rest so every
    // design in the kit is viewable without faking production conditions.
    val (realOffline, recheckOnline) = rememberOfflineState()
    var devState by remember { mutableStateOf<String?>(null) }
    val offline = realOffline || devState == "offline"
    val recorderIdle = state.status == RecorderStatus.IDLE

    // Immersive capture states hide the bottom nav (design: no navbar on these frames).
    val immersive = !recorderIdle || permDenied ||
        devState == "processing" || devState == "error" || devState == "permission" || devState == "quota"
    LaunchedEffect(immersive) { CaptureChrome.immersive.value = immersive }
    DisposableEffect(Unit) { onDispose { CaptureChrome.immersive.value = false } }

    Box(Modifier.fillMaxSize()) {
        when {
            recorderIdle && devState == "processing" -> ProcessingContent { devState = null }
            recorderIdle && devState == "error" ->
                ErrorContent(recovered = "1:02", onResume = { devState = null }, onSave = { devState = null })
            recorderIdle && (permDenied || devState == "permission") ->
                PermissionDeniedContent(
                    settingsRoute = permDeniedForever,
                    onGrant = {
                        devState = null
                        if (permDeniedForever) {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + context.packageName)
                                )
                            )
                            permDenied = false // re-checked when they come back and tap the mic
                        } else requestAndStart()
                    },
                    onDismiss = { permDenied = false; devState = null },
                )
            recorderIdle -> IdleContent(
                lastSession = sessions.firstOrNull(),
                offline = offline,
                onRecheckOnline = { recheckOnline(); if (devState == "offline") devState = null },
                onRecord = ::requestAndStart,
                onImport = { importLauncher.launch(arrayOf("audio/*", "video/*")) },
                onOpenSession = onOpenSession,
            )
            // Real interruption: another app took the mic — service auto-paused and flagged it.
            state.silenced -> ErrorContent(
                recovered = formatElapsed(state.elapsedMs),
                onResume = { RecordingController.resume(context) },
                onSave = { RecordingController.stop(context) },
            )
            else -> RecordingContent(
                paused = state.status == RecorderStatus.PAUSED,
                elapsedMs = state.elapsedMs,
                bars = bars,
                appendTitle = viewModel.appendTarget.collectAsState().value?.title,
                onPauseResume = {
                    if (state.status == RecorderStatus.PAUSED) RecordingController.resume(context)
                    else RecordingController.pause(context)
                },
                onStop = { RecordingController.stop(context) },
                onDiscard = {
                    RecordingController.discard(context)
                    Toast.makeText(context, "Recording discarded", Toast.LENGTH_SHORT).show()
                },
                onBookmark = {
                    RecordingController.bookmark(context)
                    Toast.makeText(context, "Moment bookmarked", Toast.LENGTH_SHORT).show()
                },
            )
        }

        // Quota-hit is a bottom sheet over the (dimmed) idle screen — dismiss via scrim/swipe.
        if (recorderIdle && devState == "quota") {
            QuotaSheet(
                onUpgrade = { devState = null },
                onOwnKey = { devState = null },
                onDismiss = { devState = null },
            )
        }

        // Debug-only previewer — cycles through the capture states in the design kit so each is
        // viewable without faking production conditions. Compiled out of release builds.
        if (BuildConfig.DEBUG && state.status == RecorderStatus.IDLE) {
            val states = listOf<String?>(null, "processing", "quota", "error", "permission", "offline")
            TextButton(
                onClick = { devState = states[(states.indexOf(devState) + 1) % states.size] },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp),
            ) {
                Text("preview: ${devState ?: "live"}", fontSize = 10.sp, color = YapTheme.extended.subdued)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IdleContent(
    lastSession: Session?,
    offline: Boolean = false,
    onRecheckOnline: () -> Unit = {},
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    val user by com.mehdiacho.yap.data.AuthManager.authState()
        .collectAsState(initial = com.mehdiacho.yap.data.AuthManager.currentUser)
    val name = user?.displayName?.takeIf { it.isNotBlank() }?.substringBefore(' ')
        ?: user?.email?.takeIf { it.isNotBlank() }?.substringBefore('@')
        ?: "there"
    val greeting = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    val signedIn = user != null && user?.isAnonymous == false

    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Spec §1.6 — ONLY these scale with screen width; everything else is fixed dp/sp.
        val wv = maxWidth.value
        val edge = (wv * 0.061f).dp.coerceIn(20.dp, 28.dp)   // screen edge margin
        val captureDia = (wv * 0.375f).dp.coerceIn(120.dp, 156.dp) // §2.3 idle
        val avatar = (wv * 0.106f).dp.coerceIn(34.dp, 44.dp)
        val micIcon = captureDia * 0.47f

        // Banner visibility resets whenever connectivity flips, so going offline re-shows it.
        var bannerDismissed by remember(offline) { mutableStateOf(false) }

        Column(Modifier.fillMaxSize().padding(horizontal = edge)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(8.dp))

            // 1 · Top row — avatar + sync chip (§3.1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(avatar).clip(CircleShape).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "Y",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.weight(1f))
                if (offline) {
                    // Tap: re-check connectivity. Long-press: re-show the banner (tooltip-style).
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .combinedClickable(
                                onClick = onRecheckOnline,
                                onLongClick = { bannerDismissed = false },
                            )
                            .background(ext.warningSoft).padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CloudOff, null, tint = ext.onOfflineContainer, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Offline", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ext.onOfflineContainer)
                    }
                } else {
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(ext.successSoft).padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CloudDone, null, tint = ext.onRefinedContainer, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (signedIn) "Synced" else "Guest",
                            fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ext.onRefinedContainer)
                    }
                }
            }

            // Offline banner — second on screen (below the top row), dismissible.
            if (offline && !bannerDismissed) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(ext.warningSoft).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudOff, null, tint = ext.onOfflineContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Offline — draft saved, will refine when connected.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = ext.onOfflineContainer,
                        modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.Close, "Dismiss", tint = ext.onOfflineContainer,
                        modifier = Modifier.size(18.dp).clickable { bannerDismissed = true })
                }
            }

            // 2 · Greeting (Headline Outfit 24 + Body-small subtitle)
            Spacer(Modifier.height(22.dp))
            Text("$greeting, $name",
                fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                lineHeight = 28.sp, letterSpacing = (-0.4).sp, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(if (offline) "Recording works fully offline." else "What's on your mind?",
                fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant)

            // 3 · Last-note card (§2.4-lite) — tappable: opens the note (incl. transcribing state).
            if (lastSession != null) {
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(cs.surfaceContainer)
                        .clickable { onOpenSession(lastSession.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("LAST NOTE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp, color = ext.subdued)
                    Spacer(Modifier.height(5.dp))
                    Text(lastSession.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Shared §2.5 chip — shows "Polishing" while a fresh capture transcribes.
                        StatusChip(lastSession.status)
                        Spacer(Modifier.width(6.dp))
                        Text(relativeTime(lastSession.createdAt), fontSize = 11.sp, color = ext.subdued)
                    }
                }
            }

            // 4 · ★ Flexible spacer centering block 5 (§3.1)
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 5 · Capture button + label + import pill
                Box(contentAlignment = Alignment.Center) {
                    // Soft purple glow behind the button — design: 0 18px 44px rgba(91,91,214,.42).
                    // A blurred, offset primary disc gives the halo real depth (Compose shadow
                    // alone reads too flat on a light surface). Unbounded lets it spread past bounds.
                    Box(
                        Modifier.size(captureDia)
                            .offset(y = 9.dp)
                            .blur(36.dp, BlurredEdgeTreatment.Unbounded)
                            .clip(CircleShape)
                            .background(cs.primary.copy(alpha = 0.42f))
                    )
                    Box(
                        Modifier.size(captureDia)
                            .shadow(10.dp, CircleShape, spotColor = cs.primary, ambientColor = cs.primary)
                            .clip(CircleShape).background(cs.primary).clickable { onRecord() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Mic, "Record", tint = cs.onPrimary, modifier = Modifier.size(micIcon))
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Tap to record", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainer)
                        .clickable { onImport() }.padding(horizontal = 15.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.UploadFile, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Import a file", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                }
            }
        }
    }
}

private fun relativeTime(ts: Long): String {
    val mins = (System.currentTimeMillis() - ts) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

/** §Capture · processing — auto_awesome disc + spinner ring, two status lines, "Keep capturing". */
@Composable
private fun ProcessingContent(onKeepCapturing: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(108.dp).clip(CircleShape).background(cs.primaryContainer))
                Icon(Icons.Filled.AutoAwesome, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(50.dp))
                CircularProgressIndicator(Modifier.size(108.dp), color = cs.primary, strokeWidth = 3.dp)
            }
            Spacer(Modifier.height(28.dp))
            Text("Polishing…", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Transcribed on device ✓\nNow refining in the cloud.", fontSize = 15.sp, lineHeight = 22.sp,
                color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("You can leave — this finishes in the background.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onKeepCapturing, shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Keep capturing", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(38.dp))
    }
}

/** §Capture · quota-hit — bottom sheet, hourglass, "Cloud polish paused". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotaSheet(onUpgrade: () -> Unit, onOwnKey: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color(0x7314121E),
    ) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 34.dp)) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(ext.offlineContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.HourglassEmpty, null, tint = ext.onOfflineContainer, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Cloud polish paused", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("You're out of cloud minutes this month. Recording and on-device drafts still work — refinement resumes when you upgrade or add your own key.",
                fontSize = 14.sp, lineHeight = 21.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUpgrade, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Upgrade to Pro", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOwnKey, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Use my own key", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** §Capture · permission-denied — circular error tile, "Microphone is off", direct grant. */
@Composable
private fun PermissionDeniedContent(
    settingsRoute: Boolean = false,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(cs.errorContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MicOff, null, tint = cs.error, modifier = Modifier.size(50.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Microphone is off", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(
                if (settingsRoute)
                    "Yap can't record without mic access. Enable it in system settings to start capturing."
                else "Yap can't record without mic access. Grant it to start capturing.",
                fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Button(onClick = onGrant, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (settingsRoute) "Open settings" else "Grant permission", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Maybe later", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(38.dp))
    }
}

/** §Capture · error — "Recording interrupted" with recovered-audio chip. */
@Composable
private fun ErrorContent(recovered: String, onResume: () -> Unit, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(cs.errorContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = cs.error, modifier = Modifier.size(50.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Recording interrupted", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Text("The mic was taken by another app. Your audio so far is safe and buffered.",
                fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(ext.successSoft)
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shield, null, tint = ext.onRefinedContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("$recovered of audio recovered", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ext.onRefinedContainer)
            }
        }
        Button(onClick = onResume, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text("Resume recording", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onSave, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Save what I have", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(38.dp))
    }
}

@Composable
private fun RecordingContent(
    paused: Boolean,
    elapsedMs: Long,
    bars: SnapshotStateList<Float>,
    appendTitle: String? = null,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onBookmark: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val accent = ext.recording

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wv = maxWidth.value
        val edge = (wv * 0.061f).dp.coerceIn(20.dp, 28.dp)
        val centerDia = (wv * 0.267f).dp.coerceIn(88.dp, 108.dp) // §2.3 recording
        val sideDia = centerDia * 0.6f                            // §2.3 side actions

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = edge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1 · Timer block (§3.2)
            Spacer(Modifier.height(20.dp))
            val dotAlpha = if (paused) 1f else rememberDotPulse()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape)
                    .background((if (paused) cs.onSurfaceVariant else accent).copy(alpha = dotAlpha)))
                Spacer(Modifier.width(8.dp))
                Text(if (paused) "Paused" else "Recording",
                    color = if (paused) cs.onSurfaceVariant else accent,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.6.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(formatElapsed(elapsedMs), fontFamily = Outfit, fontWeight = FontWeight.Bold,
                fontSize = 44.sp, color = cs.onSurface)

            // Append-to-note indicator — this recording merges into an existing note.
            if (appendTitle != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adding to \"${appendTitle.take(28)}${if (appendTitle.length > 28) "…" else ""}\"",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
                }
            }

            // 2 · Waveform (§2.6) — 11 centered bars driven by live amplitude.
            Spacer(Modifier.height(14.dp))
            Waveform11(bars, paused)

            // 3 · Flexible live-draft panel (§3.2 block 3)
            val draft by com.mehdiacho.yap.audio.LiveDraftEngine.draftText.collectAsState()
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(cs.surfaceContainer).padding(16.dp)
            ) {
                Text(if (draft.isBlank()) "DRAFT SO FAR" else "LIVE DRAFT", fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                if (draft.isBlank()) {
                    Text("Your words appear here as you speak…", fontSize = 14.sp,
                        color = ext.subdued, lineHeight = 21.sp)
                } else {
                    val scroll = rememberScrollState()
                    LaunchedEffect(draft) { scroll.animateScrollTo(scroll.maxValue) }
                    Text(draft, fontSize = 15.sp, lineHeight = 23.sp, color = cs.onSurface,
                        modifier = Modifier.verticalScroll(scroll))
                }
            }

            // 4 · Transport row (§3.2) — pause · stop(hero) · bookmark
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 30.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportSideButton(sideDia,
                    icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    desc = if (paused) "Resume" else "Pause", onClick = onPauseResume)
                Spacer(Modifier.width(30.dp))
                RecordStopButton(centerDia, paused, accent, onStop = onStop, onDiscard = onDiscard)
                Spacer(Modifier.width(30.dp))
                TransportSideButton(sideDia, icon = Icons.Outlined.BookmarkBorder,
                    desc = "Bookmark", onClick = onBookmark)
            }
        }
    }
}

/** §2.7 dot pulse — opacity 1 → 0.4 → 1, ~1.35s. */
@Composable
internal fun rememberDotPulse(): Float {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.4f,
        infiniteRepeatable(tween(1350, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "da")
    return a
}

/** §2.6 waveform — 11 bars, 5dp wide, 4dp gap, centered & mirrored, recording accent. */
@Composable
internal fun Waveform11(bars: SnapshotStateList<Float>, paused: Boolean) {
    val accent = YapTheme.extended.recording
    val idle = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val color = if (paused) idle else accent
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        val n = 11
        val barW = 5.dp.toPx()
        val gap = 4.dp.toPx()
        val totalW = n * barW + (n - 1) * gap
        val startX = (size.width - totalW) / 2f
        val src = bars
        for (i in 0 until n) {
            val idx = if (src.size <= 1) 0 else (i * (src.size - 1) / (n - 1)).coerceIn(0, src.size - 1)
            val amp = (if (src.isEmpty()) 0.2f else src[idx]).coerceIn(0.12f, 1f)
            val h = amp * size.height
            val x = startX + i * (barW + gap)
            val top = (size.height - h) / 2f
            drawRoundRect(color = color, topLeft = Offset(x, top), size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f))
        }
    }
}

/**
 * §2.3 recording hero — accent circle, rounded stop square, §2.7 pulse ring while recording.
 * Tap = stop & save. Long-press reveals a discard bubble above; slide up onto it and release
 * to throw the recording away (replaces the old always-visible delete button).
 */
@Composable
internal fun RecordStopButton(dia: Dp, paused: Boolean, accent: Color, onStop: () -> Unit, onDiscard: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val overDelete = dragging && -dragY > threshold

    Box(contentAlignment = Alignment.Center) {
        if (!paused) {
            val t = rememberInfiniteTransition(label = "ring")
            val scale by t.animateFloat(1f, 1.18f,
                infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "rs")
            val alpha by t.animateFloat(0.55f, 0f,
                infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ra")
            Box(Modifier.size(dia)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .clip(CircleShape).background(accent))
        }
        Box(
            Modifier.size(dia).clip(CircleShape).background(accent)
                .pointerInput(onDiscard) {
                    detectTapGestures(onTap = { onStop() })
                }
                .then(if (onDiscard != null) Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = true; dragY = 0f
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount -> change.consume(); dragY += amount.y },
                        onDragEnd = {
                            if (-dragY > threshold) onDiscard.invoke()
                            dragging = false; dragY = 0f
                        },
                        onDragCancel = { dragging = false; dragY = 0f },
                    )
                } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val sq = dia * 0.29f
            Box(Modifier.size(sq).clip(RoundedCornerShape(sq * 0.25f)).background(Color.White))
        }
        // Discard bubble — floats above the stop button while holding; grows hot when reached.
        if (dragging && onDiscard != null) {
            Box(
                Modifier.offset(y = -(dia / 2) - 52.dp)
                    .size(if (overDelete) 62.dp else 54.dp).clip(CircleShape)
                    .background(if (overDelete) cs.error else cs.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Delete, "Discard recording",
                    tint = if (overDelete) cs.onError else cs.error,
                    modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** §2.3 side action — Surface + 1dp Outline-Variant stroke, icon 45% of button. */
@Composable
internal fun TransportSideButton(dia: Dp, icon: ImageVector, desc: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(dia).clip(CircleShape).background(cs.surface)
            .border(1.dp, cs.outlineVariant, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = cs.onSurfaceVariant, modifier = Modifier.size(dia * 0.45f))
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
