package com.personalos.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personalos.app.core.tag.Ulid
import com.personalos.app.core.time.UserZone
import com.personalos.app.data.PlannerItemEntity
import com.personalos.app.data.PlannerTagEntity
import com.personalos.app.data.PlannerWorkspaceEntity
import com.personalos.app.ui.common.Chip
import com.personalos.app.ui.common.FlatButton
import com.personalos.app.ui.common.LocalAppContainer
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.common.RadarHeader
import com.personalos.app.ui.common.SoftRule
import com.personalos.app.ui.theme.CategoryColors
import com.personalos.app.ui.theme.RadarType
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * Create a reminder or event on the selected day. The target workspace defaults
 * to Personal; tags are manual and come from the seeded planner vocabulary.
 *
 * Recurrence is stored now, but occurrences are the calendar's read model; this
 * screen does not schedule notifications yet.
 */
@Composable
fun PlannerEditorScreen(
    date: LocalDate,
    initialKind: String = "reminder",
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val workspaces by container.plannerRepository.workspaces.collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by container.plannerRepository.tags.collectAsStateWithLifecycle(initialValue = emptyList())

    var kind by remember { mutableStateOf(if (initialKind == "event") "event" else "reminder") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var hour by remember { mutableIntStateOf(9) }
    var minute by remember { mutableIntStateOf(0) }
    var workspace by remember { mutableStateOf<PlannerWorkspaceEntity?>(null) }
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repeat by remember { mutableStateOf<String?>(null) }
    var workspaceMenu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(workspaces) {
        if (workspace == null) {
            workspace = workspaces.firstOrNull { it.isDefault } ?: workspaces.firstOrNull()
        }
    }

    Column(modifier.fillMaxSize().background(RadarColors.paper2)) {
        RadarHeader(
            title = if (kind == "event") "New event" else "New reminder",
            accent = CategoryColors.Rust,
            onBack = onBack,
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(
                horizontalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(4.dp),
            ) {
                FlatButton(text = "Reminder", onClick = { kind = "reminder" }, solid = kind == "reminder")
                FlatButton(text = "Event", onClick = { kind = "event" }, solid = kind == "event")
            }
            Spacer(Modifier.height(10.dp))

            PlannerField(label = "Title", value = title, onChange = { title = it })
            Spacer(Modifier.height(8.dp))

            Text(text = "DATE", style = RadarType.labelMicro, color = RadarColors.ink3)
            Text(
                text = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)),
                style = RadarType.serifTitle,
                color = RadarColors.ink,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            SoftRule()

            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatButton(text = if (allDay) "All day" else "Timed", onClick = { allDay = !allDay }, solid = allDay)
                Spacer(Modifier.weight(1f))
                if (!allDay) {
                    FlatButton(text = "− 15m", onClick = {
                        val total = (hour * 60 + minute - 15 + 1440) % 1440
                        hour = total / 60
                        minute = total % 60
                    })
                    Spacer(Modifier.width(4.dp))
                    Text(text = "%02d:%02d".format(hour, minute), style = RadarType.monoStat, color = RadarColors.ink)
                    Spacer(Modifier.width(4.dp))
                    FlatButton(text = "+ 15m", onClick = {
                        val total = (hour * 60 + minute + 15) % 1440
                        hour = total / 60
                        minute = total % 60
                    })
                }
            }
            SoftRule()

            Text(text = "CALENDAR", style = RadarType.labelMicro, color = RadarColors.ink3)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { workspaceMenu = true }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = workspace?.name ?: "Personal",
                    style = RadarType.serifTitle,
                    color = RadarColors.ink,
                )
                Spacer(Modifier.weight(1f))
                Text(text = "▾", style = RadarType.label, color = RadarColors.ink2)
                DropdownMenu(expanded = workspaceMenu, onDismissRequest = { workspaceMenu = false }) {
                    workspaces.forEach { row ->
                        DropdownMenuItem(
                            text = { Text(row.name, style = RadarType.body, color = RadarColors.ink) },
                            onClick = {
                                workspace = row
                                workspaceMenu = false
                            },
                        )
                    }
                }
            }
            SoftRule()

            Text(text = "TAGS", style = RadarType.labelMicro, color = RadarColors.ink3, modifier = Modifier.padding(top = 6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp),
            ) {
                tags.forEachIndexed { index, tag ->
                    if (index > 0) Spacer(Modifier.width(4.dp))
                    val selected = tag.id in selectedTags
                    Box(
                        Modifier.clickable {
                            selectedTags = if (selected) selectedTags - tag.id else selectedTags + tag.id
                        },
                    ) {
                        Chip(
                            text = tag.label,
                            color = if (selected) tagColor(tag) else RadarColors.paper4,
                        )
                    }
                }
            }
            SoftRule()

            Text(text = "REPEAT", style = RadarType.labelMicro, color = RadarColors.ink3, modifier = Modifier.padding(top = 6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp),
            ) {
                repeatOptions().forEachIndexed { index, option ->
                    if (index > 0) Spacer(Modifier.width(4.dp))
                    FlatButton(
                        text = option.second,
                        onClick = { repeat = option.first },
                        solid = repeat == option.first,
                    )
                }
            }
            SoftRule()

            PlannerField(label = "Notes", value = notes, onChange = { notes = it }, singleLine = false)
            Spacer(Modifier.height(14.dp))

            Row(
                horizontalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(6.dp),
            ) {
                FlatButton(
                    text = if (saving) "Saving…" else "Save",
                    onClick = {
                        val target = workspace ?: return@FlatButton
                        if (title.isBlank() || saving) return@FlatButton
                        saving = true
                        scope.launch {
                            val start = LocalTime.of(hour, minute)
                            val startAt =
                                date
                                    .atTime(if (allDay) LocalTime.MIDNIGHT else start)
                                    .atZone(UserZone.current)
                                    .toInstant()
                                    .toEpochMilli()
                            val endAt =
                                if (allDay) {
                                    null
                                } else {
                                    date
                                        .atTime(start)
                                        .plusHours(1)
                                        .atZone(UserZone.current)
                                        .toInstant()
                                        .toEpochMilli()
                                }
                            runCatching {
                                container.plannerRepository.save(
                                    PlannerItemEntity(
                                        id = Ulid.next(),
                                        workspaceId = target.id,
                                        kind = kind,
                                        title = title.trim(),
                                        notes = notes.trim(),
                                        startAt = startAt,
                                        endAt = endAt,
                                        allDay = allDay,
                                        timeZone = UserZone.current.id,
                                        recurrenceRule = repeat,
                                        recurrenceUntil = null,
                                        recurrenceCount = null,
                                        status = "scheduled",
                                        leadMinutes = if (kind == "reminder") 0 else null,
                                        sourceItemId = null,
                                        createdAt = System.currentTimeMillis(),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                    selectedTags,
                                )
                            }.onSuccess { onSaved() }
                            saving = false
                        }
                    },
                    solid = true,
                    enabled = title.isNotBlank() && workspace != null && !saving,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlannerField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(text = label.uppercase(), style = RadarType.labelMicro, color = RadarColors.ink3)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = RadarType.body.copy(color = RadarColors.ink),
            cursorBrush = SolidColor(RadarColors.ink),
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 4.dp),
        )
        SoftRule()
    }
}

private fun repeatOptions(): List<Pair<String?, String>> =
    listOf(
        null to "None",
        "FREQ=YEARLY" to "Yearly",
        "FREQ=MONTHLY" to "Monthly",
        "FREQ=WEEKLY" to "Weekly",
        "FREQ=DAILY" to "Daily",
    )

private fun tagColor(tag: PlannerTagEntity): Color = runCatching { Color(android.graphics.Color.parseColor(tag.color)) }.getOrDefault(CategoryColors.Rust)
