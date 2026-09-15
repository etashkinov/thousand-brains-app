package com.eta.tbp.lib.lm

/**
 * The LM's belief about what's being explored, as of some point mid-episode
 * — the same three-way [possibleMatches] split [RecognitionResult] itself
 * reduces to, just without [RecognitionResult.Recognized]'s
 * [EvidenceGraphLM]-internal minimum-observations gate (see
 * `EvidenceGraphLM.recognitionResult`'s own doc for why that gate exists):
 * a caller coloring the explorer's path (e.g. the web UI's grid) wants
 * "down to one candidate" the moment evidence says so, not delayed until
 * enough observations have piled up to call it a final, confident
 * recognition. [LmDecision.state] is exactly this, attached to each
 * decision as [EvidenceGraphLM] makes it.
 */
sealed class HypothesisState {
    /** Nothing taught explains what's been observed so far this episode. */
    object NoMatch : HypothesisState()

    /** Still explains more than one taught label — [labels] is every one of them, same set [RecognitionResult.Ambiguous.labels] would carry. */
    data class Tied(
        val labels: List<String>,
    ) : HypothesisState()

    /** Down to exactly one taught label that isn't yet ruled out. */
    data class Confirmed(
        val label: String,
    ) : HypothesisState()
}

/** [possibleMatches] reduced to a [HypothesisState] — the same thresholding [recognitionResult] itself uses, shared here so [EvidenceGraphLM] never computes this mapping more than once. */
fun hypothesisStateOf(possibleMatches: List<String>): HypothesisState =
    when (possibleMatches.size) {
        0 -> HypothesisState.NoMatch
        1 -> HypothesisState.Confirmed(possibleMatches.single())
        else -> HypothesisState.Tied(possibleMatches)
    }
