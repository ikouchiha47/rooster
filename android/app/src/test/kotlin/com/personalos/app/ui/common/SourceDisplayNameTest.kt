package com.personalos.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Regression tests for the label that read "THEHINDU TOP": the three call sites
 * derived a source's display name from its id instead of using the `name` the
 * feed catalog already carries. See sourceDisplayName.
 */
class SourceDisplayNameTest {
    @Test
    fun `a known feed renders its catalog name, not its id`() {
        assertEquals("The Hindu", sourceDisplayName("rss:thehindu-top"))
        assertEquals("Indian Express", sourceDisplayName("rss:indianexpress"))
        assertEquals("The Hindu Kolkata", sourceDisplayName("rss:thehindu-kolkata"))
        assertEquals("AWS Status", sourceDisplayName("rss:status-aws"))
    }

    @Test
    fun `the id never leaks into the label`() {
        val label = sourceDisplayName("rss:thehindu-top")
        assertFalse("no slug hyphens", label.contains('-'))
        assertFalse("no section slug", label.contains("top", ignoreCase = true))
    }

    @Test
    fun `a source the catalog does not know still gets a readable label`() {
        assertEquals("some new feed", sourceDisplayName("rss:some-new-feed"))
    }

    @Test
    fun `a non-feed source keeps its existing label`() {
        // SMS rows store the bare source "sms"; the label is unchanged.
        assertEquals("sms", sourceDisplayName("sms"))
    }

    @Test
    fun `a search-source row is labelled by provenance, not its query`() {
        // gnews:kolkata used to render KOLKATA — a query slug leaking into the
        // source slot. The outlet rides in the headline ("... - NDTV"), so the
        // label says where the row came from.
        assertEquals("Google News", sourceDisplayName("gnews:kolkata"))
        assertEquals("Google News", sourceDisplayName("gnews:west-bengal"))
    }

    @Test
    fun `an explicitly named source still wins`() {
        val names = mapOf("gnews:west-bengal" to "West Bengal")
        assertEquals("West Bengal", sourceDisplayName("gnews:west-bengal", names))
    }
}
