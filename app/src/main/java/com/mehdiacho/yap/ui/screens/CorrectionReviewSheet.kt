package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.TextCorrections
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme

/**
 * "You fixed one. Here are the others."
 *
 * Every occurrence is listed WITH its surrounding words, because that is the only thing that
 * lets you tell a mis-transcribed "cloud" from a real one. A bare count would force you to
 * either trust it blindly or open the note and check by hand.
 *
 * Everything starts accepted, since the common case by far is that the mishearing is
 * systematic — you already know it is wrong everywhere. Deselecting the odd genuine one is
 * less work than ticking nine boxes. What is deliberately NOT offered is applying without
 * showing you the list.
 *
 * @param cautions occurrence indices the model thinks may be genuine (advisory only — it
 *   annotates a row, it never changes what is selected or blocks applying).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionReviewSheet(
    correction: TextCorrections.Correction,
    occurrences: List<TextCorrections.Occurrence>,
    cautions: Map<Int, String> = emptyMap(),
    checking: Boolean = false,
    onApply: (List<TextCorrections.Occurrence>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember(occurrences) {
        mutableStateOf(occurrences.indices.toSet())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(
                    "Also fix this elsewhere?",
                    fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    letterSpacing = (-0.3).sp, color = cs.onSurface,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("You changed ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cs.onSurface)) {
                        append(correction.from)
                    }
                    append(" to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = cs.primary)) {
                        append(correction.to)
                    }
                    append(
                        if (occurrences.size == 1) ". It appears once more:"
                        else ". It appears ${occurrences.size} more times:"
                    )
                },
                fontSize = 13.5.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selected.size} of ${occurrences.size} selected",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ext.subdued,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == occurrences.size) emptySet()
                    else occurrences.indices.toSet()
                }) {
                    Text(
                        if (selected.size == occurrences.size) "Select none" else "Select all",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (checking) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking which ones look intentional…", fontSize = 12.sp, color = ext.subdued)
                }
            }

            LazyColumn(
                Modifier.weight(1f, fill = false).heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(occurrences.size) { i ->
                    OccurrenceRow(
                        occurrence = occurrences[i],
                        isSelected = i in selected,
                        caution = cautions[i],
                        onToggle = {
                            selected = if (i in selected) selected - i else selected + i
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("Not now", fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { onApply(selected.sorted().map { occurrences[it] }) },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1.4f).height(50.dp),
                ) {
                    Text(
                        if (selected.size == occurrences.size && occurrences.size > 1) "Fix all ${occurrences.size}"
                        else "Fix ${selected.size}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** One candidate, shown in its sentence so the decision is possible without leaving the sheet. */
@Composable
private fun OccurrenceRow(
    occurrence: TextCorrections.Occurrence,
    isSelected: Boolean,
    caution: String?,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Row(
        Modifier.fillMaxWidth()
            .clip(YapShapes.card)
            .background(if (isSelected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surfaceContainerLow)
            .border(
                1.dp,
                if (isSelected) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant,
                YapShapes.card,
            )
            .clickable { onToggle() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(YapShapes.sm)
                .background(if (isSelected) cs.primary else cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(Icons.Default.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cs.onSurfaceVariant)) {
                        append(occurrence.contextBefore)
                    }
                    // Strike the old word and show the new one, so the row states the whole
                    // change rather than making you hold the substitution in your head.
                    withStyle(
                        SpanStyle(
                            color = cs.error,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        )
                    ) { append(occurrence.matched) }
                    append(" ")
                    withStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold)) {
                        append(occurrence.replacement)
                    }
                    withStyle(SpanStyle(color = cs.onSurfaceVariant)) {
                        append(occurrence.contextAfter)
                    }
                },
                fontSize = 13.sp, lineHeight = 19.sp,
            )
            if (caution != null) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.WarningAmber, null,
                        tint = ext.onOfflineContainer, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        caution, fontSize = 11.5.sp, lineHeight = 16.sp,
                        color = ext.onOfflineContainer,
                    )
                }
            }
        }
    }
}
