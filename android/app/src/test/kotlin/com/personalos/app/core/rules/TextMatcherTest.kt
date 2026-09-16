package com.personalos.app.core.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {
    private fun matcher(pattern: String) = TextMatcher(pattern, TextTarget.ANY)

    // --------------------------------------------------------- Unicode boundaries

    @Test
    fun `a Devanagari word at a word boundary matches`() {
        assertTrue(matcher("बंध").matches("आज बंध है"))
    }

    @Test
    fun `a Devanagari fragment inside a word does not match`() {
        // `\b` is ASCII-only, so it would report a boundary between `ल` and `म`
        // here and fire; the Unicode lookarounds know both are letters.
        assertFalse(matcher("कल").matches("कलम"))
        assertTrue(matcher("कल").matches("कल ठीक है"))
    }

    @Test
    fun `case folding is Unicode aware, not just ASCII`() {
        // Only the `(?u)` flag turns on UNICODE_CASE; IGNORE_CASE alone folds
        // ASCII only, so Cyrillic would miss without it.
        assertTrue(matcher("привет").matches("ПРИВЕТ"))
        assertTrue(matcher("Bandh").matches("BANDH"))
    }

    @Test
    fun `an ASCII word does not match inside a longer word`() {
        assertFalse(matcher("down").matches("download complete"))
        assertTrue(matcher("down").matches("the system is down again"))
    }

    @Test
    fun `an empty input never matches`() {
        assertFalse(matcher("anything").matches(""))
    }

    // ----------------------------------------------------------------- ReDoS bound

    /**
     * Catastrophic on an unbounded input: `(a+)+$` backtracks over every partition
     * of the run of `a`s once `$` fails against the trailing `!`. Because
     * [TextMatcher.matches] only examines the first [TextMatcher.MAX_TEXT_LENGTH]
     * characters, the `!` falls outside the bound, the match succeeds immediately,
     * and the pathological case cannot stall ingest.
     */
    @Test(timeout = 10_000)
    fun `a pathological pattern hits the input bound instead of hanging`() {
        val pathological = "a".repeat(TextMatcher.MAX_TEXT_LENGTH + 50) + "!"
        assertTrue(matcher("(a+)+$").matches(pathological))
    }

    @Test
    fun `a match just inside the bound is found`() {
        val text = "x".repeat(TextMatcher.MAX_TEXT_LENGTH - " bandh".length) + " bandh"
        assertTrue(matcher("bandh").matches(text))
    }

    @Test
    fun `text beyond the bound is deliberately not scanned`() {
        // The documented cost of the bound: a match strictly past MAX_TEXT_LENGTH
        // is missed rather than risking an unbounded scan.
        val text = "x".repeat(TextMatcher.MAX_TEXT_LENGTH + 10) + " bandh"
        assertFalse(matcher("bandh").matches(text))
    }
}
