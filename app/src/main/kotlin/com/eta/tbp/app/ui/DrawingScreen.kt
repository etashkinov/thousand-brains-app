package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Top-level Phase-0 screen: owns the completed-stroke list so the bottom
 * toolbar's undo/clear act on the same state [DrawingCanvas] renders.
 */
@Composable
fun DrawingScreen(modifier: Modifier = Modifier) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }

    Column(modifier = modifier.fillMaxSize()) {
        DrawingCanvas(
            strokes = strokes,
            onStrokesChange = { strokes = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        DrawingToolbar(
            onUndo = { strokes = strokes.dropLast(1) },
            onClear = { strokes = emptyList() },
            canUndo = strokes.isNotEmpty(),
            canClear = strokes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
