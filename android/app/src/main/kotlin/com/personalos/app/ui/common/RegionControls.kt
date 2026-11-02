package com.personalos.app.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.personalos.app.core.map.SavedRegion
import com.personalos.app.ui.theme.RadarType

@Composable
fun RegionControls(
    regions: List<SavedRegion>,
    defaultRegion: SavedRegion?,
    selectedId: String?,
    onSelect: (SavedRegion) -> Unit,
    onSaveView: () -> Unit,
    onLocate: () -> Unit,
    locating: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        defaultRegion?.let { region ->
            FlatButton(
                text = "Home",
                onClick = { onSelect(region) },
                solid = selectedId == region.id,
            )
            Spacer(Modifier.width(4.dp))
        }
        regions.forEach { region ->
            if (region.id == defaultRegion?.id) return@forEach
            FlatButton(
                text = region.name,
                onClick = { onSelect(region) },
                solid = selectedId == region.id,
            )
            Spacer(Modifier.width(4.dp))
        }
        FlatButton(text = "Save view", onClick = onSaveView)
        Spacer(Modifier.width(4.dp))
        FlatButton(
            text = if (locating) "Locating…" else "Locate",
            onClick = onLocate,
            solid = false,
        )
    }
}

@Composable
fun SaveRegionDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this view", style = RadarType.label) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(name.trim())
                    onDismiss()
                },
            ) { Text("SAVE") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        },
    )
}
