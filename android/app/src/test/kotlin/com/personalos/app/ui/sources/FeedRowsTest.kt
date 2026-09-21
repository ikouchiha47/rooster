package com.personalos.app.ui.sources

import com.personalos.app.data.SourceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which feeds the shared feed section lists.
 *
 * It is rendered by two screens with different needs, and that difference is the
 * whole reason this is a parameter rather than a constant:
 *
 *  - **Sources** already shows the bundled feeds' health in its catalog status
 *    list, so repeating them in the manage list is one feed twice.
 *  - **RSS** is nothing *but* this section, so hiding the bundled feeds leaves
 *    the page blank — which is exactly the bug this pins.
 */
class FeedRowsTest {
    private fun source(
        id: String,
        kind: String = "rss",
        seeded: Boolean = false,
        name: String = id,
    ) = SourceEntity(
        id = id,
        name = name,
        kind = kind,
        specJson = """{"url":"https://example.com/$id"}""",
        seeded = seeded,
        enabled = true,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private val bundled = source("b-hindu", seeded = true, name = "The Hindu")
    private val bundled2 = source("b-mint", seeded = true, name = "Mint")
    private val mine = source("u-1", name = "Bangalore bytes")
    private val aSearch = source("s-1", kind = "search", name = "West Bengal")
    private val all = listOf(bundled, mine, aSearch, bundled2)

    @Test
    fun `sources shows only user feeds, because the bundled ones are already listed above it`() {
        assertEquals(listOf("u-1"), feedRows(all, includeSeeded = false).map { it.id })
    }

    @Test
    fun `rss shows every feed the device polls, so the page is never blank`() {
        assertEquals(listOf("u-1", "b-mint", "b-hindu"), feedRows(all, includeSeeded = true).map { it.id })
    }

    @Test
    fun `non-rss sources are never feed rows`() {
        assertEquals(
            "a Topics query is not a feed",
            emptyList<String>(),
            feedRows(listOf(aSearch), includeSeeded = true).map { it.id },
        )
    }

    @Test
    fun `user feeds come before bundled ones, each by name`() {
        assertEquals(
            // By name, not by id: "Mint" sorts before "The Hindu".
            listOf("u-1", "b-mint", "b-hindu"),
            feedRows(all, includeSeeded = true).map { it.id },
        )
    }
}
