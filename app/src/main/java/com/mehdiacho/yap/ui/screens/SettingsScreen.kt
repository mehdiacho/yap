package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.WorkspacePremium
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
import com.mehdiacho.yap.data.AuthManager
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import com.mehdiacho.yap.ui.theme.brandBrush
import com.mehdiacho.yap.ui.theme.brandTint

/**
 * Settings · main list (design section 09) — sectioned rows with hairline dividers,
 * primary-tinted 23dp icons, subdued eyebrows, account panel on Surface-Container.
 */
@Composable
fun SettingsScreen(
    onSignInClick: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onOpenSubscription: () -> Unit = {},
    onOpenCapture: () -> Unit = {},
    onOpenByok: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenTranscription: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenRescue: () -> Unit = {},
) {
    val user by AuthManager.authState().collectAsState(initial = AuthManager.currentUser)
    val signedIn = user != null && user?.isAnonymous == false
    // Cached, so the row is complete on arrival; the auth user is the fallback because a
    // fresh install has no cache until the profile screen has been opened once.
    val profile = com.mehdiacho.yap.data.ProfileClient.lastKnown
    val name = (profile?.displayName ?: user?.displayName)?.takeIf { it.isNotBlank() }
    val cs = MaterialTheme.colorScheme

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = (maxWidth.value * 0.061f).dp.coerceIn(20.dp, 28.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = edge)
        ) {
            // Inset lives INSIDE the scroll container so content scrolls behind the status bar.
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(8.dp))
            Text("Settings", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                letterSpacing = (-0.4).sp, color = cs.onSurface)

            // Account summary panel
            Spacer(Modifier.height(16.dp))
            // Opens the profile either way. It used to be the sign-in button when signed
            // out and inert when signed in, which meant a signed-in user had nowhere to go
            // to set a name or a picture — there was no profile screen to go to.
            Row(
                Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainer)
                    .clickable { onOpenProfile() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val photo = profile?.photoUrl
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(com.mehdiacho.yap.ui.theme.brandBrush(forSmallText = true, seed = 6)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (photo != null) {
                        coil.compose.AsyncImage(
                            model = photo,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        val label = name ?: user?.email?.takeIf { it.isNotBlank() }
                        val initial = if (signedIn) (label?.firstOrNull()?.uppercaseChar() ?: 'U').toString() else "G"
                        Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (signedIn) {
                        Text(name ?: "Add your name",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (name != null) cs.onSurface else cs.primary,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(user?.email?.takeIf { it.isNotBlank() } ?: "Synced across devices",
                            fontSize = 12.sp, color = cs.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    } else {
                        Text("Guest", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text("Sign in to back up your notes", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                if (signedIn) TextButton(onClick = { AuthManager.signOut() }) { Text("Sign out") }
                else Icon(Icons.Default.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }

            // GENERAL — design order: Subscription · Transcription · API key (BYOK) · Capture
            MainEyebrow("GENERAL")
            MainRow(Icons.Outlined.WorkspacePremium, "Subscription & Usage", first = true, onClick = onOpenSubscription)
            MainRow(Icons.Outlined.Insights, "Insights", onClick = onOpenInsights)
            MainRow(Icons.Outlined.GraphicEq, "Transcription", onClick = onOpenTranscription)
            MainRow(Icons.Outlined.Key, "API key (BYOK)", locked = true, onClick = onOpenByok)
            MainRow(Icons.Outlined.Mic, "Capture", onClick = onOpenCapture)
            MainRow(Icons.Outlined.Restore, "Recover recordings", onClick = onOpenRescue)

            // PRIVACY
            MainEyebrow("PRIVACY")
            MainRow(Icons.Outlined.Lock, "Privacy & data", first = true, onClick = onOpenPrivacy)
            MainRow(Icons.Outlined.Palette, "Appearance", onClick = onOpenAppearance)

            // ABOUT
            MainEyebrow("ABOUT")
            MainRow(Icons.Outlined.Info, "About", first = true, onClick = onOpenAbout)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MainEyebrow(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        color = YapTheme.extended.subdued,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
}

/** Design row — 23dp primary icon · 15sp title · chevron (or lock glyph), hairline above. */
@Composable
private fun MainRow(
    icon: ImageVector,
    title: String,
    first: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column {
        if (!first) HorizontalDivider(thickness = 1.dp, color = cs.surfaceContainerHigh)
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 6.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 15.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
            if (locked) {
                Icon(Icons.Outlined.Lock, "Locked", tint = YapTheme.extended.onOfflineContainer,
                    modifier = Modifier.size(16.dp))
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = cs.outlineVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
