package com.eta.tbp.lib.orchestrator

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.RawTouchObservation
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.util.angleDifference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

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
 * Mirrors real Monty's `MontyBase`/step loop, wiring [PrimitiveGraphLM]
 * (Tier 1: a taught, evidence-matched primitive recognizer) and
 * [CharacterGraphLM] (Tier 2, the character-level
 * [com.eta.tbp.lib.lm.LearningModule]) together via a global segmentation
 * search rather than a per-point streaming `SensorModule` chain.
 *
 * This replaced an earlier design chaining two `SensorModule`s
 * (`TouchSensorModule` into `PrimitiveSensorModule`, a hand-coded
 * line/arc geometric classifier) — deleted after five recalibrations on
 * real handwriting failures never converged (see IMPLEMENTATION_PLAN.md
 * §7). The actual problem was forcing every stroke window into a small,
 * fixed, hand-designed shape vocabulary via a local, irreversible
 * threshold decision — no single threshold can be right for the full
 * continuum of real hand-drawn curvature. [PrimitiveGraphLM] replaces the
 * *classifier* with a taught, evidence-matched one; [StrokeSegmenter]
 * replaces the *local, irreversible* decision with a global one — for the
 * whole stroke at once, scored by how well each candidate window matches
 * something actually taught, exactly Monty's own "maintain multiple
 * hypotheses, let evidence decide" principle, applied one level below
 * character recognition instead of only at it.
 *
 * A consequence worth being explicit about: this segmentation search needs
 * the *whole* stroke's points before it can decide anything (querying
 * arbitrary, including never-chosen, candidate windows along the way) —
 * it isn't a per-observation streaming step the way a real Monty
 * `SensorModule.step()` is. `TouchSensorModule` (whose only job was
 * wrapping a single point into a `CmpMessage` for the old per-point
 * `PrimitiveSensorModule` chain) has no remaining consumer once that chain
 * is gone, and was deleted alongside it rather than kept as an unused
 * pass-through — this class now constructs each chosen primitive's
 * `CmpMessage` directly, playing that role itself for exactly the
 * messages that actually get produced. [CharacterGraphLM] still receives
 * the same uniform `CmpMessage` stream it always did; only *how* those
 * messages get produced changed.
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
    private val primitiveGraphLM: PrimitiveGraphLM,
    private val tier2: CharacterGraphLM,
) {
    private val strokePoints = mutableListOf<List<RawPoint>>()
    private val primitiveOverlays = mutableListOf<PrimitiveOverlay>()
    private var previousExitAngle = 0f

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
     * [strokePoints]. Each stroke's points are segmented *independently* —
     * a pen lift between strokes is never bridged by a single primitive,
     * the same invariant the old per-point chain enforced by force-breaking
     * on a `strokeIndex` discontinuity. [previousExitAngle] deliberately
     * keeps tracking across stroke boundaries (only the segmentation
     * itself is per-stroke), so a primitive's turn-from-previous encoding
     * stays meaningful across a pen lift within one character.
     * `tier2.postEpisode()` deliberately does NOT fire here: its only
     * consequential effect (snapshotting the completed graph for [teach])
     * is reserved for the true end of the character, in [endCharacter].
     */
    private fun replay() {
        tier2.preEpisode()
        primitiveOverlays.clear()
        previousExitAngle = 0f

        for ((strokeIndex, observations) in buildNormalizedObservations().withIndex()) {
            if (observations.isEmpty()) continue

            val windows =
                StrokeSegmenter.segment(
                    pointCount = observations.size,
                    minWindowLength = MIN_WINDOW_LENGTH,
                    maxWindowLength = MAX_WINDOW_LENGTH,
                    segmentPenalty = SEGMENT_PENALTY,
                ) { start, end -> windowScore(observations, start, end) }

            for (window in windows) {
                val message = emitPrimitive(observations, window.startIndex, window.endIndex, strokeIndex)
                recordOverlay(message, strokeIndex)
                tier2.matchingStep(listOf(message))
                tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
            }
        }
    }

    /**
     * The *log* of [PrimitiveGraphLM]'s best evidence for the candidate
     * window `[start, end)` — not the raw evidence. This matters: raw
     * evidence for a clean match against a taught line sits close to 1.0
     * regardless of the window's length (any sub-segment of a straight
     * line is itself a straight line), so summing raw evidence directly
     * would make [StrokeSegmenter] always prefer more, shorter windows
     * whenever each one *also* scores well — no [SEGMENT_PENALTY] can fix
     * that on its own, since a small positive-per-window gain still always
     * wins by using more of them. Log-evidence caps out at 0 for a perfect
     * match, so covering the same span with more equally-good windows
     * sums to *the same* total (not more) — exactly how a real language
     * model's word segmentation naturally prefers fewer, better-fitting
     * words without needing to be told to. [SEGMENT_PENALTY] only has to
     * break near-ties toward fewer primitives after that, not fight a
     * runaway sum. [MIN_EVIDENCE] avoids `ln(0)`.
     */
    private fun windowScore(
        observations: List<RawTouchObservation>,
        start: Int,
        end: Int,
    ): Float {
        val windowPoints = windowRawPoints(observations, start, end)
        val evidence = primitiveGraphLM.evaluate(windowPoints).values.maxOrNull() ?: MIN_EVIDENCE
        return ln(evidence.coerceAtLeast(MIN_EVIDENCE))
    }

    private fun windowRawPoints(
        observations: List<RawTouchObservation>,
        start: Int,
        end: Int,
    ): List<RawPoint> = observations.subList(start, end).map { RawPoint(it.position[0], it.position[1]) }

    /** Builds one chosen window's `CmpMessage`, mirroring the shape the old per-point `PrimitiveSensorModule` used to emit. */
    private fun emitPrimitive(
        observations: List<RawTouchObservation>,
        start: Int,
        end: Int,
        strokeIndex: Int,
    ): CmpMessage {
        val windowObservations = observations.subList(start, end)
        val windowPoints = windowRawPoints(observations, start, end)
        val evidence = primitiveGraphLM.evaluate(windowPoints)
        val label = evidence.maxByOrNull { it.value }?.key ?: UNTAUGHT_LABEL
        val measurement = PrimitiveMeasurement(label = label, extent = chordLength(windowPoints))

        val absoluteAngle = circularMean(windowObservations.map { it.tangentAngle })
        val relativeAngle = angleDifference(absoluteAngle, previousExitAngle)
        previousExitAngle = absoluteAngle

        return CmpMessage(
            location = centroid(windowPoints),
            morphologicalFeatures =
                MorphologicalFeatures(
                    poseVectors = rotationBasis(relativeAngle),
                    poseFullyDefined = true,
                ),
            nonMorphologicalFeatures =
                object : PrimitiveFeatures {
                    override val measurement = measurement
                    override val startIndex = windowObservations.first().orderInStroke
                    override val endIndex = windowObservations.last().orderInStroke
                    override val strokeIndex = strokeIndex
                },
            confidence = evidence[label] ?: 0f,
            passMessage = true,
            senderId = SENDER_ID,
            senderType = SenderType.SM,
            processFeaturesInLm = true,
        )
    }

    private fun chordLength(points: List<RawPoint>): Float {
        val dx = points.last().x - points.first().x
        val dy = points.last().y - points.first().y
        return sqrt(dx * dx + dy * dy)
    }

    private fun centroid(points: List<RawPoint>): FloatArray {
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.x
            sumY += point.y
        }
        return floatArrayOf(sumX / points.size, sumY / points.size)
    }

    private fun circularMean(angles: List<Float>): Float {
        var sumCos = 0f
        var sumSin = 0f
        for (angle in angles) {
            sumCos += cos(angle)
            sumSin += sin(angle)
        }
        return atan2(sumSin, sumCos)
    }

    private fun rotationBasis(angle: Float): Array<FloatArray> {
        val cosA = cos(angle)
        val sinA = sin(angle)
        return arrayOf(
            floatArrayOf(cosA, sinA),
            floatArrayOf(-sinA, cosA),
        )
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
        const val SENDER_ID = "primitive-lm"

        /** Reported when a window matches nothing taught — a deliberately inert label, never a guessed shape name (see IMPLEMENTATION_PLAN.md). */
        const val UNTAUGHT_LABEL = "unknown"

        /** Floor for evidence before taking its log (see [windowScore]) — avoids `ln(0)`, and caps how harshly a total non-match is penalized. */
        const val MIN_EVIDENCE = 0.01f

        // A primitive shorter than this is not meaningful (see
        // IMPLEMENTATION_PLAN.md's segmentation design) -- needs empirical
        // calibration against real taught primitives and real handwriting,
        // same as every other constant in this pipeline's history.
        const val MIN_WINDOW_LENGTH = 4

        // Deliberately the *whole* per-stroke resample budget, not some
        // smaller cap: a single genuinely simple stroke (e.g. one straight
        // line spanning an entire character) must be representable as ONE
        // primitive. An earlier, smaller cap here forced even a perfectly
        // straight whole-stroke line into two artificial pieces purely
        // because it exceeded the cap -- which then spuriously matched
        // *other* unrelated two-primitive shapes on position (any bent
        // two-segment shape's node centroids land near the same diagonal a
        // straight line's two halves do, once each is independently
        // normalized to its own bounding circle). Segmentation is already
        // scoped per stroke (see [replay]), so the natural upper bound is
        // "the whole stroke," not an arbitrary smaller number.
        const val MAX_WINDOW_LENGTH = StrokePreprocessor.DEFAULT_RESAMPLE_COUNT

        // Charged once per chosen primitive, regardless of its length. With
        // windowScore() now returning *log*-evidence (capped at 0 for a
        // perfect match), covering the same span with more equally-good
        // windows no longer sums to more than one bigger window already
        // does -- this penalty only has to break near-ties toward fewer
        // primitives after that, not fight a runaway positive sum the way
        // an earlier, raw-evidence version of this constant had to. Needs
        // the same empirical calibration as the window-length bounds above.
        const val SEGMENT_PENALTY = 0.05f
    }
}
