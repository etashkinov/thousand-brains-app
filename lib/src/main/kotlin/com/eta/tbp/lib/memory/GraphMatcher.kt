package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement
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
    private const val MAX_LENGTH_ERROR = 1f // same normalized-space scale as MAX_POSITION_ERROR, for a line's length
    private val DIRECTIONS = intArrayOf(1, -1)
    private val PI_F = kotlin.math.PI.toFloat()
    private val TWO_PI_F = 2f * PI_F

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

    /** 0f if the primitive-kind (`Line`/`Arc`) sequence doesn't match at this alignment. */
    private fun alignmentScore(
        window: List<GraphNode>,
        target: List<GraphNode>,
    ): Float {
        for (i in window.indices) {
            if (!sameKind(window[i].measurement, target[i].measurement)) return NO_MATCH
        }

        val windowBaseline = window.first().absoluteAngle
        val targetBaseline = target.first().absoluteAngle

        var angleError = 0f
        var positionError = 0f
        var sizeScoreSum = 0f
        for (i in window.indices) {
            val windowAngle = angleDifference(window[i].absoluteAngle, windowBaseline)
            val targetAngle = angleDifference(target[i].absoluteAngle, targetBaseline)
            angleError += abs(angleDifference(windowAngle, targetAngle))
            positionError += distance(window[i].location, target[i].location)
            sizeScoreSum += sizeScore(window[i].measurement, target[i].measurement)
        }

        val n = window.size
        val angleScore = (1f - (angleError / n) / PI_F).coerceIn(0f, 1f)
        val positionScore = (1f - (positionError / n) / MAX_POSITION_ERROR).coerceIn(0f, 1f)
        val sizeScore = sizeScoreSum / n
        return (angleScore + positionScore + sizeScore) / 3f
    }

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }

    /** True if [a] and [b] are the same [PrimitiveMeasurement] variant (`Line`/`Arc`), regardless of their measured values. */
    private fun sameKind(
        a: PrimitiveMeasurement,
        b: PrimitiveMeasurement,
    ): Boolean =
        when (a) {
            is PrimitiveMeasurement.Line -> b is PrimitiveMeasurement.Line
            is PrimitiveMeasurement.Arc -> b is PrimitiveMeasurement.Arc
        }

    /**
     * How closely two same-kind primitives' sizes match, in 0..1 — a
     * line's length error against [MAX_LENGTH_ERROR]; an arc's sweep-angle
     * error against a full turn averaged with its radius error against
     * [MAX_LENGTH_ERROR] (the same normalized-space scale as a line's
     * length, since a radius lives in that same unit). Each of these is
     * normalized to 0..1 independently before averaging across a (possibly
     * mixed line/arc) window, rather than pooling raw errors that aren't on
     * comparable scales. [window]/[target] are already known to be the same
     * variant by the time this is called (see [alignmentScore]'s
     * [sameKind] gate), so the cast to the same variant as [window] is
     * always safe.
     *
     * Sweep-angle error is a plain difference, not [angleDifference]'s
     * wrapped one: a sweep angle is a total accumulated rotation (can
     * exceed a half turn for a tight loop, see
     * [com.eta.tbp.lib.sensor.PrimitiveSensorModule.sweepAngleOf]), not a
     * periodic heading, so treating two large-but-different sweeps as
     * "close" just because they're both near a full turn would be wrong.
     */
    private fun sizeScore(
        window: PrimitiveMeasurement,
        target: PrimitiveMeasurement,
    ): Float =
        when (window) {
            is PrimitiveMeasurement.Line -> {
                target as PrimitiveMeasurement.Line
                (1f - abs(window.length - target.length) / MAX_LENGTH_ERROR).coerceIn(0f, 1f)
            }
            is PrimitiveMeasurement.Arc -> {
                target as PrimitiveMeasurement.Arc
                val sweepScore = (1f - abs(window.sweepAngle - target.sweepAngle) / TWO_PI_F).coerceIn(0f, 1f)
                val radiusScore = (1f - abs(window.radius - target.radius) / MAX_LENGTH_ERROR).coerceIn(0f, 1f)
                (sweepScore + radiusScore) / 2f
            }
        }
}
