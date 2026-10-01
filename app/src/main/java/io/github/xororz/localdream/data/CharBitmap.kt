package io.github.xororz.localdream.data

/**
 * Language-agnostic character-presence bitmap used to pre-filter fuzzy
 * candidates. Every character is hashed into one of [WIDTH] bits; an entry's
 * bitmap ORs the bits of all its characters. A pattern can only be a subsequence
 * of a text if every pattern bit is also set in the text bitmap, so
 * `(entry & query) == query` cheaply rejects the vast majority of non-matches
 * before the O(len) subsequence scan runs.
 *
 * Hash collisions only ever weaken the filter (false positives that fall through
 * to the real check); they can never drop a genuine match. Because it hashes raw
 * code units instead of assuming an alphabet, it works the same for Latin, CJK,
 * Cyrillic or any other script — which matters since the translation dictionary
 * may be in any language.
 */
object CharBitmap {
    /** Number of longs per bitmap (128 bits). */
    const val WORDS = 2

    private const val SHIFT = 7 // log2(128)
    private const val GOLDEN = 0x9E3779B1L // Knuth multiplicative hash constant

    /** OR the bits of [s] into [out] at [offset, offset + WORDS). */
    fun addInto(out: LongArray, offset: Int, s: CharSequence) {
        for (i in s.indices) {
            val bit = bitOf(s[i])
            out[offset + (bit ushr 6)] = out[offset + (bit ushr 6)] or (1L shl (bit and 63))
        }
    }

    /** A freshly allocated bitmap for [s]. Use for the (single) query per call. */
    fun of(s: CharSequence): LongArray = LongArray(WORDS).also { addInto(it, 0, s) }

    /** True when every bit set in the query words is also set at [entry]+[offset]. */
    fun contains(entry: LongArray, offset: Int, q0: Long, q1: Long): Boolean = entry[offset] and q0 == q0 && entry[offset + 1] and q1 == q1

    private fun bitOf(c: Char): Int = ((c.code.toLong() * GOLDEN and 0xFFFFFFFFL) ushr (32 - SHIFT)).toInt()
}
