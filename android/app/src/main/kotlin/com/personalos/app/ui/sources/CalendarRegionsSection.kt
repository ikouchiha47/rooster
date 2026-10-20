package com.personalos.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalos.app.core.calendar.CalendarProvider
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.calendar.IcsCalendar
import com.personalos.app.core.calendar.Region
import com.personalos.app.core.net.Http
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceSpecs
import com.personalos.app.data.SourceEntity
import com.personalos.app.ui.calendar.calendarFollowKey
import com.personalos.app.ui.calendar.calendarRegionLabel
import com.personalos.app.ui.calendar.countryChoices
import com.personalos.app.ui.calendar.isFollowed
import com.personalos.app.ui.calendar.pickableRegions
import com.personalos.app.ui.calendar.wholeCountryLabel
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
 * here: `japan`, `india/west-bengal` and Google's `en.german` are the same shape.
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
 * Follow a region's holidays and festivals, chosen rather than typed.
 *
 * A region is a source of kind `calendar` — the same shape as a feed — so
 * Settings is the only place that writes, and the calendar sync reads it like
 * any other producer. The provider publishes the choices and the ingest checks
 * the feed before it is stored: a region that parses to nothing fails here, not
 * in silence forever.
 */
@Composable
fun CalendarRegionsSection(
    sources: List<SourceEntity>,
    selectedIds: Set<String> = emptySet(),
    defaultId: String? = null,
    onToggle: ((String, Boolean) -> Unit)? = null,
    onOnly: ((String) -> Unit)? = null,
    onDefault: ((String) -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val calendars = remember(sources) { sources.filter { it.kind == "calendar" } }
    val followed = remember(sources) { calendars.mapNotNull(::calendarFollowKey).toSet() }

    var pickerOpen by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(CalendarProviders.officeHolidays) }
    var query by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf<Region?>(null) }
    var countries by remember { mutableStateOf<Result<List<Region>>?>(null) }
    var subdivisions by remember { mutableStateOf<Result<List<Region>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // The provider owns its directory; this only reads it (and says so on failure).
    LaunchedEffect(pickerOpen, provider.id) {
        if (!pickerOpen) return@LaunchedEffect
        countries = null
        subdivisions = null
        selectedCountry = null
        countries = container.calendarDirectory.countries(provider)
    }

    fun choose(country: Region) {
        selectedCountry = country
        subdivisions = null
        query = ""
        if (provider.directory.hasSubdivisions) {
            scope.launch { subdivisions = container.calendarDirectory.subdivisions(provider, country) }
        }
    }

    fun add(region: Region) {
        val label = if (region.slug == selectedCountry?.slug) wholeCountryLabel(region) else region.name
        if (isFollowed(provider.id, region.slug, followed)) {
            note = false to "Already followed: $label"
            return
        }
        busy = true
        note = null
        scope.launch {
            val spec = calendarRegionSpec(provider.id, region.slug)
            val url = (SourceSpecs.parse("calendar", spec) as CalendarSpec).url
            val found = withContext(Dispatchers.IO) { countDates(url) }
            when {
                found == null || found == 0 ->
                    note = false to "Nothing found for $label — the feed could not be read."
                else ->
                    runCatching {
                        container.sourceRepository.addUserSource(
                            name = CalendarProviders.normalize(region.slug),
                            kind = "calendar",
                            specJson = spec,
                        )
                    }.onSuccess {
                        note = true to "$found dates for $label."
                        query = ""
                        selectedCountry = null
                    }.onFailure {
                        note = false to (it.message ?: "Could not add the region.")
                    }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        WidgetHeader(title = "Calendars", note = if (calendars.size == 1) "1 region" else "${calendars.size} regions")
        Text(
            text =
                "Holidays and festivals for a region you follow. Pick a country from a provider " +
                    "that lists it, and its states or provinces where it has them.",
            style = RadarType.small,
            color = RadarColors.ink2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlatButton(
                text = if (pickerOpen) "CLOSE" else "ADD REGION",
                onClick = {
                    pickerOpen = !pickerOpen
                    note = null
                },
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

        if (pickerOpen) {
            ProviderStrip(selected = provider) { chosen ->
                provider = chosen
                query = ""
                note = null
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                FieldBox(value = query, hint = "Search a country", onChange = { query = it })
            }
            Spacer(Modifier.height(4.dp))
            RegionChoices(
                provider = provider,
                query = query,
                selectedCountry = selectedCountry,
                countries = countries,
                subdivisions = subdivisions,
                followed = followed,
                busy = busy,
                onBack = {
                    selectedCountry = null
                    subdivisions = null
                    query = ""
                },
                onChooseCountry = ::choose,
                onAdd = ::add,
            )
        }
        SoftRule()

        calendars.forEach { source ->
            CalendarRow(
                source = source,
                label = calendarRegionLabel(source),
                selected = if (onToggle != null) source.id in selectedIds else source.enabled,
                onToggle = {
                    if (onToggle != null) {
                        onToggle(source.id, source.id !in selectedIds)
                    } else {
                        scope.launch {
                            runCatching { container.sourceRepository.setEnabled(source.id, source.enabled.not()) }
                        }
                    }
                },
                onOnly = onOnly?.let { callback -> { callback(source.id) } },
                isDefault = defaultId == source.id,
                onDefault = onDefault?.let { callback -> { callback(source.id) } },
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

/** Which provider's directory the picker is reading. One chip, no provider branch. */
@Composable
private fun ProviderStrip(
    selected: CalendarProvider,
    onSelect: (CalendarProvider) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarProviders.ALL.forEach { provider ->
            val active = provider.id == selected.id
            Text(
                text = provider.name.uppercase(),
                style = RadarType.labelMicro,
                color = if (active) RadarColors.paper2 else RadarColors.ink2,
                modifier =
                    Modifier
                        .background(if (active) RadarColors.ink else RadarColors.paper3, RoundedCornerShape(2.dp))
                        .clickable { onSelect(provider) }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
    }
}

/**
 * The choices themselves: countries, then a country's whole-country option and
 * its subdivisions. Everything is a bounded plain `Column` (the picker lives
 * inside a scrolling parent, so no nested lazy list), and every decision is
 * made by the pure model.
 */
@Composable
private fun RegionChoices(
    provider: CalendarProvider,
    query: String,
    selectedCountry: Region?,
    countries: Result<List<Region>>?,
    subdivisions: Result<List<Region>>?,
    followed: Set<String>,
    busy: Boolean,
    onBack: () -> Unit,
    onChooseCountry: (Region) -> Unit,
    onAdd: (Region) -> Unit,
) {
    val loaded = countries
    when {
        loaded == null -> PickerMessage("Loading ${provider.name} regions…", error = false)
        loaded.isFailure ->
            PickerMessage(
                "Could not load ${provider.name} regions. ${loaded.exceptionOrNull()?.message ?: "Unknown error."}",
                error = true,
            )
        selectedCountry == null -> {
            val offered = pickableRegions(loaded.getOrDefault(emptyList()), query, provider.id, followed)
            if (offered.isEmpty()) {
                PickerMessage(emptyMessage(query, provider), error = false)
            } else {
                offered.forEach { PickerRow(label = it.name, trailing = "SELECT", enabled = !busy, onClick = { onChooseCountry(it) }) }
            }
        }
        else -> {
            val country = selectedCountry
            Text(
                text = "← ${country.name}",
                style = RadarType.labelMicro,
                color = RadarColors.ink2,
                modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 6.dp),
            )
            val subs = subdivisions
            when {
                provider.directory.hasSubdivisions && subs == null -> PickerMessage("Loading ${country.name}…", error = false)
                else -> {
                    if (subs != null && subs.isFailure) {
                        PickerMessage(
                            "Could not load ${country.name}'s regions. ${subs.exceptionOrNull()?.message ?: "Unknown error."}",
                            error = true,
                        )
                    }
                    val choices = countryChoices(country, subs?.getOrNull().orEmpty())
                    val offered = pickableRegions(choices, query, provider.id, followed)
                    if (offered.isEmpty()) {
                        PickerMessage("Everything here is already followed.", error = false)
                    } else {
                        offered.forEach { region ->
                            val label = if (region.slug == country.slug) wholeCountryLabel(country) else region.name
                            PickerRow(label = label, trailing = "ADD", enabled = !busy, onClick = { onAdd(region) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    trailing: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = RadarType.body,
            color = RadarColors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            style = RadarType.labelMicro,
            color = if (enabled) RadarColors.ink2 else RadarColors.ink3,
        )
    }
    SoftRule()
}

@Composable
private fun PickerMessage(
    text: String,
    error: Boolean,
) {
    Text(
        text = text,
        style = RadarType.small,
        color = if (error) CategoryColors.Vermilion else RadarColors.ink3,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

private fun emptyMessage(
    query: String,
    provider: CalendarProvider,
): String =
    if (query.isBlank()) {
        "No regions left to add from ${provider.name}."
    } else {
        "Nothing matches \"${query.trim()}\"."
    }

/** How many dated rows a feed carries — the check that runs before storing. */
private fun countDates(url: String): Int? = runCatching { IcsCalendar.parse(Http.getText(url, accept = "text/calendar")).size }.getOrNull()

@Composable
private fun CalendarRow(
    source: SourceEntity,
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onOnly: (() -> Unit)?,
    isDefault: Boolean,
    onDefault: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .border(1.dp, RadarColors.ink)
                        .background(if (selected) RadarColors.ink else RadarColors.paper2)
                        .clickable(onClickLabel = if (selected) "Hide calendar" else "Show calendar", onClick = onToggle),
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (selected) "shown on this surface" else "hidden on this surface",
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                )
            }
            onOnly?.let {
                Spacer(Modifier.width(4.dp))
                FlatButton(text = "Only", onClick = it)
            }
            onDefault?.let {
                Spacer(Modifier.width(4.dp))
                FlatButton(
                    text = if (isDefault) "Default" else "Set default",
                    onClick = it,
                    solid = isDefault,
                )
            }
            Spacer(Modifier.width(4.dp))
            FlatButton(text = "Delete", onClick = onDelete, danger = true)
        }
        SoftRule()
    }
}
