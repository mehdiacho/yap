package com.mehdiacho.yap.ui.note

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the part of note rendering that can be wrong silently.
 *
 * A layout bug is visible the moment you open a note. A parser bug eats a user's words — an
 * unmatched asterisk swallowing half a sentence, an indented line disappearing into the row
 * above — and looks like a perfectly reasonable note that just doesn't say what they said.
 * These cases are mostly about what must NOT be treated as markup.
 */
class NoteDocumentTest {

    private fun blocks(md: String) = parseNote(md)
    private fun onlyText(md: String) = parseNote(md).toPlainText()

    // ------------------------------------------------------------------ blocks

    @Test
    fun `consecutive lines join into one paragraph`() {
        val out = blocks("The model wraps\nits output across lines.")
        assertEquals(1, out.size)
        assertEquals("The model wraps its output across lines.", (out[0] as NoteBlock.Paragraph).text.plain)
    }

    @Test
    fun `blank line separates paragraphs`() {
        val out = blocks("First.\n\nSecond.")
        assertEquals(2, out.size)
        assertTrue(out.all { it is NoteBlock.Paragraph })
    }

    @Test
    fun `heading level is captured and body is not swallowed`() {
        val out = blocks("## Next steps\nCall the bank.")
        assertEquals(2, out.size)
        val h = out[0] as NoteBlock.Heading
        assertEquals(2, h.level)
        assertEquals("Next steps", h.text.plain)
        assertEquals("Call the bank.", (out[1] as NoteBlock.Paragraph).text.plain)
    }

    @Test
    fun `hash without a space is not a heading`() {
        // "#1 priority" is something people say.
        val out = blocks("#1 priority is the invoice")
        assertTrue(out[0] is NoteBlock.Paragraph)
    }

    @Test
    fun `bullets nest by indent`() {
        val out = blocks("- Top\n  - Nested\n    - Deeper")
        val list = out.single() as NoteBlock.Bullets
        assertEquals(listOf(0, 1, 2), list.items.map { it.depth })
        assertEquals(listOf("Top", "Nested", "Deeper"), list.items.map { it.text.plain })
    }

    @Test
    fun `numbered list keeps its own markers`() {
        val out = blocks("1. First\n2. Second\n3. Third")
        val list = out.single() as NoteBlock.Numbers
        assertEquals(listOf("1.", "2.", "3."), list.items.map { it.marker })
    }

    @Test
    fun `fenced code is literal inside`() {
        val out = blocks("```kotlin\nval x = **not bold**\n# not a heading\n```")
        val code = out.single() as NoteBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = **not bold**\n# not a heading", code.code)
    }

    @Test
    fun `unterminated fence still yields a code block rather than eating the rest`() {
        val out = blocks("```\nstill code")
        val code = out.single() as NoteBlock.Code
        assertEquals("still code", code.code)
    }

    @Test
    fun `quote parses its contents recursively`() {
        val out = blocks("> He said this.\n> - and listed that")
        val quote = out.single() as NoteBlock.Quote
        assertTrue(quote.blocks[0] is NoteBlock.Paragraph)
        assertTrue(quote.blocks[1] is NoteBlock.Bullets)
    }

    @Test
    fun `table needs its separator row`() {
        val table = blocks("| A | B |\n| --- | --- |\n| 1 | 2 |").single() as NoteBlock.Table
        assertEquals(listOf("A", "B"), table.header.map { it.plain })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.plain } })
    }

    @Test
    fun `pipes in a sentence are not a table`() {
        val out = blocks("Compare cost | benefit and decide.")
        assertTrue(out.single() is NoteBlock.Paragraph)
    }

    @Test
    fun `three dashes is a divider`() {
        assertTrue(blocks("Before\n\n---\n\nAfter")[1] is NoteBlock.Divider)
    }

    // ------------------------------------------------------------------ inline

    @Test
    fun `bold and italic apply`() {
        val spans = parseInline("plain **bold** and *italic*").spans
        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "italic" && it.italic })
    }

    @Test
    fun `italic nests inside bold`() {
        val spans = parseInline("**outer *inner* outer**").spans
        val inner = spans.single { it.text == "inner" }
        assertTrue(inner.bold && inner.italic)
    }

    @Test
    fun `inline code is not parsed for emphasis`() {
        val spans = parseInline("run `make **all**` now").spans
        val code = spans.single { it.code }
        assertEquals("make **all**", code.text)
    }

    @Test
    fun `unmatched asterisk stays literal`() {
        // Dictated text is full of these. Losing the character loses the user's words.
        assertEquals("2 * 3 = 6", parseInline("2 * 3 = 6").plain)
        assertEquals("a ** b", parseInline("a ** b").plain)
    }

    @Test
    fun `underscores inside a word are not emphasis`() {
        val text = parseInline("open snake_case_name today")
        assertEquals("open snake_case_name today", text.plain)
        assertTrue(text.spans.none { it.italic })
    }

    @Test
    fun `link keeps its label and href`() {
        val span = parseInline("see [the docs](https://example.com/x) here").spans.single { it.href != null }
        assertEquals("the docs", span.text)
        assertEquals("https://example.com/x", span.href)
    }

    @Test
    fun `bracket without a url is literal`() {
        assertEquals("[inaudible] then he left", parseInline("[inaudible] then he left").plain)
    }

    @Test
    fun `strikethrough applies`() {
        assertTrue(parseInline("~~dropped~~").spans.single().strike)
    }

    @Test
    fun `speaker label formatting survives`() {
        val spans = parseInline("**MAYA:** we should ship it").spans
        assertTrue(spans.first { it.bold }.text.startsWith("MAYA"))
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `plain text keeps every word of a realistic note`() {
        val md = """
            ## Decisions

            We agreed to **ship on Friday**, with the caveat that *staging* has to be green.

            - Ask Sam about the 2 * 3 pricing tiers
              - and the snake_case_name field
            1. Draft the note
            2. Send it

            > He said: "no more delays".

            | Plan | Cost |
            | --- | --- |
            | Free | 0 |
        """.trimIndent()
        val text = onlyText(md)
        listOf(
            "Decisions", "ship on Friday", "staging", "2 * 3 pricing tiers",
            "snake_case_name", "Draft the note", "no more delays", "Free",
        ).forEach { assertTrue("missing: $it in\n$text", text.contains(it)) }
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(parseNote("").isEmpty())
        assertTrue(parseNote("\n\n   \n").isEmpty())
    }
}
