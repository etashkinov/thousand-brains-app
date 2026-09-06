package com.eta.tbp.lib.memory

import com.eta.tbp.lib.util.angleDifference
import kotlin.math.abs
import kotlin.math.sqrt

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
 */
object GraphMatcher {
    private const val NO_MATCH = 0f
    private const val MAX_POSITION_ERROR = 1f // normalized-space units (Phase 1 normalizes to unit radius)
    private val DIRECTIONS = intArrayOf(1, -1)
    private val PI_F = kotlin.math.PI.toFloat()

    /** A stored-node window paired with how well it scores against some target sequence. */
    data class Alignment(
        val window: List<GraphNode>,
        val score: Float,
    )

    /** Full match: [stored] and [candidate] must have the same node count. */
    fun matchScore(
        stored: GraphObjectModel,
        candidate: GraphObjectModel,
    ): Float {
        if (stored.nodes.isEmpty() || stored.nodes.size != candidate.nodes.size) return NO_MATCH
        return bestAlignment(stored.nodes, candidate.nodes).score
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
        return bestAlignment(stored.nodes, partialNodes).score
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
        return bestAlignment(stored.nodes, candidate.nodes).window
    }

    private fun bestAlignment(
        storedNodes: List<GraphNode>,
        target: List<GraphNode>,
    ): Alignment {
        var best = Alignment(window = emptyList(), score = NO_MATCH)
        for (direction in DIRECTIONS) {
            for (offset in storedNodes.indices) {
                val window = windowOf(storedNodes, offset, direction, target.size)
                val score = alignmentScore(window, target)
                if (score > best.score) best = Alignment(window, score)
            }
        }
        return best
    }

    private fun windowOf(
        nodes: List<GraphNode>,
        offset: Int,
        direction: Int,
        length: Int,
    ): List<GraphNode> {
        val n = nodes.size
        return List(length) { i -> nodes[Math.floorMod(offset + i * direction, n)] }
    }

    /** 0f if the primitive-type sequence doesn't match at this alignment. */
    private fun alignmentScore(
        window: List<GraphNode>,
        target: List<GraphNode>,
    ): Float {
        for (i in window.indices) {
            if (window[i].primitiveType != target[i].primitiveType) return NO_MATCH
        }

        val windowBaseline = window.first().absoluteAngle
        val targetBaseline = target.first().absoluteAngle

        var angleError = 0f
        var positionError = 0f
        for (i in window.indices) {
            val windowAngle = angleDifference(window[i].absoluteAngle, windowBaseline)
            val targetAngle = angleDifference(target[i].absoluteAngle, targetBaseline)
            angleError += abs(angleDifference(windowAngle, targetAngle))
            positionError += distance(window[i].location, target[i].location)
        }

        val n = window.size
        val angleScore = (1f - (angleError / n) / PI_F).coerceIn(0f, 1f)
        val positionScore = (1f - (positionError / n) / MAX_POSITION_ERROR).coerceIn(0f, 1f)
        return (angleScore + positionScore) / 2f
    }

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }
}
