package com.personalos.app.core.theme

/**
 * The app's font pairing.
 *
 * Exactly two themes exist. `ibm_plex` is the default: Plex Serif heads, Plex
 * Sans body, Plex Mono figures. `space_grotesk` sets Space Grotesk for both
 * the serif and sans roles (it is a sans used everywhere - that is the point
 * of the theme, it has no serif) with Space Mono for figures.
 *
 * This lives in `core` rather than `ui/theme` so the prefs-backed store can
 * map the stored id without the data layer importing anything from `ui/`.
 */
enum class RadarTheme(
    val id: String,
    val title: String,
    val blurb: String,
) {
    IBM_PLEX(
        id = "ibm_plex",
        title = "IBM Plex",
        blurb = "Plex Serif heads, Plex Sans body, Plex Mono figures",
    ),
    SPACE_GROTESK(
        id = "space_grotesk",
        title = "Space Grotesk",
        blurb = "Space Grotesk heads and body, Space Mono figures",
    ),
    ;

    companion object {
        val Default: RadarTheme = IBM_PLEX

        /**
         * Every input resolves: a missing or unknown id falls back to the
         * default rather than throwing, so a stale stored value can never
         * break startup.
         */
        fun fromId(id: String?): RadarTheme = entries.firstOrNull { it.id == id } ?: Default
    }
}
