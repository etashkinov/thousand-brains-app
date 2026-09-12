package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.FloatLocation

/**
 * The NxN grid of [MapFeature]s a [CitySensorModule] senses from — the
 * "ground truth" city an explorer moves around in, standing in for a real
 * Monty environment the same way [com.eta.tbp.lib.sensor.RawPoint] stands
 * in for a real touch sensor's raw signal in this app's other domain.
 */
class CityMap(
    val size: Int,
    val cells: Map<Location, MapFeature>,
) {
    /** [MapFeature.EMPTY] for any cell not explicitly given a feature. */
    fun featureAt(location: Location): MapFeature = cells[location] ?: MapFeature.EMPTY

    companion object {
        /** `CityMap.of(4, (0 to 0) to "post_office", (2 to 1) to "park")` — cells left out default to [MapFeature.EMPTY]. */
        fun of(
            size: Int,
            vararg features: Pair<Location, String>,
        ): CityMap =
            CityMap(
                size = size,
                cells =
                    features.associate { (location, label) ->
                        location to MapFeature(label)
                    },
            )
    }
}
