package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eta.tbp.app.capture.exportCaptures
import com.eta.tbp.app.viewmodel.RecognizerViewModel
import com.eta.tbp.app.viewmodel.TeachMode

/**
 * Top-level teach/recognize screen: the canvas feeds completed strokes to
 * [viewModel], which owns the actual recognition state; this composable
 * just renders whatever it exposes. A mode switch at the top picks between
 * teaching primitives (Tier 1) and teaching/recognizing characters (Tier
 * 2) — the "Characters" option is disabled until at least one primitive's
 * been taught, the "shapes before letters" curriculum
 * [RecognizerViewModel] itself enforces. The LM-state overlay floats over
 * the canvas, available in both modes, so it can be toggled without
 * disrupting either mode's layout.
 */
@Composable
fun DrawingScreen(
    viewModel: RecognizerViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ModeSwitchRow(
            mode = viewModel.mode,
            charactersEnabled = viewModel.taughtPrimitiveLabels.isNotEmpty(),
            onSelectMode = viewModel::onSelectMode,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (viewModel.mode) {
                TeachMode.PRIMITIVES ->
                    DrawingCanvas(
                        strokes = listOfNotNull(viewModel.primitiveStroke),
                        onStrokeCompleted = viewModel::onPrimitiveStrokeCompleted,
                        enabled = viewModel.primitiveStroke == null,
                        modifier = Modifier.fillMaxSize(),
                    )

                TeachMode.CHARACTERS ->
                    DrawingCanvas(
                        strokes = viewModel.strokes,
                        onStrokeCompleted = viewModel::onStrokeCompleted,
                        enabled = viewModel.result == null && !viewModel.isEndingCharacter,
                        primitiveOverlays = viewModel.primitiveOverlays,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
            val context = LocalContext.current
            Column(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                LmStateToggleButton(expanded = viewModel.showLmState, onToggle = viewModel::onToggleLmState)
                Spacer(Modifier.height(8.dp))
                CaptureControls(
                    enabled = viewModel.captureEnabled,
                    capturedCount = viewModel.capturedExamples.size,
                    onToggle = viewModel::onToggleCapture,
                    onExport = {
                        exportCaptures(context, viewModel.capturedExamples)
                        viewModel.onCapturedExamplesExported()
                    },
                )
                if (viewModel.showLmState) {
                    Spacer(Modifier.height(8.dp))
                    LmStateOverlay(
                        currentPrimitives = viewModel.currentPrimitives,
                        learnedGraphs = viewModel.learnedGraphs,
                        taughtPrimitiveLabels = viewModel.taughtPrimitiveLabels,
                    )
                }
            }
        }
        when (viewModel.mode) {
            TeachMode.PRIMITIVES -> {
                EvidenceBars(evidence = viewModel.primitiveEvidence, modifier = Modifier.fillMaxWidth())
                PrimitiveTeachingPanel(
                    hasStroke = viewModel.primitiveStroke != null,
                    result = viewModel.primitiveResult,
                    taughtLabel = viewModel.taughtPrimitiveLabel,
                    onTeach = viewModel::onTeachPrimitive,
                    onConfirm = viewModel::onConfirmPrimitive,
                    onCorrect = viewModel::onCorrectPrimitive,
                    onRedo = viewModel::onPrimitiveRedo,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            TeachMode.CHARACTERS -> {
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
                    val canEdit = !viewModel.isEndingCharacter && viewModel.strokes.isNotEmpty()
                    DrawingToolbar(
                        onUndo = viewModel::onUndo,
                        onClear = viewModel::onClear,
                        onDone = viewModel::onDone,
                        canUndo = canEdit,
                        canClear = canEdit,
                        canDone = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Two plain buttons, not a Material3 segmented-button row — every other
 * control in this app ([DrawingToolbar], [RecognitionResultPanel],
 * [LmStateToggleButton]) already uses plain [Button]/[OutlinedButton], and
 * this is functionally one binary toggle, not a reason to introduce a new
 * visual idiom. The active mode renders filled, the inactive one outlined
 * — the same primary/secondary convention [RecognitionResultPanel]'s
 * Confirm/Correct buttons already use.
 */
@Composable
private fun ModeSwitchRow(
    mode: TeachMode,
    charactersEnabled: Boolean,
    onSelectMode: (TeachMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton(text = "Primitives", selected = mode == TeachMode.PRIMITIVES, enabled = true) {
                onSelectMode(TeachMode.PRIMITIVES)
            }
            ModeButton(text = "Characters", selected = mode == TeachMode.CHARACTERS, enabled = charactersEnabled) {
                onSelectMode(TeachMode.CHARACTERS)
            }
        }
        if (!charactersEnabled) {
            Text("Teach at least one primitive first", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(text) }
    }
}

/**
 * A debug affordance for Phase 5.5a (see IMPLEMENTATION_PLAN.md) — records
 * taught examples verbatim while [enabled], for later export as real
 * -handwriting calibration data. Off by default; this is recording the
 * user's own handwriting, so turning it on is a deliberate act, not
 * something the teach/recognize flow does silently.
 */
@Composable
private fun CaptureControls(
    enabled: Boolean,
    capturedCount: Int,
    onToggle: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        OutlinedButton(onClick = onToggle) {
            Text(if (enabled) "Capture: on ($capturedCount)" else "Capture: off")
        }
        if (capturedCount > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExport) { Text("Export $capturedCount examples") }
        }
    }
}
