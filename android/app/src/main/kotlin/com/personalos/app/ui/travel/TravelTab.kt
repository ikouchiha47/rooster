package com.personalos.app.ui.travel

/**
 * The Travel tab strip as data: a label and what it loads.
 *
 * Two tabs, two different reads — Calendar reads the calendar repository's
 * observances, News reads travel-tagged items. Modelling the mapping here means
 * the screen has no special case of its own to get wrong, and the distinction is
 * testable without rendering anything (same shape as `MessagesTab`).
 */
enum class TravelTab(
    val label: String,
) {
    CALENDAR("Calendar"),
    NEWS("News"),
}

/** The tab at [index], or [TravelTab.CALENDAR] when the index is out of range. */
fun travelTabAt(index: Int): TravelTab = TravelTab.entries.getOrElse(index) { TravelTab.CALENDAR }

/** Whether this tab draws the region observances. */
fun TravelTab.loadsObservances(): Boolean = this == TravelTab.CALENDAR

/** Whether this tab draws travel-tagged items. */
fun TravelTab.loadsTravelNews(): Boolean = this == TravelTab.NEWS
