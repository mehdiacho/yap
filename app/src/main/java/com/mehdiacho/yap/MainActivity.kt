package com.mehdiacho.yap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
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
import com.mehdiacho.yap.navigation.Routes
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.screens.AuthScreen
import com.mehdiacho.yap.ui.screens.CaptureScreen
import com.mehdiacho.yap.ui.screens.LibraryScreen
import com.mehdiacho.yap.ui.screens.SessionDetailScreen
import com.mehdiacho.yap.ui.screens.SettingsScreen
import com.mehdiacho.yap.ui.screens.SpikeScreen
import com.mehdiacho.yap.ui.screens.SubscriptionScreen
import com.mehdiacho.yap.ui.screens.TrashScreen
import com.mehdiacho.yap.ui.theme.YapTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val autoStart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra(EXTRA_START_RECORDING, false) == true) autoStart.value = true
        intent?.let { handleShare(it) }
        com.mehdiacho.yap.overlay.FloatingBubble.startIfEnabled(this)
        setContent {
            YapTheme {
                MainScreen(viewModel, autoStart)
            }
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

    private fun guessMimeFromUri(uri: Uri): String? {
        val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        return if (ext.isNotEmpty())
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        else null
    }

    companion object {
        const val EXTRA_START_RECORDING = "start_recording"
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel, autoStart: MutableState<Boolean> = mutableStateOf(false)) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Post-capture / share: open the freshly captured note (shows the "Transcribing…" state).
    // StateFlow-based so a cold-start share isn't missed before this subscribes.
    val pendingOpen by viewModel.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { sessionId ->
            navController.navigate(Routes.createSessionDetailRoute(sessionId))
            viewModel.consumePendingOpen()
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
        bottomBar = {
            if (currentRoute in listOf(Routes.CAPTURE, Routes.LIBRARY, Routes.SETTINGS)) {
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
        NavHost(
            navController = navController,
            startDestination = Routes.CAPTURE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.CAPTURE) {
                CaptureScreen(viewModel = viewModel, autoStart = autoStart)
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    viewModel = viewModel,
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
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
                    onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                )
            }
            composable(Routes.AUTH) {
                AuthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SUBSCRIPTION) {
                SubscriptionScreen(onBack = { navController.popBackStack() })
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
                    onBack = { navController.popBackStack() },
                )
            }
        }
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
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Design: 56 x 30 pill, active fill only.
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isSelected) selectedIcon else unselectedIcon,
                contentDescription = label,
                modifier = Modifier.size(23.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
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
