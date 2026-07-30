package com.mehdiacho.yap.data

/**
 * Turning one hand-made correction into every correction it implies.
 *
 * Transcription mishears a word the same way every time. "Claude" comes back as "cloud" in
 * all eleven places you said it, so fixing one is nearly worthless — the note is still wrong
 * in ten. This finds the rest.
 *
 * Deliberately literal, not clever. Everything here is a deterministic scan over the text:
 * it runs offline, costs nothing, is instant, and always finds the same thing twice. The
 * model's judgement about which occurrences are genuine mistakes is layered on top as
 * advice (see smartReview), never as a precondition — you should not need a network round
 * trip to fix a typo.
 *
 * Nothing is ever applied without the user saying so. The one case that must not happen is
 * silently rewriting a "cloud" that really did mean cloud.
 */
object TextCorrections {

    /** A word the user replaced by hand: [from] became [to]. */
    data class Correction(val from: String, val to: String)

    /**
     * One place [Correction.from] still appears, with enough surrounding text to judge it.
     *
     * [start]/[end] index the ORIGINAL string, so applying a set of occurrences is a
     * straight splice — no re-searching, and no risk of the second replacement landing in
     * the wrong place because the first one shifted the offsets.
     */
    data class Occurrence(
        val start: Int,
        val end: Int,
        /** The matched text exactly as it appears — may differ from `from` in case. */
        val matched: String,
        /** What it would become, capitalised to match [matched]. */
        val replacement: String,
        val contextBefore: String,
        val contextAfter: String,
    )

    private val WORD = Regex("""[\p{L}\p{N}']+""")

    /**
     * Word-level substitutions between two versions of the same text.
     *
     * An LCS diff over word tokens, keeping only 1:1 replacements. Insertions, deletions and
     * multi-word rewrites are ignored on purpose: "cloud → Claude" generalises to the rest of
     * the document, whereas "rewrote this whole sentence" does not, and offering to propagate
     * the latter would produce nonsense.
     */
    fun detectCorrections(before: String, after: String): List<Correction> {
        val a = WORD.findAll(before).map { it.value }.toList()
        val b = WORD.findAll(after).map { it.value }.toList()
        if (a.isEmpty() || b.isEmpty()) return emptyList()

        val keep = longestCommonSubsequence(a, b)
        val out = LinkedHashMap<String, Correction>()

        // Walk both sides together. Between two anchors (words present in both), a run of
        // exactly one dropped word against exactly one added word is a substitution.
        var i = 0
        var j = 0
        for ((index, anchor) in (keep + listOf(a.size to b.size)).withIndex()) {
            val (ai, bj) = anchor
            val removed = a.subList(i, ai)
            val added = b.subList(j, bj)
            if (removed.size == 1 && added.size == 1 && removed[0] != added[0]) {
                out.putIfAbsent(removed[0].lowercase(), Correction(removed[0], added[0]))
            }
            // The anchor itself can still be a correction. Alignment matches words
            // case-insensitively (so a re-capitalised word doesn't derail the whole diff),
            // which means "iphone" → "iPhone" arrives here as a matched pair rather than as
            // a substitution. It is a real fix and worth propagating.
            if (index < keep.size && a[ai] != b[bj]) {
                out.putIfAbsent(a[ai].lowercase(), Correction(a[ai], b[bj]))
            }
            i = ai + 1
            j = bj + 1
        }
        return out.values.toList()
    }

    /**
     * Every remaining occurrence of [correction].from, as whole words.
     *
     * Word boundaries matter: correcting "cloud" must not touch "cloudy" or "iCloud", and a
     * user who has to un-accept those loses all trust in the feature. Matching is
     * case-insensitive so a sentence-initial "Cloud" is caught, and the replacement is
     * re-capitalised to match what it replaces.
     */
    fun findOccurrences(
        text: String,
        correction: Correction,
        contextChars: Int = 34,
    ): List<Occurrence> {
        if (correction.from.isBlank()) return emptyList()
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}'])" + Regex.escape(correction.from) + "(?![\\p{L}\\p{N}'])",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(text).map { m ->
            val s = m.range.first
            val e = m.range.last + 1
            Occurrence(
                start = s,
                end = e,
                matched = m.value,
                replacement = matchCase(m.value, correction.to),
                contextBefore = snippet(text, (s - contextChars).coerceAtLeast(0), s, leading = true),
                contextAfter = snippet(text, e, (e + contextChars).coerceAtMost(text.length), leading = false),
            )
        }.toList()
    }

    /**
     * Apply the accepted occurrences to [text].
     *
     * Splices back-to-front so earlier replacements can't invalidate later offsets — the
     * classic off-by-N that shows up only once two occurrences are accepted at once.
     */
    fun apply(text: String, accepted: List<Occurrence>): String {
        if (accepted.isEmpty()) return text
        val sb = StringBuilder(text)
        accepted.sortedByDescending { it.start }.forEach { o ->
            if (o.start in 0..sb.length && o.end in o.start..sb.length) {
                sb.replace(o.start, o.end, o.replacement)
            }
        }
        return sb.toString()
    }

    /** "cloud" → "Claude", "Cloud" → "Claude", "CLOUD" → "CLAUDE". */
    internal fun matchCase(matched: String, replacement: String): String = when {
        matched.isEmpty() || replacement.isEmpty() -> replacement
        // All-caps only counts with 2+ letters, else "I" forces "CLAUDE".
        matched.length > 1 && matched.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
        matched[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }

    /** Context snippet, trimmed to a word edge with an ellipsis so it reads as a fragment. */
    private fun snippet(text: String, from: Int, to: Int, leading: Boolean): String {
        if (from >= to) return ""
        var s = text.substring(from, to).replace('\n', ' ')
        if (leading && from > 0) {
            val cut = s.indexOfFirst { it.isWhitespace() }
            if (cut in 0 until s.length - 1) s = s.substring(cut + 1)
            s = "…$s"
        } else if (!leading && to < text.length) {
            val cut = s.indexOfLast { it.isWhitespace() }
            if (cut > 0) s = s.substring(0, cut)
            s = "$s…"
        }
        return s
    }

    /** Indices of a longest common subsequence, as (indexInA, indexInB) pairs. */
    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        // Guard against pathological documents: the table is O(n*m), and a 20k-word note
        // against another would allocate ~400M cells. Beyond this, propagation isn't offered.
        if (a.size > 4000 || b.size > 4000) return emptyList()
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                dp[i][j] = if (a[i].equals(b[j], ignoreCase = true)) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i].equals(b[j], ignoreCase = true) -> { out.add(i to j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
