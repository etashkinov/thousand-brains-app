package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eta.tbp.lib.lm.RecognitionResult
import kotlin.math.roundToInt

/**
 * Bottom panel for primitive-teaching mode — the same three-way
 * recognize/confirm/correct/disambiguate shape [RecognitionResultPanel]
 * already uses for characters, driven by the same [RecognitionResult],
 * plus a "Redo" affordance on every branch (discard the drawn stroke,
 * redraw) that character mode has no equivalent for: there, discarding a
 * completed character isn't possible once "Done" is pressed, but a
 * primitive is a single stroke, cheap to redraw, and this app already
 * treats a bad drawing as always worth letting the user simply try again.
 * There's no explicit "Next": teaching immediately clears the stroke, and
 * [taughtLabel]'s confirmation is dismissed by drawing the next one
 * (`RecognizerViewModel.onPrimitiveStrokeCompleted` clears it) rather than
 * needing its own button.
 */
@Composable
fun PrimitiveTeachingPanel(
    result: RecognitionResult?,
    taughtLabel: String?,
    onTeach: (String) -> Unit,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 4.dp) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            when {
                taughtLabel != null -> Text("Taught '$taughtLabel' — draw another to keep teaching")
                result is RecognitionResult.Unknown -> UnknownPrimitivePanel(onTeach, onRedo)
                result is RecognitionResult.Recognized ->
                    RecognizedPrimitivePanel(result.label, result.confidence, onConfirm, onCorrect, onRedo)
                result is RecognitionResult.Ambiguous -> AmbiguousPrimitivePanel(result.labels, onTeach, onRedo)
                else -> Text("Draw one primitive shape (a line, an arc, ...)")
            }
        }
    }
}

@Composable
private fun UnknownPrimitivePanel(
    onTeach: (String) -> Unit,
    onRedo: () -> Unit,
) {
    var label by remember { mutableStateOf("") }

    Text("New to me — what is this?")
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedButton(onClick = onRedo) { Text("Redo") }
        Button(onClick = { onTeach(label) }, enabled = label.isNotBlank()) { Text("Teach") }
    }
}

@Composable
private fun RecognizedPrimitivePanel(
    label: String,
    confidence: Float,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
    onRedo: () -> Unit,
) {
    var correcting by remember { mutableStateOf(false) }
    var correctedLabel by remember { mutableStateOf("") }

    Text("Is this '$label'? (${(confidence * 100).roundToInt()}%)")
    if (correcting) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = correctedLabel,
                onValueChange = { correctedLabel = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { onCorrect(correctedLabel) }, enabled = correctedLabel.isNotBlank()) { Text("Submit") }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onConfirm) { Text("Confirm") }
            OutlinedButton(onClick = { correcting = true }) { Text("Correct") }
            OutlinedButton(onClick = onRedo) { Text("Redo") }
        }
    }
}

@Composable
private fun AmbiguousPrimitivePanel(
    labels: List<String>,
    onTeach: (String) -> Unit,
    onRedo: () -> Unit,
) {
    var somethingElse by remember { mutableStateOf("") }

    Text("Which is it?")
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (label in labels) {
            Button(onClick = { onTeach(label) }) { Text(label) }
        }
        OutlinedButton(onClick = onRedo) { Text("Redo") }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = somethingElse,
            onValueChange = { somethingElse = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("Something else") },
        )
        Button(onClick = { onTeach(somethingElse) }, enabled = somethingElse.isNotBlank()) { Text("Teach") }
    }
}
