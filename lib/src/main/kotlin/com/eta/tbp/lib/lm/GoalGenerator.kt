package com.eta.tbp.lib.lm

import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.PositionTolerance

/**
 * Mirrors real Monty's Goal State Generator (see `EvidenceGoalGenerator` in
 * `goal_generation.py`): once evidence narrows to a small
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
 * same way [GraphMatcher] itself is. [EvidenceGraphLM.proposeGoal]
 * is the only caller today, but this stays a free function (same idiom as
 * [possibleMatches]/[recognitionResult] below) so it's independently
 * testable without an [EvidenceGraphLM] instance.
 *
 * Deliberately narrow in scope, the same way [GraphMemory]'s merge-vs-spawn
 * decision is: a greedy "does any candidate's prediction disagree with the
 * others" check, not an information-theoretic optimum over every possible
 * next location — plenty for the handful of tied labels and landmarks this
 * app's scale ever produces.
 *
 * [positionTolerance] (default [PositionTolerance.EXACT]) is an explicit
 * parameter here rather than constructor config — this is a pure function,
 * not an object with a lifetime to configure — but its one real caller,
 * [EvidenceGraphLM.proposeGoal], doesn't take its own copy either: it
 * forwards [EvidenceGraphLM.positionTolerance] (see that property's doc, and
 * [PositionTolerance]'s own, for why the value lives there once rather than
 * being threaded through every layer). It governs only the
 * [checkedLocations] membership test — a candidate's own predicted
 * locations are still grouped by exact [Location] equality
 * (`predictions.flatten().groupBy { it.location }` below), so two tied
 * candidates' anchor-relative projections for what's conceptually the same
 * landmark can still land in separate buckets under real positional
 * jitter. That's a clustering problem, not a tolerance one — left as a
 * known follow-up rather than solved here.
 */
fun suggestGoalLocation(
    memory: GraphMemory,
    tiedLabels: List<String>,
    observedNodes: List<GraphNode>,
    checkedLocations: Set<Location>,
    positionTolerance: PositionTolerance = PositionTolerance.EXACT,
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

    val uncheckedPredictions =
        predictions.flatten().groupBy { it.location }.filterKeys { !positionTolerance.anyNear(checkedLocations, it) }
    if (uncheckedPredictions.isEmpty()) return null

    // A location only some of the tied candidates predict a node at, or predict different features at, can only help tell them apart.
    val disambiguating =
        uncheckedPredictions.entries.firstOrNull { (_, predictedNodesHere) ->
            predictedNodesHere.size < predictions.size || predictedNodesHere.map { it.feature }.distinct().size > 1
        }
    return (disambiguating ?: uncheckedPredictions.entries.first()).key
}
