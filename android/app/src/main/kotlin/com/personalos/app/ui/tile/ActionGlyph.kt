package com.personalos.app.ui.tile

import com.personalos.app.core.tile.ActionKind
import com.personalos.app.ui.common.Glyph

/**
 * Icon per tile action, so a tile's context bar renders exactly like the Home
 * quick bar (icon over a small-caps label) instead of a row of bare words.
 */
fun ActionKind.glyph(): Glyph =
    when (this) {
        ActionKind.SEARCH -> Glyph.Search
        ActionKind.SYNC -> Glyph.Refresh
        ActionKind.ADD_RULE -> Glyph.Plus
        ActionKind.ADD_FEED -> Glyph.Rss
        ActionKind.ADD_LOCATION -> Glyph.Place
        ActionKind.ADD_PAIR -> Glyph.Banknote
        ActionKind.ADD_EVENT -> Glyph.Plane
        ActionKind.ADD_TOPIC -> Glyph.Tag
        ActionKind.SCAN -> Glyph.Scan
        ActionKind.SETTINGS -> Glyph.Tune
    }
