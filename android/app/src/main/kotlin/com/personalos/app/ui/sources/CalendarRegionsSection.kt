package com.personalos.app.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.calendar.IcsCalendar
import com.personalos.app.core.net.Http
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The spec for a calendar region, built from the provider's own URL rule.
 *
 * Pure, and the test round-trips it through the real `SourceSpecs.parse`, so a
 * region the parser would reject can never be written. No region is a literal
 * here: `japan` and `india/west-bengal` are the same shape.
 *
 * @throws IllegalArgumentException when the provider is unknown.
 */
internal fun calendarRegionSpec(
    providerId: String,
    region: String,
): String {
    val provider =
        CalendarProviders.byId(providerId)
            ?: throw IllegalArgumentException("unknown calendar provider: $providerId")
    val normalized = CalendarProviders.normalize(region)
    return buildJsonObject {
        put("provider", JsonPrimitive(provider.id))
        put("region", JsonPrimitive(normalized))
        put("url", JsonPrimitive(provider.urlFor(normalized)))
    }.toString()
}

/**
 * Why a region cannot be added, or null when it can. The blank case names the
 * shape to use, because "invalid region" tells you nothing actionable.
 */
internal fun validateCalendarRegion(
    region: String,
    existing: Set<String>,
): String? {
    val normalized = CalendarProviders.normalize(region)
    if (normalized.isEmpty()) {
        return "Region is blank — try japan, or a country/subdivision like india/west-bengal."
    }
    if (normalized in existing) return "Already subscribed: $normalized."
    return null
}

/**
 * Follow a region's holidays and festivals.
 *
 * A region is a source of kind `calendar` — the same shape as a feed — so
 * Settings is the only place that writes, and the calendar sync reads it like
 * any other producer. The region is **checked against the provider before it is
 * stored**: a typo that parses to nothing must fail here, not sync silence
 * forever.
 */
@Composable
fun CalendarRegionsSection(sources: List<SourceEntity>) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val calendars = remember(sources) { sources.filter { it.kind == "calendar" }.sortedBy { it.name.lowercase() } }
    val known = remember(calendars) { calendars.map { it.name }.toSet() }

    var region by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun add() {
        val error = validateCalendarRegion(region, known)
        if (error != null) {
            note = false to error
            return
        }
        checking = true
        note = null
        scope.launch {
            val normalized = CalendarProviders.normalize(region)
            val spec = calendarRegionSpec(CalendarProviders.officeHolidays.id, normalized)
            val url = (SourceSpecs.parse("calendar", spec) as CalendarSpec).url
            val found = withContext(Dispatchers.IO) { countDates(url) }
            when {
                found == null || found == 0 ->
                    note = false to "Nothing found for $normalized — check the spelling."
                else ->
                    runCatching {
                        container.sourceRepository.addUserSource(
                            name = normalized,
                            kind = "calendar",
                            specJson = spec,
                        )
                    }.onSuccess {
                        note = true to "$found dates for $normalized."
                        region = ""
                    }.onFailure {
                        note = false to (it.message ?: "Could not add the region.")
                    }
            }
            checking = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        WidgetHeader(title = "Calendars", note = "${calendars.size} regions")
        Text(
            text =
                "Holidays and festivals for a region you follow. A region is a country, " +
                    "or a country and a subdivision — nothing is limited to one country.",
            style = RadarType.small,
            color = RadarColors.ink2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(text = "REGION", style = RadarType.micro, color = RadarColors.ink3)
            Spacer(Modifier.height(3.dp))
            FieldBox(
                value = region,
                hint = "e.g. japan, india/west-bengal",
                onChange = {
                    region = it
                    note = null
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatButton(
                    text = if (checking) "CHECKING" else "ADD",
                    onClick = { add() },
                    enabled = !checking,
                )
                Spacer(Modifier.width(8.dp))
                note?.let { (ok, text) ->
                    Text(
                        text = text,
                        style = RadarType.microPlain,
                        color = if (ok) CategoryColors.Teal else CategoryColors.Vermilion,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SoftRule()

        calendars.forEach { source ->
            CalendarRow(
                source = source,
                onToggle = {
                    scope.launch {
                        runCatching { container.sourceRepository.setEnabled(source.id, source.enabled.not()) }
                    }
                },
                onDelete = {
                    scope.launch {
                        // A user row only; seeded rows are locked by the repository's SQL.
                        runCatching { container.sourceRepository.delete(source.id) }
                    }
                },
            )
        }
    }
}

/** How many dated rows a feed carries — the check that runs before storing. */
private fun countDates(url: String): Int? = runCatching { IcsCalendar.parse(Http.getText(url, accept = "text/calendar")).size }.getOrNull()

@Composable
private fun CalendarRow(
    source: SourceEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (source.enabled) "enabled" else "disabled",
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
            Text(
                text = if (source.enabled) "DISABLE" else "ENABLE",
                style = RadarType.labelMicro,
                color = RadarColors.ink2,
                modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                text = "DELETE",
                style = RadarType.labelMicro,
                color = CategoryColors.Vermilion,
                modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        SoftRule()
    }
}
