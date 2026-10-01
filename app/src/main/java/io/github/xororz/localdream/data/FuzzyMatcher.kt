package io.github.xororz.localdream.data

/**
 * fzf-style fuzzy matcher (the FuzzyMatchV1 algorithm and scoring model from
 * fzf's algo.go, ported to Kotlin).
 *
 * The pattern must appear as a subsequence of the text; case folding is assumed
 * to have been done by the caller (both pattern and text are compared verbatim).
 * Matches are scored so that hits at word boundaries (the string start or right
 * after '_') and consecutive runs rank highest, while scattered hits separated
 * by large gaps rank lowest. [score] returns [NO_MATCH] when the pattern is not
 * a subsequence of the text.
 */
object FuzzyMatcher {
    const val NO_MATCH: Int = Int.MIN_VALUE

    private const val SCORE_MATCH = 16
    private const val SCORE_GAP_START = -3
    private const val SCORE_GAP_EXTENSION = -1

    // Edge-triggered bonus for the first char of a word; kept modest so long
    // boundary acronyms don't always beat short consecutive matches.
    private const val BONUS_BOUNDARY = SCORE_MATCH / 2

    // Letter<->digit transitions inside a word (we are lower-cased, so there is
    // no camelCase case to reward, only the numeric edge).
    private const val BONUS_CAMEL_123 = BONUS_BOUNDARY + SCORE_GAP_EXTENSION

    // Minimum bonus handed to chars inside a consecutive run.
    private const val BONUS_CONSECUTIVE = -(SCORE_GAP_START + SCORE_GAP_EXTENSION)

    // The first matched char carries extra weight so the pattern's lead anchors
    // at a meaningful position.
    private const val BONUS_FIRST_CHAR_MULTIPLIER = 2

    private const val CLASS_NON_WORD = 0
    private const val CLASS_NUMBER = 1
    private const val CLASS_LETTER = 2

    private const val INDEX_SHIFT = 32
    private const val INDEX_MASK = 0xffffffffL

    /** Score only, zero allocation. Returns [NO_MATCH] if pattern is absent. */
    fun score(pattern: CharArray, text: String): Int {
        val packed = locate(pattern, text)
        if (packed < 0L) return NO_MATCH
        val sidx = (packed ushr INDEX_SHIFT).toInt()
        val eidx = (packed and INDEX_MASK).toInt()
        return calculate(pattern, text, sidx, eidx, null)
    }

    /** Matched character indices into [text] (ascending), or null when absent. */
    fun positions(pattern: CharArray, text: String): IntArray? {
        if (pattern.isEmpty()) return IntArray(0)
        val packed = locate(pattern, text)
        if (packed < 0L) return null
        val sidx = (packed ushr INDEX_SHIFT).toInt()
        val eidx = (packed and INDEX_MASK).toInt()
        val out = IntArray(pattern.size)
        calculate(pattern, text, sidx, eidx, out)
        return out
    }

    // Forward greedy pass locates a match, then a backward pass tightens the
    // start so the matched span is packed as far right as possible. Returns
    // (sidx shl 32 | eidx) or -1 when the pattern is not a subsequence.
    private fun locate(pattern: CharArray, text: String): Long {
        val m = pattern.size
        val n = text.length
        if (m == 0) return 0L
        if (m > n) return -1L

        var pidx = 0
        var sidx = -1
        var eidx = -1
        var j = 0
        while (j < n) {
            if (text[j] == pattern[pidx]) {
                if (sidx < 0) sidx = j
                pidx++
                if (pidx == m) {
                    eidx = j + 1
                    break
                }
            }
            j++
        }
        if (eidx < 0) return -1L

        pidx = m - 1
        j = eidx - 1
        while (j >= sidx) {
            if (text[j] == pattern[pidx]) {
                pidx--
                if (pidx < 0) {
                    sidx = j
                    break
                }
            }
            j--
        }
        return (sidx.toLong() shl INDEX_SHIFT) or (eidx.toLong() and INDEX_MASK)
    }

    // Single forward pass over [sidx, eidx) accumulating the fzf score. When
    // [positionsOut] is non-null it is filled with the matched indices (exactly
    // pattern.size of them, since the loop stops once the pattern is consumed).
    private fun calculate(pattern: CharArray, text: String, sidx: Int, eidx: Int, positionsOut: IntArray?): Int {
        val m = pattern.size
        var pidx = 0
        var score = 0
        var inGap = false
        var consecutive = 0
        var firstBonus = 0
        var posCount = 0
        var prevClass = if (sidx > 0) charClassOf(text[sidx - 1]) else CLASS_NON_WORD

        var j = sidx
        while (j < eidx) {
            val c = text[j]
            val clazz = charClassOf(c)
            if (c == pattern[pidx]) {
                if (positionsOut != null) positionsOut[posCount++] = j
                var bonus = bonusFor(prevClass, clazz)
                if (consecutive == 0) {
                    firstBonus = bonus
                } else {
                    // A boundary inside the run restarts the inherited bonus.
                    if (bonus >= BONUS_BOUNDARY && bonus > firstBonus) firstBonus = bonus
                    bonus = maxOf(maxOf(bonus, firstBonus), BONUS_CONSECUTIVE)
                }
                score += if (pidx == 0) {
                    SCORE_MATCH + bonus * BONUS_FIRST_CHAR_MULTIPLIER
                } else {
                    SCORE_MATCH + bonus
                }
                inGap = false
                consecutive++
                pidx++
                if (pidx == m) break
            } else {
                score += if (inGap) SCORE_GAP_EXTENSION else SCORE_GAP_START
                inGap = true
                consecutive = 0
                firstBonus = 0
            }
            prevClass = clazz
            j++
        }
        return score
    }

    private fun charClassOf(c: Char): Int = when {
        c in 'a'..'z' || c in 'A'..'Z' -> CLASS_LETTER
        c in '0'..'9' -> CLASS_NUMBER
        else -> CLASS_NON_WORD
    }

    // Text is lower-cased upstream, so the only word boundaries are the string
    // start and chars following a non-word char (e.g. '_'), plus digit edges.
    // Non-ASCII letters (CJK translations) fall into CLASS_NON_WORD and earn no
    // boundary bonus, relying on the consecutive bonus to reward contiguity.
    private fun bonusFor(prevClass: Int, clazz: Int): Int = when {
        clazz == CLASS_NON_WORD -> 0
        prevClass == CLASS_NON_WORD -> BONUS_BOUNDARY
        prevClass == CLASS_NUMBER && clazz != CLASS_NUMBER -> BONUS_CAMEL_123
        prevClass != CLASS_NUMBER && clazz == CLASS_NUMBER -> BONUS_CAMEL_123
        else -> 0
    }
}
