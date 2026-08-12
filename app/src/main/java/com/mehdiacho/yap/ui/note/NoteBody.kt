package com.mehdiacho.yap.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mehdiacho.yap.ui.theme.Outfit
import com.mehdiacho.yap.ui.theme.YapShapes
import com.mehdiacho.yap.ui.theme.YapTheme

/**
 * A polished note, drawn with the app's own components.
 *
 * Replaces a general-purpose Markdown renderer. The difference is not that Markdown rendered
 * badly — it's that it rendered like Markdown: a document format's typography, its bullet
 * glyphs, its rules, dropped inside an app that looks nothing like a document. Every choice
 * here is one this app makes for itself, and the [NoteBlock] model in between is what lets
 * the same note be exported as Markdown or flattened to plain text on demand.
 */
@Composable
fun NoteBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseNote(markdown) }
    Column(modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            // Spacing belongs between blocks and depends on what's on each side: a heading
            // needs air above it but sits close to the text it introduces, and consecutive
            // list rows are one object rather than several.
            if (index > 0) Spacer(Modifier.height(gapBefore(blocks[index - 1], block)))
            BlockContent(block)
        }
    }
}

private fun gapBefore(previous: NoteBlock, next: NoteBlock) = when {
    next is NoteBlock.Heading -> if (previous is NoteBlock.Heading) 10.dp else 22.dp
    previous is NoteBlock.Heading -> 8.dp
    next is NoteBlock.Divider || previous is NoteBlock.Divider -> 18.dp
    else -> 14.dp
}

@Composable
private fun BlockContent(block: NoteBlock) {
    val cs = MaterialTheme.colorScheme
    when (block) {
        is NoteBlock.Heading -> {
            // Two visual weights, not six. H1/H2 are section headings; anything deeper the
            // model emits is a sub-heading, and rendering six distinct sizes in a note that
            // is rarely longer than a screen produces a hierarchy nobody can perceive.
            val major = block.level <= 2
            Text(
                block.text.annotated(cs.primary),
                fontFamily = Outfit,
                fontWeight = if (major) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = if (major) 18.sp else 15.sp,
                lineHeight = if (major) 24.sp else 21.sp,
                letterSpacing = if (major) (-0.2).sp else 0.sp,
                color = cs.onSurface,
            )
        }

        is NoteBlock.Paragraph -> BodyText(block.text)

        is NoteBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
                ListRow(item.depth) {
                    // A small filled dot, sized and positioned against the text's own line
                    // box so it sits on the first line rather than floating near it.
                    Box(Modifier.width(16.dp).height(21.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(if (item.depth == 0) 5.dp else 4.dp)
                                .clip(CircleShape)
                                .background(if (item.depth == 0) cs.primary else cs.outline),
                        )
                    }
                    BodyText(item.text)
                }
            }
        }

        is NoteBlock.Numbers -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { index, item ->
                ListRow(item.depth) {
                    Text(
                        item.marker ?: "${index + 1}.",
                        fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        lineHeight = 21.sp, color = cs.primary,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    BodyText(item.text)
                }
            }
        }

        is NoteBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            // A rule down the side rather than a tinted card: a quote is an aside within the
            // note, and boxing it makes it read as a separate object with its own importance.
            Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(cs.outlineVariant))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(vertical = 1.dp)) {
                block.blocks.forEachIndexed { index, inner ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    BlockContent(inner)
                }
            }
        }

        is NoteBlock.Code -> Column(
            Modifier.fillMaxWidth().clip(YapShapes.lg).background(cs.surfaceContainerHigh),
        ) {
            block.language?.let {
                Text(
                    it.uppercase(),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = YapTheme.extended.subdued,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp),
                )
            }
            // Code is the one thing that must not reflow — wrapping a line changes what it
            // says. It scrolls sideways inside its own box so the note never does.
            Text(
                block.code,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp,
                color = cs.onSurface,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = if (block.language != null) 8.dp else 12.dp),
            )
        }

        is NoteBlock.Table -> Column(
            Modifier.fillMaxWidth().clip(YapShapes.lg)
                .background(cs.surfaceContainerLow)
                .horizontalScroll(rememberScrollState()),
        ) {
            TableRow(block.header, header = true)
            block.rows.forEach { row ->
                HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                TableRow(row, header = false)
            }
        }

        NoteBlock.Divider -> HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
    }
}

/** Indents by depth and keeps the marker aligned to the first line of wrapped text. */
@Composable
private fun ListRow(depth: Int, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = (depth * 18).dp),
        verticalAlignment = Alignment.Top,
    ) { content() }
}

@Composable
private fun TableRow(cells: List<InlineText>, header: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.background(if (header) cs.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 4.dp),
    ) {
        cells.forEach { cell ->
            Text(
                cell.annotated(cs.primary),
                fontSize = 13.sp, lineHeight = 19.sp,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) cs.onSurface else cs.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 72.dp, max = 220.dp)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun BodyText(text: InlineText) {
    val cs = MaterialTheme.colorScheme
    Text(
        text.annotated(cs.primary),
        fontSize = 15.sp, lineHeight = 24.sp, color = cs.onSurface,
        style = LocalTextStyle.current.copy(letterSpacing = 0.1.sp),
    )
}

/**
 * Builds the styled string for one line.
 *
 * Links use [LinkAnnotation.Url], not a click listener over an annotation range — the former
 * is what makes the text accessible as a link to TalkBack, and the latter is a tap target the
 * screen reader can't see.
 */
@Composable
private fun InlineText.annotated(accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    return buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                fontSize = if (span.code) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                background = if (span.code) cs.surfaceContainerHigh
                else androidx.compose.ui.graphics.Color.Unspecified,
            )
            if (span.href != null) {
                withLink(
                    LinkAnnotation.Url(
                        span.href,
                        TextLinkStyles(style = style.copy(color = accent, textDecoration = TextDecoration.Underline)),
                    ) { uriHandler.openUri(span.href) },
                ) { append(span.text) }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
}
