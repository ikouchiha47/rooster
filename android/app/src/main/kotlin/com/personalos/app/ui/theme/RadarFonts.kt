package com.personalos.app.ui.theme

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * The app's bundled font families.
 *
 * The app is offline-first, so fonts ship as resources in `res/font` rather than
 * being fetched at runtime through the downloadable-fonts provider (which needs
 * Play Services and a network round-trip). [load] is called once from
 * [com.personalos.app.PersonalOSApplication], before any screen composes.
 *
 * **Two faces, not fifteen.** The upstream families are *variable* fonts: one file
 * carries the whole weight axis. `google/fonts` publishes no static cuts for these
 * families, and the variable files are 1.9 MB and 2.0 MB, so shipping one file per
 * weight would have been both unavailable and far larger. Each weight in the ramp
 * is therefore produced from the single file via [FontVariation], which is why this
 * is one resource mapped to several `Font` entries rather than several resources.
 *
 * `mono` is **not** bundled - it stays on the platform monospace. Bundling
 * Devanagari/Bengali faces was also skipped: they added ~2.8 MB, and those scripts
 * already resolve through Android's Noto fallback chain. See the font decision in
 * `docs/BACKLOG.md`.
 *
 * Faces are resolved by resource *name*, not `R.font.*`, so a role whose files are
 * absent falls back to the platform family instead of failing the build.
 */
@OptIn(ExperimentalTextApi::class)
object RadarFonts {
    var serif: FontFamily = FontFamily.Serif
        private set

    var sans: FontFamily = FontFamily.SansSerif
        private set

    /** Deliberately the platform monospace: not bundled (see the class doc). */
    var mono: FontFamily = FontFamily.Monospace
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        serif = variableFamily(context, "noto_serif_variable") ?: serif
        sans = variableFamily(context, "noto_sans_variable") ?: sans
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
        val id = context.resources.getIdentifier(resourceName, "font", context.packageName)
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

    /** Mirrors the weights used by [RadarType] - nothing wider than the ramp needs. */
    private val WEIGHTS =
        listOf(
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
        )
}
