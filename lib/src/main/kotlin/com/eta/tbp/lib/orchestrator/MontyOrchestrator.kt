package com.eta.tbp.lib.orchestrator

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.sensor.PrimitiveSensorModule
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.RawTouchObservation
import com.eta.tbp.lib.sensor.SensorModule
import com.eta.tbp.lib.sensor.StrokePreprocessor

/**
 * One detected primitive's on-screen extent, for a debug overlay drawn
 * directly on top of the canvas — the pixel-space counterpart of the
 * text-only primitive list [com.eta.tbp.lib.lm.CharacterGraphLM.currentNodes]
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
 * Mirrors real Monty's `MontyBase`/step loop, wiring a two-stage sensor
 * pipeline ([SensorModule] into [PrimitiveSensorModule]) into the one
 * [com.eta.tbp.lib.lm.LearningModule] in this app's hierarchy,
 * [CharacterGraphLM]. Lives in its own package rather than `lm` because it
 * isn't itself a `LearningModule` — the same relationship Monty's own
 * `monty_base.py` has to `sensor_modules.py`/`learning_module.py`.
 *
 * There's only one `LearningModule` here, not two: `PrimitiveSensorModule`
 * does rule-based feature extraction (line/arc fitting), which is
 * architecturally SM-shaped work in Monty's own terms, not LM-shaped work
 * — see its class doc and IMPLEMENTATION_PLAN.md §3.4/§4. A single-SM-
 * chained-into-another-SM, single-LM hierarchy like this one is Monty's
 * own ordinary baseline configuration, not a stripped-down special case.
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
 * data scale (a handful of strokes, tens of points each per character).
 */
class MontyOrchestrator(
    private val sensorModule: SensorModule<RawTouchObservation>,
    private val primitiveSensor: PrimitiveSensorModule,
    private val tier2: CharacterGraphLM,
) {
    private val strokePoints = mutableListOf<List<RawPoint>>()
    private val primitiveOverlays = mutableListOf<PrimitiveOverlay>()

    /**
     * Per-stroke points as they stood right before normalization (resampled
     * and smoothed, but still in the original touch-pixel coordinate
     * space), stashed by [buildNormalizedObservations] purely so
     * [recordOverlay] can map a primitive's `startIndex`/`endIndex` back to
     * an on-screen bounding box without inverting the normalization
     * transform (translate-by-centroid, scale-by-bounding-radius) by hand.
     * `orderInStroke` on every observation built from this is exactly its
     * index here, so the mapping is exact, not approximate.
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
     * one point where [CharacterGraphLM.postEpisode]'s real effect
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
     * [CharacterGraphLM.currentNodes]/[CharacterGraphLM.evidenceSnapshot].
     * Rebuilt on every [replay], so it's always in sync with [strokePoints].
     */
    fun currentPrimitiveOverlays(): List<PrimitiveOverlay> = primitiveOverlays.toList()

    /**
     * Recomputes the whole character from scratch against every stroke in
     * [strokePoints]. `primitiveSensor.preEpisode()`/`postEpisode()` fire
     * on every replay — harmless since it's fully stateless across calls
     * — so evidence stays live as strokes are added or removed.
     * `tier2.postEpisode()` deliberately does NOT fire here: its only
     * consequential effect (snapshotting the completed graph for [teach])
     * is reserved for the true end of the character, in [endCharacter].
     */
    private fun replay() {
        primitiveSensor.preEpisode()
        tier2.preEpisode()
        primitiveOverlays.clear()
        for (observations in buildNormalizedObservations()) {
            for (observation in observations) {
                val touchMessage = sensorModule.step(observation)
                val primitiveMessage = primitiveSensor.step(touchMessage)
                recordOverlay(primitiveMessage)
                tier2.matchingStep(listOf(primitiveMessage))
                tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
            }
        }
        primitiveSensor.postEpisode()
        primitiveSensor.drainTrailingPrimitive()?.let {
            recordOverlay(it)
            tier2.matchingStep(listOf(it))
        }
    }

    /** No-op for a "nothing new this step" message (see [PrimitiveSensorModule.step]'s doc) — most steps are exactly this. */
    private fun recordOverlay(message: CmpMessage) {
        if (!message.passMessage) return
        val features = message.nonMorphologicalFeatures as? PrimitiveFeatures ?: return
        val points = rawResampledPerStroke[features.strokeIndex].subList(features.startIndex, features.endIndex + 1)
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
     * Tangent *angle* is invariant to uniform translate+scale, but
     * curvature is not — [StrokePreprocessor.tangentsAndCurvatures] reports
     * true differential curvature (radians per unit length, ≈ 1/radius),
     * which scales inversely with whatever coordinate space it's measured
     * in. An earlier version computed it on the pre-normalization
     * ([resampledPerStroke]) points as an optimization, reasoning
     * (correctly, for the *bare turning angle* curvature used to report at
     * the time) that translate+scale invariance made it equivalent to
     * computing on the normalized ones. That stopped being true once
     * curvature became length-normalized: a raw, un-normalized stroke can
     * be many times larger than the shared normalized space every other
     * distance in this pipeline (including [PrimitiveSensorModule]'s own
     * `MAX_LOCAL_TURN` threshold) is calibrated against, so curvature must
     * be computed on [normalizedChunk] — the same shared normalized space —
     * not the pre-normalization points (see IMPLEMENTATION_PLAN.md §7 for
     * the concrete regression this caused).
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
}
