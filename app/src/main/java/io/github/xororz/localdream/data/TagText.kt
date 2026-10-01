package io.github.xororz.localdream.data

import kotlin.math.abs

private val underscoreRegex = Regex("_+")

// Splits one CSV line, honoring double-quoted fields and the "" escape.
internal fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' -> {
                if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                    current.append('"')
                    index++
                } else {
                    inQuotes = !inQuotes
                }
            }

            char == ',' && !inQuotes -> {
                result += current.toString()
                current.setLength(0)
            }

            else -> current.append(char)
        }
        index++
    }
    result += current.toString()
    return result
}

internal fun normalizeTranslation(value: String): String = value.trim().replace(" ", "").lowercase()

internal fun normalizeQuery(value: String): String = value
    .trim()
    .lowercase()
    .replace(' ', '_')
    .replace('-', '_')
    .replace(underscoreRegex, "_")

internal fun containsNonAsciiLetter(value: String): Boolean = value.any { it.code > 127 && it.isLetter() }

// Renders a stored tag for display/insertion: a bare '_' is a word separator and
// becomes a space, while an escaped '\_' is a literal underscore kept as '_'.
internal fun tagUnderscoresToSpaces(tag: String): String {
    if ('_' !in tag) return tag
    val sb = StringBuilder(tag.length)
    var i = 0
    while (i < tag.length) {
        val c = tag[i]
        when {
            c == '\\' && i + 1 < tag.length && tag[i + 1] == '_' -> {
                sb.append('_')
                i += 2
            }

            c == '_' -> {
                sb.append(' ')
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

// Escapes the SD prompt attention metacharacters so a tag's literal parentheses
// are not parsed as a weighting group, e.g. "aqua (konosuba)" -> "aqua \(konosuba\)".
internal fun escapePromptParentheses(text: String): String = text.replace("(", "\\(").replace(")", "\\)")

// Damerau-Levenshtein distance with an early-out once every cell in a row
// exceeds maxDistance (used only by the typo-correction fallback).
internal fun damerauLevenshtein(source: String, target: String, maxDistance: Int): Int {
    if (source == target) return 0
    if (abs(source.length - target.length) > maxDistance) return maxDistance + 1

    val rows = source.length + 1
    val cols = target.length + 1
    val dp = Array(rows) { IntArray(cols) }

    for (i in 0 until rows) dp[i][0] = i
    for (j in 0 until cols) dp[0][j] = j

    for (i in 1 until rows) {
        var rowMin = Int.MAX_VALUE
        for (j in 1 until cols) {
            val cost = if (source[i - 1] == target[j - 1]) 0 else 1
            var value = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost,
            )
            if (i > 1 && j > 1 && source[i - 1] == target[j - 2] && source[i - 2] == target[j - 1]) {
                value = minOf(value, dp[i - 2][j - 2] + cost)
            }
            dp[i][j] = value
            if (value < rowMin) rowMin = value
        }
        if (rowMin > maxDistance) return maxDistance + 1
    }

    return dp[source.length][target.length]
}
