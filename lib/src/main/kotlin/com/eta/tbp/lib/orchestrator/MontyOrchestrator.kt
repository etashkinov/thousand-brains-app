package com.eta.tbp.lib.orchestrator

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.sensor.PrimitiveSensorModule
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.RawTouchObservation
import com.eta.tbp.lib.sensor.StrokePreprocessor

/**
 * One detected primitive's on-screen extent, for a debug overlay drawn
 * directly on top of the canvas — the pixel-space counterpart of the
 * text-only primitive list [com.eta.tbp.lib.lm.EvidenceGraphLM.currentNodes]
 * already exposes. [topLeft]/[bottomRight] are in the *original, raw touch*
 * coordinate space (same units as the [RawPoint]s passed to
 * [MontyOrchestrator.stepStroke]), not the normalized space
 * [com.eta.tbp.lib.memory.GraphNode.location] lives in — see
 * [MontyOrchestrator]'s overlay-tracking note for why that mapping is exact
 * rather than an approximation.
 */
data class PrimitiveOverlay(
    val measurement: PrimitiveMeasurement,
    val topLeft: RawPoint,
    val bottomRight: RawPoint,
)

/**
 * Mirrors real Monty's `MontyBase`/step loop, wiring [PrimitiveSensorModule]
 * (Tier 1's `SensorModule`, itself backed by [PrimitiveGraphLM]'s taught,
 * evidence-matched classification) and [EvidenceGraphLM] (Tier 2, the
 * character-level [com.eta.tbp.lib.lm.LearningModule]) together: collect
 * observation → step SM → step LM (modeling) → step LM (voting), the same
 * loop shape real Monty's own `MontyBase` runs.
 *
 * The segmentation search [PrimitiveSensorModule] runs internally needs the
 * *whole* stroke's points before it can decide anything (querying arbitrary,
 * including never-chosen, candidate windows along the way) — it isn't a
 * per-observation streaming step the way a real Monty `SensorModule.step()`
 * usually is. That's why every observation is [PrimitiveSensorModule.step]ped
 * individually (matching the interface) but the actual primitives only
 * materialize in a batch once [PrimitiveSensorModule.flushStroke] is called
 * at the end of each stroke — see [PrimitiveSensorModule]'s own class doc
 * for why that's still a faithful `SensorModule`, not a reason to fold its
 * job into this class (an earlier version of this file did exactly that,
 * after the original per-point `PrimitiveSensorModule` was deleted
 * alongside the hand-coded classifier it used to wrap — see
 * IMPLEMENTATION_PLAN.md §7 — and was restructured back behind the
 * interface once that shortcut was recognized as one).
 *
 * Episode boundary is one full character (however many strokes it takes),
 * uniform across the whole pipeline — see IMPLEMENTATION_PLAN.md §3.6 for
 * why an earlier, per-stroke-scoped draft of this class was wrong.
 *
 * Normalization ([StrokePreprocessor.normalize]) needs every stroke drawn
 * so far to compute a shared centroid/scale — otherwise each stroke of a
 * multi-stroke character would normalize to its own local origin,
 * destroying real cross-stroke position information. Since that shared
 * normalization can shift once a new stroke arrives (or a stroke is
 * undone), there's no way to incrementally patch previously-computed
 * primitives — so [stepStroke]/[undoLastStroke]/[clearCharacter] all
 * recompute ([replay]) the whole character from scratch. Cheap at this
 * data scale (a handful of strokes, tens of points each per character) —
 * segmentation search included, since [StrokeSegmenter]'s search space is
 * bounded by point count, not by anything that grows with how many
 * characters have been taught.
 */
class MontyOrchestrator(
    primitiveGraphLM: PrimitiveGraphLM,
    private val tier2: EvidenceGraphLM<PrimitiveMeasurement>,
) {
    private val primitiveSensorModule = PrimitiveSensorModule(sensorId = SENSOR_ID, primitiveGraphLM = primitiveGraphLM)
    private val strokePoints = mutableListOf<List<RawPoint>>()
    private val primitiveOverlays = mutableListOf<PrimitiveOverlay>()

    /**
     * Per-stroke points as they stood right before normalization (resampled
     * and smoothed, but still in the original touch-pixel coordinate
     * space), stashed by [buildNormalizedObservations] purely so
     * [recordOverlay] can map a primitive's window back to an on-screen
     * bounding box without inverting the normalization transform
     * (translate-by-centroid, scale-by-bounding-radius) by hand. A
     * primitive's window indices are exactly indices into this same array,
     * so the mapping is exact, not approximate.
     */
    private var rawResampledPerStroke: List<List<RawPoint>> = emptyList()

    /** Starts a new character episode, discarding any strokes from a previous one that was never ended. */
    fun beginCharacter() {
        strokePoints.clear()
        replay()
    }

    /** Adds one completed stroke's raw points to the still-open character and recomputes evidence. */
    fun stepStroke(points: List<RawPoint>) {
        strokePoints.add(points)
        replay()
    }

    /** Drops the most recently added stroke (if any) and recomputes evidence. */
    fun undoLastStroke() {
        if (strokePoints.isEmpty()) return
        strokePoints.removeAt(strokePoints.lastIndex)
        replay()
    }

    /** Discards every stroke drawn so far in the current character. */
    fun clearCharacter() {
        strokePoints.clear()
        replay()
    }

    /**
     * Ends the character episode and returns the recognition decision. The
     * one point where [EvidenceGraphLM.postEpisode]'s real effect
     * (snapshotting for [teach]) fires — deliberately not fired on every
     * [replay], see that method's doc.
     */
    fun endCharacter(): RecognitionResult {
        tier2.postEpisode()
        return tier2.recognitionResult()
    }

    fun teach(label: String) = tier2.teach(label)

    /**
     * Every primitive detected so far this episode, as an on-screen
     * bounding box + measurement — direct introspection for a debug
     * overlay drawn on the canvas itself, same spirit as
     * [EvidenceGraphLM.currentNodes]/[EvidenceGraphLM.evidenceSnapshot].
     * Rebuilt on every [replay], so it's always in sync with [strokePoints].
     */
    fun currentPrimitiveOverlays(): List<PrimitiveOverlay> = primitiveOverlays.toList()

    /**
     * Recomputes the whole character from scratch against every stroke in
     * [strokePoints]. Each stroke's points are segmented *independently* —
     * a pen lift between strokes is never bridged by a single primitive,
     * enforced by calling [PrimitiveSensorModule.flushStroke] once per
     * stroke rather than once for the whole character.
     * [PrimitiveSensorModule.preEpisode] only resets *its* own
     * cross-stroke state (the turn-from-previous angle tracking); the
     * per-stroke `step`/`flushStroke` split below is what keeps one
     * stroke's points from bleeding into another's segmentation.
     * `tier2.postEpisode()` deliberately does NOT fire here: its only
     * consequential effect (snapshotting the completed graph for [teach])
     * is reserved for the true end of the character, in [endCharacter].
     */
    private fun replay() {
        tier2.preEpisode()
        primitiveSensorModule.preEpisode()
        primitiveOverlays.clear()

        for ((strokeIndex, observations) in buildNormalizedObservations().withIndex()) {
            if (observations.isEmpty()) continue

            for (observation in observations) {
                primitiveSensorModule.step(observation)
            }

            for (message in primitiveSensorModule.flushStroke(strokeIndex)) {
                recordOverlay(message, strokeIndex)
                tier2.matchingStep(listOf(message))
                tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
            }
        }

        primitiveSensorModule.postEpisode()
    }

    /** No-op for a "nothing new this step" message — kept symmetric with the overlay's own doc even though every call here already passes. */
    private fun recordOverlay(
        message: CmpMessage,
        strokeIndex: Int,
    ) {
        val features = message.nonMorphologicalFeatures as? PrimitiveFeatures ?: return
        val points = rawResampledPerStroke[strokeIndex].subList(features.startIndex, features.endIndex + 1)
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        primitiveOverlays +=
            PrimitiveOverlay(
                measurement = features.measurement,
                topLeft = RawPoint(minX, minY),
                bottomRight = RawPoint(maxX, maxY),
            )
    }

    /**
     * Resamples every stroke independently (consistent point density per
     * stroke) but normalizes them together, sharing one centroid/scale
     * across the whole character — see class doc. Each stroke is smoothed
     * ([StrokePreprocessor.smooth]) right after resampling, damping real
     * touch jitter before tangent/curvature estimation would otherwise
     * amplify it.
     *
     * Every stroke contributes the same fixed point count to that shared
     * estimate regardless of its actual arc length, so a short stroke and a
     * long one are weighted equally rather than by true size — an accepted
     * simplification, worth revisiting in Phase 5 if very unevenly-sized
     * strokes (e.g. a dot plus a long stroke) turn out to matter.
     */
    private fun buildNormalizedObservations(): List<List<RawTouchObservation>> {
        if (strokePoints.isEmpty()) {
            rawResampledPerStroke = emptyList()
            return emptyList()
        }

        val resampledPerStroke =
            strokePoints.map { points ->
                StrokePreprocessor.smooth(
                    StrokePreprocessor.resampleByArcLength(points, StrokePreprocessor.DEFAULT_RESAMPLE_COUNT),
                )
            }
        rawResampledPerStroke = resampledPerStroke
        val flatNormalized = StrokePreprocessor.normalize(resampledPerStroke.flatten())

        var offset = 0
        return resampledPerStroke.mapIndexed { strokeIndex, resampled ->
            val normalizedChunk = flatNormalized.subList(offset, offset + resampled.size)
            val tangentsAndCurvatures = StrokePreprocessor.tangentsAndCurvatures(normalizedChunk)
            offset += resampled.size

            normalizedChunk.mapIndexed { orderInStroke, point ->
                val (tangentAngle, curvature) = tangentsAndCurvatures[orderInStroke]
                RawTouchObservation(
                    position = point.toFloatArray(),
                    tangentAngle = tangentAngle,
                    curvature = curvature,
                    strokeIndex = strokeIndex,
                    orderInStroke = orderInStroke,
                )
            }
        }
    }

    private companion object {
        const val SENSOR_ID = "primitive-sensor"
    }
}
