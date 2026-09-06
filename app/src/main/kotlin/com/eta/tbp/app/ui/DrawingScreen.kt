package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eta.tbp.app.viewmodel.RecognizerViewModel

/**
 * Top-level teach/recognize screen: the canvas feeds completed strokes to
 * [viewModel], which owns the actual recognition state; this composable
 * just renders whatever it exposes. The LM-state overlay floats over the
 * canvas so it can be toggled without disrupting the drawing/toolbar layout.
 */
@Composable
fun DrawingScreen(
    viewModel: RecognizerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DrawingCanvas(
                strokes = viewModel.strokes,
                onStrokeCompleted = viewModel::onStrokeCompleted,
                enabled = viewModel.result == null,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                LmStateToggleButton(expanded = viewModel.showLmState, onToggle = viewModel::onToggleLmState)
                if (viewModel.showLmState) {
                    Spacer(Modifier.height(8.dp))
                    LmStateOverlay(
                        currentPrimitives = viewModel.currentPrimitives,
                        learnedGraphs = viewModel.learnedGraphs,
                    )
                }
            }
        }
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
