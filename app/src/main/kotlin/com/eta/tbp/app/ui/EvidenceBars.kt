package com.eta.tbp.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Live per-label match evidence ([com.eta.tbp.lib.lm.CharacterGraphLM.evidenceSnapshot]),
 * rendered as horizontal bars sorted by descending confidence. Renders
 * nothing before anything's been drawn or taught.
 */
@Composable
fun EvidenceBars(
    evidence: Map<String, Float>,
    modifier: Modifier = Modifier,
) {
    if (evidence.isEmpty()) return

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        for ((label, value) in evidence.entries.sortedByDescending { it.value }) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, modifier = Modifier.width(48.dp))
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(12.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(value.coerceIn(0f, 1f))
                                .height(12.dp)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text("${(value * 100).roundToInt()}%", modifier = Modifier.width(44.dp))
            }
        }
    }
}
