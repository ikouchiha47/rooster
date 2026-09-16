package com.personalos.app.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.personalos.app.core.feed.FeedStatus
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType

/**
 * The unified feed list: one row treatment used everywhere feeds are shown.
 *
 * Row: health dot, feed name, domain, right-aligned age and new-count stack.
 * This is the treatment the owner preferred over the wordier My-feeds rows.
 */
@Composable
fun FeedList(
    statuses: List<FeedStatus>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        statuses.forEach { status ->
            FeedRow(status = status)
        }
    }
}

@Composable
fun FeedRow(status: FeedStatus) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(status)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = status.name,
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                    maxLines = 1,
                )
                Text(
                    text = status.lastError?.let { "${status.host} · $it" } ?: status.host,
                    style = RadarType.microPlain,
                    color = if (status.lastError != null) CategoryColors.Vermilion else RadarColors.ink3,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ago(status.lastOkAt),
                    style = RadarType.monoSmall,
                    color = RadarColors.ink2,
                )
                Text(
                    text = "${status.itemCount} NEW",
                    style = RadarType.micro,
                    color = RadarColors.ink3,
                )
            }
        }
        SoftRule()
    }
}

@Composable
internal fun StatusDot(status: FeedStatus?) {
    val color =
        when {
            status == null || status.neverSynced -> RadarColors.ruleDot
            status.ok -> Ok
            else -> CategoryColors.Vermilion
        }
    Box(
        Modifier
            .size(7.dp)
            .background(color),
    )
}

internal val Ok = Color(0xFF2F6B3A)

internal fun ago(at: Long): String {
    if (at <= 0L) return "NEVER"
    val delta = System.currentTimeMillis() - at
    return when {
        delta < 60_000L -> "NOW"
        delta < 3_600_000L -> "${delta / 60_000L}M"
        delta < 86_400_000L -> "${delta / 3_600_000L}H"
        else -> "${delta / 86_400_000L}D"
    }
}
