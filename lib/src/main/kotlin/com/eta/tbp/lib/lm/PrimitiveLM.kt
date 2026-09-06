package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.sensor.StrokeFeatures
import com.eta.tbp.lib.util.angleDifference
import kotlin.math.abs
import kotlin.math.atan2
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
        val isLineConsistent =
            abs(angleDifference(point.tangentAngle, runBuffer.first().tangentAngle)) <= LINE_ANGLE_TOLERANCE
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
        const val CORNER_CURVATURE_THRESHOLD = 0.6f
        const val MIN_ARC_CURVATURE = 0.05f
        const val ARC_CURVATURE_TOLERANCE = 0.08f
        const val LINE_ANGLE_TOLERANCE = 0.2f
    }
}
