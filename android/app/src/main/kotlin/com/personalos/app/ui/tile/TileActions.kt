package com.personalos.app.ui.tile

import com.personalos.app.core.tile.ActionKind
import com.personalos.app.ui.navigation.Destination

/**
 * Where a tile's context action goes. `SYNC` is deliberately absent: it runs a
 * sync rather than navigating, so a screen handles it before asking this map.
 *
 * A map, not a `when`: adding an action that opens a screen is a data edit, and
 * an action with no destination stays a no-op instead of growing a new branch in
 * every tile.
 */
private val ACTION_DESTINATIONS: Map<ActionKind, Destination> =
    mapOf(
        ActionKind.ADD_FEED to Destination.Rss,
        ActionKind.ADD_RULE to Destination.Rules,
        ActionKind.ADD_LOCATION to Destination.Settings,
        ActionKind.ADD_PAIR to Destination.Settings,
        ActionKind.ADD_EVENT to Destination.Calendar,
        ActionKind.ADD_TOPIC to Destination.Settings,
        ActionKind.SETTINGS to Destination.Settings,
    )

fun destinationForAction(action: ActionKind): Destination? = ACTION_DESTINATIONS[action]
