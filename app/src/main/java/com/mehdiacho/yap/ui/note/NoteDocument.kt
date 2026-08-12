package com.mehdiacho.yap.ui.note

/**
 * The structure of a polished note, parsed out of the Markdown the model returns.
 *
 * ## Why parse it ourselves
 *
 * The note view used to hand the raw string to a general-purpose Markdown renderer. That
 * renders *Markdown* — a document format with its own typographic conventions — and the result
 * looked like a README rendered inside an app rather than like a page the app had written.
 * Nothing was wrong with it exactly; it simply wasn't ours, and every heading, bullet and rule
 * carried someone else's defaults.
 *
 * Parsing to a small model of our own means the renderer decides how a heading looks, how far
 * a nested bullet indents, and what a quote does — and it means the same document can be
 * exported back out as Markdown, or flattened to plain text, without guessing.
 *
 * ## What it supports, and what it deliberately doesn't
 *
 * Exactly the constructs the formalize prompt is allowed to produce: headings, paragraphs,
 * bullet and numbered lists (nested), blockquotes, fenced code, tables, thematic breaks, and
 * the inline set (bold, italic, strikethrough, code, links). Anything outside that — HTML,
 * footnotes, reference links, setext headings — falls through as literal text rather than
 * being silently dropped, because a transcript that happens to contain `<3` is text, not markup.
 */
sealed interface NoteBlock {
    data class Heading(val level: Int, val text: InlineText) : NoteBlock
    data class Paragraph(val text: InlineText) : NoteBlock
    data class Bullets(val items: List<ListEntry>) : NoteBlock
    data class Numbers(val items: List<ListEntry>) : NoteBlock
    data class Quote(val blocks: List<NoteBlock>) : NoteBlock
    data class Code(val language: String?, val code: String) : NoteBlock
    data class Table(val header: List<InlineText>, val rows: List<List<InlineText>>) : NoteBlock
    data object Divider : NoteBlock
}

/** One list row. [depth] is 0 for top level; [marker] is the literal "1." / "2." for numbers. */
data class ListEntry(val depth: Int, val text: InlineText, val marker: String? = null)

/** A run of text with the styles that apply to all of it. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
)

/** Inline content: an ordered list of styled runs. Empty means an empty line. */
data class InlineText(val spans: List<Span>) {
    val plain: String get() = spans.joinToString("") { it.text }
    val isBlank: Boolean get() = plain.isBlank()

    companion object {
        val EMPTY = InlineText(emptyList())
        fun of(text: String) = InlineText(listOf(Span(text)))
    }
}

// ---------------------------------------------------------------- block parsing

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val NUMBER = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val FENCE = Regex("""^\s*```+\s*([A-Za-z0-9+#_-]*)\s*$""")
private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")
private val QUOTE = Regex("""^\s*>\s?(.*)$""")
private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")

/** Two spaces of source indent per nesting level, capped so a stray indent can't run off-screen. */
private const val INDENT_UNIT = 2
private const val MAX_DEPTH = 3

fun parseNote(markdown: String): List<NoteBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<NoteBlock>()
    var i = 0

    // Paragraph text accumulates across lines: Markdown joins consecutive non-blank lines into
    // one paragraph, and honouring that matters because the model wraps its output.
    val para = mutableListOf<String>()
    fun flushParagraph() {
        if (para.isEmpty()) return
        val joined = para.joinToString(" ") { it.trim() }.trim()
        para.clear()
        if (joined.isNotEmpty()) blocks += NoteBlock.Paragraph(parseInline(joined))
    }

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code first: everything inside is literal, including things that look like
        // headings or bullets.
        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            flushParagraph()
            val language = fence.groupValues[1].takeIf { it.isNotBlank() }
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && FENCE.matchEntire(lines[i]) == null) {
                body += lines[i]; i++
            }
            i++ // consume the closing fence (or fall off the end on an unterminated block)
            blocks += NoteBlock.Code(language, body.joinToString("\n").trimEnd())
            continue
        }

        if (line.isBlank()) { flushParagraph(); i++; continue }

        if (RULE.matchEntire(line) != null) {
            flushParagraph(); blocks += NoteBlock.Divider; i++; continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += NoteBlock.Heading(
                level = heading.groupValues[1].length,
                text = parseInline(heading.groupValues[2].trim()),
            )
            i++
            continue
        }

        // Table: a header row followed by a |---|---| separator. Without the separator a line
        // containing pipes is just a sentence containing pipes.
        if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matchEntire(lines[i + 1]) != null) {
            flushParagraph()
            val header = splitRow(line)
            i += 2
            val rows = mutableListOf<List<InlineText>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitRow(lines[i]); i++
            }
            blocks += NoteBlock.Table(header, rows)
            continue
        }

        if (QUOTE.matchEntire(line) != null) {
            flushParagraph()
            val inner = mutableListOf<String>()
            while (i < lines.size) {
                val q = QUOTE.matchEntire(lines[i]) ?: break
                inner += q.groupValues[1]; i++
            }
            // Recursive: a quote can hold paragraphs and lists, and re-running the same parser
            // is both less code and the only way nesting stays consistent with the top level.
            blocks += NoteBlock.Quote(parseNote(inner.joinToString("\n")))
            continue
        }

        if (BULLET.matches(line) || NUMBER.matches(line)) {
            flushParagraph()
            blocks += readList(lines, i).also { i = it.second }.first
            continue
        }

        para += line
        i++
    }
    flushParagraph()
    return blocks
}

/**
 * Reads one run of list rows.
 *
 * Ordered and unordered rows sit in the same run when they're nested inside each other, which
 * a note with "1. Do this" and an indented "- detail" underneath will do constantly. The run's
 * *kind* is decided by its first row; nested rows keep their own marker.
 */
private fun readList(lines: List<String>, start: Int): Pair<NoteBlock, Int> {
    val numbered = NUMBER.matches(lines[start])
    val entries = mutableListOf<ListEntry>()
    var i = start
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            // A single blank line inside a list is spacing; two ends it.
            if (i + 1 < lines.size && (BULLET.matches(lines[i + 1]) || NUMBER.matches(lines[i + 1]))) {
                i++; continue
            }
            break
        }
        val bullet = BULLET.matchEntire(line)
        val number = NUMBER.matchEntire(line)
        when {
            bullet != null -> entries += ListEntry(
                depth = depthOf(bullet.groupValues[1]),
                text = parseInline(bullet.groupValues[2].trim()),
            )
            number != null -> entries += ListEntry(
                depth = depthOf(number.groupValues[1]),
                text = parseInline(number.groupValues[3].trim()),
                marker = "${number.groupValues[2]}.",
            )
            // A wrapped continuation line: indented, not a new row. Fold it into the last one.
            line.startsWith("  ") && entries.isNotEmpty() -> {
                val last = entries.removeAt(entries.lastIndex)
                entries += last.copy(
                    text = InlineText(last.text.spans + parseInline(" " + line.trim()).spans),
                )
            }
            else -> break
        }
        i++
    }
    val block = if (numbered) NoteBlock.Numbers(entries) else NoteBlock.Bullets(entries)
    return block to i
}

private fun depthOf(indent: String): Int {
    val width = indent.sumOf { if (it == '\t') INDENT_UNIT else 1 }
    return (width / INDENT_UNIT).coerceIn(0, MAX_DEPTH)
}

private fun splitRow(line: String): List<InlineText> =
    line.trim().trim('|').split('|').map { parseInline(it.trim()) }

// ---------------------------------------------------------------- inline parsing

private data class Active(var bold: Boolean = false, var italic: Boolean = false, var strike: Boolean = false)

/**
 * Turns one line into styled runs.
 *
 * Hand-written rather than regex-per-style because the markers nest and overlap: `**bold with
 * *italic* inside**` has to come out as one bold run containing a bold-italic run, and running
 * separate passes would either miss it or double-apply. A single left-to-right scan with a
 * small style stack handles nesting for free.
 *
 * Unmatched markers stay literal. Transcribed speech is full of asterisks and underscores that
 * were never meant as emphasis, and eating them silently loses the user's words.
 */
fun parseInline(source: String): InlineText {
    if (source.isEmpty()) return InlineText.EMPTY
    val spans = mutableListOf<Span>()
    val buffer = StringBuilder()
    val active = Active()
    var i = 0

    fun flush() {
        if (buffer.isEmpty()) return
        spans += Span(buffer.toString(), bold = active.bold, italic = active.italic, strike = active.strike)
        buffer.clear()
    }

    while (i < source.length) {
        val c = source[i]

        // Inline code wins over everything: `**not bold**` inside backticks is literal.
        if (c == '`') {
            val close = source.indexOf('`', i + 1)
            if (close > i + 1) {
                flush()
                spans += Span(source.substring(i + 1, close), code = true)
                i = close + 1
                continue
            }
        }

        // [label](href)
        if (c == '[') {
            val closeLabel = source.indexOf(']', i + 1)
            if (closeLabel > i && closeLabel + 1 < source.length && source[closeLabel + 1] == '(') {
                val closeHref = source.indexOf(')', closeLabel + 2)
                if (closeHref > closeLabel) {
                    flush()
                    val label = source.substring(i + 1, closeLabel)
                    val href = source.substring(closeLabel + 2, closeHref).trim()
                    spans += Span(
                        label.ifBlank { href },
                        bold = active.bold, italic = active.italic, strike = active.strike,
                        href = href,
                    )
                    i = closeHref + 1
                    continue
                }
            }
        }

        // A marker that CLOSES an open style always wins. Only an opening marker has to prove
        // there's a partner further along — the closer at the end of "*italic*" has nothing
        // after it to find, and requiring one there left the trailing asterisk in the text.
        if (source.startsWith("~~", i) && (active.strike || hasCloser(source, i + 2, "~~"))) {
            flush(); active.strike = !active.strike; i += 2; continue
        }
        if (source.startsWith("***", i) &&
            ((active.bold && active.italic) || hasCloser(source, i + 3, "***"))
        ) {
            flush()
            val on = !(active.bold && active.italic)
            active.bold = on; active.italic = on
            i += 3
            continue
        }
        if (source.startsWith("**", i) || source.startsWith("__", i)) {
            val marker = source.substring(i, i + 2)
            if (active.bold || hasCloser(source, i + 2, marker)) {
                flush(); active.bold = !active.bold; i += 2; continue
            }
        }
        if ((c == '*' || c == '_') && !intrawordUnderscore(source, i) &&
            (active.italic || hasEmphasisPartner(source, i))
        ) {
            flush(); active.italic = !active.italic; i++; continue
        }

        buffer.append(c)
        i++
    }
    // Any style still open at end-of-line was never closed; the flush keeps its text either way.
    flush()
    return InlineText(spans)
}

private fun hasCloser(source: String, from: Int, marker: String): Boolean =
    source.indexOf(marker, from) >= 0

/**
 * An underscore with word characters on both sides. `snake_case_name` and `file_v2` are not
 * emphasis, and this is the case that matters most: identifiers and filenames turn up in
 * dictated notes constantly, and italicising half of one mangles it.
 */
private fun intrawordUnderscore(source: String, at: Int): Boolean {
    if (source[at] != '_') return false
    val before = source.getOrNull(at - 1) ?: return false
    val after = source.getOrNull(at + 1) ?: return false
    return before.isLetterOrDigit() && after.isLetterOrDigit()
}

/** A lone `*` or `_` opens emphasis only if a matching one follows with something between. */
private fun hasEmphasisPartner(source: String, at: Int): Boolean {
    val marker = source[at]
    if (source.getOrNull(at + 1) == marker) return false // that's a bold run, handled above
    return source.indexOf(marker, at + 1) > at + 1
}

// ---------------------------------------------------------------- serialisation

/** The document as plain text: what Copy should put on the clipboard. */
fun NoteBlock.toPlainText(): String = when (this) {
    is NoteBlock.Heading -> text.plain
    is NoteBlock.Paragraph -> text.plain
    is NoteBlock.Bullets -> items.joinToString("\n") { "${"  ".repeat(it.depth)}• ${it.text.plain}" }
    is NoteBlock.Numbers -> items.joinToString("\n") {
        "${"  ".repeat(it.depth)}${it.marker ?: "-"} ${it.text.plain}"
    }
    is NoteBlock.Quote -> blocks.joinToString("\n") { it.toPlainText() }
    is NoteBlock.Code -> code
    is NoteBlock.Table -> (listOf(header) + rows).joinToString("\n") { row ->
        row.joinToString("   ") { it.plain }
    }
    NoteBlock.Divider -> "—"
}

fun List<NoteBlock>.toPlainText(): String =
    joinToString("\n\n") { it.toPlainText() }.trim()
