package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.sensor.StrokeFeatures
import com.eta.tbp.lib.util.angleDifference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** The three primitive shapes a stroke segments into. */
enum class PrimitiveType { LINE, ARC, CORNER }

interface PrimitiveFeatures {
    val type: PrimitiveType
    val startIndex: Int
    val endIndex: Int
    val strokeIndex: Int
}

/**
 * Tier 1: segments the per-point stream coming from a [com.eta.tbp.lib.sensor.SensorModule]
 * into line/arc/corner runs by tangent-angle stability.
 *
 * Online by design, matching the orchestrator's per-point step loop
 * (`matchingStep`/`getOutput` called once per point, not once per
 * stroke): a run accumulates across calls until a point breaks its
 * hypothesis, at which point the finished run is queued for the next
 * [getOutput] call(s) as a [CmpMessage]. A run ending and a corner can
 * both complete on the same incoming point, so finished primitives are
 * queued rather than held in a single slot — a step with nothing new
 * returns `null`, matching Monty's `get_output() -> Message | None`.
 *
 * "Episode" is one full character's worth of points, potentially spanning
 * several strokes — uniform with [com.eta.tbp.lib.lm.CharacterGraphLM] and
 * matching real Monty's own episode boundary, which resets every SM/LM
 * identically regardless of tier (see IMPLEMENTATION_PLAN.md §3.6). A pen
 * lift between strokes of the same character is *not* a separate episode:
 * it's an ordinary `strokeIndex` discontinuity in the message stream,
 * force-breaking whatever run is open in [step] — the same way Monty
 * signals sensor discontinuities (on/off-object) as per-message data
 * (`use_state`) rather than a distinct lifecycle scope.
 */
class PrimitiveLM(
    override val lmId: String,
) : LearningModule<Unit> {
    private val runBuffer = mutableListOf<DecodedPoint>()
    private var runType: PrimitiveType? = null
    private var previousExitAngle = 0f
    private val pendingOutputs = ArrayDeque<CmpMessage>()

    override fun matchingStep(messages: List<CmpMessage>) {
        for (message in messages) {
            step(decode(message))
        }
    }

    override fun receiveVotes(votes: List<Any>) {
        // No-op in v1: a single LM per tier, nothing to cross-check yet.
    }

    override fun sendOutVote(): Any? = null // No siblings to vote with in v1.

    override fun getOutput(): CmpMessage? = pendingOutputs.removeFirstOrNull()

    /** Resets run-tracking state for a new character (not a new stroke — see class doc). */
    override fun preEpisode() {
        runBuffer.clear()
        runType = null
        previousExitAngle = 0f
        pendingOutputs.clear()
    }

    /** Flushes whatever run is still open at the end of the character. Strokes within it were already force-segmented at their boundaries by [step]. */
    override fun postEpisode() {
        if (runBuffer.isNotEmpty()) {
            pendingOutputs.addLast(finalizeRun())
        }
    }

    override fun setExperimentMode(mode: ExperimentMode) {
        // No behavioral difference yet: v1 has no training-only bookkeeping.
    }

    override fun state(): Unit = Unit

    override fun loadState(state: Unit) {
        // Stateless across episodes: nothing to restore.
    }

    private fun step(point: DecodedPoint) {
        // A pen lift between strokes of the same character is a strokeIndex
        // discontinuity riding in this same message stream, not a separate
        // lifecycle scope (see class doc) — force-break whatever run is
        // open, the same code path as a corner ending a run, so a run never
        // silently spans a stroke boundary. previousExitAngle deliberately
        // keeps tracking across the gap; only the run buffer/type reset.
        if (runBuffer.isNotEmpty() && runBuffer.last().strokeIndex != point.strokeIndex) {
            pendingOutputs.addLast(finalizeRun())
        }

        // A real corner's turn typically lands on more than one resampled
        // point (arc-length resampling plus a central-difference curvature
        // spreads it out), so consecutive high-curvature points merge into
        // one CORNER run rather than each emitting its own primitive.
        if (abs(point.curvature) >= CORNER_CURVATURE_THRESHOLD) {
            if (runType != PrimitiveType.CORNER) {
                if (runBuffer.isNotEmpty()) pendingOutputs.addLast(finalizeRun())
                runType = PrimitiveType.CORNER
            }
            runBuffer.add(point)
            return
        }

        if (runType == PrimitiveType.CORNER) {
            pendingOutputs.addLast(finalizeRun())
            runBuffer.add(point)
            return
        }

        if (runBuffer.size < 2) {
            runBuffer.add(point)
            return
        }

        when (val decision = decide(point)) {
            is RunDecision.Continue -> {
                runType = decision.lockedType
                runBuffer.add(point)
            }

            RunDecision.Break -> {
                pendingOutputs.addLast(finalizeRun())
                runBuffer.add(point)
            }
        }
    }

    /**
     * Whether [point] continues the buffered run. Undetermined runs (< 2
     * points don't reach here) can still be ambiguous between line/arc —
     * that's [RunDecision.Continue] with a `null` locked type, not a break.
     */
    private fun decide(point: DecodedPoint): RunDecision {
        // Compared against a recent window's circular-mean tangent, not a
        // single reference point (e.g. runBuffer.first()) and not the
        // WHOLE run either: real touch input has per-point jitter that
        // mostly cancels out in an average but would otherwise poison every
        // subsequent comparison if the ONE reference sample happened to be
        // noisy, spuriously breaking a genuinely straight stroke into
        // several short "line" runs. But averaging the whole run creates
        // its own bug: tangent is curvature's running integral, so a
        // whole-run average's sensitivity to a real, sustained-but-shallow
        // curve keeps shrinking the longer the run has already gone on --
        // eventually letting even a curvature well below MIN_ARC_CURVATURE
        // accumulate enough drift to break LINE, with nothing to hand off
        // to (ARC's own threshold was never reached), producing a spurious
        // LINE/LINE split with no CORNER or ARC between them. A bounded
        // recent window (LINE_TREND_WINDOW, derived from this tolerance and
        // MIN_ARC_CURVATURE) keeps that sensitivity constant regardless of
        // run length instead.
        val averageTangent = circularMean(runBuffer.takeLast(LINE_TREND_WINDOW).map { it.tangentAngle })
        val isLineConsistent = abs(angleDifference(point.tangentAngle, averageTangent)) <= LINE_ANGLE_TOLERANCE
        val averageCurvature = runBuffer.map { it.curvature }.average().toFloat()
        val isArcConsistent =
            abs(averageCurvature) >= MIN_ARC_CURVATURE &&
                abs(point.curvature - averageCurvature) <= ARC_CURVATURE_TOLERANCE

        return when (runType) {
            PrimitiveType.LINE -> {
                if (isLineConsistent) RunDecision.Continue(PrimitiveType.LINE) else RunDecision.Break
            }

            PrimitiveType.ARC -> {
                if (isArcConsistent) RunDecision.Continue(PrimitiveType.ARC) else RunDecision.Break
            }

            // Unreachable: step() handles a CORNER-typed run (and the point that ends it)
            // before decide() is ever called.
            PrimitiveType.CORNER -> {
                RunDecision.Break
            }

            null -> {
                when {
                    isLineConsistent && isArcConsistent -> RunDecision.Continue(null)
                    isLineConsistent -> RunDecision.Continue(PrimitiveType.LINE)
                    isArcConsistent -> RunDecision.Continue(PrimitiveType.ARC)
                    else -> RunDecision.Break
                }
            }
        }
    }

    private sealed interface RunDecision {
        data class Continue(
            val lockedType: PrimitiveType?,
        ) : RunDecision

        object Break : RunDecision
    }

    /** Finalizes the buffered run into a primitive message, clearing the buffer for the next run. */
    private fun finalizeRun(): CmpMessage {
        val type = runType ?: PrimitiveType.LINE
        val message = emitPrimitive(type, runBuffer.toList())
        runBuffer.clear()
        runType = null
        return message
    }

    private fun emitPrimitive(
        type: PrimitiveType,
        points: List<DecodedPoint>,
    ): CmpMessage {
        val absoluteAngle = circularMean(points.map { it.tangentAngle })
        val relativeAngle = angleDifference(absoluteAngle, previousExitAngle)
        previousExitAngle = absoluteAngle

        return CmpMessage(
            location = centroid(points),
            morphologicalFeatures =
                MorphologicalFeatures(
                    poseVectors = rotationBasis(relativeAngle),
                    poseFullyDefined = true,
                ),
            nonMorphologicalFeatures =
                object : PrimitiveFeatures {
                    override val type = type
                    override val startIndex = points.first().orderInStroke
                    override val endIndex = points.last().orderInStroke
                    override val strokeIndex = points.first().strokeIndex
                },
            confidence = 1f,
            passMessage = true,
            senderId = lmId,
            senderType = SenderType.LM,
            processFeaturesInLm = true,
        )
    }

    private fun centroid(points: List<DecodedPoint>): FloatArray {
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.position[0]
            sumY += point.position[1]
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

    private fun decode(message: CmpMessage): DecodedPoint {
        val poseVectors = requireNotNull(message.getPoseVectors()) { "SensorModule messages must carry a pose" }
        val tangentAngle = atan2(poseVectors[0][1], poseVectors[0][0])

        val nonMorphologicalFeatures = message.nonMorphologicalFeatures
        if (nonMorphologicalFeatures !is StrokeFeatures) {
            throw IllegalArgumentException(
                "SensorModule messages must carry StrokeFeatures as nonMorphologicalFeatures. Found: ${nonMorphologicalFeatures::class.simpleName}",
            )
        }

        return DecodedPoint(
            position = requireNotNull(message.location) { "SensorModule messages must carry a location" },
            tangentAngle = tangentAngle,
            curvature = message.nonMorphologicalFeatures.curvature,
            strokeIndex = message.nonMorphologicalFeatures.strokeIndex,
            orderInStroke = message.nonMorphologicalFeatures.orderInStroke,
        )
    }

    private data class DecodedPoint(
        val position: FloatArray,
        val tangentAngle: Float,
        val curvature: Float,
        val strokeIndex: Int,
        val orderInStroke: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DecodedPoint

            if (tangentAngle != other.tangentAngle) return false
            if (curvature != other.curvature) return false
            if (strokeIndex != other.strokeIndex) return false
            if (orderInStroke != other.orderInStroke) return false
            if (!position.contentEquals(other.position)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = tangentAngle.hashCode()
            result = 31 * result + curvature.hashCode()
            result = 31 * result + strokeIndex
            result = 31 * result + orderInStroke
            result = 31 * result + position.contentHashCode()
            return result
        }
    }

    private companion object {
        // Calibrated against MEASURED curvature after resample+smooth, not
        // the shape's raw geometric angle -- the two are very different
        // scales, and the relationship isn't even linear-friendly to eyeball:
        // sweeping a two-straight-legs corner's interior angle (see
        // PrimitiveLMTest's corner-angle sweep) measures ~66 degrees of
        // curvature for a 90-degree (right-angle) corner, ~41 degrees for a
        // 120-degree corner, but only ~20 degrees for a 150-degree corner --
        // a "gentle" corner (any real handwriting has plenty of these, not
        // just right angles) attenuates fast. A deliberately-drawn
        // semicircle measures only ~7-8 degrees of curvature per point at
        // the same density (see StrokePreprocessorTest's curvature tests).
        // Threshold values tuned to real geometric angles directly (e.g.
        // ~90 degrees for a right angle) miss real corners/arcs entirely.
        // ~15 degrees: comfortably above arc-range curvature (~8 degrees
        // measured), while still catching corners down to ~150 degrees
        // interior (~20 degrees measured) -- much shallower than 40 degrees
        // (~120 degrees interior) caught before, which missed the very
        // common case of a corner that isn't close to a right angle.
        const val CORNER_CURVATURE_THRESHOLD = PI / 12f

        // ~2.5 degrees: below the ~7-8-degree measured semicircle curvature, above smoothed noise.
        const val MIN_ARC_CURVATURE = PI / 72f

        // ~10 degrees: covers the measured semicircle's curvature spread (~3-8 degrees).
        const val ARC_CURVATURE_TOLERANCE = PI / 18f

        const val LINE_ANGLE_TOLERANCE = PI / 12f // 15 degrees

        /**
         * How many of the run's most recent points [decide]'s line-
         * consistency check averages against, instead of the whole run —
         * see [decide]'s doc for why a whole-run average opens a "dead
         * zone" where a real, shallow, sustained curve can drift far enough
         * to break LINE without its curvature ever reaching
         * [MIN_ARC_CURVATURE], producing a spurious LINE/LINE split with
         * nothing in between (no CORNER, no ARC). Derived from the two
         * thresholds rather than hand-picked, so retuning either one can't
         * silently reopen that gap: a curvature of [MIN_ARC_CURVATURE]
         * deviates roughly `curvature * (window + 1) / 2` from a window-of-
         * `window` circular mean, so this solves for the window where that
         * deviation just reaches [LINE_ANGLE_TOLERANCE] — the point where a
         * curvature right at the ARC floor reliably breaks LINE in time to
         * hand off to ARC, regardless of how long the run has already gone on.
         */
        val LINE_TREND_WINDOW = ceil(2 * LINE_ANGLE_TOLERANCE / MIN_ARC_CURVATURE).toInt().coerceAtLeast(2)
    }
}
