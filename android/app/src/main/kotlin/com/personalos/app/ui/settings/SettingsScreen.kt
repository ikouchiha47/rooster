package com.personalos.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.core.theme.RadarTheme
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.RadarFonts
import com.personalos.app.ui.theme.RadarType

/**
 * Settings: the edit surface for the app's stored choices.
 *
 * Today that is the theme only. A tap writes through the theme repository
 * (the single store) and the runtime theme state together, so the choice
 * persists across restarts and applies to every screen in place. Rows render
 * in the ramp's own styles, so this section itself previews the active
 * pairing - there is no per-screen font logic here.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    // Reads the single theme state, so this screen recomposes on every change.
    val current = RadarFonts.theme

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(
            title = "Settings",
            accent = RadarColors.ink,
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            WidgetHeader(
                title = "Theme",
                note = "${RadarTheme.entries.size} options",
            )
            RadarTheme.entries.forEach { theme ->
                ThemeRow(
                    theme = theme,
                    selected = theme == current,
                    onSelect = {
                        container.themeRepository.save(theme)
                        RadarFonts.applyTheme(theme)
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            FooterStrip("Theme is stored on this device")
        }
    }
}

@Composable
private fun ThemeRow(
    theme: RadarTheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) RadarColors.paper3 else RadarColors.paper2)
                    .clickable(onClickLabel = "Use ${theme.title}", onClick = onSelect)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = theme.title,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                )
                Text(
                    text = theme.blurb,
                    style = RadarType.microPlain,
                    color = RadarColors.ink2,
                    maxLines = 2,
                )
                if (selected) {
                    Text(
                        text = "IN USE",
                        style = RadarType.micro,
                        color = RadarColors.ink3,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Selected marker: a filled ink square, not a Material switch.
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (selected) RadarColors.ink else Color.Transparent)
                    .border(1.dp, RadarColors.ink),
            )
        }
        SoftRule()
    }
}
