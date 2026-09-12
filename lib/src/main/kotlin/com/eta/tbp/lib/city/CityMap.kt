package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.Environment
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.LabelFeature
import kotlin.random.Random

/**
 * The NxN grid of [com.eta.tbp.lib.sensor.LabelFeature]s a [com.eta.tbp.lib.sensor.EnvironmentSensorModule] senses from — the
 * "ground truth" city an explorer moves around in, standing in for a real
 * Monty environment the same way [com.eta.tbp.lib.sensor.RawPoint] stands
 * in for a real touch sensor's raw signal in this app's other domain.
 */
class CityMap(
    override val size: Int,
    val cells: Map<Location, LabelFeature>,
    private val random: Random = Random.Default,
) : Environment {
    override fun randomLocation() = FloatLocation(random.nextInt(size).toFloat(), random.nextInt(size).toFloat())

    /** @return null for any cell not explicitly given a feature. */
    override fun featureAt(location: Location) = cells[location]

    companion object {
        /** `CityMap.of(4, (0 to 0) to "post_office", (2 to 1) to "park")` — cells left out default to null. [random] seeds [randomLocation]; pass a fixed seed for a reproducible exploration order (e.g. in tests). */
        fun of(
            size: Int,
            vararg features: Pair<Location, String>,
            random: Random = Random.Default,
        ): CityMap =
            CityMap(
                size = size,
                cells =
                    features.associate { (location, label) ->
                        location to LabelFeature(label)
                    },
                random = random,
            )
    }
}
