package com.eta.tbp.lib.memory

import com.eta.tbp.lib.util.AlignmentSearch
import com.eta.tbp.lib.util.alignmentScore

/**
 * Order- and direction-tolerant matching between a stored [GraphObjectModel]
 * and a fresh candidate — the user may start drawing from a different
 * primitive than they did when teaching, or trace the same shape starting
 * from the other end, and neither should count as a different character
 * (same spirit as the $1 Unistroke Recognizer this project's README cites).
 *
 * Every node's angle is re-baselined to its own sequence's first node
 * before comparing, rather than compared as stored: a node's
 * [GraphNode.absoluteAngle] is only meaningful relative to *some* fixed
 * reference, and which node is "first" changes with the alignment being
 * tried. Re-baselining also absorbs direction reversal for free — reading
 * a stored sequence backward and re-baselining it lands on the same
 * relative-angle sequence a genuine reverse retrace would reconstruct,
 * without needing to special-case the turn-angle sign.
 *
 * The offset/direction search itself lives in [AlignmentSearch] — this
 * object only supplies [alignmentScore], the per-node comparison generic
 * over any [EvidenceFeature] node payload, so
 * [com.eta.tbp.lib.lm.PrimitiveGraphLM] can reuse the same search over its
 * own, differently-shaped node type without both tiers sharing one schema.
 */
object GraphMatcher {
    private const val NO_MATCH = 0f
    private const val MAX_POSITION_ERROR = 1f // normalized-space units (Phase 1 normalizes to unit radius)
    private const val MAX_FEATURE_DIFFERENCE = 1f // same normalized-space scale as MAX_POSITION_ERROR, for a node's feature difference

    /** Full match: [stored] and [candidate] must have the same node count. */
    fun <F : EvidenceFeature<F>> matchScore(
        stored: GraphObjectModel<F>,
        candidate: GraphObjectModel<F>,
    ): Float {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return NO_MATCH
        return AlignmentSearch.bestAlignment(stored.nodes, candidate.nodes, ::alignmentScore).score
    }

    /**
     * Live/partial match: scores how well [partialNodes] (the shape-so-far,
     * mid-stroke) tracks some contiguous window of [stored]'s nodes.
     */
    fun <F : EvidenceFeature<F>> partialMatchScore(
        stored: GraphObjectModel<F>,
        partialNodes: List<GraphNode<F>>,
    ): Float {
        if (partialNodes.isEmpty() || partialNodes.size > stored.nodes.size) return NO_MATCH
        return AlignmentSearch.bestAlignment(stored.nodes, partialNodes, ::alignmentScore).score
    }

    /**
     * The best-scoring window of [stored]'s nodes, in [candidate]'s own
     * order/direction — what [GraphMemory] merges [candidate] into node by
     * node. Null if the node counts don't match.
     */
    fun <F : EvidenceFeature<F>> bestAlignedWindow(
        stored: GraphObjectModel<F>,
        candidate: GraphObjectModel<F>,
    ): List<GraphNode<F>>? {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return null
        return AlignmentSearch.bestAlignment(stored.nodes, candidate.nodes, ::alignmentScore).window
    }

    /**
     * 0f if either aligned pair's feature [EvidenceFeature.difference] is infinite
     * (e.g. the primitives' taught labels don't match) — same hard-veto behavior as
     * before, now derived from one [EvidenceFeature.difference] call instead of a
     * separate boolean gate.
     */
    private fun <F : EvidenceFeature<F>> alignmentScore(
        window: List<GraphNode<F>>,
        target: List<GraphNode<F>>,
    ): Float =
        alignmentScore(
            window,
            target,
            angleOf = { it.absoluteAngle },
            positionOf = { it.location },
            gate = { a, b -> a.feature.difference(b.feature).isFinite() },
            maxPositionError = MAX_POSITION_ERROR,
            extraTerm = { a, b -> (1f - a.feature.difference(b.feature) / MAX_FEATURE_DIFFERENCE).coerceIn(0f, 1f) },
        )
}
