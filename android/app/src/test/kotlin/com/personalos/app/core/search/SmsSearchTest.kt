package com.personalos.app.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what the user typed into what the store can run.
 *
 * The rule this file pins: user input can never change the *meaning* of the
 * query. Every term is quoted, so a quote, a dash, an asterisk or a word like
 * `OR`/`NEAR` is text to find, not FTS5 syntax. A query that contains a word
 * shorter than three characters cannot be served by the trigram index at all,
 * so it becomes a literal LIKE pattern instead of a MATCH that would silently
 * return nothing.
 */
class SmsSearchTest {
    // ------------------------------------------------------------------ match

    @Test
    fun `words become quoted prefix terms, joined as an implicit AND`() {
        assertEquals(SmsSearch.Match("\"1214\"* \"airtel\"*"), smsSearch("1214 airtel"))
    }

    @Test
    fun `infix input stays one quoted term`() {
        // The whole point of trigram: `rtel` must find `airtel`, `654` must
        // find `9876543210`. The expression itself is just the term.
        assertEquals(SmsSearch.Match("\"rtel\"*"), smsSearch("rtel"))
        assertEquals(SmsSearch.Match("\"654\"*"), smsSearch("654"))
    }

    @Test
    fun `whitespace is collapsed and ends are trimmed`() {
        assertEquals(SmsSearch.Match("\"spaced\"* \"out\"*"), smsSearch("  spaced   out  "))
    }

    @Test
    fun `a quote in the input is escaped, so it cannot end the term`() {
        assertEquals(SmsSearch.Match("\"foo\"\"bar\"* \"baz\"*"), smsSearch("foo\"bar baz"))
    }

    @Test
    fun `dashes and asterisks are text inside the quotes, not operators`() {
        assertEquals(SmsSearch.Match("\"a-b\"* \"c-d\"*"), smsSearch("a-b c-d"))
        assertEquals(SmsSearch.Match("\"a*b\"*"), smsSearch("a*b"))
    }

    @Test
    fun `boolean-looking words are quoted, so they are searched not honoured`() {
        assertEquals(SmsSearch.Match("\"NEAR\"*"), smsSearch("NEAR"))
        assertEquals(SmsSearch.Match("\"AND\"*"), smsSearch("AND"))
    }

    // ------------------------------------------------------------------- blank

    @Test
    fun `empty and whitespace-only input is blank, not an empty match`() {
        assertEquals(SmsSearch.Blank, smsSearch(""))
        assertEquals(SmsSearch.Blank, smsSearch("   \t \n "))
    }

    // ------------------------------------------------------------- like fallback

    @Test
    fun `a query shorter than three characters falls back to LIKE`() {
        // Trigram yields nothing below three characters; a LIKE is the only way
        // this query can find anything at all.
        assertEquals(SmsSearch.Like("%ab%"), smsSearch("ab"))
        assertEquals(SmsSearch.Like("%a%"), smsSearch("a"))
    }

    @Test
    fun `two characters is the boundary - three goes back to MATCH`() {
        assertEquals(SmsSearch.Like("%ab%"), smsSearch("ab"))
        assertEquals(SmsSearch.Match("\"abc\"*"), smsSearch("abc"))
    }

    @Test
    fun `a short word forces the whole query down the LIKE path`() {
        // `ab` cannot be matched by trigram, and a MATCH of `"ab"* "cd"*` would
        // silently return nothing for the whole search - so neither term is
        // allowed to use MATCH.
        assertEquals(SmsSearch.Like("%ab cd%"), smsSearch("ab cd"))
    }

    @Test
    fun `LIKE wildcards in the input are escaped, not left as wildcards`() {
        assertEquals(SmsSearch.Like("%a\\%%"), smsSearch("a%"))
        assertEquals(SmsSearch.Like("%\\_%"), smsSearch("_"))
        assertEquals(SmsSearch.Like("%\\\\%"), smsSearch("\\"))
    }

    @Test
    fun `the LIKE pattern is always anchored with wildcards of its own`() {
        val plan = smsSearch("ab")
        assertTrue(plan is SmsSearch.Like)
        assertTrue((plan as SmsSearch.Like).pattern.startsWith("%"))
        assertTrue(plan.pattern.endsWith("%"))
    }
}
