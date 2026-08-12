package com.mehdiacho.yap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.theme.YapDims

/**
 * Every small labelled badge and chip in the app.
 *
 * These were hand-rolled at each call site, and the drift showed: vertical padding ran from
 * 3dp to 8dp, icons from 12dp to 15dp, text from 11sp to 13sp, radius from 8dp to fully
 * round. Put two of them next to each other — which happens constantly, in the filter row and
 * on every card — and one is visibly shorter than the other for no reason the user can name.
 *
 * The fixes that matter are structural, not cosmetic:
 *
 * - **Height is fixed, not derived.** Content no longer decides. A pill with an icon and one
 *   without are the same height, and a pill whose state changes doesn't resize the row it
 *   sits in.
 * - **One line, always, with a ceiling.** Labels come from user data (category names, topic
 *   clusters). Left uncapped, a long one wrapped and made the whole row taller — pushing the
 *   list below it down — which was the visible bug in the library filter row.
 */
enum class PillSize {
    /** Non-interactive badges inside a card: status, category, counts. */
    Small,

    /** Tappable chips: filters, pickers. */
    Medium,
}

private val PillSize.height: Dp
    get() = when (this) {
        PillSize.Small -> YapDims.pillHeight
        PillSize.Medium -> YapDims.chipHeight
    }

private val PillSize.hPadding: Dp
    get() = when (this) {
        PillSize.Small -> 10.dp
        PillSize.Medium -> 12.dp
    }

private val PillSize.iconSize: Dp
    get() = when (this) {
        PillSize.Small -> 13.dp
        PillSize.Medium -> 15.dp
    }

private val PillSize.fontSize
    get() = when (this) {
        PillSize.Small -> 11.sp
        PillSize.Medium -> 13.sp
    }

/**
 * @param leading drawn before the label at [PillSize.iconSize]; typically an `Icon` or a dot.
 *   Given as a slot rather than an `ImageVector` so a caller can pass a dot or a spinner
 *   without the pill needing to know about either.
 * @param maxWidth caps the label before it ellipsizes. Defaults off for fixed strings; pass
 *   [YapDims.chipMaxWidth] for anything the user typed.
 */
@Composable
fun Pill(
    label: String,
    modifier: Modifier = Modifier,
    size: PillSize = PillSize.Small,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color? = null,
    shape: Shape = CircleShape,
    bold: Boolean = true,
    maxWidth: Dp = Dp.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(size.height)
            .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
            .clip(shape)
            .background(background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = size.hPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leading != null) {
            PillIcon(size) { leading() }
            Spacer(Modifier.width(if (size == PillSize.Small) 5.dp else 6.dp))
        }
        Text(
            label,
            fontSize = size.fontSize,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Constrains whatever the caller passed to the pill's icon box, so slots can't change height. */
@Composable
private fun PillIcon(size: PillSize, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(size.iconSize),
        contentAlignment = Alignment.Center,
    ) { content() }
}
