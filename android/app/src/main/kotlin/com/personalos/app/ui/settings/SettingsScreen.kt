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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personalos.app.core.AppContainer
import com.personalos.app.core.Chars
import com.personalos.app.core.theme.RadarTheme
import com.personalos.app.data.BatteryOptimization
import com.personalos.app.data.KnownArtifacts
import com.personalos.app.ui.common.FooterStrip
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarFonts
import com.personalos.app.ui.theme.RadarType
import com.personalos.mlsdk.Artifact
import com.personalos.mlsdk.ArtifactStatus
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
                models = listOf(KnownArtifacts.embeddingGemma),
                onDownloaded = { it.upgradeTagger() },
            )

            Spacer(Modifier.height(12.dp))
            ModelsSection(
                title = "Vocabulary",
                blurb =
                    "The tag vocabulary as data — descriptions and terms. Update it " +
                        "without an app release.",
                footer = "Vocabulary is shared on this device",
                models = listOf(KnownArtifacts.vocabulary),
                onDownloaded = { it.reloadVocabulary() },
            )

            Spacer(Modifier.height(12.dp))
            BatterySection()

            Spacer(Modifier.height(12.dp))
            EnrichmentSection()
        }
    }
}

/**
 * Background reliability. Without the exemption, Android defers a long retag into
 * never (Doze/App Standby); granting it is a one-time system dialog, no permanent
 * notification.
 */
@Composable
private fun BatterySection() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }

    WidgetHeader(title = "Background", note = if (exempt) "exempt" else "best-effort")
    Text(
        text =
            if (exempt) {
                "Exempt from battery optimization — background tagging runs reliably."
            } else {
                "Allow this app to run in the background, so re-tagging is not deferred " +
                    "or cut short."
            },
        style = RadarType.microPlain,
        color = RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    if (!exempt) {
        Text(
            text = "ALLOW",
            style = RadarType.labelMicro,
            color = CategoryColors.Teal,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching { context.startActivity(BatteryOptimization.requestIntent(context)) }
                        exempt = BatteryOptimization.isExempt(context)
                    }.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
    FooterStrip("Background work is best-effort without this")
}

/**
 * Which enrichment path is live. The workflow (enrich → re-tag) is the default;
 * this row is the kill switch, so a bad release returns to the direct call without
 * a new build. It takes effect on the next run, not retroactively.
 */
@Composable
private fun EnrichmentSection() {
    val container = LocalAppContainer.current
    var viaWorkflow by remember { mutableStateOf(container.workflowPrefs.enrichViaWorkflow()) }

    WidgetHeader(title = "Enrichment", note = if (viaWorkflow) "workflow" else "direct")
    Text(
        text =
            if (viaWorkflow) {
                "Enrichment runs as a workflow — fetch, then re-tag — so a run is " +
                    "tracked and resumable."
            } else {
                "Enrichment calls the enricher directly and records no run."
            },
        style = RadarType.microPlain,
        color = RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable {
                    viaWorkflow = !viaWorkflow
                    container.workflowPrefs.setEnrichViaWorkflow(viaWorkflow)
                }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "USE WORKFLOW", style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (viaWorkflow) "ON" else "OFF",
            style = RadarType.micro,
            color = if (viaWorkflow) RadarColors.paper2 else RadarColors.ink2,
            modifier =
                Modifier
                    .background(if (viaWorkflow) RadarColors.ink else RadarColors.paper4)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    FooterStrip("Applies from the next run")
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
    models: List<Artifact>,
    onDownloaded: suspend (AppContainer) -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var statuses by remember { mutableStateOf<Map<String, ArtifactStatus>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        statuses = models.associate { it.ref to container.models.status(it.ref) }
    }
    LaunchedEffect(Unit) { refresh() }

    WidgetHeader(title = title, note = "${models.sumOf { it.files.size }} files")
    Text(
        text = blurb,
        style = RadarType.microPlain,
        color = RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
    models.forEach { model ->
        model.files.forEach { spec ->
            ModelRow(
                name = spec.fileName,
                size = modelSizeLabel(spec.sizeBytes),
                status = modelStatusLabel(statuses[model.ref] ?: ArtifactStatus.ABSENT),
            )
        }
    }
    val anyMissing = models.any { needsDownload(statuses[it.ref] ?: ArtifactStatus.ABSENT) }
    if (anyMissing) {
        Text(
            text = if (busy) "DOWNLOADING" else "DOWNLOAD",
            style = RadarType.labelMicro,
            color = if (busy) RadarColors.ink3 else CategoryColors.Teal,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) {
                        android.util.Log.i("ModelsSection", "download tapped: ${models.map { it.name }}")
                        scope.launch {
                            busy = true
                            val ok = container.modelDownloader.download(models.map { it.ref })
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
