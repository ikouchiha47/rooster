package com.personalos.app.core.tag

import kotlin.math.sqrt

/**
 * Extractive compression for a body that is longer than a character budget: keep
 * the **most salient whole sentences**, in their original order, rather than
 * cutting mid-sentence.
 *
 * Salience is term frequency, damped by sentence length: a sentence scores the sum
 * of the corpus frequencies of its content words, divided by `sqrt(wordCount)`, so a
 * long sentence does not win merely by being long and a short one does not win
 * merely by being short. The lede is not hard-coded as "important" - it is kept
 * only if its words really are the frequent ones.
 *
 * A single sentence over budget is returned whole when it is the only sentence,
 * because there is nothing to select from and a mid-sentence cut is exactly what
 * this exists to avoid; the embedder's own token cap remains the final backstop.
 */
object SalientSentences {
    // Split after a sentence terminator, or at a blank line. Kept simple on purpose:
    // a missed split only means a slightly longer "sentence", never lost text.
    private val BOUNDARY = Regex("(?<=[.!?])\\s+|\\n{2,}")
    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private val STOPWORDS =
        setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "at",
            "by", "from", "is", "are", "was", "were", "be", "been", "being", "as", "that",
            "this", "these", "those", "it", "its", "his", "her", "their", "they", "them",
            "he", "she", "we", "you", "but", "not", "has", "have", "had", "will", "would",
            "can", "could", "should", "may", "might", "says", "said", "also", "more",
            "than", "then", "when", "while", "over", "into", "about", "after", "before",
            "other", "such", "which", "who", "whom", "what", "there", "here", "were",
        )

    fun compress(
        text: String,
        budgetChars: Int,
    ): String {
        val whole = text.trim()
        if (whole.length <= budgetChars) return whole

        val sentences = whole.split(BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
        // Nothing to choose between: return it whole rather than cut it. The
        // embedder's token cap is the final bound.
        if (sentences.size <= 1) return whole

        val freq = HashMap<String, Int>()
        sentences.forEach { sentence -> contentWords(sentence).forEach { freq[it] = (freq[it] ?: 0) + 1 } }

        val ranked =
            sentences
                .mapIndexed { index, sentence ->
                    val words = contentWords(sentence)
                    val raw = words.sumOf { (freq[it] ?: 0).toDouble() }
                    Scored(index, sentence, raw / sqrt(words.size.coerceAtLeast(1).toDouble()))
                }
                // Ties keep document order, so the result is deterministic.
                .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.index })

        val kept = ArrayList<Scored>(ranked.size)
        var length = 0
        for (candidate in ranked) {
            if (kept.isEmpty()) {
                // Always keep the best sentence, even if it alone exceeds the budget.
                kept += candidate
                length = candidate.text.length
                continue
            }
            if (length + 1 + candidate.text.length > budgetChars) continue
            kept += candidate
            length += 1 + candidate.text.length
        }

        return kept.sortedBy { it.index }.joinToString(" ") { it.text }
    }

    private fun contentWords(sentence: String): List<String> =
        WORD
            .findAll(sentence.lowercase())
            .map { it.value }
            .filter { it.length > 3 && it !in STOPWORDS }
            .toList()

    private data class Scored(
        val index: Int,
        val text: String,
        val score: Double,
    )
}
