package com.eta.tbp.lib.memory

/**
 * Translation- and order-tolerant matching between a stored
 * [GraphObjectModel] and a fresh candidate. Neither side is assumed to know
 * its own absolute position in the other's frame of reference — a hand-drawn
 * character can start anywhere in touch-space, and a city explorer doesn't
 * know their starting cell's true coordinate in the taught map either — so
 * every comparison first picks an anchor pair (one stored node, one
 * candidate node, feature-compatible) and re-bases every other node's
 * [Location] to that anchor before comparing, trying every anchor pair and
 * keeping the best-scoring one. There is no assumption that nodes arrive in
 * a fixed order either: unlike a pen stroke's inherent draw order, a city
 * can be explored one arbitrary cell at a time ("not necessarily
 * adjacent"), so a candidate node is matched against whichever stored node
 * it best corresponds to under the anchor, not against a fixed position in
 * the sequence — a genuine domain-driven divergence from a simpler
 * fixed-order comparison, not an invented one (see this project's "default
 * to Monty's own approach" rule).
 *
 * Node comparison is generic over any [Location]/[Feature] pair: a node's
 * feature must not be an infinite [Feature.difference] from its candidate
 * match (a hard veto, e.g. a taught label mismatch), and position agreement
 * is scored via [Location.displacement]/[Location.magnitude] — both
 * [com.eta.tbp.lib.sensor.FloatLocation]/[com.eta.tbp.lib.sensor.PrimitiveFeature]
 * and [com.eta.tbp.lib.city.MapLocation]/[com.eta.tbp.lib.city.MapFeature]
 * implement, so this object has zero domain-specific code for either tier.
 */
object GraphMatcher {
    private const val NO_MATCH = 0f

    // normalized-space units (Phase 1 normalizes to unit radius); a city's grid units play the same role.
    private const val MAX_POSITION_ERROR = 1f
    private const val MAX_FEATURE_DIFFERENCE = 1f // same scale as MAX_POSITION_ERROR, for a node's feature difference

    /** Full match: [stored] and [candidate] must have the same node count. */
    fun matchScore(
        stored: GraphObjectModel,
        candidate: GraphObjectModel,
    ): Float {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return NO_MATCH
        return anchoredScore(stored.nodes, candidate.nodes)
    }

    /**
     * Live/partial match: scores how well [partialNodes] (fewer nodes than
     * the full stored model — a shape mid-stroke, or a city only partly
     * explored) corresponds to some subset of [stored]'s nodes.
     */
    fun partialMatchScore(
        stored: GraphObjectModel,
        partialNodes: List<GraphNode>,
    ): Float {
        if (partialNodes.isEmpty() || partialNodes.size > stored.nodes.size) return NO_MATCH
        return anchoredScore(stored.nodes, partialNodes)
    }

    /**
     * For each of [target]'s own nodes, the best-matching node in
     * [candidate] under the anchor pairing that scores [candidate] highest
     * against [target] — same length and order as [target].nodes, so
     * [GraphMemory] can zip the two node lists to average a merge. A greedy
     * nearest-match per target node, not a strict one-to-one assignment
     * (same simplification spirit as [GraphMemory]'s merge-vs-spawn
     * decision vs. Monty's own k-steps/exponential version — not worth
     * exact bipartite matching at the handful of nodes this app deals in).
     * Null if [target] or [candidate] has no nodes, or nothing in
     * [candidate] is feature-compatible with anything in [target].
     */
    fun bestAlignedWindow(
        target: GraphObjectModel,
        candidate: GraphObjectModel,
    ): List<GraphNode>? {
        if (target.nodes.isEmpty() || candidate.nodes.isEmpty()) return null
        val anchor = bestAnchor(target.nodes, candidate.nodes) ?: return null
        return target.nodes.map { targetNode -> bestMatchFor(targetNode, candidate.nodes, anchor) }
    }

    private data class Anchor(
        val targetNode: GraphNode,
        val candidateNode: GraphNode,
    )

    private fun anchoredScore(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
    ): Float {
        val anchor = bestAnchor(target, candidate) ?: return NO_MATCH
        return scoreForAnchor(target, candidate, anchor)
    }

    /** Tries every feature-compatible (target node, candidate node) pair as the shared reference point, keeping whichever scores [candidate] highest overall. */
    private fun bestAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
    ): Anchor? {
        var best: Anchor? = null
        var bestScore = NO_MATCH
        for (targetNode in target) {
            for (candidateNode in candidate) {
                if (targetNode.feature.difference(candidateNode.feature).isInfinite()) continue
                val anchor = Anchor(targetNode, candidateNode)
                val score = scoreForAnchor(target, candidate, anchor)
                if (score > bestScore) {
                    bestScore = score
                    best = anchor
                }
            }
        }
        return best
    }

    /** Every [candidate] node against its own best-matching [target] node once both sides are re-based to [anchor], averaged into one [0,1] score. */
    private fun scoreForAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
        anchor: Anchor,
    ): Float {
        var totalPositionError = 0f
        var totalFeatureDifference = 0f

        for (candidateNode in candidate) {
            val candidateRelative = candidateNode.location.displacement(anchor.candidateNode.location)
            var bestPositionError = Float.POSITIVE_INFINITY
            var bestFeatureDifference = Float.POSITIVE_INFINITY

            for (targetNode in target) {
                val featureDifference = candidateNode.feature.difference(targetNode.feature)
                if (featureDifference.isInfinite()) continue
                val targetRelative = targetNode.location.displacement(anchor.targetNode.location)
                val positionError = candidateRelative.displacement(targetRelative).magnitude()
                if (positionError < bestPositionError) {
                    bestPositionError = positionError
                    bestFeatureDifference = featureDifference
                }
            }

            if (bestPositionError.isInfinite()) return NO_MATCH
            totalPositionError += bestPositionError
            totalFeatureDifference += bestFeatureDifference
        }

        val n = candidate.size
        val positionScore = (1f - (totalPositionError / n) / MAX_POSITION_ERROR).coerceIn(0f, 1f)
        val featureScore = (1f - (totalFeatureDifference / n) / MAX_FEATURE_DIFFERENCE).coerceIn(0f, 1f)
        return (positionScore + featureScore) / 2f
    }

    private fun bestMatchFor(
        targetNode: GraphNode,
        candidate: List<GraphNode>,
        anchor: Anchor,
    ): GraphNode {
        val targetRelative = targetNode.location.displacement(anchor.targetNode.location)
        return candidate.minBy { candidateNode ->
            val featureDifference = targetNode.feature.difference(candidateNode.feature)
            if (featureDifference.isInfinite()) {
                Float.POSITIVE_INFINITY
            } else {
                val candidateRelative = candidateNode.location.displacement(anchor.candidateNode.location)
                targetRelative.displacement(candidateRelative).magnitude()
            }
        }
    }
}
