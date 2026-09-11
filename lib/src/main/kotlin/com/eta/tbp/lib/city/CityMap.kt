package com.eta.tbp.lib.city

/**
 * The NxN grid of [MapFeature]s a [CitySensorModule] senses from — the
 * "ground truth" city an explorer moves around in, standing in for a real
 * Monty environment the same way [com.eta.tbp.lib.sensor.RawPoint] stands
 * in for a real touch sensor's raw signal in this app's other domain.
 */
class CityMap(
    val size: Int,
    private val cells: Map<MapLocation, MapFeature>,
) {
    /** [MapFeature.EMPTY] for any cell not explicitly given a feature. */
    fun featureAt(location: MapLocation): MapFeature = cells[location] ?: MapFeature.EMPTY

    companion object {
        /** `CityMap.of(4, (0 to 0) to "post_office", (2 to 1) to "park")` — cells left out default to [MapFeature.EMPTY]. */
        fun of(
            size: Int,
            vararg features: Pair<Pair<Int, Int>, String>,
        ): CityMap =
            CityMap(
                size = size,
                cells =
                    features.associate { (coordinates, label) ->
                        MapLocation(coordinates.first, coordinates.second) to MapFeature(label)
                    },
            )
    }
}
