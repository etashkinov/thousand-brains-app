package com.eta.tbp.lib.memory

/**
 * Translation- and order-tolerant matching between a stored
 * [GraphObjectModel] and a fresh candidate. Neither side is assumed to know
 * its own absolute position in the other's frame of reference — a hand-drawn
 * character can start anywhere in touch-space, and a city explorer doesn't
 * know their starting cell's true coordinate in the taught map either — so
 * every comparison first picks an anchor pair and re-bases every other
 * node's [Location] to it before comparing. Anchoring tries every
 * (stored node, candidate node) pair whose features are compatible, not
 * just one fixed candidate node: an automated random-order explorer
 * ([com.eta.tbp.lib.city.CityAutoExplorer]) can't guarantee the *first*
 * node it happens to observe is one the true matching city shares — if it
 * isn't, anchoring on it alone would permanently stall the whole episode
 * at zero evidence, since candidate nodes are never removed from the
 * buffer mid-episode. These graphs are a handful of nodes (a character's
 * primitives, a city's landmarks), so the full O(stored × candidate) anchor
 * search this requires costs nothing in absolute terms — correctness here
 * is worth more than a constant-factor saving that only matters at a scale
 * this app doesn't operate at.
 *
 * There is no assumption that nodes arrive in a fixed order either: unlike
 * a pen stroke's inherent draw order, a city can be explored one arbitrary
 * cell at a time ("not necessarily adjacent"), so once an anchor is fixed,
 * [findNodeNear] looks up each remaining candidate node's best-matching
 * stored node by proximity — feature-compatible and closest by
 * [Location.displacement]/[Location.magnitude] — rather than by position in
 * the sequence. [findNodeNear] is a plain tolerant nearest-match, not an
 * exact-equality index: these graphs are a handful of nodes (a character's
 * primitives, a city's landmarks), so a linear scan costs nothing, and
 * grading by distance rather than requiring exact equality is what lets a
 * jittery real-world observation (unlike a city cell's exact grid
 * coordinate) still match — the same universal-over-[Location] contract
 * [GraphMemory]'s merge averaging relies on.
 *
 * Everything here is generic over any [Location]/[Feature] pair — a node's
 * feature must not be an infinite [Feature.difference] from its candidate
 * match (a hard veto, e.g. a taught label mismatch) — so this object has
 * zero domain-specific code for either
 * [com.eta.tbp.lib.sensor.FloatLocation]/[com.eta.tbp.lib.sensor.PrimitiveFeature]
 * or [com.eta.tbp.lib.city.MapLocation]/[com.eta.tbp.lib.city.MapFeature].
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
        return target.nodes.map { targetNode ->
            val targetRelative = targetNode.location.displacement(anchor.targetNode.location)
            findNodeNear(candidate.nodes, anchor.candidateNode, targetRelative, targetNode.feature)?.node ?: candidate.nodes.first()
        }
    }

    /** [score] is computed once, while searching for the best anchor in [bestAnchor] — callers reuse it rather than re-scoring the chosen anchor a second time. */
    private data class Anchor(
        val targetNode: GraphNode,
        val candidateNode: GraphNode,
        val score: Float,
    )

    /** One [nodes] entry found near a query location, and how far off it was. */
    private data class NearestMatch(
        val node: GraphNode,
        val positionError: Float,
    )

    private fun anchoredScore(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
    ): Float = bestAnchor(target, candidate)?.score ?: NO_MATCH

    /** Tries every feature-compatible (target node, candidate node) pair as the shared reference point — see class doc for why every candidate node needs a turn, not just one. */
    private fun bestAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
    ): Anchor? {
        var best: Anchor? = null
        for (anchorCandidateNode in candidate) {
            for (targetNode in target) {
                if (targetNode.feature.difference(anchorCandidateNode.feature).isInfinite()) continue
                val score = scoreForAnchor(target, candidate, targetNode, anchorCandidateNode)
                if (best == null || score > best.score) {
                    best = Anchor(targetNode, anchorCandidateNode, score)
                }
            }
        }
        return best
    }

    /** Every [candidate] node against its own best-matching [target] node (via [findNodeNear]) once both sides are re-based to ([anchorTargetNode], [anchorCandidateNode]), averaged into one [0,1] score. */
    private fun scoreForAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
        anchorTargetNode: GraphNode,
        anchorCandidateNode: GraphNode,
    ): Float {
        var totalPositionError = 0f
        var totalFeatureDifference = 0f

        for (candidateNode in candidate) {
            val candidateRelative = candidateNode.location.displacement(anchorCandidateNode.location)
            val match =
                findNodeNear(target, anchorTargetNode, candidateRelative, candidateNode.feature)
                    ?: return NO_MATCH
            totalPositionError += match.positionError
            totalFeatureDifference += candidateNode.feature.difference(match.node.feature)
        }

        val n = candidate.size
        val positionScore = (1f - (totalPositionError / n) / MAX_POSITION_ERROR).coerceIn(0f, 1f)
        val featureScore = (1f - (totalFeatureDifference / n) / MAX_FEATURE_DIFFERENCE).coerceIn(0f, 1f)
        return (positionScore + featureScore) / 2f
    }

    /**
     * The feature-compatible entry in [nodes] whose location — re-based to
     * [nodesAnchor], the same way [relativeLocation] already is — sits
     * closest to [relativeLocation]. A plain linear scan (see class doc for
     * why that's the right call at this scale), tolerant of position error
     * rather than requiring exact equality. Null if nothing in [nodes] is
     * feature-compatible with [feature] at all.
     */
    private fun findNodeNear(
        nodes: List<GraphNode>,
        nodesAnchor: GraphNode,
        relativeLocation: Location,
        feature: Feature,
    ): NearestMatch? {
        var best: NearestMatch? = null
        for (node in nodes) {
            if (feature.difference(node.feature).isInfinite()) continue
            val nodeRelative = node.location.displacement(nodesAnchor.location)
            val positionError = relativeLocation.displacement(nodeRelative).magnitude()
            if (best == null || positionError < best.positionError) {
                best = NearestMatch(node, positionError)
            }
        }
        return best
    }
}
