package com.mehdiacho.yap.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mehdiacho.yap.data.Profile
import com.mehdiacho.yap.data.ProfileClient
import com.mehdiacho.yap.ui.components.Pill
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme
import com.mehdiacho.yap.ui.theme.brandBrush
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Who you are, across Green Forrest.
 *
 * Split out of Settings rather than folded into it because the account row was doing three
 * unrelated jobs at once — showing who you are, being the sign-in button, and being the
 * sign-out button — and there was nowhere at all to set a name or a picture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit, onSignIn: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // Seeded from cache so the page is whole on arrival — same reasoning as Subscription.
    var profile by remember { mutableStateOf(ProfileClient.lastKnown) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loadError = null
            refreshing = true
            try { profile = ProfileClient.fetch() } catch (e: Exception) {
                loadError = e.message ?: "Couldn't load your profile"
            } finally { refreshing = false }
        }
    }
    LaunchedEffect(Unit) { reload() }

    // Photo Picker rather than READ_MEDIA_IMAGES: the user hands over one image they chose,
    // and we never ask for — or hold — permission to read the rest of their gallery.
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            saving = true
            try {
                profile = ProfileClient.setPhoto(context, uri)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Couldn't set that photo", Toast.LENGTH_LONG).show()
            } finally { saving = false }
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp, start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = cs.onSurface,
                modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Text("Profile", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                letterSpacing = (-0.3).sp, color = cs.onSurface)
            Spacer(Modifier.weight(1f))
            if (refreshing || saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onSurfaceVariant)
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { reload() },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                loadError?.let {
                    Row(
                        Modifier.fillMaxWidth().clip(YapShapes.lg).background(cs.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (profile == null) it else "$it Showing what we last knew.",
                            color = cs.onErrorContainer, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Retry", color = cs.onErrorContainer, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.clickable { reload() })
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------------------------------------------------- identity
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AvatarWithEdit(
                        photoUrl = profile?.photoUrl,
                        initial = initialFor(profile),
                        onEdit = {
                            pickPhoto.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))

                    if (editingName) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            label = { Text("Your name") },
                            singleLine = true,
                            shape = YapShapes.card,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingName = false }) { Text("Cancel") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        saving = true
                                        try {
                                            profile = ProfileClient.setName(nameDraft)
                                            editingName = false
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.message ?: "Couldn't save that name",
                                                Toast.LENGTH_LONG).show()
                                        } finally { saving = false }
                                    }
                                },
                                enabled = !saving && nameDraft.isNotBlank(),
                                shape = CircleShape,
                            ) { Text("Save") }
                        }
                    } else {
                        Text(
                            profile?.displayName ?: "Add your name",
                            fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                            letterSpacing = (-0.3).sp,
                            color = if (profile?.displayName != null) cs.onSurface else cs.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(YapShapes.md).clickable {
                                nameDraft = profile?.displayName.orEmpty()
                                editingName = true
                            }.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        profile?.email?.let {
                            Spacer(Modifier.height(3.dp))
                            Text(it, fontSize = 13.sp, color = ext.subdued,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    if (profile?.isGuest == true) {
                        Spacer(Modifier.height(12.dp))
                        // A guest's name and picture live only on this device's anonymous
                        // account. Saying so beats letting someone decorate a profile that
                        // disappears with the app.
                        Text(
                            "You're signed in as a guest. Sign in to keep your profile and notes if you change phones.",
                            fontSize = 12.sp, lineHeight = 17.sp, color = ext.subdued,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onSignIn, shape = CircleShape) { Text("Sign in") }
                    }
                }

                // ---------------------------------------------------- Green Forrest ID
                Spacer(Modifier.height(28.dp))
                SectionLabel("GREEN FORREST ACCOUNT")
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(YapShapes.panel).background(cs.surfaceContainer)
                        .clickable(enabled = !profile?.greenForrestId.isNullOrBlank()) {
                            clipboard.setText(
                                androidx.compose.ui.text.AnnotatedString(profile!!.greenForrestId))
                            Toast.makeText(context, "ID copied", Toast.LENGTH_SHORT).show()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile?.greenForrestId?.takeIf { it.isNotBlank() } ?: "—",
                            fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            letterSpacing = 1.sp, color = cs.onSurface,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("Your ID across every Green Forrest app.",
                            fontSize = 12.sp, color = ext.subdued)
                    }
                    if (!profile?.greenForrestId.isNullOrBlank()) {
                        Icon(Icons.Outlined.ContentCopy, "Copy ID", tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(19.dp))
                    }
                }
                profile?.memberSince?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(
                            label = "Since ${SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it))}",
                            background = cs.surfaceContainerHigh,
                            contentColor = cs.onSurfaceVariant,
                        )
                        if (profile?.email != null && profile?.emailVerified == false) {
                            Spacer(Modifier.width(8.dp))
                            Pill(
                                label = "Email unverified",
                                background = ext.warningSoft,
                                contentColor = ext.onOfflineContainer,
                            )
                        }
                    }
                }

                // ------------------------------------------------------- other apps
                Spacer(Modifier.height(28.dp))
                SectionLabel("MORE FROM GREEN FORREST")
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clip(YapShapes.panel)
                        .border(1.dp, cs.outlineVariant, YapShapes.panel)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Apps, null, tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Yap is the first", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface)
                        Spacer(Modifier.height(2.dp))
                        // Deliberately not a teaser for apps that don't exist. When there is
                        // a second one, this row becomes a list of real entries; until then
                        // it says the true thing, which is that your ID is already portable.
                        Text(
                            "There's nothing else to install yet. When there is, your Green Forrest ID already gets you in.",
                            fontSize = 12.sp, lineHeight = 17.sp, color = ext.subdued,
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        color = YapTheme.extended.subdued)
}

private fun initialFor(profile: Profile?): String {
    val source = profile?.displayName ?: profile?.email
    return source?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "Y"
}

/**
 * The avatar, with the camera affordance attached to it rather than to a row elsewhere.
 *
 * Falls back through photo → initial on the brand mark. The gradient earns its place here:
 * an avatar is an identity moment, which is the only thing it is for.
 */
@Composable
private fun AvatarWithEdit(photoUrl: String?, initial: String, onEdit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(brandBrush(forSmallText = true, seed = 9))
                .clickable { onEdit() },
            contentAlignment = Alignment.Center,
        ) {
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = "Profile picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 38.sp,
                    fontFamily = Outfit)
            }
        }
        Box(
            Modifier.size(30.dp).clip(CircleShape)
                .background(cs.surfaceContainerHighest)
                .border(2.dp, cs.background, CircleShape)
                .clickable { onEdit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PhotoCamera, "Change picture", tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp))
        }
    }
}
