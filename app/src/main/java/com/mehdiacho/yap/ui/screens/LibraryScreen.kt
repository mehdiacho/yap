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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.BuildConfig
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.LibraryChrome
import com.mehdiacho.yap.ui.MainViewModel
import com.mehdiacho.yap.ui.rememberOfflineState
import com.mehdiacho.yap.ui.components.Pill
import com.mehdiacho.yap.ui.components.PillSize
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapDims
import com.mehdiacho.yap.ui.theme.YapShapes
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
    val sessionsLoaded by viewModel.sessionsLoaded.collectAsState()
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    var searchFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
    var activeFilter by remember { androidx.compose.runtime.mutableStateOf("All") }
    // Task filter, kept separate from the category filter so the two stack: "Brainstorm"
    // AND "has tasks" AND "still open" is the query you actually want, and folding tasks
    // into the same one-of-N chip row would make it exclusive with categories for no reason.
    // null = off · "any" = notes with tasks · "open" = only those with something outstanding.
    var taskFilter by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Which cards have their checklist open. Hoisted out of the card so it survives the
    // LazyColumn recycling rows as you scroll.
    var expandedCards by remember { androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet()) }
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

    // Recordings turned away at the monthly cap. They're intact and waiting — the only thing
    // missing was quota, so once there is some they should not need chasing down one by one.
    val waitingCount = remember(sessions) {
        sessions.count { it.status == "QUOTA" && it.trashedAt == null }
    }
    val catchUp by viewModel.pendingTranscribeProgress.collectAsState()

    // Once per launch, and only if the server says there's room — see the ViewModel.
    LaunchedEffect(sessionsLoaded) {
        if (sessionsLoaded) viewModel.resumePendingTranscriptions()
    }

    // Multi-select replaces the app bottom nav with the 4-action bar (design · multi-select).
    LaunchedEffect(selecting) { LibraryChrome.selectionActive.value = selecting }
    DisposableEffect(Unit) { onDispose { LibraryChrome.selectionActive.value = false } }

    // Voice search — system speech recognizer fills the query.
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) { query = spoken; searchFocused = true }
    }
    fun launchVoiceSearch() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search your notes")
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure {
            android.widget.Toast.makeText(
                context,
                "Voice search isn't available on this device.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val categories = remember(sessions) {
        listOf("All") +
            (if (sessions.any { it.pinned }) listOf("Pinned") else emptyList()) +
            sessions.map { it.category }.filter { it.isNotBlank() }.distinct().take(6)
    }
    val filtered = remember(sessions, query, activeFilter, taskFilter) {
        val q = query.trim().lowercase()
        sessions.filter { s ->
            (activeFilter == "All" ||
                (activeFilter == "Pinned" && s.pinned) ||
                s.category == activeFilter) &&
                when (taskFilter) {
                    "any" -> s.hasTasks
                    "open" -> s.hasOpenTasks
                    else -> true
                } &&
                (q.isBlank() ||
                    s.title.lowercase().contains(q) ||
                    s.draftText.lowercase().contains(q) ||
                    (s.formattedText?.lowercase()?.contains(q) == true) ||
                    s.category.lowercase().contains(q) ||
                    s.topicCluster.lowercase().contains(q))
        }.sortedByDescending { it.pinned } // stable: pinned first, newest-first within each
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tightened from 6.1%/20–28dp: the cards were leaving more margin than they needed
        // and reading as narrow strips. Everything on the screen shares this inset, so the
        // cards get wider without breaking alignment with the header and search field.
        val edge = (maxWidth.value * 0.044f).dp.coerceIn(14.dp, 20.dp)
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
                            Pill(
                                label = "$pendingCount",
                                background = ext.offlineContainer,
                                contentColor = ext.onOfflineContainer,
                                leading = {
                                    Icon(Icons.Outlined.CloudSync, null, tint = ext.onOfflineContainer,
                                        modifier = Modifier.size(13.dp))
                                },
                                onClick = { showSyncSheet = true },
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Icon(Icons.Outlined.Delete, "Trash", tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(22.dp).clickable { onOpenTrash() })
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(avatar).clip(CircleShape)
                                .background(com.mehdiacho.yap.ui.theme.brandBrush(forSmallText = true)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Y", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                    // Offline banner — "Offline · showing cached notes".
                    if (offline) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = edge).padding(top = 12.dp)
                                .clip(YapShapes.lg).background(ext.warningSoft)
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

            // Real first-load signal (plus the debug long-press preview).
            if (showSkeleton || !sessionsLoaded) {
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
                        onVoice = ::launchVoiceSearch,
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
                                    // Long-press selection works here too. Search is exactly
                                    // where you need it most — finding an old note by name is
                                    // the whole reason you searched, and previously you had to
                                    // clear the query and hunt for it down the list to pin it.
                                    SessionCard(
                                        session,
                                        highlight = query.trim(),
                                        selected = session.id in selection,
                                        selectionMode = selecting,
                                        expanded = session.id in expandedCards,
                                        onExpandToggle = {
                                            expandedCards = if (session.id in expandedCards)
                                                expandedCards - session.id else expandedCards + session.id
                                        },
                                        onToggleTask = { item, done ->
                                            viewModel.setActionItemDone(session, item.id, done)
                                        },
                                        onClick = {
                                            if (selecting) selection =
                                                if (session.id in selection) selection - session.id
                                                else selection + session.id
                                            else onNavigateToDetail(session.id)
                                        },
                                        onLongClick = {
                                            selection =
                                                if (session.id in selection) selection - session.id
                                                else selection + session.id
                                        },
                                    )
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
                        if (!selecting && (waitingCount > 0 || catchUp != null)) {
                            Spacer(Modifier.height(12.dp))
                            WaitingOnQuotaBanner(
                                waiting = waitingCount,
                                progress = catchUp,
                                onTranscribe = { viewModel.transcribePending() },
                            )
                        }

                        // 3 · Filter chips (design: outlined radius-10; active = filled Primary + check)
                        val anyTasks = remember(sessions) { sessions.any { it.hasTasks } }
                        if (!selecting && (categories.size > 1 || anyTasks)) {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Tasks leads the row — it cuts across every category, so it
                                // isn't one of them. Hidden entirely until a note has tasks.
                                if (anyTasks) {
                                    TaskFilterChip(
                                        value = taskFilter,
                                        onChange = { taskFilter = it },
                                    )
                                }
                                categories.forEach { cat ->
                                    FilterChipSquare(cat, active = cat != "All" && cat == activeFilter) {
                                        activeFilter = if (activeFilter == cat) "All" else cat
                                    }
                                }
                            }
                        }
                        // Filtered count row — "N notes · Clear all"
                        if ((activeFilter != "All" || taskFilter != null) && !selecting) {
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${filtered.size} note${if (filtered.size == 1) "" else "s"}",
                                    fontSize = 12.sp, color = ext.subdued)
                                Spacer(Modifier.weight(1f))
                                Text("Clear all", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = cs.primary, modifier = Modifier.clickable {
                                        activeFilter = "All"; taskFilter = null
                                    })
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
                                        expanded = session.id in expandedCards,
                                        onExpandToggle = {
                                            expandedCards = if (session.id in expandedCards)
                                                expandedCards - session.id else expandedCards + session.id
                                        },
                                        onToggleTask = { item, done ->
                                            viewModel.setActionItemDone(session, item.id, done)
                                        },
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
                        // Pin toggles: if every selected note is already pinned, unpin them.
                        val allPinned = selection.isNotEmpty() &&
                            sessions.filter { it.id in selection }.all { it.pinned }
                        SelectionAction(Icons.Outlined.PushPin, if (allPinned) "Unpin" else "Pin", cs.onPrimaryContainer) {
                            viewModel.setPinned(selection, !allPinned)
                            selection = emptySet()
                        }
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
                    // Neutral elevation, not a coloured glow. A shadow tinted with the accent
                    // is light that no object casts; it reads as a sticker rather than a
                    // control sitting above the page, and it is one of the most recognisable
                    // marks of generated UI. Height above the surface is the only thing a
                    // shadow should be saying.
                    .shadow(8.dp, YapShapes.xl)
                    .clip(YapShapes.xl)
                    .background(com.mehdiacho.yap.ui.theme.brandBrush(seed = 5))
                    .clickable { onRecordNew() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, "New note", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }

    if (showSyncSheet) {
        SyncSheet(sessions = sessions, onSyncNow = { recheckOnline() }, onDismiss = { showSyncSheet = false })
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            shape = YapShapes.dialog,
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
        modifier = Modifier.clip(YapShapes.lg).clickable { onClick() }
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
/**
 * Tasks filter — one chip that grows a second segment instead of spawning a second chip.
 *
 * "Unfinished" is meaningless on its own: it only narrows notes that have tasks at all. Two
 * separate chips would let you pick the nonsensical combination and would imply they're
 * independent. Joining them into one pill says what's true — the second refines the first —
 * and keeps the row short.
 */
@Composable
private fun TaskFilterChip(value: String?, onChange: (String?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val on = value != null
    val openOnly = value == "open"
    val shape = YapShapes.md
    Row(
        Modifier.height(YapDims.chipHeight).clip(shape)
            .then(
                if (on) Modifier.background(cs.primary)
                else Modifier.border(1.dp, cs.outlineVariant, shape)
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxHeight()
                // Turning the parent off has to take the refinement with it, otherwise
                // "open" would linger invisibly and the next tap would filter unexpectedly.
                .clickable { onChange(if (on) null else "any") }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Checklist, null,
                tint = if (on) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Tasks", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (on) cs.onPrimary else cs.onSurfaceVariant)
        }
        if (on) {
            // Hairline seam: one pill, two parts — not two chips that happen to touch.
            Box(Modifier.width(1.dp).fillMaxHeight().background(cs.onPrimary.copy(alpha = 0.45f)))
            Row(
                Modifier.fillMaxHeight()
                    .background(if (openOnly) Color.Black.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onChange(if (openOnly) "any" else "open") }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (openOnly) {
                    Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text("Unfinished", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimary.copy(alpha = if (openOnly) 1f else 0.8f))
            }
        }
    }
}

/**
 * Category chip.
 *
 * Two things here are load-bearing. It is the same fixed height as the Tasks chip beside it —
 * they were 34dp and ~28dp before, which looked like a mistake because it was one. And the
 * label is capped and ellipsized: categories are user-named, and a long one used to wrap,
 * making this chip taller than its neighbours and pushing the entire note list down the
 * screen. The row scrolls horizontally; length should cost horizontal space, never vertical.
 */
@Composable
private fun FilterChipSquare(label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Pill(
        label = label,
        size = PillSize.Medium,
        shape = YapShapes.md,
        background = if (active) cs.primary else cs.surface,
        contentColor = if (active) cs.onPrimary else cs.onSurfaceVariant,
        borderColor = if (active) null else cs.outlineVariant,
        bold = active,
        maxWidth = YapDims.chipMaxWidth,
        leading = if (!active) null else {
            { Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp)) }
        },
        onClick = onClick,
    )
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
        // Geometry copied from the real thing, not eyeballed: same 46dp field, same chip
        // height, same card radius and padding. A skeleton whose blocks land somewhere other
        // than the content does is a visible reflow the moment the data arrives.
        Box(Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(bar))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(80, 64, 70).forEach { w ->
                Box(Modifier.width(w.dp).height(YapDims.chipHeight).clip(YapShapes.md).background(bar))
            }
        }
        Spacer(Modifier.height(18.dp))
        repeat(3) { i ->
            Column(
                Modifier.fillMaxWidth().clip(YapShapes.card)
                    .border(1.dp, bar, YapShapes.card).padding(horizontal = 15.dp, vertical = 13.dp)
            ) {
                Box(Modifier.width((60 - i * 5).dp).height(18.dp).clip(YapShapes.sm).background(bar))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(0.9f - i * 0.05f).height(16.dp).clip(YapShapes.sm).background(bar))
                if (i < 2) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(0.75f - i * 0.07f).height(13.dp).clip(YapShapes.sm).background(bar))
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
    expanded: Boolean = false,
    onExpandToggle: () -> Unit = {},
    onToggleTask: (com.mehdiacho.yap.data.ActionItem, Boolean) -> Unit = { _, _ -> },
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val shape = YapShapes.card
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Depth instead of an outline. A hairline border reads as a boundary drawn on the
            // page; a raised surface reads as an object sitting on it, which is what a note is.
            // The shadow carries it in light mode and the lifted container colour carries it in
            // dark, where shadows are all but invisible.
            .shadow(if (selected) 0.dp else 3.dp, shape, clip = false)
            .clip(shape)
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surfaceContainer)
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        highlightText(session.title, highlight, cs.primaryContainer, cs.onPrimaryContainer),
                        fontFamily = Outfit, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp, color = cs.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (selectionMode) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .then(
                                    if (selected) Modifier.background(cs.primary)
                                    else Modifier.border(2.dp, cs.outlineVariant, CircleShape)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Default.Check, null, tint = cs.onPrimary,
                                modifier = Modifier.size(16.dp))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.pinned) {
                                Icon(Icons.Filled.PushPin, "Pinned", tint = cs.primary,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(relativeTimeLib(session.createdAt), fontSize = 11.sp, color = ext.subdued)
                        }
                    }
                }

                if (!selectionMode) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        highlightText(snippetAround(session, highlight), highlight, cs.primaryContainer, cs.onPrimaryContainer),
                        fontSize = 13.sp, color = cs.onSurfaceVariant,
                        lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(10.dp))
                // Fixed height: this row carries a status chip that comes and goes, and both
                // labels are user data. Letting it size to content meant the card grew a few
                // pixels taller whenever a note was polished or had a long category, and a
                // list of cards that don't line up is the thing you notice without knowing why.
                Row(
                    Modifier.height(YapDims.pillHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        session.category.ifBlank { "Note" },
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (session.topicCluster.isNotBlank() && session.topicCluster != "General") {
                        Text("  ·  ", fontSize = 11.sp, color = cs.outlineVariant)
                        Text(session.topicCluster, fontSize = 11.sp, color = ext.subdued,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                    }
                    Spacer(Modifier.weight(1f))
                    // Only states that need acting on earn a chip. "Refined" on every settled
                    // note is noise: it's the norm, and repeating it everywhere makes the
                    // handful of notes that are stuck harder to spot, not easier.
                    if (session.status != "DONE" && session.status != "REFINED") {
                        StatusChip(session.status)
                        Spacer(Modifier.width(7.dp))
                    }
                    if (!selectionMode) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val availableOffline = remember(session.audioPath) {
                            session.audioPath != null && java.io.File(session.audioPath).exists()
                        }
                        val cached = remember(session.storagePath) {
                            com.mehdiacho.yap.data.AudioCache.isCached(context, session.storagePath)
                        }
                        when {
                            availableOffline -> Icon(Icons.Outlined.OfflinePin, "Available offline",
                                tint = cs.outline, modifier = Modifier.size(15.dp))
                            cached -> Icon(Icons.Outlined.CloudDone, "Downloaded",
                                tint = cs.primary, modifier = Modifier.size(15.dp))
                            session.storagePath != null -> Icon(Icons.Outlined.CloudQueue, "Online only",
                                tint = cs.primary, modifier = Modifier.size(15.dp))
                        }
                    }
                }

                if (session.hasTasks && !selectionMode) {
                    Spacer(Modifier.height(10.dp))
                    TaskStrip(
                        session = session,
                        expanded = expanded,
                        onExpandToggle = onExpandToggle,
                        onToggleTask = onToggleTask,
                    )
                }
        }
    }
}

/**
 * The checklist, summarised on the card and openable in place.
 *
 * Ticking one thing off a note should not cost a screen transition and a trip back. The
 * summary alone answers the question you scan a library for — is there anything left in this
 * one? — and expanding answers the follow-up without losing your place in the list.
 */
@Composable
private fun TaskStrip(
    session: Session,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onToggleTask: (com.mehdiacho.yap.data.ActionItem, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val total = session.actionItems.size
    val done = session.tasksDone
    val allDone = done == total

    Column {
        // The two states of this strip — a progress rail, or the "all done" badge — used to
        // measure differently, so ticking the last task made the card jump. Pinning the row
        // to the badge's height means the card is the same size before and after, and the
        // only thing that changes is what it says.
        Row(
            Modifier.fillMaxWidth().height(YapDims.pillHeight).clip(YapShapes.md)
                .clickable { onExpandToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allDone) {
                // Finished is a different KIND of state, not merely 100% of the same one. A
                // full bar and a cleared list look alike at a glance, so say it outright.
                Pill(
                    label = "All $total done",
                    background = ext.refinedContainer,
                    contentColor = ext.onRefinedContainer,
                    leading = {
                        Icon(Icons.Default.CheckCircle, null, tint = ext.onRefinedContainer,
                            modifier = Modifier.size(13.dp))
                    },
                )
                Spacer(Modifier.weight(1f))
            } else {
                Icon(Icons.Outlined.Checklist, null, tint = ext.subdued, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier.weight(1f).height(5.dp).clip(CircleShape)
                        .background(cs.surfaceContainerHighest)
                ) {
                    if (done > 0) {
                        Box(
                            Modifier.fillMaxWidth(done.toFloat() / total).fillMaxHeight()
                                .clip(CircleShape)
                                .background(cs.primary)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("$done/$total", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = ext.subdued)
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                if (expanded) "Hide tasks" else "Show tasks",
                tint = ext.subdued, modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            session.actionItems.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(YapShapes.md)
                        .clickable { onToggleTask(item, !item.done) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(17.dp).clip(YapShapes.sm)
                            .then(
                                if (item.done) Modifier.background(cs.primary)
                                else Modifier.border(2.dp, cs.outlineVariant, YapShapes.sm)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.done) Icon(Icons.Default.Check, null, tint = cs.onPrimary,
                            modifier = Modifier.size(11.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        item.text, fontSize = 13.sp, lineHeight = 18.sp,
                        color = if (item.done) ext.subdued else cs.onSurface,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
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

/** §2.5 status chip. Fixed height, so a card doesn't resize as its note changes state. */
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
        // Polishing is the one live, in-progress state, so it's the one that gets the filled
        // accent. The settled states stay quiet on purpose: if every chip shouted, none of
        // them would signal anything.
        polishing -> cs.primary to cs.onPrimary
        else -> ext.draftNeutral to cs.onSurfaceVariant
    }
    Pill(
        label = when {
            refined -> "Refined"
            pending -> "Pending"
            polishing -> "Polishing"
            // "Quota" describes our accounting, not the note. What the user has is a
            // recording that still needs transcribing.
            status == "QUOTA" -> "Waiting"
            else -> status.lowercase().replaceFirstChar { it.uppercase() }
        },
        background = bg,
        contentColor = fg,
        leading = when {
            refined -> {
                { Icon(Icons.Default.CheckCircle, null, tint = fg, modifier = Modifier.size(12.dp)) }
            }
            pending -> {
                { Icon(Icons.Outlined.CloudOff, null, tint = fg, modifier = Modifier.size(12.dp)) }
            }
            polishing -> null
            // Draft variant — small filled dot, no glyph (§2.5).
            else -> {
                { Box(Modifier.size(6.dp).clip(CircleShape).background(cs.outline)) }
            }
        },
    )
}

/**
 * Recordings that were turned away at the monthly cap and are still waiting.
 *
 * Surfaced on the Library rather than only on each stuck note: after an upgrade you want the
 * backlog cleared, and finding out how many there are by scrolling for grey chips is not a
 * way to learn it. Only appears when there is something to act on.
 */
@Composable
private fun WaitingOnQuotaBanner(
    waiting: Int,
    progress: Pair<Int, Int>?,
    onTranscribe: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val running = progress != null
    Row(
        Modifier.fillMaxWidth().clip(YapShapes.panel).background(ext.warningSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                color = ext.onOfflineContainer,
            )
        } else {
            Icon(Icons.Outlined.Schedule, null, tint = ext.onOfflineContainer,
                modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (running) "Transcribing ${(progress!!.first + 1).coerceAtMost(progress.second)} of ${progress.second}…"
                else "$waiting recording${if (waiting == 1) "" else "s"} waiting",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ext.onOfflineContainer,
            )
            Text(
                if (running) "Keep the app open until this finishes."
                else "Held back by your monthly limit. The audio is safe.",
                fontSize = 12.sp, color = ext.onOfflineContainer.copy(alpha = 0.85f),
            )
        }
        if (!running) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(cs.primary)
                    .clickable { onTranscribe() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Transcribe", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = cs.onPrimary)
            }
        }
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), // YapShapes.dialog, top corners only
        containerColor = cs.surface,
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudSync, null, tint = cs.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("Sync status", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = cs.onSurface, modifier = Modifier.weight(1f))
                if (pending.isNotEmpty()) {
                    Pill(
                        label = "${pending.size} pending",
                        background = ext.offlineContainer,
                        contentColor = ext.onOfflineContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            @Composable
            fun syncRow(tileBg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color,
                        title: String, subtitle: String, trailing: String? = null, divider: Boolean = true) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(YapShapes.md).background(tileBg),
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
