package com.personalos.app.ui.travel

/**
 * The Travel tab strip as data: a label and what it loads.
 *
 * Three tabs, three different reads — Holidays reads the calendar repository's
 * observances, News reads travel-tagged items, Alerts reads the rule matches
 * scoped to those same travel facts. Modelling the mapping here means the screen
 * has no special case of its own to get wrong, and the distinction is testable
 * without rendering anything (same shape as `MessageTab`).
 */
enum class TravelTab(
    val label: String,
) {
    CALENDAR("Holidays"),
    NEWS("News"),
    ALERTS("Alerts"),
}

/** The tab at [index], or [TravelTab.CALENDAR] when the index is out of range. */
fun travelTabAt(index: Int): TravelTab = TravelTab.entries.getOrElse(index) { TravelTab.CALENDAR }

/** Whether this tab draws the region observances. */
fun TravelTab.loadsObservances(): Boolean = this == TravelTab.CALENDAR

/** Whether this tab draws travel-tagged items. */
fun TravelTab.loadsTravelNews(): Boolean = this == TravelTab.NEWS

/** Whether this tab draws the travel-scoped rule matches. */
fun TravelTab.loadsAlerts(): Boolean = this == TravelTab.ALERTS
