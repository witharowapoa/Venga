package au.nick.venga

import java.text.Normalizer
import java.util.Locale

enum class Verdict { CORRECT, CLOSE, REGIONAL, WRONG }

data class Check(val verdict: Verdict, val heard: String)

/**
 * Compares what the recogniser heard (or what was typed) with a card's answers.
 * Lenient about accents, capitals, punctuation and leading articles (el, la, un…),
 * and copes with speech services that mask swear words with asterisks (e.g. "j****").
 */
object Matcher {

    private val articles = setOf("el", "la", "los", "las", "un", "una", "unos", "unas", "lo")
    private val spain = Locale("es", "ES")

    fun normalize(input: String): String {
        val lower = input.lowercase(spain)
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val cleaned = stripped.replace(Regex("[^a-z0-9* ]+"), " ").trim().replace(Regex("\\s+"), " ")
        val words = cleaned.split(" ").filter { it.isNotEmpty() }
        return if (words.size > 1 && words[0] in articles) words.drop(1).joinToString(" ") else cleaned
    }

    private fun same(heard: String, answer: String): Boolean {
        if (heard.isEmpty() || answer.isEmpty()) return false
        if (heard == answer) return true
        if (wildcard(heard, answer)) return true
        // Said it inside a short phrase, e.g. "me mola mazo" for "mazo".
        if (answer.length >= 3 && " $heard ".contains(" $answer ")) return true
        // Masked swear word inside a phrase.
        if ('*' in heard) {
            val hw = heard.split(" ")
            val aw = answer.split(" ")
            if (aw.size <= hw.size) {
                for (start in 0..hw.size - aw.size) {
                    if (aw.indices.all { i -> wildcard(hw[start + i], aw[i]) || hw[start + i] == aw[i] }) return true
                }
            }
        }
        return false
    }

    private fun wildcard(heard: String, answer: String): Boolean {
        if ('*' !in heard || heard.length != answer.length) return false
        return heard.indices.all { heard[it] == '*' || heard[it] == answer[it] }
    }

    private fun tolerance(len: Int) = when {
        len <= 3 -> 0
        len <= 7 -> 1
        else -> 2
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    fun check(card: Card, heardOptions: List<String>): Check {
        val options = heardOptions.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.isEmpty()) return Check(Verdict.WRONG, "")
        val answers = card.answers.map { normalize(it) }
        val regional = card.regional.map { normalize(it) }

        for (h in options) {
            val hn = normalize(h)
            if (answers.any { same(hn, it) }) return Check(Verdict.CORRECT, h)
        }
        for (h in options) {
            val hn = normalize(h)
            if (regional.any { same(hn, it) }) return Check(Verdict.REGIONAL, h)
        }
        for (h in options) {
            val hn = normalize(h)
            if (answers.any { levenshtein(hn, it) <= tolerance(it.length) }) return Check(Verdict.CLOSE, h)
        }
        return Check(Verdict.WRONG, options.first())
    }
}
