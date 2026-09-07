package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.MorphologicalFeatures
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.util.angleDifference
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which of the two primitive shapes a run segments into, together with the
 * size/shape data a bare type tag wouldn't carry — without this, a short
 * line and a long line pointing the same direction (or a tight arc and a
 * gently-curving one) are indistinguishable to
 * [com.eta.tbp.lib.memory.GraphMatcher]. There's deliberately no separate
 * `PrimitiveType` enum alongside this: an earlier version had both a `type`
 * field and this measurement as two parallel discriminants for the same
 * fact, kept in sync only by "always constructed together" convention, not
 * by anything the compiler checked. Folding type into this sealed
 * hierarchy makes that invariant structural instead of conventional — the
 * variant *is* the type. Each variant's field is whatever that shape is
 * naturally measured by, in the same normalized-space units as
 * [CmpMessage.location] (length, radius) or radians (sweep angle) — not a
 * shared generic "size" so the value stays meaningful on its own. See
 * IMPLEMENTATION_PLAN.md §7 for the full history of this merge.
 *
 * There's no separate `CORNER` type — see [PrimitiveSensorModule]'s class
 * doc for why.
 */
sealed interface PrimitiveMeasurement {
    /** A straight run; [length] is the chord between its first and last point (equivalent to arc length for a line by definition). */
    data class Line(
        val length: Float,
    ) : PrimitiveMeasurement

    /**
     * A curved run; [sweepAngle] is the signed total rotation swept around
     * the fitted circle's center, in radians — how much of the circle this
     * run traces. [radius] is that circle's radius — without it, a tight
     * quarter-turn and a huge, gently-curving quarter-turn would report the
     * same sweep angle and be indistinguishable in size, the same gap that
     * motivated adding a line's [length] in the first place.
     */
    data class Arc(
        val sweepAngle: Float,
        val radius: Float,
    ) : PrimitiveMeasurement
}

interface PrimitiveFeatures {
    val measurement: PrimitiveMeasurement
    val startIndex: Int
    val endIndex: Int
    val strokeIndex: Int
}

/**
 * Tier 1: segments the per-point stream coming from [TouchSensorModule]
 * into line/arc runs by whole-window geometric fit, not per-point
 * threshold comparisons.
 *
 * This used to implement `LearningModule<Unit>`, satisfying that interface
 * without ever really *learning* anything: `state()` was always `Unit`,
 * `receiveVotes`/`sendOutVote` were permanent no-ops, `setExperimentMode`
 * had no effect. Checking real Monty's own `SensorModule` shows it
 * computes features like curvature via fixed least-squares math too — this
 * segmentation work is architecturally SM-shaped, not LM-shaped, so it's
 * modeled as a `SensorModule` now: [com.eta.tbp.lib.lm.CharacterGraphLM]
 * is the sole `LearningModule` in this app's hierarchy, matching Monty's
 * own common single-SM/single-LM baseline configuration (see
 * IMPLEMENTATION_PLAN.md §3.4/§4). The move also sheds every vestigial
 * method that was only ever a no-op under `LearningModule` — `receiveVotes`,
 * `sendOutVote`, `state`/`loadState`, `setExperimentMode` don't exist on
 * `SensorModule` at all, because Monty's own SMs don't vote, don't persist
 * learned state, and don't have training-vs-eval behavior either.
 *
 * A wrinkle real Monty's SM doesn't have to deal with: [SensorModule.step]
 * is called once per observation and must return exactly one [CmpMessage]
 * — but a line/arc run can span many observations before it's complete, so
 * most steps have nothing new to report ([CmpMessage.passMessage] = false,
 * mirroring Monty's own `use_state` per-message flag for "nothing
 * interesting this step" — [com.eta.tbp.lib.lm.CharacterGraphLM.matchingStep]
 * already skips messages with `passMessage = false`), and one run may
 * still be open when the episode ends. [SensorModule.postEpisode] can't
 * return a value to flush that trailing primitive through the normal path,
 * so [drainTrailingPrimitive] is this class's one deliberate, documented
 * addition beyond the plain interface — the same category of honest
 * extension as `CharacterGraphLM.teach()`/`evidenceSnapshot()`. This only
 * works because, empirically, [step] never needs to emit more than one
 * finished primitive per call (verified by [PrimitiveSensorModuleTest]):
 * a stroke-boundary flush always leaves the buffer empty, so the same
 * point can never *also* trigger a break immediately after.
 *
 * A candidate run keeps growing as long as its points *as a whole* still
 * fit within [WIDTH_TOLERANCE] of one of two idealized shapes — one shared
 * tolerance, not two differently-scaled criteria:
 * - **line**: fit a best-fit line through the points (the major axis of
 *   their PCA covariance); every point must lie within `WIDTH_TOLERANCE`
 *   of that line — "does this fit inside a thin rectangle."
 * - **arc**: fit a best-fit circle through the points (least-squares
 *   algebraic circle fit); every point must lie within `WIDTH_TOLERANCE`
 *   of that circle's circumference — "does this fit inside a thin annulus
 *   (a donut)."
 *
 * This replaced an earlier design that classified lines by the aspect
 * ratio of the point cloud's principal-axis spreads, and arcs by whether
 * curvature's mean/standard deviation looked "roughly constant" — see git
 * history/IMPLEMENTATION_PLAN.md §7 for that saga. The curvature-based arc
 * test had a real, structural flaw the distance-based one doesn't: a
 * resampled circle's *curvature* (turning angle per resampled step) scales
 * with how tight the circle is, so a small, tight loop measured curvature
 * close to what a genuine sharp corner measures — but a fitted circle's
 * *residual* (how far points sit from the fitted circumference) is near
 * zero for a clean loop of *any* radius, tight or wide, since a circle fit
 * doesn't care how fast the tangent turns, only how well the points
 * actually lie on some circle. This is also why the two shapes can now
 * share one tolerance: both tests are "distance from an idealized curve,"
 * just a straight one or a round one.
 *
 * That distance-from-fit test alone isn't quite enough on its own, though
 * — a circle has only 3 degrees of freedom, so a *short* window (few
 * points) can trivially find some large-radius circle passing within
 * `WIDTH_TOLERANCE` of a moderate corner's two legs, "absorbing" the bend
 * into a barely-curving arc rather than rejecting it. [MAX_LOCAL_TURN]
 * catches that locally, applied per point rather than per window: any
 * point whose own curvature exceeds it is discarded outright — joining
 * neither run — rather than being folded into whichever aggregate fit it
 * happened to land in. It's calibrated between a tight loop's own measured
 * curvature (which must NOT trip it, or the bug above reappears) and the
 * shallowest corner this app still needs to catch, so it's a narrower
 * safety net than the old curvature-based classification was, not a
 * reintroduction of it — the *primary* test for both shapes is still the
 * distance-from-fit one.
 *
 * There's no third `CORNER` type. A sharp bend is just the boundary
 * between two `LINE`/`ARC` runs — the angle between two adjacent nodes'
 * own recorded poses already carries exactly how sharp that bend was
 * (`GraphMatcher` re-baselines and compares consecutive nodes' angles
 * directly), so a dedicated corner node added no information a `LINE`-
 * `LINE` pair didn't already have. Worse, an earlier design tried to
 * synthesize a corner at stroke boundaries (pen lifts) too, to keep a
 * hand-drawn corner's graph the same whether drawn as one continuous
 * stroke or as two separate strokes meeting at a point — but that requires
 * knowing which *other* stroke (drawn in any order, from either end) a
 * given stroke's endpoint connects to, which an online, per-point,
 * single-pass segmenter fundamentally can't determine (that's a whole-
 * character, order/direction-independent question, not a local one).
 * Dropping the dedicated type sidesteps the problem entirely: a corner
 * mid-stroke and a corner split across two strokes (in any order, either
 * direction) now produce the *same* `LINE`-`LINE` (or `LINE`-`ARC`, etc.)
 * sequence, because there's nothing left that only fires for one of them.
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
class PrimitiveSensorModule(
    override val sensorId: String,
) : SensorModule<CmpMessage> {
    private val runBuffer = mutableListOf<DecodedPoint>()
    private var previousExitAngle = 0f
    private var trailingPrimitive: CmpMessage? = null

    override fun step(observation: CmpMessage): CmpMessage {
        val point = decode(observation)
        var finished: CmpMessage? = null

        // A pen lift between strokes of the same character is a strokeIndex
        // discontinuity riding in this same message stream, not a separate
        // lifecycle scope (see class doc) — force-break whatever run is
        // open, so a run never silently spans a stroke boundary.
        // previousExitAngle deliberately keeps tracking across the gap;
        // only the run buffer resets.
        if (runBuffer.isNotEmpty() && runBuffer.last().strokeIndex != point.strokeIndex) {
            finished = finalizeRun()
        }

        // A point whose own curvature is a sharp kink doesn't belong to ANY
        // line/arc run -- see class doc on why a distance-from-idealized-
        // shape fit alone isn't quite enough for short windows, and why
        // this veto is calibrated well above a tight loop's curvature so it
        // doesn't reintroduce the bug that motivated dropping curvature as
        // the arc test's primary signal. Discarding the point outright
        // (rather than starting a new run WITH it) matters: a real corner's
        // turn typically spans more than one resampled point, and if each
        // of those sharp points had to anchor its own tentative run, no two
        // of them could ever coexist in the same buffer (each would
        // immediately veto the other), producing a cascade of spurious
        // degenerate single-point "line" primitives right where the bend
        // is, instead of two clean lines with nothing between them.
        if (abs(point.curvature) > MAX_LOCAL_TURN) {
            if (finished == null && runBuffer.isNotEmpty()) finished = finalizeRun()
            return finished ?: noPrimitiveYet()
        }

        // Nothing to test yet with a single point -- always start a run.
        if (runBuffer.isEmpty()) {
            runBuffer.add(point)
            return finished ?: noPrimitiveYet()
        }

        val tentative = runBuffer + point
        if (fitsAsLine(tentative) || fitsAsArc(tentative)) {
            runBuffer.add(point)
        } else {
            // Unreachable when `finished` is already set: a stroke-boundary
            // flush just above always leaves runBuffer empty, which takes
            // the "start a new run" branch above instead of reaching here.
            finished = finalizeRun()
            runBuffer.add(point)
        }
        return finished ?: noPrimitiveYet()
    }

    /** Resets run-tracking state for a new character (not a new stroke — see class doc). */
    override fun preEpisode() {
        runBuffer.clear()
        previousExitAngle = 0f
        trailingPrimitive = null
    }

    /** Flushes whatever run is still open at the end of the character, for [drainTrailingPrimitive] to retrieve. */
    override fun postEpisode() {
        if (runBuffer.isNotEmpty()) {
            trailingPrimitive = finalizeRun()
        }
    }

    /**
     * The trailing primitive flushed by [postEpisode] when the character
     * ended mid-run, if any — see class doc on why [SensorModule]'s plain
     * interface can't carry this. Consumed once, then cleared.
     */
    fun drainTrailingPrimitive(): CmpMessage? {
        val result = trailingPrimitive
        trailingPrimitive = null
        return result
    }

    private fun noPrimitiveYet(): CmpMessage =
        CmpMessage(
            location = null,
            morphologicalFeatures = null,
            nonMorphologicalFeatures = Unit,
            confidence = 0f,
            passMessage = false,
            senderId = sensorId,
            senderType = SenderType.SM,
            processFeaturesInLm = false,
        )

    /** Every point within [WIDTH_TOLERANCE] of the best-fit line through them all — "fits inside a thin rectangle." */
    private fun fitsAsLine(points: List<DecodedPoint>): Boolean {
        val n = points.size
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.position[0]
            sumY += point.position[1]
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var covXX = 0f
        var covYY = 0f
        var covXY = 0f
        for (point in points) {
            val dx = point.position[0] - meanX
            val dy = point.position[1] - meanY
            covXX += dx * dx
            covYY += dy * dy
            covXY += dx * dy
        }

        // Angle of the covariance matrix's major eigenvector -- the best-fit
        // line's direction through the centroid.
        val majorAxisAngle = 0.5f * atan2(2f * covXY, covXX - covYY)
        val perpX = -sin(majorAxisAngle)
        val perpY = cos(majorAxisAngle)

        val maxPerpDistance =
            points.maxOf { point ->
                abs((point.position[0] - meanX) * perpX + (point.position[1] - meanY) * perpY)
            }
        return maxPerpDistance <= WIDTH_TOLERANCE
    }

    /** Every point within [WIDTH_TOLERANCE] of a best-fit circle's circumference — "fits inside a thin donut." */
    private fun fitsAsArc(points: List<DecodedPoint>): Boolean {
        val circle = fitCircle(points) ?: return false
        return residualWithinTolerance(points, circle)
    }

    private fun residualWithinTolerance(
        points: List<DecodedPoint>,
        circle: Circle,
    ): Boolean =
        points.maxOf { point ->
            abs(distanceTo(point.position, circle) - circle.radius)
        } <= WIDTH_TOLERANCE

    /**
     * Least-squares algebraic circle fit (Kåsa's method), computed in
     * centered coordinates for numerical stability. Returns `null` for
     * near-collinear points, where no circle is meaningfully determined
     * (the normal equations become singular) — callers treat that the same
     * as "doesn't fit an arc," letting [fitsAsLine] handle straight runs.
     */
    private fun fitCircle(points: List<DecodedPoint>): Circle? {
        val n = points.size
        if (n < MIN_POINTS_FOR_CIRCLE_FIT) return null

        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.position[0]
            sumY += point.position[1]
        }
        val meanX = sumX / n
        val meanY = sumY / n

        var suu = 0.0
        var svv = 0.0
        var suv = 0.0
        var suuu = 0.0
        var svvv = 0.0
        var suuv = 0.0
        var suvv = 0.0
        for (point in points) {
            val u = (point.position[0] - meanX).toDouble()
            val v = (point.position[1] - meanY).toDouble()
            suu += u * u
            svv += v * v
            suv += u * v
            suuu += u * u * u
            svvv += v * v * v
            suuv += u * u * v
            suvv += u * v * v
        }

        val det = suu * svv - suv * suv
        if (abs(det) < DETERMINANT_EPSILON) return null

        val pu = suuu + suvv
        val pv = suuv + svvv
        val dCoefficient = (suv * pv - svv * pu) / det
        val eCoefficient = (suv * pu - suu * pv) / det
        val fCoefficient = -(suu + svv) / n

        val centerU = -dCoefficient / 2.0
        val centerV = -eCoefficient / 2.0
        val radiusSquared = centerU * centerU + centerV * centerV - fCoefficient
        if (radiusSquared <= 0.0 || !radiusSquared.isFinite()) return null

        return Circle(
            centerX = (meanX + centerU).toFloat(),
            centerY = (meanY + centerV).toFloat(),
            radius = sqrt(radiusSquared).toFloat(),
        )
    }

    private fun distanceTo(
        position: FloatArray,
        circle: Circle,
    ): Float {
        val dx = position[0] - circle.centerX
        val dy = position[1] - circle.centerY
        return sqrt(dx * dx + dy * dy)
    }

    private data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
    )

    /**
     * [points] is already known to satisfy [fitsAsLine] or [fitsAsArc] as a
     * whole (see [step]); this picks which shape to report and measures it,
     * reusing the same circle fit for both instead of fitting twice. The
     * returned variant (`Line`/`Arc`) doubles as the primitive's type — see
     * [PrimitiveMeasurement]'s class doc for why there's no separate enum.
     */
    private fun classify(points: List<DecodedPoint>): PrimitiveMeasurement {
        val circle = fitCircle(points)
        val isArc = circle != null && residualWithinTolerance(points, circle) && !fitsAsLine(points)
        return if (isArc) {
            PrimitiveMeasurement.Arc(sweepAngle = sweepAngleOf(points, circle), radius = circle.radius)
        } else {
            PrimitiveMeasurement.Line(length = chordLength(points))
        }
    }

    /** Finalizes the buffered run into a primitive message, deciding its type only now that it's complete. */
    private fun finalizeRun(): CmpMessage {
        val points = runBuffer.toList()
        val message = emitPrimitive(classify(points), points)
        runBuffer.clear()
        return message
    }

    /** Straight-line distance between the run's first and last point — its chord (see [PrimitiveMeasurement.Line]). */
    private fun chordLength(points: List<DecodedPoint>): Float {
        val dx = points.last().position[0] - points.first().position[0]
        val dy = points.last().position[1] - points.first().position[1]
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Signed total rotation around [circle]'s center, accumulated as wrapped
     * per-step deltas rather than a single first-to-last angle difference —
     * that's what lets this correctly report more than half a turn (e.g. the
     * tight-full-loop case, close to a full 2*PI) instead of folding it into
     * the shorter apparent angle a single wraparound-unaware subtraction
     * would give.
     */
    private fun sweepAngleOf(
        points: List<DecodedPoint>,
        circle: Circle,
    ): Float {
        val angles = points.map { point -> atan2(point.position[1] - circle.centerY, point.position[0] - circle.centerX) }
        var total = 0f
        for (i in 1 until angles.size) {
            total += angleDifference(angles[i], angles[i - 1])
        }
        return total
    }

    private fun emitPrimitive(
        measurement: PrimitiveMeasurement,
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
                    override val measurement = measurement
                    override val startIndex = points.first().orderInStroke
                    override val endIndex = points.last().orderInStroke
                    override val strokeIndex = points.first().strokeIndex
                },
            confidence = 1f,
            passMessage = true,
            senderId = sensorId,
            senderType = SenderType.SM,
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
                "PrimitiveSensorModule observations must carry StrokeFeatures as nonMorphologicalFeatures. Found: ${nonMorphologicalFeatures::class.simpleName}",
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
        // Calibrated against measured data (PrimitiveSensorModuleTest),
        // not hand-picked -- see class doc for the whole-window design.

        /** Shared by both fit tests: how far (in normalized-space units) a point may sit from the idealized line/circle. */
        const val WIDTH_TOLERANCE = 0.05f

        /** Fewer points than this can't meaningfully determine a circle (3 points always lie on some circle, exactly). */
        const val MIN_POINTS_FOR_CIRCLE_FIT = 3

        /** Below this, the circle-fit normal equations are too close to singular (near-collinear points) to trust. */
        const val DETERMINANT_EPSILON = 1e-8

        // A circle has only 3 degrees of freedom, so a short window can
        // trivially find SOME large-radius circle that passes within
        // WIDTH_TOLERANCE of a moderate corner's two legs -- distance-from-
        // fit alone isn't enough with few points. This veto catches that
        // locally, using point.curvature -- true differential curvature
        // (radians per unit normalized length, roughly 1/radius; see
        // StrokePreprocessor.tangentsAndCurvatures's class doc), NOT a bare
        // per-resampled-step turning angle. That distinction matters: an
        // earlier version compared a bare turning angle against a threshold
        // tuned on single-primitive synthetic shapes, which broke down on a
        // real multi-primitive stroke -- the same true semicircle measures a
        // much smaller turning angle per step when it gets the *whole*
        // DEFAULT_RESAMPLE_COUNT budget to itself than when it shares that
        // fixed budget with another primitive in the same stroke (half the
        // points over the same turn = double the apparent per-step angle),
        // so a perfectly smooth arc in a multi-primitive character could
        // spuriously exceed a threshold calibrated against single-primitive
        // test shapes and fragment into meaningless line segments (see
        // IMPLEMENTATION_PLAN.md §7 for the real-drawing repro). True
        // curvature doesn't have this problem: it's normalized by the arc
        // length each turning-angle sample spans, so a given radius reads
        // the same regardless of how many points happen to be spent
        // resampling it.
        //
        // Calibrated between the largest curvature a legitimate arc this
        // app already needs to support should show (a tight full loop or a
        // bare semicircle, both ~1.0-1.2, and a 170-degree near-straight
        // bend at ~1.3 -- none of these are "corners" and must NOT trip
        // this) and the smallest curvature the shallowest corner this app
        // still needs to catch shows (~150 degrees interior, ~4.0 measured
        // -- see PrimitiveSensorModuleTest's corner-angle sweep). The gap
        // here (~1.3 to ~4.0) is far more comfortable than the old bare-
        // angle version's (~15 to ~20 degrees) precisely because true
        // curvature actually separates "smooth, however tight" from
        // "genuinely sharp," where a resample-density-dependent angle only
        // accidentally did so for single-primitive shapes.
        const val MAX_LOCAL_TURN = 2.2f // radians per unit normalized length (~1/0.45 -- see above)
    }
}
