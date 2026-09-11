package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Feature

/**
 * A city grid cell's identity — a point of interest like a post office,
 * park, or bakery, or [EMPTY] for a cell with nothing notable. Implements
 * [Feature] the same way [com.eta.tbp.lib.sensor.PrimitiveFeature] does: a
 * label mismatch is an infinite [difference] (never a match), and merging
 * is a no-op — a cell's identity doesn't average the way a continuous
 * measurement does; either two taught exemplars agree on what's there, or
 * [difference] already vetoed the match before [mergedWith] would ever be
 * called with a real mismatch.
 */
data class MapFeature(
    override val label: String,
) : Feature {
    override fun difference(other: Feature): Float = if (other is MapFeature && other.label == label) 0f else Float.POSITIVE_INFINITY

    override fun mergedWith(
        other: Feature,
        selfWeight: Float,
        totalWeight: Float,
    ): Feature = this

    companion object {
        val EMPTY = MapFeature("empty")
    }
}
