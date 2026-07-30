package com.mehdiacho.yap.data

import com.mehdiacho.yap.data.TextCorrections.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this guards against is the expensive one: silently rewriting a word that was
 * never wrong. Most of these cases are about NOT matching.
 */
class TextCorrectionsTest {

    // ------------------------------------------------------------------ detectCorrections

    @Test
    fun `detects a single word substitution`() {
        val corrections = TextCorrections.detectCorrections(
            before = "I told cloud about the roadmap",
            after = "I told Claude about the roadmap",
        )
        assertEquals(listOf(Correction("cloud", "Claude")), corrections)
    }

    @Test
    fun `ignores pure insertions and deletions`() {
        assertTrue(
            TextCorrections.detectCorrections("the roadmap", "the whole roadmap").isEmpty()
        )
        assertTrue(
            TextCorrections.detectCorrections("the whole roadmap", "the roadmap").isEmpty()
        )
    }

    @Test
    fun `ignores a wholesale rewrite`() {
        // Propagating "everything I just rewrote" across a document produces nonsense.
        val corrections = TextCorrections.detectCorrections(
            before = "alpha beta gamma delta",
            after = "completely different words here now",
        )
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun `detects a case-only correction`() {
        assertEquals(
            listOf(Correction("iphone", "iPhone")),
            TextCorrections.detectCorrections("my iphone died", "my iPhone died"),
        )
    }

    @Test
    fun `identical text yields nothing`() {
        assertTrue(TextCorrections.detectCorrections("same text", "same text").isEmpty())
    }

    @Test
    fun `finds a substitution among unchanged surroundings`() {
        val corrections = TextCorrections.detectCorrections(
            before = "we should ship the cloud integration before Friday",
            after = "we should ship the Claude integration before Friday",
        )
        assertEquals(listOf(Correction("cloud", "Claude")), corrections)
    }

    // -------------------------------------------------------------------- findOccurrences

    @Test
    fun `finds every whole-word occurrence regardless of case`() {
        val text = "Cloud is great. I like cloud. CLOUD wins."
        val found = TextCorrections.findOccurrences(text, Correction("cloud", "Claude"))
        assertEquals(3, found.size)
        assertEquals(listOf("Cloud", "cloud", "CLOUD"), found.map { it.matched })
    }

    @Test
    fun `never matches inside a longer word`() {
        // The trust-killer: correcting "cloud" must not touch these.
        val text = "cloudy iCloud clouds cloud-native soundcloud"
        val found = TextCorrections.findOccurrences(text, Correction("cloud", "Claude"))
        assertTrue(
            "matched inside a longer word: ${found.map { it.matched }}",
            found.none { it.start > 0 && text[it.start - 1].isLetter() },
        )
        // "cloud-native" is a genuine whole-word match (hyphen is a boundary); the rest are not.
        assertEquals(1, found.size)
    }

    @Test
    fun `apostrophes are part of a word`() {
        val found = TextCorrections.findOccurrences("don't stop", Correction("don", "Don"))
        assertTrue("'don' inside \"don't\" is not a whole word", found.isEmpty())
    }

    @Test
    fun `context brackets the match`() {
        val text = "We discussed the cloud roadmap at length yesterday afternoon"
        val o = TextCorrections.findOccurrences(text, Correction("cloud", "Claude")).single()
        assertTrue(o.contextBefore.contains("discussed"))
        assertTrue(o.contextAfter.contains("roadmap"))
        assertEquals("cloud", o.matched)
    }

    // ------------------------------------------------------------------------- matchCase

    @Test
    fun `replacement takes the case of what it replaces`() {
        assertEquals("Claude", TextCorrections.matchCase("cloud", "Claude"))
        assertEquals("Claude", TextCorrections.matchCase("Cloud", "Claude"))
        assertEquals("CLAUDE", TextCorrections.matchCase("CLOUD", "Claude"))
        assertEquals("claude", TextCorrections.matchCase("cloud", "claude"))
    }

    // ------------------------------------------------------------------------------ apply

    @Test
    fun `applies multiple occurrences without corrupting offsets`() {
        // Replacements change length, so a front-to-back splice would drift. This is the
        // regression that only appears once two are accepted together.
        val text = "cloud and cloud and cloud"
        val all = TextCorrections.findOccurrences(text, Correction("cloud", "Claude"))
        assertEquals("Claude and Claude and Claude", TextCorrections.apply(text, all))
    }

    @Test
    fun `applies only the accepted subset`() {
        val text = "Cloud storage is fine but cloud wrote the code"
        val all = TextCorrections.findOccurrences(text, Correction("cloud", "Claude"))
        assertEquals(2, all.size)
        // Skip the first — it genuinely means cloud storage.
        val result = TextCorrections.apply(text, listOf(all[1]))
        assertEquals("Cloud storage is fine but Claude wrote the code", result)
    }

    @Test
    fun `applying nothing leaves the text untouched`() {
        val text = "nothing should change here"
        assertEquals(text, TextCorrections.apply(text, emptyList()))
    }

    // -------------------------------------------------------------- end-to-end, the ask

    @Test
    fun `one hand correction propagates across the whole note`() {
        val original = """
            I spoke to cloud about the plan. cloud suggested we ship early.
            Later, cloud drafted the release notes. Separately, our cloud storage bill went up.
        """.trimIndent()
        // The user fixes only the first one, by hand.
        val edited = original.replaceFirst("cloud", "Claude")

        val corrections = TextCorrections.detectCorrections(original, edited)
        assertEquals(listOf(Correction("cloud", "Claude")), corrections)

        val remaining = TextCorrections.findOccurrences(edited, corrections.single())
        assertEquals("should offer the other three, including the storage one", 3, remaining.size)

        // Accept the two that are really Claude, skip "cloud storage".
        val accepted = remaining.filterNot { it.contextAfter.contains("storage") }
        assertEquals(2, accepted.size)
        val result = TextCorrections.apply(edited, accepted)

        assertEquals(3, Regex("Claude").findAll(result).count())
        assertTrue("must not touch genuine cloud storage", result.contains("cloud storage"))
    }
}
