@file:Suppress("ktlint:standard:no-empty-file")

package com.eta.tbp.lib.sensor

// Disabled pending a redesign for the new Feature/Location kernel (see
// CmpMessage/GraphNode's move away from MorphologicalFeatures/
// PrimitiveFeatures and FloatArray locations to the generic Feature/
// Location interfaces): this class still constructs CmpMessage with the
// old morphologicalFeatures/nonMorphologicalFeatures params and implements
// an already-deleted PrimitiveFeatures interface, so it no longer compiles.
// The "city" evidence-graph work (see com.eta.tbp.lib.city) deliberately
// left the digit-stroke pipeline (this class, MontyOrchestrator, and their
// tests) commented out rather than porting it, per this branch's direction
// — see MontyOrchestrator.kt's matching note. Kotlin nests block comments,
// so the class body below (including its own KDoc) is safely inert.

/*
import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.orchestrator.StrokeSegmenter
import com.eta.tbp.lib.util.angleDifference
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The concrete `SensorModule<RawTouchObservation>` that turns a stroke's
 * resampled, normalized observations into the primitive `CmpMessage`s
 * [com.eta.tbp.lib.lm.EvidenceGraphLM] consumes — restoring the SM/LM
 * boundary the original, deleted `PrimitiveSensorModule` (see
 * IMPLEMENTATION_PLAN.md §7) provided, after a rewrite briefly folded this
 * class's job directly into [com.eta.tbp.lib.orchestrator.MontyOrchestrator]
 * instead. [PrimitiveGraphLM] supplies the taught, evidence-matched
 * classification this class queries; this class owns *when* and *how* that
 * classification becomes a message stream, mirroring real Monty's own SMs
 * (e.g. `TwoDSensorModule`), which stay concrete `SensorModule` subclasses
 * even when their feature extraction needs more than one raw sample (edge
 * detection, surface-normal fitting) — needing context beyond a single
 * observation doesn't by itself justify inlining a sensor's job into the
 * orchestrator.
 *
 * [step] buffers each observation for the stroke currently in progress and
 * returns a `passMessage = false` placeholder: a segmentation decision
 * fundamentally can't be made per-point, since
 * [com.eta.tbp.lib.orchestrator.StrokeSegmenter]'s global search needs the
 * *whole* stroke's points before it can choose anything (see
 * [com.eta.tbp.lib.orchestrator.MontyOrchestrator]'s own class doc for why
 * that's still true here). [flushStroke] is this class's one deliberate,
 * documented addition beyond the plain [SensorModule] interface — the same
 * spirit as the original `PrimitiveSensorModule`'s `drainTrailingPrimitive()`
 * — run once a whole stroke's observations have all been [step]ped, to
 * actually perform the segmentation search and drain the resulting
 * primitives as a batch of messages, in order.
 *
 * [previousExitAngle] deliberately survives across [flushStroke] calls
 * within the same episode — only [preEpisode] resets it — so a primitive's
 * turn-from-previous encoding stays meaningful across a pen lift between
 * strokes of the same character, the same cross-stroke tracking the
 * original `PrimitiveSensorModule` did.
 */
class PrimitiveSensorModule(
    override val sensorId: String,
    private val primitiveGraphLM: PrimitiveGraphLM,
) : SensorModule<RawTouchObservation> {
    private val strokeBuffer = mutableListOf<RawTouchObservation>()
    private var previousExitAngle = 0f

    override fun step(observation: RawTouchObservation): CmpMessage {
        strokeBuffer.add(observation)
        return CmpMessage(
            location = null,
            feature = null,
            confidence = 0f,
            passMessage = false,
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = false,
        )
    }

    override fun preEpisode() {
        strokeBuffer.clear()
        previousExitAngle = 0f
    }

    override fun postEpisode() {
        strokeBuffer.clear()
    }

    /**
 * Runs [StrokeSegmenter]'s global search over every observation buffered
 * by [step] since the last [flushStroke] (or [preEpisode]) call, and
 * returns one [CmpMessage] per chosen window, in order. Clears the
 * buffer for the next stroke. Empty if nothing was buffered.
 */
    fun flushStroke(strokeIndex: Int): List<CmpMessage> {
        val observations = strokeBuffer.toList()
        strokeBuffer.clear()
        if (observations.isEmpty()) return emptyList()

        val windows =
            StrokeSegmenter.segment(
                pointCount = observations.size,
                minWindowLength = MIN_WINDOW_LENGTH,
                maxWindowLength = MAX_WINDOW_LENGTH,
                segmentPenalty = SEGMENT_PENALTY,
            ) { start, end -> windowScore(observations, start, end) }

        return windows.map { window -> emitPrimitive(observations, window.startIndex, window.endIndex, strokeIndex) }
    }

    /**
 * The *log* of [primitiveGraphLM]'s best evidence for the candidate
 * window `[start, end)` — not the raw evidence. This matters: raw
 * evidence for a clean match against a taught line sits close to 1.0
 * regardless of the window's length (any sub-segment of a straight line
 * is itself a straight line), so summing raw evidence directly would
 * make [StrokeSegmenter] always prefer more, shorter windows whenever
 * each one *also* scores well — no [SEGMENT_PENALTY] can fix that on
 * its own, since a small positive-per-window gain still always wins by
 * using more of them. Log-evidence caps out at 0 for a perfect match, so
 * covering the same span with more equally-good windows sums to *the
 * same* total (not more) — exactly how a real language model's word
 * segmentation naturally prefers fewer, better-fitting words without
 * needing to be told to. [SEGMENT_PENALTY] only has to break near-ties
 * toward fewer primitives after that, not fight a runaway sum.
 * [MIN_EVIDENCE] avoids `ln(0)`.
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

    /** Builds one chosen window's `CmpMessage`, mirroring the shape the original per-point `PrimitiveSensorModule` used to emit. */
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
        val measurement = PrimitiveFeature(label = label, extent = chordLength(windowPoints))

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
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = true,
        )
    }

    private fun chordLength(points: List<RawPoint>): Float {
        val dx = points.last().x - points.first().x
        val dy = points.last().y - points.first().y
        return sqrt(dx * dx + dy * dy)
    }

    private fun centroid(points: List<RawPoint>): FloatLocation {
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.x
            sumY += point.y
        }
        return FloatLocation(sumX / points.size, sumY / points.size)
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

    private companion object {
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
        // primitive. [flushStroke] is scoped to one stroke at a time, so the
        // natural upper bound is "the whole stroke," not an arbitrary
        // smaller number.
        const val MAX_WINDOW_LENGTH = StrokePreprocessor.DEFAULT_RESAMPLE_COUNT

        // Charged once per chosen primitive, regardless of its length. See
        // [windowScore]'s doc for why this only has to break near-ties
        // toward fewer primitives, not fight a runaway positive sum.
        const val SEGMENT_PENALTY = 0.05f
    }
}
*/
