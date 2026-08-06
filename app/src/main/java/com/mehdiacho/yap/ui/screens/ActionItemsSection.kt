package com.mehdiacho.yap.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.data.ActionItem
import com.mehdiacho.yap.data.Session
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapTheme
import com.mehdiacho.yap.ui.theme.brandBrush

/**
 * The tasks buried in a note, lifted out where they can be ticked off.
 *
 * A spoken note is a terrible to-do list — the three things you actually have to do are
 * scattered through six paragraphs of context. This is the part of Smart Review that was
 * always meant to exist: the note keeps its prose, and the commitments inside it become
 * something you can close.
 *
 * Completed items stay visible, struck through, rather than disappearing. Vanishing items
 * make it impossible to tell "I did that" from "that was never picked up", which is exactly
 * the doubt a task list has to remove.
 */
@Composable
fun ActionItemsSection(
    session: Session,
    onToggle: (ActionItem, Boolean) -> Unit,
    onAct: (ActionItem) -> Unit,
) {
    if (session.actionItems.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    val doneCount = session.actionItems.count { it.done }
    val total = session.actionItems.size

    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Checklist, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Tasks", fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = cs.onSurface, modifier = Modifier.weight(1f),
            )
            Text(
                "$doneCount/$total",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = if (doneCount == total) ext.onRefinedContainer else ext.subdued,
            )
        }
        Spacer(Modifier.height(4.dp))
        // Thin progress rail — reading "2/5" is slower than seeing it.
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(cs.surfaceContainerHigh)
        ) {
            if (doneCount > 0) {
                Box(
                    Modifier.fillMaxWidth(doneCount.toFloat() / total).fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(brandBrush(forSmallText = true, seed = 21))
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        session.actionItems.forEach { item ->
            ActionItemRow(item, onToggle = { onToggle(item, it) }, onAct = { onAct(item) })
            Spacer(Modifier.height(7.dp))
        }
    }
}

@Composable
private fun ActionItemRow(
    item: ActionItem,
    onToggle: (Boolean) -> Unit,
    onAct: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(if (item.done) cs.surfaceContainerLow else cs.surfaceContainer)
            .clickable { onToggle(!item.done) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(21.dp).clip(RoundedCornerShape(7.dp))
                .then(
                    if (item.done) Modifier.background(brandBrush(forSmallText = true, seed = 22))
                    else Modifier.border(2.dp, cs.outlineVariant, RoundedCornerShape(7.dp))
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) {
                Icon(
                    Icons.Default.Check, null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Text(
            item.text,
            fontSize = 14.sp, lineHeight = 20.sp,
            color = if (item.done) ext.subdued else cs.onSurface,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        if (!item.done) {
            Spacer(Modifier.width(8.dp))
            // Hand the task to whatever actually does the work — an assistant, a task app,
            // a chat. Deliberately the system share sheet rather than a fixed integration:
            // it works with everything already installed, today, and doesn't presume which
            // tool this user lives in.
            Icon(
                Icons.Outlined.IosShare, "Send this task somewhere",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp).clickable { onAct() },
            )
        }
    }
}

/**
 * Capture · category pre-selection.
 *
 * Filing happens at the moment of intent — you know this is an app update *before* you start
 * talking, and picking it afterwards means finding the note again later, which is exactly the
 * friction that leaves libraries unsorted.
 */
@Composable
fun CategoryPicker(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onCreate: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ext = YapTheme.extended
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryChip(
            label = selected ?: "No category",
            active = selected != null,
            seed = 23,
            onClick = { if (selected != null) onSelect(null) },
        )
        categories.filter { it != selected }.take(8).forEach { c ->
            CategoryChip(label = c, active = false, seed = c.hashCode(), onClick = { onSelect(c) })
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainer)
                .clickable { onCreate() }.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Bolt, null, tint = ext.subdued, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text("New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ext.subdued)
        }
    }
}

@Composable
private fun CategoryChip(label: String, active: Boolean, seed: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .then(
                if (active) Modifier.background(brandBrush(forSmallText = true, seed = seed))
                else Modifier.background(cs.surfaceContainer)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (active) androidx.compose.ui.graphics.Color.White else cs.onSurfaceVariant,
        )
    }
}
