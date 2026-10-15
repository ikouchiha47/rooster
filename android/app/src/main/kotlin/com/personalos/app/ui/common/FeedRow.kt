package com.personalos.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personalos.app.data.MentionEntity
import com.personalos.app.ui.theme.RadarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lines of the headline block, and of the description block under it. */
private const val TITLE_LINES = 2
private const val SUMMARY_LINES = 2

private val ROW_TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * The one feed row. News, Money, Weather (and Travel's News tab) all render this,
 * so the description — and whatever comes next — lives in exactly one place
 * instead of being copied per screen and missed in one of them.
 *
 * The 3dp spine repeats the leading subject's colour; the meta line prints the
 * source and the non-implied tags (via [TagLine], which drops the tag the tile
 * already stands for). The headline and description are **fixed blocks**: they
 * wrap-then-ellipsise into their full height whatever the length, so every row
 * measures one height and the list scans as a grid.
 *
 * @param time the row's clock, or null for a source that has none (Money/Weather).
 * @param rule a dotted separator under the description (News/Travel use it).
 * @param onOpen tap target; null leaves the row inert (Weather).
 */
@Composable
fun FeedRow(
    title: String,
    content: String,
    source: String,
    tags: List<String>,
    /** Tags for the spine colour, when it differs from the meta chips (Money/Weather). */
    spineTags: List<String> = tags,
    mentions: List<MentionEntity> = emptyList(),
    time: Long? = null,
    rule: Boolean = true,
    onOpen: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (onOpen != null) it.clickable { onOpen() } else it },
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            CategorySpine(color = subjectSpineColor(spineTags))
            if (time != null) {
                Text(
                    text = ROW_TIME_FMT.format(Date(time)),
                    style = RadarType.monoSmall,
                    color = RadarColors.ink3,
                    modifier = Modifier.width(44.dp),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(blockHeight(RadarType.serifTitle, TITLE_LINES)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = title,
                        style = RadarType.serifTitle,
                        color = RadarColors.ink,
                        minLines = TITLE_LINES,
                        maxLines = TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.fillMaxWidth().height(blockHeight(RadarType.small, SUMMARY_LINES))) {
                    if (content.isNotBlank()) {
                        Text(
                            text = content,
                            style = RadarType.small,
                            color = RadarColors.ink2,
                            maxLines = SUMMARY_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (rule) {
                    Spacer(Modifier.height(4.dp))
                    DottedRule()
                    Spacer(Modifier.height(4.dp))
                } else {
                    Spacer(Modifier.height(3.dp))
                }
                TagLine(tags = tags, source = source, mentions = mentions)
            }
        }
        SoftRule()
    }
}

/** Height of a fixed [lines]-line block of [style], from the token's own lineHeight. */
@Composable
private fun blockHeight(
    style: TextStyle,
    lines: Int,
): Dp = with(LocalDensity.current) { (style.lineHeight * lines).toDp() }
