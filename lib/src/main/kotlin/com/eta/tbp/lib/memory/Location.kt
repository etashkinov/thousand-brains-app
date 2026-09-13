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

    /** This location translated by [displacement] — the inverse of [displacement]: `a.displacement(b).let(b::plus) == a`. Used to project a stored node into another location's frame (e.g. [GraphMatcher.predictedLocations]), the counterpart of subtracting two locations to get a vector. */
    fun plus(displacement: Location): Location

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

        override fun plus(displacement: Location) = this

        override fun magnitude() = Float.POSITIVE_INFINITY

        override fun mergedWith(
            other: Location,
            selfWeight: Float,
            totalWeight: Float,
        ) = this
    }
}

/**
 * How close two [Location]s must be to count as the same place — the
 * tolerant counterpart of exact [Location] equality, for callers that need
 * "close enough to be the same place" rather than "bit-identical." Bundles
 * the raw tolerance value together with its own comparison methods, rather
 * than every call site passing a bare [Float] alongside the [Location]s
 * being compared (`a.isNear(b, tolerance)` scattered everywhere) — one
 * configured value, held once by whatever owns it
 * ([com.eta.tbp.lib.sensor.Environment.positionTolerance],
 * [com.eta.tbp.lib.lm.EvidenceGraphLM.positionTolerance]), used as
 * `tolerance.isNear(a, b)`.
 *
 * Deliberately *not* a property of [Location] itself: tolerance describes
 * how much error the *comparing process* (a sensor's precision, a domain's
 * own cell spacing) is willing to accept, not a fact about either point
 * being compared — two [Location] values don't each carry their own
 * opinion on this, the same way real Monty's own `cmp.Goal.goal_tolerances`
 * lives on the `Goal` message as a sibling of `location`, never embedded in
 * the location/coordinate itself (`cmp.py`). If a single goal ever needs
 * its own bespoke tolerance, [com.eta.tbp.lib.cmp.CmpGoal.goalTolerances]
 * is that extension point — not this class.
 *
 * A domain-scaled value: it must stay well under whatever domain it's used
 * in considers its own minimum distinct-location spacing, or genuinely
 * different locations start collapsing into each other (e.g. a city grid's
 * unit-spaced cells). [EXACT] is the only value safe to assume with no
 * domain context at all.
 */
@JvmInline
value class PositionTolerance(private val value: Float) {
    /**
     * Whether [b] is within this tolerance of [a]. Always `false` against
     * [Location.Infinity] regardless of this tolerance's value — its
     * `magnitude()` is already `POSITIVE_INFINITY`, so no special-casing is
     * needed here.
     */
    fun isNear(
        a: Location,
        b: Location,
    ): Boolean = a.displacement(b).magnitude() <= value

    /** Whether any location in [locations] [isNear] [location]. */
    fun anyNear(
        locations: Iterable<Location>,
        location: Location,
    ): Boolean = locations.any { isNear(it, location) }

    companion object {
        /** Exact equality only — the safe default wherever a caller has no domain-scaled value to supply. */
        val EXACT = PositionTolerance(0f)
    }
}
