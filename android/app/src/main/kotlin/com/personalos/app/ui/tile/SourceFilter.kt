package com.personalos.app.ui.tile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personalos.app.data.SourceCount
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.compactNumber
import com.personalos.app.ui.theme.RadarType

/** One source filter: a family prefix, or null for "All". */
data class SourceFilterOption(
    val value: String?,
    val label: String,
    val count: Int,
)

/**
 * Turns the families present in the store into menu entries. The "All" entry
 * counts the sum, so it never disagrees with the rows it reveals.
 *
 * The only special-casing is presentation: a prefix is still a prefix, and an
 * unknown family gets a readable fallback rather than disappearing.
 */
fun sourceFilterOptions(
    families: List<SourceCount>,
    allCount: Int,
): List<SourceFilterOption> =
    buildList {
        add(SourceFilterOption(value = null, label = "All", count = allCount))
        families.distinctBy { it.source }.forEach { family ->
            add(
                SourceFilterOption(
                    value = family.source,
                    label = sourceFamilyLabel(family.source),
                    count = family.count,
                ),
            )
        }
    }

fun sourceFamilyLabel(family: String): String =
    when (family) {
        "rss:" -> "RSS"
        "userrss:" -> "Your RSS"
        "gnews:" -> "Google News"
        "sms" -> "SMS"
        else -> family.removeSuffix(":").replace('-', ' ').ifBlank { "Other" }
    }

/**
 * The source filter as one compact control pinned to the right of the tab
 * strip. It replaces a second full-width chip rail: the active source stays
 * visible, and the options live in a menu that scales to any number of feeds.
 */
@Composable
fun SourceFilterDropdown(
    options: List<SourceFilterOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size <= 1) return
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected } ?: options.first()

    Box(modifier) {
        Row(
            modifier =
                Modifier
                    .clickable(onClickLabel = "Choose source") { expanded = true }
                    .background(if (selected == null) RadarColors.paper2 else RadarColors.paper3)
                    .border(1.dp, RadarColors.ruleSoft, RoundedCornerShape(2.dp))
                    .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current.label.uppercase(),
                style = RadarType.label,
                color = RadarColors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = compactNumber(current.count),
                style = RadarType.microPlain,
                color = RadarColors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.width(4.dp))
            Text(text = "▾", style = RadarType.label, color = RadarColors.ink2)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(RadarColors.paper),
        ) {
            options.forEach { option ->
                val active = option.value == selected
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = option.label.uppercase(),
                                style = RadarType.label,
                                color = if (active) RadarColors.ink else RadarColors.ink2,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = compactNumber(option.count),
                                style = RadarType.microPlain,
                                color = RadarColors.ink3,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                    modifier = Modifier.background(if (active) RadarColors.paper3 else RadarColors.paper),
                )
            }
        }
    }
}
