package com.personalos.app.ui.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class ConditionSummaryTest {
    @Test
    fun `subject predicate`() {
        assertEquals("subject is finance", conditionSummary("""{"subject":"finance"}"""))
    }

    @Test
    fun `nature predicate`() {
        assertEquals("nature is incident", conditionSummary("""{"nature":"incident"}"""))
    }

    @Test
    fun `marker predicate`() {
        assertEquals("marker is news", conditionSummary("""{"marker":true}"""))
    }

    @Test
    fun `mention predicate`() {
        assertEquals("place is Kolkata", conditionSummary("""{"mention":{"kind":"place","value":"Kolkata"}}"""))
    }

    @Test
    fun `source predicate`() {
        assertEquals("source is rss:mint-markets", conditionSummary("""{"source":"rss:mint-markets"}"""))
    }

    @Test
    fun `field predicate with number`() {
        assertEquals("amount > 10000", conditionSummary("""{"field":{"name":"amount","op":"gt","value":10000}}"""))
    }

    @Test
    fun `field predicate with string`() {
        assertEquals("sender contains \"bank\"", conditionSummary("""{"field":{"name":"sender","op":"contains","value":"bank"}}"""))
    }

    @Test
    fun `text predicate`() {
        assertEquals("""text(any) ~ "bandh"""", conditionSummary("""{"text":{"pattern":"bandh","target":"any"}}"""))
    }

    @Test
    fun `all composition`() {
        assertEquals(
            "subject is finance and source is rss:mint-markets",
            conditionSummary("""{"all":[{"subject":"finance"},{"source":"rss:mint-markets"}]}"""),
        )
    }

    @Test
    fun `any composition`() {
        assertEquals(
            "subject is finance or nature is incident",
            conditionSummary("""{"any":[{"subject":"finance"},{"nature":"incident"}]}"""),
        )
    }

    @Test
    fun `nested composition`() {
        assertEquals(
            "subject is finance and nature is incident or nature is promo",
            conditionSummary(
                """{"all":[{"subject":"finance"},{"any":[{"nature":"incident"},{"nature":"promo"}]}]}""",
            ),
        )
    }

    @Test
    fun `series predicate shows unavailable`() {
        assertEquals(
            "series crossing (not previewable)",
            conditionSummary("""{"crossing":{"field":"amount","direction":"below","value":30000,"window":5}}"""),
        )
    }

    @Test
    fun `malformed json falls back to raw`() {
        assertEquals("not-json", conditionSummary("not-json"))
    }
}
