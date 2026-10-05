package io.github.xororz.localdream.utils

/**
 * et.31: local on-device content restriction (no network, no telemetry).
 * ON: block generation when prompt/negative contains blocked words.
 * OFF: pass-through, no injection of any extra words.
 */
object ContentFilter {
    private val BLOCKED = setOf(
        // Chinese
        "裸体", "裸照", "裸", "脱衣", "色情", "黄色", "情色", "艳舞", "做爱",
        // English (common forms, lower-cased substring match)
        "nude", "naked", "undress", "nsfw", "porn", "nude photo", "topless",
    )

    fun containsBlocked(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase()
        for (w in BLOCKED) {
            if (lower.contains(w)) return w
        }
        return null
    }
}
