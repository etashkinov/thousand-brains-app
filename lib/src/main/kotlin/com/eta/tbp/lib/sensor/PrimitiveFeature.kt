package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import kotlin.math.abs

/**
 * A primitive's taught identity ([label]) plus one generic size measure
 * ([extent], the chord distance between its first and last point) — no
 * fixed, closed vocabulary of shape types. Produced by
 * [com.eta.tbp.lib.orchestrator.MontyOrchestrator] for whichever window
 * [com.eta.tbp.lib.orchestrator.StrokeSegmenter]'s search chose, using
 * [com.eta.tbp.lib.lm.PrimitiveGraphLM]'s best-matching label for that
 * window's evidence — there is no hand-coded geometric classifier deciding
 * this anymore (see IMPLEMENTATION_PLAN.md §7 for why an earlier one, a
 * closed `Line`/`Arc` sealed hierarchy classified by distance-from-a-fit
 * thresholds, was replaced).
 *
 * Implements [Feature] so [com.eta.tbp.lib.memory.GraphMatcher]/
 * [com.eta.tbp.lib.memory.GraphMemory] can compare/merge [PrimitiveFeature]
 * nodes generically: a label mismatch is an infinite [difference] (never a
 * match, same as the old hard-coded gate), otherwise the plain [extent] error.
 */
data class PrimitiveFeature(
    override val label: String,
    val angle: Float,
    val extent: Float,
) : Feature {
    override fun difference(other: Feature): Float {
        if (other !is PrimitiveFeature) {
            return Float.POSITIVE_INFINITY
        }

        return if (label != other.label) Float.POSITIVE_INFINITY else abs(extent - other.extent)
    }

    override fun mergedWith(
        other: Feature,
        selfWeight: Float,
        totalWeight: Float,
    ): Feature {
        if (other !is PrimitiveFeature) {
            return Feature.Infinity
        }

        return PrimitiveFeature(label = label, angle = angle, extent = (extent * selfWeight + other.extent) / totalWeight)
    }
}
