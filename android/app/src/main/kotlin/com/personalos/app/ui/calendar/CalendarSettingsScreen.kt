package com.personalos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.calendar.CalendarSurface
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.sources.CalendarRegionsSection
import com.personalos.app.ui.theme.CategoryColors
import kotlinx.coroutines.launch

/**
 * Calendar settings: the saved set for the Calendar surface, its default
 * namespace, and the Add Region picker flattened into the same page.
 *
 * Travel keeps its own saved set on the Travel surface; it is a different
 * service item and is not edited from here.
 */
@Composable
fun CalendarSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val sources by container.sourceRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val calendars by container.calendarRepository.calendars(CalendarSurface.CALENDAR).collectAsStateWithLifecycle(initialValue = emptyList())
    val defaultId by container.calendarRepository.defaultSource(CalendarSurface.CALENDAR).collectAsStateWithLifecycle(initialValue = null)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(title = "Calendars", accent = CategoryColors.Rust, onBack = onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                CalendarRegionsSection(
                    sources = sources,
                    selectedIds = calendars.filter { it.enabled }.map { it.id }.toSet(),
                    defaultId = defaultId,
                    onToggle = { id, enabled ->
                        scope.launch { container.calendarRepository.setEnabled(CalendarSurface.CALENDAR, id, enabled) }
                    },
                    onOnly = { id ->
                        scope.launch { container.calendarRepository.only(CalendarSurface.CALENDAR, id) }
                    },
                    onDefault = { id ->
                        scope.launch { container.calendarRepository.setDefault(CalendarSurface.CALENDAR, id) }
                    },
                )
            }
        }
    }
}
