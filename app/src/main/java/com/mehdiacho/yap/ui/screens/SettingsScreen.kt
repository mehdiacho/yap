package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.AudioCache
import com.mehdiacho.yap.data.AuthManager

@Composable
fun SettingsScreen(
    onSignInClick: () -> Unit = {},
    onOpenSubscription: () -> Unit = {},
) {
    val user by AuthManager.authState().collectAsState(initial = AuthManager.currentUser)
    val signedIn = user != null && user?.isAnonymous == false
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 22.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Account
        Row(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = !signedIn) { onSignInClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                // displayName can be a non-null EMPTY string — never .first() it blindly.
                val label = user?.displayName?.takeIf { it.isNotBlank() }
                    ?: user?.email?.takeIf { it.isNotBlank() }
                val initial = if (signedIn) (label?.firstOrNull()?.uppercaseChar() ?: 'U').toString() else "G"
                Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (signedIn) {
                    Text(
                        user?.displayName?.takeIf { it.isNotBlank() }
                            ?: user?.email?.takeIf { it.isNotBlank() } ?: "Account",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        user?.email?.takeIf { it.isNotBlank() } ?: "Synced across devices",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Guest", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Tap to sign in — keeps all your notes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (signedIn) {
                TextButton(onClick = { AuthManager.signOut() }) { Text("Sign out") }
            } else {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            "GENERAL",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        SettingsItem(icon = Icons.Default.WorkspacePremium, title = "Subscription & Usage", onClick = onOpenSubscription)
        SettingsItem(icon = Icons.Default.Mic, title = "Capture")
        FloatingBubbleItem()
        
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "PRIVACY",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        SettingsItem(icon = Icons.Default.Security, title = "Privacy & data")
        SettingsItem(icon = Icons.Default.Palette, title = "Appearance")

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "STORAGE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        ClearDownloadsItem()

        Spacer(modifier = Modifier.height(40.dp))
    }
}

/** Toggles the system-overlay floating mic bubble (asks for the overlay permission once). */
@Composable
private fun FloatingBubbleItem() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(com.mehdiacho.yap.overlay.FloatingBubble.isEnabled(context)) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (com.mehdiacho.yap.overlay.FloatingBubble.hasPermission(context)) {
            com.mehdiacho.yap.overlay.FloatingBubble.enable(context); enabled = true
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.BubbleChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Floating record button", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("A bubble on your screen to record anytime", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

/** Frees space by deleting downloaded copies of online-only notes (cloud copies remain). */
@Composable
private fun ClearDownloadsItem() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheBytes by remember { mutableLongStateOf(AudioCache.totalBytes(context)) }
    val sizeLabel = remember(cacheBytes) {
        when {
            cacheBytes <= 0L -> "Nothing downloaded"
            cacheBytes < 1_048_576L -> "%.0f KB".format(cacheBytes / 1024.0)
            else -> "%.1f MB".format(cacheBytes / 1_048_576.0)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = cacheBytes > 0) {
                val freed = AudioCache.clear(context)
                cacheBytes = AudioCache.totalBytes(context)
                android.widget.Toast.makeText(
                    context,
                    "Freed %.1f MB — cloud copies are untouched".format(freed / 1_048_576.0),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Clear downloads", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "$sizeLabel · notes marked available offline are kept",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
    }
}
