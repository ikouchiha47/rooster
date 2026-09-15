package com.personalos.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.personalos.app.core.theme.RadarTheme

/**
 * The app's bundled font families, for the current [RadarTheme].
 *
 * The app is offline-first, so fonts ship as resources in `res/font` rather than
 * being fetched at runtime through the downloadable-fonts provider (which needs
 * Play Services and a network round-trip). [load] is called once from
 * [com.personalos.app.PersonalOSApplication], before any screen composes, with
 * the stored theme so the first frame already uses the right pairing.
 *
 * **One owner, live swap.** [theme] is the single SnapshotState behind every
 * style in [RadarType]: the families below are getters over it, and the ramp's
 * styles are getters over the families, so writing the theme recomposes every
 * screen in place. No restart, no per-screen font logic - screens keep reading
 * `RadarType.someStyle` exactly as before.
 *
 * **Variable files use [FontVariation]; static files map per weight.** The two
 * variable faces (`ibm_plex_sans`, `space_grotesk`) carry the whole weight axis
 * in one file, so each weight in the ramp is produced from the single file via
 * [FontVariation] - without it the family would render at the file's default
 * weight and bold text would be synthesised rather than real. The Serif and
 * Mono faces ship as static regular/bold cuts, so those families map the
 * ramp's weights onto the nearest real file instead.
 *
 * Faces are resolved by resource *name*, not `R.font.*`, so a role whose files
 * are absent falls back to the platform family instead of failing the build.
 */
@OptIn(ExperimentalTextApi::class)
object RadarFonts {
    /**
     * The current theme. The one owner of this fact: Settings writes it (plus
     * the prefs store), the type ramp reads it, nothing else holds a copy.
     */
    var theme: RadarTheme by mutableStateOf(RadarTheme.Default)
        private set

    fun applyTheme(theme: RadarTheme) {
        this.theme = theme
    }

    /** Serif role: Plex Serif by default, Space Grotesk under the space theme. */
    val serif: FontFamily
        get() =
            when (theme) {
                RadarTheme.IBM_PLEX -> ibmSerif
                RadarTheme.SPACE_GROTESK -> spaceGrotesk
            }

    /** Sans role: Plex Sans by default, Space Grotesk under the space theme. */
    val sans: FontFamily
        get() =
            when (theme) {
                RadarTheme.IBM_PLEX -> ibmSans
                RadarTheme.SPACE_GROTESK -> spaceGrotesk
            }

    /** Mono role: Plex Mono by default, Space Mono under the space theme. */
    val mono: FontFamily
        get() =
            when (theme) {
                RadarTheme.IBM_PLEX -> ibmMono
                RadarTheme.SPACE_GROTESK -> spaceMono
            }

    private var ibmSerif: FontFamily = FontFamily.Serif
    private var ibmSans: FontFamily = FontFamily.SansSerif
    private var ibmMono: FontFamily = FontFamily.Monospace
    private var spaceGrotesk: FontFamily = FontFamily.SansSerif
    private var spaceMono: FontFamily = FontFamily.Monospace

    private var loaded = false

    fun load(
        context: Context,
        initialTheme: RadarTheme = RadarTheme.Default,
    ) {
        if (!loaded) {
            loaded = true
            ibmSerif =
                staticSerifFamily(
                    context,
                    regularName = "ibm_plex_serif",
                    boldName = "ibm_plex_serif_bold",
                    italicName = "ibm_plex_serif_italic",
                ) ?: ibmSerif
            ibmSans = variableFamily(context, "ibm_plex_sans") ?: ibmSans
            ibmMono =
                staticMonoFamily(
                    context,
                    regularName = "ibm_plex_mono",
                    boldName = "ibm_plex_mono_bold",
                ) ?: ibmMono
            // One variable file serves both the serif and sans roles: the
            // space theme is a sans used everywhere, by design.
            spaceGrotesk = variableFamily(context, "space_grotesk") ?: spaceGrotesk
            spaceMono =
                staticMonoFamily(
                    context,
                    regularName = "space_mono",
                    boldName = "space_mono_bold",
                ) ?: spaceMono
        }
        theme = initialTheme
    }

    /**
     * One variable file, expanded into the weights the type ramp actually uses.
     *
     * Returns null when the resource is absent, so the caller keeps the platform
     * family rather than shipping a half-built one.
     */
    private fun variableFamily(
        context: Context,
        resourceName: String,
    ): FontFamily? {
        val id = fontId(context, resourceName)
        if (id == 0) return null

        return FontFamily(
            WEIGHTS.map { weight ->
                Font(
                    resId = id,
                    weight = weight,
                    // Select the instance from the `wght` axis; without this the
                    // whole family renders at the file's default weight and bold
                    // text would be synthesised rather than real.
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
                )
            },
        )
    }

    /**
     * Static serif cuts: regular covers Normal/Medium, bold covers
     * SemiBold/Bold, plus a true italic - so headline bold and body italic are
     * real files, not synthesised.
     */
    private fun staticSerifFamily(
        context: Context,
        regularName: String,
        boldName: String,
        italicName: String,
    ): FontFamily? {
        val regularId = fontId(context, regularName)
        if (regularId == 0) return null

        val entries =
            mutableListOf(
                Font(resId = regularId, weight = FontWeight.Normal),
                Font(resId = regularId, weight = FontWeight.Medium),
            )
        val boldId = fontId(context, boldName)
        if (boldId != 0) {
            entries += Font(resId = boldId, weight = FontWeight.SemiBold)
            entries += Font(resId = boldId, weight = FontWeight.Bold)
        }
        val italicId = fontId(context, italicName)
        if (italicId != 0) {
            entries += Font(resId = italicId, weight = FontWeight.Normal, style = FontStyle.Italic)
        }
        return FontFamily(entries)
    }

    /**
     * Static mono cuts: regular covers Normal/Medium, bold covers
     * SemiBold/Bold, so tabular figures stay real at every ramp weight.
     */
    private fun staticMonoFamily(
        context: Context,
        regularName: String,
        boldName: String,
    ): FontFamily? {
        val regularId = fontId(context, regularName)
        if (regularId == 0) return null

        val entries =
            mutableListOf(
                Font(resId = regularId, weight = FontWeight.Normal),
                Font(resId = regularId, weight = FontWeight.Medium),
            )
        val boldId = fontId(context, boldName)
        if (boldId != 0) {
            entries += Font(resId = boldId, weight = FontWeight.SemiBold)
            entries += Font(resId = boldId, weight = FontWeight.Bold)
        }
        return FontFamily(entries)
    }

    private fun fontId(
        context: Context,
        resourceName: String,
    ): Int = context.resources.getIdentifier(resourceName, "font", context.packageName)

    /** Mirrors the weights used by [RadarType] - nothing wider than the ramp needs. */
    private val WEIGHTS =
        listOf(
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
        )
}
