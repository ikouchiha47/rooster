package com.personalos.app.ui.common

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.personalos.app.core.map.SavedRegion
import com.personalos.app.ui.common.Glyph
import com.personalos.app.ui.common.GlyphIcon
import com.personalos.app.ui.common.RadarColors
import com.personalos.app.ui.theme.RadarType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionControls(
    regions: List<SavedRegion>,
    defaultRegion: SavedRegion?,
    selectedId: String?,
    onSelect: (SavedRegion) -> Unit,
    onSaveView: () -> Unit,
    onLocate: () -> Unit,
    onClear: (() -> Unit)? = null,
    locating: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = regions.firstOrNull { it.id == selectedId } ?: defaultRegion
    Column(modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = selected?.name ?: "Whole world",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("Saved location", style = RadarType.microPlain) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Whole world") },
                    onClick = {
                        expanded = false
                        onClear?.invoke()
                    },
                )
                defaultRegion?.let { region ->
                    DropdownMenuItem(
                        text = { Text("Home · ${region.name}") },
                        onClick = {
                            expanded = false
                            onSelect(region)
                        },
                    )
                }
                regions.forEach { region ->
                    if (region.id == defaultRegion?.id) return@forEach
                    DropdownMenuItem(
                        text = { Text(region.name) },
                        onClick = {
                            expanded = false
                            onSelect(region)
                        },
                    )
                }
                if (regions.isEmpty() && defaultRegion == null) {
                    DropdownMenuItem(
                        text = { Text("No saved locations yet") },
                        onClick = { expanded = false },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(label = "ADD PIN", glyph = Glyph.Place, onClick = onSaveView)
            Spacer(Modifier.width(8.dp))
            IconAction(label = "LOCATE", glyph = Glyph.Scan, onClick = onLocate)
            Spacer(Modifier.width(8.dp))
            IconAction(
                label = "CLEAR",
                glyph = Glyph.Trash,
                enabled = selectedId != null,
                onClick = { onClear?.invoke() },
            )
        }
    }
}

@Composable
private fun IconAction(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .width(58.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(vertical = 2.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .background(RadarColors.paper2)
                    .border(1.dp, RadarColors.ruleSoft),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph = glyph, tint = RadarColors.ink, size = 20.dp)
        }
        Spacer(Modifier.height(3.dp))
        Text(text = label, style = RadarType.labelMicro, color = RadarColors.ink3)
    }
}

@Composable
fun SaveRegionDialog(
    onDismiss: () -> Unit,
    radiusKm: Double? = null,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a pin", style = RadarType.label) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                if (radiusKm != null) {
                    Text(
                        text = "Pin radius ~${radiusKm.toInt()} km at this zoom",
                        style = RadarType.microPlain,
                        color = RadarColors.ink3,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(name.trim())
                    onDismiss()
                },
            ) { Text("ADD PIN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}
