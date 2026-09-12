package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature

/**
 * A city grid cell's identity — a point of interest like a post office,
 * park, or bakery, or [EMPTY] for a cell with nothing notable. Implements
 * [com.eta.tbp.lib.memory.Feature] the same way [PrimitiveFeature] does: a
 * label mismatch is an infinite [difference] (never a match), and merging
 * is a no-op — a cell's identity doesn't average the way a continuous
 * measurement does; either two taught exemplars agree on what's there, or
 * [difference] already vetoed the match before [mergedWith] would ever be
 * called with a real mismatch.
 */
data class LabelFeature(
    override val label: String,
) : Feature {
    override fun difference(other: Feature): Float = if (other is LabelFeature && other.label == label) 0f else Float.POSITIVE_INFINITY

    override fun mergedWith(
        other: Feature,
        selfWeight: Float,
        totalWeight: Float,
    ): Feature = this
}
