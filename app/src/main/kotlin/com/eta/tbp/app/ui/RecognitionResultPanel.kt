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
 * The teach/recognize/disambiguate panel shown once a character's "Done"
 * has been pressed — one of three variants driven by [RecognitionResult],
 * mirroring Monty's own possible-matches-driven terminal states (see
 * IMPLEMENTATION_PLAN.md §3.4/§3.6): zero matches is a teach prompt, one is
 * a confirm/correct prompt, two-or-more is a disambiguation question, never
 * a forced guess. [taughtLabel] takes priority over [result] once set — a
 * label has already been committed for this character and we're just
 * waiting for "Next".
 */
@Composable
fun RecognitionResultPanel(
    result: RecognitionResult?,
    taughtLabel: String?,
    onTeach: (String) -> Unit,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
    onNext: () -> Unit,
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
                taughtLabel != null -> TaughtConfirmation(taughtLabel, onNext)
                result is RecognitionResult.Unknown -> UnknownPanel(onTeach)
                result is RecognitionResult.Recognized ->
                    RecognizedPanel(result.label, result.confidence, onConfirm, onCorrect)
                result is RecognitionResult.Ambiguous -> AmbiguousPanel(result.labels, onTeach)
                else -> Unit
            }
        }
    }
}

@Composable
private fun TaughtConfirmation(
    label: String,
    onNext: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Taught '$label'")
        Button(onClick = onNext) { Text("Next") }
    }
}

@Composable
private fun UnknownPanel(onTeach: (String) -> Unit) {
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
        Button(onClick = { onTeach(label) }, enabled = label.isNotBlank()) { Text("Teach") }
    }
}

@Composable
private fun RecognizedPanel(
    label: String,
    confidence: Float,
    onConfirm: () -> Unit,
    onCorrect: (String) -> Unit,
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
        }
    }
}

@Composable
private fun AmbiguousPanel(
    labels: List<String>,
    onTeach: (String) -> Unit,
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
