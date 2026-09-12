package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.GraphNode

/**
 * Mirrors [com.eta.tbp.lib.orchestrator.MontyOrchestrator]'s
 * observe-SM-then-LM step loop for the city domain, minus the segmentation
 * search that tier needs: [visit] steps [sensorModule] once per cell and
 * feeds the resulting message straight into [lm], since a city cell is
 * already one resolved observation (see [CitySensorModule]'s doc).
 *
 * "Episode" here is one exploration of a city — however many cells [visit]
 * is called for, in whatever order, ending at [endExploration] — the same
 * episode-boundary idiom [lm] itself already uses for a character. Cells
 * don't need to be adjacent or visited in any particular order:
 * [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]'s translation- and
 * order-tolerant matching is exactly what makes that safe — the explorer
 * never needs to know their own coordinate in the taught city's frame, only
 * their moves relative to wherever they started.
 *
 * A [RecognitionResult.Unknown] after [endExploration] means no taught city
 * explains what was observed — the caller can [teach] a new label to define
 * one. [RecognitionResult.Ambiguous] means multiple taught cities still fit
 * everything observed so far — the caller should keep exploring (more
 * [visit] calls, then a fresh [endExploration]) rather than guess.
 *
 * [logger] logs only what's unique to this layer — the move itself and the
 * episode's start/end — not [sensorModule]'s or [lm]'s own events, which
 * each already log themselves if given the same [Logger]. Defaults to
 * [Logger.None], same as every class in this pipeline.
 */
class CityExplorer(
    private val sensorModule: CitySensorModule,
    private val lm: EvidenceGraphLM,
    private val logger: Logger = Logger.Console,
) {
    fun beginExploration() {
        lm.preEpisode()
        sensorModule.preEpisode()
        logger.debug(TAG) { "exploration started" }
    }

    /** Moves to [location] (not necessarily adjacent to the last one) and folds its observed feature into the current exploration. */
    fun visit(location: MapLocation) {
        logger.debug(TAG) { "visiting $location" }
        lm.matchingStep(listOf(sensorModule.step(location)))
    }

    fun endExploration(): RecognitionResult {
        sensorModule.postEpisode()
        lm.postEpisode()
        val result = lm.recognitionResult()
        logger.info(TAG) { "exploration ended: $result" }
        return result
    }

    /** Labels the just-ended exploration as [label] — defines a new city, or merges into an existing one taught under the same label. */
    fun teach(label: String) = lm.teach(label)

    /** The full evidence breakdown across every taught city — for direct introspection, same spirit as [EvidenceGraphLM.evidenceSnapshot]. */
    fun evidenceSnapshot(): Map<String, Float> = lm.evidenceSnapshot()

    /**
     * A live look at the recognition state so far this exploration —
     * unlike [endExploration], this doesn't end the episode, so
     * [CityExperiment] can check it after every single [visit] and keep
     * exploring on anything other than a unique [RecognitionResult.Recognized].
     */
    fun currentResult(): RecognitionResult = lm.recognitionResult()

    /** The cells observed so far this exploration, in visit order — direct introspection, same spirit as [EvidenceGraphLM.currentNodes]. */
    fun currentNodes(): List<GraphNode> = lm.currentNodes()

    /**
     * Where [lm] suggests looking next to tell its currently tied
     * hypotheses apart — see [EvidenceGraphLM.suggestNextLocation]. The
     * cast is safe: every [com.eta.tbp.lib.memory.Location] [lm] has ever
     * been given by [sensorModule] is a [MapLocation], so anything it
     * predicts back is one too. Null under the same conditions
     * [EvidenceGraphLM.suggestNextLocation] is — [CityExperiment] falls
     * back to its own random pick in that case.
     */
    fun suggestNextLocation(): MapLocation? = lm.suggestNextLocation() as? MapLocation

    private companion object {
        const val TAG = "CityExplorer"
    }
}
