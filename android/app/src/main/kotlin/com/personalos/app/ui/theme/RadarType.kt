package com.personalos.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type ramp lifted from design/style.css.
 * Serif for display / headlines / event titles, sans for UI, monospace for time
 * gutters and tabular figures.
 * Sizes mirror the CSS custom properties 1:1.
 *
 * Families come from [RadarFonts], which serves the current theme's faces.
 * Every style is a getter over those families - not a snapshot taken at
 * startup - so a theme change recomposes every call site in place. Call sites
 * keep reading `RadarType.someStyle` exactly as before; the ramp's sizes,
 * weights and line heights are unchanged - a theme swap is a family swap, not
 * a re-scale.
 */
object RadarType {
    // --serif: headlines, section titles, event titles
    val serifH1
        get() =
            TextStyle(
                fontFamily = RadarFonts.serif,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 29.sp,
                letterSpacing = 0.2.sp,
            )
    val serifH3
        get() =
            TextStyle(
                fontFamily = RadarFonts.serif,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp,
            )
    val serifH4
        get() =
            TextStyle(
                fontFamily = RadarFonts.serif,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 17.sp,
            )
    val serifTitle
        get() =
            TextStyle(
                fontFamily = RadarFonts.serif,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 17.sp,
            )
    val serifSmall
        get() =
            TextStyle(
                fontFamily = RadarFonts.serif,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 15.sp,
            )

    // --sans body ramp
    val body get() = TextStyle(fontFamily = RadarFonts.sans, fontSize = 13.sp, lineHeight = 18.sp)
    val small get() = TextStyle(fontFamily = RadarFonts.sans, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny get() = TextStyle(fontFamily = RadarFonts.sans, fontSize = 11.sp, lineHeight = 14.sp)

    // letter-spaced caps labels (--fs-micro, tracking ~.12em)
    val micro
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
                lineHeight = 13.sp,
            )
    val microPlain
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 10.sp,
                letterSpacing = 0.3.sp,
                lineHeight = 13.sp,
            )
    val microBold
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                lineHeight = 13.sp,
            )

    // tabs / buttons (--fs-small weight 600)
    val label
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
    val labelMicro
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )

    // bottom nav labels (9px, tracking .1em)
    val navLabel
        get() =
            TextStyle(
                fontFamily = RadarFonts.sans,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
                lineHeight = 11.sp,
            )

    // monospace figures (time gutter, prices, counts)
    val mono get() = TextStyle(fontFamily = RadarFonts.mono, fontSize = 13.sp)
    val monoSmall
        get() =
            TextStyle(
                fontFamily = RadarFonts.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 13.sp,
            )
    val monoMicro get() = TextStyle(fontFamily = RadarFonts.mono, fontSize = 9.sp)
    val monoStat
        get() =
            TextStyle(
                fontFamily = RadarFonts.mono,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 19.sp,
            )
    val monoH2
        get() =
            TextStyle(
                fontFamily = RadarFonts.mono,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp,
            )
}
