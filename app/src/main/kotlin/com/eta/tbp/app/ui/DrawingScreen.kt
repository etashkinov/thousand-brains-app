package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eta.tbp.app.viewmodel.RecognizerViewModel

/**
 * Top-level teach/recognize screen: the canvas feeds completed strokes to
 * [viewModel], which owns the actual recognition state; this composable
 * just renders whatever it exposes.
 */
@Composable
fun DrawingScreen(
    viewModel: RecognizerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        DrawingCanvas(
            strokes = viewModel.strokes,
            onStrokeCompleted = viewModel::onStrokeCompleted,
            enabled = viewModel.result == null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        EvidenceBars(evidence = viewModel.evidence, modifier = Modifier.fillMaxWidth())
        if (viewModel.result != null) {
            RecognitionResultPanel(
                result = viewModel.result,
                taughtLabel = viewModel.taughtLabel,
                onTeach = viewModel::onTeach,
                onConfirm = viewModel::onConfirm,
                onCorrect = viewModel::onCorrect,
                onNext = viewModel::onNext,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            DrawingToolbar(
                onUndo = viewModel::onUndo,
                onClear = viewModel::onClear,
                onDone = viewModel::onDone,
                canUndo = viewModel.strokes.isNotEmpty(),
                canClear = viewModel.strokes.isNotEmpty(),
                canDone = viewModel.strokes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
