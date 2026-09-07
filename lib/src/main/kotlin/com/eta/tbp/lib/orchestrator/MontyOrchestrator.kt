package com.eta.tbp.lib.orchestrator

import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.sensor.PrimitiveSensorModule
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.RawTouchObservation
import com.eta.tbp.lib.sensor.SensorModule
import com.eta.tbp.lib.sensor.StrokePreprocessor

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
        for (observations in buildNormalizedObservations()) {
            for (observation in observations) {
                val touchMessage = sensorModule.step(observation)
                val primitiveMessage = primitiveSensor.step(touchMessage)
                tier2.matchingStep(listOf(primitiveMessage))
                tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
            }
        }
        primitiveSensor.postEpisode()
        primitiveSensor.drainTrailingPrimitive()?.let { tier2.matchingStep(listOf(it)) }
    }

    /**
     * Resamples every stroke independently (consistent point density per
     * stroke) but normalizes them together, sharing one centroid/scale
     * across the whole character — see class doc. Each stroke is smoothed
     * ([StrokePreprocessor.smooth]) right after resampling, damping real
     * touch jitter before tangent/curvature estimation would otherwise
     * amplify it. Tangent/curvature are invariant to uniform translate+
     * scale, so computing them per-stroke on the smoothed-but-unnormalized
     * points is equivalent to computing them on the normalized ones; only
     * `position` needs the shared normalization.
     *
     * Every stroke contributes the same fixed point count to that shared
     * estimate regardless of its actual arc length, so a short stroke and a
     * long one are weighted equally rather than by true size — an accepted
     * simplification, worth revisiting in Phase 5 if very unevenly-sized
     * strokes (e.g. a dot plus a long stroke) turn out to matter.
     */
    private fun buildNormalizedObservations(): List<List<RawTouchObservation>> {
        if (strokePoints.isEmpty()) return emptyList()

        val resampledPerStroke =
            strokePoints.map { points ->
                StrokePreprocessor.smooth(
                    StrokePreprocessor.resampleByArcLength(points, StrokePreprocessor.DEFAULT_RESAMPLE_COUNT),
                )
            }
        val flatNormalized = StrokePreprocessor.normalize(resampledPerStroke.flatten())

        var offset = 0
        return resampledPerStroke.mapIndexed { strokeIndex, resampled ->
            val normalizedChunk = flatNormalized.subList(offset, offset + resampled.size)
            val tangentsAndCurvatures = StrokePreprocessor.tangentsAndCurvatures(resampled)
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
