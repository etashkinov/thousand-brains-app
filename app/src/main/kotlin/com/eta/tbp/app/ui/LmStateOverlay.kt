package com.eta.tbp.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eta.tbp.lib.lm.PrimitiveType
import com.eta.tbp.lib.memory.GraphObjectModel

/** Small always-visible toggle for [LmStateOverlay] — a debug affordance, not part of the teach/recognize flow itself. */
@Composable
fun LmStateToggleButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onToggle, modifier = modifier) {
        Text(if (expanded) "Hide LM state" else "LM state")
    }
}

/**
 * Debug overlay showing both tiers' current internal state, direct from
 * their own introspection queries (`CharacterGraphLM.currentNodes()`,
 * `GraphMemory.snapshot()`) rather than anything carried by a `CmpMessage`
 * — the same "read the LM's state directly" spirit as the live evidence
 * bars (see IMPLEMENTATION_PLAN.md §3.4's note on [evidenceSnapshot]).
 *
 * @param currentPrimitives Tier 1's output for the episode so far, in order.
 * @param learnedGraphs every taught label's learned [GraphObjectModel] variants.
 */
@Composable
fun LmStateOverlay(
    currentPrimitives: List<PrimitiveType>,
    learnedGraphs: Map<String, List<GraphObjectModel>>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .padding(12.dp)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Text("LM state", style = MaterialTheme.typography.titleSmall)

            Spacer(Modifier.heightIn(min = 8.dp))
            Text("Primitives this episode (Tier 1)", style = MaterialTheme.typography.labelMedium)
            Text(
                if (currentPrimitives.isEmpty()) "(none yet)" else currentPrimitives.joinToString(" → ") { it.name },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.heightIn(min = 8.dp))
            Text("Graphs learned (Tier 2)", style = MaterialTheme.typography.labelMedium)
            if (learnedGraphs.isEmpty()) {
                Text("(none yet)", style = MaterialTheme.typography.bodySmall)
            } else {
                for ((label, variants) in learnedGraphs) {
                    Text(
                        "$label — ${variants.size} variant${if (variants.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    variants.forEachIndexed { index, variant ->
                        val sequence = variant.nodes.joinToString(" → ") { it.primitiveType.name }
                        Text(
                            "  #$index: $sequence (x${variant.exemplarCount})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
