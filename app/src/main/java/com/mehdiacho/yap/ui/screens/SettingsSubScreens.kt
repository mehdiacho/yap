package com.mehdiacho.yap.ui.screens

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

// =====================================================================================
// Settings sub-screens (design section 09). Toggles/sliders are DESIGN STUBS backed by
// local state — persistence + behavior wiring is tracked on the functionality list.
// =====================================================================================

/** Shared scaffold: §2.8 app bar (back + Outfit 21sp title) over a scrolling body. */
@Composable
internal fun SettingsSubScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text(title, fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            content()
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** Design row: icon 24dp onSurfaceVariant · title 15/600 (+subtitle 12) · trailing slot. */
@Composable
private fun SubRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    titleColor: Color? = null,
    iconTint: Color? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = iconTint ?: cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = titleColor ?: cs.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = YapTheme.extended.subdued)
            }
            trailing()
        }
        if (divider) HorizontalDivider(thickness = 1.dp, color = cs.surfaceContainerHigh)
    }
}

@Composable
private fun SubEyebrow(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        color = YapTheme.extended.subdued,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

// ------------------------------------------------------------------ Settings · Capture

@Composable
fun CaptureSettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val store = com.mehdiacho.yap.data.SettingsStore
    val autoStopEnabled by store.autoStopEnabled.collectAsState()
    val autoStopSecsStored by store.autoStopSecs.collectAsState()
    var autoStopSecs by remember(autoStopSecsStored) { mutableFloatStateOf(autoStopSecsStored.toFloat()) }
    val haptics by store.haptics.collectAsState()
    val sounds by store.sounds.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    SettingsSubScaffold("Capture", onBack) {
        // Auto-stop slider card (design · Settings Capture)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-stop after silence", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                Text("${autoStopSecs.toInt()}s", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cs.primary)
            }
            Text("Stops recording when you go quiet.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Slider(
                value = autoStopSecs, onValueChange = { autoStopSecs = it },
                onValueChangeFinished = { store.setAutoStopSecs(autoStopSecs.toInt()) },
                valueRange = 5f..120f, enabled = autoStopEnabled,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("5s", fontSize = 11.sp, color = ext.subdued)
                Spacer(Modifier.weight(1f))
                Text("120s", fontSize = 11.sp, color = ext.subdued)
            }
        }
        Spacer(Modifier.height(16.dp))

        SubRow(Icons.Outlined.Timer, "Auto-stop", "Enable silence detection") {
            Switch(checked = autoStopEnabled, onCheckedChange = { store.setAutoStopEnabled(it) })
        }
        SubRow(Icons.Outlined.Vibration, "Haptics") {
            Switch(checked = haptics, onCheckedChange = { store.setHaptics(it) })
        }
        SubRow(Icons.Outlined.VolumeUp, "Start / stop sound") {
            Switch(checked = sounds, onCheckedChange = { store.setSounds(it) })
        }
        // Real feature — floating record bubble lives under Capture entry points.
        FloatingBubbleSubRow()
        SubRow(Icons.Outlined.Widgets, "Manage entry points", "Widget · tile · shortcuts", divider = false,
            onClick = {
                android.widget.Toast.makeText(context, "Entry points guide coming soon", android.widget.Toast.LENGTH_SHORT).show()
            }) {
            Icon(Icons.Default.ChevronRight, null, tint = cs.outlineVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Toggles the system-overlay floating mic bubble (asks for the overlay permission once). */
@Composable
private fun FloatingBubbleSubRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(com.mehdiacho.yap.overlay.FloatingBubble.isEnabled(context)) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (com.mehdiacho.yap.overlay.FloatingBubble.hasPermission(context)) {
            com.mehdiacho.yap.overlay.FloatingBubble.enable(context); enabled = true
        }
    }
    SubRow(Icons.Outlined.BubbleChart, "Floating record button", "A bubble on your screen to record anytime") {
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                if (on) {
                    if (com.mehdiacho.yap.overlay.FloatingBubble.hasPermission(context)) {
                        com.mehdiacho.yap.overlay.FloatingBubble.enable(context); enabled = true
                    } else {
                        permLauncher.launch(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + context.packageName)
                            )
                        )
                    }
                } else {
                    com.mehdiacho.yap.overlay.FloatingBubble.disable(context); enabled = false
                }
            }
        )
    }
}

// ------------------------------------------------------------- Settings · Privacy & data

@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val store = com.mehdiacho.yap.data.SettingsStore
    val appLock by store.appLock.collectAsState()
    val retention by store.retention.collectAsState()
    val onDeviceOnly by store.onDeviceOnly.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun stub(what: String) =
        android.widget.Toast.makeText(context, "$what coming soon", android.widget.Toast.LENGTH_SHORT).show()

    SettingsSubScaffold("Privacy & data", onBack) {
        SubRow(Icons.Outlined.Fingerprint, "App lock", "Require biometrics to open") {
            Switch(checked = appLock, onCheckedChange = { on ->
                // Only allow enabling when the device can actually authenticate.
                if (on) {
                    val mgr = androidx.biometric.BiometricManager.from(context)
                    val ok = mgr.canAuthenticate(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                    if (ok) store.setAppLock(true)
                    else android.widget.Toast.makeText(context,
                        "Set up a screen lock or fingerprint first", android.widget.Toast.LENGTH_LONG).show()
                } else store.setAppLock(false)
            })
        }
        if (appLock) {
            val lockTimeout by store.lockTimeoutMins.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Lock after", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                listOf(0 to "Right away", 1 to "1 min", 5 to "5 min", 15 to "15 min").forEach { (mins, label) ->
                    val selected = lockTimeout == mins
                    Text(
                        label, fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) cs.primaryContainer else cs.surfaceContainer)
                            .clickable { store.setLockTimeoutMins(mins) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        SubEyebrow("AUDIO RETENTION")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)) {
            listOf(
                "keep" to "Keep audio",
                "discard" to "Discard after transcribe",
                "auto30" to "Auto-delete after 30 days",
            ).forEachIndexed { i, (key, label) ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = cs.surfaceVariant)
                Row(
                    Modifier.fillMaxWidth().clickable { store.setRetention(key) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (retention == key) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        null, tint = if (retention == key) cs.primary else YapTheme.extended.subdued,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label, fontSize = 14.sp, color = cs.onSurface)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        SubRow(Icons.Outlined.CloudOff, "On-device only mode", "Never send audio to the cloud") {
            Switch(checked = onDeviceOnly, onCheckedChange = { store.setOnDeviceOnly(it) })
        }
        ClearDownloadsSubRow()
        ExportDataRow()
        DeleteAccountRow()
    }
}

/** Two-step confirm → server-side deleteAccount → local sign-out. */
@Composable
private fun DeleteAccountRow() {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmStep by remember { mutableStateOf(0) } // 0 none · 1 first ask · 2 final ask
    var deleting by remember { mutableStateOf(false) }

    SubRow(Icons.Outlined.DeleteForever, if (deleting) "Deleting…" else "Delete account",
        titleColor = cs.error, iconTint = cs.error, divider = false,
        onClick = { if (!deleting) confirmStep = 1 }) {
        if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.error)
    }

    if (confirmStep == 1) {
        AlertDialog(
            onDismissRequest = { confirmStep = 0 },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete your account?") },
            text = { Text("This permanently removes your notes, recordings, and account from Yap's servers. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmStep = 2 }) { Text("Continue", color = cs.error) } },
            dismissButton = { TextButton(onClick = { confirmStep = 0 }) { Text("Cancel") } },
        )
    }
    if (confirmStep == 2) {
        AlertDialog(
            onDismissRequest = { confirmStep = 0 },
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = cs.error, modifier = Modifier.size(30.dp)) },
            title = { Text("Last chance") },
            text = { Text("Everything will be gone — notes, audio, subscription state. Export your data first if you want a copy.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                    shape = RoundedCornerShape(50),
                    onClick = {
                        confirmStep = 0; deleting = true
                        scope.launch {
                            try {
                                com.mehdiacho.yap.data.AccountClient.deleteAccount()
                                com.mehdiacho.yap.data.AuthManager.signOut()
                                android.widget.Toast.makeText(context, "Account deleted", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context,
                                    e.message ?: "Couldn't delete the account", android.widget.Toast.LENGTH_LONG).show()
                            } finally { deleting = false }
                        }
                    },
                ) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmStep = 0 }) { Text("Cancel") } },
        )
    }
}

/** Builds the export zip (notes JSON + on-device audio) and opens the share sheet. */
@Composable
private fun ExportDataRow() {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    SubRow(
        Icons.Outlined.Download, "Export my data",
        subtitle = if (exporting) "Preparing your export…" else "Notes + audio on this device, as a zip",
        onClick = {
            if (exporting) return@SubRow
            exporting = true
            scope.launch {
                try {
                    val zip = com.mehdiacho.yap.data.DataExporter.buildExportZip(context)
                    context.startActivity(android.content.Intent.createChooser(
                        com.mehdiacho.yap.data.DataExporter.shareIntent(context, zip), "Export Yap data"))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context,
                        e.message ?: "Export failed", android.widget.Toast.LENGTH_LONG).show()
                } finally { exporting = false }
            }
        },
    ) {
        if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.ChevronRight, null, tint = cs.outlineVariant, modifier = Modifier.size(20.dp))
    }
}

/** Real feature — frees space by deleting downloaded copies (cloud copies remain). */
@Composable
private fun ClearDownloadsSubRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheBytes by remember { mutableLongStateOf(com.mehdiacho.yap.data.AudioCache.totalBytes(context)) }
    val sizeLabel = remember(cacheBytes) {
        when {
            cacheBytes <= 0L -> "Nothing downloaded"
            cacheBytes < 1_048_576L -> "%.0f KB".format(cacheBytes / 1024.0)
            else -> "%.1f MB".format(cacheBytes / 1_048_576.0)
        }
    }
    SubRow(
        Icons.Outlined.CloudDownload, "Clear downloads",
        "$sizeLabel · offline-pinned notes are kept",
        onClick = {
            if (cacheBytes > 0) {
                val freed = com.mehdiacho.yap.data.AudioCache.clear(context)
                cacheBytes = com.mehdiacho.yap.data.AudioCache.totalBytes(context)
                android.widget.Toast.makeText(context,
                    "Freed %.1f MB — cloud copies are untouched".format(freed / 1_048_576.0),
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    ) {
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- Settings · Appearance

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val store = com.mehdiacho.yap.data.SettingsStore
    val theme by store.themeMode.collectAsState()
    val dynamicColor by store.dynamicColor.collectAsState()

    SettingsSubScaffold("Appearance", onBack) {
        Text("THEME", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
            color = YapTheme.extended.subdued, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeSwatch("System", "system", theme, swatchBg = cs.surface, dot = cs.primary) { store.setThemeMode(it) }
            ThemeSwatch("Light", "light", theme, swatchBg = Color(0xFFFCFBFF), dot = Color(0xFF5B5BD6)) { store.setThemeMode(it) }
            ThemeSwatch("Dark", "dark", theme, swatchBg = Color(0xFF131318), dot = Color(0xFFC5C0FF)) { store.setThemeMode(it) }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(thickness = 1.dp, color = cs.surfaceContainerHigh)
        SubRow(Icons.Outlined.Colorize, "Dynamic color", "Match my wallpaper (Material You)") {
            Switch(checked = dynamicColor, onCheckedChange = { store.setDynamicColor(it) })
        }
        val amoledDark by store.amoledDark.collectAsState()
        SubRow(Icons.Outlined.DarkMode, "Pure black dark", "True black on AMOLED screens", divider = false) {
            Switch(checked = amoledDark, onCheckedChange = { store.setAmoledDark(it) })
        }
    }
}

@Composable
private fun RowScope.ThemeSwatch(
    label: String,
    key: String,
    selected: String,
    swatchBg: Color,
    dot: Color,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isSel = key == selected
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(14.dp)).background(swatchBg)
                .border(2.dp, if (isSel) cs.primary else cs.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { onSelect(key) },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(dot))
            if (isSel) {
                Icon(Icons.Default.CheckCircle, null, tint = cs.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp,
            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSel) cs.onSurface else cs.onSurfaceVariant)
    }
}

// -------------------------------------------------------------- Settings · Transcription

@Composable
fun TranscriptionSettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val store = com.mehdiacho.yap.data.SettingsStore
    val vtt by store.vttExport.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun stub(what: String) =
        android.widget.Toast.makeText(context, "$what coming soon", android.widget.Toast.LENGTH_SHORT).show()

    SettingsSubScaffold("Transcription", onBack) {
        SubRow(Icons.Outlined.Translate, "Language", "Auto-detect · override", onClick = { stub("Language override") }) {
            Text("Auto", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
        SubRow(Icons.Outlined.Subtitles, "Default VTT / subtitles", "Add timed captions on export") {
            Switch(checked = vtt, onCheckedChange = { store.setVttExport(it) })
        }

        SubEyebrow("ON-DEVICE MODEL")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Standard (Fast)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Text("Best for quick capture", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Default.RadioButtonChecked, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), thickness = 1.dp, color = cs.surfaceVariant)
            Row {
                Text("Storage used", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("148 MB", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { 0.42f }, trackColor = cs.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Download, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("High-accuracy model", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("+320 MB · download to enable", fontSize = 12.sp, color = ext.subdued)
            }
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                    .clickable { stub("Model download") }.padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text("Get", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer) }
        }
    }
}

// --------------------------------------------------------------------- Settings · About

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val store = com.mehdiacho.yap.data.SettingsStore
    var taps by remember { mutableStateOf(0) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    val advancedUnlocked by store.advancedUnlocked.collectAsState()
    val experimental by store.experimentalModel.collectAsState()
    val verboseLogs by store.verboseLogs.collectAsState()
    val remaining = 7 - taps

    SettingsSubScaffold("About", onBack) {
        // App identity row — gradient icon tile + version.
        Row(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6D5BEA), Color(0xFF5B5BD6)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Yap", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("Version 1.0.0" + if (advancedUnlocked) " · build 142" else "",
                    fontSize = 12.sp, color = ext.subdued)
            }
        }

        // Build-number row — Developer-Options-style gesture: tap 7× to unlock Advanced.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainer)
                .clickable(enabled = !advancedUnlocked) {
                    taps++
                    if (taps >= 7) showUnlockDialog = true
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Tag, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Build number", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("142", fontSize = 12.sp, color = ext.subdued)
            }
        }

        // Countdown toast-pill (design shows it after a few taps).
        if (taps in 3..6 && !advancedUnlocked) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1B1B21))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text("Tap $remaining more time${if (remaining == 1) "" else "s"} to unlock Advanced",
                        fontSize = 13.sp, color = Color.White)
                }
            }
        }

        // ADVANCED UNLOCKED panel — dark card with power-user toggles.
        if (advancedUnlocked) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1B21)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, tint = ext.offline, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("ADVANCED UNLOCKED", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp, color = ext.offline)
                }
                Spacer(Modifier.height(8.dp))
                Text("Power-user controls. These can affect stability — change with care.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFC8C5D0))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Science, null, tint = Color(0xFFC5C0FF), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Experimental refine model", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = experimental, onCheckedChange = { store.setExperimentalModel(it) })
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BugReport, null, tint = Color(0xFFC5C0FF), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Verbose debug logs", fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = verboseLogs, onCheckedChange = { store.setVerboseLogs(it) })
                }
            }
        }
    }

    // Warning dialog before unlocking (design · About advanced).
    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false; taps = 0 },
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Default.Warning, null, tint = ext.onOfflineContainer, modifier = Modifier.size(30.dp)) },
            title = { Text("Unlock Advanced?", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 19.sp) },
            text = {
                Text("These power-user options can affect stability and aren't supported. Only continue if you know what you're doing.",
                    fontSize = 13.sp, lineHeight = 19.sp)
            },
            confirmButton = {
                Button(
                    onClick = { store.setAdvancedUnlocked(true); showUnlockDialog = false },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = ext.onOfflineContainer),
                ) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { showUnlockDialog = false; taps = 0 }) { Text("Cancel") } },
        )
    }
}
