package com.mehdiacho.yap

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.navigation.Routes
import com.mehdiacho.yap.ui.MainViewModel
import androidx.lifecycle.lifecycleScope
import com.mehdiacho.yap.ui.screens.AboutSettingsScreen
import com.mehdiacho.yap.ui.screens.AppearanceSettingsScreen
import com.mehdiacho.yap.ui.screens.AuthScreen
import com.mehdiacho.yap.ui.screens.ByokKeyScreen
import com.mehdiacho.yap.ui.screens.InsightsScreen
import com.mehdiacho.yap.ui.screens.ProfileScreen
import com.mehdiacho.yap.ui.screens.CaptureScreen
import com.mehdiacho.yap.ui.screens.CaptureSettingsScreen
import com.mehdiacho.yap.ui.screens.UnlockByokScreen
import com.mehdiacho.yap.ui.screens.PrivacySettingsScreen
import com.mehdiacho.yap.ui.screens.RescueRecordingsScreen
import com.mehdiacho.yap.ui.screens.TranscriptionSettingsScreen
import com.mehdiacho.yap.ui.screens.LibraryScreen
import com.mehdiacho.yap.ui.screens.SessionDetailScreen
import com.mehdiacho.yap.ui.screens.SettingsScreen
import com.mehdiacho.yap.ui.screens.SpikeScreen
import com.mehdiacho.yap.ui.screens.SubscriptionScreen
import com.mehdiacho.yap.ui.screens.TrashScreen
import com.mehdiacho.yap.ui.theme.YapTheme
import kotlinx.coroutines.launch

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires it for the app lock.
class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val autoStart = mutableStateOf(false)

    /** Shows the biometric/device-credential prompt; flips [unlocked] on success. */
    private fun promptUnlock() {
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlocked.value = true
                }
            },
        )
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Yap")
            .setSubtitle("Your notes are locked")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Apply AFTER enableEdgeToEdge so these win over the styles it sets. MIUI/HyperOS applies a
        // translucent contrast scrim behind the gesture pill in light mode; disabling contrast
        // enforcement + fully transparent bars is the most it honors. Content still flows to the
        // true bottom edge (edge-to-edge), so the bottom bar background reaches behind the pill.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < 35) {
            // Deprecated no-ops on Android 15+ (edge-to-edge is enforced there); still needed
            // below 35 for the MIUI/HyperOS nav-bar scrim fix.
            @Suppress("DEPRECATION")
            window.statusBarColor = AndroidColor.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = AndroidColor.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true) autoStart.value = true
        intent?.let { handleShare(it) }
        com.mehdiacho.yap.data.SettingsStore.init(this)
        com.mehdiacho.yap.data.AccountClient.initCache(this)
        com.mehdiacho.yap.data.ProfileClient.initCache(this)
        com.mehdiacho.yap.data.PersonaClient.initCache(this)
        warmProfileCache()
        // One-shot: turns an invite link install into an attributed referral.
        com.mehdiacho.yap.data.InstallReferral.applyIfPresent(this)
        com.mehdiacho.yap.data.BillingManager.init(this)
        com.mehdiacho.yap.data.BillingManager.reconcilePending()
        com.mehdiacho.yap.overlay.FloatingBubble.startIfEnabled(this)
        publishShareShortcut()
        setContent {
            // Theme prefs drive the app theme live (Appearance settings).
            val themeMode by com.mehdiacho.yap.data.SettingsStore.themeMode.collectAsState()
            val dynamicColor by com.mehdiacho.yap.data.SettingsStore.dynamicColor.collectAsState()
            val amoledDark by com.mehdiacho.yap.data.SettingsStore.amoledDark.collectAsState()
            YapTheme(
                darkTheme = when (themeMode) {
                    "light" -> false
                    "dark" -> true
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                },
                dynamicColor = dynamicColor,
                amoled = amoledDark,
            ) {
                val appLock by com.mehdiacho.yap.data.SettingsStore.appLock.collectAsState()
                val isUnlocked by unlocked
                if (appLock && !isUnlocked) {
                    LockScreen(onUnlock = ::promptUnlock)
                } else {
                    MainScreen(viewModel, autoStart)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.mehdiacho.yap.ui.Notifier.appInForeground = true
        // Evaluate the lock only when the app genuinely left the foreground (or cold-started) —
        // rotation keeps its unlock. Within the configured timeout the gate silently opens, so
        // e.g. tapping a "note polished" notification a minute later needs no fresh unlock
        // (unless the user chose "Immediately").
        if (leftForeground) {
            unlocked.value = !com.mehdiacho.yap.data.SettingsStore.shouldRelock()
            leftForeground = false
        }
    }

    override fun onStop() {
        super.onStop()
        com.mehdiacho.yap.ui.Notifier.appInForeground = false
        // Rotation also passes through onStop — don't count it as leaving the app.
        if (!isChangingConfigurations) {
            com.mehdiacho.yap.data.SettingsStore.noteBackgrounded()
            leftForeground = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_RECORDING, false)) autoStart.value = true
        handleShare(intent)
    }

    /** Handle audio/video/text shared into Yap from another app. */
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val declared = intent.type ?: "*/*"
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.getItemAt(0)?.uri

        // Many apps (e.g. the system recorder) share audio with a generic */* or
        // application/octet-stream type, so resolve the concrete MIME from the file itself.
        val mime = when {
            declared.startsWith("audio/") || declared.startsWith("video/") || declared.startsWith("text/") -> declared
            stream != null -> contentResolver.getType(stream) ?: guessMimeFromUri(stream) ?: declared
            else -> declared
        }
        android.util.Log.d("YapShare", "declared=$declared resolved=$mime stream=$stream")

        when {
            (mime.startsWith("audio/") || mime.startsWith("video/")) && stream != null ->
                viewModel.importSharedAudio(stream, mime)
            mime.startsWith("text/") -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
                text?.takeIf { it.isNotBlank() }?.let { viewModel.importSharedText(it) }
            }
            stream != null -> {
                // Generic type but we have a file — fall back to an extension guess.
                val guessed = guessMimeFromUri(stream)
                if (guessed != null && (guessed.startsWith("audio/") || guessed.startsWith("video/"))) {
                    viewModel.importSharedAudio(stream, guessed)
                } else {
                    android.util.Log.w("YapShare", "unhandled share: mime=$mime stream=$stream")
                }
            }
        }
    }

    /**
     * Publishes the dynamic sharing shortcut that puts a "Transcribe in Yap" row in other
     * apps' share sheets for audio/video (pairs with the share-target in shortcuts.xml).
     */
    /**
     * Pull the name and avatar in the background so the home greeting and the settings row
     * are right on the first frame after an update, instead of only once the profile screen
     * has been visited.
     *
     * Skipped for anonymous guests on purpose: the fetch mints a Green Forrest ID, and doing
     * that for every fresh install would fill the index with identities for people who never
     * signed up. A guest who opens the profile screen gets one there, on demand.
     */
    private fun warmProfileCache() {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (user == null || user.isAnonymous) return
        lifecycleScope.launch {
            runCatching { com.mehdiacho.yap.data.ProfileClient.fetch() }
        }
    }

    private fun publishShareShortcut() {
        runCatching {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(this, "transcribe_share")
                .setShortLabel(getString(R.string.share_target_label))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(this, R.drawable.ic_tile_mic))
                .setIntent(Intent(Intent.ACTION_VIEW, null, this, MainActivity::class.java))
                .setCategories(setOf("com.mehdiacho.yap.category.AUDIO_SHARE"))
                .setLongLived(true)
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        }
    }

    private fun guessMimeFromUri(uri: Uri): String? {
        val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        return if (ext.isNotEmpty())
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        else null
    }

    companion object {
        const val EXTRA_START_RECORDING = "start_recording"

        // App-lock gate. Process-level (not per-activity) so recreation — rotation, theme
        // change — doesn't re-lock; onStart + the persisted background timestamp decide.
        private val unlocked = mutableStateOf(false)
        private var leftForeground = true
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, autoStart: MutableState<Boolean> = mutableStateOf(false)) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Post-capture auto-open is DISABLED: it hijacked whatever screen you were on (worst case:
    // mid-onboarding, where leaving reset the flow). Progress now shows on the Capture tab's
    // LAST NOTE card, which is tappable to open the note. The event is still consumed so a
    // stale id never fires later. (Share-ins land on Capture's last-note card the same way.)
    val pendingOpen by viewModel.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { viewModel.consumePendingOpen() }
    }

    // One-shot notices (e.g. "that addition was too long, saved as its own note"). These
    // used to be log-only, which meant a failed append looked exactly like success.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notice by viewModel.notice.collectAsState()
    LaunchedEffect(notice) {
        notice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeNotice()
        }
    }

    // Quick Settings tile / external entry: jump to Capture so it can auto-start recording.
    LaunchedEffect(autoStart.value) {
        if (autoStart.value) {
            navController.navigate(Routes.CAPTURE) { launchSingleTop = true }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // We handle insets ourselves: nav bar draws under the gesture pill (flush to the
        // bottom edge), content clears the status bar via statusBarsPadding below.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Capture's immersive states (recording, processing, quota, etc.) hide the nav bar,
            // as does Library's multi-select mode (its 4-action bar replaces the nav).
            val captureImmersive by com.mehdiacho.yap.ui.CaptureChrome.immersive.collectAsState()
            val librarySelecting by com.mehdiacho.yap.ui.LibraryChrome.selectionActive.collectAsState()
            val showNav = currentRoute in listOf(Routes.CAPTURE, Routes.LIBRARY, Routes.SETTINGS) &&
                !(currentRoute == Routes.CAPTURE && captureImmersive) &&
                !(currentRoute == Routes.LIBRARY && librarySelecting)
            if (showNav) {
                fun go(route: String) = navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                // Flush-to-bottom nav: background reaches the screen edge, items sit above
                // the gesture inset (design bar = 72dp on #F2EFF7 surfaceContainer).
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavBarItem(
                            Icons.Filled.Mic, Icons.Outlined.Mic, "Capture",
                            currentRoute == Routes.CAPTURE
                        ) { go(Routes.CAPTURE) }
                        NavBarItem(
                            Icons.Filled.LibraryBooks, Icons.Outlined.LibraryBooks, "Library",
                            currentRoute == Routes.LIBRARY
                        ) { go(Routes.LIBRARY) }
                        NavBarItem(
                            Icons.Filled.Settings, Icons.Outlined.Settings, "Settings",
                            currentRoute == Routes.SETTINGS
                        ) { go(Routes.SETTINGS) }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    ) { innerPadding ->
        val navContext = androidx.compose.ui.platform.LocalContext.current
        NavHost(
            navController = navController,
            startDestination = if (com.mehdiacho.yap.ui.screens.Onboarding.isDone(navContext))
                Routes.CAPTURE else Routes.ONBOARDING,
            // Navigation-compose's default is a 700ms cross-fade, which reads as sluggish.
            // 220ms fade + a light slide keeps switches feeling instant.
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(220)) { it / 16 }
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                    androidx.compose.animation.slideInHorizontally(
                        androidx.compose.animation.core.tween(220)) { -it / 16 }
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.slideOutHorizontally(
                        androidx.compose.animation.core.tween(220)) { it / 16 }
            },
            // contentWindowInsets is zeroed above so screens draw edge-to-edge (behind the status
            // bar). Each screen applies its own status-bar inset — inside its scroll container
            // where scrolling-behind-the-bar is wanted. Bottom: the nav bar handles its own
            // gesture inset; navbar-less screens add navigationBarsPadding themselves.
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.ONBOARDING) {
                com.mehdiacho.yap.ui.screens.OnboardingFlow(viewModel = viewModel) {
                    navController.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(
                    viewModel = viewModel,
                    autoStart = autoStart,
                    onOpenSession = { navController.navigate(Routes.createSessionDetailRoute(it)) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    viewModel = viewModel,
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                    onRecordNew = {
                        // FAB: jump to Capture and start recording immediately.
                        autoStart.value = true
                        navController.navigate(Routes.CAPTURE) { launchSingleTop = true }
                    },
                ) { sessionId ->
                    navController.navigate(Routes.createSessionDetailRoute(sessionId))
                }
            }
            composable(Routes.TRASH) {
                TrashScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onSignInClick = { navController.navigate(Routes.AUTH) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenInsights = { navController.navigate(Routes.INSIGHTS) },
                    onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                    onOpenCapture = { navController.navigate(Routes.SETTINGS_CAPTURE) },
                    onOpenByok = { navController.navigate(Routes.BYOK_KEY) },
                    onOpenPrivacy = { navController.navigate(Routes.SETTINGS_PRIVACY) },
                    onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                    onOpenTranscription = { navController.navigate(Routes.SETTINGS_TRANSCRIPTION) },
                    onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                    onOpenRescue = { navController.navigate(Routes.SETTINGS_RESCUE) },
                )
            }
            composable(Routes.BYOK_KEY) {
                ByokKeyScreen(
                    onBack = { navController.popBackStack() },
                    onOpenUnlock = { navController.navigate(Routes.BYOK_UNLOCK) },
                )
            }
            composable(Routes.BYOK_UNLOCK) { UnlockByokScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_CAPTURE) { CaptureSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_PRIVACY) { PrivacySettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_APPEARANCE) { AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_TRANSCRIPTION) { TranscriptionSettingsScreen(onBack = { navController.popBackStack() }, viewModel = viewModel) }
            composable(Routes.SETTINGS_ABOUT) { AboutSettingsScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.SETTINGS_RESCUE) {
                RescueRecordingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.AUTH) {
                AuthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.INSIGHTS) {
                InsightsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSignIn = { navController.navigate(Routes.AUTH) },
                )
            }
            composable(Routes.SUBSCRIPTION) {
                SubscriptionScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlans = { navController.navigate(Routes.PLANS) },
                )
            }
            composable(Routes.PLANS) {
                com.mehdiacho.yap.ui.screens.PlansScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SPIKE) {
                SpikeScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SESSION_DETAIL) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                SessionDetailScreen(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                    onOpenApiKey = { navController.navigate(Routes.BYOK_KEY) },
                    onAppendRecording = { session ->
                        // Append-to-note: the next recording merges into this note.
                        viewModel.setAppendTarget(session)
                        autoStart.value = true
                        navController.navigate(Routes.CAPTURE) { launchSingleTop = true }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/** App-lock gate — auto-fires the biometric prompt, with a manual retry button. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(84.dp).clip(YapShapes.xl).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Fingerprint, null,
                    tint = cs.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Yap is locked", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Text("Unlock with your fingerprint or screen lock.",
                fontSize = 14.sp, color = cs.onSurfaceVariant)
        }
        Button(onClick = onUnlock, shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("Unlock", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(38.dp))
    }
}

@Composable
fun NavBarItem(
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(YapShapes.panel)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Design: 56 x 30 pill, active fill only — now the brand gradient rather than a flat
        // container. The glyph inside stays a solid colour: gradient-on-gradient would lose
        // the icon entirely, and the selected item has to stay the most readable thing here.
        val brand = com.mehdiacho.yap.ui.theme.brandBrush(forSmallText = true, seed = 1)
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .clip(YapShapes.panel)
                .then(
                    if (isSelected) Modifier.background(brand)
                    else Modifier.background(Color.Transparent)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isSelected) selectedIcon else unselectedIcon,
                contentDescription = label,
                modifier = Modifier.size(23.dp),
                tint = if (isSelected) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
