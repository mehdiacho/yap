package com.mehdiacho.yap.ui.note

import com.mehdiacho.yap.data.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caption timing is the kind of thing that looks fine in a demo and is wrong everywhere else.
 *
 * The failures that matter here are quiet ones: a line that holds on screen through a silence
 * so the captions look desynced, a lookup that picks the wrong cue near a boundary, an empty
 * transcript taking the player down with it. All of them are invisible until someone watches
 * a real video all the way through, which is exactly why they are worth pinning here.
 */
class CaptionsTest {

    private fun w(text: String, startMs: Long, endMs: Long) = WordTiming(text, startMs, endMs)

    /** Evenly spaced words, [gap]ms of speech each, no pauses. */
    private fun run(vararg words: String, from: Long = 0, each: Long = 300) =
        words.mapIndexed { i, t -> w(t, from + i * each, from + i * each + each - 50) }

    // ------------------------------------------------------------------ cue building

    @Test
    fun `empty input yields no cues`() {
        assertEquals(emptyList<CaptionCue>(), buildCues(emptyList()))
    }

    @Test
    fun `a short run becomes one cue`() {
        val cues = buildCues(run("the", "quick", "brown", "fox"))
        assertEquals(1, cues.size)
        assertEquals("the quick brown fox", cues[0].text)
    }

    @Test
    fun `a long run is split rather than left as one unreadable line`() {
        val cues = buildCues(run(*Array(25) { "word$it" }))
        assertTrue("expected several cues, got ${cues.size}", cues.size >= 3)
        assertTrue("no cue may exceed the line budget", cues.all { it.words.size <= 9 })
    }

    @Test
    fun `a pause breaks the cue`() {
        val words = run("before", "the") + run("pause", "after", from = 3_000)
        val cues = buildCues(words)
        assertEquals(2, cues.size)
        assertEquals("before the", cues[0].text)
        assertEquals("pause after", cues[1].text)
    }

    @Test
    fun `a sentence ending breaks the cue`() {
        val cues = buildCues(run("that", "is", "all.", "now", "this"))
        assertEquals(2, cues.size)
        assertEquals("that is all.", cues[0].text)
        assertEquals("now this", cues[1].text)
    }

    @Test
    fun `cue bounds span its words`() {
        val cues = buildCues(run("one", "two", "three"))
        assertEquals(0L, cues[0].startMs)
        assertEquals(850L, cues[0].endMs)
    }

    // ------------------------------------------------------------------ lookup

    @Test
    fun `lookup finds the cue covering the playhead`() {
        val cues = buildCues(run("before", "the") + run("pause", "after", from = 3_000))
        assertEquals("before the", cueAt(cues, 200)?.text)
        assertEquals("pause after", cueAt(cues, 3_100)?.text)
    }

    @Test
    fun `nothing shows during a silence between cues`() {
        val cues = buildCues(run("before", "the") + run("pause", "after", from = 3_000))
        // 1.5s in: the first cue ended at 550ms, the second doesn't start until 3000ms.
        // Holding the first line here is what makes captions look broken.
        assertNull(cueAt(cues, 1_500))
    }

    @Test
    fun `nothing shows before the first word`() {
        val cues = buildCues(run("hello", "there", from = 2_000))
        assertNull(cueAt(cues, 100))
    }

    @Test
    fun `lookup on an empty transcript is not a crash`() {
        assertNull(cueAt(emptyList(), 1_000))
    }

    @Test
    fun `boundaries are inclusive at both ends`() {
        val cues = buildCues(listOf(w("solo", 1_000, 2_000)))
        assertEquals("solo", cueAt(cues, 1_000)?.text)
        assertEquals("solo", cueAt(cues, 2_000)?.text)
        assertNull(cueAt(cues, 2_001))
    }

    // ------------------------------------------------------------------ highlight

    @Test
    fun `the spoken word is the highlighted one`() {
        val cue = buildCues(run("alpha", "beta", "gamma"))[0]
        assertEquals(0, activeWordIndex(cue, 100))
        assertEquals(1, activeWordIndex(cue, 400))
        assertEquals(2, activeWordIndex(cue, 700))
    }

    @Test
    fun `between two words nothing is highlighted`() {
        // Words run 0-250 and 300-550; 270ms falls in the gap between them.
        val cue = buildCues(run("alpha", "beta"))[0]
        assertEquals(-1, activeWordIndex(cue, 270))
    }

    // ------------------------------------------------------------------ robustness

    @Test
    fun `words with degenerate timings are kept not dropped`() {
        // A zero-length word is a model artefact, not a reason to lose it from the sentence.
        val cues = buildCues(listOf(w("kept", 500, 500), w("also", 600, 900)))
        assertEquals("kept also", cues.joinToString(" ") { it.text })
    }

    @Test
    fun `a long transcript stays ordered and complete`() {
        val words = run(*Array(200) { "w$it" })
        val cues = buildCues(words)
        assertEquals(
            words.map { it.text },
            cues.flatMap { cue -> cue.words.map { it.text } },
        )
        // Cue starts must be non-decreasing or the binary search in cueAt is meaningless.
        assertTrue(cues.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
    }
}
