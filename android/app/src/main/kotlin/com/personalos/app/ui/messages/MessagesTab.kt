package com.personalos.app.ui.messages

import com.personalos.app.core.category.CategoryTaxonomy

/**
 * The Messages tab strip as data: one tab per **category**, plus Alerts.
 *
 * A **category tab** carries its taxonomy `id` as [categoryId], so the strip is
 * built from the stored taxonomy — adding a category is a row in
 * `categorization.v1.json`, not code. There is no enum and no `when` over tab
 * ids: [messageTabs] is the one place the list is assembled, and a new category
 * appears by being read, not by being added here.
 *
 * **Alerts** is the one tab that is not a classification: it is a view over
 * user-authored rules, so [categoryId] is null. It is still just another entry
 * in the same list, so the strip never special-cases it beyond that.
 */
data class MessageTab(
    val id: String,
    val label: String,
    /** The taxonomy category this tab filters by, or null for Alerts. */
    val categoryId: String? = id,
)

/** The one non-category tab. Reserved, so it can never collide with a category id. */
const val ALERTS_TAB_ID = "alerts"

/**
 * The strip, in display order: every category by `sort` (id breaks a tie), then
 * Alerts last.
 *
 * A null [taxonomy] yields just Alerts, so the screen always has a valid strip
 * while the store is still loading — and [defaultMessageTabId] can never crash
 * on an empty taxonomy.
 */
fun messageTabs(taxonomy: CategoryTaxonomy?): List<MessageTab> {
    val categoryTabs =
        taxonomy
            ?.categories
            .orEmpty()
            .sortedWith(compareBy({ it.sort }, { it.id }))
            .map { MessageTab(id = it.id, label = it.label, categoryId = it.id) }
    return categoryTabs + MessageTab(id = ALERTS_TAB_ID, label = "Alerts", categoryId = null)
}

/**
 * The tab to show when the user has not chosen one: the first category, or
 * Alerts when the taxonomy carries none. Not an index — an id — so it survives
 * the strip growing.
 */
fun defaultMessageTabId(taxonomy: CategoryTaxonomy?): String = messageTabs(taxonomy).first().id

/** The index of [id] in [tabs], or 0 when it is not there (never throws). */
fun messageTabIndex(
    tabs: List<MessageTab>,
    id: String,
): Int = tabs.indexOfFirst { it.id == id }.coerceAtLeast(0)

/**
 * A tab's badge: the conversation count for its category, or the real matched
 * **event** count for Alerts. Both units are "rows the tab lists" — a category
 * tab lists conversations, Alerts lists matched events (a rule matching three
 * events is three rows) — so the badge never overcounts what the list shows.
 *
 * [alertCount] is passed in rather than read here, so this stays a pure map from
 * state to a badge and the store stays the one owner of the number.
 */
fun messageTabCount(
    tab: MessageTab,
    countsByCategory: Map<String, Int>,
    alertCount: Int,
): Int = tab.categoryId?.let { countsByCategory[it] ?: 0 } ?: alertCount
