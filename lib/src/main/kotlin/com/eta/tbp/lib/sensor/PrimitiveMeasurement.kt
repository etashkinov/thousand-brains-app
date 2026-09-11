package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.EvidenceFeature
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
 * Implements [EvidenceFeature] so [com.eta.tbp.lib.memory.GraphMatcher]/
 * [com.eta.tbp.lib.memory.GraphMemory] can compare/merge [PrimitiveMeasurement]
 * nodes generically: a label mismatch is an infinite [difference] (never a
 * match, same as the old hard-coded gate), otherwise the plain [extent] error.
 */
data class PrimitiveMeasurement(
    val label: String,
    val extent: Float,
) : EvidenceFeature<PrimitiveMeasurement> {
    override fun difference(other: PrimitiveMeasurement): Float =
        if (label != other.label) Float.POSITIVE_INFINITY else abs(extent - other.extent)

    override fun mergedWith(
        other: PrimitiveMeasurement,
        selfWeight: Float,
        totalWeight: Float,
    ): PrimitiveMeasurement = PrimitiveMeasurement(label = label, extent = (extent * selfWeight + other.extent) / totalWeight)
}

/**
 * The [com.eta.tbp.lib.cmp.CmpMessage.nonMorphologicalFeatures] payload for
 * one chosen primitive, consumed by
 * [com.eta.tbp.lib.lm.EvidenceGraphLM.matchingStep]. [startIndex]/[endIndex]
 * are inclusive indices into that stroke's resampled, normalized point
 * sequence — used by [com.eta.tbp.lib.orchestrator.MontyOrchestrator] to
 * map a primitive back to an on-screen bounding box for its debug overlay.
 */
interface PrimitiveFeatures {
    val measurement: PrimitiveMeasurement
    val startIndex: Int
    val endIndex: Int
    val strokeIndex: Int
}
