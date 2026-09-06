package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Bottom toolbar for the drawing screen: undo the last stroke, or clear all of them. */
@Composable
fun DrawingToolbar(
    onUndo: () -> Unit,
    onClear: () -> Unit,
    canUndo: Boolean,
    canClear: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            OutlinedButton(onClick = onUndo, enabled = canUndo) {
                Text("Undo")
            }
            Button(onClick = onClear, enabled = canClear) {
                Text("Clear")
            }
        }
    }
}
