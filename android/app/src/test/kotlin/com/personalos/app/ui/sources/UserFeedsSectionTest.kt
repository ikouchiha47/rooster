package com.personalos.app.ui.sources

import com.personalos.app.core.sources.SourceKind
import com.personalos.app.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-a-feed form's local guards, pinned: a bad URL or a duplicate never
 * reaches the network, and the duplicate check ignores a trailing slash.
 */
class UserFeedsSectionTest {
    private fun rule(
        name: String,
        url: String,
        seeded: Boolean = false,
    ) = SourceEntity(
        id = "id-$name",
        name = name,
        kind = SourceKind.RSS.serialName,
        specJson = """{"url": "$url", "tags": ["news"]}""",
        seeded = seeded,
        enabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `a blank name is rejected before any fetch`() {
        assertEquals(
            "Name is blank — give the feed a name.",
            validateUserFeed("  ", "https://example.com/feed", emptyMap()),
        )
    }

    @Test
    fun `a non-http scheme is rejected`() {
        val reason = validateUserFeed("Feed", "ftp://example.com/feed", emptyMap())
        assertTrue(reason != null && reason.contains("http"))
    }

    @Test
    fun `a duplicate URL is caught regardless of a trailing slash`() {
        val existing = mapOf(normalizeFeedUrl("https://example.com/feed") to "Express")

        val reason = validateUserFeed("Another", "https://example.com/feed/", existing)

        assertTrue(reason != null && reason.contains("Express"))
    }

    @Test
    fun `a valid new feed passes`() {
        assertNull(validateUserFeed("Feed", "https://example.com/feed", emptyMap()))
    }

    @Test
    fun `the URL index maps normalised urls to names and skips bad specs`() {
        val index = rssUrlIndex(listOf(rule("Express", "https://example.com/feed/"), rule("Broken", "")))

        assertEquals("Express", index[normalizeFeedUrl("https://example.com/feed")])
        assertEquals(1, index.size)
    }

    @Test
    fun `interval reads the rule's own value and falls back to the shared schedule`() {
        assertEquals("every 1h", intervalLabel(rule("A", "https://x/f")))
        assertEquals("every 4h", intervalLabel(rule("B", "https://x/f").copy(intervalSec = 14400)))
        assertEquals("every 30m", intervalLabel(rule("C", "https://x/f").copy(intervalSec = 1800)))
    }
}
