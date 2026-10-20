package com.personalos.app.data

import com.personalos.app.core.calendar.CalendarDirectoryParser
import com.personalos.app.core.calendar.CalendarProviders
import com.personalos.app.core.calendar.CalendarSurface
import com.personalos.app.core.sources.CalendarSpec
import com.personalos.app.core.sources.SourceKeys
import com.personalos.app.core.sources.SourceSpecs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One selectable calendar row: a followed provider region plus this surface's visibility. */
data class CalendarSource(
    val id: String,
    val label: String,
    val region: String,
    val provider: String,
    val enabled: Boolean,
)

/**
 * The single owner of the calendar fact (docs/CODE-DESIGN-GUIDELINES.md §1).
 *
 * A followed region is an enabled `sources` row of kind `calendar`. The dates
 * are the provider's facts. Per-surface selection is a projection over those
 * facts, stored in Room: an explicit selection row wins; otherwise the
 * surface's default namespace wins; otherwise every followed calendar is on.
 */
class CalendarRepository(
    private val sources: SourceDao,
    private val calendar: CalendarDao,
    private val settings: CalendarSettingsDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Every followed calendar with this surface's checkbox state. */
    fun calendars(surface: CalendarSurface): Flow<List<CalendarSource>> =
        combine(
            sources.observeAll(),
            settings.observeSelections(surface.id),
            settings.observeSettings(surface.id),
        ) { rows, selections, setting ->
            val followed =
                rows
                    .asSequence()
                    .filter { it.kind == KIND }
                    .mapNotNull { row ->
                        val spec = spec(row) ?: return@mapNotNull null
                        CalendarSource(
                            id = row.id,
                            label = label(spec),
                            region = spec.region,
                            provider = spec.provider,
                            enabled = row.enabled,
                        )
                    }.sortedWith(compareBy({ it.label }, { it.id }))
                    .toList()

            val explicit = selections.associate { it.sourceId to it.enabled }
            val default = setting?.defaultSourceId
            val projected =
                followed.map { row ->
                    val enabled =
                        when {
                            explicit.containsKey(row.id) -> explicit.getValue(row.id)
                            default != null -> row.id == default
                            else -> true
                        }
                    row.copy(enabled = row.enabled && enabled)
                }
            projected
        }

    /** The surface's default namespace id, or null when it falls back to All. */
    fun defaultSource(surface: CalendarSurface): Flow<String?> = settings.observeSettings(surface.id).map { it?.defaultSourceId }

    fun followedRegions(surface: CalendarSurface = CalendarSurface.CALENDAR): Flow<List<String>> = calendars(surface).map { rows -> rows.filter { it.enabled }.map { it.label }.distinct() }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun upcoming(surface: CalendarSurface = CalendarSurface.CALENDAR): Flow<List<CalendarDateEntity>> =
        calendars(surface).flatMapLatest { rows ->
            val selected = rows.filter { it.enabled }.map { it.id }.toSet()
            if (selected.isEmpty()) {
                flowOf(emptyList())
            } else {
                val all = sources.all()
                val identities = all.filter { it.id in selected }.mapNotNull(::identity)
                if (identities.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val from = now()
                    calendar.observeUpcomingForSources(identities, from, from + WINDOW_MS)
                }
            }
        }

    suspend fun setEnabled(
        surface: CalendarSurface,
        sourceId: String,
        enabled: Boolean,
    ) {
        val current = currentSelection(surface)
        val all = sourceIds()
        val rows =
            all.map { id ->
                CalendarSelectionEntity(
                    surface = surface.id,
                    sourceId = id,
                    enabled = if (id == sourceId) enabled else current[id] ?: true,
                )
            }
        settings.putSelections(rows)
    }

    suspend fun only(
        surface: CalendarSurface,
        sourceId: String,
    ) {
        settings.putSelections(
            sourceIds().map {
                CalendarSelectionEntity(surface.id, it, enabled = it == sourceId)
            },
        )
    }

    /** Set the default namespace and load it alone on that surface. */
    suspend fun setDefault(
        surface: CalendarSurface,
        sourceId: String,
    ) {
        settings.putSettings(CalendarSettingsEntity(surface.id, sourceId))
        only(surface, sourceId)
    }

    suspend fun reset(surface: CalendarSurface) {
        settings.clearSelections(surface.id)
    }

    private suspend fun sourceIds(): List<String> = sources.all().filter { it.kind == KIND }.map { it.id }

    private suspend fun currentSelection(surface: CalendarSurface): Map<String, Boolean> {
        val setting = settings.settings(surface.id)
        val default = setting?.defaultSourceId
        val explicit = settings.selections(surface.id)
        if (explicit.isNotEmpty()) return explicit.associate { it.sourceId to it.enabled }
        return sourceIds().associateWith { id -> default == null || id == default }
    }

    private fun spec(row: SourceEntity): CalendarSpec? = runCatching { SourceSpecs.parse(KIND, row.specJson) as CalendarSpec }.getOrNull()

    private fun identity(row: SourceEntity): String? = spec(row)?.let { SourceKeys.sourceFor(row.id, it) }

    private fun label(spec: CalendarSpec): String =
        CalendarProviders.byId(spec.provider)?.displayName(spec.region)
            ?: CalendarDirectoryParser.displayName(spec.region)

    private companion object {
        const val KIND = "calendar"
        const val WINDOW_MS = 365L * 24 * 60 * 60 * 1000
    }
}
