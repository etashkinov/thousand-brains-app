package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.LabelFeature
import com.eta.tbp.lib.memory.Location
import kotlin.random.Random

/**
 * A generic NxN grid [Environment]: a fixed [size], a sparse
 * `Location -> Feature` lookup ([cells], `null` for anything left out), and
 * a uniformly random cell as [randomLocation] — standing in for a real
 * Monty environment the same way [RawPoint] stands in for a real touch
 * sensor's raw signal in this app's other domain. Nothing here is tied to
 * any one domain's [Feature] — a city's points of interest (this app's test
 * fixtures build them via [of]'s [LabelFeature] shorthand) are the one
 * example today, but [cells] takes any [Feature].
 *
 * Owns no notion of tolerance itself — see [Environment.featureAt]'s own
 * doc for why that value is supplied by the caller instead.
 */
class GridEnvironment(
    override val size: Int,
    val cells: Map<Location, Feature>,
    private val random: Random = Random.Default,
) : Environment {
    override fun randomLocation() = FloatLocation(random.nextInt(size).toFloat(), random.nextInt(size).toFloat())

    /** The 4 orthogonal neighbors of [location] (up/down/left/right one grid unit — no diagonals, the way city blocks are actually laid out) that still fall within `[0, size)` on both axes. */
    override fun adjacentLocations(location: Location): List<Location> {
        val (x, y) = (location as FloatLocation).location[0] to location.location[1]
        return listOf(x to y - 1f, x to y + 1f, x - 1f to y, x + 1f to y)
            .filter { (nx, ny) -> nx >= 0f && nx < size && ny >= 0f && ny < size }
            .map { (nx, ny) -> FloatLocation(nx, ny) }
    }

    /** The nearest cell within [tolerance] of [location], or `null` if none qualifies. */
    override fun featureAt(
        location: Location,
        tolerance: Float,
    ): Feature? =
        cells.entries
            .filter { (cellLocation, _) -> cellLocation.isNear(location, tolerance) }
            .minByOrNull { (cellLocation, _) -> cellLocation.displacement(location).magnitude() }
            ?.value

    override fun toString() = "GridEnvironment(size=$size, cells=${cells.size})"

    companion object {
        /** `GridEnvironment.of(4, FloatLocation(0f, 0f) to "post_office", FloatLocation(2f, 1f) to "park")` — a [LabelFeature] shorthand for the common "named points of interest" case; cells left out default to null. [random] seeds [randomLocation]; pass a fixed seed for a reproducible exploration order (e.g. in tests). */
        fun of(
            size: Int,
            vararg features: Pair<Location, String>,
            random: Random = Random.Default,
        ): GridEnvironment =
            GridEnvironment(
                size = size,
                cells =
                    features.associate { (location, label) ->
                        location to LabelFeature(label)
                    },
                random = random,
            )
    }
}
