package com.personalos.app.core.place

import com.personalos.app.data.Event

/** A labelled group of items, nearest-first. */
data class PlaceTier(
    val label: String,
    val events: List<Event>,
)

/**
 * Groups and orders items by place.
 *
 * This is a **seam**, not an implementation. Real place ranking needs a bundled
 * gazetteer, home places and a radius, none of which exist yet
 * (docs/ARCHITECTURE.md §9). Swapping the implementation changes one binding in
 * `AppContainer` - the same pattern as [com.personalos.app.core.tag.Tagger].
 */
interface PlaceRanker {
    val id: String

    fun tiers(events: List<Event>): List<PlaceTier>
}

/**
 * Rung 0: no place data, so everything is one tier in the order given.
 *
 * Deliberately honest - it does not claim a ranking it cannot compute. The
 * hierarchy it stands in for is `city/town -> state -> surrounding states ->
 * country` with national events pinned above (§4.2).
 */
class RecencyPlaceRanker : PlaceRanker {
    override val id: String = "recency-v1"

    override fun tiers(events: List<Event>): List<PlaceTier> =
        if (events.isEmpty()) {
            emptyList()
        } else {
            listOf(PlaceTier(label = "Latest", events = events))
        }
}
