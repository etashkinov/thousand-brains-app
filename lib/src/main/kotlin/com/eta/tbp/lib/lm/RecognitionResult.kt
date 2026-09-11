package com.eta.tbp.lib.lm

/**
 * The three-way outcome a teach/recognize UI branches on. Mirrors Monty's
 * own `possible_matches`-driven terminal states
 * (`evidence_matching/learning_module.py`), not an app-invented concept:
 * zero possible matches is Monty's own "no_match" terminal state, one is a
 * normal convergence, and 2+ is Monty's own multi-hypothesis case — an app
 * surfaces that last case as a question instead of forcing a guess.
 *
 * Generic over which tier's evidence produced it: [EvidenceGraphLM] and
 * [PrimitiveGraphLM] both reduce a plain `Map<String, Float>` evidence
 * snapshot down to one of these via [recognitionResult] below — there's
 * nothing character-specific about the type itself.
 */
sealed class RecognitionResult {
    object Unknown : RecognitionResult()

    data class Recognized(
        val label: String,
        val confidence: Float,
    ) : RecognitionResult()

    data class Ambiguous(
        val labels: List<String>,
        val evidence: Map<String, Float>,
    ) : RecognitionResult()
}

/**
 * Labels within [xPercentThreshold]% of [evidence]'s max — a simplified
 * port of Monty's `get_possible_matches()`/`_threshold_possible_matches()`
 * (`evidence_matching/learning_module.py`): straight percent-of-max
 * thresholding, dropping Monty's own mean/std branching and its
 * `len(graph_memory) == 1` special case (not needed at this evidence
 * scale — same simplification spirit as
 * [com.eta.tbp.lib.memory.GraphMemory.detectNewObject] vs. Monty's
 * k-steps/exponential version). Strict `>` matches Monty's own `ge > th`
 * exactly. 0 results means no match (Monty's "no_match" terminal state), 1
 * a confident recognition, 2+ a genuine tie. A pure function of any
 * evidence map, so both tiers share the identical thresholding logic
 * rather than each reimplementing it.
 */
fun possibleMatches(
    evidence: Map<String, Float>,
    xPercentThreshold: Float = 10f,
): List<String> {
    if (evidence.isEmpty()) return emptyList()
    val maxEvidence = evidence.values.max()
    val threshold = if (maxEvidence > 0f) maxEvidence - (maxEvidence * xPercentThreshold / 100f) else 0f
    return evidence.filter { it.value > threshold }.keys.toList()
}

/** Combines [possibleMatches] and [evidence] into the three-way UI decision. */
fun recognitionResult(
    evidence: Map<String, Float>,
    xPercentThreshold: Float = 10f,
): RecognitionResult {
    val matches = possibleMatches(evidence, xPercentThreshold)
    return when (matches.size) {
        0 -> RecognitionResult.Unknown
        1 -> RecognitionResult.Recognized(matches.single(), evidence.getValue(matches.single()))
        else -> RecognitionResult.Ambiguous(matches, evidence)
    }
}
