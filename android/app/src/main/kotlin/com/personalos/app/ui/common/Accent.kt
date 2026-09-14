package com.personalos.app.ui.common

import androidx.compose.ui.graphics.Color
import com.personalos.app.core.model.Accent
import com.personalos.app.ui.theme.CategoryColors

/** Maps a domain [Accent] to its palette colour. Keeps core free of Compose types. */
fun Accent.color(): Color =
    when (this) {
        Accent.VERMILION -> CategoryColors.Vermilion
        Accent.INDIGO -> CategoryColors.Indigo
        Accent.MUSTARD -> CategoryColors.Mustard
        Accent.TEAL -> CategoryColors.Teal
        Accent.PERIWINKLE -> CategoryColors.Periwinkle
        Accent.PLUM -> CategoryColors.Plum
        Accent.CYAN -> CategoryColors.Cyan
        Accent.RUST -> CategoryColors.Rust
        Accent.CHARTREUSE -> CategoryColors.Chartreuse
        Accent.INK -> RadarColors.ink
    }
