package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.MainViewModel

@Composable
fun LibraryScreen(viewModel: MainViewModel, onNavigateToDetail: (String) -> Unit) {
    val sessions by viewModel.allSessions.collectAsState()
    var query by remember { androidx.compose.runtime.mutableStateOf("") }
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else {
            val q = query.trim().lowercase()
            sessions.filter { s ->
                s.title.lowercase().contains(q) ||
                    s.draftText.lowercase().contains(q) ||
                    (s.formattedText?.lowercase()?.contains(q) == true) ||
                    s.category.lowercase().contains(q) ||
                    s.topicCluster.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    ) {
        Text(
            text = "Library",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Search — filters title, transcript, formatted text, category, and cluster.
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search notes", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Default.Close, contentDescription = "Clear",
                        modifier = Modifier.clickable { query = "" }
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Empty",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (query.isBlank()) "No notes yet" else "No matches",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (query.isBlank()) "Tap the mic to capture your first idea."
                        else "Nothing matches \"$query\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered) { session ->
                    SessionCard(session, onClick = { onNavigateToDetail(session.id) })
                }
            }
        }
    }
}

@Composable
fun SessionCard(session: Session, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    session.category,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Availability on THIS device (OneDrive-style states):
            //   offline-pin = available offline (device-born or pinned; never auto-cleared)
            //   cloud-done  = downloaded copy in cache (plays offline; evictable via Clear downloads)
            //   cloud       = online-only here (tap-to-download in the note)
            val context = androidx.compose.ui.platform.LocalContext.current
            val availableOffline = remember(session.audioPath) {
                session.audioPath != null && java.io.File(session.audioPath).exists()
            }
            val cached = remember(session.storagePath) {
                com.mehdiacho.yap.data.AudioCache.isCached(context, session.storagePath)
            }
            val inCloud = session.storagePath != null
            when {
                availableOffline -> Icon(
                    Icons.Default.OfflinePin, contentDescription = "Available offline",
                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp)
                )
                cached -> Icon(
                    Icons.Default.CloudDone, contentDescription = "Downloaded (clearable)",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                )
                inCloud -> Icon(
                    Icons.Default.CloudQueue, contentDescription = "Online only on this device",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = session.title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = session.draftText.take(60) + "...",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 16.sp
        )
        
        Spacer(modifier = Modifier.height(10.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isRefined = session.status == "REFINED"
            val statusColor = if (isRefined) Color(0xFF0F5132) else MaterialTheme.colorScheme.onSurfaceVariant
            val statusBg = if (isRefined) Color(0xFFB8F0CF) else MaterialTheme.colorScheme.surfaceVariant
            
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(statusBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRefined) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (session.status == "OFFLINE_PENDING" || session.status == "POLISHING") {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = session.status.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
