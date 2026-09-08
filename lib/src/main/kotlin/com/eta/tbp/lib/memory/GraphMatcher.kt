package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.util.AlignmentSearch
import com.eta.tbp.lib.util.alignmentScore
import kotlin.math.abs

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
 * object only supplies [alignmentScore], the per-node comparison specific
 * to a sparse character-level [GraphNode] sequence, so
 * [com.eta.tbp.lib.lm.PrimitiveGraphLM] can reuse the same search over its
 * own, differently-shaped node type without both tiers sharing one schema.
 */
object GraphMatcher {
    private const val NO_MATCH = 0f
    private const val MAX_POSITION_ERROR = 1f // normalized-space units (Phase 1 normalizes to unit radius)
    private const val MAX_LENGTH_ERROR = 1f // same normalized-space scale as MAX_POSITION_ERROR, for a primitive's extent

    /** Full match: [stored] and [candidate] must have the same node count. */
    fun matchScore(
        stored: GraphObjectModel,
        candidate: GraphObjectModel,
    ): Float {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return NO_MATCH
        return AlignmentSearch.bestAlignment(stored.nodes, candidate.nodes, ::alignmentScore).score
    }

    /**
     * Live/partial match: scores how well [partialNodes] (the shape-so-far,
     * mid-stroke) tracks some contiguous window of [stored]'s nodes.
     */
    fun partialMatchScore(
        stored: GraphObjectModel,
        partialNodes: List<GraphNode>,
    ): Float {
        if (partialNodes.isEmpty() || partialNodes.size > stored.nodes.size) return NO_MATCH
        return AlignmentSearch.bestAlignment(stored.nodes, partialNodes, ::alignmentScore).score
    }

    /**
     * The best-scoring window of [stored]'s nodes, in [candidate]'s own
     * order/direction — what [GraphMemory] merges [candidate] into node by
     * node. Null if the node counts don't match.
     */
    fun bestAlignedWindow(
        stored: GraphObjectModel,
        candidate: GraphObjectModel,
    ): List<GraphNode>? {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return null
        return AlignmentSearch.bestAlignment(stored.nodes, candidate.nodes, ::alignmentScore).window
    }

    /** 0f if the primitives' taught-label sequence doesn't match at this alignment. */
    private fun alignmentScore(
        window: List<GraphNode>,
        target: List<GraphNode>,
    ): Float =
        alignmentScore(
            window,
            target,
            angleOf = { it.absoluteAngle },
            positionOf = { it.location },
            gate = { a, b -> sameKind(a.measurement, b.measurement) },
            maxPositionError = MAX_POSITION_ERROR,
            extraTerm = { a, b -> sizeScore(a.measurement, b.measurement) },
        )

    /** True if [a] and [b] are the same taught primitive [PrimitiveMeasurement.label], regardless of their measured size. */
    private fun sameKind(
        a: PrimitiveMeasurement,
        b: PrimitiveMeasurement,
    ): Boolean = a.label == b.label

    /**
     * How closely two same-label primitives' sizes match, in 0..1 — a
     * plain [PrimitiveMeasurement.extent] error against [MAX_LENGTH_ERROR].
     * One generic size measure works uniformly across any taught label,
     * unlike the old type-specific fields (a line's length, an arc's
     * sweep angle/radius) this replaced — see [PrimitiveMeasurement]'s
     * class doc.
     */
    private fun sizeScore(
        window: PrimitiveMeasurement,
        target: PrimitiveMeasurement,
    ): Float = (1f - abs(window.extent - target.extent) / MAX_LENGTH_ERROR).coerceIn(0f, 1f)
}
