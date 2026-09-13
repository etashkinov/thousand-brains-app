package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.LabelFeature
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.isNear
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
 * [positionTolerance] defaults to `0.3f`, comfortably under this class's own
 * 1.0-unit cell spacing (every test fixture in `Cities.kt` places landmarks
 * at least `sqrt(5)` ≈ 2.24 apart) — tunable per instance, same as [random].
 */
class GridEnvironment(
    override val size: Int,
    val cells: Map<Location, Feature>,
    override val positionTolerance: Float = 0.3f,
    private val random: Random = Random.Default,
) : Environment {
    override fun randomLocation() = FloatLocation(random.nextInt(size).toFloat(), random.nextInt(size).toFloat())

    /** The nearest cell within [positionTolerance] of [location], or `null` if none qualifies. */
    override fun featureAt(location: Location): Feature? =
        cells.entries
            .filter { (cellLocation, _) -> cellLocation.isNear(location, positionTolerance) }
            .minByOrNull { (cellLocation, _) -> cellLocation.displacement(location).magnitude() }
            ?.value

    companion object {
        /** `GridEnvironment.of(4, FloatLocation(0f, 0f) to "post_office", FloatLocation(2f, 1f) to "park")` — a [LabelFeature] shorthand for the common "named points of interest" case; cells left out default to null. [random] seeds [randomLocation]; pass a fixed seed for a reproducible exploration order (e.g. in tests). */
        fun of(
            size: Int,
            vararg features: Pair<Location, String>,
            positionTolerance: Float = 0.3f,
            random: Random = Random.Default,
        ): GridEnvironment =
            GridEnvironment(
                size = size,
                cells =
                    features.associate { (location, label) ->
                        location to LabelFeature(label)
                    },
                positionTolerance = positionTolerance,
                random = random,
            )
    }
}
