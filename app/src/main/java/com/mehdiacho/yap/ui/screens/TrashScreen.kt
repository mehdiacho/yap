package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.MainViewModel

private const val TRASH_DAYS = 30L

@Composable
fun TrashScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val trashed by viewModel.trashedSessions.collectAsState()
    var confirmForever by remember { mutableStateOf<Session?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Trash", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            if (trashed.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) {
                    Text("Empty trash", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Text(
            "Notes stay here for $TRASH_DAYS days, then they're deleted everywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(14.dp))

        if (trashed.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(trashed, key = { it.id }) { s ->
                    val daysLeft = s.trashedAt?.let {
                        (TRASH_DAYS - (System.currentTimeMillis() - it) / 86_400_000L).coerceAtLeast(0)
                    } ?: TRASH_DAYS
                    Row(
                        Modifier.fillMaxWidth().clip(YapShapes.panel)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                            Text(
                                "gone in $daysLeft day${if (daysLeft == 1L) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { viewModel.restoreSession(s.id) }) {
                            Icon(Icons.Default.RestoreFromTrash, "Restore",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { confirmForever = s }) {
                            Icon(Icons.Default.DeleteForever, "Delete forever",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    confirmForever?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            title = { Text("Delete forever?") },
            text = { Text("\"${s.title}\" and its recording will be removed everywhere. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSessionCompletely(s); confirmForever = null
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } }
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the trash?") },
            text = { Text("All ${trashed.size} notes and their recordings will be removed everywhere. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    trashed.forEach { viewModel.deleteSessionCompletely(it) }
                    confirmEmpty = false
                }) { Text("Empty trash", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }
}
