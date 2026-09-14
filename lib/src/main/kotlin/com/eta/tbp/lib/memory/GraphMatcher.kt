package com.eta.tbp.lib.memory

/**
 * Translation- and order-tolerant matching between a stored
 * [GraphObjectModel] and a fresh candidate. Neither side is assumed to know
 * its own absolute position in the other's frame of reference — a hand-drawn
 * character can start anywhere in touch-space, and a city explorer doesn't
 * know their starting cell's true coordinate in the taught map either — so
 * every comparison first picks an anchor pair and re-bases every other
 * node's [Location] to it before comparing.
 *
 * Every observed node is scored, not just the ones a stored model has a
 * counterpart for: a node with no feature-compatible, nearby counterpart in
 * [stored] contributes [MIN_EVIDENCE] rather than being dropped or vetoing
 * the whole comparison — mirrors real Monty's own
 * `_calculate_evidence_for_new_locations`
 * (`evidence_matching/hypotheses_displacer.py`), which assigns evidence -1
 * to a hypothesis when nothing is within `max_match_distance` instead of
 * discarding the observation: an observation that doesn't fit a candidate
 * is itself evidence against it, not noise to filter out beforehand. See
 * [com.eta.tbp.lib.lm.EvidenceGraphLM]'s own class doc for why an earlier
 * version of this port got that wrong (dropping anything that didn't match
 * *any* taught object, globally, before scoring ever started) and what that
 * broke.
 *
 * Anchoring itself mirrors an actual journey: [candidate]'s nodes are tried
 * *in the order they arrived*, and the first one with any feature-compatible
 * counterpart anywhere in [target] becomes the anchor, full stop — the same
 * way a real explorer's first recognized landmark becomes their reference
 * point for interpreting everything else, not something they'd retroactively
 * swap out for a "better" one after the fact. [bestAnchor] only searches
 * *within [target]* for the best partner for that one fixed candidate node
 * (there can be several equally-compatible target nodes, e.g. two stored
 * "line" nodes); it never reconsiders a later candidate node as an
 * alternative anchor once an earlier one has already found a match. This
 * keeps the search to at most O(candidate + target) work — one pass to find
 * the anchor, one to score against it — rather than treating every
 * (candidate node, target node) pair as its own hypothesis to fully score
 * and compare, which would cost O(candidate² × target) for no real benefit
 * at this app's scale.
 *
 * A candidate node that isn't the fixed anchor but still has no
 * feature-compatible, nearby counterpart in [target] does still count
 * against the match (see [MIN_EVIDENCE] below) — only the anchor *choice*
 * itself is fixed to the first workable one, not the scoring of everything
 * after it.
 *
 * There is no assumption that nodes arrive in a fixed order either: unlike
 * a pen stroke's inherent draw order, a city can be explored one arbitrary
 * cell at a time ("not necessarily adjacent"), so once an anchor is fixed,
 * [findNodeNear] looks up each remaining candidate node's best-matching
 * stored node by proximity — feature-compatible, closest by
 * [Location.displacement]/[Location.magnitude], and within
 * [MAX_POSITION_ERROR] — rather than by position in the sequence.
 * [findNodeNear] is a plain tolerant nearest-match, not an exact-equality
 * index: these graphs are a handful of nodes (a character's primitives, a
 * city's landmarks), so a linear scan costs nothing, and grading by
 * distance rather than requiring exact equality is what lets a jittery
 * real-world observation (unlike a city cell's exact grid coordinate) still
 * match — the same universal-over-[Location] contract [GraphMemory]'s merge
 * averaging relies on.
 *
 * Everything here is generic over any [Location]/[Feature] pair — a node's
 * feature must not be an infinite [Feature.difference] from its candidate
 * match (a hard veto, e.g. a taught label mismatch) — so this object has
 * zero domain-specific code for either
 * [com.eta.tbp.lib.sensor.FloatLocation]/[com.eta.tbp.lib.sensor.PrimitiveFeature]
 * or [com.eta.tbp.lib.city.MapLocation]/[LabelFeature].
 */
object GraphMatcher {
    private const val NO_MATCH = 0f

    /** Mirrors real Monty's own `MIN_EVIDENCE` (`hypotheses_displacer.py`) — what an observation with no nearby, feature-compatible counterpart in a candidate contributes: disconfirming, not neutral. */
    private const val MIN_EVIDENCE = -1f

    // normalized-space units (Phase 1 normalizes to unit radius); a city's grid units play the same role.
    private const val MAX_POSITION_ERROR = 1f
    private const val MAX_FEATURE_DIFFERENCE = 1f // same scale as MAX_POSITION_ERROR, for a node's feature difference

    /** Full match: [stored] and [candidate] must have the same node count — used for deciding whether a freshly taught exemplar should merge into this one, where a wildly different node count is itself a good reason not to (see [GraphMemory.addOrMerge]). Unlike [partialMatchScore], a node count mismatch alone is disqualifying here, before any per-node scoring. */
    fun matchScore(
        stored: GraphObjectModel,
        candidate: GraphObjectModel,
    ): Float {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return NO_MATCH
        return anchoredScore(stored.nodes, candidate.nodes)
    }

    /**
     * Live/partial match: scores every one of [observedNodes] — however many
     * have been observed so far this episode, fewer than [stored]'s own node
     * count early on, or *more* (e.g. an observation [stored] has no
     * counterpart for at all) — against [stored]. See the class doc for why
     * an unexplained observation drags the score down instead of being
     * dropped or forcing an automatic zero; unlike [matchScore], there's no
     * node-count gate here at all, since a partial (or over-complete)
     * observation is the normal case, not a disqualifying one.
     */
    fun partialMatchScore(
        stored: GraphObjectModel,
        observedNodes: List<GraphNode>,
    ): Float {
        if (observedNodes.isEmpty() || stored.nodes.isEmpty()) return NO_MATCH
        return anchoredScore(stored.nodes, observedNodes)
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

    /**
     * Every node in [stored], translated into [observed]'s own frame of
     * reference via the best anchor pairing between the two — including
     * [stored] nodes that have no counterpart in [observed] yet, i.e. cells
     * not visited this episode. [com.eta.tbp.lib.lm.suggestGoalLocation]
     * uses this to predict where a leading hypothesis's still-unseen
     * landmarks should be, mirroring real Monty's own `EvidenceGoalGenerator`
     * projecting a hypothesis graph into the current sensed frame to find a
     * disambiguating point to move to next. Null under the same conditions
     * [bestAlignedWindow] is.
     */
    fun predictedLocations(
        stored: GraphObjectModel,
        observed: List<GraphNode>,
    ): List<GraphNode>? {
        if (stored.nodes.isEmpty() || observed.isEmpty()) return null
        val anchor = bestAnchor(stored.nodes, observed) ?: return null
        return stored.nodes.map { storedNode ->
            val relativeToAnchor = storedNode.location.displacement(anchor.targetNode.location)
            storedNode.copy(location = anchor.candidateNode.location.plus(relativeToAnchor))
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
    ): Float = (bestAnchor(target, candidate)?.score ?: NO_MATCH).coerceIn(0f, 1f)

    /** [candidate]'s nodes tried as the anchor in arrival order — the first one with any feature-compatible [target] counterpart wins; see class doc for why later candidate nodes are never reconsidered as alternative anchors once that happens. */
    private fun bestAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
    ): Anchor? {
        for (candidateNode in candidate) {
            val anchor = bestTargetPartnerFor(target, candidate, candidateNode)
            if (anchor != null) return anchor
        }
        return null
    }

    /** The best-scoring [target] node to pair [anchorCandidateNode] with — there can be several equally feature-compatible options (e.g. two stored "line" nodes) — or null if nothing in [target] is feature-compatible with it at all. */
    private fun bestTargetPartnerFor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
        anchorCandidateNode: GraphNode,
    ): Anchor? {
        var best: Anchor? = null
        for (targetNode in target) {
            if (targetNode.feature.difference(anchorCandidateNode.feature).isInfinite()) continue
            val score = evidenceForAnchor(target, candidate, targetNode, anchorCandidateNode)
            if (best == null || score > best.score) {
                best = Anchor(targetNode, anchorCandidateNode, score)
            }
        }
        return best
    }

    /** Every [candidate] node scored against its own best-matching [target] node (via [findNodeNear]) once both sides are re-based to ([anchorTargetNode], [anchorCandidateNode]) — [MIN_EVIDENCE] for one with no match at all — averaged into one score. */
    private fun evidenceForAnchor(
        target: List<GraphNode>,
        candidate: List<GraphNode>,
        anchorTargetNode: GraphNode,
        anchorCandidateNode: GraphNode,
    ): Float {
        var totalEvidence = 0f
        for (candidateNode in candidate) {
            val candidateRelative = candidateNode.location.displacement(anchorCandidateNode.location)
            val match = findNodeNear(target, anchorTargetNode, candidateRelative, candidateNode.feature)
            totalEvidence += match?.let { nodeEvidence(candidateNode, it) } ?: MIN_EVIDENCE
        }
        return totalEvidence / candidate.size
    }

    /** [MAX_POSITION_ERROR]/[MAX_FEATURE_DIFFERENCE]-normalized closeness of [candidateNode] to its [match], averaged into [0,1] — 1 only when both the position and the feature match exactly. */
    private fun nodeEvidence(
        candidateNode: GraphNode,
        match: NearestMatch,
    ): Float {
        val positionScore = (1f - match.positionError / MAX_POSITION_ERROR).coerceIn(0f, 1f)
        val featureScore = (1f - candidateNode.feature.difference(match.node.feature) / MAX_FEATURE_DIFFERENCE).coerceIn(0f, 1f)
        return (positionScore + featureScore) / 2f
    }

    /**
     * The feature-compatible entry in [nodes], within [MAX_POSITION_ERROR],
     * whose location — re-based to [nodesAnchor], the same way
     * [relativeLocation] already is — sits closest to [relativeLocation]. A
     * plain linear scan (see class doc for why that's the right call at this
     * scale), tolerant of position error rather than requiring exact
     * equality. Null if nothing in [nodes] is feature-compatible with
     * [feature] at all, or the nearest compatible entry is still further
     * than [MAX_POSITION_ERROR] away — mirrors real Monty's own
     * `max_match_distance` radius check (see class doc).
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
        return best?.takeIf { it.positionError <= MAX_POSITION_ERROR }
    }
}
