package com.eta.tbp.lib.memory

/**
 * One node in a taught graph — a primitive in a character's graph, or a
 * visited cell in a city's graph. [location]/[feature] are the generic
 * [Location]/[Feature] interfaces rather than a fixed shape, so [GraphNode]
 * isn't tied to one tier's payload type; see [Feature] and [Location] for
 * what [GraphMatcher]/[GraphMemory] need each to support. [GraphMatcher]
 * re-bases every node's [location] to a shared anchor per alignment attempt
 * rather than comparing stored positions directly, since neither a stroke's
 * nor a city's starting reference point is fixed across exemplars.
 */
data class GraphNode(
    val id: Int,
    val location: Location,
    val feature: Feature,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphNode

        if (id != other.id) return false
        if (feature != other.feature) return false
        if (location != other.location) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + feature.hashCode()
        result = 31 * result + location.hashCode()
        return result
    }
}

/** The sequential chain between consecutive primitives (multi-stroke: just concatenated in drawing order). */
data class GraphEdge(
    val fromNode: Int,
    val toNode: Int,
    val displacement: Location,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GraphEdge

        if (fromNode != other.fromNode) return false
        if (toNode != other.toNode) return false
        if (displacement != other.displacement) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fromNode
        result = 31 * result + toNode
        result = 31 * result + displacement.hashCode()
        return result
    }
}

data class GraphObjectModel(
    val label: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    var exemplarCount: Int,
) {
    fun find(feature: Feature) = nodes.filter { node -> node.feature == feature }
}

/** Builds the sequential edge chain over [nodes] in the order they're given. */
fun edgeChainOf(nodes: List<GraphNode>): List<GraphEdge> =
    (0 until nodes.size - 1).map { i ->
        val from = nodes[i]
        val to = nodes[i + 1]
        GraphEdge(
            fromNode = from.id,
            toNode = to.id,
            displacement = to.location.displacement(from.location),
        )
    }
