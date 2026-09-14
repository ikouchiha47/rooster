package com.personalos.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// PersonalOS color palette from design/style.css
// ColorScheme constructor order (35 params):
// primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
// secondary, onSecondary, secondaryContainer, onSecondaryContainer,
// tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
// background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant,
// surfaceTint, inverseSurface, inverseOnSurface,
// error, onError, errorContainer, onErrorContainer,
// outline, outlineVariant, scrim,
// surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow, surfaceContainerLowest

val PersonalOSColors =
    ColorScheme(
        primary = Color(0xFF171513),
        onPrimary = Color(0xFFFAF8F2),
        primaryContainer = Color(0xFFEFEADB),
        onPrimaryContainer = Color(0xFF171513),
        inversePrimary = Color(0xFFEFEADB),
        secondary = Color(0xFF4A443C),
        onSecondary = Color(0xFFFAF8F2),
        secondaryContainer = Color(0xFFEFEADB),
        onSecondaryContainer = Color(0xFF171513),
        tertiary = Color(0xFFD93B2B),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFFCF3),
        onTertiaryContainer = Color(0xFF171513),
        background = Color(0xFFFAF8F2),
        onBackground = Color(0xFF171513),
        surface = Color(0xFFFAF8F2),
        onSurface = Color(0xFF171513),
        surfaceVariant = Color(0xFFEFEADB),
        onSurfaceVariant = Color(0xFF4A443C),
        surfaceTint = Color(0xFF171513),
        inverseSurface = Color(0xFF171513),
        inverseOnSurface = Color(0xFFFAF8F2),
        error = Color(0xFFD93B2B),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFFCF3),
        onErrorContainer = Color(0xFF171513),
        outline = Color(0xFFCFC6B3),
        outlineVariant = Color(0xFFB8AE99),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFAF8F2),
        surfaceDim = Color(0xFFEFEADB),
        surfaceContainer = Color(0xFFE4DCC7),
        surfaceContainerHigh = Color(0xFFD9CFBE),
        surfaceContainerHighest = Color(0xFFCEC5B3),
        surfaceContainerLow = Color(0xFFF0EBE3),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    )

// Accent colors for categories
object CategoryColors {
    val Vermilion = Color(0xFFD93B2B)
    val Indigo = Color(0xFF2B4C8C)
    val Mustard = Color(0xFFE0A800)
    val Teal = Color(0xFF1F8A70)
    val Chartreuse = Color(0xFFA8C020)
    val Periwinkle = Color(0xFF6C7BD9)
    val Plum = Color(0xFF7A2E5E)
    val Cyan = Color(0xFF1B7A8C)
    val Rust = Color(0xFFB4532A)
}

// Shape - minimal corner radius (2-4dp), no pill shapes
val PersonalOSShapes =
    Shapes(
        extraSmall =
            androidx.compose.foundation.shape
                .RoundedCornerShape(2.dp),
        small =
            androidx.compose.foundation.shape
                .RoundedCornerShape(2.dp),
        medium =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        large =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        extraLarge =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
    )

@Composable
fun PersonalOSTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PersonalOSColors,
        shapes = PersonalOSShapes,
        content = content,
    )
}
