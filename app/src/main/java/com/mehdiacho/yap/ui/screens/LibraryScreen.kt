package com.mehdiacho.yap.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.BuildConfig
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.LibraryChrome
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.rememberOfflineState
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onOpenTrash: () -> Unit = {},
    onRecordNew: () -> Unit = {},
    onNavigateToDetail: (String) -> Unit,
) {
    val sessions by viewModel.allSessions.collectAsState()
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    var searchFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    var activeFilter by remember { androidx.compose.runtime.mutableStateOf("All") }
    var selection by remember { androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet()) }
    var confirmTrash by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showSkeleton by remember { androidx.compose.runtime.mutableStateOf(false) } // debug preview
    val selecting = selection.isNotEmpty()
    val searching = query.isNotBlank() || searchFocused

    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val context = androidx.compose.ui.platform.LocalContext.current
    fun stub(what: String) =
        android.widget.Toast.makeText(context, "$what coming soon", android.widget.Toast.LENGTH_SHORT).show()

    // Library · offline — banner + pending-count pill (recordings waiting to sync/refine).
    val (offline, recheckOnline) = rememberOfflineState()
    val pendingCount = remember(sessions) { sessions.count { it.status == "OFFLINE_PENDING" } }
    var showSyncSheet by remember { androidx.compose.runtime.mutableStateOf(false) }

    // Multi-select replaces the app bottom nav with the 4-action bar (design · multi-select).
    LaunchedEffect(selecting) { LibraryChrome.selectionActive.value = selecting }
    DisposableEffect(Unit) { onDispose { LibraryChrome.selectionActive.value = false } }

    val categories = remember(sessions) {
        listOf("All") + sessions.map { it.category }.filter { it.isNotBlank() }.distinct().take(6)
    }
    val filtered = remember(sessions, query, activeFilter) {
        val q = query.trim().lowercase()
        sessions.filter { s ->
            (activeFilter == "All" || s.category == activeFilter) &&
                (q.isBlank() ||
                    s.title.lowercase().contains(q) ||
                    s.draftText.lowercase().contains(q) ||
                    (s.formattedText?.lowercase()?.contains(q) == true) ||
                    s.category.lowercase().contains(q) ||
                    s.topicCluster.lowercase().contains(q))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val edge = (maxWidth.value * 0.061f).dp.coerceIn(20.dp, 28.dp)
        val avatar = (maxWidth.value * 0.106f).dp.coerceIn(34.dp, 44.dp)
        val fab = (maxWidth.value * 0.167f).dp.coerceIn(56.dp, 64.dp)

        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                // Contextual selection bar — full-bleed Primary-Container (design · multi-select).
                Row(
                    Modifier.fillMaxWidth().background(cs.primaryContainer).statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Close, "Cancel selection", tint = cs.onPrimaryContainer,
                        modifier = Modifier.clickable { selection = emptySet() })
                    Spacer(Modifier.width(14.dp))
                    Text("${selection.size} selected", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, color = cs.onPrimaryContainer)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.SelectAll, "Select all", tint = cs.onPrimaryContainer,
                        modifier = Modifier.size(22.dp).clickable { selection = filtered.map { it.id }.toSet() })
                }
            } else {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                if (!searching) {
                    // 1 · Header row — Headline + avatar (§3.7)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = edge).padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Library", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                            letterSpacing = (-0.4).sp, color = cs.onSurface,
                            modifier = Modifier.weight(1f).combinedClickable(
                                onClick = {},
                                onLongClick = { if (BuildConfig.DEBUG) showSkeleton = !showSkeleton },
                            ))
                        // Pending-sync pill (design · offline): cloud_sync + count. Tap → Sync sheet.
                        if (offline && pendingCount > 0) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(ext.offlineContainer)
                                    .clickable { showSyncSheet = true }
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.CloudSync, null, tint = ext.onOfflineContainer,
                                    modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("$pendingCount", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = ext.onOfflineContainer)
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        Icon(Icons.Outlined.Delete, "Trash", tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp).clickable { onOpenTrash() })
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(avatar).clip(CircleShape).background(cs.primaryContainer),
                            contentAlignment = Alignment.Center) {
                            Text("Y", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = cs.onPrimaryContainer)
                        }
                    }
                    // Offline banner — "Offline · showing cached notes".
                    if (offline) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = edge).padding(top = 12.dp)
                                .clip(RoundedCornerShape(12.dp)).background(ext.warningSoft)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.CloudOff, null, tint = ext.onOfflineContainer,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Offline · showing cached notes", fontSize = 12.sp, color = ext.onOfflineContainer)
                        }
                    }
                }
            }

            if (showSkeleton) {
                LibrarySkeleton(edge)
                return@Column
            }

            Column(Modifier.weight(1f).padding(horizontal = edge)) {
                if (!selecting) {
                    // 2 · Search field (§2.10) — focused variant gets back chevron + 2dp border.
                    Spacer(Modifier.height(if (searching) 12.dp else 14.dp))
                    SearchField(
                        query = query, onQueryChange = { query = it },
                        active = searching,
                        onFocusChange = { searchFocused = it },
                        onExit = { query = ""; searchFocused = false },
                        onVoice = { stub("Voice search") },
                    )
                }

                when {
                    // Search: results count / no-results (design · search)
                    searching && query.isNotBlank() -> {
                        if (filtered.isEmpty()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Outlined.SearchOff, null, tint = cs.outlineVariant, modifier = Modifier.size(60.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("No matches", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp, color = cs.onSurface)
                                Spacer(Modifier.height(6.dp))
                                Text("Nothing for \"$query\". Try fewer words or clear filters.",
                                    fontSize = 14.sp, lineHeight = 21.sp, color = ext.subdued, textAlign = TextAlign.Center)
                            }
                        } else {
                            Text("${filtered.size} result${if (filtered.size == 1) "" else "s"}",
                                fontSize = 12.sp, color = ext.subdued,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                                items(filtered, key = { it.id }) { session ->
                                    SessionCard(session, highlight = query.trim(),
                                        onClick = { onNavigateToDetail(session.id) })
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }

                    sessions.isEmpty() -> {
                        // Library · empty
                        Column(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Outlined.GraphicEq, null, tint = cs.outlineVariant, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("No notes yet", fontFamily = Outfit, fontWeight = FontWeight.Bold,
                                fontSize = 20.sp, color = cs.onSurface)
                            Spacer(Modifier.height(6.dp))
                            Text("Tap the mic to capture your first idea. Drafts appear here instantly.",
                                fontSize = 14.sp, lineHeight = 21.sp, color = ext.subdued, textAlign = TextAlign.Center)
                        }
                    }

                    else -> {
                        // 3 · Filter chips (design: outlined radius-10; active = filled Primary + check)
                        if (!selecting && categories.size > 1) {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                categories.forEach { cat ->
                                    FilterChipSquare(cat, active = cat != "All" && cat == activeFilter) {
                                        activeFilter = if (activeFilter == cat) "All" else cat
                                    }
                                }
                            }
                        }
                        // Filtered count row — "N notes · Clear all"
                        if (activeFilter != "All" && !selecting) {
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${filtered.size} note${if (filtered.size == 1) "" else "s"}",
                                    fontSize = 12.sp, color = ext.subdued)
                                Spacer(Modifier.weight(1f))
                                Text("Clear all", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = cs.primary, modifier = Modifier.clickable { activeFilter = "All" })
                            }
                        } else {
                            Spacer(Modifier.height(if (selecting) 16.dp else 12.dp))
                        }

                        // 4 · Flexible scrolling list with recency eyebrows (§3.7)
                        val now = System.currentTimeMillis()
                        val sections = remember(filtered) {
                            filtered.groupBy { s ->
                                val age = now - s.createdAt
                                when {
                                    age < 24 * 3600_000L -> "TODAY"
                                    age < 7 * 24 * 3600_000L -> "THIS WEEK"
                                    else -> "EARLIER"
                                }
                            }
                        }
                        val order = listOf("TODAY", "THIS WEEK", "EARLIER")
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
                            order.forEach { key ->
                                val group = sections[key] ?: return@forEach
                                if (activeFilter == "All" && !selecting) {
                                    item(key = "hdr-$key") {
                                        Text(key, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                                            color = ext.subdued, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                                    }
                                }
                                items(group, key = { it.id }) { session ->
                                    SessionCard(
                                        session,
                                        selected = session.id in selection,
                                        selectionMode = selecting,
                                        onClick = {
                                            if (selecting) selection =
                                                if (session.id in selection) selection - session.id else selection + session.id
                                            else onNavigateToDetail(session.id)
                                        },
                                        onLongClick = {
                                            selection = if (session.id in selection) selection - session.id else selection + session.id
                                        },
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Multi-select 4-action bar — replaces the bottom nav (design · multi-select).
            if (selecting) {
                Column(Modifier.fillMaxWidth().background(cs.surfaceContainer)) {
                    HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp, start = 14.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        SelectionAction(Icons.Outlined.Hub, "Group", cs.onPrimaryContainer) { stub("Group") }
                        SelectionAction(Icons.Outlined.AutoAwesome, "Formalize", cs.onPrimaryContainer) { stub("Formalize") }
                        SelectionAction(Icons.Outlined.PushPin, "Pin", cs.onPrimaryContainer) { stub("Pin") }
                        SelectionAction(Icons.Outlined.Delete, "Delete", cs.error) { confirmTrash = true }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }

        // 5 · FAB (§2.15) — squircle, primary, above the bottom nav; hidden while selecting/searching.
        if (!selecting && !searching && sessions.isNotEmpty() && !showSkeleton) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp)
                    .size(fab)
                    .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = cs.primary, ambientColor = cs.primary)
                    .clip(RoundedCornerShape(20.dp)).background(cs.primary)
                    .clickable { onRecordNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, "New note", tint = cs.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }

    if (showSyncSheet) {
        SyncSheet(sessions = sessions, onSyncNow = { recheckOnline() }, onDismiss = { showSyncSheet = false })
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("Move ${selection.size} note${if (selection.size == 1) "" else "s"} to trash?") },
            text = { Text("They can be restored from the trash for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.trashSessions(selection); selection = emptySet(); confirmTrash = false
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/** §2.10 search field. Active variant: Surface bg, 2dp Primary border, back chevron + clear. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onExit: () -> Unit,
    onVoice: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(50))
            .background(if (active) cs.surface else cs.surfaceContainerHigh)
            .then(if (active) Modifier.border(2.dp, cs.primary, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Exit search", tint = cs.primary,
                modifier = Modifier.size(22.dp).clickable { focusManager.clearFocus(); onExit() })
        } else {
            Icon(Icons.Outlined.Search, "Search", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search notes", fontSize = 14.sp, color = ext.subdued)
            BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = cs.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) },
            )
        }
        if (active && query.isNotEmpty()) {
            Icon(Icons.Default.Close, "Clear", tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable { onQueryChange("") })
        } else if (!active) {
            Icon(Icons.Outlined.Mic, "Voice search", tint = cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp).clickable { onVoice() })
        }
    }
}

/** Filter chip — design: radius 10, outlined idle / filled Primary with check when active. */
@Composable
private fun FilterChipSquare(label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) cs.primary else cs.surface)
            .then(if (active) Modifier else Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp)))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) cs.onPrimary else cs.onSurfaceVariant)
    }
}

/** Library · loading — skeleton search bar, chips, and cards with a slow pulse. */
@Composable
private fun LibrarySkeleton(edge: androidx.compose.ui.unit.Dp) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "libskel")
    val alpha by t.animateFloat(1f, 0.55f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val bar = cs.surfaceContainerHigh.copy(alpha = alpha)
    Column(Modifier.padding(horizontal = edge)) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(26.dp)).background(bar))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80, 64, 70).forEach { w ->
                Box(Modifier.width(w.dp).height(30.dp).clip(RoundedCornerShape(10.dp)).background(bar))
            }
        }
        Spacer(Modifier.height(18.dp))
        repeat(3) { i ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .border(1.dp, bar, RoundedCornerShape(18.dp)).padding(14.dp)
            ) {
                Box(Modifier.width((60 - i * 5).dp).height(18.dp).clip(RoundedCornerShape(6.dp)).background(bar))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.9f - i * 0.05f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(bar))
                if (i < 2) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.75f - i * 0.07f).height(13.dp).clip(RoundedCornerShape(6.dp)).background(bar))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    session: Session,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    highlight: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) cs.primary else cs.outlineVariant,
                shape = RoundedCornerShape(18.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp)
    ) {
        // Selection badge — 22dp check circle / empty ring (design · multi-select).
        if (selectionMode) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(cs.primary)
                        else Modifier.border(2.dp, cs.outlineVariant, CircleShape)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
            }
        }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(cs.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(session.category.ifBlank { "Note" }, color = cs.onPrimaryContainer,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                if (session.topicCluster.isNotBlank() && session.topicCluster != "General") {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(7.dp)).background(cs.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(session.topicCluster, color = cs.onSurfaceVariant, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!selectionMode) {
                    // Offline-availability glyph on THIS device.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val availableOffline = remember(session.audioPath) {
                        session.audioPath != null && java.io.File(session.audioPath).exists()
                    }
                    val cached = remember(session.storagePath) {
                        com.mehdiacho.yap.data.AudioCache.isCached(context, session.storagePath)
                    }
                    when {
                        availableOffline -> Icon(Icons.Outlined.OfflinePin, "Available offline",
                            tint = cs.outline, modifier = Modifier.size(16.dp))
                        cached -> Icon(Icons.Outlined.CloudDone, "Downloaded",
                            tint = cs.primary, modifier = Modifier.size(16.dp))
                        session.storagePath != null -> Icon(Icons.Outlined.CloudQueue, "Online only",
                            tint = cs.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(highlightText(session.title, highlight, cs.primaryContainer, cs.onPrimaryContainer),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (!selectionMode) {
                Spacer(Modifier.height(4.dp))
                Text(highlightText(snippetAround(session, highlight), highlight, cs.primaryContainer, cs.onPrimaryContainer),
                    fontSize = 12.sp, color = cs.onSurfaceVariant,
                    lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(session.status)
                    if (session.audioPath != null || session.storagePath != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.GraphicEq, "Has audio", tint = Color(0xFF7A7886),
                            modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(relativeTimeLib(session.createdAt), fontSize = 11.sp, color = ext.subdued)
                }
            }
        }
    }
}

/** Builds an AnnotatedString with <mark>-style highlight spans on query matches. */
private fun highlightText(
    text: String,
    query: String?,
    bg: Color,
    fg: Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    if (query.isNullOrBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    val lower = text.lowercase(); val q = query.lowercase()
    while (i < text.length) {
        val idx = lower.indexOf(q, i)
        if (idx < 0) { append(text.substring(i)); break }
        append(text.substring(i, idx))
        pushStyle(SpanStyle(background = bg, color = fg))
        append(text.substring(idx, idx + q.length))
        pop()
        i = idx + q.length
    }
}

/** Snippet centered on the first query match (falls back to the leading text). */
private fun snippetAround(session: Session, query: String?): String {
    val body = session.formattedText ?: session.draftText
    if (query.isNullOrBlank()) return body.take(90)
    val idx = body.lowercase().indexOf(query.lowercase())
    if (idx < 0) return body.take(90)
    val start = (idx - 30).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + body.substring(start, (start + 100).coerceAtMost(body.length))
}

/** §2.5 status chip. */
@Composable
fun StatusChip(status: String) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val refined = status == "REFINED" || status == "DONE"
    val pending = status == "OFFLINE_PENDING"
    val polishing = status == "POLISHING" || status == "TRANSCRIBING"
    val (bg, fg) = when {
        refined -> ext.refinedContainer to ext.onRefinedContainer
        pending -> ext.warningSoft to cs.onSurfaceVariant
        polishing -> cs.primaryContainer to cs.onPrimaryContainer
        else -> ext.draftNeutral to cs.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            refined -> {
                Icon(Icons.Default.CheckCircle, null, tint = fg, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            pending -> {
                Icon(Icons.Outlined.CloudOff, null, tint = fg, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            !polishing -> {
                // Draft variant — small filled dot, no glyph (§2.5).
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF7A7886)))
                Spacer(Modifier.width(5.dp))
            }
        }
        val label = when {
            refined -> "Refined"; pending -> "Pending"
            status == "TRANSCRIBING" -> "Polishing"; polishing -> "Polishing"
            else -> status.lowercase().replaceFirstChar { it.uppercase() }
        }
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun relativeTimeLib(ts: Long): String {
    val mins = (System.currentTimeMillis() - ts) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}

/** Sync-status bottom sheet (design · Sync sheet) — pending / uploading / synced rows. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SyncSheet(sessions: List<Session>, onSyncNow: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val pending = sessions.filter { it.status == "OFFLINE_PENDING" }
    val uploading = sessions.filter { it.status == "TRANSCRIBING" || it.status == "POLISHING" }
    val recentSynced = sessions.filter { it.status == "DONE" || it.status == "REFINED" }.take(2)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = cs.surface,
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, null, tint = cs.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sync status", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                if (pending.isNotEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(ext.offlineContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("${pending.size} pending", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = ext.onOfflineContainer)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            @Composable
            fun syncRow(tileBg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color,
                        title: String, subtitle: String, trailing: String? = null, divider: Boolean = true) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tileBg),
                        contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, fontSize = 11.sp, color = ext.subdued)
                    }
                    if (trailing != null) Text(trailing, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
                }
                if (divider) HorizontalDivider(thickness = 1.dp, color = cs.surfaceContainerHigh)
            }

            pending.forEach {
                syncRow(ext.warningSoft, Icons.Outlined.Schedule, ext.onOfflineContainer,
                    it.title, "Waiting for connection")
            }
            uploading.forEach {
                syncRow(cs.primaryContainer, Icons.Outlined.Autorenew, cs.onPrimaryContainer,
                    it.title, "Uploading…")
            }
            recentSynced.forEachIndexed { i, s ->
                syncRow(ext.successSoft, Icons.Default.CheckCircle, ext.onRefinedContainer,
                    s.title, "Synced ${relativeTimeLib(s.createdAt)} ago",
                    divider = i < recentSynced.size - 1)
            }
            if (pending.isEmpty() && uploading.isEmpty() && recentSynced.isEmpty()) {
                Text("Nothing to sync yet.", fontSize = 13.sp, color = ext.subdued,
                    modifier = Modifier.padding(vertical = 16.dp))
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSyncNow(); onDismiss() }, shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Outlined.Sync, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sync now", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
