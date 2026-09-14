package com.personalos.app.core.tile

/** Actions a tile can offer in its context bar (docs/ARCHITECTURE.md §8). */
enum class ActionKind(
    val label: String,
) {
    SEARCH("Search"),
    SYNC("Sync"),
    ADD_RULE("Rule"),
    ADD_FEED("Feed"),
    ADD_LOCATION("Place"),
    ADD_PAIR("Pair"),
    ADD_EVENT("Event"),
    ADD_TOPIC("Topic"),
    SCAN("Scan"),
    SETTINGS("Settings"),
}

/**
 * The shell contract. Every tile declares what its search means and which
 * actions its bar carries, so the shell renders search + action bar generically
 * and a tile's own config cannot drift from the aggregate Settings page.
 */
data class TileConfig(
    val id: String,
    val title: String,
    /** What search means inside this tile - it differs per tile. */
    val searchHint: String,
    val actions: List<ActionKind>,
)
