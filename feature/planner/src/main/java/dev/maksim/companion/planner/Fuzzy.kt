package dev.maksim.companion.planner

import java.text.Normalizer
import java.util.Locale

/**
 * Matching names the way people type them: without Estonian letters ("parnu" for Pärnu, "ulemiste" for Ülemiste,
 * "oismae" for Õismäe), with a letter missing, extra, wrong or swapped ("lasnamae", "kristine", "mustmäe"), one
 * double letter for two ("talin"), with or without spaces ("baltijaam"), and while still typing ("balti j").
 */
object Fuzzy {

    /** A name prepared once for [score]: an index has many of them, scored on every key typed. */
    class Name(text: String) {
        val tokens: List<String> = tokens(text)
        val compact: String = tokens.joinToString("")
    }

    /** What's typed, prepared once per search. Its last word may still be being typed, unless a space follows. */
    class Query(text: String) {
        val tokens: List<String> = tokens(text)
        val compact: String = tokens.joinToString("")
        val isEmpty get() = tokens.isEmpty()
    }

    /** Below this, a name isn't counted as found. */
    const val MATCH = 60

    /**
     * Scores this close are as good a match, and other things (how near, how big a place) decide: a name with a
     * word more than typed still comes after one without.
     */
    const val RANK = 5

    /**
     * How well [name] matches [query], 0 when it doesn't: 100 and up for the same words, 90s for words typed
     * so far, 60s to 80s with typos. Each word of the query has to be found in the name; names with more words
     * than were typed come a little after those without ("Tartu" before "Tartu mnt").
     */
    fun score(query: Query, name: Name): Int {
        if (query.isEmpty || name.tokens.isEmpty()) return 0
        var total = 0
        val used = BooleanArray(name.tokens.size)
        var firstFirst = false
        for ((i, q) in query.tokens.withIndex()) {
            var best = 0
            var bestAt = -1
            for ((j, t) in name.tokens.withIndex()) {
                if (used[j]) continue
                val s = word(q, t)
                if (s > best) {
                    best = s
                    bestAt = j
                }
            }
            if (best == 0) return compact(query, name)
            used[bestAt] = true
            if (i == 0 && bestAt == 0) firstFirst = true
            total += best
        }
        var score = total / query.tokens.size
        if (firstFirst) score += 6
        score -= 3 * used.count { !it }
        return maxOf(score, compact(query, name))
    }

    /**
     * One typed word against one word of a name. People seldom get a word's first letter wrong, so a word
     * starting with another letter is only forgiven one typo, and only as a whole word.
     */
    private fun word(q: String, t: String): Int {
        if (q == t) return 100
        if (t.startsWith(q)) return if (q.length >= 2) 90 else 70
        val sameStart = q[0] == t[0]
        val allowed = typos(q.length).let { if (sameStart) it else minOf(it, 1) }
        if (allowed == 0) return 0
        if (kotlin.math.abs(q.length - t.length) <= allowed) {
            val d = distance(q, t, allowed)
            if (d <= allowed) return 80 - 10 * d
        }
        // Still typing it: like the start of the word, but for a letter.
        if (sameStart && t.length > q.length) {
            for (length in q.length - 1..minOf(t.length, q.length + 1)) {
                if (distance(q, t.substring(0, length), 1) <= 1) return 65
            }
        }
        return 0
    }

    /** All typed as one against the name as one, for words run together or split differently. */
    private fun compact(query: Query, name: Name): Int {
        if (query.tokens.size == 1 && name.tokens.size == 1) return 0
        val q = query.compact
        val t = name.compact
        if (q.length < 4) return 0
        if (q == t) return 95
        if (t.startsWith(q)) return 85
        val allowed = typos(q.length)
        if (t.length >= q.length - allowed) {
            val d = distance(q, t.substring(0, minOf(t.length, q.length)), allowed)
            if (d <= allowed) return 70 - 10 * d
        }
        return 0
    }

    /** Typos forgiven in a word of [length] letters. */
    private fun typos(length: Int) = when {
        length <= 3 -> 0
        length <= 6 -> 1
        else -> 2
    }

    /**
     * [text] in lower case without accents (ä, õ, ö, ü, š, ž as a, o, o, u, s, z), split into words at anything
     * that isn't a letter or digit, each with double letters as one: "Tallinn" and "Talin" are both "talin".
     */
    fun tokens(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }.map(::squeeze)

    /** [text] in lower case, without accents, with anything but letters and digits as single spaces. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var space = true
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> {
                    out.append(c)
                    space = false
                }
                !space -> {
                    out.append(' ')
                    space = true
                }
            }
        }
        return out.trimEnd().toString()
    }

    private fun squeeze(word: String): String {
        val out = StringBuilder(word.length)
        for (c in word) if (out.isEmpty() || out[out.length - 1] != c) out.append(c)
        return out.toString()
    }

    /**
     * Edits from [a] to [b], a swap of two letters next to each other counting as one (optimal string alignment);
     * anything over [max] as max + 1.
     */
    fun distance(a: String, b: String, max: Int): Int {
        if (a.isEmpty() || b.isEmpty()) return minOf(maxOf(a.length, b.length), max + 1)
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var before = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var d = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, before[j - 2] + 1)
                current[j] = d
                rowBest = minOf(rowBest, d)
            }
            if (rowBest > max) return max + 1
            val spare = before
            before = previous
            previous = current
            current = spare
        }
        return minOf(previous[b.length], max + 1)
    }
}
