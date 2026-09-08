package com.eta.tbp.lib.sensor

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
 */
data class PrimitiveMeasurement(
    val label: String,
    val extent: Float,
)

/**
 * The [com.eta.tbp.lib.cmp.CmpMessage.nonMorphologicalFeatures] payload for
 * one chosen primitive, consumed by
 * [com.eta.tbp.lib.lm.CharacterGraphLM.matchingStep]. [startIndex]/[endIndex]
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
