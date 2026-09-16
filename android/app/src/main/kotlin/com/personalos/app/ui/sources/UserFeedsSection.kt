package com.personalos.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.core.feed.FeedStatus
import com.personalos.app.core.sources.RssSpec
import com.personalos.app.core.sources.SourceKind
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity
import com.personalos.app.data.SourceRepository.Companion.DEFAULT_USER_INTERVAL_SEC
import com.personalos.app.data.SourceRepository.Companion.MAX_INTERVAL_SEC
import com.personalos.app.data.SourceRepository.Companion.MIN_INTERVAL_SEC
import com.personalos.app.data.remote.FeedCheck
import com.personalos.app.data.remote.FeedVerifier
import com.personalos.app.data.work.SyncScheduler
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * My feeds: the user-managed RSS sources.
 *
 * A view over the sources store (ADR 0003) — rows, validation and writes all go
 * through [com.personalos.app.data.SourceRepository], the single owner, so this
 * section never holds a second copy. User rows get a disable toggle and a
 * delete button; locked seeded rows get neither (the repository would reject
 * the write; the UI does not offer it).
 *
 * New rows re-poll on the shared schedule
 * ([SyncScheduler.DEFAULT_INTERVAL_MINUTES]) unless the form sets a per-feed
 * interval; each row shows its own cadence. New rows default to the `news` tag
 * and join the next sync automatically.
 */
@Composable
fun UserFeedsSection(sources: List<SourceEntity>) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val rssSources =
        remember(sources) {
            sources
                .filter { SourceKind.from(it.kind) == SourceKind.RSS }
                .sortedWith(compareBy({ it.seeded }, { it.name.lowercase() }))
        }
    val urlIndex = remember(rssSources) { rssUrlIndex(rssSources) }
    val userCount = rssSources.count { !it.seeded }
    val seedCount = rssSources.size - userCount

    // Per-source health from the ingestor: the dot, the last sync and the HTTP
    // code the row reports. Same source the catalog feeds' status comes from.
    val sourceStatuses by container.feeds.sourceStatuses.collectAsState()
    val sourceStatusById = remember(sourceStatuses) { sourceStatuses.associateBy { it.id } }

    // Seeded rows are never polled *as sources* — the catalog pass fetches those
    // same URLs — so their health lives under the catalog feed, not the source.
    // Matching on the URL is what keeps the two views over one fact.
    val catalogStatuses by container.feeds.statuses.collectAsState()
    val catalogByUrl = remember(catalogStatuses) { catalogStatuses.associateBy { normalizeFeedUrl(it.url) } }

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<AddPhase>(AddPhase.Editing) }
    var adding by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var rowError by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun toggle(source: SourceEntity) {
        busyId = source.id
        rowError = null
        scope.launch {
            runCatching { container.sourceRepository.setEnabled(source.id, !source.enabled) }
                .onFailure { rowError = source.id to (it.message ?: "Update failed.") }
            busyId = null
        }
    }

    fun delete(source: SourceEntity) {
        busyId = source.id
        rowError = null
        scope.launch {
            runCatching { container.sourceRepository.delete(source.id) }
                .onFailure { rowError = source.id to (it.message ?: "Delete failed.") }
            busyId = null
        }
    }

    fun submit() {
        if (phase is AddPhase.Verified) {
            val seconds = intervalSecondsOrNull(interval)
            if (seconds == null) {
                phase = AddPhase.Failed("Interval must be whole seconds between 900 and 86400.")
                return
            }
            adding = true
            scope.launch {
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            container.sourceRepository.addUserSource(
                                name.trim(),
                                SourceKind.RSS.serialName,
                                rssSpecJson(url.trim()),
                                intervalSec = seconds,
                            )
                        }
                    }
                result
                    .onSuccess {
                        name = ""
                        url = ""
                        interval = ""
                        phase = AddPhase.Editing
                        // The row joins the next sync on its own; pull now so
                        // its items land while the form is still on screen.
                        runCatching { container.feeds.refresh() }
                        runCatching { container.retagger.run() }
                    }.onFailure { phase = AddPhase.Failed(it.message ?: "Add failed.") }
                adding = false
            }
        } else {
            val local = validateUserFeed(name, url, urlIndex)
            if (local != null) {
                phase = AddPhase.Failed(local)
            } else if (intervalSecondsOrNull(interval) == null) {
                phase = AddPhase.Failed("Interval must be whole seconds between 900 and 86400.")
            } else {
                phase = AddPhase.Verifying
                val target = url.trim()
                scope.launch {
                    val check = withContext(Dispatchers.IO) { FeedVerifier.verify(target) }
                    phase =
                        when (check) {
                            is FeedCheck.Ok ->
                                AddPhase.Verified(check.statusCode, check.itemCount, check.sampleTitle)
                            is FeedCheck.Err -> AddPhase.Failed(check.reason)
                        }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        WidgetHeader(
            title = "My feeds",
            note = "$userCount user · $seedCount seed · sync every ${SyncScheduler.DEFAULT_INTERVAL_MINUTES}m",
        )
        // The form leads: adding a feed is what this section is for, and the
        // list below grows without pushing it off screen.
        AddFeedForm(
            name = name,
            url = url,
            interval = interval,
            phase = phase,
            adding = adding,
            onNameChange = {
                name = it
                if (phase is AddPhase.Verified || phase is AddPhase.Failed) phase = AddPhase.Editing
            },
            onUrlChange = {
                url = it
                if (phase is AddPhase.Verified || phase is AddPhase.Failed) phase = AddPhase.Editing
            },
            onIntervalChange = {
                interval = it.filter(Char::isDigit).take(6)
                if (phase is AddPhase.Verified || phase is AddPhase.Failed) phase = AddPhase.Editing
            },
            onSubmit = { submit() },
        )
        if (userCount > 0) {
            WidgetHeader(title = "Subscribed", note = "$userCount")
        }
        rssSources.forEach { source ->
            val feedUrl = rssUrlOf(source)
            UserFeedRow(
                source = source,
                url = feedUrl,
                status = sourceStatusById[source.id] ?: feedUrl?.let { catalogByUrl[normalizeFeedUrl(it)] },
                busy = busyId != null,
                error = rowError?.takeIf { it.first == source.id }?.second,
                onToggle = { toggle(source) },
                onDelete = { delete(source) },
            )
        }
    }
}

private sealed interface AddPhase {
    data object Editing : AddPhase

    data object Verifying : AddPhase

    data class Verified(
        val statusCode: Int,
        val itemCount: Int,
        val sampleTitle: String,
    ) : AddPhase

    data class Failed(
        val reason: String,
    ) : AddPhase
}

@Composable
private fun UserFeedRow(
    source: SourceEntity,
    url: String?,
    status: FeedStatus?,
    busy: Boolean,
    error: String?,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    // Host and cadence first, then what actually happened on the last poll:
    // the HTTP code with how long ago it last succeeded, or the failure.
    val health =
        when {
            status == null || status.neverSynced -> "awaiting first sync"
            status.lastError != null -> status.lastError
            status.statusCode != null -> "HTTP ${status.statusCode} · ${ago(status.lastOkAt)}"
            else -> "synced ${ago(status.lastOkAt)}"
        }
    val meta =
        listOfNotNull(
            url?.let(::hostOf),
            intervalLabel(source),
            health,
            "DISABLED".takeIf { !source.enabled },
            "SEED".takeIf { source.seeded },
        ).joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = RadarType.microPlain,
                    color = if (!source.enabled || error != null || status?.lastError != null) CategoryColors.Vermilion else RadarColors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!source.seeded) {
                FlatButton(
                    text = if (source.enabled) "DISABLE" else "ENABLE",
                    onClick = onToggle,
                    enabled = !busy,
                )
                Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = "DELETE",
                    onClick = onDelete,
                    enabled = !busy,
                    vermilion = true,
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = RadarType.microPlain,
                color = CategoryColors.Vermilion,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        SoftRule()
    }
}

@Composable
private fun AddFeedForm(
    name: String,
    url: String,
    interval: String,
    phase: AddPhase,
    adding: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val buttonText =
        when {
            adding -> "ADDING"
            phase is AddPhase.Verifying -> "VERIFYING"
            phase is AddPhase.Verified -> "ADD"
            else -> "VERIFY"
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(text = "NAME", style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(3.dp))
        FieldBox(value = name, hint = "e.g. Bangalore bytes", onChange = onNameChange)
        Spacer(Modifier.height(6.dp))
        Text(text = "FEED URL", style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(3.dp))
        FieldBox(value = url, hint = "https://example.com/feed.xml", onChange = onUrlChange)
        Spacer(Modifier.height(6.dp))
        Text(text = "REFRESH INTERVAL (SECONDS)", style = RadarType.micro, color = RadarColors.ink3)
        Spacer(Modifier.height(3.dp))
        FieldBox(
            value = interval,
            hint = "blank = ${DEFAULT_USER_INTERVAL_SEC}s",
            onChange = onIntervalChange,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatButton(
                text = buttonText,
                onClick = onSubmit,
                enabled = !adding && phase !is AddPhase.Verifying,
                solid = phase is AddPhase.Verified,
            )
            Spacer(Modifier.width(8.dp))
            when (phase) {
                is AddPhase.Verified ->
                    Text(
                        text = "HTTP ${phase.statusCode} · ${phase.itemCount} ITEMS · “${phase.sampleTitle}”",
                        style = RadarType.microPlain,
                        color = RadarColors.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                is AddPhase.Failed ->
                    Text(
                        text = phase.reason,
                        style = RadarType.microPlain,
                        color = CategoryColors.Vermilion,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                else -> Unit
            }
        }
    }
    SoftRule()
}

@Composable
private fun FieldBox(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(RadarColors.paper, RoundedCornerShape(2.dp))
            .border(1.dp, RadarColors.ink, RoundedCornerShape(2.dp))
            .padding(horizontal = 7.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (value.isEmpty()) {
            Text(
                text = hint,
                style = RadarType.small,
                color = RadarColors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = RadarType.small.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * How often this source re-polls: its own interval when set, else the shared
 * feed schedule. Read-only on the row — the scheduler runs one periodic job,
 * so the interval paces the source's cache, not a separate timer.
 */
internal fun intervalLabel(source: SourceEntity): String {
    val secs = source.intervalSec ?: DEFAULT_USER_INTERVAL_SEC
    return when {
        secs % 3600L == 0L -> "every ${secs / 3600}h"
        secs % 60L == 0L -> "every ${secs / 60}m"
        else -> "every ${secs}s"
    }
}

/**
 * The interval field's value in seconds, or null when it is not a usable
 * number. Blank means "the default", which is what a plain add uses.
 */
internal fun intervalSecondsOrNull(raw: String): Long? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return DEFAULT_USER_INTERVAL_SEC
    val secs = trimmed.toLongOrNull() ?: return null
    return secs.takeIf { it in MIN_INTERVAL_SEC..MAX_INTERVAL_SEC }
}

/** Normalised URL to source name, for the duplicate guard. Unparseable rows are skipped. */
internal fun rssUrlIndex(sources: List<SourceEntity>): Map<String, String> =
    sources
        .mapNotNull { row ->
            if (SourceKind.from(row.kind) != SourceKind.RSS) return@mapNotNull null
            val url =
                runCatching { SourceSpecs.parse(SourceKind.RSS, row.specJson) as RssSpec }
                    .getOrNull()
                    ?.url ?: return@mapNotNull null
            normalizeFeedUrl(url) to row.name
        }.toMap()

internal fun normalizeFeedUrl(url: String): String = url.trim().removeSuffix("/")

/** Local validation before any fetch: blank name, non-http(s) scheme, duplicate URL. */
internal fun validateUserFeed(
    name: String,
    url: String,
    existing: Map<String, String>,
): String? {
    if (name.isBlank()) return "Name is blank — give the feed a name."
    val clean = url.trim()
    if (!(clean.startsWith("http://") || clean.startsWith("https://"))) {
        return "URL must start with http:// or https://."
    }
    existing[normalizeFeedUrl(clean)]?.let { return "Already subscribed as “$it”." }
    return null
}

private fun rssUrlOf(source: SourceEntity): String? =
    runCatching { SourceSpecs.parse(SourceKind.RSS, source.specJson) as RssSpec }
        .getOrNull()
        ?.url

private fun rssSpecJson(url: String): String =
    JsonObject(
        mapOf(
            "url" to JsonPrimitive(url),
            "tags" to JsonArray(listOf(JsonPrimitive("news"))),
        ),
    ).toString()

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")
