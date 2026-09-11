package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Location
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A cell's coordinate in a city's NxN grid. [displacement] is plain
 * component-wise subtraction, exactly the vector-between-two-points (or
 * between two displacements — see [Location.displacement]'s doc) idiom
 * [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher] relies on generically
 * for every [Location] implementation, nothing city-specific about it.
 *
 * Unlike [com.eta.tbp.lib.sensor.FloatLocation]'s continuous averaging,
 * [mergedWith] rounds the weighted average back to the nearest cell: a
 * city's grid has no rotation ambiguity the way a hand-drawn stroke does
 * (its layout is fixed and axis-aligned), but it is discrete, so an
 * "average" position only means anything once snapped back onto the grid —
 * a deliberate domain difference, not an oversight (see this project's
 * "default to Monty's own approach" rule for why divergences like this one
 * are called out rather than silently made).
 */
data class MapLocation(
    val x: Int,
    val y: Int,
) : Location {
    override fun displacement(from: Location): Location {
        if (from !is MapLocation) {
            return Location.Infinity
        }

        return MapLocation(x - from.x, y - from.y)
    }

    override fun magnitude(): Float = sqrt((x * x + y * y).toFloat())

    override fun mergedWith(
        other: Location,
        selfWeight: Float,
        totalWeight: Float,
    ): Location {
        if (other !is MapLocation) return Location.Infinity
        return MapLocation(
            x = ((x * selfWeight + other.x) / totalWeight).roundToInt(),
            y = ((y * selfWeight + other.y) / totalWeight).roundToInt(),
        )
    }
}
