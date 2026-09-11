package com.eta.tbp.lib.memory

/**
 * What [GraphMatcher]/[GraphMemory] need to know about a node's position to
 * compare and merge two nodes — the same self-describing-interface idiom as
 * [Feature] (see its doc): both a stroke's continuous
 * [com.eta.tbp.lib.sensor.FloatLocation] and a city's discrete
 * [com.eta.tbp.lib.city.MapLocation] implement this so [GraphMatcher]/
 * [GraphMemory] can align/merge nodes generically without knowing which
 * concrete location type they're holding.
 */
interface Location {
    /** The vector from [from] to this location. Also used to diff two vectors themselves — subtraction is subtraction either way, so [GraphMatcher] reuses it to compare two displacements. */
    fun displacement(from: Location): Location

    /** This location's scalar size — called on a [displacement] result to score positional error. 0 = identical. */
    fun magnitude(): Float

    /** Weighted merge of this location (weight [selfWeight]) with [other], normalized by [totalWeight]. */
    fun mergedWith(
        other: Location,
        selfWeight: Float,
        totalWeight: Float,
    ): Location

    object Infinity : Location {
        override fun displacement(from: Location) = this

        override fun magnitude() = Float.POSITIVE_INFINITY

        override fun mergedWith(
            other: Location,
            selfWeight: Float,
            totalWeight: Float,
        ) = this
    }
}
