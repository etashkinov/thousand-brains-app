package com.eta.tbp.lib.lm

import com.eta.tbp.lib.memory.Location

/**
 * One step of [EvidenceGraphLM]'s own reasoning, in the order it happened —
 * exposed the same spirit as [EvidenceGraphLM.evidenceSnapshot]: a caller
 * (the web UI's exploration panel, say) reads this structured trail instead
 * of scraping [com.eta.tbp.lib.log.Logger] output, which stays free-form
 * debug text for a human at a console, not something a UI parses. Mirrors
 * real Monty's own decision points here — `set_detected_object`'s terminal
 * state, `_threshold_possible_matches`'s possible-matches transitions
 * (`evidence_matching/learning_module.py`) — just captured as data instead
 * of only a `logger.debug`/`logger.info` call, so this app's UI (not only a
 * console) can show what changed and why.
 *
 * [location] is null for a decision that isn't about a specific visit — a
 * goal proposal names where [EvidenceGraphLM] wants to look *next*, not a
 * place already checked.
 *
 * [state] is the LM's overall belief right after this decision — the same
 * value a caller coloring a path of visited locations would want at every
 * step (see [Explorer.visitedLocationsWithState]), attached here too so a
 * UI listing decisions (not just cells) can color each line the same way
 * without re-deriving it from [message]'s free-form text.
 */
data class LmDecision(
    val step: Int,
    val location: Location?,
    val message: String,
    val state: HypothesisState,
)
