package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement

/**
 * One primitive, as stored in a character's graph. [absoluteAngle] is the
 * primitive's tangent angle in the *character's own drawing-order frame*
 * (cumulative from the first primitive, not the turn-from-previous value
 * [com.eta.tbp.lib.lm.PrimitiveLM] emits) — [GraphMatcher] re-baselines it
 * per alignment attempt, which only works if the starting reference is
 * consistent across every node, not anchored to whichever primitive
 * happened to be first when this was taught. [measurement] carries both the
 * primitive's type (`Line`/`Arc`, as the runtime variant) and its size/shape
 * (a line's length, an arc's sweep angle and radius) in one field — there's
 * no separate type enum alongside it (see [PrimitiveMeasurement]'s class
 * doc for why that used to be two parallel, only-conventionally-synced
 * discriminants for the same fact).
 */
data class GraphNode(
    val id: Int,
    val location: FloatArray,
    val absoluteAngle: Float,
    val measurement: PrimitiveMeasurement,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphNode

        if (id != other.id) return false
        if (absoluteAngle != other.absoluteAngle) return false
        if (measurement != other.measurement) return false
        if (!location.contentEquals(other.location)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + absoluteAngle.hashCode()
        result = 31 * result + measurement.hashCode()
        result = 31 * result + location.contentHashCode()
        return result
    }
}

/** The sequential chain between consecutive primitives (multi-stroke: just concatenated in drawing order). */
data class GraphEdge(
    val fromNode: Int,
    val toNode: Int,
    val displacement: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphEdge

        if (fromNode != other.fromNode) return false
        if (toNode != other.toNode) return false
        if (!displacement.contentEquals(other.displacement)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fromNode
        result = 31 * result + toNode
        result = 31 * result + displacement.contentHashCode()
        return result
    }
}

data class GraphObjectModel(
    val label: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    var exemplarCount: Int,
)

/** Builds the sequential edge chain over [nodes] in the order they're given. */
fun edgeChainOf(nodes: List<GraphNode>): List<GraphEdge> =
    (0 until nodes.size - 1).map { i ->
        val from = nodes[i]
        val to = nodes[i + 1]
        GraphEdge(
            fromNode = from.id,
            toNode = to.id,
            displacement = floatArrayOf(to.location[0] - from.location[0], to.location[1] - from.location[1]),
        )
    }
