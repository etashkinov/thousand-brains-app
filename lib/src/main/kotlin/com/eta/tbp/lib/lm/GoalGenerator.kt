package com.eta.tbp.lib.lm

import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.Location

/**
 * Mirrors real Monty's Goal State Generator (see `EvidenceGoalGenerator` in
 * `evidence_matching/learning_module.py`): once evidence narrows to a small
 * set of tied hypotheses, it proposes *where* to look next to best tell
 * them apart, rather than a caller having to wander randomly. Real Monty
 * computes this as the point of maximum disagreement between the top-2
 * hypotheses' graphs (a nearest-neighbor mismatch, computed in the
 * runner-up's own reference frame); this mirrors that shape: project every
 * tied candidate's *entire* node set — including nodes nobody's observed
 * yet — into [observedNodes]'s own frame via [GraphMatcher.predictedLocations],
 * then look for an unchecked location where the candidates disagree (a
 * feature only some of them predict there).
 *
 * A pure function of [memory] and what's already been observed/checked —
 * generic over whatever [Location]/[Feature][com.eta.tbp.lib.memory.Feature]
 * pair the caller's domain uses (a city's discrete [com.eta.tbp.lib.city.MapLocation]
 * or a stroke's continuous [com.eta.tbp.lib.sensor.FloatLocation] alike), the
 * same way [GraphMatcher] itself is. [EvidenceGraphLM.suggestNextLocation]
 * is the only caller today, but this stays a free function (same idiom as
 * [possibleMatches]/[recognitionResult] below) so it's independently
 * testable without an [EvidenceGraphLM] instance.
 *
 * Deliberately narrow in scope, the same way [GraphMemory]'s merge-vs-spawn
 * decision is: a greedy "does any candidate's prediction disagree with the
 * others" check, not an information-theoretic optimum over every possible
 * next location — plenty for the handful of tied labels and landmarks this
 * app's scale ever produces.
 */
fun suggestGoalLocation(
    memory: GraphMemory,
    tiedLabels: List<String>,
    observedNodes: List<GraphNode>,
    checkedLocations: Set<Location>,
): Location? {
    if (tiedLabels.size < 2) return null

    val predictions =
        tiedLabels.mapNotNull { label ->
            memory
                .candidatesForLabel(label)
                .maxByOrNull { GraphMatcher.partialMatchScore(it, observedNodes) }
                ?.let { bestVariant -> GraphMatcher.predictedLocations(bestVariant, observedNodes) }
        }
    if (predictions.size < 2) return null

    val uncheckedPredictions = predictions.flatten().groupBy { it.location }.filterKeys { it !in checkedLocations }
    if (uncheckedPredictions.isEmpty()) return null

    // A location only some of the tied candidates predict a node at, or predict different features at, can only help tell them apart.
    val disambiguating =
        uncheckedPredictions.entries.firstOrNull { (_, predictedNodesHere) ->
            predictedNodesHere.size < predictions.size || predictedNodesHere.map { it.feature }.distinct().size > 1
        }
    return (disambiguating ?: uncheckedPredictions.entries.first()).key
}
