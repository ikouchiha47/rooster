package com.personalos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.Chars
import com.personalos.app.data.CalendarDateEntity
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SectionHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.common.WidgetHeader
import com.personalos.app.ui.sources.CalendarRegionsSection
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** `12 MAR`, or the raw ISO string rather than nothing if it cannot be parsed. */
private fun observanceDay(iso: String): String = runCatching { LocalDate.parse(iso).format(DAY_FMT) }.getOrDefault(iso)

/**
 * Calendar: the regions you follow, and their observances month by month.
 *
 * Both reads come from one owner ([com.personalos.app.data.CalendarRepository]):
 * the picker writes `calendar` sources through [CalendarRegionsSection], and the
 * list below is the observances for exactly the enabled ones — so the two can
 * never drift. All decisions (ordering, month grouping, the display cap, the
 * empty state) live in [CalendarMonth] and are tested without rendering.
 */
@Composable
fun CalendarScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val container = LocalAppContainer.current
    val sources by container.sourceRepository.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val regions by container.calendarRepository.followedRegions().collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by container.calendarRepository.upcoming().collectAsStateWithLifecycle(initialValue = emptyList())
    val months = remember(upcoming) { groupIntoCalendarMonths(upcoming) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(RadarColors.paper2),
    ) {
        RadarHeader(
            title = "Calendar",
            accent = CategoryColors.Rust,
            onBack = onBack,
            subtitle = {
                Text(
                    text = "${regions.size} REGIONS ${Chars.MIDDLE_DOT} ${upcoming.size} UPCOMING",
                    style = RadarType.labelMicro,
                    color = RadarColors.paper4,
                )
            },
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // The region picker. A region is a source of kind `calendar`, so this
            // is the same writer Sources uses, hosted on the Calendar surface.
            item { CalendarRegionsSection(sources = sources) }

            item { Spacer(Modifier.height(10.dp)) }
            item { WidgetHeader(title = "Upcoming", note = "${months.size} months") }

            if (months.isEmpty()) {
                item {
                    Text(
                        text = calendarEmptyMessage(regions.size),
                        style = RadarType.body,
                        color = RadarColors.ink3,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            } else {
                months.forEach { month ->
                    item(key = "month-${month.year}-${month.month}") {
                        SectionHeader(
                            title = calendarMonthHeader(month.year, month.month),
                            accent = CategoryColors.Rust,
                            note = "${month.items.size}",
                        )
                    }
                    items(month.items, key = { "${it.source}:${it.feedUid}" }) { row ->
                        ObservanceRow(row)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Shared with Travel's Calendar tab, so both surfaces draw observances the same way. */
@Composable
internal fun ObservanceRow(row: CalendarDateEntity) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = observanceDay(row.date),
                style = RadarType.monoSmall,
                color = RadarColors.ink3,
                modifier = Modifier.width(56.dp),
                maxLines = 1,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.kind.uppercase(Locale.ENGLISH)} ${Chars.MIDDLE_DOT} ${row.region}",
                    style = RadarType.microPlain,
                    color = RadarColors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SoftRule()
    }
}
