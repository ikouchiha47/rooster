package com.personalos.app.ui.sources

import com.personalos.app.core.feed.FeedStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedListModelTest {
    private fun status(
        error: String? = null,
        tags: Set<String> = emptySet(),
    ) = FeedStatus(
        id = "x",
        name = "Example",
        url = "https://example.com/feed.xml",
        lastError = error,
        tags = tags,
    )

    @Test
    fun `an error takes the subtitle`() {
        assertEquals("example.com · timed out", feedSubtitle(status(error = "timed out", tags = setOf("tech"))))
    }

    @Test
    fun `otherwise it is host and category`() {
        assertEquals("example.com · tech", feedSubtitle(status(tags = setOf("tech"))))
    }

    @Test
    fun `with no category it is just the host`() {
        assertEquals("example.com", feedSubtitle(status()))
    }
}
