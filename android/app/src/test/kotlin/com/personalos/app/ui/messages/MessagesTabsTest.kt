package com.personalos.app.ui.messages

import com.personalos.app.core.category.Category
import com.personalos.app.core.category.CategoryTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Messages tab strip as data. The point of the model: the strip is built
 * from the taxonomy — ordered by `sort`, Alerts last — so adding a category is a
 * DB row, never a code change. Nothing here is a `when` over ids.
 */
class MessagesTabsTest {
    private fun taxonomy(vararg categories: Category): CategoryTaxonomy = CategoryTaxonomy(id = "categorization", version = 1, categories = categories.toList())

    private val sample =
        taxonomy(
            Category(id = "promotions", label = "Promotions", sort = 30),
            Category(id = "personal", label = "Personal", sort = 10),
            Category(id = "transactional", label = "Transactional", sort = 20),
        )

    @Test
    fun `tabs are the categories by sort, then Alerts last`() {
        assertEquals(
            listOf("personal", "transactional", "promotions", "alerts"),
            messageTabs(sample).map { it.id },
        )
    }

    @Test
    fun `labels come from the taxonomy, not from code`() {
        assertEquals(
            listOf("Personal", "Transactional", "Promotions", "Alerts"),
            messageTabs(sample).map { it.label },
        )
    }

    @Test
    fun `a sort tie is broken by id, deterministically`() {
        val tied =
            taxonomy(
                Category(id = "b", label = "Bee", sort = 5),
                Category(id = "a", label = "Ay", sort = 5),
            )
        assertEquals(listOf("a", "b", "alerts"), messageTabs(tied).map { it.id })
    }

    @Test
    fun `alerts is the one tab with no category`() {
        val tabs = messageTabs(sample)
        val alerts = tabs.single { it.id == ALERTS_TAB_ID }
        assertNull(alerts.categoryId)
        assertEquals(
            "only Alerts is not a category",
            listOf(ALERTS_TAB_ID),
            tabs.filter { it.categoryId == null }.map { it.id },
        )
    }

    @Test
    fun `the default tab is the first category`() {
        assertEquals("personal", defaultMessageTabId(sample))
    }

    @Test
    fun `an empty taxonomy yields just Alerts, and a safe default`() {
        val tabs = messageTabs(taxonomy())
        assertEquals(listOf(ALERTS_TAB_ID), tabs.map { it.id })
        assertEquals(ALERTS_TAB_ID, defaultMessageTabId(null))
        assertEquals(ALERTS_TAB_ID, defaultMessageTabId(taxonomy()))
    }

    @Test
    fun `the index of a tab falls back to the first, never crashes`() {
        val tabs = messageTabs(sample)
        assertEquals(1, messageTabIndex(tabs, "transactional"))
        assertEquals(3, messageTabIndex(tabs, ALERTS_TAB_ID))
        assertEquals(0, messageTabIndex(tabs, "gone"))
    }

    @Test
    fun `a tab's badge is its category's count, and Alerts shows the real match count`() {
        val tabs = messageTabs(sample)
        val counts = mapOf("personal" to 12, "promotions" to 3)
        assertEquals(12, messageTabCount(tabs.first { it.id == "personal" }, counts, alertCount = 5))
        assertEquals(0, messageTabCount(tabs.first { it.id == "transactional" }, counts, alertCount = 5))
        assertEquals(
            "Alerts counts matched events, not a hard-coded 0",
            5,
            messageTabCount(tabs.last(), counts, alertCount = 5),
        )
    }

    @Test
    fun `Alerts shows zero when nothing has matched`() {
        val tabs = messageTabs(sample)
        assertEquals(0, messageTabCount(tabs.last(), emptyMap(), alertCount = 0))
    }

    @Test
    fun `a category added as data appears without touching the model`() {
        val grown =
            taxonomy(
                Category(id = "personal", label = "Personal", sort = 10),
                Category(id = "travel", label = "Travel", sort = 15),
            )
        assertTrue(messageTabs(grown).any { it.id == "travel" && it.label == "Travel" })
        assertEquals(listOf("personal", "travel", "alerts"), messageTabs(grown).map { it.id })
    }
}
