package com.mehdiacho.yap.ui.note

import com.mehdiacho.yap.data.WordTiming

/**
 * One line of caption: a handful of words shown together.
 *
 * Captions are cut into fixed cues once, rather than rendered as a window that slides a word
 * at a time. A sliding window re-flows the text on every single word — it never sits still
 * long enough to be read, which is the one thing a caption has to do. Cues hold a line
 * steady, and only the highlight moves inside it.
 */
data class CaptionCue(
    val words: List<WordTiming>,
    val startMs: Long,
    val endMs: Long,
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Longest a cue gets. Two comfortable lines on a phone. */
private const val MAX_WORDS_PER_CUE = 9

/**
 * A silence this long ends a cue.
 *
 * Speech pauses at clause and sentence boundaries, so breaking on the gap lands the cut where
 * the speaker themselves put one — which reads far better than cutting purely on word count.
 */
private const val CUE_BREAK_GAP_MS = 700L

/** Cues also break after this, so a full stop doesn't end up mid-line. */
private const val SENTENCE_ENDINGS = ".?!…"

/**
 * Group timed words into readable caption lines.
 *
 * Input is assumed to be in spoken order; the model returns it that way. Words with nonsense
 * timings are kept rather than dropped — a caption slightly out of step is better than a
 * sentence with a hole in it.
 */
fun buildCues(words: List<WordTiming>): List<CaptionCue> {
    if (words.isEmpty()) return emptyList()
    val cues = mutableListOf<CaptionCue>()
    var current = mutableListOf<WordTiming>()

    fun flush() {
        if (current.isEmpty()) return
        cues += CaptionCue(
            words = current.toList(),
            startMs = current.first().startMs,
            endMs = current.maxOf { it.endMs },
        )
        current = mutableListOf()
    }

    for ((i, word) in words.withIndex()) {
        val previous = words.getOrNull(i - 1)
        val gap = if (previous == null) 0L else word.startMs - previous.endMs
        if (current.isNotEmpty() && gap >= CUE_BREAK_GAP_MS) flush()

        current += word
        val endsSentence = word.text.lastOrNull()?.let { it in SENTENCE_ENDINGS } == true
        if (current.size >= MAX_WORDS_PER_CUE || endsSentence) flush()
    }
    flush()
    return cues
}

/**
 * The cue to show at [positionMs], or null when the playhead sits in a silence.
 *
 * A gap between cues shows nothing rather than holding the previous line on screen. Leaving
 * a stale caption up during a pause makes it look like the captions have desynced.
 */
fun cueAt(cues: List<CaptionCue>, positionMs: Long): CaptionCue? {
    if (cues.isEmpty()) return null
    // Binary search for the last cue that has already started.
    var lo = 0
    var hi = cues.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (cues[mid].startMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (found < 0) return null
    val cue = cues[found]
    return if (positionMs <= cue.endMs) cue else null
}

/**
 * Index of the word being spoken within [cue], or -1 when between words.
 *
 * The highlight is what makes a caption feel attached to the picture rather than printed
 * near it, so it tracks the actual word rather than interpolating across the line.
 */
fun activeWordIndex(cue: CaptionCue, positionMs: Long): Int =
    cue.words.indexOfFirst { positionMs >= it.startMs && positionMs <= it.endMs }
