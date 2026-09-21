package com.personalos.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The single owner of the calendar fact (docs/CODE-DESIGN-GUIDELINES.md §1).
 *
 * Two things live here and nowhere else: **which regions are followed**, and the
 * observances for exactly those regions. A followed region is an enabled
 * `sources` row of kind `calendar` (the row's `name` is the normalised slug, so
 * the same writer Settings uses is the only region list — no second copy).
 *
 * The window (`now` → +12 months) is the repository's contract, not a display
 * cap: it returns everything a year holds. The screen decides how many months
 * to draw (see `CALENDAR_MONTHS_SHOWN`).
 */
class CalendarRepository(
    private val sources: SourceDao,
    private val calendar: CalendarDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Followed regions, normalised slug, stable order, no duplicates. */
    fun followedRegions(): Flow<List<String>> =
        sources.observeAll().map { rows ->
            rows
                .asSequence()
                .filter { it.kind == KIND && it.enabled }
                .map { it.name }
                .distinct()
                .sorted()
                .toList()
        }

    /**
     * Upcoming observances for the followed regions, soonest first.
     *
     * Re-subscribes when the followed set changes, so enabling a region makes its
     * dates appear without any screen re-querying. No regions is an empty list,
     * not an error.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun upcoming(): Flow<List<CalendarDateEntity>> =
        followedRegions().flatMapLatest { regions ->
            if (regions.isEmpty()) {
                flowOf(emptyList())
            } else {
                val from = now()
                calendar.observeUpcoming(regions, from, from + WINDOW_MS)
            }
        }

    private companion object {
        /** The `sources.kind` a followed region is stored as. */
        const val KIND = "calendar"

        /**
         * Twelve months. A year of observances is the useful horizon; anything
         * beyond it is a different feature (plans), not a longer list.
         */
        const val WINDOW_MS = 365L * 24 * 60 * 60 * 1000
    }
}
