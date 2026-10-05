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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.core.AppContainer
import com.personalos.app.core.Chars
import com.personalos.app.core.theme.RadarTheme
import com.personalos.app.data.KnownModels
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarFonts
import com.personalos.app.ui.theme.RadarType
import com.personalos.mlsdk.ModelSpec
import com.personalos.mlsdk.ModelStatus
import kotlinx.coroutines.launch

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

            Spacer(Modifier.height(12.dp))
            ModelsSection(
                title = "Models",
                blurb =
                    "The embedding model tags messages by meaning — in any language, and " +
                        "without a keyword list. Downloaded once, shared with your other apps.",
                footer = "Models are shared on this device",
                bundle = KnownModels.embeddingGemmaBundle,
                onDownloaded = { it.upgradeTagger() },
            )

            Spacer(Modifier.height(12.dp))
            ModelsSection(
                title = "Vocabulary",
                blurb =
                    "The tag vocabulary as data — descriptions and terms. Update it " +
                        "without an app release.",
                footer = "Vocabulary is shared on this device",
                bundle = KnownModels.vocabularyBundle,
                onDownloaded = { it.reloadVocabulary() },
            )
        }
    }
}

/**
 * The model manager: what the ML tagger needs, its state, and a way to fetch it.
 *
 * Runs in the background through the SDK; progress shows on the global
 * notification host, so leaving this screen is fine. After a successful download
 * the active tagger is upgraded in place — no restart.
 */
@Composable
private fun ModelsSection(
    title: String,
    blurb: String,
    footer: String,
    bundle: List<ModelSpec>,
    onDownloaded: suspend (AppContainer) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var statuses by remember { mutableStateOf<Map<String, ModelStatus>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        statuses = bundle.associate { it.fileName to container.models.status(it) }
    }
    LaunchedEffect(Unit) { refresh() }

    WidgetHeader(title = title, note = "${bundle.size} files")
    Text(
        text = blurb,
        style = RadarType.microPlain,
        color = RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    bundle.forEach { spec ->
        ModelRow(
            name = spec.fileName,
            size = modelSizeLabel(spec.sizeBytes),
            status = modelStatusLabel(statuses[spec.fileName] ?: ModelStatus.ABSENT),
        )
    }
    if (bundle.any { needsDownload(statuses[it.fileName] ?: ModelStatus.ABSENT) }) {
        Text(
            text = if (busy) "DOWNLOADING" else "DOWNLOAD",
            style = RadarType.labelMicro,
            color = if (busy) RadarColors.ink3 else CategoryColors.Teal,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        android.util.Log.i("ModelsSection", "download tapped: ${bundle.map { it.fileName }}")
                        scope.launch {
                            busy = true
                            val ok = container.modelDownloader.download(bundle)
                            android.util.Log.i("ModelsSection", "download finished: ok=$ok")
                            if (ok) onDownloaded(container)
                            refresh()
                            busy = false
                        }
                    }.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
    FooterStrip(footer)
}

@Composable
private fun ModelRow(
    name: String,
    size: String,
    status: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = name, style = RadarType.serifTitle, color = RadarColors.ink, maxLines = 1)
                Text(text = "$size ${Chars.MIDDLE_DOT} $status", style = RadarType.microPlain, color = RadarColors.ink3, maxLines = 1)
            }
        }
        SoftRule()
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
